/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.system.partitions.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.atomix.raft.RaftServer.Role;
import io.camunda.zeebe.broker.system.partitions.PartitionContext;
import io.camunda.zeebe.util.exception.UnrecoverableException;
import io.camunda.zeebe.util.sched.Actor;
import io.camunda.zeebe.util.sched.testing.ControlledActorSchedulerRule;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.Timeout;
import org.mockito.InOrder;

public class PartitionTransitionTest {

  private final ControlledActorSchedulerRule schedulerRule = new ControlledActorSchedulerRule();
  private final Timeout testTimeout = Timeout.seconds(30);

  @Rule public final RuleChain chain = RuleChain.outerRule(testTimeout).around(schedulerRule);

  private PartitionContext ctx;

  @Before
  public void setup() {
    ctx = mock(PartitionContext.class);
    when(ctx.getPartitionId()).thenReturn(0);
  }

  @Test
  public void shouldCloseInOppositeOrderOfOpen() {
    // given
    final TestPartitionStep firstComponent = spy(TestPartitionStep.builder().build());
    final TestPartitionStep secondComponent = spy(TestPartitionStep.builder().build());
    final PartitionTransitionImpl transition =
        new PartitionTransitionImpl(
            ctx, List.of(firstComponent, secondComponent), Collections.emptyList());

    // when
    final Actor actor =
        Actor.wrap(
            actorCtrl -> {
              transition.toLeader(1).onComplete((none, err) -> assertThat(err).isNull());
              transition.toInactive().onComplete((none, err) -> assertThat(err).isNull());
            });

    schedulerRule.submitActor(actor);
    schedulerRule.workUntilDone();

    // then
    final InOrder order = inOrder(firstComponent, secondComponent);
    order.verify(firstComponent).open(ctx);
    order.verify(secondComponent).open(ctx);
    order.verify(secondComponent).close(ctx);
    order.verify(firstComponent).close(ctx);
  }

  @Test
  public void shouldTransitionInSequence() {
    // given
    final TestPartitionStep leaderComponent = spy(TestPartitionStep.builder().build());
    final TestPartitionStep followerComponent = spy(TestPartitionStep.builder().build());
    final PartitionTransitionImpl partitionTransition =
        new PartitionTransitionImpl(ctx, List.of(leaderComponent), List.of(followerComponent));

    // when
    final Actor actor =
        Actor.wrap(
            actorCtrl -> {
              partitionTransition.toLeader(1);
              partitionTransition.toFollower(1);
            });

    schedulerRule.submitActor(actor);
    schedulerRule.workUntilDone();

    // then
    final InOrder order = inOrder(leaderComponent, followerComponent);
    order.verify(leaderComponent).open(ctx);
    order.verify(leaderComponent).close(ctx);
    order.verify(followerComponent).open(ctx);
    order.verifyNoMoreInteractions();
  }

  @Test
  public void shouldCloseAllEvenAfterFailure() {
    // given
    final TestPartitionStep succeedStep = spy(TestPartitionStep.builder().build());
    final TestPartitionStep failStep = spy(TestPartitionStep.builder().failOnClose().build());
    final PartitionTransitionImpl transition =
        new PartitionTransitionImpl(ctx, List.of(succeedStep, failStep), Collections.emptyList());

    // when
    final Actor actor =
        Actor.wrap(
            actorCtrl -> {
              transition.toLeader(1).onComplete((none, err) -> assertThat(err).isNull());
              transition.toInactive().onComplete((none, err) -> assertThat(err).isNull());
            });

    schedulerRule.submitActor(actor);
    schedulerRule.workUntilDone();

    // then
    final InOrder order = inOrder(succeedStep, failStep);
    order.verify(failStep).close(ctx);
    order.verify(succeedStep).close(ctx);
  }

  @Test
  public void shouldCloseOpenedEvenIfOpenWasInterrupted() {
    // given
    final TestPartitionStep succeedStep = spy(TestPartitionStep.builder().build());
    final TestPartitionStep failStep = spy(TestPartitionStep.builder().failOnOpen().build());
    final PartitionTransitionImpl transition =
        new PartitionTransitionImpl(ctx, List.of(succeedStep, failStep), Collections.emptyList());

    // when
    final Actor actor =
        Actor.wrap(
            actorCtrl -> {
              transition
                  .toLeader(1)
                  .onComplete((none, err) -> assertThat(err).hasMessage("expected"));
              transition.toInactive().onComplete((none, err) -> assertThat(err).isNull());
            });

    schedulerRule.submitActor(actor);
    schedulerRule.workUntilDone();

    // then
    final InOrder order = inOrder(succeedStep, failStep);
    order.verify(succeedStep).open(ctx);
    order.verify(failStep).open(ctx);
    order.verify(succeedStep).close(ctx);
  }

