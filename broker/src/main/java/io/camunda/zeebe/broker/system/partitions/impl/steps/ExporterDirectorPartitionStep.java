/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.system.partitions.impl.steps;

import io.camunda.zeebe.broker.exporter.stream.ExporterDirector;
import io.camunda.zeebe.broker.exporter.stream.ExporterDirectorContext;
import io.camunda.zeebe.broker.system.partitions.PartitionBoostrapAndTransitionContextImpl;
import io.camunda.zeebe.broker.system.partitions.PartitionStep;
import io.camunda.zeebe.util.sched.Actor;
import io.camunda.zeebe.util.sched.future.ActorFuture;

public class ExporterDirectorPartitionStep implements PartitionStep {

  private static final int EXPORTER_PROCESSOR_ID = 1003;

  @Override
  public ActorFuture<Void> open(final PartitionBoostrapAndTransitionContextImpl context) {
    final var exporterDescriptors = context.getExporterRepository().getExporters().values();

    final ExporterDirectorContext exporterCtx =
        new ExporterDirectorContext()
            .id(EXPORTER_PROCESSOR_ID)
            .name(Actor.buildActorName(context.getNodeId(), "Exporter", context.getPartitionId()))
            .logStream(context.getLogStream())
            .zeebeDb(context.getZeebeDb())
            .partitionMessagingService(context.getMessagingService())
            .descriptors(exporterDescriptors);

    final ExporterDirector director = new ExporterDirector(exporterCtx, !context.shouldExport());
    context.setExporterDirector(director);
    context.getComponentHealthMonitor().registerComponent(director.getName(), director);

    final var startFuture = director.startAsync(context.getActorSchedulingService());
    startFuture.onComplete(
        (nothing, error) -> {
          if (error == null) {
            // Pause/Resume here in case the state was changed after the director was created
            if (!context.shouldExport()) {
              director.pauseExporting();
            } else {
              director.resumeExporting();
            }
          }
        });
    return startFuture;
  }

  @Override
  public ActorFuture<Void> close(final PartitionBoostrapAndTransitionContextImpl context) {
    final var director = context.getExporterDirector();
    context.getComponentHealthMonitor().removeComponent(director.getName());
    final ActorFuture<Void> future = director.closeAsync();
    context.setExporterDirector(null);
    return future;
  }

  @Override
  public String getName() {
    return "ExporterDirector";
  }
}
