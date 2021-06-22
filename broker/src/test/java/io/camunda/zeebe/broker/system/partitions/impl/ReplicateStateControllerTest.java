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
import io.camunda.zeebe.logstreams.util.RocksDBWrapper;
import io.camunda.zeebe.snapshots.ConstructableSnapshotStore;
import io.camunda.zeebe.snapshots.ReceivableSnapshotStore;
import io.camunda.zeebe.snapshots.SnapshotChunk;
import io.camunda.zeebe.snapshots.impl.FileBasedSnapshotStoreFactory;
import io.camunda.zeebe.test.util.AutoCloseableRule;
import io.camunda.zeebe.util.sched.testing.ActorSchedulerRule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.zip.CRC32C;
import org.agrona.concurrent.UnsafeBuffer;
import org.awaitility.Awaitility;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

@Ignore("State on followers will not replicate snapshots")
public final class ReplicateStateControllerTest {

  private static final int VALUE = 0xCAFE;
  private static final String KEY = "test";

  @Rule public final TemporaryFolder tempFolderRule = new TemporaryFolder();
  @Rule public final AutoCloseableRule autoCloseableRule = new AutoCloseableRule();
  @Rule public final ActorSchedulerRule actorSchedulerRule = new ActorSchedulerRule();

  private StateControllerImpl replicatorSnapshotController;
  private StateControllerImpl receiverSnapshotController;
  private Replicator replicator;
  private ConstructableSnapshotStore senderStore;
  private ReceivableSnapshotStore receiverStore;

  @Before
  public void setup() throws IOException {
    final var senderRoot = tempFolderRule.newFolder("sender").toPath();

    final var senderFactory = new FileBasedSnapshotStoreFactory(actorSchedulerRule.get(), 1);
    senderFactory.createReceivableSnapshotStore(senderRoot, 1);
    senderStore = senderFactory.getConstructableSnapshotStore(1);

    final var receiverRoot = tempFolderRule.newFolder("receiver").toPath();
    final var receiverFactory = new FileBasedSnapshotStoreFactory(actorSchedulerRule.get(), 2);
    receiverStore = receiverFactory.createReceivableSnapshotStore(receiverRoot, 1);

    replicator = new Replicator();
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
            receiverStore,
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

    final RocksDBWrapper wrapper = new RocksDBWrapper();
    wrapper.wrap(replicatorSnapshotController.openDb());
    wrapper.putInt(KEY, VALUE);
  }

  @Test
  public void shouldReplicateSnapshotChunks() {
    // given
    final var snapshot = replicatorSnapshotController.takeTransientSnapshot(1).orElseThrow();

    // when
    snapshot.persist().join();

    // then
    final List<SnapshotChunk> replicatedChunks = replicator.replicatedChunks;
    final int totalCount = replicatedChunks.size();
    assertThat(totalCount).isGreaterThan(0);

    final SnapshotChunk firstChunk = replicatedChunks.get(0);
    final int chunkTotalCount = firstChunk.getTotalCount();
    assertThat(totalCount).isEqualTo(chunkTotalCount);

    assertThat(replicatedChunks).extracting(SnapshotChunk::getTotalCount).containsOnly(totalCount);

    assertThat(replicatedChunks)
        .extracting(SnapshotChunk::getSnapshotId)
        .flatExtracting(chunk -> List.of(chunk.split("-")))
        .contains("1");
  }

  @Test
  public void shouldContainChecksumPerChunk() {
    // given
    final var snapshot = replicatorSnapshotController.takeTransientSnapshot(1).orElseThrow();

    // when
    snapshot.persist().join();

    // then
    final List<SnapshotChunk> replicatedChunks = replicator.replicatedChunks;
    assertThat(replicatedChunks.size()).isGreaterThan(0);

    replicatedChunks.forEach(
        chunk -> {
          final CRC32C crc32 = new CRC32C();
          crc32.update(chunk.getContent());
          assertThat(chunk.getChecksum()).isEqualTo(crc32.getValue());
        });
  }

  @Test
  public void shouldReceiveSnapshotChunks() throws Exception {
    // given
    receiverSnapshotController.consumeReplicatedSnapshots();
    final var snapshot = replicatorSnapshotController.takeTransientSnapshot(1).orElseThrow();
    final CompletableFuture<Void> snapshotTaken = new CompletableFuture<>();
    snapshot.onSnapshotTaken((ignore, error) -> snapshotTaken.complete(null));
    snapshotTaken.join();

    // when
    snapshot.persist().join();
    Awaitility.await()
        .until(() -> receiverStore.hasSnapshotId(snapshot.snapshotId().getSnapshotIdAsString()));

    // then
    final RocksDBWrapper wrapper = new RocksDBWrapper();
    receiverSnapshotController.recover();
    assertThat(receiverSnapshotController.isDbOpened()).isTrue();

    wrapper.wrap(receiverSnapshotController.openDb());
    final int valueFromSnapshot = wrapper.getInt(KEY);
    assertThat(valueFromSnapshot).isEqualTo(VALUE);
  }

  @Test
  public void shouldNotFailOnReplicatingAndReceivingTwice() throws Exception {
    // given
    receiverSnapshotController.consumeReplicatedSnapshots();
    final var transientSnapshot =
        replicatorSnapshotController.takeTransientSnapshot(1).orElseThrow();
    final CompletableFuture<Void> snapshotTaken = new CompletableFuture<>();
    transientSnapshot.onSnapshotTaken((ignor, error) -> snapshotTaken.complete(null));
    snapshotTaken.join();

    final var snapshot = transientSnapshot.persist().join();
    Awaitility.await()
        .until(
            () ->
                receiverStore.hasSnapshotId(
                    transientSnapshot.snapshotId().getSnapshotIdAsString()));

    // when
    receiverSnapshotController.onNewSnapshot(snapshot);

    // then
    final RocksDBWrapper wrapper = new RocksDBWrapper();
    receiverSnapshotController.recover();
    assertThat(receiverSnapshotController.isDbOpened()).isTrue();

    wrapper.wrap(receiverSnapshotController.openDb());
    final int valueFromSnapshot = wrapper.getInt(KEY);
    assertThat(valueFromSnapshot).isEqualTo(VALUE);
  }

  protected static final class Replicator implements SnapshotReplication {

    final List<SnapshotChunk> replicatedChunks = new ArrayList<>();
    private Consumer<SnapshotChunk> chunkConsumer;
    // Consumers are usually running on a separate thread
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Override
    public void replicate(final SnapshotChunk snapshot) {
      replicatedChunks.add(snapshot);
      if (chunkConsumer != null) {
        executorService.execute(() -> chunkConsumer.accept(snapshot));
      }
    }

    @Override
    public void consume(final Consumer<SnapshotChunk> consumer) {
      chunkConsumer = consumer;
    }

    @Override
    public void close() {}
  }
}
