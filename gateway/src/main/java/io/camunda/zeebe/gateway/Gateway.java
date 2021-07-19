/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.gateway;

import io.atomix.cluster.ClusterMembershipService;
import io.atomix.cluster.messaging.ClusterEventService;
import io.atomix.cluster.messaging.MessagingService;
import io.camunda.zeebe.gateway.impl.broker.BrokerClient;
import io.camunda.zeebe.gateway.impl.broker.BrokerClientImpl;
import io.camunda.zeebe.gateway.impl.configuration.GatewayCfg;
import io.camunda.zeebe.gateway.impl.configuration.NetworkCfg;
import io.camunda.zeebe.gateway.impl.configuration.SecurityCfg;
import io.camunda.zeebe.gateway.impl.job.ActivateJobsHandler;
import io.camunda.zeebe.gateway.impl.job.LongPollingActivateJobsHandler;
import io.camunda.zeebe.gateway.impl.job.RoundRobinActivateJobsHandler;
import io.camunda.zeebe.util.VersionUtil;
import io.camunda.zeebe.util.sched.ActorSchedulingService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.netty.NettyServerBuilder;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import me.dinowernli.grpc.prometheus.Configuration;
import me.dinowernli.grpc.prometheus.MonitoringServerInterceptor;
import org.slf4j.Logger;

public final class Gateway {
  private static final Logger LOG = Loggers.GATEWAY_LOGGER;
  private static final Function<GatewayCfg, ServerBuilder> DEFAULT_SERVER_BUILDER_FACTORY =
      cfg -> setNetworkConfig(cfg.getNetwork());

  private final Function<GatewayCfg, ServerBuilder> serverBuilderFactory;
  private final Function<GatewayCfg, BrokerClient> brokerClientFactory;
  private final GatewayCfg gatewayCfg;
  private final ActorSchedulingService actorSchedulingService;

  private Server server;
  private BrokerClient brokerClient;

  private volatile Status status = Status.INITIAL;

  public Gateway(
      final GatewayCfg gatewayCfg,
      final MessagingService messagingService,
      final ClusterMembershipService membershipService,
      final ClusterEventService eventService,
      final ActorSchedulingService actorSchedulingService) {
    this(
        gatewayCfg,
        cfg -> new BrokerClientImpl(cfg, messagingService, membershipService, eventService),
        DEFAULT_SERVER_BUILDER_FACTORY,
        actorSchedulingService);
  }

  public Gateway(
      final GatewayCfg gatewayCfg,
      final Function<GatewayCfg, BrokerClient> brokerClientFactory,
      final ActorSchedulingService actorSchedulingService) {
    this(gatewayCfg, brokerClientFactory, DEFAULT_SERVER_BUILDER_FACTORY, actorSchedulingService);
  }

  public Gateway(
      final GatewayCfg gatewayCfg,
      final Function<GatewayCfg, BrokerClient> brokerClientFactory,
      final Function<GatewayCfg, ServerBuilder> serverBuilderFactory,
      final ActorSchedulingService actorSchedulingService) {
    this.gatewayCfg = gatewayCfg;
    this.brokerClientFactory = brokerClientFactory;
    this.serverBuilderFactory = serverBuilderFactory;
    this.actorSchedulingService = actorSchedulingService;
  }

  public GatewayCfg getGatewayCfg() {
    return gatewayCfg;
  }

  public Status getStatus() {
    return status;
  }

  public BrokerClient getBrokerClient() {
    return brokerClient;
  }

