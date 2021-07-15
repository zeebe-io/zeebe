/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.exporter.stream;

import static io.camunda.zeebe.test.util.TestUtil.doRepeatedly;
import static io.camunda.zeebe.test.util.TestUtil.waitUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.camunda.zeebe.broker.exporter.repo.ExporterDescriptor;
import io.camunda.zeebe.broker.exporter.util.ControlledTestExporter;
import io.camunda.zeebe.broker.exporter.util.PojoConfigurationExporter;
import io.camunda.zeebe.broker.exporter.util.PojoConfigurationExporter.PojoExporterConfiguration;
import io.camunda.zeebe.engine.Loggers;
import io.camunda.zeebe.exporter.api.context.Context;
import io.camunda.zeebe.protocol.impl.record.value.deployment.DeploymentRecord;
import io.camunda.zeebe.protocol.impl.record.value.incident.IncidentRecord;
import io.camunda.zeebe.protocol.impl.record.value.job.JobRecord;
import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.protocol.record.RecordType;
import io.camunda.zeebe.protocol.record.ValueType;
import io.camunda.zeebe.protocol.record.intent.DeploymentIntent;
import io.camunda.zeebe.protocol.record.intent.IncidentIntent;
import io.camunda.zeebe.protocol.record.intent.JobIntent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.awaitility.Awaitility;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.verification.VerificationWithTimeout;

public final class ExporterDirectorTest {

  private static final int PARTITION_ID = 1;

  private static final String EXPORTER_ID_1 = "exporter-1";
  private static final String EXPORTER_ID_2 = "exporter-2";

  private static final VerificationWithTimeout TIMEOUT = timeout(5_000);
  @Rule public final ExporterRule rule = ExporterRule.activeExporter();
  private final List<ControlledTestExporter> exporters = new ArrayList<>();
  private final List<ExporterDescriptor> exporterDescriptors = new ArrayList<>();

  @Before
  public void init() {
    exporters.clear();
    exporterDescriptors.clear();

    createExporter(EXPORTER_ID_1, Collections.singletonMap("x", 1));
    createExporter(EXPORTER_ID_2, Collections.singletonMap("y", 2));
  }

  private void createExporter(final String exporterId, final Map<String, Object> arguments) {
    final ControlledTestExporter exporter = spy(new ControlledTestExporter());

    final ExporterDescriptor descriptor =
        spy(new ExporterDescriptor(exporterId, exporter.getClass(), arguments));
    doAnswer(c -> exporter).when(descriptor).newInstance();

    exporters.add(exporter);
    exporterDescriptors.add(descriptor);
  }

  private void startExporterDirector(final List<ExporterDescriptor> exporterDescriptors) {
    rule.startExporterDirector(exporterDescriptors);
  }

