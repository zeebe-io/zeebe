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
package io.atomix.raft.zeebe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.google.common.base.Stopwatch;
import io.atomix.raft.RaftCommitListener;
import io.atomix.raft.partition.impl.RaftPartitionServer;
import io.atomix.raft.storage.log.IndexedRaftLogEntry;
import io.atomix.raft.zeebe.util.TestAppender;
import io.atomix.raft.zeebe.util.ZeebeTestHelper;
import io.atomix.raft.zeebe.util.ZeebeTestNode;
import io.atomix.utils.concurrent.Futures;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(Parameterized.class)
public class ZeebeTest {

  // rough estimate of how many entries we'd need to write to fill a segment
  // segments are configured for 1kb, and one entry takes ~30 bytes (plus some metadata I guess)
  private static final int ENTRIES_PER_SEGMENT = (1024 / 30) + 1;

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();
  @Parameter public String name;

  @Parameter(1)
  public Collection<Function<TemporaryFolder, ZeebeTestNode>> nodeSuppliers;

  private final Logger logger = LoggerFactory.getLogger(getClass());
  private final Stopwatch stopwatch = Stopwatch.createUnstarted();
  private final TestAppender appenderWrapper = new TestAppender();

  private Collection<ZeebeTestNode> nodes;
  private ZeebeTestHelper helper;

