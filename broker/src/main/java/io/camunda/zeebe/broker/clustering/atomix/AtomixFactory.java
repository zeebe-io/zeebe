/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.clustering.atomix;

import io.atomix.cluster.ClusterConfig;
import io.atomix.cluster.Node;
import io.atomix.cluster.discovery.BootstrapDiscoveryBuilder;
import io.atomix.cluster.discovery.BootstrapDiscoveryProvider;
import io.atomix.cluster.discovery.NodeDiscoveryProvider;
import io.atomix.cluster.protocol.GroupMembershipProtocol;
import io.atomix.cluster.protocol.SwimMembershipProtocol;
import io.atomix.core.Atomix;
import io.atomix.core.AtomixBuilder;
import io.atomix.utils.net.Address;
import io.camunda.zeebe.broker.Loggers;
import io.camunda.zeebe.broker.system.configuration.BrokerCfg;
import io.camunda.zeebe.broker.system.configuration.ClusterCfg;
import io.camunda.zeebe.broker.system.configuration.DataCfg;
import io.camunda.zeebe.broker.system.configuration.MembershipCfg;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.agrona.IoUtil;
import org.slf4j.Logger;

public final class AtomixFactory {

  public static final String GROUP_NAME = "raft-partition";

  private static final Logger LOG = Loggers.CLUSTERING_LOGGER;

  private AtomixFactory() {}

  public static Atomix fromConfiguration(final BrokerCfg configuration) {
    final var clusterCfg = configuration.getCluster();
    final var nodeId = clusterCfg.getNodeId();
    final var localMemberId = Integer.toString(nodeId);
    final var networkCfg = configuration.getNetwork();

    final NodeDiscoveryProvider discoveryProvider =
        createDiscoveryProvider(clusterCfg, localMemberId);

    final MembershipCfg membershipCfg = clusterCfg.getMembership();
    final GroupMembershipProtocol membershipProtocol =
        SwimMembershipProtocol.builder()
            .withFailureTimeout(membershipCfg.getFailureTimeout())
            .withGossipInterval(membershipCfg.getGossipInterval())
            .withProbeInterval(membershipCfg.getProbeInterval())
            .withProbeTimeout(membershipCfg.getProbeTimeout())
            .withBroadcastDisputes(membershipCfg.isBroadcastDisputes())
            .withBroadcastUpdates(membershipCfg.isBroadcastUpdates())
            .withGossipFanout(membershipCfg.getGossipFanout())
            .withNotifySuspect(membershipCfg.isNotifySuspect())
            .withSuspectProbes(membershipCfg.getSuspectProbes())
            .withSyncInterval(membershipCfg.getSyncInterval())
            .build();

    final AtomixBuilder atomixBuilder =
        Atomix.builder(new ClusterConfig())
            .withClusterId(clusterCfg.getClusterName())
            .withMemberId(localMemberId)
            .withMembershipProtocol(membershipProtocol)
            .withMessagingInterface(networkCfg.getInternalApi().getHost())
            .withMessagingPort(networkCfg.getInternalApi().getPort())
            .withAddress(
                Address.from(
                    networkCfg.getInternalApi().getAdvertisedHost(),
                    networkCfg.getInternalApi().getAdvertisedPort()))
            .withMembershipProvider(discoveryProvider);

    final DataCfg dataConfiguration = configuration.getData();
    final String rootDirectory = dataConfiguration.getDirectory();
    IoUtil.ensureDirectoryExists(new File(rootDirectory), "Zeebe data directory");

    return atomixBuilder.build();
  }

  private static NodeDiscoveryProvider createDiscoveryProvider(
      final ClusterCfg clusterCfg, final String localMemberId) {
    final BootstrapDiscoveryBuilder builder = BootstrapDiscoveryProvider.builder();
    final List<String> initialContactPoints = clusterCfg.getInitialContactPoints();

    final List<Node> nodes = new ArrayList<>();
    initialContactPoints.forEach(
        contactAddress -> {
          final Node node = Node.builder().withAddress(Address.from(contactAddress)).build();
          LOG.debug("Member {} will contact node: {}", localMemberId, node.address());
          nodes.add(node);
        });
    return builder.withNodes(nodes).build();
  }
}
