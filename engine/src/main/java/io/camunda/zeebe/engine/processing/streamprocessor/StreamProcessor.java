/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.streamprocessor;

import io.camunda.zeebe.db.TransactionContext;
import io.camunda.zeebe.db.ZeebeDb;
import io.camunda.zeebe.engine.metrics.StreamProcessorMetrics;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.TypedStreamWriterImpl;
import io.camunda.zeebe.engine.state.EventApplier;
import io.camunda.zeebe.engine.state.ZeebeDbState;
import io.camunda.zeebe.engine.state.mutable.MutableZeebeState;
import io.camunda.zeebe.logstreams.impl.Loggers;
import io.camunda.zeebe.logstreams.log.LogStream;
import io.camunda.zeebe.logstreams.log.LogStreamBatchWriter;
import io.camunda.zeebe.logstreams.log.LogStreamReader;
import io.camunda.zeebe.util.exception.UnrecoverableException;
import io.camunda.zeebe.util.health.FailureListener;
import io.camunda.zeebe.util.health.HealthMonitorable;
import io.camunda.zeebe.util.health.HealthStatus;
import io.camunda.zeebe.util.sched.Actor;
import io.camunda.zeebe.util.sched.ActorCondition;
import io.camunda.zeebe.util.sched.ActorScheduler;
import io.camunda.zeebe.util.sched.clock.ActorClock;
import io.camunda.zeebe.util.sched.future.ActorFuture;
import io.camunda.zeebe.util.sched.future.CompletableActorFuture;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;

public class StreamProcessor extends Actor implements HealthMonitorable {

  public static final long UNSET_POSITION = -1L;
  public static final Duration HEALTH_CHECK_TICK_DURATION = Duration.ofSeconds(5);

  private static final String ERROR_MESSAGE_RECOVER_FROM_SNAPSHOT_FAILED =
      "Expected to find event with the snapshot position %s in log stream, but nothing was found. Failed to recover '%s'.";
  private static final Logger LOG = Loggers.LOGSTREAMS_LOGGER;
  private final ActorScheduler actorScheduler;
  private final AtomicBoolean isOpened = new AtomicBoolean(false);
  private final List<StreamProcessorLifecycleAware> lifecycleAwareListeners;
  private final Function<MutableZeebeState, EventApplier> eventApplierFactory;
  private final Set<FailureListener> failureListeners = new HashSet<>();

  // log stream
  private final LogStream logStream;
  private final int partitionId;
  // snapshotting
  private final ZeebeDb zeebeDb;
  // processing
  private final ProcessingContext processingContext;
  private final TypedRecordProcessorFactory typedRecordProcessorFactory;
  private final String actorName;
  private LogStreamReader logStreamReader;
  private ActorCondition onCommitPositionUpdatedCondition;
  private long snapshotPosition = -1L;
  private ProcessingStateMachine processingStateMachine;

  private volatile Phase phase = Phase.REPROCESSING;

  private CompletableActorFuture<Void> openFuture;
  private CompletableActorFuture<Void> closeFuture = CompletableActorFuture.completed(null);
  private volatile long lastTickTime;
  private boolean shouldProcess = true;
  /** Recover future is completed after reprocessing is done. */
  private ActorFuture<Long> recoverFuture;

  protected StreamProcessor(final StreamProcessorBuilder processorBuilder) {
    actorScheduler = processorBuilder.getActorScheduler();
    lifecycleAwareListeners = processorBuilder.getLifecycleListeners();

    typedRecordProcessorFactory = processorBuilder.getTypedRecordProcessorFactory();
    zeebeDb = processorBuilder.getZeebeDb();
    eventApplierFactory = processorBuilder.getEventApplierFactory();

    processingContext =
        processorBuilder
            .getProcessingContext()
            .eventCache(new RecordValues())
            .actor(actor)
            .abortCondition(this::isClosed);
    logStream = processingContext.getLogStream();
    partitionId = logStream.getPartitionId();
    actorName = buildActorName(processorBuilder.getNodeId(), "StreamProcessor", partitionId);
  }

  public static StreamProcessorBuilder builder() {
    return new StreamProcessorBuilder();
  }

  @Override
  public String getName() {
    return actorName;
  }

  @Override
  protected void onActorStarting() {
    actor.runOnCompletionBlockingCurrentPhase(
        logStream.newLogStreamBatchWriter(), this::onRetrievingWriter);
  }

