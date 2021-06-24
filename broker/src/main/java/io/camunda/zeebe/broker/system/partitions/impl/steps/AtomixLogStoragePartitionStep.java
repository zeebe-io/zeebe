/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.system.partitions.impl.steps;

import io.atomix.raft.RaftCommitListener;
import io.atomix.raft.storage.log.IndexedRaftLogEntry;
import io.camunda.zeebe.broker.system.partitions.PartitionContext;
import io.camunda.zeebe.broker.system.partitions.PartitionStep;
import io.camunda.zeebe.logstreams.storage.atomix.AtomixLogStorage;
import io.camunda.zeebe.util.sched.future.ActorFuture;
import io.camunda.zeebe.util.sched.future.CompletableActorFuture;
import java.util.function.Consumer;

public class AtomixLogStoragePartitionStep implements PartitionStep {
  private static final String WRONG_TERM_ERROR_MSG =
      "Expected that current term '%d' is same as raft term '%d', but was not. Failing installation of 'AtomixLogStoragePartitionStep' on partition %d.";

  @Override
  public ActorFuture<Void> open(final PartitionContext context) {
    final var openFuture = new CompletableActorFuture<Void>();
    final var server = context.getRaftPartition().getServer();

    context.setAtomixLogStorage(
        AtomixLogStorage.ofPartition(
            server::openReader,
            () -> context.getRaftPartition().getServer().getAppender(),
            (Consumer<Long> commitListener) ->
                server.addCommitListener(
                    new RaftCommitListener() {
                      @Override
                      public void onCommit(final IndexedRaftLogEntry index) {
                        if (index.isApplicationEntry()) {
                          final var applicationEntry = index.getApplicationEntry();
                          commitListener.accept(applicationEntry.highestPosition());
                        }
                      }
                    })));
    openFuture.complete(null);

    return openFuture;
  }

  @Override
  public ActorFuture<Void> close(final PartitionContext context) {
    context.setAtomixLogStorage(null);
    return CompletableActorFuture.completed(null);
  }

  @Override
  public String getName() {
    return "AtomixLogStorage";
  }
}
