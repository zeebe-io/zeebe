/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker;

import io.atomix.cluster.MemberId;
import io.atomix.cluster.messaging.ManagedMessagingService;
import io.atomix.cluster.messaging.MessagingConfig;
import io.atomix.cluster.messaging.impl.NettyMessagingService;
import io.atomix.core.Atomix;
import io.atomix.raft.partition.RaftPartition;
import io.atomix.raft.partition.RaftPartitionGroup;
import io.atomix.utils.net.Address;
import io.camunda.zeebe.broker.bootstrap.CloseProcess;
import io.camunda.zeebe.broker.bootstrap.StartProcess;
import io.camunda.zeebe.broker.clustering.atomix.AtomixFactory;
import io.camunda.zeebe.broker.clustering.topology.TopologyManagerImpl;
import io.camunda.zeebe.broker.clustering.topology.TopologyPartitionListenerImpl;
import io.camunda.zeebe.broker.engine.impl.DeploymentDistributorImpl;
import io.camunda.zeebe.broker.engine.impl.LongPollingJobNotification;
import io.camunda.zeebe.broker.engine.impl.PartitionCommandSenderImpl;
import io.camunda.zeebe.broker.engine.impl.SubscriptionApiCommandMessageHandlerService;
import io.camunda.zeebe.broker.exporter.jar.ExporterJarLoadException;
import io.camunda.zeebe.broker.exporter.repo.ExporterLoadException;
import io.camunda.zeebe.broker.exporter.repo.ExporterRepository;
import io.camunda.zeebe.broker.system.EmbeddedGatewayService;
import io.camunda.zeebe.broker.system.SystemContext;
import io.camunda.zeebe.broker.system.configuration.BrokerCfg;
import io.camunda.zeebe.broker.system.configuration.ClusterCfg;
import io.camunda.zeebe.broker.system.configuration.DataCfg;
import io.camunda.zeebe.broker.system.configuration.NetworkCfg;
import io.camunda.zeebe.broker.system.configuration.SocketBindingCfg;
import io.camunda.zeebe.broker.system.configuration.backpressure.BackpressureCfg;
import io.camunda.zeebe.broker.system.management.BrokerAdminService;
import io.camunda.zeebe.broker.system.management.BrokerAdminServiceImpl;
import io.camunda.zeebe.broker.system.management.LeaderManagementRequestHandler;
import io.camunda.zeebe.broker.system.management.deployment.PushDeploymentRequestHandler;
import io.camunda.zeebe.broker.system.monitoring.BrokerHealthCheckService;
import io.camunda.zeebe.broker.system.monitoring.DiskSpaceUsageListener;
import io.camunda.zeebe.broker.system.monitoring.DiskSpaceUsageMonitor;
import io.camunda.zeebe.broker.system.partitions.PartitionContext;
import io.camunda.zeebe.broker.system.partitions.PartitionHealthBroadcaster;
import io.camunda.zeebe.broker.system.partitions.PartitionStep;
import io.camunda.zeebe.broker.system.partitions.TypedRecordProcessorsFactory;
import io.camunda.zeebe.broker.system.partitions.ZeebePartition;
import io.camunda.zeebe.broker.system.partitions.impl.AtomixPartitionMessagingService;
import io.camunda.zeebe.broker.system.partitions.impl.PartitionProcessingState;
import io.camunda.zeebe.broker.system.partitions.impl.PartitionTransitionImpl;
import io.camunda.zeebe.broker.system.partitions.impl.steps.AtomixLogStoragePartitionStep;
import io.camunda.zeebe.broker.system.partitions.impl.steps.ExporterDirectorPartitionStep;
import io.camunda.zeebe.broker.system.partitions.impl.steps.LogDeletionPartitionStep;
import io.camunda.zeebe.broker.system.partitions.impl.steps.LogStreamPartitionStep;
import io.camunda.zeebe.broker.system.partitions.impl.steps.RaftLogReaderPartitionStep;
import io.camunda.zeebe.broker.system.partitions.impl.steps.RocksDbMetricExporterPartitionStep;
import io.camunda.zeebe.broker.system.partitions.impl.steps.SnapshotDirectorPartitionStep;
import io.camunda.zeebe.broker.system.partitions.impl.steps.StateControllerPartitionStep;
import io.camunda.zeebe.broker.system.partitions.impl.steps.StreamProcessorPartitionStep;
import io.camunda.zeebe.broker.system.partitions.impl.steps.ZeebeDbPartitionStep;
import io.camunda.zeebe.broker.transport.backpressure.PartitionAwareRequestLimiter;
import io.camunda.zeebe.broker.transport.commandapi.CommandApiService;
import io.camunda.zeebe.engine.processing.EngineProcessors;
import io.camunda.zeebe.engine.processing.message.command.SubscriptionCommandSender;
import io.camunda.zeebe.engine.processing.streamprocessor.ProcessingContext;
import io.camunda.zeebe.engine.state.mutable.MutableZeebeState;
import io.camunda.zeebe.logstreams.log.LogStream;
import io.camunda.zeebe.protocol.impl.encoding.BrokerInfo;
import io.camunda.zeebe.snapshots.SnapshotStoreSupplier;
import io.camunda.zeebe.snapshots.impl.FileBasedSnapshotStoreFactory;
import io.camunda.zeebe.transport.ServerTransport;
import io.camunda.zeebe.transport.TransportFactory;
import io.camunda.zeebe.util.LogUtil;
import io.camunda.zeebe.util.VersionUtil;
import io.camunda.zeebe.util.exception.UncheckedExecutionException;
import io.camunda.zeebe.util.sched.Actor;
import io.camunda.zeebe.util.sched.ActorControl;
import io.camunda.zeebe.util.sched.ActorScheduler;
import io.camunda.zeebe.util.sched.clock.ActorClock;
import io.netty.util.NetUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;

