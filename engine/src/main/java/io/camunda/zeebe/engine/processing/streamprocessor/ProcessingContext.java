/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.streamprocessor;

import io.camunda.zeebe.db.TransactionContext;
import io.camunda.zeebe.engine.processing.bpmn.behavior.TypedStreamWriterProxy;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.CommandResponseWriter;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.EventApplyingStateWriter;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.NoopTypedStreamWriter;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.TypedResponseWriterImpl;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.TypedStreamWriter;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.Writers;
import io.camunda.zeebe.engine.state.EventApplier;
import io.camunda.zeebe.engine.state.KeyGeneratorControls;
import io.camunda.zeebe.engine.state.ZeebeDbState;
import io.camunda.zeebe.engine.state.mutable.MutableLastProcessedPositionState;
import io.camunda.zeebe.engine.state.mutable.MutableZeebeState;
import io.camunda.zeebe.logstreams.log.LogStream;
import io.camunda.zeebe.logstreams.log.LogStreamReader;
import io.camunda.zeebe.logstreams.log.LoggedEvent;
import io.camunda.zeebe.util.sched.ActorControl;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class ProcessingContext implements ReadonlyProcessingContext {

  private final TypedStreamWriterProxy streamWriterProxy = new TypedStreamWriterProxy();
  private final NoopTypedStreamWriter noopTypedStreamWriter = new NoopTypedStreamWriter();

  private ActorControl actor;
  private LogStream logStream;
  private LogStreamReader logStreamReader;
  private TypedStreamWriter logStreamWriter = noopTypedStreamWriter;
  private CommandResponseWriter commandResponseWriter;
  private TypedResponseWriterImpl typedResponseWriter;

  private RecordValues recordValues;
  private RecordProcessorMap recordProcessorMap;
  private ZeebeDbState zeebeState;
  private TransactionContext transactionContext;
  private EventApplier eventApplier;

  private BooleanSupplier abortCondition;
  private Consumer<TypedRecord<?>> onProcessedListener = record -> {};
  private Consumer<LoggedEvent> onSkippedListener = record -> {};
  private int maxFragmentSize;
  private ReplayMode replayMode = ReplayMode.UNTIL_END;

  public ProcessingContext() {
    streamWriterProxy.wrap(logStreamWriter);
  }

  public ProcessingContext actor(final ActorControl actor) {
    this.actor = actor;
    return this;
  }

  public ProcessingContext logStream(final LogStream logStream) {
    this.logStream = logStream;
    return this;
  }

  public ProcessingContext logStreamReader(final LogStreamReader logStreamReader) {
    this.logStreamReader = logStreamReader;
    return this;
  }

  public ProcessingContext eventCache(final RecordValues recordValues) {
    this.recordValues = recordValues;
    return this;
  }

  public ProcessingContext recordProcessorMap(final RecordProcessorMap recordProcessorMap) {
    this.recordProcessorMap = recordProcessorMap;
    return this;
  }

  public ProcessingContext zeebeState(final ZeebeDbState zeebeState) {
    this.zeebeState = zeebeState;
    return this;
  }

  public ProcessingContext transactionContext(final TransactionContext transactionContext) {
    this.transactionContext = transactionContext;
    return this;
  }

  public ProcessingContext abortCondition(final BooleanSupplier abortCondition) {
    this.abortCondition = abortCondition;
    return this;
  }

  public ProcessingContext logStreamWriter(final TypedStreamWriter logStreamWriter) {
    this.logStreamWriter = logStreamWriter;
    return this;
  }

  public ProcessingContext commandResponseWriter(
      final CommandResponseWriter commandResponseWriter) {
    this.commandResponseWriter = commandResponseWriter;
    typedResponseWriter =
        new TypedResponseWriterImpl(commandResponseWriter, getLogStream().getPartitionId());
    return this;
  }

  public CommandResponseWriter getCommandResponseWriter() {
    return commandResponseWriter;
  }

  public ProcessingContext onProcessedListener(final Consumer<TypedRecord<?>> onProcessedListener) {
    this.onProcessedListener = onProcessedListener;
    return this;
  }

  public ProcessingContext onSkippedListener(final Consumer<LoggedEvent> onSkippedListener) {
    this.onSkippedListener = onSkippedListener;
    return this;
  }

  public ProcessingContext maxFragmentSize(final int maxFragmentSize) {
    this.maxFragmentSize = maxFragmentSize;
    return this;
  }

  public ProcessingContext eventApplier(final EventApplier eventApplier) {
    this.eventApplier = eventApplier;
    return this;
  }

  public ProcessingContext replayContinuously() {
    replayMode = ReplayMode.CONTINUOUSLY;
    return this;
  }

  public KeyGeneratorControls getKeyGeneratorControls() {
    return zeebeState.getKeyGeneratorControls();
  }

  public MutableLastProcessedPositionState getLastProcessedPositionState() {
    return zeebeState.getLastProcessedPositionState();
  }

  @Override
  public ActorControl getActor() {
    return actor;
  }

  @Override
  public LogStream getLogStream() {
    return logStream;
  }

  @Override
  public LogStreamReader getLogStreamReader() {
    return logStreamReader;
  }

  @Override
  public int getMaxFragmentSize() {
    return maxFragmentSize;
  }

  @Override
  public TypedStreamWriter getLogStreamWriter() {
    return streamWriterProxy;
  }

  @Override
  public Writers getWriters() {
    // todo (#6202): cleanup - revisit after migration is finished
    // create newly every time, because the specific writers may differ over time
    final var stateWriter = new EventApplyingStateWriter(streamWriterProxy, eventApplier);
    return new Writers(streamWriterProxy, stateWriter, typedResponseWriter);
  }

  @Override
  public RecordValues getRecordValues() {
    return recordValues;
  }

  @Override
  public RecordProcessorMap getRecordProcessorMap() {
    return recordProcessorMap;
  }

  @Override
  public MutableZeebeState getZeebeState() {
    return zeebeState;
  }

  @Override
  public TransactionContext getTransactionContext() {
    return transactionContext;
  }

  @Override
  public BooleanSupplier getAbortCondition() {
    return abortCondition;
  }

  @Override
  public EventApplier getEventApplier() {
    return eventApplier;
  }

  public Consumer<TypedRecord<?>> getOnProcessedListener() {
    return onProcessedListener;
  }

  public Consumer<LoggedEvent> getOnSkippedListener() {
    return onSkippedListener;
  }

  public void enableLogStreamWriter() {
    streamWriterProxy.wrap(logStreamWriter);
  }

  public void disableLogStreamWriter() {
    streamWriterProxy.wrap(noopTypedStreamWriter);
  }

  public boolean shouldReplayContinuously() {
    return replayMode == ReplayMode.CONTINUOUSLY;
  }
}
