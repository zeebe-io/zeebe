/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.exporter.stream;

import io.camunda.zeebe.broker.exporter.repo.ExporterDescriptor;
import io.camunda.zeebe.broker.system.partitions.PartitionMessagingService;
import io.camunda.zeebe.db.ZeebeDb;
import io.camunda.zeebe.logstreams.log.LogStream;
import java.util.Collection;

public final class ExporterDirectorContext {

  private int id;
  private String name;
  private LogStream logStream;
  private Collection<ExporterDescriptor> descriptors;
  private ZeebeDb zeebeDb;
  private PartitionMessagingService partitionMessagingService;
  private ExporterMode exporterMode = ExporterMode.ACTIVE; // per default we export records

  public int getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public LogStream getLogStream() {
    return logStream;
  }

  public Collection<ExporterDescriptor> getDescriptors() {
    return descriptors;
  }

  public ZeebeDb getZeebeDb() {
    return zeebeDb;
  }

  public PartitionMessagingService getPartitionMessagingService() {
    return partitionMessagingService;
  }

  public ExporterMode getExporterMode() {
    return exporterMode;
  }

  public ExporterDirectorContext id(final int id) {
    this.id = id;
    return this;
  }

  public ExporterDirectorContext name(final String name) {
    this.name = name;
    return this;
  }

  public ExporterDirectorContext logStream(final LogStream logStream) {
    this.logStream = logStream;
    return this;
  }

  public ExporterDirectorContext descriptors(final Collection<ExporterDescriptor> descriptors) {
    this.descriptors = descriptors;
    return this;
  }

  public ExporterDirectorContext zeebeDb(final ZeebeDb zeebeDb) {
    this.zeebeDb = zeebeDb;
    return this;
  }

  public ExporterDirectorContext partitionMessagingService(
      final PartitionMessagingService messagingService) {
    partitionMessagingService = messagingService;
    return this;
  }

  public ExporterDirectorContext exporterMode(final ExporterMode exporterMode) {
    this.exporterMode = exporterMode;
    return this;
  }

  public enum ExporterMode {
    /**
     * ACTIVE, means it is actively running the exporting and distributes the exporter positions to
     * the followers. This mode is used on the leader side.
     */
    ACTIVE, // default, used on Leaders
    /**
     * PASSIVE, means it is not actively exporting records. It is consuming the distributed exporter
     * positions and stores them in the state. This mode is used on the follower side.
     */
    PASSIVE
  }
}