  @Override
  protected void onActorStarted() {
    try {
      LOG.debug("Recovering state of partition {} from snapshot", partitionId);
      final long startTime = System.currentTimeMillis();
      snapshotPosition = recoverFromSnapshot();

      initProcessors();

      processingStateMachine =
          new ProcessingStateMachine(processingContext, this::shouldProcessNext);

      healthCheckTick();
      openFuture.complete(null);

      final var replayStateMachine = new ReplayStateMachine(processingContext);

      // disable writing to the log stream but for reprocessing checks
      processingContext.disableLogStreamWriter();
      processingContext.enableReprocessingStreamWriter();

      recoverFuture = replayStateMachine.startRecover(snapshotPosition);

      actor.runOnCompletion(
          recoverFuture,
          (lastReprocessedPosition, throwable) -> {
            if (throwable != null) {
              LOG.error("Unexpected error on recovery happens.", throwable);
              onFailure(throwable);
            } else {
              onRecovered(lastReprocessedPosition);
              new StreamProcessorMetrics(partitionId)
                  .recoveryTime(System.currentTimeMillis() - startTime);
            }
          });
    } catch (final RuntimeException e) {
      onFailure(e);
    }
  }

  @Override
  protected void onActorClosing() {
    tearDown();
  }

  @Override
  protected void onActorClosed() {
    closeFuture.complete(null);
    LOG.debug("Closed stream processor controller {}.", getName());
  }

  @Override
  protected void onActorCloseRequested() {
    if (!isFailed()) {
      lifecycleAwareListeners.forEach(StreamProcessorLifecycleAware::onClose);
    }
  }

  @Override
  public ActorFuture<Void> closeAsync() {
    if (isOpened.compareAndSet(true, false)) {
      closeFuture = new CompletableActorFuture<>();
      actor.close();
    }
    return closeFuture;
  }

  @Override
  protected void handleFailure(final Exception failure) {
    onFailure(failure);
  }

  @Override
  public void onActorFailed() {
    phase = Phase.FAILED;
    closeFuture = CompletableActorFuture.completed(null);
    isOpened.set(false);
    lifecycleAwareListeners.forEach(StreamProcessorLifecycleAware::onFailed);
    tearDown();
  }

  private boolean shouldProcessNext() {
    return isOpened() && shouldProcess;
  }

  private void tearDown() {
    processingContext.getLogStreamReader().close();

    if (onCommitPositionUpdatedCondition != null) {
      logStream.removeOnCommitPositionUpdatedCondition(onCommitPositionUpdatedCondition);
      onCommitPositionUpdatedCondition = null;
    }
  }

  private void healthCheckTick() {
    lastTickTime = ActorClock.currentTimeMillis();
    actor.runDelayed(HEALTH_CHECK_TICK_DURATION, this::healthCheckTick);
  }

  private void onRetrievingWriter(
      final LogStreamBatchWriter batchWriter, final Throwable errorOnReceivingWriter) {

    if (errorOnReceivingWriter == null) {
      processingContext
          .maxFragmentSize(batchWriter.getMaxFragmentLength())
          .logStreamWriter(new TypedStreamWriterImpl(batchWriter));

      actor.runOnCompletionBlockingCurrentPhase(
          logStream.newLogStreamReader(), this::onRetrievingReader);
    } else {
      LOG.error(
          "Unexpected error on retrieving batch writer from log stream.", errorOnReceivingWriter);
      actor.close();
    }
  }

  private void onRetrievingReader(
      final LogStreamReader reader, final Throwable errorOnReceivingReader) {
    if (errorOnReceivingReader == null) {
      logStreamReader = reader;
      processingContext.logStreamReader(reader);
    } else {
      LOG.error("Unexpected error on retrieving reader from log stream.", errorOnReceivingReader);
      actor.close();
    }
  }

  public ActorFuture<Void> openAsync(final boolean pauseOnStart) {
    if (isOpened.compareAndSet(false, true)) {
      shouldProcess = !pauseOnStart;
      openFuture = new CompletableActorFuture<>();
      actorScheduler.submitActor(this);
    }
    return openFuture;
  }

  private void initProcessors() {
    final TypedRecordProcessors typedRecordProcessors =
        typedRecordProcessorFactory.createProcessors(processingContext);

    lifecycleAwareListeners.addAll(typedRecordProcessors.getLifecycleListeners());
    final RecordProcessorMap recordProcessorMap = typedRecordProcessors.getRecordProcessorMap();
    recordProcessorMap.values().forEachRemaining(lifecycleAwareListeners::add);

    processingContext.recordProcessorMap(recordProcessorMap);
  }

  private long recoverFromSnapshot() {
    final var zeebeState = recoverState();

    final long snapshotPosition =
        zeebeState.getLastProcessedPositionState().getLastSuccessfulProcessedRecordPosition();

    final boolean failedToRecoverReader = !logStreamReader.seekToNextEvent(snapshotPosition);
    if (failedToRecoverReader) {
      throw new IllegalStateException(
          String.format(ERROR_MESSAGE_RECOVER_FROM_SNAPSHOT_FAILED, snapshotPosition, getName()));
    }

    LOG.info(
        "Recovered state of partition {} from snapshot at position {}",
        partitionId,
        snapshotPosition);
    return snapshotPosition;
  }

