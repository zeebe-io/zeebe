package io.camunda.zeebe.broker.exporter.stream;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.zeebe.broker.system.partitions.PartitionMessagingService;
import io.camunda.zeebe.util.collection.Tuple;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Test;

public class ExporterPositionsDistributionTest {

  private ExporterPositionsDistributionService exporterPositionsDistributionService;
  private Map<String, Long> exporterPositions;
  private SimplePartitionMessageService partitionMessagingService;

  @Before
  public void setup() {
    exporterPositions = new HashMap<>();
    partitionMessagingService = new SimplePartitionMessageService();
    exporterPositionsDistributionService =
        new ExporterPositionsDistributionService(
            exporterPositions::put, partitionMessagingService, "topic");
  }

  @Test
  public void shouldSubscribeForGivenTopic() {
    // given

    // when
    exporterPositionsDistributionService.subscribeForExporterPositions(Runnable::run);

    // then
    assertThat(partitionMessagingService.consumers).containsKey("topic");
  }

  @Test
  public void shouldConsumeExporterMessage() {
    // given
    final var exporterPositionsMessage = new ExporterPositionsMessage();
    exporterPositionsMessage.putExporter("elastic", 123);
    exporterPositionsMessage.putExporter("metric", 345);
    exporterPositionsDistributionService.subscribeForExporterPositions(Runnable::run);

    // when
    exporterPositionsDistributionService.publishExporterPositions(exporterPositionsMessage);

    // then
    assertThat(exporterPositions).containsEntry("elastic", 123L).containsEntry("metric", 345L);
  }

  @Test
  public void shouldRemoveSubscriptionOnClose() throws Exception {
    // given
    final var exporterPositionsMessage = new ExporterPositionsMessage();
    exporterPositionsMessage.putExporter("elastic", 123);
    exporterPositionsMessage.putExporter("metric", 345);
    exporterPositionsDistributionService.subscribeForExporterPositions(Runnable::run);

    // when
    exporterPositionsDistributionService.close();

    // then
    assertThat(partitionMessagingService.consumers).isEmpty();
  }

  private static final class SimplePartitionMessageService implements PartitionMessagingService {

    public final Map<String, Tuple<Executor, Consumer<ByteBuffer>>> consumers = new HashMap<>();

    @Override
    public void subscribe(
        final String subject, final Consumer<ByteBuffer> consumer, final Executor executor) {
      consumers.put(subject, new Tuple<>(executor, consumer));
    }

    @Override
    public void broadcast(final String subject, final ByteBuffer payload) {
      final var executorConsumerTuple = consumers.get(subject);
      final var executor = executorConsumerTuple.getLeft();
      executor.execute(() -> executorConsumerTuple.getRight().accept(payload));
    }

    @Override
    public void unsubscribe(final String subject) {
      consumers.remove(subject);
    }
  }
}