public final class Broker implements AutoCloseable {

  public static final Logger LOG = Loggers.SYSTEM_LOGGER;

  private static final List<PartitionStep> BOOTSTRAP_STEPS =
      List.of(
          new AtomixLogStoragePartitionStep(),
          new LogStreamPartitionStep(),
          new RaftLogReaderPartitionStep(),
          new LogDeletionPartitionStep(),
          new StateControllerPartitionStep(),
          new ZeebeDbPartitionStep());

  private static final List<PartitionStep> LEADER_STEPS =
      List.of(
          // TODO: reset dispatcher
          new StreamProcessorPartitionStep(),
          new SnapshotDirectorPartitionStep(),
          new RocksDbMetricExporterPartitionStep(),
          new ExporterDirectorPartitionStep());
  private static final List<PartitionStep> FOLLOWER_STEPS =
      List.of(
          new ZeebeDbPartitionStep(), // for simplicity; we need to recover the last snapshot from
          // Leader-To-Follower, on bootstrap we would do it twice now,
          // but this makes it still simpler to implement we dont want
          // to do it from Follower-To-Leader switch
          new StreamProcessorPartitionStep(),
          new SnapshotDirectorPartitionStep(),
          new RocksDbMetricExporterPartitionStep());

  private final SystemContext brokerContext;
  private final List<PartitionListener> partitionListeners;
  private boolean isClosed = false;
  private Atomix atomix;
  private CompletableFuture<Broker> startFuture;
  private TopologyManagerImpl topologyManager;
  private LeaderManagementRequestHandler managementRequestHandler;
  private CommandApiService commandHandler;
  private ActorScheduler scheduler;
  private CloseProcess closeProcess;
  private EmbeddedGatewayService embeddedGatewayService;
  private ServerTransport serverTransport;
  private BrokerHealthCheckService healthCheckService;
  private final List<DiskSpaceUsageListener> diskSpaceUsageListeners = new ArrayList<>();
  private final SpringBrokerBridge springBrokerBridge;
  private DiskSpaceUsageMonitor diskSpaceUsageMonitor;
  private SnapshotStoreSupplier snapshotStoreSupplier;
  private final List<ZeebePartition> partitions = new ArrayList<>();
  private BrokerAdminService brokerAdminService;

