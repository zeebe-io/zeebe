/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.system.partitions;

import io.atomix.raft.RaftRoleChangeListener;
import io.atomix.raft.RaftServer.Role;
import io.camunda.zeebe.broker.Loggers;
import io.camunda.zeebe.broker.exporter.stream.ExporterDirector;
import io.camunda.zeebe.broker.system.monitoring.DiskSpaceUsageListener;
import io.camunda.zeebe.broker.system.monitoring.HealthMetrics;
import io.camunda.zeebe.engine.processing.streamprocessor.StreamProcessor;
import io.camunda.zeebe.snapshots.PersistedSnapshotStore;
import io.camunda.zeebe.util.exception.UnrecoverableException;
import io.camunda.zeebe.util.health.CriticalComponentsHealthMonitor;
import io.camunda.zeebe.util.health.FailureListener;
import io.camunda.zeebe.util.health.HealthMonitorable;
import io.camunda.zeebe.util.health.HealthStatus;
import io.camunda.zeebe.util.sched.Actor;
import io.camunda.zeebe.util.sched.future.ActorFuture;
import io.camunda.zeebe.util.sched.future.CompletableActorFuture;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;

public final class ZeebePartition extends Actor
    implements RaftRoleChangeListener, HealthMonitorable, FailureListener, DiskSpaceUsageListener {

  private static final Logger LOG = Loggers.SYSTEM_LOGGER;
  private Role raftRole;

  private final String actorName;
  private final List<FailureListener> failureListeners;
  private final HealthMetrics healthMetrics;
  private final ZeebePartitionHealth zeebePartitionHealth;

  private final PartitionContext context;
  private final PartitionTransition transition;
  private CompletableActorFuture<Void> closeFuture;
  private ActorFuture<Void> currentTransitionFuture;
  private final List<PartitionStep> bootstrapSteps;

  public ZeebePartition(
      final PartitionContext context,
      final List<PartitionStep> bootstrapSteps,
      final PartitionTransition transition) {
    this.context = context;
    this.bootstrapSteps = bootstrapSteps;
    this.transition = transition;

    context.setActor(actor);
    context.setDiskSpaceAvailable(true);

    actorName = buildActorName(context.getNodeId(), "ZeebePartition", context.getPartitionId());
    context.setComponentHealthMonitor(new CriticalComponentsHealthMonitor(actor, LOG));
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
    LOG.debug("Partition role transitioning from {} to {} in term {}", raftRole, newRole, newTerm);
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
                  if (t != null) {
                    onInstallFailure(t);
                  }
                });
            onRecoveredInternal();
          } else {
            LOG.error("Failed to install leader partition {}", context.getPartitionId(), error);
            onInstallFailure(error);
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
                  if (t != null) {
                    onInstallFailure(t);
                  }
                });
            onRecoveredInternal();
          } else {
            LOG.error("Failed to install follower partition {}", context.getPartitionId(), error);
            onInstallFailure(error);
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

  @Override
  public String getName() {
    return actorName;
  }

  @Override
  public void onActorStarting() {
    context.getRaftPartition().addRoleChangeListener(this);
    context.getComponentHealthMonitor().addFailureListener(this);

    // boot strap
    final var bootstrapFuture = new CompletableActorFuture<Void>();
    installBootstrapSteps(bootstrapFuture, new ArrayList<>(bootstrapSteps));

    bootstrapFuture.onComplete(
        (v, e) -> {
          if (e == null) {
            onRoleChange(context.getRaftPartition().getRole(), context.getRaftPartition().term());
          } else {
            // todo maybe to something else?!
            actor.fail();
          }
        });
  }

  @Override
  protected void onActorStarted() {
    context.getComponentHealthMonitor().startMonitoring();
    context
        .getComponentHealthMonitor()
        .registerComponent(context.getRaftPartition().name(), context.getRaftPartition());
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

              context
                  .getComponentHealthMonitor()
                  .removeComponent(context.getRaftPartition().name());
              closeFuture.complete(null);
            });
  }

  @Override
  protected void onActorCloseRequested() {
    LOG.debug("Closing ZeebePartition {}", context.getPartitionId());
    context.getComponentHealthMonitor().removeComponent(zeebePartitionHealth.getName());
  }

  @Override
  public ActorFuture<Void> closeAsync() {
    if (closeFuture != null) {
      return closeFuture;
    }

    closeFuture = new CompletableActorFuture<>();

    actor.run(
        () ->
            // allows to await current transition to avoid concurrent modifications and
            // transitioning
            currentTransitionFuture.onComplete(
                (nothing, err) -> {
                  final var partitionSteps = new ArrayList<PartitionStep>(bootstrapSteps);
                  Collections.reverse(partitionSteps);
                  final var future = new CompletableActorFuture<Void>();
                  closeBootstrapSteps(future, partitionSteps);

                  future.onComplete((v, e) -> super.closeAsync());
                }));

    return closeFuture;
  }

  @Override
  protected void handleFailure(final Exception failure) {
    LOG.warn("Uncaught exception in {}.", actorName, failure);
    // Most probably exception happened in the middle of installing leader or follower services
    // because this actor is not doing anything else
    onInstallFailure(failure);
  }

  private void installBootstrapSteps(
      final CompletableActorFuture<Void> future, final List<PartitionStep> steps) {
    if (steps.isEmpty()) {
      LOG.info("Partition {} first bootstrap phase complete!", context.getPartitionId());
      future.complete(null);
      return;
    }

    final PartitionStep step = steps.remove(0);
    try {
      step.open(context)
          .onComplete(
              (value, err) -> {
                if (err != null) {
                  LOG.error("Expected to open step '{}' but failed with", step.getName(), err);
                  //                  tryCloseStep(step);
                  future.completeExceptionally(err);
                } else {
                  //                  openedSteps.add(step);
                  installBootstrapSteps(future, steps);
                }
              });
    } catch (final Exception e) {
      LOG.error("Expected to open step '{}' but failed with", step.getName(), e);
      //      tryCloseStep(step);
      future.completeExceptionally(e);
    }
  }

  private void closeBootstrapSteps(
      final CompletableActorFuture<Void> future, final List<PartitionStep> steps) {
    if (steps.isEmpty()) {
      LOG.info("Partition {} closing phase complete!", context.getPartitionId());
      future.complete(null);
      return;
    }

    final PartitionStep step = steps.remove(0);
    try {
      step.close(context)
          .onComplete(
              (value, err) -> {
                if (err != null) {
                  LOG.error("Expected to close step '{}' but failed with", step.getName(), err);
                  // continue
                } else {
                  closeBootstrapSteps(future, steps);
                }
              });
    } catch (final Exception e) {
      LOG.error("Expected to open step '{}' but failed with", step.getName(), e);
      future.completeExceptionally(e);
    }
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

  private void onInstallFailure(final Throwable error) {
    if (error instanceof UnrecoverableException) {
      LOG.error(
          "Failed to install partition {} (role {}, term {}) with unrecoverable failure: ",
          context.getPartitionId(),
          context.getCurrentRole(),
          context.getCurrentTerm(),
          error);
      handleUnrecoverableFailure();
    } else {
      handleRecoverableFailure();
    }
  }

  private void handleRecoverableFailure() {
    zeebePartitionHealth.setServicesInstalled(false);
    context
        .getPartitionListeners()
        .forEach(l -> l.onBecomingInactive(context.getPartitionId(), context.getCurrentTerm()));

    // If RaftPartition has already transition to a new role in a new term, we can ignore this
    // failure. The transition for the higher term will be already enqueued and services will be
    // installed for the new role.
    if (context.getCurrentRole() == Role.LEADER
        && context.getCurrentTerm() == context.getRaftPartition().term()) {
      LOG.info(
          "Unexpected failure occurred in partition {} (role {}, term {}), stepping down",
          context.getPartitionId(),
          context.getCurrentRole(),
          context.getCurrentTerm());
      context.getRaftPartition().stepDown();
    } else if (context.getCurrentRole() == Role.FOLLOWER) {
      LOG.info(
          "Unexpected failure occurred in partition {} (role {}, term {}), transitioning to inactive",
          context.getPartitionId(),
          context.getCurrentRole(),
          context.getCurrentTerm());
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
        .forEach(l -> l.onBecomingInactive(context.getPartitionId(), context.getCurrentTerm()));
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
  public void removeFailureListener(final FailureListener failureListener) {
    actor.run(() -> failureListeners.remove(failureListener));
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