  @Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return Arrays.asList(
        new Object[] {"single node", Collections.singleton(provideNode(1))},
        new Object[] {
          "three nodes", Arrays.asList(provideNode(1), provideNode(2), provideNode(3))
        });
  }

  private static Function<TemporaryFolder, ZeebeTestNode> provideNode(final int id) {
    return tmp -> new ZeebeTestNode(id, newFolderUnchecked(tmp, id));
  }

  private static File newFolderUnchecked(final TemporaryFolder tmp, final int id) {
    try {
      return tmp.newFolder(String.valueOf(id));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Before
  public void setUp() throws Exception {
    stopwatch.reset();
    nodes = buildNodes();
    helper = new ZeebeTestHelper(nodes);
    start();

    stopwatch.start();
  }

  @After
  public void tearDown() throws Exception {
    if (stopwatch.isRunning()) {
      stopwatch.stop();
    }

    logger.info("Test run time: {}", stopwatch.toString());
    stop();
  }

  @SuppressWarnings("squid:S2699") // awaitAllContain is the assert here
  @Test
  public void shouldAppendAndReplicate() {
    // given
    final int partitionId = 1;
    final ZeebeLogAppender appender = helper.awaitLeaderAppender(partitionId);

    // when
    final IndexedRaftLogEntry appended = appenderWrapper.append(appender, 0, 0, getIntAsBytes(0));

    // then
    helper.awaitAllContain(partitionId, appended);
  }

  @Test
  public void shouldNotCompactAnything() {
    // given
    final int partitionId = 1;
    final RaftPartitionServer server = helper.awaitLeaderServer(1);
    final ZeebeLogAppender appender = helper.awaitLeaderAppender(partitionId);

    // when
    final IndexedRaftLogEntry firstAppended =
        appenderWrapper.append(appender, 0L, 0L, getIntAsBytes(0));
    for (int i = 1; i < ENTRIES_PER_SEGMENT; i++) {
      helper.awaitAllContain(partitionId, appenderWrapper.append(appender, i, i, getIntAsBytes(i)));
    }
    server.snapshot().join();

    // then
    assertTrue(helper.containsIndexed(server, firstAppended));
  }

  @Test
  public void shouldCompactUpToCompactablePosition() {
    // given
    final int partitionId = 1;
    final RaftPartitionServer server = helper.awaitLeaderServer(1);
    final ZeebeLogAppender appender = helper.awaitLeaderAppender(partitionId);

    // when
    IndexedRaftLogEntry appended = appenderWrapper.append(appender, 0L, 0L, getIntAsBytes(0));
    final IndexedRaftLogEntry firstAppended = appended;
    for (int i = 1; i < ENTRIES_PER_SEGMENT; i++) {
      appended = appenderWrapper.append(appender, i, i, getIntAsBytes(i));
      helper.awaitAllContain(partitionId, appended);
    }
    server.setCompactableIndex(appended.index());
    server.snapshot().join();

    // then
    assertFalse(helper.containsIndexed(server, firstAppended));
    assertTrue(helper.containsIndexed(server, appended));
  }

  @Test
  public void shouldFailover() {
    assumeTrue(nodes.size() > 1);

    // given
    final int partitionId = 1;
    final ZeebeTestNode originalLeader = helper.awaitLeader(partitionId);
    final Collection<ZeebeTestNode> followers = new ArrayList<>(nodes);
    followers.remove(originalLeader);

    // when
    originalLeader.stop().join();
    final ZeebeTestNode newLeader = helper.awaitLeader(partitionId, followers);
    originalLeader.start(nodes).join();

    // then
    assertNotEquals(originalLeader, helper.awaitLeader(partitionId));
    assertEquals(newLeader, helper.awaitLeader(partitionId));
  }

  @SuppressWarnings("squid:S2699") // awaitAllContain is the assert here
  @Test
  public void shouldAppendAllEntriesEvenWithFollowerFailures() {
    assumeTrue(nodes.size() > 1);

    // given
    final int partitionId = 1;
    final ZeebeTestNode leader = helper.awaitLeader(partitionId);
    final ZeebeLogAppender appender = helper.awaitLeaderAppender(partitionId);
    final List<ZeebeTestNode> followers =
        nodes.stream().filter(node -> !node.equals(leader)).collect(Collectors.toList());
    final List<IndexedRaftLogEntry> entries = new ArrayList<>();

    // when
    for (int i = 0; i < followers.size(); i++) {
      final ZeebeTestNode follower = followers.get(i);
      final List<ZeebeTestNode> others =
          nodes.stream().filter(node -> !node.equals(follower)).collect(Collectors.toList());
      follower.stop().join();

      entries.add(i, appenderWrapper.append(appender, i, i, getIntAsBytes(i)));
      helper.awaitAllContains(others, partitionId, entries.get(i));
      follower.start(nodes).join();
    }

    // then
    for (final IndexedRaftLogEntry entry : entries) {
      helper.awaitAllContain(partitionId, entry);
    }
  }

  @Test
  public void shouldNotifyCommitListeners() {
    // given
    final int partitionId = 1;
    final ZeebeLogAppender appender = helper.awaitLeaderAppender(partitionId);
    final Map<ZeebeTestNode, CommitListener> listeners =
        nodes.stream()
            .collect(
                Collectors.toMap(
                    Function.identity(),
                    node -> {
                      final CommitListener listener = new CommitListener();
                      node.getPartitionServer(partitionId).addCommitListener(listener);
                      return listener;
                    }));

    // when - then
    for (int i = 0; i < 5; i++) {
      final IndexedRaftLogEntry entry = appenderWrapper.append(appender, i, i, getIntAsBytes(i));
      final int expectedCount = i + 1;
      helper.awaitAllContains(nodes, partitionId, entry);

      for (final ZeebeTestNode node : nodes) {
        final CommitListener listener = listeners.get(node);
        // it may take a little bit before the listener is called as this is done
        // asynchronously
        helper.await(() -> listener.calledCount.get() == expectedCount);
        assertEquals(entry.index(), listener.lastCommitted.get());
      }
    }
  }

  private ByteBuffer getIntAsBytes(final int value) {
    final ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
    buffer.putInt(value).flip();

    return buffer;
  }

  private Collection<ZeebeTestNode> buildNodes() {
    return nodeSuppliers.stream()
        .map(supplier -> supplier.apply(temporaryFolder))
        .collect(Collectors.toList());
  }

  private void start() throws ExecutionException, InterruptedException, TimeoutException {
    Futures.allOf(nodes.stream().map(n -> n.start(nodes))).get(30, TimeUnit.SECONDS);
  }

  private void stop() throws InterruptedException, ExecutionException, TimeoutException {
    Futures.allOf(nodes.stream().map(ZeebeTestNode::stop)).get(30, TimeUnit.SECONDS);
  }

  static class CommitListener implements RaftCommitListener {

    private final AtomicLong lastCommitted = new AtomicLong();
    private final AtomicInteger calledCount = new AtomicInteger(0);

    @Override
    public void onCommit(final long index) {
      lastCommitted.set(index);
      calledCount.incrementAndGet();
    }
  }
}
