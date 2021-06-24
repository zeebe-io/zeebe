/*
 * Copyright 2017-present Open Networking Foundation
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.raft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.google.common.collect.Maps;
import io.atomix.cluster.ClusterMembershipService;
import io.atomix.cluster.MemberId;
import io.atomix.raft.RaftServer.Builder;
import io.atomix.raft.RaftServer.Role;
import io.atomix.raft.cluster.RaftMember;
import io.atomix.raft.metrics.RaftRoleMetrics;
import io.atomix.raft.primitive.TestMember;
import io.atomix.raft.protocol.TestRaftProtocolFactory;
import io.atomix.raft.protocol.TestRaftServerProtocol;
import io.atomix.raft.roles.LeaderRole;
import io.atomix.raft.snapshot.TestSnapshotStore;
import io.atomix.raft.storage.RaftStorage;
import io.atomix.raft.storage.log.IndexedRaftLogEntry;
import io.atomix.raft.storage.log.RaftLog;
import io.atomix.raft.storage.log.RaftLogReader;
import io.atomix.raft.storage.log.RaftLogReader.Mode;
import io.atomix.raft.storage.log.entry.InitialEntry;
import io.atomix.raft.zeebe.ZeebeLogAppender;
import io.atomix.utils.concurrent.SingleThreadContext;
import io.atomix.utils.concurrent.ThreadContext;
import io.camunda.zeebe.journal.file.LogCorrupter;
import io.camunda.zeebe.journal.file.record.CorruptedLogException;
import io.camunda.zeebe.util.health.FailureListener;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.jodah.concurrentunit.ConcurrentTestCase;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

/** Raft test. */
public class RaftTest extends ConcurrentTestCase {

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();
  private volatile int nextId;
  private volatile List<RaftMember> members;
  private volatile List<RaftServer> servers = new ArrayList<>();
  private volatile TestRaftProtocolFactory protocolFactory;
  private volatile ThreadContext context;
  private volatile long position = 0;
  private Path directory;
  private final Map<MemberId, TestRaftServerProtocol> serverProtocols = Maps.newConcurrentMap();

  @Before
  @After
  public void clearTests() throws Exception {
    servers.forEach(
        s -> {
          try {
            if (s.isRunning()) {
              s.shutdown().get(10, TimeUnit.SECONDS);
            }
          } catch (final Exception e) {
            // its fine..
          }
        });

    directory = temporaryFolder.newFolder().toPath();

    if (context != null) {
      context.close();
    }

    members = new ArrayList<>();
    nextId = 0;
    servers = new ArrayList<>();
    context = new SingleThreadContext("raft-test-messaging-%d");
    protocolFactory = new TestRaftProtocolFactory(context);
  }

