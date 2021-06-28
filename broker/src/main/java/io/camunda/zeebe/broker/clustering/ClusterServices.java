/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.clustering;

import io.atomix.cluster.ClusterMembershipService;
import io.atomix.cluster.messaging.ClusterCommunicationService;
import io.atomix.cluster.messaging.ClusterEventService;
import io.atomix.cluster.messaging.MessagingService;
import io.atomix.core.Atomix;
import java.util.concurrent.CompletableFuture;

public final class ClusterServices {

  private final Atomix atomix;

  public ClusterServices(final Atomix atomix) {
    this.atomix = atomix;
  }

  public CompletableFuture<Void> start() {
    return atomix.start();
  }

  public CompletableFuture<Void> stop() {
    return atomix.stop();
  }

  public MessagingService getMessagingService() {
    return atomix.getMessagingService();
  }

  public ClusterMembershipService getMembershipService() {
    return atomix.getMembershipService();
  }

  public ClusterEventService getEventService() {
    return atomix.getEventService();
  }

  public ClusterCommunicationService getCommunicationService() {
    return atomix.getCommunicationService();
  }
}
