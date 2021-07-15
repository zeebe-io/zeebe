package io.camunda.zeebe.broker.exporter.stream;

import io.camunda.zeebe.broker.system.partitions.PartitionMessagingService;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

public class ExporterPositionsDistributionService implements AutoCloseable {

  private final ExporterPositionsMessageConsumer exporterPositionsMessageConsumer;
  private final PartitionMessagingService partitionMessagingService;
  private final String exporterPositionsTopic;

  public ExporterPositionsDistributionService(
      final BiConsumer<String, Long> exporterPositionConsumer,
      final PartitionMessagingService partitionMessagingService,
      final String exporterTopic) {
    exporterPositionsMessageConsumer =
        new ExporterPositionsMessageConsumer(exporterPositionConsumer);
    this.partitionMessagingService = partitionMessagingService;
    exporterPositionsTopic = exporterTopic;
  }

  public void subscribeForExporterPositions(final Executor executor) {
    partitionMessagingService.subscribe(
        exporterPositionsTopic, exporterPositionsMessageConsumer, executor);
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
