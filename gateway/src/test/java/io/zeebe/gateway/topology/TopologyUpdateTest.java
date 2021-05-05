/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.gateway.topology;

import static io.zeebe.gateway.impl.broker.cluster.BrokerClusterState.NODE_ID_NULL;
import static io.zeebe.test.util.TestUtil.waitUntil;
import static org.assertj.core.api.Assertions.assertThat;

import io.atomix.cluster.ClusterMembershipEvent;
import io.atomix.cluster.ClusterMembershipEvent.Type;
import io.atomix.cluster.Member;
import io.atomix.cluster.MemberConfig;
import io.zeebe.gateway.impl.broker.cluster.BrokerTopologyManagerImpl;
import io.zeebe.protocol.impl.encoding.BrokerInfo;
import io.zeebe.protocol.record.PartitionHealthStatus;
import io.zeebe.util.sched.clock.ControlledActorClock;
import io.zeebe.util.sched.testing.ActorSchedulerRule;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public final class TopologyUpdateTest {

  private final ControlledActorClock actorClock = new ControlledActorClock();
  @Rule public final ActorSchedulerRule actorSchedulerRule = new ActorSchedulerRule(actorClock);

  private BrokerTopologyManagerImpl topologyManager;
  private Set<Member> members;

  @Before
  public void setUp() {
    members = new HashSet<>();
    topologyManager = new BrokerTopologyManagerImpl(() -> members);
    actorSchedulerRule.submitActor(topologyManager);
  }

  @After
  public void tearDown() {
    topologyManager.close();
  }

  @Test
  public void shouldUpdateTopologyOnBrokerAdd() {
    final BrokerInfo broker = createBroker(0);
    topologyManager.event(createMemberAddedEvent(broker));
    waitUntil(() -> topologyManager.getTopology() != null);
    assertThat(topologyManager.getTopology().getFollowersForPartition(1)).isNull();

    final BrokerInfo brokerUpdated = createBroker(0);
    brokerUpdated.setFollowerForPartition(1);
    topologyManager.event(createMemberUpdateEvent(brokerUpdated));
    waitUntil(() -> topologyManager.getTopology().getFollowersForPartition(1) != null);
    assertThat(topologyManager.getTopology().getFollowersForPartition(1).contains(0)).isTrue();
    assertThat(topologyManager.getTopology().getBrokerVersion(0)).isEqualTo(broker.getVersion());
  }

  @Test
  public void shouldAddBrokerOnTopologyEvenOnNotReceivedEvent() {
    // given
    final BrokerInfo broker = createBroker(0);
    broker.setFollowerForPartition(1);
    createMemberAddedEvent(broker);

    // when
    actorClock.addTime(Duration.ofSeconds(10));

    // then
    waitUntil(() -> topologyManager.getTopology() != null);
    assertThat(topologyManager.getTopology().getFollowersForPartition(1).contains(0)).isTrue();
  }

  @Test
  public void shouldUpdateBrokerOnTopologyEvenOnNotReceivedEvent() {
    // given
    final BrokerInfo broker = createBroker(0);
    topologyManager.event(createMemberAddedEvent(broker));
    waitUntil(() -> topologyManager.getTopology() != null);
    assertThat(topologyManager.getTopology().getFollowersForPartition(1)).isNull();

    // when
    final BrokerInfo brokerUpdate = createBroker(0);
    brokerUpdate.setFollowerForPartition(1);
    createMemberUpdateEvent(brokerUpdate);
    actorClock.addTime(Duration.ofSeconds(10));

    // then
    waitUntil(() -> topologyManager.getTopology().getFollowersForPartition(1) != null);
    assertThat(topologyManager.getTopology().getFollowersForPartition(1).contains(0)).isTrue();
  }

  @Test
  public void shouldUpdateTopologyOnBrokerRemove() {
    final BrokerInfo broker = createBroker(0);
    topologyManager.event(createMemberAddedEvent(broker));
    final BrokerInfo brokerUpdated = createBroker(0);
    brokerUpdated.setFollowerForPartition(1);
    topologyManager.event(createMemberUpdateEvent(brokerUpdated));
    waitUntil(() -> topologyManager.getTopology() != null);

    topologyManager.event(createMemberRemoveEvent(brokerUpdated));
    waitUntil(() -> topologyManager.getTopology().getBrokers().isEmpty());

    topologyManager.event(createMemberAddedEvent(broker));
    waitUntil(() -> !topologyManager.getTopology().getBrokers().isEmpty());

    assertThat(topologyManager.getTopology().getFollowersForPartition(1)).doesNotContain(0);
    assertThat(topologyManager.getTopology().getLeaderForPartition(1)).isEqualTo(NODE_ID_NULL);
  }

  @Test
  public void shouldUpdateLeaderWithNewTerm() {
    // given
    final int oldLeaderId = 0;
    final BrokerInfo oldLeader = createBroker(oldLeaderId);
    oldLeader.setLeaderForPartition(1, 1);
    topologyManager.event(createMemberAddedEvent(oldLeader));
    waitUntil(() -> topologyManager.getTopology() != null);
    assertThat(topologyManager.getTopology().getLeaderForPartition(1)).isEqualTo(oldLeaderId);

    // when
    final int newLeaderId = 1;
    final BrokerInfo newLeader = createBroker(newLeaderId);
    newLeader.setLeaderForPartition(1, 2);
    topologyManager.event(createMemberAddedEvent(newLeader));

    // then
    waitUntil(() -> topologyManager.getTopology().getBrokers().contains(newLeaderId));
    assertThat(topologyManager.getTopology().getLeaderForPartition(1)).isEqualTo(newLeaderId);
  }

  @Test
  public void shouldNotUpdateLeaderWhenPreviousTerm() {
    // given
    final int newLeaderId = 1;
    final BrokerInfo newLeader = createBroker(newLeaderId);
    newLeader.setLeaderForPartition(1, 2);
    topologyManager.event(createMemberAddedEvent(newLeader));
    waitUntil(() -> topologyManager.getTopology() != null);
    assertThat(topologyManager.getTopology().getLeaderForPartition(1)).isEqualTo(newLeaderId);

    // when
    final int oldLeaderId = 0;
    final BrokerInfo oldLeader = createBroker(oldLeaderId);
    oldLeader.setLeaderForPartition(1, 1);
    topologyManager.event(createMemberAddedEvent(oldLeader));

    // then
    waitUntil(() -> topologyManager.getTopology().getBrokers().contains(oldLeaderId));
    assertThat(topologyManager.getTopology().getLeaderForPartition(1)).isEqualTo(newLeaderId);
  }

  @Test
  public void shouldUpdateTopologyOnBrokerRemoveAndDirectlyRejoin() {
    // given
    final int leaderId = 1;
    final BrokerInfo leader = createBroker(leaderId);
    leader.setLeaderForPartition(1, 1);
    topologyManager.event(createMemberAddedEvent(leader));
    waitUntil(() -> topologyManager.getTopology() != null);

    // when
    topologyManager.event(createMemberRemoveEvent(leader));
    waitUntil(() -> topologyManager.getTopology().getBrokers().isEmpty());
    topologyManager.event(createMemberAddedEvent(leader));

    // then
    waitUntil(() -> topologyManager.getTopology().getBrokers().contains(leaderId));
    assertThat(topologyManager.getTopology().getLeaderForPartition(1)).isEqualTo(leaderId);
  }

  @Test
  public void shouldUpdateTopologyOnPartitionHealth() {
    final int brokerId = 0;
    final int partition = 0;
    final BrokerInfo broker = createBroker(brokerId);
    broker.setPartitionHealthy(partition);
    topologyManager.event(createMemberAddedEvent(broker));
    waitUntil(() -> topologyManager.getTopology() != null);
    assertThat(topologyManager.getTopology().getPartitionHealth(brokerId, partition))
        .isEqualTo(PartitionHealthStatus.HEALTHY);

    final BrokerInfo updatedBroker = createBroker(0);
    updatedBroker.setPartitionUnhealthy(partition);
    topologyManager.event(createMemberUpdateEvent(updatedBroker));
    waitUntil(
        () ->
            topologyManager.getTopology().getPartitionHealth(brokerId, partition)
                == PartitionHealthStatus.UNHEALTHY);
    assertThat(topologyManager.getTopology().getPartitionHealth(brokerId, partition))
        .isEqualTo(PartitionHealthStatus.UNHEALTHY);
  }

  @Test
  public void shouldUpdateTopologyOnLeaderRemoval() {
    // given
    final BrokerInfo broker = createBroker(0).setLeaderForPartition(1, 1);

    // when
    topologyManager.event(createMemberUpdateEvent(broker));
    Awaitility.await("broker 0 is leader of partition 1")
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(
            () -> assertThat(topologyManager.getTopology().getLeaderForPartition(1)).isZero());

    broker.setFollowerForPartition(1);
    topologyManager.event(createMemberUpdateEvent(broker));

    // then
    Awaitility.await("broker 0 is follower of partition 1")
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(
            () -> {
              assertThat(topologyManager.getTopology().getFollowersForPartition(1))
                  .containsExactlyInAnyOrder(0);
              assertThat(topologyManager.getTopology().getLeaderForPartition(1))
                  .isEqualTo(NODE_ID_NULL);
            });
  }

  @Test
  public void shouldUpdateTopologyOnBrokerInactive() {
    // given
    final int partition = 0;
    final int brokerId = 0;
    final BrokerInfo broker = createBroker(brokerId);
    broker.setLeaderForPartition(partition, 1);
    topologyManager.event(createMemberAddedEvent(broker));

    // when
    Awaitility.waitAtMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(
            () -> {
              assertThat(topologyManager.getTopology().getInactiveNodesForPartition(partition))
                  .isNullOrEmpty();
              assertThat(topologyManager.getTopology().getLeaderForPartition(partition))
                  .isEqualTo(brokerId);
            });
    broker.setInactiveForPartition(partition);
    topologyManager.event(createMemberUpdateEvent(broker));

    // then
    Awaitility.waitAtMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(
            () -> {
              assertThat(topologyManager.getTopology().getInactiveNodesForPartition(partition))
                  .contains(brokerId);
              assertThat(topologyManager.getTopology().getLeaderForPartition(partition))
                  .isNotEqualTo(brokerId);
            });
  }

  private BrokerInfo createBroker(final int brokerId) {
    final BrokerInfo broker =
        new BrokerInfo()
            .setNodeId(brokerId)
            .setPartitionsCount(1)
            .setClusterSize(3)
            .setReplicationFactor(3);
    broker.setCommandApiAddress("localhost:1000");
    broker.setVersion("0.23.0-SNAPSHOT");
    return broker;
  }

  private ClusterMembershipEvent createMemberAddedEvent(final BrokerInfo broker) {
    final Member member = createMemberFromBrokerInfo(broker);
    return new ClusterMembershipEvent(Type.MEMBER_ADDED, member);
  }

  private ClusterMembershipEvent createMemberUpdateEvent(final BrokerInfo broker) {
    final Member member = createMemberFromBrokerInfo(broker);
    return new ClusterMembershipEvent(Type.METADATA_CHANGED, member);
  }

  private Member createMemberFromBrokerInfo(final BrokerInfo broker) {
    final Member member =
        new Member(new MemberConfig().setId(Integer.toString(broker.getNodeId())));
    broker.writeIntoProperties(member.properties());
    members.add(member);
    return member;
  }

  private ClusterMembershipEvent createMemberRemoveEvent(final BrokerInfo broker) {
    final Member member = new Member(new MemberConfig());
    broker.writeIntoProperties(member.properties());
    return new ClusterMembershipEvent(Type.MEMBER_REMOVED, member);
  }
}