  /** Creates a set of Raft servers. */
  private List<RaftServer> createServers(final int nodes) throws Throwable {
    final List<RaftServer> servers = new ArrayList<>();

    for (int i = 0; i < nodes; i++) {
      members.add(nextMember(RaftMember.Type.ACTIVE));
    }

    final CountDownLatch latch = new CountDownLatch(nodes);

    for (int i = 0; i < nodes; i++) {
      final RaftServer server = createServer(members.get(i).memberId());
      if (members.get(i).getType() == RaftMember.Type.ACTIVE) {
        server
            .bootstrap(members.stream().map(RaftMember::memberId).collect(Collectors.toList()))
            .thenRun(latch::countDown);
      }
      servers.add(server);
    }

    assertThat(latch.await(30L * nodes, TimeUnit.SECONDS)).isTrue();

    return servers;
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

  /** Creates a Raft server. */
  private RaftServer createServer(final MemberId memberId) {
    return createServer(memberId, b -> b.withStorage(createStorage(memberId)));
  }

  private RaftServer createServer(
      final MemberId memberId,
      final Function<RaftServer.Builder, RaftServer.Builder> configurator) {
    final TestRaftServerProtocol protocol = protocolFactory.newServerProtocol(memberId);
    final RaftServer.Builder defaults =
        RaftServer.builder(memberId)
            .withMembershipService(mock(ClusterMembershipService.class))
            .withProtocol(protocol);
    final RaftServer server = configurator.apply(defaults).build();

    serverProtocols.put(memberId, protocol);
    servers.add(server);
    return server;
  }

  private RaftStorage createStorage(final MemberId memberId) {
    return createStorage(memberId, Function.identity());
  }

  private RaftStorage createStorage(
      final MemberId memberId,
      final Function<RaftStorage.Builder, RaftStorage.Builder> configurator) {
    final var directory = new File(this.directory.toFile(), memberId.toString());
    final RaftStorage.Builder defaults =
        RaftStorage.builder()
            .withDirectory(directory)
            .withSnapshotStore(new TestSnapshotStore(new AtomicReference<>()))
            .withMaxSegmentSize(1024 * 10);
    return configurator.apply(defaults).build();
  }

  /** Tests transferring leadership. */
  @Test
  @Ignore
  public void testTransferLeadership() throws Throwable {
    final List<RaftServer> servers = createServers(3);
    final var leader = getLeader(servers).orElseThrow();
    awaitAppendEntries(leader, 1000);
    final RaftServer follower = servers.stream().filter(RaftServer::isFollower).findFirst().get();
    follower.promote().thenRun(this::resume);
    await(15000, 1001);
    assertTrue(follower.isLeader());
  }

  /** Tests demoting the leader. */
  @Test
  public void testDemoteLeader() throws Throwable {
    final List<RaftServer> servers = createServers(3);

    final RaftServer leader =
        servers.stream()
            .filter(s -> s.cluster().getLocalMember().equals(s.cluster().getLeader()))
            .findFirst()
            .get();

    final RaftServer follower =
        servers.stream()
            .filter(s -> !s.cluster().getLocalMember().equals(s.cluster().getLeader()))
            .findFirst()
            .get();

    follower
        .cluster()
        .getMember(leader.cluster().getLocalMember().memberId())
        .addTypeChangeListener(
            t -> {
              threadAssertEquals(t, RaftMember.Type.PASSIVE);
              resume();
            });
    leader.cluster().getLocalMember().demote(RaftMember.Type.PASSIVE).thenRun(this::resume);
    await(15000, 2);
  }

  /** Tests submitting a command. */
  @Test
  public void testTwoOfThreeNodeSubmitCommand() throws Throwable {
    testSubmitCommand(2, 3);
  }

  /** Tests submitting a command to a partial cluster. */
  private void testSubmitCommand(final int live, final int total) throws Throwable {
    final var leader = getLeader(createServers(live, total));

    appendEntry(leader.orElseThrow());
  }

  /** Creates a set of Raft servers. */
  private List<RaftServer> createServers(final int live, final int total) throws Throwable {
    final List<RaftServer> servers = new ArrayList<>();

    for (int i = 0; i < total; i++) {
      members.add(nextMember(RaftMember.Type.ACTIVE));
    }

    for (int i = 0; i < live; i++) {
      final RaftServer server = createServer(members.get(i).memberId());
      if (members.get(i).getType() == RaftMember.Type.ACTIVE) {
        server
            .bootstrap(members.stream().map(RaftMember::memberId).collect(Collectors.toList()))
            .thenRun(this::resume);
      }
      servers.add(server);
    }

    await(30000L * live, live);

    return servers;
  }

  /** Tests submitting a command. */
  @Test
  public void testThreeOfFourNodeSubmitCommand() throws Throwable {
    testSubmitCommand(3, 4);
  }

  /** Tests submitting a command. */
  @Test
  public void testThreeOfFiveNodeSubmitCommand() throws Throwable {
    testSubmitCommand(3, 5);
  }

  @Test
  public void testThreeNodesSequentiallyStart() throws Throwable {
    // given
    for (int i = 0; i < 3; i++) {
      members.add(nextMember(RaftMember.Type.ACTIVE));
    }

    // wait between bootstraps to produce more realistic environment
    for (int i = 0; i < 3; i++) {
      final RaftServer server = createServer(members.get(i).memberId());
      server
          .bootstrap(members.stream().map(RaftMember::memberId).collect(Collectors.toList()))
          .thenRun(this::resume);
      Thread.sleep(500);
    }

    // then expect that all come up in time
    await(2000 * 3, 3);
  }

  @Test
  public void testThreeNodeManyEventsDoNotMissHeartbeats() throws Throwable {
    // given
    createServers(3);
    final var leader = getLeader(servers).orElseThrow();

    appendEntry(leader);

    final double startMissedHeartBeats = RaftRoleMetrics.getHeartbeatMissCount("1");

    // when
    appendEntries(leader, 1000);

    // then
    final double missedHeartBeats = RaftRoleMetrics.getHeartbeatMissCount("1");
    assertThat(0.0, is(missedHeartBeats - startMissedHeartBeats));
  }

  private void waitUntil(final BooleanSupplier condition, int retries) {
    try {
      while (!condition.getAsBoolean() && retries > 0) {
        Thread.sleep(100);
        retries--;
      }
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }

    assertTrue(condition.getAsBoolean());
  }

  @Test
  public void testRoleChangeNotificationAfterInitialEntryOnLeader() throws Throwable {
    // given
    final List<RaftServer> servers = createServers(3);
    final RaftServer leader = getLeader(servers).get();
    final CountDownLatch transitionCompleted = new CountDownLatch(1);
    servers.forEach(
        server ->
            server.addRoleChangeListener(
                (role, term) ->
                    assertLastReadInitialEntry(role, term, server, transitionCompleted)));
    // when
    leader.stepDown();

    // then
    assertTrue(transitionCompleted.await(1000, TimeUnit.SECONDS));
  }

  private Optional<RaftServer> getLeader(final List<RaftServer> servers) {
    return servers.stream().filter(s -> s.getRole() == Role.LEADER).findFirst();
  }

  private List<RaftServer> getFollowers(final List<RaftServer> servers) {
    return servers.stream().filter(s -> s.getRole() == Role.FOLLOWER).collect(Collectors.toList());
  }

  private void assertLastReadInitialEntry(
      final Role role,
      final long term,
      final RaftServer server,
      final CountDownLatch transitionCompleted) {
    if (role == Role.LEADER) {
      final RaftLog raftLog = server.getContext().getLog();
      final RaftLogReader raftLogReader = raftLog.openReader(Mode.COMMITS);
      raftLogReader.seek(raftLog.getLastIndex());
      final IndexedRaftLogEntry entry = raftLogReader.next();

      assertThat(entry.entry()).isInstanceOf(InitialEntry.class);
      assertEquals(term, entry.term());
      transitionCompleted.countDown();
    }
  }

  @Test
  public void testNotifyOnFailure() throws Throwable {
    // given
    final List<RaftServer> servers = createServers(1);
    final RaftServer server = servers.get(0);
    final CountDownLatch firstLatch = new CountDownLatch(1);
    final CountDownLatch secondLatch = new CountDownLatch(1);

    server.addFailureListener(new LatchFailureListener(firstLatch));
    server.addFailureListener(new LatchFailureListener(secondLatch));

    // when
    // inject failures
    server
        .getContext()
        .getThreadContext()
        .execute(
            () -> {
              throw new RuntimeException("injected failure");
            });

    // then
    assertThat(firstLatch.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(secondLatch.await(1, TimeUnit.SECONDS)).isTrue();

    assertEquals(Role.INACTIVE, server.getRole());
  }

  @Test
  public void shouldLeaderStepDownOnDisconnect() throws Throwable {
    final List<RaftServer> servers = createServers(3);
    final RaftServer leader = getLeader(servers).get();
    final MemberId leaderId = leader.getContext().getCluster().getLocalMember().memberId();

    final CountDownLatch stepDownListener = new CountDownLatch(1);
    leader.addRoleChangeListener(
        (role, term) -> {
          if (role == Role.FOLLOWER) {
            stepDownListener.countDown();
          }
        });

    // when
    protocolFactory.partition(leaderId);

    // then
    assertTrue(stepDownListener.await(30, TimeUnit.SECONDS));
    assertFalse(leader.isLeader());
  }

  @Test
  public void shouldReconnect() throws Throwable {
    // given
    final List<RaftServer> servers = createServers(3);
    final RaftServer leader = getLeader(servers).get();
    final MemberId leaderId = leader.getContext().getCluster().getLocalMember().memberId();
    final AtomicLong commitIndex = new AtomicLong();
    leader.getContext().addCommitListener(newValue -> commitIndex.set(newValue.index()));
    appendEntry(leader);
    protocolFactory.partition(leaderId);
    waitUntil(() -> !leader.isLeader(), 100);

    // when
    final var newLeader = servers.stream().filter(RaftServer::isLeader).findFirst().orElseThrow();
    assertNotEquals(newLeader, leader);
    final var secondCommit = appendEntry(newLeader);
    protocolFactory.heal(leaderId);

    // then
    waitUntil(() -> commitIndex.get() >= secondCommit, 200);
  }

  @Test
  public void shouldFailOverOnLeaderDisconnect() throws Throwable {
    final List<RaftServer> servers = createServers(3);

    final RaftServer leader = getLeader(servers).get();
    final MemberId leaderId = leader.getContext().getCluster().getLocalMember().memberId();

    final CountDownLatch newLeaderElected = new CountDownLatch(1);
    final AtomicReference<MemberId> newLeaderId = new AtomicReference<>();
    servers.forEach(
        s ->
            s.addRoleChangeListener(
                (role, term) -> {
                  if (role == Role.LEADER) {
                    newLeaderId.set(s.getContext().getCluster().getLocalMember().memberId());
                    newLeaderElected.countDown();
                  }
                }));
    // when
    protocolFactory.partition(leaderId);

    // then
    assertTrue(newLeaderElected.await(30, TimeUnit.SECONDS));
    assertNotEquals(newLeaderId.get(), leaderId);
  }

  @Test
  public void shouldDetectCorruptionOnStart() throws Throwable {
    // given
    final RaftServer leader = createServers(1).get(0);
    final long index = appendEntry(leader);
    final File directory = leader.getContext().getStorage().directory();

    final Optional<File> optLog =
        Arrays.stream(directory.listFiles()).filter(f -> f.getName().endsWith(".log")).findFirst();
    assertThat(optLog).isPresent();
    final File log = optLog.get();

    // when
    leader.shutdown().join();
    assertThat(LogCorrupter.corruptRecord(log, index)).isTrue();

    // then
    final MemberId memberId = members.get(0).memberId();
    assertThatThrownBy(() -> recreateServer(leader, memberId))
        .isInstanceOf(CorruptedLogException.class);
  }

  @Test
  public void shouldTruncateLogAfterPartialWrite() throws Throwable {
    // given
    RaftServer leader = createServers(1).get(0);
    final long index = appendEntry(leader);

    final File directory = leader.getContext().getStorage().directory();
    final Optional<File> optLog =
        Arrays.stream(directory.listFiles()).filter(f -> f.getName().endsWith(".log")).findFirst();
    assertThat(optLog).isPresent();
    final File log = optLog.get();

    // when
    leader.getContext().setLastWrittenIndex(index - 1);
    leader.shutdown().join();
    assertThat(LogCorrupter.corruptRecord(log, index)).isTrue();

    final MemberId memberId = members.get(0).memberId();
    leader = recreateServer(leader, memberId);

    // then
    final RaftLog raftLog = leader.getContext().getLog();
    final RaftLogReader raftLogReader = raftLog.openReader(Mode.ALL);

    assertThat(raftLog.getLastIndex()).isEqualTo(index - 1);
    raftLogReader.seek(raftLog.getLastIndex());
    assertThat(raftLogReader.next().index()).isEqualTo(index - 1);
  }

  private RaftServer recreateServer(final RaftServer server, final MemberId memberId) {
    final Function<RaftStorage.Builder, RaftStorage.Builder> storageConfig =
        c -> c.withDirectory(server.getContext().getStorage().directory());
    final RaftStorage storage = createStorage(memberId, storageConfig);
    final Function<Builder, Builder> serverConfig = b -> b.withStorage(storage);

    return createServer(memberId, serverConfig);
  }

  @Test
  public void shouldTriggerHeartbeatTimeouts() throws Throwable {
    final List<RaftServer> servers = createServers(3);
    final List<RaftServer> followers = getFollowers(servers);
    final MemberId followerId =
        followers.get(0).getContext().getCluster().getLocalMember().memberId();

    // when
    final TestRaftServerProtocol followerServer = serverProtocols.get(followerId);
    Mockito.clearInvocations(followerServer);
    protocolFactory.partition(followerId);

    // then
    // should send poll requests to 2 nodes
    verify(followerServer, timeout(5000).atLeast(2)).poll(any(), any());
  }

  @Test
  public void shouldReSendPollRequestOnTimeouts() throws Throwable {
    final List<RaftServer> servers = createServers(3);
    final List<RaftServer> followers = getFollowers(servers);
    final MemberId followerId =
        followers.get(0).getContext().getCluster().getLocalMember().memberId();

    // when
    final TestRaftServerProtocol followerServer = serverProtocols.get(followerId);
    Mockito.clearInvocations(followerServer);
    protocolFactory.partition(followerId);
    verify(followerServer, timeout(5000).atLeast(2)).poll(any(), any());
    Mockito.clearInvocations(followerServer);

    // then
    // no response for previous poll requests, so send them again
    verify(followerServer, timeout(5000).atLeast(2)).poll(any(), any());
  }

  private void appendEntries(final RaftServer leader, final int count) {
    for (int i = 0; i < count; i++) {
      appendEntryAsync(leader, 1024);
    }
  }

  private long appendEntry(final RaftServer leader) throws Exception {
    return appendEntry(leader, 1024);
  }

  private long appendEntry(final RaftServer leader, final int entrySize) throws Exception {
    final var raftRole = leader.getContext().getRaftRole();
    if (raftRole instanceof LeaderRole) {
      final var appendListener = appendEntry(entrySize, (LeaderRole) raftRole);
      return appendListener.awaitCommit();
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

  private void awaitAppendEntries(final RaftServer newLeader, final int i) throws Exception {
    // this call is async
    appendEntries(newLeader, i - 1);

    // this awaits the last append
    appendEntry(newLeader);
  }

  private static final class TestAppendListener implements ZeebeLogAppender.AppendListener {

    private final CompletableFuture<Long> commitFuture = new CompletableFuture<>();

    @Override
    public void onWriteError(final Throwable error) {
      fail("Unexpected write error: " + error.getMessage());
    }

    @Override
    public void onCommit(final IndexedRaftLogEntry indexed) {
      commitFuture.complete(indexed.index());
    }

    @Override
    public void onCommitError(final IndexedRaftLogEntry indexed, final Throwable error) {
      fail("Unexpected write error: " + error.getMessage());
    }

    public long awaitCommit() throws Exception {
      return commitFuture.get(30, TimeUnit.SECONDS);
    }
  }

  private static class LatchFailureListener implements FailureListener {

    private final CountDownLatch latch;

    LatchFailureListener(final CountDownLatch latch) {
      this.latch = latch;
    }

    @Override
    public void onFailure() {
      latch.countDown();
    }

    @Override
    public void onRecovered() {}

    @Override
    public void onUnrecoverableFailure() {}
  }
}
