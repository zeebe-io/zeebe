/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.broker.clustering.topology;

import io.atomix.cluster.ClusterMembershipEvent;
import io.atomix.cluster.ClusterMembershipEvent.Type;
import io.atomix.cluster.ClusterMembershipEventListener;
import io.atomix.cluster.Member;
import io.atomix.core.Atomix;
import io.zeebe.broker.Loggers;
import io.zeebe.broker.PartitionListener;
import io.zeebe.broker.system.configuration.ClusterCfg;
import io.zeebe.logstreams.log.LogStream;
import io.zeebe.protocol.impl.encoding.BrokerInfo;
import io.zeebe.util.LogUtil;
import io.zeebe.util.VersionUtil;
import io.zeebe.util.health.HealthStatus;
import io.zeebe.util.sched.Actor;
import io.zeebe.util.sched.future.ActorFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.agrona.collections.Int2ObjectHashMap;
import org.slf4j.Logger;

// TODO: This will be fixed in the https://github.com/zeebe-io/zeebe/issues/5640
@SuppressWarnings("squid:S1200")
public final class TopologyManagerImpl extends Actor
    implements TopologyManager, ClusterMembershipEventListener, PartitionListener {

  private static final Logger LOG = Loggers.CLUSTERING_LOGGER;

  private final Int2ObjectHashMap<BrokerInfo> partitionLeaders = new Int2ObjectHashMap<>();
  private final Atomix atomix;
  private final BrokerInfo localBroker;

  private final List<TopologyPartitionListener> topologyPartitionListeners = new ArrayList<>();
  private final String actorName;

  public TopologyManagerImpl(
      final Atomix atomix, final BrokerInfo localBroker, final ClusterCfg clusterCfg) {
    this.atomix = atomix;
    this.localBroker = localBroker;
    localBroker
        .setClusterSize(clusterCfg.getClusterSize())
        .setPartitionsCount(clusterCfg.getPartitionsCount())
        .setReplicationFactor(clusterCfg.getReplicationFactor());

    final String version = VersionUtil.getVersion();
    if (version != null && !version.isBlank()) {
      localBroker.setVersion(version);
    }

    actorName = buildActorName(localBroker.getNodeId(), "TopologyManager");
  }

  @Override
  public ActorFuture<Void> onBecomingFollower(final int partitionId, final long term) {
    return setFollower(partitionId);
  }

  @Override
  public ActorFuture<Void> onBecomingLeader(
      final int partitionId, final long term, final LogStream logStream) {
    return setLeader(term, partitionId);
  }

  @Override
  public ActorFuture<Void> onBecomingInactive(final int partitionId, final long term) {
    return setInactive(partitionId);
  }

  @Override
  public String getName() {
    return actorName;
  }

  @Override
  protected void onActorStarted() {
    // ensures that the first published event will contain the broker's info
    publishTopologyChanges();
    atomix.getMembershipService().addListener(this);
    atomix
        .getMembershipService()
        .getMembers()
        .forEach(m -> event(new ClusterMembershipEvent(Type.MEMBER_ADDED, m)));
  }

  public ActorFuture<Void> setLeader(final long term, final int partitionId) {
    return actor.call(
        () -> {
          partitionLeaders.put(partitionId, localBroker);
          localBroker.setLeaderForPartition(partitionId, term);
          publishTopologyChanges();
          notifyPartitionLeaderUpdated(partitionId, localBroker);
        });
  }

  public ActorFuture<Void> setFollower(final int partitionId) {
    return actor.call(
        () -> {
          removeIfLeader(localBroker, partitionId);
          localBroker.setFollowerForPartition(partitionId);
          publishTopologyChanges();
        });
  }

  public ActorFuture<Void> setInactive(final int partitionId) {
    return actor.call(
        () -> {
          removeIfLeader(localBroker, partitionId);
          localBroker.setInactiveForPartition(partitionId);
          publishTopologyChanges();
        });
  }

  @Override
  public void event(final ClusterMembershipEvent clusterMembershipEvent) {
    final Member eventSource = clusterMembershipEvent.subject();

    final BrokerInfo brokerInfo = readBrokerInfo(eventSource);

    if (brokerInfo != null && brokerInfo.getNodeId() != localBroker.getNodeId()) {
      actor.run(
          () -> {
            switch (clusterMembershipEvent.type()) {
              case METADATA_CHANGED:
              case MEMBER_ADDED:
                onMetadataChanged(brokerInfo);
                break;

              case MEMBER_REMOVED:
                onMemberRemoved(brokerInfo);
                break;

              case REACHABILITY_CHANGED:
              default:
                LOG.debug(
                    "Received {} from member {}, was not handled.",
                    clusterMembershipEvent.type(),
                    brokerInfo.getNodeId());
                break;
            }
          });
    }
  }

  // Remove a member from the topology
  private void onMemberRemoved(final BrokerInfo brokerInfo) {
    LOG.debug("Received member removed {} ", brokerInfo);
    brokerInfo.consumePartitions(
        partition -> removeIfLeader(brokerInfo, partition),
        (leaderPartitionId, term) -> {},
        followerPartitionId -> {},
        inactivePartitionId -> {});
  }

  private void removeIfLeader(final BrokerInfo brokerInfo, final Integer partition) {
    final BrokerInfo currentLeader = partitionLeaders.get(partition);
    if (currentLeader != null && currentLeader.getNodeId() == brokerInfo.getNodeId()) {
      partitionLeaders.remove(partition);
    }
  }

  // Update local knowledge about the partitions of remote node
  private void onMetadataChanged(final BrokerInfo brokerInfo) {
    LOG.debug(
        "Received metadata change for {}, partitions {} terms {}",
        brokerInfo.getNodeId(),
        brokerInfo.getPartitionRoles(),
        brokerInfo.getPartitionLeaderTerms());
    brokerInfo.consumePartitions(
        (leaderPartitionId, term) -> {
          if (updatePartitionLeader(brokerInfo, leaderPartitionId, term)) {
            notifyPartitionLeaderUpdated(leaderPartitionId, brokerInfo);
          }
        },
        followerPartitionId -> removeIfLeader(brokerInfo, followerPartitionId),
        inactivePartitionId -> removeIfLeader(brokerInfo, inactivePartitionId));
  }

  private boolean updatePartitionLeader(
      final BrokerInfo brokerInfo, final int leaderPartitionId, final long term) {
    final BrokerInfo currentLeader = partitionLeaders.get(leaderPartitionId);

    if (currentLeader != null) {
      final Long currentLeaderTerm = currentLeader.getPartitionLeaderTerms().get(leaderPartitionId);
      if (currentLeaderTerm == null) {
        LOG.error(
            "Could not update new leader for partition {} at term {}. Expected to have a non-null value for current leader term, but found null",
            leaderPartitionId,
            term);
        return false;
      }
      if (currentLeaderTerm >= term) {
        return false;
      }
    }
    partitionLeaders.put(leaderPartitionId, brokerInfo);
    return true;
  }

  private BrokerInfo readBrokerInfo(final Member eventSource) {
    final BrokerInfo brokerInfo = BrokerInfo.fromProperties(eventSource.properties());
    if (brokerInfo != null && !isStaticConfigValid(brokerInfo)) {
      LOG.error(
          "Static configuration of node {} differs from local node {}: "
              + "NodeId: 0 <= {} < {}, "
              + "ClusterSize: {} == {}, "
              + "PartitionsCount: {} == {}, "
              + "ReplicationFactor: {} == {}.",
          eventSource.id(),
          atomix.getMembershipService().getLocalMember().id(),
          brokerInfo.getNodeId(),
          localBroker.getClusterSize(),
          brokerInfo.getClusterSize(),
          localBroker.getClusterSize(),
          brokerInfo.getPartitionsCount(),
          localBroker.getPartitionsCount(),
          brokerInfo.getReplicationFactor(),
          localBroker.getReplicationFactor());

      return null;
    }
    return brokerInfo;
  }

  // Validate that the remote node's configuration is equal to the local node
  private boolean isStaticConfigValid(final BrokerInfo brokerInfo) {
    return brokerInfo.getNodeId() >= 0
        && brokerInfo.getNodeId() < localBroker.getClusterSize()
        && localBroker.getClusterSize() == brokerInfo.getClusterSize()
        && localBroker.getPartitionsCount() == brokerInfo.getPartitionsCount()
        && localBroker.getReplicationFactor() == brokerInfo.getReplicationFactor();
  }

  // Propagate local partition info to other nodes through Atomix member properties
  private void publishTopologyChanges() {
    final Properties memberProperties = atomix.getMembershipService().getLocalMember().properties();
    localBroker.writeIntoProperties(memberProperties);
  }

  @Override
  public void removeTopologyPartitionListener(final TopologyPartitionListener listener) {
    actor.run(() -> topologyPartitionListeners.remove(listener));
  }

  @Override
  public void addTopologyPartitionListener(final TopologyPartitionListener listener) {
    actor.run(
        () -> {
          topologyPartitionListeners.add(listener);

          // notify initially
          partitionLeaders.forEach(
              (partitionId, leader) ->
                  LogUtil.catchAndLog(
                      LOG, () -> listener.onPartitionLeaderUpdated(partitionId, leader)));
        });
  }

  private void notifyPartitionLeaderUpdated(final int partitionId, final BrokerInfo member) {
    for (final TopologyPartitionListener listener : topologyPartitionListeners) {
      LogUtil.catchAndLog(LOG, () -> listener.onPartitionLeaderUpdated(partitionId, member));
    }
  }

  public void onHealthChanged(final int partitionId, final HealthStatus status) {
    actor.run(
        () -> {
          if (status == HealthStatus.HEALTHY) {
            localBroker.setPartitionHealthy(partitionId);
          } else if (status == HealthStatus.UNHEALTHY) {
            localBroker.setPartitionUnhealthy(partitionId);
          } else if (status == HealthStatus.DEAD) {
            localBroker.setPartitionDead(partitionId);
          }
          publishTopologyChanges();
        });
  }
}
