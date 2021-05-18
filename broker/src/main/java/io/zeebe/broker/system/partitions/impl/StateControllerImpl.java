/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.system.partitions.impl;

import io.zeebe.broker.system.partitions.AtomixRecordEntrySupplier;
import io.zeebe.broker.system.partitions.SnapshotReplication;
import io.zeebe.broker.system.partitions.StateController;
import io.zeebe.db.ZeebeDb;
import io.zeebe.db.ZeebeDbFactory;
import io.zeebe.logstreams.impl.Loggers;
import io.zeebe.snapshots.broker.ConstructableSnapshotStore;
import io.zeebe.snapshots.raft.PersistedSnapshot;
import io.zeebe.snapshots.raft.PersistedSnapshotListener;
import io.zeebe.snapshots.raft.ReceivableSnapshotStore;
import io.zeebe.snapshots.raft.ReceivedSnapshot;
import io.zeebe.snapshots.raft.SnapshotChunk;
import io.zeebe.snapshots.raft.TransientSnapshot;
import io.zeebe.util.FileUtil;
import io.zeebe.util.sched.future.ActorFuture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToLongFunction;
import org.agrona.collections.Object2NullableObjectHashMap;
import org.slf4j.Logger;

/** Controls how snapshot/recovery operations are performed */
public class StateControllerImpl implements StateController, PersistedSnapshotListener {

  private static final ReplicationContext INVALID_SNAPSHOT = new ReplicationContext(null, -1, null);
  private static final Logger LOG = Loggers.SNAPSHOT_LOGGER;

  private final SnapshotReplication replication;
  private final Map<String, ReplicationContext> receivedSnapshots =
      new Object2NullableObjectHashMap<>();

  private final Path runtimeDirectory;
  private final ZeebeDbFactory zeebeDbFactory;
  private final ToLongFunction<ZeebeDb> exporterPositionSupplier;
  private final AtomixRecordEntrySupplier entrySupplier;

  private final SnapshotReplicationMetrics metrics;

  private ZeebeDb db;
  private final ConstructableSnapshotStore constructableSnapshotStore;
  private final ReceivableSnapshotStore receivableSnapshotStore;

  public StateControllerImpl(
      final int partitionId,
      final ZeebeDbFactory zeebeDbFactory,
      final ConstructableSnapshotStore constructableSnapshotStore,
      final ReceivableSnapshotStore receivableSnapshotStore,
      final Path runtimeDirectory,
      final SnapshotReplication replication,
      final AtomixRecordEntrySupplier entrySupplier,
      final ToLongFunction<ZeebeDb> exporterPositionSupplier) {
    this.constructableSnapshotStore = constructableSnapshotStore;
    this.receivableSnapshotStore = receivableSnapshotStore;
    this.runtimeDirectory = runtimeDirectory;
    this.zeebeDbFactory = zeebeDbFactory;
    this.exporterPositionSupplier = exporterPositionSupplier;
    this.entrySupplier = entrySupplier;
    this.replication = replication;
    metrics = new SnapshotReplicationMetrics(Integer.toString(partitionId));
  }

  @Override
  public Optional<TransientSnapshot> takeTransientSnapshot(final long lowerBoundSnapshotPosition) {
    if (!isDbOpened()) {
      LOG.warn(
          "Expected to take snapshot for last processed position {}, but database was closed.",
          lowerBoundSnapshotPosition);
      return Optional.empty();
    }

    final long exportedPosition = exporterPositionSupplier.applyAsLong(openDb());
    final long snapshotPosition =
        determineSnapshotPosition(lowerBoundSnapshotPosition, exportedPosition);
    final var optionalIndexed = entrySupplier.getIndexedEntry(snapshotPosition);
    if (optionalIndexed.isEmpty()) {
      LOG.warn(
          "Failed to take snapshot. Expected to find an indexed entry for determined snapshot position {}, but found no matching indexed entry which contains this position.",
          snapshotPosition);
      return Optional.empty();
    }

    final var snapshotIndexedEntry = optionalIndexed.get();
    final Optional<TransientSnapshot> transientSnapshot =
        constructableSnapshotStore.newTransientSnapshot(
            snapshotIndexedEntry.index(),
            snapshotIndexedEntry.entry().term(),
            lowerBoundSnapshotPosition,
            exportedPosition);
    transientSnapshot.ifPresent(this::takeSnapshot);
    return transientSnapshot;
  }