  public void start() throws IOException {
    status = Status.STARTING;
    if (LOG.isInfoEnabled()) {
      LOG.info("Version: {}", VersionUtil.getVersion());
      LOG.info("Starting gateway with configuration {}", gatewayCfg.toJson());
    }

    brokerClient = buildBrokerClient();

    final ActivateJobsHandler activateJobsHandler;
    if (gatewayCfg.getLongPolling().isEnabled()) {
      final LongPollingActivateJobsHandler longPollingHandler =
          buildLongPollingHandler(brokerClient);
      actorSchedulingService.submitActor(longPollingHandler);
      activateJobsHandler = longPollingHandler;
    } else {
      activateJobsHandler = new RoundRobinActivateJobsHandler(brokerClient);
    }

    final EndpointManager endpointManager = new EndpointManager(brokerClient, activateJobsHandler);
    final GatewayGrpcService gatewayGrpcService = new GatewayGrpcService(endpointManager);
    final ServerBuilder<?> serverBuilder = serverBuilderFactory.apply(gatewayCfg);

    if (gatewayCfg.getMonitoring().isEnabled()) {
      final MonitoringServerInterceptor monitoringInterceptor =
          MonitoringServerInterceptor.create(Configuration.allMetrics());
      serverBuilder.addService(
          ServerInterceptors.intercept(gatewayGrpcService, monitoringInterceptor));
    } else {
      serverBuilder.addService(gatewayGrpcService);
    }

    final SecurityCfg securityCfg = gatewayCfg.getSecurity();
    if (securityCfg.isEnabled()) {
      setSecurityConfig(serverBuilder, securityCfg);
    }

    server = serverBuilder.build();

    server.start();
    status = Status.RUNNING;
  }

  private static NettyServerBuilder setNetworkConfig(final NetworkCfg cfg) {
    final Duration minKeepAliveInterval = cfg.getMinKeepAliveInterval();

    if (minKeepAliveInterval.isNegative() || minKeepAliveInterval.isZero()) {
      throw new IllegalArgumentException("Minimum keep alive interval must be positive.");
    }

    return NettyServerBuilder.forAddress(new InetSocketAddress(cfg.getHost(), cfg.getPort()))
        .permitKeepAliveTime(minKeepAliveInterval.toMillis(), TimeUnit.MILLISECONDS)
        .permitKeepAliveWithoutCalls(false);
  }

  private void setSecurityConfig(final ServerBuilder<?> serverBuilder, final SecurityCfg security) {
    if (security.getCertificateChainPath() == null) {
      throw new IllegalArgumentException(
          "Expected to find a valid path to a certificate chain but none was found. "
              + "Edit the gateway configuration file to provide one or to disable TLS.");
    }

    if (security.getPrivateKeyPath() == null) {
      throw new IllegalArgumentException(
          "Expected to find a valid path to a private key but none was found. "
              + "Edit the gateway configuration file to provide one or to disable TLS.");
    }

    final File certChain = new File(security.getCertificateChainPath());
    final File privateKey = new File(security.getPrivateKeyPath());

    if (!certChain.exists()) {
      throw new IllegalArgumentException(
          String.format(
              "Expected to find a certificate chain file at the provided location '%s' but none was found.",
              security.getCertificateChainPath()));
    }

    if (!privateKey.exists()) {
      throw new IllegalArgumentException(
          String.format(
              "Expected to find a private key file at the provided location '%s' but none was found.",
              security.getPrivateKeyPath()));
    }

    serverBuilder.useTransportSecurity(certChain, privateKey);
  }

  private BrokerClient buildBrokerClient() {
    return brokerClientFactory.apply(gatewayCfg);
  }

  private LongPollingActivateJobsHandler buildLongPollingHandler(final BrokerClient brokerClient) {
    return LongPollingActivateJobsHandler.newBuilder().setBrokerClient(brokerClient).build();
  }

  public void listenAndServe() throws IOException {
    start();
  }

  public void stop() {
    status = Status.SHUTDOWN;
    if (server != null && !server.isShutdown()) {
      server.shutdownNow();
      try {
        server.awaitTermination();
      } catch (final InterruptedException e) {
        LOG.error("Failed to await termination of gateway", e);
        Thread.currentThread().interrupt();
      } finally {
        server = null;
      }
    }

    if (brokerClient != null) {
      brokerClient.close();
      brokerClient = null;
    }
  }

  public enum Status {
    INITIAL,
    STARTING,
    RUNNING,
    SHUTDOWN
  }
}
