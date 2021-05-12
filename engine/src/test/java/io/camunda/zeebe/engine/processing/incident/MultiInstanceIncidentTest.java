/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.camunda.zeebe.engine.util.EngineRule;
import io.camunda.zeebe.engine.util.client.IncidentClient.ResolveIncidentClient;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.protocol.record.Assertions;
import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.protocol.record.intent.IncidentIntent;
import io.camunda.zeebe.protocol.record.intent.JobIntent;
import io.camunda.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.camunda.zeebe.protocol.record.intent.VariableIntent;
import io.camunda.zeebe.protocol.record.value.BpmnElementType;
import io.camunda.zeebe.protocol.record.value.ErrorType;
import io.camunda.zeebe.protocol.record.value.IncidentRecordValue;
import io.camunda.zeebe.protocol.record.value.ProcessInstanceRecordValue;
import io.camunda.zeebe.test.util.BrokerClassRuleHelper;
import io.camunda.zeebe.test.util.record.RecordingExporter;
import io.camunda.zeebe.test.util.record.RecordingExporterTestWatcher;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

public final class MultiInstanceIncidentTest {

  @ClassRule public static final EngineRule ENGINE = EngineRule.singlePartition();

  private static final String MULTI_TASK_PROCESS = "multi-task-process";
  private static final String MULTI_SUB_PROC_PROCESS = "multi-sub-process-process";
  private static final String ELEMENT_ID = "task";
  private static final String INPUT_COLLECTION = "items";
  private static final String INPUT_ELEMENT = "item";

  @Rule
  public final RecordingExporterTestWatcher recordingExporterTestWatcher =
      new RecordingExporterTestWatcher();

  @Rule public final BrokerClassRuleHelper helper = new BrokerClassRuleHelper();
  private String jobType;

  @Before
  public void init() {
    jobType = helper.getJobType();
    ENGINE
        .deployment()
        .withXmlResource(
            Bpmn.createExecutableProcess(MULTI_TASK_PROCESS)
                .startEvent()
                .serviceTask(
                    ELEMENT_ID,
                    t ->
                        t.zeebeJobType(jobType)
                            .multiInstance(
                                b ->
                                    b.zeebeInputCollectionExpression(INPUT_COLLECTION)
                                        .zeebeInputElement(INPUT_ELEMENT)
                                        .zeebeOutputElementExpression("sum(undefined_var)")
                                        .zeebeOutputCollection("results")))
                .endEvent()
                .done())
        .withXmlResource(
            Bpmn.createExecutableProcess(MULTI_SUB_PROC_PROCESS)
                .startEvent()
                .subProcess("sub-process")
                .zeebeInputExpression("y", "y")
                .multiInstance(
                    b ->
                        b.parallel()
                            .zeebeInputCollectionExpression(INPUT_COLLECTION)
                            .zeebeInputElement(INPUT_ELEMENT))
                .embeddedSubProcess()
                .startEvent("sub-process-start")
                .serviceTask(ELEMENT_ID, t -> t.zeebeJobType(jobType))
                .endEvent("sub-process-end")
                .moveToNode("sub-process")
                .endEvent()
                .done())
        .deploy();
  }

  @Test
  public void shouldCreateIncidentIfInputVariableNotFound() {
    // when
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId(MULTI_TASK_PROCESS).create();

    // then
    final Record<IncidentRecordValue> incident =
        RecordingExporter.incidentRecords(IncidentIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    final Record<ProcessInstanceRecordValue> elementInstance =
        RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_ACTIVATING)
            .withProcessInstanceKey(processInstanceKey)
            .withElementId(ELEMENT_ID)
            .getFirst();

    Assertions.assertThat(incident.getValue())
        .hasElementInstanceKey(elementInstance.getKey())
        .hasElementId(elementInstance.getValue().getElementId())
        .hasErrorType(ErrorType.EXTRACT_VALUE_ERROR)
        .hasErrorMessage(
            "failed to evaluate expression '"
                + INPUT_COLLECTION
                + "': no variable found for name '"
                + INPUT_COLLECTION
                + "'");
  }

  @Test
  public void shouldCreateIncidentIfInputVariableIsNotAnArray() {
    // when
    final long processInstanceKey =
        ENGINE
            .processInstance()
            .ofBpmnProcessId(MULTI_TASK_PROCESS)
            .withVariable(INPUT_COLLECTION, "not-an-array-but-a-string")
            .create();

    // then
    final Record<IncidentRecordValue> incident =
        RecordingExporter.incidentRecords(IncidentIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    final Record<ProcessInstanceRecordValue> elementInstance =
        RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_ACTIVATING)
            .withProcessInstanceKey(processInstanceKey)
            .withElementId(ELEMENT_ID)
            .getFirst();

    Assertions.assertThat(incident.getValue())
        .hasElementInstanceKey(elementInstance.getKey())
        .hasElementId(elementInstance.getValue().getElementId())
        .hasErrorType(ErrorType.EXTRACT_VALUE_ERROR)
        .hasErrorMessage(
            "Expected result of the expression '"
                + INPUT_COLLECTION
                + "' to be 'ARRAY', but was 'STRING'.");
  }