  @Test
  public void shouldUpdatePositionWhenInitialRecordsAreSkipped() {
    // given
    final ControlledTestExporter tailingExporter = exporters.get(1);
    exporters.forEach(
        e ->
            e.onConfigure(withFilter(List.of(RecordType.COMMAND), List.of(ValueType.DEPLOYMENT)))
                .shouldAutoUpdatePosition(false));

    // when
    startExporterDirector(exporterDescriptors);
    final ExportersState state = rule.getExportersState();
    final long skippedRecordPosition =
        rule.writeEvent(DeploymentIntent.CREATED, new DeploymentRecord());
    rule.writeCommand(DeploymentIntent.CREATE, new DeploymentRecord());

    // then
    Awaitility.await("director has read all records until now")
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(tailingExporter.getExportedRecords()).hasSize(1));
    assertThat(state.getPosition(EXPORTER_ID_1)).isEqualTo(skippedRecordPosition);
    assertThat(state.getPosition(EXPORTER_ID_2)).isEqualTo(skippedRecordPosition);
  }

  @Test
  public void shouldUpdatePositionOfUpToDateExportersOnSkipRecord() {
    // given
    final ControlledTestExporter filteringExporter = exporters.get(0);
    final ControlledTestExporter tailingExporter = exporters.get(1);
    tailingExporter
        .onConfigure(withFilter(List.of(RecordType.COMMAND), List.of(ValueType.DEPLOYMENT)))
        .shouldAutoUpdatePosition(false);
    filteringExporter
        .onConfigure(withFilter(List.of(RecordType.COMMAND), List.of(ValueType.DEPLOYMENT)))
        .shouldAutoUpdatePosition(false);

    // when
    startExporterDirector(exporterDescriptors);
    final ExportersState state = rule.getExportersState();

    // accepted by both
    final long firstRecordPosition =
        rule.writeCommand(DeploymentIntent.CREATE, new DeploymentRecord());
    Awaitility.await("filteringExporter has exported the first record")
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(filteringExporter.getExportedRecords()).hasSize(1));
    filteringExporter.getController().updateLastExportedRecordPosition(firstRecordPosition);
    // skipped entirely
    final long skippedRecordPosition =
        rule.writeCommand(IncidentIntent.CREATED, new IncidentRecord());
    // accepted by both again
    rule.writeCommand(DeploymentIntent.CREATE, new DeploymentRecord());

    // then
    Awaitility.await("director has read all records until now")
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(tailingExporter.getExportedRecords()).hasSize(2));
    assertThat(state.getPosition(EXPORTER_ID_1)).isEqualTo(skippedRecordPosition);
    assertThat(state.getPosition(EXPORTER_ID_2)).isEqualTo(-1L);
  }

  @Test
  public void shouldUpdateIfSkippingInitialRecordForSingleExporter() {
    final ControlledTestExporter filteringExporter = exporters.get(0);
    final ControlledTestExporter tailingExporter = exporters.get(1);
    tailingExporter
        .onConfigure(
            withFilter(
                List.of(RecordType.COMMAND, RecordType.EVENT), List.of(ValueType.DEPLOYMENT)))
        .shouldAutoUpdatePosition(false);
    filteringExporter
        .onConfigure(withFilter(List.of(RecordType.COMMAND), List.of(ValueType.DEPLOYMENT)))
        .shouldAutoUpdatePosition(false);

    // when
    startExporterDirector(exporterDescriptors);
    final ExportersState state = rule.getExportersState();

    // skipped only by filteringExporter
    final long skippedRecordPosition =
        rule.writeEvent(DeploymentIntent.CREATED, new DeploymentRecord());
    // accepted by both
    rule.writeCommand(DeploymentIntent.CREATE, new DeploymentRecord());

    // then
    Awaitility.await("director has read all records until now")
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(tailingExporter.getExportedRecords()).hasSize(2));
    assertThat(state.getPosition(EXPORTER_ID_1)).isEqualTo(skippedRecordPosition);
    assertThat(state.getPosition(EXPORTER_ID_2)).isEqualTo(-1L);
  }

  @Test
  public void shouldUpdateIfRecordSkipsSingleUpToDateExporter() {
    final ControlledTestExporter filteringExporter = exporters.get(0);
    final ControlledTestExporter tailingExporter = exporters.get(1);
    tailingExporter
        .onConfigure(
            withFilter(
                List.of(RecordType.COMMAND, RecordType.EVENT), List.of(ValueType.DEPLOYMENT)))
        .shouldAutoUpdatePosition(false);
    filteringExporter
        .onConfigure(withFilter(List.of(RecordType.COMMAND), List.of(ValueType.DEPLOYMENT)))
        .shouldAutoUpdatePosition(false);

    // when
    startExporterDirector(exporterDescriptors);
    final ExportersState state = rule.getExportersState();

    // accepted by both
    final long firstRecordPosition =
        rule.writeCommand(DeploymentIntent.CREATE, new DeploymentRecord());
    Awaitility.await("filteringExporter has exported the first record")
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(filteringExporter.getExportedRecords()).hasSize(1));
    filteringExporter.getController().updateLastExportedRecordPosition(firstRecordPosition);
    // skipped only by filteringExporter
    final long skippedRecordPosition =
        rule.writeEvent(DeploymentIntent.CREATED, new DeploymentRecord());

    // then
    Awaitility.await("director has read all records until now")
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(tailingExporter.getExportedRecords()).hasSize(2));
    assertThat(state.getPosition(EXPORTER_ID_1)).isEqualTo(skippedRecordPosition);
    assertThat(state.getPosition(EXPORTER_ID_2)).isEqualTo(-1L);
  }

  @Test
  public void shouldConfigureAllExportersProperlyOnStart() {
    // when
    startExporterDirector(exporterDescriptors);

    // then
    verify(exporters.get(0), TIMEOUT).open(any());
    verify(exporters.get(1), TIMEOUT).open(any());

    exporters.forEach(
        exporter -> {
          assertThat(exporter.getController()).isNotNull();
          assertThat(exporter.getContext().getLogger()).isNotNull();
          assertThat(exporter.getContext().getConfiguration()).isNotNull();
        });

    final Context exporterContext1 = exporters.get(0).getContext();
    assertThat(exporterContext1.getConfiguration().getId()).isEqualTo(EXPORTER_ID_1);
    assertThat(exporterContext1.getConfiguration().getArguments())
        .isEqualTo(Collections.singletonMap("x", 1));
    assertThat(exporterContext1.getLogger().getName())
        .isEqualTo(Loggers.getExporterLogger(EXPORTER_ID_1).getName());

    final Context exporterContext2 = exporters.get(1).getContext();
    assertThat(exporterContext2.getConfiguration().getId()).isEqualTo(EXPORTER_ID_2);
    assertThat(exporterContext2.getConfiguration().getArguments())
        .isEqualTo(Collections.singletonMap("y", 2));
    assertThat(exporterContext2.getLogger().getName())
        .isEqualTo(Loggers.getExporterLogger(EXPORTER_ID_2).getName());
  }

  @Test
  public void shouldCloseAllExportersOnClose() throws Exception {
    // given
    startExporterDirector(exporterDescriptors);

    // when
    rule.closeExporterDirector();

    // then
    verify(exporters.get(0), TIMEOUT).close();
    verify(exporters.get(1), TIMEOUT).close();
  }

  @Test
  public void shouldInstantiateConfigurationClass() {
    // given
    final String foo = "bar";
    final int x = 123;
    final String bar = "baz";
    final double y = 32.12;

    final Map<String, Object> nested = new HashMap<>();
    nested.put("bar", bar);
    nested.put("y", y);

    final Map<String, Object> arguments = new HashMap<>();
    arguments.put("foo", foo);
    arguments.put("x", x);
    arguments.put("nested", nested);

    final ExporterDescriptor descriptor =
        new ExporterDescriptor(
            "instantiateConfiguration", PojoConfigurationExporter.class, arguments);

    startExporterDirector(Collections.singletonList(descriptor));

    // then
    waitUntil(() -> PojoConfigurationExporter.configuration != null);
    final PojoExporterConfiguration configuration = PojoConfigurationExporter.configuration;

    assertThat(configuration.getFoo()).isEqualTo(foo);
    assertThat(configuration.getX()).isEqualTo(x);
    assertThat(configuration.getNested().getBar()).isEqualTo(bar);
    assertThat(configuration.getNested().getY()).isEqualTo(y);
  }

  @Test
  public void shouldApplyRecordFilter() {
    // given
    exporters
        .get(0)
        .onConfigure(
            withFilter(
                Arrays.asList(RecordType.COMMAND, RecordType.EVENT),
                Collections.singletonList(ValueType.DEPLOYMENT)));

    exporters
        .get(1)
        .onConfigure(
            withFilter(
                Collections.singletonList(RecordType.EVENT),
                Arrays.asList(ValueType.DEPLOYMENT, ValueType.JOB)));

    startExporterDirector(exporterDescriptors);

    // when
    final long deploymentCommand =
        rule.writeCommand(DeploymentIntent.CREATE, new DeploymentRecord());
    final long deploymentEvent = rule.writeEvent(DeploymentIntent.CREATED, new DeploymentRecord());
    rule.writeEvent(IncidentIntent.CREATED, new IncidentRecord());
    final long jobEvent = rule.writeEvent(JobIntent.CREATED, new JobRecord());

    // then
    waitUntil(() -> exporters.get(1).getExportedRecords().size() == 2);

    assertThat(exporters.get(0).getExportedRecords())
        .extracting(Record::getPosition)
        .hasSize(2)
        .contains(deploymentCommand, deploymentEvent);
    assertThat(exporters.get(1).getExportedRecords())
        .extracting(Record::getPosition)
        .hasSize(2)
        .contains(deploymentEvent, jobEvent);
  }

  @Test
  public void shouldRetryExportingOnException() {
    // given
    final AtomicLong failCount = new AtomicLong(3);
    exporters
        .get(0)
        .onExport(
            e -> {
              if (failCount.getAndDecrement() > 0) {
                throw new RuntimeException("Export failed (expected)");
              }
            });

    startExporterDirector(exporterDescriptors);

    // when
    final long eventPosition1 = writeEvent();
    final long eventPosition2 = writeEvent();

    // then
    doRepeatedly(() -> rule.getClock().addTime(Duration.ofSeconds(1)))
        .until((r) -> failCount.get() <= -2);
    assertThat(exporters.get(0).getExportedRecords())
        .extracting(Record::getPosition)
        .containsExactly(eventPosition1, eventPosition2);
    assertThat(exporters.get(1).getExportedRecords())
        .extracting(Record::getPosition)
        .containsExactly(eventPosition1, eventPosition2);
  }

  @Test
  public void shouldExecuteScheduledTask() throws Exception {
    // given
    final CountDownLatch timerTriggerLatch = new CountDownLatch(1);
    final CountDownLatch timerScheduledLatch = new CountDownLatch(1);
    final Duration delay = Duration.ofSeconds(10);

    exporters
        .get(0)
        .onExport(
            r -> {
              exporters
                  .get(0)
                  .getController()
                  .scheduleCancellableTask(delay, timerTriggerLatch::countDown);
              timerScheduledLatch.countDown();
            });

    // when
    startExporterDirector(exporterDescriptors);

    writeEvent();

    assertThat(timerScheduledLatch.await(5, TimeUnit.SECONDS)).isTrue();

    rule.getClock().addTime(delay);

    // then
    assertThat(timerTriggerLatch.await(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  public void shouldExecuteScheduledCancellableTask() throws InterruptedException {
    // given
    final CountDownLatch timerTriggerLatch = new CountDownLatch(1);
    final CountDownLatch timerScheduledLatch = new CountDownLatch(1);
    final Duration delay = Duration.ofSeconds(10);

    final ControlledTestExporter exporter = exporters.get(0);
    exporter.onExport(
        r -> {
          exporter.getController().scheduleCancellableTask(delay, timerTriggerLatch::countDown);
          timerScheduledLatch.countDown();
        });

    // when
    startExporterDirector(exporterDescriptors);

    writeEvent();

    assertThat(timerScheduledLatch.await(5, TimeUnit.SECONDS)).isTrue();

    rule.getClock().addTime(delay);

    // then
    assertThat(timerTriggerLatch.await(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  public void shouldCancelScheduledCancellableTask() throws InterruptedException {
    // given
    final CountDownLatch timerScheduledLatch = new CountDownLatch(1);
    final CountDownLatch timerTriggerLatch = new CountDownLatch(1);
    final Duration delay = Duration.ofSeconds(10);

    final var exporter = exporters.get(0);
    final var shouldBeNotModifiedVariable = new AtomicLong(0);
    exporter.onExport(
        r -> {
          final var taskToCancel =
              exporter
                  .getController()
                  .scheduleCancellableTask(delay, () -> shouldBeNotModifiedVariable.set(1));
          exporter.getController().scheduleCancellableTask(delay, timerTriggerLatch::countDown);
          taskToCancel.cancel();
          timerScheduledLatch.countDown();
        });

    // when
    startExporterDirector(exporterDescriptors);

    writeEvent();

    assertThat(timerScheduledLatch.await(5, TimeUnit.SECONDS)).isTrue();

    rule.getClock().addTime(delay);

    // then
    assertThat(timerTriggerLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(shouldBeNotModifiedVariable.get()).isZero();
  }

  @Test
  public void shouldRecoverPositionsFromState() throws Exception {
    // given
    startExporterDirector(exporterDescriptors);

    final long eventPosition1 = writeEvent();
    final long eventPosition2 = writeEvent();

    waitUntil(() -> exporters.get(0).getExportedRecords().size() == 2);
    waitUntil(() -> exporters.get(1).getExportedRecords().size() == 2);

    exporters.get(0).getController().updateLastExportedRecordPosition(eventPosition2);
    exporters.get(1).getController().updateLastExportedRecordPosition(eventPosition1);

    rule.closeExporterDirector();
    exporters.get(0).getExportedRecords().clear();
    exporters.get(1).getExportedRecords().clear();

    // then
    startExporterDirector(exporterDescriptors);

    // then
    waitUntil(() -> exporters.get(1).getExportedRecords().size() >= 1);
    assertThat(exporters.get(0).getExportedRecords()).isEmpty();
    assertThat(exporters.get(1).getExportedRecords())
        .extracting(Record::getPosition)
        .hasSize(1)
        .contains(eventPosition2);
  }

  @Test
  public void shouldNotUpdatePositionToSmallerValue() throws Exception {
    // given
    final CountDownLatch latch = new CountDownLatch(1);
    final var controlledTestExporter = exporters.get(0);
    controlledTestExporter.onOpen(c -> latch.countDown());
    startExporterDirector(exporterDescriptors);
    latch.await();
    exporters.get(0).getController().updateLastExportedRecordPosition(1);
    final var firstPosition =
        Awaitility.await()
            .until(() -> rule.getExportersState().getPosition("exporter-1"), (pos) -> pos > -1);

    // when
    exporters.get(0).getController().updateLastExportedRecordPosition(-1);

    // then
    final var secondPosition = rule.getExportersState().getPosition("exporter-1");
    assertThat(secondPosition).isEqualTo(firstPosition);
  }

  @Test
  public void shouldUpdateLastExportedPositionOnClose() throws Exception {
    // given
    startExporterDirector(exporterDescriptors);

    final long eventPosition1 = writeEvent();
    final long eventPosition2 = writeEvent();

    waitUntil(() -> exporters.get(0).getExportedRecords().size() == 2);
    waitUntil(() -> exporters.get(1).getExportedRecords().size() == 2);

    exporters
        .get(0)
        .onClose(
            () ->
                exporters.get(0).getController().updateLastExportedRecordPosition(eventPosition1));

    // when
    rule.closeExporterDirector();
    exporters.get(0).getExportedRecords().clear();
    exporters.get(1).getExportedRecords().clear();

    startExporterDirector(exporterDescriptors);

    // then
    waitUntil(() -> exporters.get(1).getExportedRecords().size() >= 2);
    assertThat(exporters.get(0).getExportedRecords())
        .extracting(Record::getPosition)
        .hasSize(1)
        .contains(eventPosition2);
    assertThat(exporters.get(1).getExportedRecords())
        .extracting(Record::getPosition)
        .hasSize(2)
        .contains(eventPosition1, eventPosition2);
  }

  @Test
  public void shouldRemoveExporterFromState() throws Exception {
    // given
    startExporterDirector(exporterDescriptors);

    final long eventPosition = writeEvent();
    waitUntil(() -> exporters.get(0).getExportedRecords().size() == 1);
    waitUntil(() -> exporters.get(1).getExportedRecords().size() == 1);

    exporters.get(0).getController().updateLastExportedRecordPosition(eventPosition);
    exporters.get(1).getController().updateLastExportedRecordPosition(eventPosition);

    rule.closeExporterDirector();

    // when
    startExporterDirector(Collections.singletonList(exporterDescriptors.get(0)));

    verify(exporters.get(0), TIMEOUT.times(2)).open(any());

    // then
    final ExportersState exportersState = rule.getExportersState();
    assertThat(exportersState.getPosition(EXPORTER_ID_1)).isEqualTo(eventPosition);
    waitUntil(() -> exportersState.getPosition(EXPORTER_ID_2) == -1);
  }

  @Test
  public void shouldRecoverFromStartWithNonUpdatingExporter() throws Exception {
    // given
    startExporterDirector(exporterDescriptors);
    final long eventPosition = writeEvent();

    waitUntil(() -> exporters.get(0).getExportedRecords().size() == 1);
    waitUntil(() -> exporters.get(1).getExportedRecords().size() == 1);

    exporters.get(1).getController().updateLastExportedRecordPosition(eventPosition);

    // when
    rule.closeExporterDirector();
    exporters.get(0).getExportedRecords().clear();
    exporters.get(1).getExportedRecords().clear();
    startExporterDirector(exporterDescriptors);

    // then
    waitUntil(() -> exporters.get(0).getExportedRecords().size() >= 1);
    assertThat(exporters.get(0).getExportedRecords())
        .extracting(Record::getPosition)
        .containsExactly(eventPosition);
    assertThat(exporters.get(1).getExportedRecords()).isEmpty();
  }

  private long writeEvent() {
    final DeploymentRecord event = new DeploymentRecord();
    return rule.writeEvent(DeploymentIntent.CREATED, event);
  }

  private Consumer<Context> withFilter(
      final List<RecordType> acceptedTypes, final List<ValueType> valueTypes) {
    return context ->
        context.setFilter(
            new Context.RecordFilter() {
              @Override
              public boolean acceptType(final RecordType recordType) {
                return acceptedTypes.contains(recordType);
              }

              @Override
              public boolean acceptValue(final ValueType valueType) {
                return valueTypes.contains(valueType);
              }
            });
  }
}
