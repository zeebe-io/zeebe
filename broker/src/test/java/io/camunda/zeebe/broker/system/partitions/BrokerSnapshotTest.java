/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.system.partitions;

import static org.assertj.core.api.Assertions.assertThat;

import io.atomix.primitive.partition.PartitionId;
import io.atomix.raft.partition.RaftPartition;
import io.atomix.raft.storage.log.RaftLogReader;
import io.atomix.raft.storage.log.RaftLogReader.Mode;
import io.camunda.zeebe.broker.clustering.atomix.AtomixFactory;
import io.camunda.zeebe.broker.system.management.BrokerAdminService;
import io.camunda.zeebe.broker.system.management.PartitionStatus;
import io.camunda.zeebe.broker.test.EmbeddedBrokerRule;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.ZeebeClientBuilder;
import io.camunda.zeebe.snapshots.SnapshotId;
import io.camunda.zeebe.snapshots.impl.FileBasedSnapshotMetadata;
import io.netty.util.NetUtil;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.agrona.CloseHelper;
import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class BrokerSnapshotTest {

  private static final int PARTITION_ID = 1;
  @Rule public final EmbeddedBrokerRule brokerRule = new EmbeddedBrokerRule();

  private RaftLogReader journalReader;
  private BrokerAdminService brokerAdminService;
  private ZeebeClient client;

  @Before
  public void setup() {
    final RaftPartition raftPartition =
        (RaftPartition)
            brokerRule
                .getBroker()
                .getPartitionManager()
                .getPartitionGroup()
                .getPartition(PartitionId.from(AtomixFactory.GROUP_NAME, PARTITION_ID));
    journalReader = raftPartition.getServer().openReader(Mode.COMMITS);
    brokerAdminService = brokerRule.getBroker().getBrokerAdminService();

    final String contactPoint = NetUtil.toSocketAddressString(brokerRule.getGatewayAddress());
    final ZeebeClientBuilder zeebeClientBuilder =
        ZeebeClient.newClientBuilder().usePlaintext().gatewayAddress(contactPoint);
    client = zeebeClientBuilder.build();
  }

  @After
  public void after() {
    CloseHelper.closeAll(client, journalReader);
  }

  @Test
  public void shouldTakeSnapshotAtCorrectIndex() {
    // given
    createSomeEvents();

    // when
    brokerAdminService.takeSnapshot();
    final SnapshotId snapshotId = waitForSnapshotAtBroker(brokerAdminService, PARTITION_ID);

    // then
    final long processedIndex = journalReader.seekToAsqn(snapshotId.getProcessedPosition());
    final long expectedSnapshotIndex = processedIndex - 1;

    assertThat(snapshotId.getIndex()).isEqualTo(expectedSnapshotIndex);
  }

  private void createSomeEvents() {
    IntStream.range(0, 10).forEach(this::publishMaxMessageSizeMessage);
  }

  private void publishMaxMessageSizeMessage(final int key) {
    client.newPublishMessageCommand().messageName("msg").correlationKey("msg-" + key).send().join();
  }

  private SnapshotId waitForSnapshotAtBroker(
      final BrokerAdminService adminService, final int partitionId) {
    Awaitility.await()
        .pollInterval(1, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(
                        adminService
                            .getPartitionStatus()
                            .get(partitionId)
                            .getProcessedPositionInSnapshot())
                    .isNotNull());
    final PartitionStatus partitionStatus = brokerAdminService.getPartitionStatus().get(1);
    return FileBasedSnapshotMetadata.ofFileName(partitionStatus.getSnapshotId()).get();
  }
}