  @Test
  public void shouldCreateIncidentIfOutputElementExpressionEvaluationFailed() {
    // given
    final long processInstanceKey =
        ENGINE
            .processInstance()
            .ofBpmnProcessId(MULTI_TASK_PROCESS)
            .withVariable(INPUT_COLLECTION, List.of(1, 2, 3))
            .create();

    // when
    ENGINE.job().withType(jobType).ofInstance(processInstanceKey).complete();

    // then
    final Record<IncidentRecordValue> incident =
        RecordingExporter.incidentRecords(IncidentIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    final Record<ProcessInstanceRecordValue> elementInstance =
        RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_COMPLETING)
            .withProcessInstanceKey(processInstanceKey)
            .withElementId(ELEMENT_ID)
            .getFirst();

    Assertions.assertThat(incident.getValue())
        .hasElementInstanceKey(elementInstance.getKey())
        .hasElementId(elementInstance.getValue().getElementId())
        .hasErrorType(ErrorType.EXTRACT_VALUE_ERROR)
        .hasErrorMessage(
            "failed to evaluate expression 'sum(undefined_var)': expected number but found "
                + "'ValError(no variable found for name 'undefined_var')'");
  }

  @Test
  public void shouldResolveIncident() {
    // given
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId(MULTI_TASK_PROCESS).create();

    final Record<IncidentRecordValue> incident =
        RecordingExporter.incidentRecords(IncidentIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    // when
    ENGINE
        .variables()
        .ofScope(incident.getValue().getVariableScopeKey())
        .withDocument(Collections.singletonMap(INPUT_COLLECTION, Arrays.asList(10, 20, 30)))
        .update();

    ENGINE.incident().ofInstance(processInstanceKey).withKey(incident.getKey()).resolve();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords()
                .withRecordKey(incident.getValue().getElementInstanceKey())
                .limit(3))
        .extracting(Record::getIntent)
        .contains(ProcessInstanceIntent.ELEMENT_ACTIVATED);
  }

  @Test
  public void shouldOnlyCreateNotUpdateLoopCountersWhenIncidentResolved() {
    // given
    final var processInstanceKey =
        ENGINE
            .processInstance()
            .ofBpmnProcessId(MULTI_SUB_PROC_PROCESS)
            .withVariables("{\"items\":[1,2,3]}")
            .create();

    Awaitility.await("Each multi-instance inner body has an incident")
        .pollInterval(Duration.ofMillis(100))
        .until(
            () ->
                RecordingExporter.incidentRecords(IncidentIntent.CREATED)
                    .withProcessInstanceKey(processInstanceKey)
                    .limit(3)
                    .count(),
            count -> count == 3);

    // when
    ENGINE.variables().ofScope(processInstanceKey).withDocument("{\"y\":10}").update();
    RecordingExporter.incidentRecords(IncidentIntent.CREATED)
        .withProcessInstanceKey(processInstanceKey)
        .limit(3)
        .map(Record::getKey)
        .map(key -> ENGINE.incident().ofInstance(processInstanceKey).withKey(key))
        .forEach(ResolveIncidentClient::resolve);

    Awaitility.await("Each multi-instance has a job created")
        .pollInterval(Duration.ofMillis(100))
        .until(
            () ->
                RecordingExporter.jobRecords(JobIntent.CREATED)
                    .withProcessInstanceKey(processInstanceKey)
                    .limit(3)
                    .count(),
            count -> count == 3);

    final var subProcessKeys =
        RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_ACTIVATING)
            .withProcessInstanceKey(processInstanceKey)
            .withElementType(BpmnElementType.SUB_PROCESS)
            .limit(3)
            .map(Record::getKey)
            .collect(Collectors.toSet());

    // then
    final var loopCounters =
        RecordingExporter.variableRecords()
            .withName("loopCounter")
            .filter(v -> subProcessKeys.contains(v.getValue().getScopeKey()))
            .collect(Collectors.toList());

    assertThat(loopCounters)
        .filteredOn(v -> v.getIntent() == VariableIntent.CREATED)
        .extracting(v -> tuple(v.getIntent(), v.getValue().getValue()))
        .hasSize(3)
        .containsExactly(
            tuple(VariableIntent.CREATED, "1"),
            tuple(VariableIntent.CREATED, "2"),
            tuple(VariableIntent.CREATED, "3"));

    assertThat(loopCounters)
        .filteredOn(v -> v.getIntent() == VariableIntent.UPDATED)
        .extracting(v -> tuple(v.getIntent(), v.getValue().getValue()))
        .isEmpty();
  }
}
