/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.streamprocessor;

import io.camunda.zeebe.db.TransactionContext;
import io.camunda.zeebe.db.TransactionOperation;
import io.camunda.zeebe.db.ZeebeDbTransaction;
import io.camunda.zeebe.engine.state.EventApplier;
import io.camunda.zeebe.engine.state.KeyGeneratorControls;
import io.camunda.zeebe.engine.state.mutable.MutableLastProcessedPositionState;
import io.camunda.zeebe.logstreams.impl.Loggers;
import io.camunda.zeebe.logstreams.log.LogStreamReader;
import io.camunda.zeebe.logstreams.log.LoggedEvent;
import io.camunda.zeebe.protocol.impl.record.RecordMetadata;
import io.camunda.zeebe.protocol.impl.record.UnifiedRecordValue;
import io.camunda.zeebe.protocol.record.RecordType;
import io.camunda.zeebe.protocol.record.ValueType;
import io.camunda.zeebe.util.retry.EndlessRetryStrategy;
import io.camunda.zeebe.util.retry.RetryStrategy;
import io.camunda.zeebe.util.sched.ActorControl;
import io.camunda.zeebe.util.sched.future.ActorFuture;
import io.camunda.zeebe.util.sched.future.CompletableActorFuture;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;

/**
 * Represents the reprocessing state machine, which is executed on reprocessing.
 *
 * <pre>
 * +------------------+   +-------------+           +------------------------+
 * |                  |   |             |           |                        |
 * |  startRecover()  |--->  scanLog()  |---------->|  reprocessNextEvent()  |
 * |                  |   |             |           |                        |
 * +------------------+   +---+---------+           +-----^------+-----------+
 *                            |                           |      |
 * +-----------------+        | no source events          |      |
 * |                 |        |                           |      |
 * |  onRecovered()  <--------+                           |      |    +--------------------+
 * |                 |                                    |      |    |                    |
 * +--------^--------+                hasNext             |      +--->|  reprocessEvent()  |
 *          |            +--------------------------------+           |                    |
 *          |            |                                            +----+----------+----+
 *          |            |                                                 |          |
 *   +------+------------+-----+                                           |          |
 *   |                         |               no event processor          |          |
 *   |  onRecordReprocessed()  |<------------------------------------------+          |
 *   |                         |                                                      |
 *   +---------^---------------+                                                      |
 *             |                                                                      |
 *             |      +--------------------------+       +----------------------+     |
 *             |      |                          |       |                      |     |
 *             +------+  updateStateUntilDone()  <-------+  processUntilDone()  |<----+
 *                    |                          |       |                      |
 *                    +------^------------+------+       +---^------------+-----+
 *                           |            |                  |            |
 *                           +------------+                  +------------+
 *                             exception                       exception
 * </pre>
 *
 * See https://textik.com/#773271ce7ea2096a
 */
public final class ReplayStateMachine {

  private static final Logger LOG = Loggers.PROCESSOR_LOGGER;
  private static final String ERROR_MESSAGE_ON_EVENT_FAILED_SKIP_EVENT =
      "Expected to find event processor for event '{} {}', but caught an exception. Skip this event.";
  private static final String LOG_STMT_REPROCESSING_FINISHED = "Processor finished replay.";
  private static final String LOG_STMT_FAILED_ON_PROCESSING =
      "Event {} failed on processing last time, will call #onError to update process instance blacklist.";

  private final KeyGeneratorControls keyGeneratorControls;
  private final MutableLastProcessedPositionState lastProcessedPositionState;
  private final ActorControl actor;

  private final RecordValues recordValues;

  private final EventFilter eventFilter =
      new MetadataEventFilter(
          new RecordProtocolVersionFilter()
              // we only replay events
              .and(metadata -> metadata.getRecordType() == RecordType.EVENT));

  private final LogStreamReader logStreamReader;
  private final EventApplier eventApplier;

  private final TransactionContext transactionContext;
  private final RetryStrategy updateStateRetryStrategy;
  private final RetryStrategy processRetryStrategy;

  private final BooleanSupplier abortCondition;

  // current iteration
  private long lastSourceRecordPosition;
  private long lastFollowUpEventPosition;
  private long snapshotPosition;
  private long highestRecordKey = -1L;

  private ActorFuture<Long> replayFuture;
  private LoggedEvent currentEvent;
  private ZeebeDbTransaction zeebeDbTransaction;
  private final ReplayMode replayMode;
  private final ReplayContext replayContext;
  private final int partitionId;

  public ReplayStateMachine(final ProcessingContext context) {
    actor = context.getActor();
    logStreamReader = context.getLogStreamReader();
    recordValues = context.getRecordValues();
    transactionContext = context.getTransactionContext();
    abortCondition = context.getAbortCondition();
    eventApplier = context.getEventApplier();
    keyGeneratorControls = context.getKeyGeneratorControls();
    lastProcessedPositionState = context.getLastProcessedPositionState();

    updateStateRetryStrategy = new EndlessRetryStrategy(actor);
    processRetryStrategy = new EndlessRetryStrategy(actor);
    replayMode = ReplayMode.UNTIL_END;
    partitionId = context.getLogStream().getPartitionId();
    replayContext = new ReplayContext(new TypedEventImpl(partitionId));
  }

