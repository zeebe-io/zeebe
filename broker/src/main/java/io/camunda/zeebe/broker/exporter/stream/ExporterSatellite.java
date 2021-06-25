package io.camunda.zeebe.broker.exporter.stream;

import io.camunda.zeebe.broker.Loggers;
import io.camunda.zeebe.broker.system.partitions.PartitionMessagingService;
import io.camunda.zeebe.db.ZeebeDb;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.UnsafeBuffer;

public class ExporterSatellite implements AutoCloseable {

  public static final String TOPIC_FORMAT = "exporterState-%d";

  private final PartitionMessagingService partitionMessagingService;
  private final ExportersState exportersState;
  private ExecutorService executorService;
  private final String exporterPositionsTopic;
  private final String threadExecutorName;

  public ExporterSatellite(
      final PartitionMessagingService partitionMessagingService,
      final ZeebeDb zeebeDb,
      final int nodeId,
      final int partitionId) {
    this.partitionMessagingService = partitionMessagingService;
    exportersState = new ExportersState(zeebeDb, zeebeDb.createContext());
    exporterPositionsTopic = String.format(TOPIC_FORMAT, partitionId);
    threadExecutorName = "Broker-" + nodeId + "-Exporter-Satellite-" + partitionId;
  }

  public void subscribe() {
    executorService = Executors.newSingleThreadExecutor(r -> new Thread(r, threadExecutorName));
    partitionMessagingService.subscribe(
        exporterPositionsTopic, this::storeExporterPositions, executorService);
  }

  private void storeExporterPositions(final ByteBuffer byteBuffer) {
    final var readBuffer = new UnsafeBuffer(byteBuffer);
    final var exportPositionsReq = new ExportPositionsReq();
    exportPositionsReq.wrap(readBuffer, 0, readBuffer.capacity());

    final var exporterPositions = exportPositionsReq.getExporterPositions();

    Loggers.EXPORTER_LOGGER.debug(
        "[{}] Received new exporter state {}", exporterPositionsTopic, exporterPositions);

    exporterPositions.forEach(exportersState::setPosition);
  }

  @Override
  public void close() throws Exception {
    partitionMessagingService.unsubscribe(exporterPositionsTopic);

    if (executorService != null) {
      executorService.shutdownNow();
      executorService.awaitTermination(10, TimeUnit.SECONDS);
      executorService = null;
    }
  }
}
