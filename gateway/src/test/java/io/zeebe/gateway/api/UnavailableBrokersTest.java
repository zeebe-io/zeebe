/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.atomix.cluster.AtomixCluster;
import io.grpc.Status.Code;
import io.netty.util.NetUtil;
import io.zeebe.client.ZeebeClient;
import io.zeebe.client.api.ZeebeFuture;
import io.zeebe.client.api.command.ClientStatusException;
import io.zeebe.client.api.command.FinalCommandStep;
import io.zeebe.gateway.Gateway;
import io.zeebe.gateway.impl.configuration.GatewayCfg;
import io.zeebe.gateway.impl.configuration.NetworkCfg;
import io.zeebe.test.util.asserts.grpc.ClientStatusExceptionAssert;
import io.zeebe.test.util.socket.SocketUtil;
import io.zeebe.util.sched.ActorScheduler;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Execution(ExecutionMode.CONCURRENT)
class UnavailableBrokersTest {
  static Gateway gateway;
  static AtomixCluster cluster;
  static ActorScheduler actorScheduler;
  static ZeebeClient client;

  @BeforeAll
  static void setUp() throws IOException {
    final NetworkCfg networkCfg = new NetworkCfg().setPort(SocketUtil.getNextAddress().getPort());
    final GatewayCfg config = new GatewayCfg().setNetwork(networkCfg);
    config.init(InetAddress.getLocalHost().getHostName());

    cluster = AtomixCluster.builder().build();
    cluster.start();

    actorScheduler = ActorScheduler.newActorScheduler().build();
    actorScheduler.start();

    gateway = new Gateway(new GatewayCfg().setNetwork(networkCfg), cluster, actorScheduler);
    gateway.start();

    final String gatewayAddress = NetUtil.toSocketAddressString(networkCfg.toSocketAddress());
    client = ZeebeClient.newClientBuilder().gatewayAddress(gatewayAddress).usePlaintext().build();
  }

  @AfterAll
  static void tearDown() {
    client.close();
    gateway.stop();
    actorScheduler.stop();
    cluster.stop();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unavailableTestCases")
  void shouldReturnUnavailableOnMissingTopology(
      final String testName, final FinalCommandStep<?> command) {
    // when
    // setting a lower timeout than the time we wait on the future ensures we see a result from the
    // gateway and not simply our future timing out
    final ZeebeFuture<?> result = command.requestTimeout(Duration.ofSeconds(5)).send();

    // then
    assertThatCode(() -> result.join(10, TimeUnit.SECONDS))
        .isInstanceOf(ClientStatusException.class)
        .asInstanceOf(ClientStatusExceptionAssert.assertFactory())
        .hasStatusSatisfying(s -> assertThat(s.getCode()).isEqualTo(Code.UNAVAILABLE));
  }

  /**
   * Returns a list of test cases consisting primarily of commands which should return unavailable
   * if the gateway has no topology.
   */
  static Stream<Object[]> unavailableTestCases() {
    return Stream.of(
            client.newCreateInstanceCommand().bpmnProcessId("process").latestVersion(),
            client.newPublishMessageCommand().messageName("message").correlationKey("key"))
        .map(command -> new Object[] {command.getClass().getSimpleName(), command});
  }
}