  @Test
  public void shouldTryToInstallEvenIfCloseFailed() {
    // given
    final TestPartitionStep leaderStep = spy(TestPartitionStep.builder().build());
    final TestPartitionStep failStep = spy(TestPartitionStep.builder().failOnClose().build());
    final TestPartitionStep followerStep = spy(TestPartitionStep.builder().build());
    final PartitionTransitionImpl transition =
        new PartitionTransitionImpl(ctx, List.of(leaderStep, failStep), List.of(followerStep));

    // when
    final Actor actor =
        Actor.wrap(
            actorCtrl -> {
              transition.toLeader(1).onComplete((none, err) -> assertThat(err).isNull());
              transition.toFollower(1).onComplete((none, err) -> assertThat(err).isNull());
            });

    schedulerRule.submitActor(actor);
    schedulerRule.workUntilDone();

    // then
    final InOrder order = inOrder(leaderStep, failStep, followerStep);
    order.verify(leaderStep).open(ctx);
    order.verify(failStep).open(ctx);
    order.verify(failStep).close(ctx);
    order.verify(leaderStep).close(ctx);
    order.verify(followerStep).open(ctx);
    order.verifyNoMoreInteractions();
  }

  @Test
  public void shouldHandleExceptionThrownByStepOpen() {
    // given
    final TestPartitionStep leaderStep = spy(TestPartitionStep.builder().build());
    final TestPartitionStep failStep =
        spy(
            TestPartitionStep.builder()
                .throwOnOpen(new UnrecoverableException("expected"))
                .build());
    final PartitionTransitionImpl transition =
        new PartitionTransitionImpl(ctx, List.of(leaderStep, failStep), List.of());

    // when
    final Actor actor =
        Actor.wrap(
            actorCtrl ->
                transition
                    .toLeader(1)
                    .onComplete(
                        (none, err) -> assertThat(err).isInstanceOf(UnrecoverableException.class)));

    schedulerRule.submitActor(actor);
    schedulerRule.workUntilDone();

    // then
    final InOrder order = inOrder(leaderStep, failStep);
    order.verify(leaderStep).open(ctx);
    order.verify(failStep).open(ctx);
    order.verify(failStep).close(ctx);
    order.verifyNoMoreInteractions();
  }

  @Test
  public void shouldHandleExceptionThrownInClose() {
    // given
    final TestPartitionStep leaderStep = spy(TestPartitionStep.builder().build());
    final TestPartitionStep failStep =
        spy(
            TestPartitionStep.builder()
                .throwOnClose(new UnrecoverableException("expected"))
                .build());
    final PartitionTransitionImpl transition =
        new PartitionTransitionImpl(ctx, List.of(leaderStep, failStep), List.of());

    // when
    final Actor actor =
        Actor.wrap(
            actorCtrl -> {
              transition.toLeader(1).onComplete((none, err) -> assertThat(err).isNull());
              transition
                  .toFollower(2)
                  .onComplete(
                      (n, err) -> assertThat(err).isInstanceOf(UnrecoverableException.class));
            });

    schedulerRule.submitActor(actor);
    schedulerRule.workUntilDone();

    // then
    final InOrder order = inOrder(leaderStep, failStep);
    order.verify(leaderStep).open(ctx);
    order.verify(failStep).open(ctx);
    order.verify(failStep).close(ctx);
    order.verify(leaderStep).close(ctx);
    order.verify(leaderStep).getName();
    order.verifyNoMoreInteractions();
  }

  @Test
  public void shouldSetTermAndRoleInContext() {
    // given
    final TestPartitionStep leaderStep = spy(TestPartitionStep.builder().build());

    final PartitionTransitionImpl transition =
        new PartitionTransitionImpl(ctx, List.of(leaderStep), List.of());

    // when
    final Actor actor =
        Actor.wrap(
            actorCtrl -> {
              transition.toLeader(3);
            });

    schedulerRule.submitActor(actor);
    schedulerRule.workUntilDone();

    // then
    verify(ctx).setCurrentRole(Role.LEADER);
    verify(ctx).setCurrentTerm(3);
  }
}
