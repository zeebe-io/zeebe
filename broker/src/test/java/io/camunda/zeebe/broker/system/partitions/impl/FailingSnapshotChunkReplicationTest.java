/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.system.partitions.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.atomix.raft.storage.log.entry.ApplicationEntry;
import io.camunda.zeebe.broker.system.partitions.SnapshotReplication;
import io.camunda.zeebe.broker.system.partitions.TestIndexedRaftLogEntry;
import io.camunda.zeebe.db.impl.rocksdb.ZeebeRocksDbFactory;
import io.camunda.zeebe.snapshots.ConstructableSnapshotStore;
import io.camunda.zeebe.snapshots.ReceivableSnapshotStore;
import io.camunda.zeebe.snapshots.SnapshotChunk;
import io.camunda.zeebe.snapshots.TransientSnapshot;
import io.camunda.zeebe.snapshots.impl.FileBasedSnapshotStoreFactory;
import io.camunda.zeebe.test.util.AutoCloseableRule;
import io.camunda.zeebe.util.sched.testing.ActorSchedulerRule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

@Ignore("State on followers will not replicate snapshots")
public final class FailingSnapshotChunkReplicationTest {

  @Rule public final TemporaryFolder tempFolderRule = new TemporaryFolder();
  @Rule public final AutoCloseableRule autoCloseableRule = new AutoCloseableRule();
  @Rule public final ActorSchedulerRule actorSchedulerRule = new ActorSchedulerRule();

  private StateControllerImpl replicatorSnapshotController;
  private StateControllerImpl receiverSnapshotController;
  private ConstructableSnapshotStore senderStore;
  private ReceivableSnapshotStore receiverStore;

  public void setup(final SnapshotReplication replicator) throws IOException {
    final var senderRoot = tempFolderRule.newFolder("sender").toPath();

    final var senderFactory = new FileBasedSnapshotStoreFactory(actorSchedulerRule.get(), 1);
    senderFactory.createReceivableSnapshotStore(senderRoot, 1);
    senderStore = senderFactory.getConstructableSnapshotStore(1);

    final var receiverRoot = tempFolderRule.newFolder("receiver").toPath();
    final var receiverFactory = new FileBasedSnapshotStoreFactory(actorSchedulerRule.get(), 2);
    receiverStore = receiverFactory.createReceivableSnapshotStore(receiverRoot, 1);

    replicatorSnapshotController =
        new StateControllerImpl(
            1,
            ZeebeRocksDbFactory.newFactory(),
            senderStore,
            senderFactory.getReceivableSnapshotStore(1),
            senderRoot.resolve("runtime"),
            replicator,
            l ->
                Optional.of(
                    new TestIndexedRaftLogEntry(
                        l, 1, new ApplicationEntry(1, 10, new UnsafeBuffer()))),
            db -> Long.MAX_VALUE);
    senderStore.addSnapshotListener(replicatorSnapshotController);

    receiverSnapshotController =
        new StateControllerImpl(
            1,
            ZeebeRocksDbFactory.newFactory(),
            receiverFactory.getConstructableSnapshotStore(1),
            receiverFactory.getReceivableSnapshotStore(1),
            receiverRoot.resolve("runtime"),
            replicator,
            l ->
                Optional.of(
                    new TestIndexedRaftLogEntry(
                        l, 1, new ApplicationEntry(1, 10, new UnsafeBuffer()))),
            db -> Long.MAX_VALUE);
    receiverStore.addSnapshotListener(receiverSnapshotController);

    autoCloseableRule.manage(replicatorSnapshotController);
    autoCloseableRule.manage(receiverSnapshotController);
    replicatorSnapshotController.openDb();
  }

  @Test
  public void shouldNotWriteChunksAfterReceivingInvalidChunk() throws Exception {
    // given
    final EvilReplicator replicator = new EvilReplicator();
    setup(replicator);
    final var transientSnapshot = takeSnapshot();

    // when
    transientSnapshot.persist().join();

    // then
    final List<SnapshotChunk> replicatedChunks = replicator.replicatedChunks;
    assertThat(replicatedChunks.size()).isGreaterThan(0);

    assertThat(receiverStore.getLatestSnapshot()).isEmpty();
  }

  @Test
  public void shouldNotMarkSnapshotAsValidIfNotReceivedAllChunks() throws Exception {
    // given
    final FlakyReplicator replicator = new FlakyReplicator();
    setup(replicator);
    final var transientSnapshot = takeSnapshot();

    // when
    transientSnapshot.persist().join();

    // then
    final List<SnapshotChunk> replicatedChunks = replicator.replicatedChunks;
    assertThat(replicatedChunks.size()).isGreaterThan(0);
    assertThat(receiverStore.getLatestSnapshot()).isEmpty();
  }

  private TransientSnapshot takeSnapshot() {
    receiverSnapshotController.consumeReplicatedSnapshots();
    return replicatorSnapshotController.takeTransientSnapshot(1).orElseThrow();
  }

  private final class FlakyReplicator implements SnapshotReplication {

    final List<SnapshotChunk> replicatedChunks = new ArrayList<>();
    private Consumer<SnapshotChunk> chunkConsumer;
    // Consumers are usually running on a separate thread
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Override
    public void replicate(final SnapshotChunk snapshot) {
      replicatedChunks.add(snapshot);
      if (chunkConsumer != null) {
        if (replicatedChunks.size() < 3) {
          executorService.execute(() -> chunkConsumer.accept(snapshot));
        }
      }
    }

    @Override
    public void consume(final Consumer<SnapshotChunk> consumer) {
      chunkConsumer = consumer;
    }

    @Override
    public void close() {}
  }

  private final class EvilReplicator implements SnapshotReplication {

    final List<SnapshotChunk> replicatedChunks = new ArrayList<>();
    private Consumer<SnapshotChunk> chunkConsumer;
    // Consumers are usually running on a separate thread
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Override
    public void replicate(final SnapshotChunk snapshot) {
      replicatedChunks.add(snapshot);
      if (chunkConsumer != null) {
        executorService.execute(
            () ->
                chunkConsumer.accept(
                    replicatedChunks.size() > 1 ? new DisruptedSnapshotChunk(snapshot) : snapshot));
      }
    }

    @Override
    public void consume(final Consumer<SnapshotChunk> consumer) {
      chunkConsumer = consumer;
    }

    @Override
    public void close() {}
  }

  private final class DisruptedSnapshotChunk implements SnapshotChunk {

    private final SnapshotChunk snapshotChunk;

    DisruptedSnapshotChunk(final SnapshotChunk snapshotChunk) {
      this.snapshotChunk = snapshotChunk;
    }

    @Override
    public String getSnapshotId() {
      return snapshotChunk.getSnapshotId();
    }

    @Override
    public int getTotalCount() {
      return snapshotChunk.getTotalCount();
    }

    @Override
    public String getChunkName() {
      return snapshotChunk.getChunkName();
    }

    @Override
    public long getChecksum() {
      return 0;
    }

    @Override
    public byte[] getContent() {
      return snapshotChunk.getContent();
    }

    @Override
    public long getSnapshotChecksum() {
      return snapshotChunk.getSnapshotChecksum();
    }
  }
}
