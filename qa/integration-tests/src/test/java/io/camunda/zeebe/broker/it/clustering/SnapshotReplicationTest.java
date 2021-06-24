/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.it.clustering;

import io.camunda.zeebe.broker.Broker;
import io.camunda.zeebe.broker.it.util.GrpcClientRule;
import io.camunda.zeebe.broker.system.configuration.BrokerCfg;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

public final class SnapshotReplicationTest {

  private static final int PARTITION_COUNT = 1;
  private static final Duration SNAPSHOT_PERIOD = Duration.ofMinutes(5);
  private static final BpmnModelInstance PROCESS =
      Bpmn.createExecutableProcess("process").startEvent().endEvent().done();

  private final ClusteringRule clusteringRule =
      new ClusteringRule(PARTITION_COUNT, 3, 3, SnapshotReplicationTest::configureBroker);
  public final GrpcClientRule clientRule = new GrpcClientRule(clusteringRule);
  @Rule public RuleChain ruleChain = RuleChain.outerRule(clusteringRule).around(clientRule);

  @Test
  public void shouldReplicateSnapshots() {
    // given
    final int leaderNodeId = clusteringRule.getLeaderForPartition(1).getNodeId();
    final Broker leader = clusteringRule.getBroker(leaderNodeId);

    // when
    triggerSnapshotCreation();
    clusteringRule.waitForSnapshotAtBroker(leader);
    final List<Broker> otherBrokers = clusteringRule.getOtherBrokerObjects(leaderNodeId);
    for (final Broker broker : otherBrokers) {
      clusteringRule.waitForSnapshotAtBroker(broker);
    }
  }

  @Test
  public void shouldReceiveNewSnapshotsOnRejoin() {
    // given
    final var leaderNodeId = clusteringRule.getLeaderForPartition(1).getNodeId();
    final var followers =
        clusteringRule.getOtherBrokerObjects(leaderNodeId).stream()
            .map(b -> b.getConfig().getCluster().getNodeId())
            .collect(Collectors.toList());

    final var firstFollowerId = followers.get(0);
    final var secondFollowerId = followers.get(1);

    // when - snapshot
    clusteringRule.stopBrokerAndAwaitNewLeader(firstFollowerId);
    triggerSnapshotCreation();
    final var snapshotAtSecondFollower =
        clusteringRule.waitForSnapshotAtBroker(clusteringRule.getBroker(secondFollowerId));
    clusteringRule.restartBroker(firstFollowerId);

    triggerSnapshotCreation();
    clusteringRule.waitForNewSnapshotAtBroker(
        clusteringRule.getBroker(leaderNodeId), snapshotAtSecondFollower);
    clusteringRule.waitForNewSnapshotAtBroker(
        clusteringRule.getBroker(firstFollowerId), snapshotAtSecondFollower);
  }

  private void triggerSnapshotCreation() {
    clientRule.deployProcess(PROCESS);
    try {
      Thread.sleep(5000L); // we need to wait a bit until all are ready
    } catch (final InterruptedException e) {
      e.printStackTrace();
    }
    clusteringRule.getClock().addTime(SNAPSHOT_PERIOD);
  }

  @Test
  public void shouldReplicateSnapshotsOnLeaderChange() {
    // given
    final var oldLeaderId = clusteringRule.getLeaderForPartition(1).getNodeId();
    final var otherBrokers = clusteringRule.getOtherBrokerObjects(oldLeaderId);
    final var firstFollowerId = otherBrokers.get(0).getConfig().getCluster().getNodeId();
    final var secondFollowerId = otherBrokers.get(1).getConfig().getCluster().getNodeId();

    // when
    triggerSnapshotCreation();
    final var snapshotAtLeader =
        clusteringRule.waitForSnapshotAtBroker(clusteringRule.getBroker(oldLeaderId));
    final var snapshotAtFirstFollower =
        clusteringRule.waitForSnapshotAtBroker(clusteringRule.getBroker(firstFollowerId));
    final var snapshotAtSecondFollower =
        clusteringRule.waitForSnapshotAtBroker(clusteringRule.getBroker(secondFollowerId));

    triggerLeaderChange(oldLeaderId);
    triggerSnapshotCreation();
    clusteringRule.waitForNewSnapshotAtBroker(
        clusteringRule.getBroker(firstFollowerId), snapshotAtFirstFollower);
    clusteringRule.waitForNewSnapshotAtBroker(
        clusteringRule.getBroker(secondFollowerId), snapshotAtSecondFollower);
    clusteringRule.waitForNewSnapshotAtBroker(
        clusteringRule.getBroker(oldLeaderId), snapshotAtLeader);
  }

  private void triggerLeaderChange(final int oldLeaderId) {
    long newLeaderId;
    do {
      clusteringRule.restartBroker(oldLeaderId);
      newLeaderId = clusteringRule.getLeaderForPartition(1).getNodeId();
    } while (newLeaderId == oldLeaderId);
  }

  private static void configureBroker(final BrokerCfg brokerCfg) {
    brokerCfg.getData().setSnapshotPeriod(SNAPSHOT_PERIOD);
    brokerCfg.getData().setLogIndexDensity(1);
  }
}
