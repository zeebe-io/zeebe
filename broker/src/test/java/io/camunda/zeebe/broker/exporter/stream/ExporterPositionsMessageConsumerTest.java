package io.camunda.zeebe.broker.exporter.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class ExporterPositionsMessageConsumerTest {

  private Map<String, Long> exporterPositions;
  private ExporterPositionsMessageConsumer exporterSatellite;

  @Before
  public void setup() {
    exporterPositions = new HashMap<>();
    exporterSatellite = new ExporterPositionsMessageConsumer(exporterPositions::put);
  }

  @Test
  public void shouldConsumeExporterPositionsMessage() {
    // given
    final var positionsMessage = new ExporterPositionsMessage();
    positionsMessage.putExporter("elastic", 123);
    positionsMessage.putExporter("metric", 456);

    // when
    exporterSatellite.accept(positionsMessage.toByteBuffer());

    // then
    assertThat(exporterPositions).containsEntry("elastic", 123L).containsEntry("metric", 456L);
  }
}