  public Broker(final SystemContext systemContext, final SpringBrokerBridge springBrokerBridge) {
    brokerContext = systemContext;
    partitionListeners = new ArrayList<>();
    this.springBrokerBridge = springBrokerBridge;
  }

  public Broker(
      final BrokerCfg cfg,
      final String basePath,
      final ActorClock clock,
      final SpringBrokerBridge springBrokerBridge) {
    this(new SystemContext(cfg, basePath, clock), springBrokerBridge);
  }

  public void addPartitionListener(final PartitionListener listener) {
    partitionListeners.add(listener);
  }

  public synchronized CompletableFuture<Broker> start() {
    if (startFuture == null) {
      logBrokerStart();

      startFuture = new CompletableFuture<>();
      LogUtil.doWithMDC(brokerContext.getDiagnosticContext(), this::internalStart);
    }
    return startFuture;
  }

  private void logBrokerStart() {
    if (LOG.isInfoEnabled()) {
      final BrokerCfg brokerCfg = getConfig();
      LOG.info("Version: {}", VersionUtil.getVersion());
      LOG.info(
          "Starting broker {} with configuration {}",
          brokerCfg.getCluster().getNodeId(),
          brokerCfg.toJson());
    }
  }

  private void internalStart() {
    final StartProcess startProcess = initStart();

    try {
      closeProcess = startProcess.start();
      startFuture.complete(this);
      healthCheckService.setBrokerStarted();
    } catch (final Exception bootStrapException) {
      final BrokerCfg brokerCfg = getConfig();
      LOG.error(
          "Failed to start broker {}!", brokerCfg.getCluster().getNodeId(), bootStrapException);

      final UncheckedExecutionException exception =
          new UncheckedExecutionException("Failed to start broker", bootStrapException);
      startFuture.completeExceptionally(exception);
      throw exception;
    }
  }

  private StartProcess initStart() {
    final BrokerCfg brokerCfg = getConfig();
    final NetworkCfg networkCfg = brokerCfg.getNetwork();

    final ClusterCfg clusterCfg = brokerCfg.getCluster();
    final BrokerInfo localBroker =
        new BrokerInfo(
            clusterCfg.getNodeId(),
            NetUtil.toSocketAddressString(networkCfg.getCommandApi().getAdvertisedAddress()));

    final StartProcess startContext = new StartProcess("Broker-" + localBroker.getNodeId());

    startContext.addStep("actor scheduler", this::actorSchedulerStep);
    startContext.addStep(
        "membership and replication protocol", () -> atomixCreateStep(brokerCfg, localBroker));
    startContext.addStep(
        "command api transport",
        () ->
            commandApiTransportStep(
                clusterCfg, brokerCfg.getNetwork().getCommandApi(), localBroker));
    startContext.addStep(
        "command api handler", () -> commandApiHandlerStep(brokerCfg, localBroker));
    startContext.addStep("subscription api", () -> subscriptionAPIStep(localBroker));

    startContext.addStep("cluster services", () -> atomix.start().join());
    startContext.addStep("topology manager", () -> topologyManagerStep(clusterCfg, localBroker));
    if (brokerCfg.getGateway().isEnable()) {
      startContext.addStep(
          "embedded gateway",
          () -> {
            embeddedGatewayService = new EmbeddedGatewayService(brokerCfg, scheduler, atomix);
            return embeddedGatewayService;
          });
    }
    startContext.addStep("monitoring services", () -> monitoringServerStep(localBroker));
    startContext.addStep("disk space monitor", () -> diskSpaceMonitorStep(brokerCfg.getData()));
    startContext.addStep(
        "leader management request handler", () -> managementRequestStep(localBroker));
    startContext.addStep(
        "zeebe partitions", () -> partitionsStep(brokerCfg, clusterCfg, localBroker));
    startContext.addStep("register diskspace usage listeners", this::addDiskSpaceUsageListeners);
    startContext.addStep("upgrade manager", this::addBrokerAdminService);

    return startContext;
  }