  private ZeebeDbState recoverState() {
    final TransactionContext transactionContext = zeebeDb.createContext();
    final ZeebeDbState zeebeState = new ZeebeDbState(partitionId, zeebeDb, transactionContext);

    processingContext.transactionContext(transactionContext);
    processingContext.zeebeState(zeebeState);
    processingContext.eventApplier(eventApplierFactory.apply(zeebeState));

    return zeebeState;
  }

  private void onRecovered(final long lastReprocessedPosition) {
    phase = Phase.PROCESSING;

    // enable writing records to the stream
    processingContext.enableLogStreamWriter();

    onCommitPositionUpdatedCondition =
        actor.onCondition(
            getName() + "-on-commit-position-updated", processingStateMachine::readNextEvent);
    logStream.registerOnCommitPositionUpdatedCondition(onCommitPositionUpdatedCondition);

    // start reading
    lifecycleAwareListeners.forEach(l -> l.onRecovered(processingContext));
    processingStateMachine.startProcessing(lastReprocessedPosition);
    if (!shouldProcess) {
      setStateToPausedAndNotifyListeners();
    }
  }

  private void onFailure(final Throwable throwable) {
    LOG.error("Actor {} failed in phase {}.", actorName, actor.getLifecyclePhase(), throwable);
    actor.fail();
    if (!openFuture.isDone()) {
      openFuture.completeExceptionally(throwable);
    }

    if (throwable instanceof UnrecoverableException) {
      failureListeners.forEach(FailureListener::onUnrecoverableFailure);
    } else {
      failureListeners.forEach(FailureListener::onFailure);
    }
  }

  public boolean isOpened() {
    return isOpened.get();
  }

  public boolean isClosed() {
    return !isOpened.get();
  }

  public boolean isFailed() {
    return phase == Phase.FAILED;
  }

  public ActorFuture<Long> getLastProcessedPositionAsync() {
    return actor.call(processingStateMachine::getLastSuccessfulProcessedEventPosition);
  }

  public ActorFuture<Long> getLastWrittenPositionAsync() {
    return actor.call(processingStateMachine::getLastWrittenEventPosition);
  }

  @Override
  public HealthStatus getHealthStatus() {
    if (actor.isClosed()) {
      return HealthStatus.UNHEALTHY;
    }

    if (!processingStateMachine.isMakingProgress()) {
      return HealthStatus.UNHEALTHY;
    }

    // If healthCheckTick was not invoked it indicates the actor is blocked in a runUntilDone loop.
    if (ActorClock.currentTimeMillis() - lastTickTime > HEALTH_CHECK_TICK_DURATION.toMillis() * 2) {
      return HealthStatus.UNHEALTHY;
    } else if (phase == Phase.FAILED) {
      return HealthStatus.UNHEALTHY;
    } else {
      return HealthStatus.HEALTHY;
    }
  }

  @Override
  public void addFailureListener(final FailureListener failureListener) {
    actor.run(() -> failureListeners.add(failureListener));
  }

  @Override
  public void removeFailureListener(final FailureListener failureListener) {
    actor.run(() -> failureListeners.remove(failureListener));
  }

  public ActorFuture<Phase> getCurrentPhase() {
    return actor.call(() -> phase);
  }

  public ActorFuture<Void> pauseProcessing() {
    return actor.call(
        () ->
            recoverFuture.onComplete(
                (v, t) -> {
                  if (shouldProcess) {
                    setStateToPausedAndNotifyListeners();
                  }
                }));
  }

  private void setStateToPausedAndNotifyListeners() {
    lifecycleAwareListeners.forEach(StreamProcessorLifecycleAware::onPaused);
    shouldProcess = false;
    phase = Phase.PAUSED;
    LOG.debug("Paused processing for partition {}", partitionId);
  }

  public void resumeProcessing() {
    actor.call(
        () ->
            recoverFuture.onComplete(
                (v, t) -> {
                  if (!shouldProcess) {
                    lifecycleAwareListeners.forEach(StreamProcessorLifecycleAware::onResumed);
                    shouldProcess = true;
                    phase = Phase.PROCESSING;
                    actor.submit(processingStateMachine::readNextEvent);
                    LOG.debug("Resumed processing for partition {}", partitionId);
                  }
                }));
  }

  public enum Phase {
    REPROCESSING,
    PROCESSING,
    FAILED,
    PAUSED,
  }
}
