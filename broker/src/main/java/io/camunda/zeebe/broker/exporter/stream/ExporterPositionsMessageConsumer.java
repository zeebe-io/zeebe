package io.camunda.zeebe.broker.exporter.stream;

import io.camunda.zeebe.broker.Loggers;
import java.nio.ByteBuffer;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.agrona.concurrent.UnsafeBuffer;

public class ExporterPositionsMessageConsumer implements Consumer<ByteBuffer> {

  private final BiConsumer<String, Long> exporterPositionsConsumer;

  public ExporterPositionsMessageConsumer(
      final BiConsumer<String, Long> exporterPositionsConsumer) {
    this.exporterPositionsConsumer = exporterPositionsConsumer;
  }

  @Override
  public void accept(final ByteBuffer byteBuffer) {
    storeExporterPositions(byteBuffer);
  }

  private void storeExporterPositions(final ByteBuffer byteBuffer) {
    final var readBuffer = new UnsafeBuffer(byteBuffer);
    final var exportPositionsMessage = new ExporterPositionsMessage();
    exportPositionsMessage.wrap(readBuffer, 0, readBuffer.capacity());

    final var exporterPositions = exportPositionsMessage.getExporterPositions();

    Loggers.EXPORTER_LOGGER.debug("Received new exporter state {}", exporterPositions);

    exporterPositions.forEach(exporterPositionsConsumer);
  }
}