  private AutoCloseable addBrokerAdminService() {
    final var adminService = new BrokerAdminServiceImpl(partitions);
    scheduleActor(adminService);
    brokerAdminService = adminService;
    springBrokerBridge.registerBrokerAdminServiceSupplier(() -> brokerAdminService);
    return adminService;
  }

  private AutoCloseable actorSchedulerStep() {
    scheduler = brokerContext.getScheduler();
    scheduler.start();
    return () ->
        scheduler.stop().get(brokerContext.getStepTimeout().toMillis(), TimeUnit.MILLISECONDS);
  }

  private AutoCloseable atomixCreateStep(final BrokerCfg brokerCfg, final BrokerInfo localBroker) {
    final var snapshotStoreFactory =
        new FileBasedSnapshotStoreFactory(scheduler, localBroker.getNodeId());
    snapshotStoreSupplier = snapshotStoreFactory;
    atomix = AtomixFactory.fromConfiguration(brokerCfg, snapshotStoreFactory);

    return () ->
        atomix.stop().get(brokerContext.getStepTimeout().toMillis(), TimeUnit.MILLISECONDS);
  }

  private AutoCloseable commandApiTransportStep(
      final ClusterCfg clusterCfg,
      final SocketBindingCfg commpandApiConfig,
      final BrokerInfo localBroker) {
    final var messagingService = createMessagingService(clusterCfg, commpandApiConfig);
    messagingService.start().join();
    LOG.debug(
        "Bound command API to {}, using advertised address {} ",
        messagingService.bindingAddresses(),
        messagingService.address());

    final var transportFactory = new TransportFactory(scheduler);
    serverTransport =
        transportFactory.createServerTransport(localBroker.getNodeId(), messagingService);

    return () -> {
      serverTransport.close();
      messagingService.stop().join();
    };
  }

  private ManagedMessagingService createMessagingService(
      final ClusterCfg clusterCfg, final SocketBindingCfg socketCfg) {
    final var messagingConfig = new MessagingConfig();
    messagingConfig.setInterfaces(List.of(socketCfg.getHost()));
    messagingConfig.setPort(socketCfg.getPort());
    return new NettyMessagingService(
        clusterCfg.getClusterName(),
        Address.from(socketCfg.getAdvertisedHost(), socketCfg.getAdvertisedPort()),
        messagingConfig);
  }

  private AutoCloseable commandApiHandlerStep(
      final BrokerCfg brokerCfg, final BrokerInfo localBroker) {

    final BackpressureCfg backpressureCfg = brokerCfg.getBackpressure();
    PartitionAwareRequestLimiter limiter = PartitionAwareRequestLimiter.newNoopLimiter();
    if (backpressureCfg.isEnabled()) {
      limiter = PartitionAwareRequestLimiter.newLimiter(backpressureCfg);
    }

    commandHandler = new CommandApiService(serverTransport, localBroker, limiter);
    partitionListeners.add(commandHandler);
    scheduleActor(commandHandler);
    diskSpaceUsageListeners.add(commandHandler);
    return commandHandler;
  }

  private AutoCloseable subscriptionAPIStep(final BrokerInfo localBroker) {
    final SubscriptionApiCommandMessageHandlerService messageHandlerService =
        new SubscriptionApiCommandMessageHandlerService(localBroker, atomix);
    partitionListeners.add(messageHandlerService);
    scheduleActor(messageHandlerService);
    diskSpaceUsageListeners.add(messageHandlerService);
    return messageHandlerService;
  }

