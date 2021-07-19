/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.gateway;

import static java.lang.Runtime.getRuntime;

import io.atomix.cluster.AtomixCluster;
import io.atomix.cluster.discovery.BootstrapDiscoveryProvider;
import io.atomix.cluster.protocol.GroupMembershipProtocol;
import io.atomix.cluster.protocol.SwimMembershipProtocol;
import io.atomix.utils.net.Address;
import io.camunda.zeebe.gateway.impl.SpringGatewayBridge;
import io.camunda.zeebe.gateway.impl.broker.BrokerClient;
import io.camunda.zeebe.gateway.impl.broker.BrokerClientImpl;
import io.camunda.zeebe.gateway.impl.broker.cluster.BrokerTopologyManager;
import io.camunda.zeebe.gateway.impl.configuration.ClusterCfg;
import io.camunda.zeebe.gateway.impl.configuration.GatewayCfg;
import io.camunda.zeebe.gateway.impl.configuration.MembershipCfg;
import io.camunda.zeebe.util.VersionUtil;
import io.camunda.zeebe.util.sched.ActorScheduler;
import java.io.IOException;
import java.util.Optional;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

public class StandaloneGateway {
  private static final Logger LOG = Loggers.GATEWAY_LOGGER;
  private final AtomixCluster atomixCluster;
  private final Gateway gateway;
  private final ActorScheduler actorScheduler;

  public StandaloneGateway(
      final GatewayCfg gatewayCfg, final SpringGatewayBridge springGatewayBridge) {
    atomixCluster = createAtomixCluster(gatewayCfg.getCluster());
    actorScheduler = createActorScheduler(gatewayCfg);
    final Function<GatewayCfg, BrokerClient> brokerClientFactory =
        cfg ->
            new BrokerClientImpl(
                cfg,
                atomixCluster.getMessagingService(),
                atomixCluster.getMembershipService(),
                atomixCluster.getEventService(),
                actorScheduler,
                false);
    gateway = new Gateway(gatewayCfg, brokerClientFactory, actorScheduler);

    springGatewayBridge.registerGatewayStatusSupplier(gateway::getStatus);
    springGatewayBridge.registerClusterStateSupplier(
        () ->
            Optional.ofNullable(gateway.getBrokerClient())
                .map(BrokerClient::getTopologyManager)
                .map(BrokerTopologyManager::getTopology));
  }

  private AtomixCluster createAtomixCluster(final ClusterCfg clusterCfg) {
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

    final var atomix =
        AtomixCluster.builder()
            .withMemberId(clusterCfg.getMemberId())
            .withAddress(Address.from(clusterCfg.getHost(), clusterCfg.getPort()))
            .withClusterId(clusterCfg.getClusterName())
            .withMembershipProvider(
                BootstrapDiscoveryProvider.builder()
                    .withNodes(Address.from(clusterCfg.getContactPoint()))
                    .build())
            .withMembershipProtocol(membershipProtocol)
            .build();

    atomix.start();
    return atomix;
  }

  private ActorScheduler createActorScheduler(final GatewayCfg configuration) {
    final ActorScheduler actorScheduler =
        ActorScheduler.newActorScheduler()
            .setCpuBoundActorThreadCount(configuration.getThreads().getManagementThreads())
            .setIoBoundActorThreadCount(0)
            .setSchedulerName("gateway-scheduler")
            .build();

    actorScheduler.start();

    return actorScheduler;
  }

  public void run() throws IOException, InterruptedException {
    gateway.listenAndServe();
  }

  public void stop() {
    atomixCluster.stop();
    actorScheduler.stop();
  }

  public static void main(final String[] args) throws Exception {
    System.setProperty("spring.banner.location", "classpath:/assets/zeebe_gateway_banner.txt");

    SpringApplication.run(Launcher.class, args);
  }

  @SpringBootApplication(exclude = {ElasticsearchRestClientAutoConfiguration.class})
  @ComponentScan({"io.camunda.zeebe.gateway", "io.camunda.zeebe.shared", "io.camunda.zeebe.util"})
  public static class Launcher implements CommandLineRunner {

    @Autowired GatewayCfg configuration;
    @Autowired SpringGatewayBridge springGatewayBridge;

    @Override
    public void run(final String... args) throws Exception {
      final GatewayCfg gatewayCfg = configuration;
      gatewayCfg.init();

      if (LOG.isInfoEnabled()) {
        LOG.info("Version: {}", VersionUtil.getVersion());
        LOG.info("Starting standalone gateway with configuration {}", gatewayCfg.toJson());
      }

      final StandaloneGateway gateway = new StandaloneGateway(gatewayCfg, springGatewayBridge);
      gateway.run();

      getRuntime()
          .addShutdownHook(
              new Thread("Gateway close thread") {
                @Override
                public void run() {
                  try {
                    gateway.stop();
                  } finally {
                    LogManager.shutdown();
                  }
                }
              });
    }
  }
}
