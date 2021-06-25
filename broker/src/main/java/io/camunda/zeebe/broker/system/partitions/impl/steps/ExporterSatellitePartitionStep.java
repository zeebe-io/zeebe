/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.system.partitions.impl.steps;

import io.camunda.zeebe.broker.Loggers;
import io.camunda.zeebe.broker.exporter.stream.ExporterSatellite;
import io.camunda.zeebe.broker.system.partitions.PartitionContext;
import io.camunda.zeebe.broker.system.partitions.PartitionStep;
import io.camunda.zeebe.util.sched.future.ActorFuture;
import io.camunda.zeebe.util.sched.future.CompletableActorFuture;

public class ExporterSatellitePartitionStep implements PartitionStep {

  private ExporterSatellite exporterSatellite;

  @Override
  public ActorFuture<Void> open(final PartitionContext context) {

    // in order to receive exporter states we need to install the satellite service

    exporterSatellite =
        new ExporterSatellite(
            context.getMessagingService(),
            context.getZeebeDb(),
            context.getNodeId(),
            context.getPartitionId());

    exporterSatellite.subscribe();

    return CompletableActorFuture.completed(null);
  }

  @Override
  public ActorFuture<Void> close(final PartitionContext context) {
    try {
      if (exporterSatellite != null) {
        exporterSatellite.close();
      }
    } catch (final Exception e) {
      Loggers.SYSTEM_LOGGER.error(
          "Unexpected error closing exporter satellite for partition {}",
          context.getPartitionId(),
          e);
    } finally {
      exporterSatellite = null;
    }

    return CompletableActorFuture.completed(null);
  }

  @Override
  public String getName() {
    return "ExporterSatelliteService";
  }
}
