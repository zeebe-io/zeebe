/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.exporter.stream;

import io.camunda.zeebe.broker.Loggers;
import io.camunda.zeebe.broker.system.partitions.PartitionMessagingService;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import org.agrona.concurrent.UnsafeBuffer;

public class ExporterPositionsDistributionService implements AutoCloseable {

  private final PartitionMessagingService partitionMessagingService;
  private final String exporterPositionsTopic;
  private final BiConsumer<String, Long> exporterPositionConsumer;

  public ExporterPositionsDistributionService(
      final BiConsumer<String, Long> exporterPositionConsumer,
      final PartitionMessagingService partitionMessagingService,
      final String exporterTopic) {
    this.exporterPositionConsumer = exporterPositionConsumer;
    this.partitionMessagingService = partitionMessagingService;
    exporterPositionsTopic = exporterTopic;
  }

  public void subscribeForExporterPositions(final Executor executor) {
    partitionMessagingService.subscribe(
        exporterPositionsTopic, this::storeExporterPositions, executor);
  }

  private void storeExporterPositions(final ByteBuffer byteBuffer) {
    final var readBuffer = new UnsafeBuffer(byteBuffer);
    final var exportPositionsMessage = new ExporterPositionsMessage();
    exportPositionsMessage.wrap(readBuffer, 0, readBuffer.capacity());

    final var exporterPositions = exportPositionsMessage.getExporterPositions();

    Loggers.EXPORTER_LOGGER.debug("Received new exporter state {}", exporterPositions);

    exporterPositions.forEach(exporterPositionConsumer);
  }

  public void publishExporterPositions(final ExporterPositionsMessage exporterPositionsMessage) {
    partitionMessagingService.broadcast(
        exporterPositionsTopic, exporterPositionsMessage.toByteBuffer());
  }

  @Override
  public void close() throws Exception {
    partitionMessagingService.unsubscribe(exporterPositionsTopic);
  }
}