  private void addDiskSpaceUsageListeners() {
    diskSpaceUsageListeners.forEach(diskSpaceUsageMonitor::addDiskUsageListener);
  }

  private void scheduleActor(final Actor actor) {
    brokerContext
        .getScheduler()
        .submitActor(actor)
        .join(brokerContext.getStepTimeout().toSeconds(), TimeUnit.SECONDS);
  }

  private AutoCloseable topologyManagerStep(
      final ClusterCfg clusterCfg, final BrokerInfo localBroker) {
    topologyManager = new TopologyManagerImpl(atomix, localBroker, clusterCfg);
    partitionListeners.add(topologyManager);
    scheduleActor(topologyManager);
    return topologyManager;
  }

  private AutoCloseable monitoringServerStep(final BrokerInfo localBroker) {
    healthCheckService = new BrokerHealthCheckService(localBroker, atomix);
    springBrokerBridge.registerBrokerHealthCheckServiceSupplier(() -> healthCheckService);
    partitionListeners.add(healthCheckService);
    scheduleActor(healthCheckService);
    return () -> healthCheckService.close();
  }

  private AutoCloseable diskSpaceMonitorStep(final DataCfg data) {
    diskSpaceUsageMonitor = new DiskSpaceUsageMonitor(data);
    if (data.isDiskUsageMonitoringEnabled()) {
      scheduleActor(diskSpaceUsageMonitor);
      diskSpaceUsageListeners.forEach(l -> diskSpaceUsageMonitor.addDiskUsageListener(l));
      return () -> diskSpaceUsageMonitor.close();
    } else {
      LOG.info("Skipping start of disk space usage monitor, as it is disabled by configuration");
      return () -> {};
    }
  }

  private AutoCloseable managementRequestStep(final BrokerInfo localBroker) {
    managementRequestHandler = new LeaderManagementRequestHandler(localBroker, atomix);
    scheduleActor(managementRequestHandler);
    partitionListeners.add(managementRequestHandler);
    diskSpaceUsageListeners.add(managementRequestHandler);
    return managementRequestHandler;
  }

  private AutoCloseable partitionsStep(
      final BrokerCfg brokerCfg, final ClusterCfg clusterCfg, final BrokerInfo localBroker)
      throws Exception {
    final RaftPartitionGroup partitionGroup =
        (RaftPartitionGroup)
            atomix.getPartitionService().getPartitionGroup(AtomixFactory.GROUP_NAME);

    final MemberId nodeId = atomix.getMembershipService().getLocalMember().id();
    final List<RaftPartition> owningPartitions =
        partitionGroup.getPartitions().stream()
            .filter(partition -> partition.members().contains(nodeId))
            .map(RaftPartition.class::cast)
            .collect(Collectors.toList());

    final StartProcess partitionStartProcess = new StartProcess("Broker-" + nodeId + " partitions");

    for (final RaftPartition owningPartition : owningPartitions) {
      final var partitionId = owningPartition.id().id();
      partitionStartProcess.addStep(
          "partition " + partitionId,
          () -> {
            final var messagingService =
                new AtomixPartitionMessagingService(
                    atomix.getCommunicationService(),
                    atomix.getMembershipService(),
                    owningPartition.members());

            final PartitionContext context =
                new PartitionContext(
                    localBroker.getNodeId(),
                    owningPartition,
                    partitionListeners,
                    messagingService,
                    scheduler,
                    brokerCfg,
                    commandHandler,
                    snapshotStoreSupplier,
                    createFactory(topologyManager, clusterCfg, atomix, managementRequestHandler),
                    buildExporterRepository(brokerCfg),
                    new PartitionProcessingState(owningPartition));
            final PartitionTransitionImpl transitionBehavior =
                new PartitionTransitionImpl(context, LEADER_STEPS, FOLLOWER_STEPS);
            final ZeebePartition zeebePartition =
                new ZeebePartition(context, BOOTSTRAP_STEPS, transitionBehavior);
            scheduleActor(zeebePartition);
            zeebePartition.addFailureListener(
                new PartitionHealthBroadcaster(partitionId, topologyManager::onHealthChanged));
            healthCheckService.registerMonitoredPartition(
                owningPartition.id().id(), zeebePartition);
            diskSpaceUsageListeners.add(zeebePartition);
            partitions.add(zeebePartition);
            return () -> {
              healthCheckService.removeMonitoredPartition(partitionId);
              zeebePartition.close();
            };
          });
    }
    return partitionStartProcess.start();
  }

