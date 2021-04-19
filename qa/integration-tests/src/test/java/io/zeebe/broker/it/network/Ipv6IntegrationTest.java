/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.it.network;

import com.github.dockerjava.api.model.Network.Ipam;
import com.github.dockerjava.api.model.Network.Ipam.Config;
import io.zeebe.client.ZeebeClient;
import io.zeebe.client.ZeebeClientBuilder;
import io.zeebe.client.api.response.Topology;
import io.zeebe.containers.ZeebeBrokerContainer;
import io.zeebe.containers.ZeebeGatewayContainer;
import io.zeebe.containers.ZeebePort;
import io.zeebe.containers.ZeebeTopologyWaitStrategy;
import io.zeebe.test.util.asserts.TopologyAssert;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

@EnabledOnOs(
    value = OS.LINUX,
    disabledReason =
        "The Docker documentation says that IPv6 networking is only supported on Docker daemons running on Linux hosts. See: https://docs.docker.com/config/daemon/ipv6/")
public class Ipv6IntegrationTest {

  private static final int CLUSTER_SIZE = 3;
  private static final int PARTITION_COUNT = 1;
  private static final int REPLICATION_FACTOR = 3;
  private static final String ZEEBE_IMAGE_VERSION = "camunda/zeebe:current-test";
  private static final String NETWORK_ALIAS = Ipv6IntegrationTest.class.getName();
  private static final String BASE_PART_OF_SUBNET = "2081::aede:4844:fe00:";
  private static final String SUBNET = BASE_PART_OF_SUBNET + "0/123";
  private static final String INADDR6_ANY = "[::]";

  public final Network network =
      Network.builder()
          .createNetworkCmdModifier(
              createNetworkCmd ->
                  createNetworkCmd
                      .withIpam(new Ipam().withConfig(new Config().withSubnet(SUBNET)))
                      .withName(NETWORK_ALIAS))
          .enableIpv6(true)
          .build();

  private List<ZeebeBrokerContainer> containers;
  private List<String> initialContactPoints;
  private ZeebeGatewayContainer gateway;

  @BeforeEach
  public void setup() {
    initialContactPoints = new ArrayList<>();
    containers =
        IntStream.range(0, CLUSTER_SIZE)
            .mapToObj(i -> new ZeebeBrokerContainer(ZEEBE_IMAGE_VERSION).withNetwork(network))
            .collect(Collectors.toList());

    gateway = new ZeebeGatewayContainer(ZEEBE_IMAGE_VERSION);
    IntStream.range(0, CLUSTER_SIZE).forEach(i -> configureBrokerContainer(i, containers));
    configureGatewayContainer(gateway, initialContactPoints.get(0));
  }

  @AfterEach
  public void tearDown() {
    containers.parallelStream().forEach(GenericContainer::stop);
    network.close();
  }

  @Test
  public void shouldCommunicateOverIpv6() {
    // given
    containers.parallelStream().forEach(GenericContainer::start);
    gateway.start();

    // when
    final ZeebeClientBuilder zeebeClientBuilder =
        ZeebeClient.newClientBuilder()
            .usePlaintext()
            .gatewayAddress(gateway.getExternalGatewayAddress());
    try (final var client = zeebeClientBuilder.build()) {
      final Topology topology = client.newTopologyRequest().send().join(5, TimeUnit.SECONDS);
      // then - can find each other
      TopologyAssert.assertThat(topology).isComplete(3, 1);
    }
  }

  private void configureBrokerContainer(final int index, final List<ZeebeBrokerContainer> brokers) {
    final int clusterSize = brokers.size();
    final var broker = brokers.get(index);
    final var hostNameWithoutBraces = getIpv6AddressForIndex(index);
    final var hostName = String.format("[%s]", hostNameWithoutBraces);

    initialContactPoints.add(hostName + ":" + ZeebePort.INTERNAL.getPort());

    broker
        .withNetwork(network)
        .withNetworkAliases(NETWORK_ALIAS)
        .withCreateContainerCmdModifier(
            createContainerCmd ->
                createContainerCmd
                    .withIpv6Address(hostNameWithoutBraces)
                    .withHostName(hostNameWithoutBraces))
        .withEnv("ZEEBE_BROKER_NETWORK_MAXMESSAGESIZE", "128KB")
        .withEnv("ZEEBE_BROKER_CLUSTER_NODEID", String.valueOf(index))
        .withEnv("ZEEBE_BROKER_CLUSTER_CLUSTERSIZE", String.valueOf(clusterSize))
        .withEnv("ZEEBE_BROKER_CLUSTER_REPLICATIONFACTOR", String.valueOf(REPLICATION_FACTOR))
        .withEnv("ZEEBE_BROKER_CLUSTER_PARTITIONCOUNT", String.valueOf(PARTITION_COUNT))
        .withEnv(
            "ZEEBE_BROKER_CLUSTER_INITIALCONTACTPOINTS", String.join(",", initialContactPoints))
        .withEnv("ZEEBE_BROKER_NETWORK_ADVERTISEDHOST", hostNameWithoutBraces)
        .withEnv("ZEEBE_LOG_LEVEL", "DEBUG")
        .withEnv("ZEEBE_BROKER_NETWORK_HOST", INADDR6_ANY)
        .withEnv("ZEEBE_LOG_LEVEL", "DEBUG")
        .withEnv("ATOMIX_LOG_LEVEL", "INFO");
  }

  private void configureGatewayContainer(
      final ZeebeGatewayContainer gateway, final String initialContactPoint) {
    final String address = getIpv6AddressForIndex(CLUSTER_SIZE);
    gateway
        .withEnv("ZEEBE_GATEWAY_CLUSTER_CONTACTPOINT", initialContactPoint)
        .withTopologyCheck(
            new ZeebeTopologyWaitStrategy()
                .forBrokersCount(CLUSTER_SIZE)
                .forPartitionsCount(PARTITION_COUNT)
                .forReplicationFactor(REPLICATION_FACTOR))
        .withNetwork(network)
        .withNetworkAliases(NETWORK_ALIAS)
        .withEnv("ZEEBE_GATEWAY_NETWORK_HOST", INADDR6_ANY)
        .withEnv("ZEEBE_GATEWAY_CLUSTER_HOST", address)
        .withCreateContainerCmdModifier(
            createContainerCmd ->
                createContainerCmd.withIpv6Address(address).withHostName(address));
  }

  private String getIpv6AddressForIndex(final int index) {
    // offset the index by 2 as indexes start at 0, and :1 is the gateway, so the first address
    // should start at :2
    return String.format("%s%d", BASE_PART_OF_SUBNET, index + 2);
  }
}
