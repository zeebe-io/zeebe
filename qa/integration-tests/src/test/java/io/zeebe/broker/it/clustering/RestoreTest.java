/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.it.clustering;

import static io.zeebe.protocol.Protocol.START_PARTITION_ID;
import static org.assertj.core.api.Assertions.assertThat;

import io.netty.util.NetUtil;
import io.zeebe.broker.Broker;
import io.zeebe.broker.it.util.GrpcClientRule;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.springframework.util.unit.DataSize;

public final class RestoreTest {
  private static final DataSize ATOMIX_SEGMENT_SIZE = DataSize.ofMegabytes(2);
  private static final DataSize LARGE_PAYLOAD_BYTESIZE = DataSize.ofKilobytes(32);
  private static final String LARGE_PAYLOAD =
      "{\"blob\": \"" + getRandomBase64Bytes(LARGE_PAYLOAD_BYTESIZE.toBytes()) + "\"}";

  private static final Duration SNAPSHOT_PERIOD = Duration.ofMinutes(5);
  private final ClusteringRule clusteringRule =
      new ClusteringRule(
          1,
          3,
          3,
          cfg -> {
            cfg.getData().setSnapshotPeriod(SNAPSHOT_PERIOD);
            cfg.getData().setLogSegmentSize(ATOMIX_SEGMENT_SIZE);
            cfg.getData().setLogIndexDensity(1);
            cfg.getNetwork().setMaxMessageSize(ATOMIX_SEGMENT_SIZE);
            cfg.getData().setUseMmap(false);
          });
  private final GrpcClientRule clientRule =
      new GrpcClientRule(
          config ->
              config
                  .gatewayAddress(NetUtil.toSocketAddressString(clusteringRule.getGatewayAddress()))
                  .defaultRequestTimeout(Duration.ofMinutes(1))
                  .usePlaintext());

  @Rule public RuleChain ruleChain = RuleChain.outerRule(clusteringRule).around(clientRule);

  @Test
  public void shouldReplicateLogEvents() {
    // given
    clusteringRule.stopBrokerAndAwaitNewLeader(2);

    final BpmnModelInstance firstWorkflow =
        Bpmn.createExecutableProcess("process-test1").startEvent().endEvent().done();

    final BpmnModelInstance secondWorkflow =
        Bpmn.createExecutableProcess("process-test2").startEvent().endEvent().done();

    final BpmnModelInstance thirdWorkflow =
        Bpmn.createExecutableProcess("process-test3").startEvent().endEvent().done();

    // when
    final long firstWorkflowKey = clientRule.deployWorkflow(firstWorkflow);
    clusteringRule.getClock().addTime(SNAPSHOT_PERIOD);
    clusteringRule.waitForSnapshotAtBroker(getLeader());

    final long secondWorkflowKey = clientRule.deployWorkflow(secondWorkflow);

    writeManyEventsUntilAtomixLogIsCompactable();
    clusteringRule.waitForSnapshotAtBroker(
        clusteringRule.getBroker(clusteringRule.getLeaderForPartition(1).getNodeId()));

    clusteringRule.restartBroker(2);
    clusteringRule.waitForSnapshotAtBroker(clusteringRule.getBroker(2));

    clusteringRule.stopBrokerAndAwaitNewLeader(1);

    final long thirdWorkflowKey = clientRule.deployWorkflow(thirdWorkflow);

    writeManyEventsUntilAtomixLogIsCompactable();
    clusteringRule.waitForSnapshotAtBroker(
        clusteringRule.getBroker(clusteringRule.getLeaderForPartition(1).getNodeId()));

    clusteringRule.restartBroker(1);
    clusteringRule.stopBrokerAndAwaitNewLeader(0);

    // then
    // If restore did not happen, following workflows won't be deployed
    assertThat(clientRule.createWorkflowInstance(firstWorkflowKey)).isPositive();
    assertThat(clientRule.createWorkflowInstance(secondWorkflowKey)).isPositive();
    assertThat(clientRule.createWorkflowInstance(thirdWorkflowKey)).isPositive();
  }

  @Test
  public void shouldKeepPositionsConsistent() {
    // given
    writeManyEventsUntilAtomixLogIsCompactable();

    // when
    clusteringRule.restartBroker(clusteringRule.getLeaderForPartition(1).getNodeId());

    writeManyEventsUntilAtomixLogIsCompactable();

    // then
    final var leaderLogStream = clusteringRule.getLogStream(1);

    final var reader = leaderLogStream.newLogStreamReader().join();
    reader.seekToFirstEvent();
    assertThat(reader.hasNext()).isTrue();

    var previousPosition = -1L;
    while (reader.hasNext()) {
      final var position = reader.next().getPosition();
      assertThat(position).isGreaterThan(previousPosition);
      previousPosition = position;
    }
  }

  private Broker getLeader() {
    return clusteringRule.getBroker(
        clusteringRule.getLeaderForPartition(START_PARTITION_ID).getNodeId());
  }

  private void writeManyEventsUntilAtomixLogIsCompactable() {
    final BpmnModelInstance workflow =
        Bpmn.createExecutableProcess("process").startEvent().endEvent().done();
    final long workflowKey = clientRule.deployWorkflow(workflow);
    final int requiredInstances =
        (int) Math.floorDiv(ATOMIX_SEGMENT_SIZE.toBytes(), LARGE_PAYLOAD_BYTESIZE.toBytes()) + 1;
    IntStream.range(0, requiredInstances)
        .forEach(i -> clientRule.createWorkflowInstance(workflowKey, LARGE_PAYLOAD));
  }

  private static String getRandomBase64Bytes(final long size) {
    final byte[] bytes = new byte[(int) size];
    ThreadLocalRandom.current().nextBytes(bytes);

    return Base64.getEncoder().encodeToString(bytes);
  }
}
