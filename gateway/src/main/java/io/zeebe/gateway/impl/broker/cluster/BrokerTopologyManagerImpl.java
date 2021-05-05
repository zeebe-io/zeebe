/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.gateway.impl.broker.cluster;

import static io.zeebe.gateway.impl.broker.cluster.BrokerClusterState.NODE_ID_NULL;

import io.atomix.cluster.ClusterMembershipEvent;
import io.atomix.cluster.ClusterMembershipEvent.Type;
import io.atomix.cluster.ClusterMembershipEventListener;
import io.atomix.cluster.Member;
import io.zeebe.gateway.Loggers;
import io.zeebe.protocol.impl.encoding.BrokerInfo;
import io.zeebe.util.sched.Actor;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;

public final class BrokerTopologyManagerImpl extends Actor
    implements BrokerTopologyManager, ClusterMembershipEventListener {

  private static final Logger LOG = Loggers.GATEWAY_LOGGER;

  protected final AtomicReference<BrokerClusterStateImpl> topology;
  private final Supplier<Set<Member>> membersSupplier;
  private final GatewayTopologyMetrics topologyMetrics = new GatewayTopologyMetrics();

  public BrokerTopologyManagerImpl(final Supplier<Set<Member>> membersSupplier) {
    this.membersSupplier = membersSupplier;
    topology = new AtomicReference<>(null);
  }

  /** @return the current known cluster state or null if the topology was not fetched yet */
  @Override
  public BrokerClusterState getTopology() {
    return topology.get();
  }

  public void setTopology(final BrokerClusterStateImpl topology) {
    this.topology.set(topology);
  }

  private void checkForMissingEvents() {
    final Set<Member> members = membersSupplier.get();
    if (members == null || members.isEmpty()) {
      return;
    }

    final BrokerClusterStateImpl newTopology = new BrokerClusterStateImpl(topology.get());
    for (final Member member : members) {
      final BrokerInfo brokerInfo = BrokerInfo.fromProperties(member.properties());
      if (brokerInfo != null) {
        newTopology.addBrokerIfAbsent(brokerInfo.getNodeId());
        processProperties(brokerInfo, newTopology);
      }
    }
    topology.set(newTopology);
  }

  @Override
  public String getName() {
    return "GatewayTopologyManager";
  }

  @Override
  protected void onActorStarted() {
    // to make gateway topology more robust we need to check for missing events periodically
    actor.runAtFixedRate(Duration.ofSeconds(5), this::checkForMissingEvents);
  }

  @Override
  public void event(final ClusterMembershipEvent event) {
    final Member subject = event.subject();
    final Type eventType = event.type();
    final BrokerInfo brokerInfo = BrokerInfo.fromProperties(subject.properties());

    if (brokerInfo != null) {
      actor.call(
          () -> {
            final BrokerClusterStateImpl newTopology = new BrokerClusterStateImpl(topology.get());

            switch (eventType) {
              case MEMBER_ADDED:
                LOG.debug("Received new broker {}.", brokerInfo);
                newTopology.addBrokerIfAbsent(brokerInfo.getNodeId());
                processProperties(brokerInfo, newTopology);
                break;

              case METADATA_CHANGED:
                LOG.debug(
                    "Received metadata change from Broker {}, partitions {}, terms {} and health {}.",
                    brokerInfo.getNodeId(),
                    brokerInfo.getPartitionRoles(),
                    brokerInfo.getPartitionLeaderTerms(),
                    brokerInfo.getPartitionHealthStatuses());
                newTopology.addBrokerIfAbsent(brokerInfo.getNodeId());
                processProperties(brokerInfo, newTopology);
                break;

              case MEMBER_REMOVED:
                LOG.debug("Received broker was removed {}.", brokerInfo);
                newTopology.removeBroker(brokerInfo.getNodeId());
                break;

              case REACHABILITY_CHANGED:
              default:
                LOG.debug(
                    "Received {} for broker {}, do nothing.", eventType, brokerInfo.getNodeId());
                break;
            }

            topology.set(newTopology);
            updateMetrics(newTopology);
          });
    }
  }

  // Update topology information based on the distributed event
  private void processProperties(
      final BrokerInfo distributedBrokerInfo, final BrokerClusterStateImpl newTopology) {

    newTopology.setClusterSize(distributedBrokerInfo.getClusterSize());
    newTopology.setPartitionsCount(distributedBrokerInfo.getPartitionsCount());
    newTopology.setReplicationFactor(distributedBrokerInfo.getReplicationFactor());

    final int nodeId = distributedBrokerInfo.getNodeId();

    distributedBrokerInfo.consumePartitions(
        newTopology::addPartitionIfAbsent,
        (leaderPartitionId, term) ->
            newTopology.setPartitionLeader(leaderPartitionId, nodeId, term),
        followerPartitionId -> newTopology.addPartitionFollower(followerPartitionId, nodeId),
        inactivePartitionId -> newTopology.addPartitionInactive(inactivePartitionId, nodeId));

    distributedBrokerInfo.consumePartitionsHealth(
        (partition, health) -> newTopology.setPartitionHealthStatus(nodeId, partition, health));

    final String clientAddress = distributedBrokerInfo.getCommandApiAddress();
    if (clientAddress != null) {
      newTopology.setBrokerAddressIfPresent(nodeId, clientAddress);
    }

    newTopology.setBrokerVersionIfPresent(nodeId, distributedBrokerInfo.getVersion());
  }

  private void updateMetrics(final BrokerClusterState topology) {
    final var partitions = topology.getPartitions();
    partitions.forEach(
        partition -> {
          final var leader = topology.getLeaderForPartition(partition);
          if (leader != NODE_ID_NULL) {
            topologyMetrics.setLeaderForPartition(partition, leader);
          }

          final var followers = topology.getFollowersForPartition(partition);
          if (followers != null) {
            followers.forEach(broker -> topologyMetrics.setFollower(partition, broker));
          }
        });
  }
}
