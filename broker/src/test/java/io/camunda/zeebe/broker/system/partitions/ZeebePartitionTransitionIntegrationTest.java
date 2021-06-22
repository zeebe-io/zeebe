/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.system.partitions;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.atomix.primitive.partition.PartitionId;
import io.atomix.raft.RaftServer.Role;
import io.atomix.raft.partition.RaftPartition;
import io.camunda.zeebe.broker.system.partitions.impl.PartitionTransitionImpl;
import io.camunda.zeebe.broker.system.partitions.impl.TestPartitionStep;
import io.camunda.zeebe.util.health.CriticalComponentsHealthMonitor;
import io.camunda.zeebe.util.sched.testing.ActorSchedulerRule;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

public class ZeebePartitionTransitionIntegrationTest {

  private static final List<PartitionStep> BOOTSTRAP_STEPS = List.of();
  @Rule public ActorSchedulerRule schedulerRule = new ActorSchedulerRule();

  private PartitionContext ctx;
  private PartitionTransition transition;

  @Before
  public void setup() {
    ctx = mock(PartitionContext.class);
    final TestPartitionStep firstComponent = spy(TestPartitionStep.builder().build());
    final TestPartitionStep secondComponent = spy(TestPartitionStep.builder().build());
    transition =
        spy(new PartitionTransitionImpl(ctx, List.of(firstComponent), List.of(secondComponent)));

    final RaftPartition raftPartition = mock(RaftPartition.class);
    when(raftPartition.id()).thenReturn(new PartitionId("", 0));
    when(raftPartition.getRole()).thenReturn(Role.INACTIVE);

    final CriticalComponentsHealthMonitor healthMonitor =
        mock(CriticalComponentsHealthMonitor.class);

    when(ctx.getRaftPartition()).thenReturn(raftPartition);
    when(ctx.getComponentHealthMonitor()).thenReturn(healthMonitor);
  }

  @Test
  public void shouldTransitionToAndCloseInSequence() {
    // given
    final ZeebePartition partition = new ZeebePartition(ctx, BOOTSTRAP_STEPS, transition);
    schedulerRule.submitActor(partition);
    partition.onNewRole(Role.LEADER, 1);
    partition.onNewRole(Role.FOLLOWER, 1);

    // when
    partition.closeAsync().join();

    // then
    final InOrder inOrder = Mockito.inOrder(transition);
    inOrder.verify(transition).toInactive();
    inOrder.verify(transition).toLeader(1);
    inOrder.verify(transition).toFollower(1);
    inOrder.verify(transition).toInactive();
  }
}