  /**
   * Reprocess the records. It returns the position of the last successfully processed record. If
   * there is nothing processed it returns {@link StreamProcessor#UNSET_POSITION}
   *
   * @return a ActorFuture with last reprocessed position
   */
  ActorFuture<Long> startRecover(final long snapshotPosition) {
    replayFuture = new CompletableActorFuture<>();

    this.snapshotPosition = snapshotPosition;
    lastSourceRecordPosition = snapshotPosition;

    // idea:
    // no longer need for scanning the log
    // support two modes - one time replay (for leader bootstrapping) or continuously replay

    if (snapshotPosition > 0) {
      LOG.info("Replay starts in mode {}", replayMode);
      logStreamReader.seekToNextEvent(snapshotPosition);
    }

    replayNextEvent();
    //    } else {
    //      replayFuture.complete(StreamProcessor.UNSET_POSITION);
    //    }

    return replayFuture;
  }

  private void replayNextEvent() {
    try {

      if (!logStreamReader.hasNext()) {
        if (replayMode == ReplayMode.CONTINUOUSLY) {
          // todo: handle - register for commit to replay next
        } else {
          // Done; complete replay future to continue with leader processing
          LOG.info(LOG_STMT_REPROCESSING_FINISHED);

          // TODO: think about it more. Fill the last source event position again.
          // reset the position to the first event where the processing should start
          logStreamReader.seekToNextEvent(lastSourceRecordPosition);

          onRecovered(lastSourceRecordPosition);
          return;
        }
      }

      currentEvent = logStreamReader.next();

      if (eventFilter.applies(currentEvent)) {
        setReplayContext();

        replayUntilDone(currentEvent.getPosition(), replayContext);
      } else {
        actor.submit(this::replayNextEvent);
      }

    } catch (final RuntimeException e) {
      final var processingException =
          new ProcessingException(
              "Unable to replay record", currentEvent, replayContext.metadata, e);
      replayFuture.completeExceptionally(processingException);
    }
  }

  /** Sets all necessary information at the context, which is used during replay the event. */
  private void setReplayContext() {
    final var metadata = replayContext.metadata;
    try {
      metadata.reset();
      currentEvent.readMetadata(metadata);
    } catch (final Exception e) {
      LOG.error(ERROR_MESSAGE_ON_EVENT_FAILED_SKIP_EVENT, currentEvent, metadata, e);
    }

    final UnifiedRecordValue value =
        recordValues.readRecordValue(currentEvent, metadata.getValueType());
    replayContext.typedEvent.wrap(currentEvent, metadata, value);
  }

  private void replayUntilDone(final long position, final ReplayContext replayContext) {
    final var currentTypedEvent = replayContext.typedEvent;

    final TransactionOperation operationOnReplay;
    if (currentTypedEvent.getValueType() == ValueType.ERROR) {
      LOG.info(LOG_STMT_FAILED_ON_PROCESSING, currentEvent);
      // TODO: blacklist via error records - currently not possible
      operationOnReplay = null;
      //          () -> zeebeState.getBlackListState().tryToBlacklist(currentEvent,
      // NOOP_LONG_CONSUMER);
    } else {
      operationOnReplay =
          () -> {
            // We only replay events. We don't need to check for blacklisted, since we wouldn't
            // create an event for a blacklisted instances on processing.

            final long recordPosition = currentTypedEvent.getPosition();

            // skip events if the state changes are already applied to the state in the snapshot
            final var sourceRecordPosition = currentTypedEvent.getSourceRecordPosition();
            if (sourceRecordPosition > snapshotPosition) {
              eventApplier.applyState(
                  currentEvent.getKey(),
                  currentTypedEvent.getIntent(),
                  currentTypedEvent.getValue());
            }

            lastProcessedPositionState.markAsProcessed(position);

            lastFollowUpEventPosition = Math.max(recordPosition, lastFollowUpEventPosition);
            lastSourceRecordPosition = Math.max(sourceRecordPosition, lastSourceRecordPosition);

            if (currentTypedEvent.getPartitionId() == partitionId) {

              // remember the highest key on the stream to restore the key generator after replay
              highestRecordKey = Math.max(currentTypedEvent.getKey(), highestRecordKey);
            }
          };
    }

    final ActorFuture<Boolean> resultFuture =
        processRetryStrategy.runWithRetry(
            () -> {
              final boolean onRetry = zeebeDbTransaction != null;
              if (onRetry) {
                zeebeDbTransaction.rollback();
              }
              zeebeDbTransaction = transactionContext.getCurrentTransaction();
              zeebeDbTransaction.run(operationOnReplay);
              return true;
            },
            abortCondition);

    actor.runOnCompletion(
        resultFuture,
        (v, t) -> {
          // processing should be retried endless until it worked
          assert t == null : "On reprocessing there shouldn't be any exception thrown.";
          updateStateUntilDone();
        });
  }

  private void updateStateUntilDone() {
    final ActorFuture<Boolean> retryFuture =
        updateStateRetryStrategy.runWithRetry(
            () -> {
              zeebeDbTransaction.commit();
              zeebeDbTransaction = null;
              return true;
            },
            abortCondition);

    actor.runOnCompletion(
        retryFuture,
        (bool, throwable) -> {
          // update state should be retried endless until it worked
          assert throwable == null : "On reprocessing there shouldn't be any exception thrown.";

          actor.submit(this::replayNextEvent);
        });
  }

  private void onRecovered(final long lastProcessedPosition) {
    keyGeneratorControls.setKeyIfHigher(highestRecordKey);

    replayFuture.complete(lastProcessedPosition);
  }

  private static final class ReplayContext {
    private final RecordMetadata metadata = new RecordMetadata();
    private final TypedEventImpl typedEvent;

    ReplayContext(final TypedEventImpl typedEvent) {
      this.typedEvent = typedEvent;
    }
  }

  public enum ReplayMode {
    UNTIL_END,
    CONTINUOUSLY
  }
}
