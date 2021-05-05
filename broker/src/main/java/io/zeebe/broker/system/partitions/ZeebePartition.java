/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.broker.system.partitions;

import io.atomix.raft.RaftRoleChangeListener;
import io.atomix.raft.RaftServer.Role;
import io.zeebe.broker.Loggers;
import io.zeebe.broker.exporter.stream.ExporterDirector;
import io.zeebe.broker.system.monitoring.DiskSpaceUsageListener;
import io.zeebe.broker.system.monitoring.HealthMetrics;
import io.zeebe.engine.processing.streamprocessor.StreamProcessor;
import io.zeebe.snapshots.PersistedSnapshotStore;
import io.zeebe.util.exception.UnrecoverableException;
import io.zeebe.util.health.CriticalComponentsHealthMonitor;
import io.zeebe.util.health.FailureListener;
import io.zeebe.util.health.HealthMonitorable;
import io.zeebe.util.health.HealthStatus;
import io.zeebe.util.sched.Actor;
import io.zeebe.util.sched.future.ActorFuture;
import io.zeebe.util.sched.future.CompletableActorFuture;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.slf4j.Logger;

public final class ZeebePartition extends Actor
    implements RaftRoleChangeListener, HealthMonitorable, FailureListener, DiskSpaceUsageListener {

  private static final Logger LOG = Loggers.SYSTEM_LOGGER;
  private Role raftRole;

  private final String actorName;
  private final List<FailureListener> failureListeners;
  private final HealthMetrics healthMetrics;
  private final RaftPartitionHealth raftPartitionHealth;
  private final ZeebePartitionHealth zeebePartitionHealth;
  private long term;

  private final PartitionContext context;
  private final PartitionTransition transition;
  private CompletableActorFuture<Void> closeFuture;
  private ActorFuture<Void> currentTransitionFuture;

  public ZeebePartition(final PartitionContext context, final PartitionTransition transition) {
    this.context = context;
    this.transition = transition;

    context.setActor(actor);
    context.setDiskSpaceAvailable(true);

    actorName = buildActorName(context.getNodeId(), "ZeebePartition", context.getPartitionId());
    context.setComponentHealthMonitor(new CriticalComponentsHealthMonitor(actor, LOG));
    raftPartitionHealth =
        new RaftPartitionHealth(context.getRaftPartition(), actor, this::onRaftFailed);
    zeebePartitionHealth = new ZeebePartitionHealth(context.getPartitionId());
    healthMetrics = new HealthMetrics(context.getPartitionId());
    healthMetrics.setUnhealthy();
    failureListeners = new ArrayList<>();
  }

  /**
   * Called by atomix on role change.
   *
   * @param newRole the new role of the raft partition
   */
  @Override
  public void onNewRole(final Role newRole, final long newTerm) {
    actor.run(() -> onRoleChange(newRole, newTerm));
  }

  private void onRoleChange(final Role newRole, final long newTerm) {
    ActorFuture<Void> nextTransitionFuture = null;
    term = newTerm;
    switch (newRole) {
      case LEADER:
        if (raftRole != Role.LEADER) {
          nextTransitionFuture = leaderTransition(newTerm);
        }
        break;
      case INACTIVE:
        nextTransitionFuture = transitionToInactive();
        break;
      case PASSIVE:
      case PROMOTABLE:
      case CANDIDATE:
      case FOLLOWER:
      default:
        if (raftRole == null || raftRole == Role.LEADER) {
          nextTransitionFuture = followerTransition(newTerm);
        }
        break;
    }

    if (nextTransitionFuture != null) {
      currentTransitionFuture = nextTransitionFuture;
    }
    LOG.debug("Partition role transitioning from {} to {} in term {}", raftRole, newRole, term);
    raftRole = newRole;
  }

  private ActorFuture<Void> leaderTransition(final long newTerm) {
    final var leaderTransitionFuture = transition.toLeader(newTerm);
    leaderTransitionFuture.onComplete(
        (success, error) -> {
          if (error == null) {
            final List<ActorFuture<Void>> listenerFutures =
                context.getPartitionListeners().stream()
                    .map(
                        l ->
                            l.onBecomingLeader(
                                context.getPartitionId(), newTerm, context.getLogStream()))
                    .collect(Collectors.toList());
            actor.runOnCompletion(
                listenerFutures,
                t -> {
                  // Compare with the current term in case a new role transition happened
                  if (t != null && term == newTerm) {
                    onInstallFailure(newTerm, t);
                  }
                });
            onRecoveredInternal();
          } else {
            LOG.error("Failed to install leader partition {}", context.getPartitionId(), error);
            onInstallFailure(newTerm, error);
          }
        });
    return leaderTransitionFuture;
  }

  private ActorFuture<Void> followerTransition(final long newTerm) {
    final var followerTransitionFuture = transition.toFollower(newTerm);
    followerTransitionFuture.onComplete(
        (success, error) -> {
          if (error == null) {
            final List<ActorFuture<Void>> listenerFutures =
                context.getPartitionListeners().stream()
                    .map(l -> l.onBecomingFollower(context.getPartitionId(), newTerm))
                    .collect(Collectors.toList());
            actor.runOnCompletion(
                listenerFutures,
                t -> {
                  // Compare with the current term in case a new role transition happened
                  if (t != null && term == newTerm) {
                    onInstallFailure(newTerm, t);
                  }
                });
            onRecoveredInternal();
          } else {
            LOG.error("Failed to install follower partition {}", context.getPartitionId(), error);
            onInstallFailure(newTerm, error);
          }
        });
    return followerTransitionFuture;
  }

  private ActorFuture<Void> transitionToInactive() {
    zeebePartitionHealth.setServicesInstalled(false);
    final var inactiveTransitionFuture = transition.toInactive();
    currentTransitionFuture = inactiveTransitionFuture;
    return inactiveTransitionFuture;
  }

  private CompletableFuture<Void> onRaftFailed() {
    final CompletableFuture<Void> inactiveTransitionFuture = new CompletableFuture<>();
    actor.run(
        () -> {
          final ActorFuture<Void> transitionComplete = transitionToInactive();
          transitionComplete.onComplete(
              (v, t) -> {
                if (t != null) {
                  inactiveTransitionFuture.completeExceptionally(t);
                  return;
                }
                inactiveTransitionFuture.complete(null);
              });
        });
    return inactiveTransitionFuture;
  }

  @Override
  public String getName() {
    return actorName;
  }

  @Override
  public void onActorStarting() {
    context.getRaftPartition().addRoleChangeListener(this);
    context.getComponentHealthMonitor().addFailureListener(this);
    onRoleChange(context.getRaftPartition().getRole(), context.getRaftPartition().term());
  }

  @Override
  protected void onActorStarted() {
    context.getComponentHealthMonitor().startMonitoring();
    context
        .getComponentHealthMonitor()
        .registerComponent(raftPartitionHealth.getName(), raftPartitionHealth);
    // Add a component that keep track of health of ZeebePartition. This way
    // criticalComponentsHealthMonitor can monitor the health of ZeebePartition similar to other
    // components.
    context
        .getComponentHealthMonitor()
        .registerComponent(zeebePartitionHealth.getName(), zeebePartitionHealth);
  }

  @Override
  protected void onActorClosing() {
    transitionToInactive()
        .onComplete(
            (nothing, err) -> {
              context.getRaftPartition().removeRoleChangeListener(this);

              context.getComponentHealthMonitor().removeComponent(raftPartitionHealth.getName());
              raftPartitionHealth.close();
              closeFuture.complete(null);
            });
  }

  @Override
  public ActorFuture<Void> closeAsync() {
    if (closeFuture != null) {
      return closeFuture;
    }

    closeFuture = new CompletableActorFuture<>();

    actor.call(
        () ->
            // allows to await current transition to avoid concurrent modifications and
            // transitioning
            currentTransitionFuture.onComplete(
                (nothing, err) -> {
                  LOG.debug("Closing Zeebe Partition {}.", context.getPartitionId());
                  super.closeAsync();
                }));

    return closeFuture;
  }

  @Override
  protected void handleFailure(final Exception failure) {
    LOG.warn("Uncaught exception in {}.", actorName, failure);
    // Most probably exception happened in the middle of installing leader or follower services
    // because this actor is not doing anything else
    onInstallFailure(term, failure);
  }

  @Override
  public void onFailure() {
    actor.run(
        () -> {
          healthMetrics.setUnhealthy();
          failureListeners.forEach(FailureListener::onFailure);
        });
  }

  @Override
  public void onRecovered() {
    actor.run(
        () -> {
          healthMetrics.setHealthy();
          failureListeners.forEach(FailureListener::onRecovered);
        });
  }

  @Override
  public void onUnrecoverableFailure() {
    actor.run(this::handleUnrecoverableFailure);
  }

  private void onInstallFailure(final long term, final Throwable error) {
    if (error instanceof UnrecoverableException) {
      LOG.error(
          "Failed to install partition {} with unrecoverable failure: ",
          context.getPartitionId(),
          error);
      handleUnrecoverableFailure();
    } else {
      handleRecoverableFailure(term);
    }
  }

  private void handleRecoverableFailure(final long term) {
    zeebePartitionHealth.setServicesInstalled(false);
    context
        .getPartitionListeners()
        .forEach(l -> l.onBecomingInactive(context.getPartitionId(), term));

    if (context.getRaftPartition().getRole() == Role.LEADER) {
      LOG.info("Unexpected failure occurred, stepping down");
      context.getRaftPartition().stepDown();
    } else {
      LOG.info("Unexpected failure occurred, transitioning to inactive");
      context.getRaftPartition().goInactive();
    }
  }

  private void handleUnrecoverableFailure() {
    healthMetrics.setDead();
    zeebePartitionHealth.onUnrecoverableFailure();
    transitionToInactive();
    context.getRaftPartition().goInactive();
    failureListeners.forEach(FailureListener::onUnrecoverableFailure);
    context
        .getPartitionListeners()
        .forEach(l -> l.onBecomingInactive(context.getPartitionId(), term));
  }

  private void onRecoveredInternal() {
    zeebePartitionHealth.setServicesInstalled(true);
  }

  @Override
  public HealthStatus getHealthStatus() {
    return context.getComponentHealthMonitor().getHealthStatus();
  }

  @Override
  public void addFailureListener(final FailureListener failureListener) {
    actor.run(
        () -> {
          failureListeners.add(failureListener);
          if (getHealthStatus() == HealthStatus.HEALTHY) {
            failureListener.onRecovered();
          } else {
            failureListener.onFailure();
          }
        });
  }

  @Override
  public void onDiskSpaceNotAvailable() {
    actor.call(
        () -> {
          context.setDiskSpaceAvailable(false);
          zeebePartitionHealth.setDiskSpaceAvailable(false);
          if (context.getStreamProcessor() != null) {
            LOG.warn("Disk space usage is above threshold. Pausing stream processor.");
            context.getStreamProcessor().pauseProcessing();
          }
        });
  }

  @Override
  public void onDiskSpaceAvailable() {
    actor.call(
        () -> {
          context.setDiskSpaceAvailable(true);
          zeebePartitionHealth.setDiskSpaceAvailable(false);
          if (context.getStreamProcessor() != null && context.shouldProcess()) {
            LOG.info("Disk space usage is below threshold. Resuming stream processor.");
            context.getStreamProcessor().resumeProcessing();
          }
        });
  }

  public ActorFuture<Void> pauseProcessing() {
    final CompletableActorFuture<Void> completed = new CompletableActorFuture<>();
    actor.call(
        () -> {
          try {
            context.pauseProcessing();

            if (context.getStreamProcessor() != null && !context.shouldProcess()) {
              context.getStreamProcessor().pauseProcessing().onComplete(completed);
            } else {
              completed.complete(null);
            }
          } catch (final IOException e) {
            LOG.error("Could not pause processing state", e);
            completed.completeExceptionally(e);
          }
        });
    return completed;
  }

  public void resumeProcessing() {
    actor.call(
        () -> {
          try {
            context.resumeProcessing();
            if (context.getStreamProcessor() != null && context.shouldProcess()) {
              context.getStreamProcessor().resumeProcessing();
            }
          } catch (final IOException e) {
            LOG.error("Could not resume processing", e);
          }
        });
  }

  public int getPartitionId() {
    return context.getPartitionId();
  }

  public PersistedSnapshotStore getSnapshotStore() {
    return context.getRaftPartition().getServer().getPersistedSnapshotStore();
  }

  public void triggerSnapshot() {
    actor.call(
        () -> {
          if (context.getSnapshotDirector() != null) {
            context.getSnapshotDirector().forceSnapshot();
          }
        });
  }

  public ActorFuture<Optional<StreamProcessor>> getStreamProcessor() {
    return actor.call(() -> Optional.ofNullable(context.getStreamProcessor()));
  }

  public ActorFuture<Optional<ExporterDirector>> getExporterDirector() {
    return actor.call(() -> Optional.ofNullable(context.getExporterDirector()));
  }

  public ActorFuture<Void> pauseExporting() {
    final CompletableActorFuture<Void> completed = new CompletableActorFuture<>();
    actor.call(
        () -> {
          try {
            final var pauseStatePersisted = context.pauseExporting();

            if (context.getExporterDirector() != null && pauseStatePersisted) {
              context.getExporterDirector().pauseExporting().onComplete(completed);
            } else {
              completed.complete(null);
            }
          } catch (final IOException e) {
            LOG.error("Could not pause exporting", e);
            completed.completeExceptionally(e);
          }
        });
    return completed;
  }

  public void resumeExporting() {
    actor.call(
        () -> {
          try {
            context.resumeExporting();
            if (context.getExporterDirector() != null && context.shouldExport()) {
              context.getExporterDirector().resumeExporting();
            }
          } catch (final IOException e) {
            LOG.error("Could not resume exporting", e);
          }
        });
  }
}
