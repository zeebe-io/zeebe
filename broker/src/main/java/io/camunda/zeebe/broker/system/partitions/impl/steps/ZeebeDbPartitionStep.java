/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.system.partitions.impl.steps;

import io.camunda.zeebe.broker.Loggers;
import io.camunda.zeebe.broker.system.partitions.PartitionContext;
import io.camunda.zeebe.broker.system.partitions.PartitionStep;
import io.camunda.zeebe.db.ZeebeDb;
import io.camunda.zeebe.util.sched.future.ActorFuture;
import io.camunda.zeebe.util.sched.future.CompletableActorFuture;

public class ZeebeDbPartitionStep implements PartitionStep {

  @Override
  public ActorFuture<Void> open(final PartitionContext context) {
    context
        .getSnapshotStoreSupplier()
        .getPersistedSnapshotStore(context.getRaftPartition().id().id())
        .addSnapshotListener(context.getSnapshotController());

    final ZeebeDb zeebeDb;
    try {
      context.getSnapshotController().recover();
      zeebeDb = context.getSnapshotController().openDb();
    } catch (final Exception e) {
      Loggers.SYSTEM_LOGGER.error("Failed to recover from snapshot", e);

      return CompletableActorFuture.completedExceptionally(
          new IllegalStateException(
              String.format(
                  "Unexpected error occurred while recovering snapshot controller during leader partition install for partition %d",
                  context.getPartitionId()),
              e));
    }

    context.setZeebeDb(zeebeDb);
    return CompletableActorFuture.completed(null);
  }

  @Override
  public ActorFuture<Void> close(final PartitionContext context) {
    // ZeebeDb is closed in the StateController's close()
    // TODO: currently we dont want to set the zeebe db to null - so we can use it for switch from
    // FOLLOWER TO LEADER
    //    context.setZeebeDb(null);
    return CompletableActorFuture.completed(null);
  }

  @Override
  public String getName() {
    return "ZeebeDb";
  }
}
