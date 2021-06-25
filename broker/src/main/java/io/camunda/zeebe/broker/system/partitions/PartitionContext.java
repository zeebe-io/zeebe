/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.system.partitions;

import io.atomix.raft.RaftServer.Role;
import io.atomix.raft.partition.RaftPartition;
import io.atomix.raft.storage.log.RaftLogReader;
import io.camunda.zeebe.broker.PartitionListener;
import io.camunda.zeebe.broker.exporter.repo.ExporterRepository;
import io.camunda.zeebe.broker.exporter.stream.ExporterDirector;
import io.camunda.zeebe.broker.exporter.stream.ExporterSatellite;
import io.camunda.zeebe.broker.logstreams.LogDeletionService;
import io.camunda.zeebe.broker.system.configuration.BrokerCfg;
import io.camunda.zeebe.broker.system.partitions.impl.AsyncSnapshotDirector;
import io.camunda.zeebe.broker.system.partitions.impl.PartitionProcessingState;
import io.camunda.zeebe.broker.system.partitions.impl.StateControllerImpl;
import io.camunda.zeebe.broker.transport.commandapi.CommandApiService;
import io.camunda.zeebe.db.ZeebeDb;
import io.camunda.zeebe.engine.processing.streamprocessor.StreamProcessor;
import io.camunda.zeebe.logstreams.log.LogStream;
import io.camunda.zeebe.logstreams.storage.atomix.AtomixLogStorage;
import io.camunda.zeebe.snapshots.SnapshotStoreSupplier;
import io.camunda.zeebe.util.health.HealthMonitor;
import io.camunda.zeebe.util.sched.ActorControl;
import io.camunda.zeebe.util.sched.ActorScheduler;
import io.camunda.zeebe.util.sched.ScheduledTimer;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class PartitionContext {

  private final int nodeId;
  private final List<PartitionListener> partitionListeners;
  private final PartitionMessagingService messagingService;
  private final ActorScheduler scheduler;
  private final BrokerCfg brokerCfg;

  private final SnapshotStoreSupplier snapshotStoreSupplier;
  private final RaftPartition raftPartition;
  private final TypedRecordProcessorsFactory typedRecordProcessorsFactory;
  private final CommandApiService commandApiService;
  private final Integer partitionId;
  private final int maxFragmentSize;
  private final ExporterRepository exporterRepository;
  private final PartitionProcessingState partitionProcessingState;

  private StreamProcessor streamProcessor;
  private LogStream logStream;
  private AtomixLogStorage atomixLogStorage;
  private long deferredCommitPosition;
  private RaftLogReader raftLogReader;
  private SnapshotReplication snapshotReplication;
  private StateControllerImpl stateController;
  private LogDeletionService logDeletionService;
  private AsyncSnapshotDirector snapshotDirector;
  private HealthMonitor criticalComponentsHealthMonitor;
  private ZeebeDb zeebeDb;
  private ActorControl actor;
  private ScheduledTimer metricsTimer;
  private ExporterDirector exporterDirector;

  private long currentTerm;
  private Role currentRole;
  private ExporterSatellite exporterSatellite;

  public PartitionContext(
      final int nodeId,
      final RaftPartition raftPartition,
      final List<PartitionListener> partitionListeners,
      final PartitionMessagingService messagingService,
      final ActorScheduler actorScheduler,
      final BrokerCfg brokerCfg,
      final CommandApiService commandApiService,
      final SnapshotStoreSupplier snapshotStoreSupplier,
      final TypedRecordProcessorsFactory typedRecordProcessorsFactory,
      final ExporterRepository exporterRepository,
      final PartitionProcessingState partitionProcessingState) {
    this.nodeId = nodeId;
    this.raftPartition = raftPartition;
    this.messagingService = messagingService;
    this.brokerCfg = brokerCfg;
    this.snapshotStoreSupplier = snapshotStoreSupplier;
    this.typedRecordProcessorsFactory = typedRecordProcessorsFactory;
    this.commandApiService = commandApiService;
    this.partitionListeners = Collections.unmodifiableList(partitionListeners);
    partitionId = raftPartition.id().id();
    scheduler = actorScheduler;
    maxFragmentSize = (int) brokerCfg.getNetwork().getMaxMessageSizeInBytes();
    this.exporterRepository = exporterRepository;
    this.partitionProcessingState = partitionProcessingState;
  }

  public ExporterDirector getExporterDirector() {
    return exporterDirector;
  }

  public void setExporterDirector(final ExporterDirector exporterDirector) {
    this.exporterDirector = exporterDirector;
  }

  public ScheduledTimer getMetricsTimer() {
    return metricsTimer;
  }

  public void setMetricsTimer(final ScheduledTimer metricsTimer) {
    this.metricsTimer = metricsTimer;
  }

  public ActorControl getActor() {
    return actor;
  }

  public void setActor(final ActorControl actor) {
    this.actor = actor;
  }

  public ZeebeDb getZeebeDb() {
    return zeebeDb;
  }

  public void setZeebeDb(final ZeebeDb zeebeDb) {
    this.zeebeDb = zeebeDb;
  }

  public HealthMonitor getComponentHealthMonitor() {
    return criticalComponentsHealthMonitor;
  }

  public void setComponentHealthMonitor(final HealthMonitor criticalComponentsHealthMonitor) {
    this.criticalComponentsHealthMonitor = criticalComponentsHealthMonitor;
  }

  public AsyncSnapshotDirector getSnapshotDirector() {
    return snapshotDirector;
  }

  public void setSnapshotDirector(final AsyncSnapshotDirector snapshotDirector) {
    this.snapshotDirector = snapshotDirector;
  }

  public LogDeletionService getLogDeletionService() {
    return logDeletionService;
  }

  public void setLogDeletionService(final LogDeletionService logDeletionService) {
    this.logDeletionService = logDeletionService;
  }

  public StateControllerImpl getSnapshotController() {
    return stateController;
  }

  public void setSnapshotController(final StateControllerImpl controller) {
    stateController = controller;
  }

  public SnapshotReplication getSnapshotReplication() {
    return snapshotReplication;
  }

  public void setSnapshotReplication(final SnapshotReplication snapshotReplication) {
    this.snapshotReplication = snapshotReplication;
  }

  public RaftLogReader getRaftLogReader() {
    return raftLogReader;
  }

  public void setRaftLogReader(final RaftLogReader raftLogReader) {
    this.raftLogReader = raftLogReader;
  }

  public long getDeferredCommitPosition() {
    return deferredCommitPosition;
  }

  public void setDeferredCommitPosition(final long deferredCommitPosition) {
    this.deferredCommitPosition = deferredCommitPosition;
  }

  public AtomixLogStorage getAtomixLogStorage() {
    return atomixLogStorage;
  }

  public void setAtomixLogStorage(final AtomixLogStorage atomixLogStorage) {
    this.atomixLogStorage = atomixLogStorage;
  }

  public StreamProcessor getStreamProcessor() {
    return streamProcessor;
  }

  public void setStreamProcessor(final StreamProcessor streamProcessor) {
    this.streamProcessor = streamProcessor;
  }

  public LogStream getLogStream() {
    return logStream;
  }

  public void setLogStream(final LogStream logStream) {
    this.logStream = logStream;
  }

  public int getNodeId() {
    return nodeId;
  }

  public int getPartitionId() {
    return partitionId;
  }

  public List<PartitionListener> getPartitionListeners() {
    return partitionListeners;
  }

  public PartitionMessagingService getMessagingService() {
    return messagingService;
  }

  public ActorScheduler getScheduler() {
    return scheduler;
  }

  public BrokerCfg getBrokerCfg() {
    return brokerCfg;
  }

  public SnapshotStoreSupplier getSnapshotStoreSupplier() {
    return snapshotStoreSupplier;
  }

  public RaftPartition getRaftPartition() {
    return raftPartition;
  }

  public TypedRecordProcessorsFactory getTypedRecordProcessorsFactory() {
    return typedRecordProcessorsFactory;
  }

  public CommandApiService getCommandApiService() {
    return commandApiService;
  }

  public int getMaxFragmentSize() {
    return maxFragmentSize;
  }

  public ExporterRepository getExporterRepository() {
    return exporterRepository;
  }

  public void setDiskSpaceAvailable(final boolean diskSpaceAvailable) {
    partitionProcessingState.setDiskSpaceAvailable(diskSpaceAvailable);
  }

  public boolean shouldProcess() {
    return partitionProcessingState.shouldProcess();
  }

  public boolean shouldExport() {
    return !partitionProcessingState.isExportingPaused();
  }

  public void pauseProcessing() throws IOException {
    partitionProcessingState.pauseProcessing();
  }

  public void resumeProcessing() throws IOException {
    partitionProcessingState.resumeProcessing();
  }

  public boolean pauseExporting() throws IOException {
    return partitionProcessingState.pauseExporting();
  }

  public boolean resumeExporting() throws IOException {
    return partitionProcessingState.resumeExporting();
  }

  public long getCurrentTerm() {
    return currentTerm;
  }

  public void setCurrentTerm(final long currentTerm) {
    this.currentTerm = currentTerm;
  }

  public Role getCurrentRole() {
    return currentRole;
  }

  public void setCurrentRole(final Role currentRole) {
    this.currentRole = currentRole;
  }
}
