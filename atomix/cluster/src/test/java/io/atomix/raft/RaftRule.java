/*
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.raft;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import io.atomix.cluster.ClusterMembershipService;
import io.atomix.cluster.MemberId;
import io.atomix.raft.RaftServer.Builder;
import io.atomix.raft.RaftServer.Role;
import io.atomix.raft.cluster.RaftMember;
import io.atomix.raft.impl.RaftContext;
import io.atomix.raft.primitive.TestMember;
import io.atomix.raft.protocol.TestRaftProtocolFactory;
import io.atomix.raft.protocol.TestRaftServerProtocol;
import io.atomix.raft.roles.LeaderRole;
import io.atomix.raft.snapshot.InMemorySnapshot;
import io.atomix.raft.snapshot.TestSnapshotStore;
import io.atomix.raft.storage.RaftStorage;
import io.atomix.raft.storage.log.IndexedRaftLogEntry;
import io.atomix.raft.storage.log.RaftLogReader.Mode;
import io.atomix.raft.zeebe.EntryValidator;
import io.atomix.raft.zeebe.NoopEntryValidator;
import io.atomix.raft.zeebe.ZeebeLogAppender;
import io.atomix.utils.AbstractIdentifier;
import io.atomix.utils.concurrent.SingleThreadContext;
import io.atomix.utils.concurrent.ThreadContext;
import io.camunda.zeebe.snapshots.PersistedSnapshot;
import io.camunda.zeebe.snapshots.PersistedSnapshotListener;
import io.camunda.zeebe.snapshots.PersistedSnapshotStore;
import io.camunda.zeebe.util.FileUtil;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.apache.commons.lang3.RandomStringUtils;
import org.awaitility.Awaitility;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

public final class RaftRule extends ExternalResource {

  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private volatile int nextId;
  private volatile List<RaftMember> members;
  private Map<String, Long> memberLog;
  private final Map<String, RaftServer> servers = new HashMap<>();
  private volatile TestRaftProtocolFactory protocolFactory;
  private volatile ThreadContext context;
  private Path directory;
  private final int nodeCount;
  private volatile long highestCommit;
  private final AtomicReference<CommitAwaiter> commitAwaiterRef = new AtomicReference<>();
  private final Map<String, AtomicReference<CountDownLatch>> compactAwaiters = new HashMap<>();
  private long position;
  private EntryValidator entryValidator = new NoopEntryValidator();
  // Keep a reference to the snapshots to ensure they are persisted across the restarts.
  private Map<String, AtomicReference<InMemorySnapshot>> snapshots;
  private Map<String, TestSnapshotStore> snapshotStores;

  private RaftRule(final int nodeCount) {
    this.nodeCount = nodeCount;
  }

  public static RaftRule withBootstrappedNodes(final int nodeCount) {
    if (nodeCount < 1) {
      throw new IllegalArgumentException("Expected to have at least one node to configure.");
    }
    return new RaftRule(nodeCount);
  }

  public static RaftRule withoutNodes() {
    return new RaftRule(-1);
  }

  public RaftRule setEntryValidator(final EntryValidator entryValidator) {
    this.entryValidator = entryValidator;
    return this;
  }

  @Override
  public Statement apply(final Statement base, final Description description) {
    final var statement = super.apply(base, description);
    return temporaryFolder.apply(statement, description);
  }

  @Override
  protected void before() throws Throwable {
    directory = temporaryFolder.newFolder().toPath();

    position = 0;
    members = new ArrayList<>();
    memberLog = new ConcurrentHashMap<>();
    snapshotStores = new HashMap<>();
    snapshots = new HashMap<>();
    nextId = 0;
    context = new SingleThreadContext("raft-test-messaging-%d");
    protocolFactory = new TestRaftProtocolFactory(context);

    if (nodeCount > 0) {
      createServers(nodeCount);
    }
  }

  @Override
  protected void after() {
    try {
      CompletableFuture.allOf(
              servers.values().stream().map(RaftServer::shutdown).toArray(CompletableFuture[]::new))
          .get(30, TimeUnit.SECONDS);
    } catch (final Exception e) {
      // we failed to shutdown server
    }

    servers.clear();
    context.close();
    context = null;
    members.clear();
    compactAwaiters.clear();
    nextId = 0;
    protocolFactory = null;
    highestCommit = 0;
    commitAwaiterRef.set(null);
    memberLog.clear();
    memberLog = null;
    position = 0;
    directory = null;
  }

  /**
   * Returns the next server address.
   *
   * @param type The startup member type.
   * @return The next server address.
   */
  private RaftMember nextMember(final RaftMember.Type type) {
    return new TestMember(nextNodeId(), type);
  }

  /**
   * Returns the next unique member identifier.
   *
   * @return The next unique member identifier.
   */
  private MemberId nextNodeId() {
    return MemberId.from(String.valueOf(++nextId));
  }

  /** Creates a set of Raft servers. */
  private List<RaftServer> createServers(final int nodes) throws Exception {
    final List<RaftServer> servers = new ArrayList<>();

    for (int i = 0; i < nodes; i++) {
      members.add(nextMember(RaftMember.Type.ACTIVE));
    }

    final CountDownLatch latch = new CountDownLatch(nodes);

    for (int i = 0; i < nodes; i++) {
      final var raftMember = members.get(i);
      final RaftServer server = createServer(raftMember.memberId());
      server
          .bootstrap(members.stream().map(RaftMember::memberId).collect(Collectors.toList()))
          .thenAccept(this::addCommitListener)
          .thenRun(latch::countDown);
      servers.add(server);
      compactAwaiters.put(server.name(), new AtomicReference<>());
    }

    latch.await(30, TimeUnit.SECONDS);

    return servers;
  }

  public String shutdownFollower() throws Exception {
    final var follower = getFollower().orElseThrow();
    shutdownServer(follower);
    return follower.name();
  }

  public Set<String> getNodes() {
    return servers.keySet();
  }

  public void joinCluster(final String nodeId) throws Exception {
    final RaftMember member = getRaftMember(nodeId);
    createServer(member.memberId())
        .bootstrap(getMemberIds())
        .thenAccept(this::addCommitListener)
        .get(30, TimeUnit.SECONDS);
  }

  public void bootstrapNode(final String nodeId) throws Exception {
    final RaftMember member = getRaftMember(nodeId);
    createServer(member.memberId())
        .bootstrap(getMemberIds())
        .thenAccept(this::addCommitListener)
        .get(30, TimeUnit.SECONDS);
  }

  public void bootstrapNodeWithMemberIds(final String nodeId, final List<MemberId> memberIds)
      throws Exception {
    final RaftMember member = getRaftMember(nodeId);
    createServer(member.memberId())
        .bootstrap(memberIds)
        .thenAccept(this::addCommitListener)
        .get(30, TimeUnit.SECONDS);
  }

  public String shutdownLeader() throws Exception {
    final var leader = getLeader().orElseThrow();
    shutdownServer(leader);
    return leader.name();
  }

  public void restartLeader() throws Exception {
    awaitNewLeader();
    final var leader = shutdownLeader();
    joinCluster(leader);
  }

  public List<MemberId> getMemberIds() {
    return members.stream().map(RaftMember::memberId).collect(Collectors.toList());
  }

  public void shutdownServer(final RaftServer raftServer) throws Exception {
    shutdownServer(raftServer.name());
  }

  public void shutdownServer(final String nodeName) throws Exception {
    servers.remove(nodeName).shutdown().get(30, TimeUnit.SECONDS);
    compactAwaiters.remove(nodeName);
    memberLog.remove(nodeName);
    snapshotStores.remove(nodeName);
  }

  private RaftMember getRaftMember(final String memberId) {
    return members.stream()
        .filter(member -> member.memberId().id().equals(memberId))
        .findFirst()
        .orElseThrow();
  }

  public void doSnapshot(final long index) throws Exception {
    doSnapshot(index, 1);
  }

  public void doSnapshot(final long index, final int size) throws Exception {
    awaitNewLeader();

    // we write on all nodes the same snapshot
    // this is similar to our current logic where leader takes a snapshot and replicates it
    // in the end all call the method #newSnapshot and the snapshot listener is triggered to compact

    for (final RaftServer raftServer : servers.values()) {
      if (raftServer.isRunning()) {
        final var raftContext = raftServer.getContext();
        final var snapshotStore =
            getSnapshotStore(raftServer.cluster().getLocalMember().memberId().id());

        compactAwaiters.get(raftServer.name()).set(new CountDownLatch(1));
        InMemorySnapshot.newPersistedSnapshot(index, raftContext.getTerm(), size, snapshotStore);
      }
    }

    // await the compaction to avoid race condition with reading the logs
    for (final RaftServer server : servers.values()) {
      final var latchAtomicReference = compactAwaiters.get(server.name());
      final var latch = latchAtomicReference.get();
      if (!latch.await(30, TimeUnit.SECONDS)) {
        throw new TimeoutException("Expected to compact the log after 30 seconds!");
      }
      latchAtomicReference.set(null);
    }
  }

  private TestSnapshotStore getSnapshotStore(final String memberId) {
    return snapshotStores.get(memberId);
  }

  private AtomicReference<InMemorySnapshot> getOrCreatePersistedSnapshot(final String memberId) {
    return snapshots.computeIfAbsent(memberId, i -> new AtomicReference<>());
  }

  public boolean allNodesHaveSnapshotWithIndex(final long index) {
    return servers.values().stream()
            .map(RaftServer::getContext)
            .map(RaftContext::getPersistedSnapshotStore)
            .map(PersistedSnapshotStore::getCurrentSnapshotIndex)
            .filter(idx -> idx == index)
            .count()
        == servers.values().size();
  }

  public PersistedSnapshot getSnapshotFromLeader() {
    final var leader = getLeader().orElseThrow();
    final var context = leader.getContext();
    final var snapshotStore = context.getPersistedSnapshotStore();
    return snapshotStore.getLatestSnapshot().orElseThrow();
  }

  public PersistedSnapshot getSnapshotOnNode(final String nodeId) {
    final var raftServer = servers.get(nodeId);
    final var context = raftServer.getContext();
    final var snapshotStore = context.getPersistedSnapshotStore();
    return snapshotStore.getLatestSnapshot().orElseThrow();
  }

  public void awaitNewLeader() {
    waitUntil(() -> getLeader().isPresent(), 100);
  }

  private void addCommitListener(final RaftServer raftServer) {
    raftServer
        .getContext()
        .addCommitListener(
            new RaftCommitListener() {
              @Override
              public void onCommit(final long index) {
                final var currentIndex = index;

                memberLog.put(raftServer.name(), currentIndex);
                if (highestCommit < currentIndex) {
                  highestCommit = currentIndex;
                }

                final var commitAwaiter = commitAwaiterRef.get();
                if (commitAwaiter != null && commitAwaiter.reachedCommit(currentIndex)) {
                  commitAwaiterRef.set(null);
                }
              }
            });
  }

  public Map<String, List<IndexedRaftLogEntry>> getMemberLogs() {

    final Map<String, List<IndexedRaftLogEntry>> memberLogs = new HashMap<>();

    for (final var server : servers.values()) {
      if (server.isRunning()) {

        final var log = server.getContext().getLog();
        final List<IndexedRaftLogEntry> entryList = new ArrayList<>();
        try (final var raftLogReader = log.openReader(Mode.ALL)) {
          while (raftLogReader.hasNext()) {
            final var indexedEntry = raftLogReader.next();
            entryList.add(indexedEntry);
          }
        }

        memberLogs.put(server.name(), entryList);
      }
    }

    return memberLogs;
  }

  public void awaitSameLogSizeOnAllNodes(final long lastIndex) {
    Awaitility.await("awaitSameLogSizeOnAllNodes")
        .until(
            () -> memberLog.values().stream().distinct().collect(Collectors.toList()),
            lastIndexes -> lastIndexes.size() == 1 && lastIndexes.get(0) == lastIndex);
  }

  private void waitUntil(final BooleanSupplier condition, final int retries) {
    waitUntil(condition, retries, () -> null);
  }

  private void waitUntil(
      final BooleanSupplier condition, int retries, final Supplier<String> errorMessage) {
    try {
      while (!condition.getAsBoolean() && retries > 0) {
        Thread.sleep(100);
        retries--;
      }
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }

    assertTrue(errorMessage.get(), condition.getAsBoolean());
  }

  public void awaitCommit(final long commitIndex) throws Exception {
    if (highestCommit >= commitIndex) {
      return;
    }

    final var commitAwaiter = new CommitAwaiter(commitIndex);
    commitAwaiterRef.set(commitAwaiter);

    commitAwaiter.awaitCommit();
  }

  /** Creates a Raft server. */
  private RaftServer createServer(final MemberId memberId) {
    return createServer(
        memberId,
        b -> {
          final var storage = createStorage(memberId);
          storage
              .getPersistedSnapshotStore()
              .addSnapshotListener(new RaftSnapshotListener(memberId));
          return b.withStorage(storage);
        });
  }

  private RaftServer createServer(
      final MemberId memberId, final UnaryOperator<Builder> configurator) {
    final TestRaftServerProtocol protocol = protocolFactory.newServerProtocol(memberId);
    final RaftServer.Builder defaults =
        RaftServer.builder(memberId)
            .withMembershipService(mock(ClusterMembershipService.class))
            .withProtocol(protocol)
            .withEntryValidator(entryValidator);
    final RaftServer server = configurator.apply(defaults).build();

    servers.put(memberId.id(), server);
    return server;
  }

  private RaftStorage createStorage(final MemberId memberId) {
    return createStorage(memberId, Function.identity());
  }

  public void copySnapshotOffline(final String sourceNode, final String targetNode) {
    final var snapshotOnNode = getSnapshotOnNode(sourceNode);
    final var targetSnapshotStore = new TestSnapshotStore(getOrCreatePersistedSnapshot(sourceNode));
    final var receivedSnapshot = targetSnapshotStore.newReceivedSnapshot(snapshotOnNode.getId());
    for (final var reader = snapshotOnNode.newChunkReader(); reader.hasNext(); ) {
      receivedSnapshot.apply(reader.next());
    }
    receivedSnapshot.persist();
  }

  private RaftStorage createStorage(
      final MemberId memberId,
      final Function<RaftStorage.Builder, RaftStorage.Builder> configurator) {

    final var memberDirectory = getMemberDirectory(directory, memberId.toString());
    final RaftStorage.Builder defaults =
        RaftStorage.builder()
            .withDirectory(memberDirectory)
            .withMaxSegmentSize(1024 * 10)
            .withFreeDiskSpace(100)
            .withSnapshotStore(
                snapshotStores.compute(
                    memberId.id(),
                    (k, v) -> new TestSnapshotStore(getOrCreatePersistedSnapshot(memberId.id()))));
    return configurator.apply(defaults).build();
  }

  private File getMemberDirectory(final Path directory, final String s) {
    return new File(directory.toFile(), s);
  }

  private Optional<RaftServer> getLeader() {
    return servers.values().stream().filter(s -> s.getRole() == Role.LEADER).findFirst();
  }

  private Optional<RaftServer> getFollower() {
    return servers.values().stream().filter(s -> s.getRole() == Role.FOLLOWER).findFirst();
  }

  public long appendEntries(final int count) throws Exception {
    final var leader = getLeader().orElseThrow();

    for (int i = 0; i < count - 1; i++) {
      appendEntry();
    }

    return appendEntry();
  }

  public long appendEntry() throws Exception {
    final var leader = getLeader().orElseThrow();

    return appendEntry(leader, 1024);
  }

  private long appendEntry(final RaftServer leader, final int entrySize) throws Exception {
    final var raftRole = leader.getContext().getRaftRole();
    if (raftRole instanceof LeaderRole) {
      final var testAppendListener = appendEntry(entrySize, (LeaderRole) raftRole);
      return testAppendListener.awaitCommit();
    }
    throw new IllegalArgumentException(
        "Expected to append entry on leader, "
            + leader.getContext().getName()
            + " was not the leader!");
  }

  private void appendEntryAsync(final RaftServer leader, final int entrySize) {
    final var raftRole = leader.getContext().getRaftRole();

    if (raftRole instanceof LeaderRole) {
      appendEntry(entrySize, (LeaderRole) raftRole);
      return;
    }

    throw new IllegalArgumentException(
        "Expected to append entry on leader, "
            + leader.getContext().getName()
            + " was not the leader!");
  }

  private TestAppendListener appendEntry(final int entrySize, final LeaderRole leaderRole) {
    final var appendListener = new TestAppendListener();
    position += 1;
    leaderRole.appendEntry(
        position,
        position + 10,
        ByteBuffer.wrap(RandomStringUtils.random(entrySize).getBytes()),
        appendListener);
    position += 10;
    return appendListener;
  }

  @Override
  public String toString() {
    return "RaftRule with " + nodeCount + " nodes.";
  }

  public void triggerDataLossOnNode(final String node) throws IOException {
    final var member =
        members.stream()
            .map(RaftMember::memberId)
            .map(AbstractIdentifier::id)
            .filter(id -> id.equals(node))
            .findAny()
            .orElseThrow();

    final var memberDirectory = getMemberDirectory(directory, member);
    FileUtil.deleteFolder(memberDirectory.toPath());
    // Clear in memory snapshots
    snapshots.remove(node);
  }

  public PersistedSnapshotStore getPersistedSnapshotStore(final String followerB) {
    return servers.get(followerB).getContext().getPersistedSnapshotStore();
  }

  private static final class CommitAwaiter {

    private final long awaitedIndex;
    private final CountDownLatch latch = new CountDownLatch(1);

    public CommitAwaiter(final long index) {
      awaitedIndex = index;
    }

    public boolean reachedCommit(final long currentIndex) {
      if (awaitedIndex <= currentIndex) {
        latch.countDown();
        return true;
      }
      return false;
    }

    public void awaitCommit() throws Exception {
      latch.await(30, TimeUnit.SECONDS);
    }
  }

  private static final class TestAppendListener implements ZeebeLogAppender.AppendListener {

    private final CompletableFuture<Long> commitFuture = new CompletableFuture<>();

    @Override
    public void onWriteError(final Throwable error) {
      commitFuture.completeExceptionally(error);
    }

    @Override
    public void onCommit(final IndexedRaftLogEntry indexed) {
      commitFuture.complete(indexed.index());
    }

    @Override
    public void onCommitError(final IndexedRaftLogEntry indexed, final Throwable error) {
      commitFuture.completeExceptionally(error);
    }

    public long awaitCommit() throws Exception {
      return commitFuture.get(30, TimeUnit.SECONDS);
    }
  }

  private final class RaftSnapshotListener implements PersistedSnapshotListener {

    private final MemberId memberId;

    public RaftSnapshotListener(final MemberId memberId) {
      this.memberId = memberId;
    }

    @Override
    public void onNewSnapshot(final PersistedSnapshot persistedSnapshot) {
      final var raftServer = servers.get(memberId.id());
      if (raftServer != null) {
        final var raftContext = raftServer.getContext();
        final var serviceManager = raftContext.getLogCompactor();
        serviceManager.setCompactableIndex(persistedSnapshot.getIndex());

        raftServer
            .compact()
            .whenComplete(
                (v, t) -> {
                  final var latch = compactAwaiters.get(memberId.id()).get();
                  if (latch != null) {
                    latch.countDown();
                  }
                });
      }
    }
  }
}
