/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.streamprocessor;

import io.camunda.zeebe.db.ZeebeDb;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.CommandResponseWriter;
import io.camunda.zeebe.engine.state.EventApplier;
import io.camunda.zeebe.engine.state.mutable.MutableZeebeState;
import io.camunda.zeebe.logstreams.log.LogStream;
import io.camunda.zeebe.util.sched.ActorSchedulingService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

public final class StreamProcessorBuilder {

  private final ProcessingContext processingContext;
  private final List<StreamProcessorLifecycleAware> lifecycleListeners = new ArrayList<>();
  private TypedRecordProcessorFactory typedRecordProcessorFactory;
  private ActorSchedulingService actorSchedulingService;
  private ZeebeDb zeebeDb;
  private Function<MutableZeebeState, EventApplier> eventApplierFactory;
  private int nodeId;

  public StreamProcessorBuilder() {
    processingContext = new ProcessingContext();
  }

  public StreamProcessorBuilder streamProcessorFactory(
      final TypedRecordProcessorFactory typedRecordProcessorFactory) {
    this.typedRecordProcessorFactory = typedRecordProcessorFactory;
    return this;
  }

  public StreamProcessorBuilder actorSchedulingService(
      final ActorSchedulingService actorSchedulingService) {
    this.actorSchedulingService = actorSchedulingService;
    return this;
  }

  public StreamProcessorBuilder nodeId(final int nodeId) {
    this.nodeId = nodeId;
    return this;
  }

  public StreamProcessorBuilder logStream(final LogStream stream) {
    processingContext.logStream(stream);
    return this;
  }

  public StreamProcessorBuilder commandResponseWriter(
      final CommandResponseWriter commandResponseWriter) {
    processingContext.commandResponseWriter(commandResponseWriter);
    return this;
  }

  public StreamProcessorBuilder onProcessedListener(final Consumer<TypedRecord<?>> onProcessed) {
    processingContext.onProcessedListener(onProcessed);
    return this;
  }

  public StreamProcessorBuilder zeebeDb(final ZeebeDb zeebeDb) {
    this.zeebeDb = zeebeDb;
    return this;
  }

  public StreamProcessorBuilder eventApplierFactory(
      final Function<MutableZeebeState, EventApplier> eventApplierFactory) {
    this.eventApplierFactory = eventApplierFactory;
    return this;
  }

  public StreamProcessorBuilder replayContinuously() {
    processingContext.replayContinuously();
    return this;
  }

  public TypedRecordProcessorFactory getTypedRecordProcessorFactory() {
    return typedRecordProcessorFactory;
  }

  public ProcessingContext getProcessingContext() {
    return processingContext;
  }

  public ActorSchedulingService getActorSchedulingService() {
    return actorSchedulingService;
  }

  public List<StreamProcessorLifecycleAware> getLifecycleListeners() {
    return lifecycleListeners;
  }

  public ZeebeDb getZeebeDb() {
    return zeebeDb;
  }

  public int getNodeId() {
    return nodeId;
  }

  public Function<MutableZeebeState, EventApplier> getEventApplierFactory() {
    return eventApplierFactory;
  }

  public StreamProcessor build() {
    validate();

    return new StreamProcessor(this);
  }

  private void validate() {
    Objects.requireNonNull(typedRecordProcessorFactory, "No stream processor factory provided.");
    Objects.requireNonNull(actorSchedulingService, "No task scheduler provided.");
    Objects.requireNonNull(processingContext.getLogStream(), "No log stream provided.");
    Objects.requireNonNull(
        processingContext.getWriters().response(), "No command response writer provided.");
    Objects.requireNonNull(zeebeDb, "No database provided.");
    Objects.requireNonNull(eventApplierFactory, "No factory for the event supplier provided.");
  }
}