  @Override
  public void consumeReplicatedSnapshots() {
    replication.consume(this::consumeSnapshotChunk);
  }

  @Override
  public void recover() throws Exception {

    if (Files.exists(runtimeDirectory)) {
      FileUtil.deleteFolder(runtimeDirectory);
    }

    final var optLatestSnapshot = constructableSnapshotStore.getLatestSnapshot();
    if (optLatestSnapshot.isPresent()) {
      final var snapshot = optLatestSnapshot.get();
      LOG.debug("Available snapshot: {}", snapshot);

      FileUtil.copySnapshot(runtimeDirectory, snapshot.getPath());

      try {
        // open database to verify that the snapshot is recoverable
        openDb();
        LOG.debug("Recovered state from snapshot '{}'", snapshot);
      } catch (final Exception exception) {
        LOG.error(
            "Failed to open snapshot '{}'. No snapshots available to recover from. Manual action is required.",
            snapshot,
            exception);

        FileUtil.deleteFolder(runtimeDirectory);
        throw new IllegalStateException("Failed to recover from snapshots", exception);
      }
    }
  }

  @Override
  public ZeebeDb openDb() {
    if (db == null) {
      db = zeebeDbFactory.createDb(runtimeDirectory.toFile());
      LOG.debug("Opened database from '{}'.", runtimeDirectory);
      final var filesBeforeCompact = runtimeDirectory.toFile().listFiles().length;
      db.compactRange();
      final var filesAfterCompact = runtimeDirectory.toFile().listFiles().length;
      LOG.info(
          "Attempted to compact on startup - file count before {} after {}",
          filesBeforeCompact,
          filesAfterCompact);
    }

    return db;
  }

  @Override
  public int getValidSnapshotsCount() {
    return constructableSnapshotStore.getLatestSnapshot().isPresent() ? 1 : 0;
  }

  @Override
  public void close() throws Exception {
    if (db != null) {
      db.close();
      LOG.debug("Closed database from '{}'.", runtimeDirectory);
      db = null;
    }
  }

  boolean isDbOpened() {
    return db != null;
  }

  private ActorFuture<Boolean> takeSnapshot(final TransientSnapshot snapshot) {
    return snapshot.take(
        snapshotDir -> {
          if (db == null) {
            LOG.error("Expected to take a snapshot, but no database was opened");
            return false;
          }

          LOG.debug("Taking temporary snapshot into {}.", snapshotDir);
          try {
            db.createSnapshot(snapshotDir.toFile());
          } catch (final Exception e) {
            LOG.error("Failed to create snapshot of runtime database", e);
            return false;
          }

          return true;
        });
  }

  @Override
  public void onNewSnapshot(final PersistedSnapshot newPersistedSnapshot) {
    LOG.debug("New snapshot {} was persisted. Start replicating.", newPersistedSnapshot.getId());
    // replicate snapshots when new snapshot was committed
    try (final var snapshotChunkReader = newPersistedSnapshot.newChunkReader()) {
      while (snapshotChunkReader.hasNext()) {
        final var snapshotChunk = snapshotChunkReader.next();
        replication.replicate(snapshotChunk);
      }
    }
  }

  /**
   * This is called by the snapshot replication implementation on each snapshot chunk
   *
   * @param snapshotChunk the chunk to consume
   */
  private void consumeSnapshotChunk(final SnapshotChunk snapshotChunk) {
    final String snapshotId = snapshotChunk.getSnapshotId();
    final String chunkName = snapshotChunk.getChunkName();

    final ReplicationContext context =
        receivedSnapshots.computeIfAbsent(
            snapshotId,
            id -> {
              final var startTimestamp = System.currentTimeMillis();
              final ReceivedSnapshot transientSnapshot =
                  receivableSnapshotStore.newReceivedSnapshot(snapshotChunk.getSnapshotId());
              return newReplication(startTimestamp, transientSnapshot);
            });
    if (context == INVALID_SNAPSHOT) {
      LOG.trace(
          "Ignore snapshot chunk {}, because snapshot {} is marked as invalid.",
          chunkName,
          snapshotId);
      return;
    }

    try {
      if (context.apply(snapshotChunk)) {
        validateWhenReceivedAllChunks(snapshotChunk, context);
      } else {
        markSnapshotAsInvalid(context, snapshotChunk);
      }
    } catch (final IOException e) {
      LOG.error("Unexpected error on writing the received snapshot chunk {}", snapshotChunk, e);
      markSnapshotAsInvalid(context, snapshotChunk);
    }
  }