  private ExporterRepository buildExporterRepository(final BrokerCfg cfg) {
    final ExporterRepository exporterRepository = new ExporterRepository();
    final var exporterEntries = cfg.getExporters().entrySet();

    // load and validate exporters
    for (final var exporterEntry : exporterEntries) {
      final var id = exporterEntry.getKey();
      final var exporterCfg = exporterEntry.getValue();
      try {
        exporterRepository.load(id, exporterCfg);
      } catch (final ExporterLoadException | ExporterJarLoadException e) {
        throw new IllegalStateException(
            "Failed to load exporter with configuration: " + exporterCfg, e);
      }
    }

    return exporterRepository;
  }

  private TypedRecordProcessorsFactory createFactory(
      final TopologyManagerImpl topologyManager,
      final ClusterCfg clusterCfg,
      final Atomix atomix,
      final LeaderManagementRequestHandler requestHandler) {
    return (ActorControl actor,
        MutableZeebeState zeebeState,
        ProcessingContext processingContext) -> {
      final LogStream stream = processingContext.getLogStream();

      final TopologyPartitionListenerImpl partitionListener =
          new TopologyPartitionListenerImpl(actor);
      topologyManager.addTopologyPartitionListener(partitionListener);

      final DeploymentDistributorImpl deploymentDistributor =
          new DeploymentDistributorImpl(
              atomix, partitionListener, zeebeState.getDeploymentState(), actor);

      final PartitionCommandSenderImpl partitionCommandSender =
          new PartitionCommandSenderImpl(atomix, topologyManager, actor);
      final SubscriptionCommandSender subscriptionCommandSender =
          new SubscriptionCommandSender(stream.getPartitionId(), partitionCommandSender);

      final PushDeploymentRequestHandler deploymentRequestHandler =
          requestHandler.getPushDeploymentRequestHandler();

      final LongPollingJobNotification jobsAvailableNotification =
          new LongPollingJobNotification(atomix.getEventService());

      return EngineProcessors.createEngineProcessors(
          processingContext,
          clusterCfg.getPartitionsCount(),
          subscriptionCommandSender,
          deploymentDistributor,
          deploymentRequestHandler,
          jobsAvailableNotification::onJobsAvailable);
    };
  }

  public BrokerCfg getConfig() {
    return brokerContext.getBrokerConfiguration();
  }

  @Override
  public void close() {
    LogUtil.doWithMDC(
        brokerContext.getDiagnosticContext(),
        () -> {
          if (!isClosed && startFuture != null) {
            startFuture
                .thenAccept(
                    b -> {
                      closeProcess.closeReverse();
                      isClosed = true;
                      LOG.info("Broker shut down.");
                    })
                .join();
          }
        });
  }

  public EmbeddedGatewayService getEmbeddedGatewayService() {
    return embeddedGatewayService;
  }

  public Atomix getAtomix() {
    return atomix;
  }

  public DiskSpaceUsageMonitor getDiskSpaceUsageMonitor() {
    return diskSpaceUsageMonitor;
  }

  public BrokerAdminService getBrokerAdminService() {
    return brokerAdminService;
  }

  public SystemContext getBrokerContext() {
    return brokerContext;
  }
}