  private void markSnapshotAsInvalid(
      final ReplicationContext replicationContext, final SnapshotChunk chunk) {
    LOG.debug("Abort snapshot {} and mark it as invalid.", chunk.getSnapshotId());
    replicationContext.abort();
    receivedSnapshots.put(chunk.getSnapshotId(), INVALID_SNAPSHOT);
  }

  private void validateWhenReceivedAllChunks(
      final SnapshotChunk snapshotChunk, final ReplicationContext context) {
    final int totalChunkCount = snapshotChunk.getTotalCount();

    if (context.incrementCount() == totalChunkCount) {
      LOG.debug(
          "Received all snapshot chunks ({}/{}), snapshot {} is valid",
          context.getChunkCount(),
          totalChunkCount,
          snapshotChunk.getSnapshotId());
      if (!tryToMarkSnapshotAsValid(snapshotChunk, context)) {
        LOG.debug("Failed to mark snapshot {} as valid", snapshotChunk.getSnapshotId());
      }
    } else {
      LOG.debug(
          "Waiting for more snapshot chunks of snapshot {}, currently have {}/{}",
          snapshotChunk.getSnapshotId(),
          context.getChunkCount(),
          totalChunkCount);
    }
  }

  private boolean tryToMarkSnapshotAsValid(
      final SnapshotChunk snapshotChunk, final ReplicationContext context) {
    try {
      context.persist();
    } catch (final Exception exception) {
      markSnapshotAsInvalid(context, snapshotChunk);
      LOG.warn("Unexpected error on persisting received snapshot.", exception);
      return false;
    } finally {
      receivedSnapshots.remove(snapshotChunk.getSnapshotId());
    }
    return true;
  }

  private ReplicationContext newReplication(
      final long startTimestamp, final ReceivedSnapshot transientSnapshot) {
    return new ReplicationContext(metrics, startTimestamp, transientSnapshot);
  }

  private long determineSnapshotPosition(
      final long lowerBoundSnapshotPosition, final long exportedPosition) {
    final long snapshotPosition = Math.min(exportedPosition, lowerBoundSnapshotPosition);
    LOG.debug(
        "Based on lowest exporter position '{}' and last processed position '{}', determined '{}' as snapshot position.",
        exportedPosition,
        lowerBoundSnapshotPosition,
        snapshotPosition);
    return snapshotPosition;
  }

  private static final class ReplicationContext {

    private final long startTimestamp;
    private final ReceivedSnapshot receivedSnapshot;
    private final SnapshotReplicationMetrics metrics;
    private long chunkCount;

    ReplicationContext(
        final SnapshotReplicationMetrics metrics,
        final long startTimestamp,
        final ReceivedSnapshot receivedSnapshot) {
      this.metrics = metrics;
      if (metrics != null) {
        metrics.incrementCount();
      }
      this.startTimestamp = startTimestamp;
      chunkCount = 0L;
      this.receivedSnapshot = receivedSnapshot;
    }

    long incrementCount() {
      return ++chunkCount;
    }

    long getChunkCount() {
      return chunkCount;
    }

    void abort() {
      try {
        receivedSnapshot.abort();
      } finally {
        metrics.decrementCount();
      }
    }

    void persist() {
      try {
        receivedSnapshot.persist();
      } finally {
        final var end = System.currentTimeMillis();
        metrics.decrementCount();
        metrics.observeDuration(end - startTimestamp);
      }
    }

    public boolean apply(final SnapshotChunk snapshotChunk) throws IOException {
      return receivedSnapshot.apply(snapshotChunk).join();
    }
  }
}
