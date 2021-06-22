/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.streamprocessor;

import static io.camunda.zeebe.engine.processing.streamprocessor.StreamProcessor.HEALTH_CHECK_TICK_DURATION;
import static io.camunda.zeebe.engine.processing.streamprocessor.TypedRecordProcessors.processors;
import static io.camunda.zeebe.protocol.record.intent.ProcessInstanceIntent.ELEMENT_ACTIVATED;
import static io.camunda.zeebe.protocol.record.intent.ProcessInstanceIntent.ELEMENT_ACTIVATING;
import static io.camunda.zeebe.test.util.TestUtil.waitUntil;
import static org.mockito.Mockito.mock;

import io.camunda.zeebe.engine.processing.streamprocessor.sideeffect.SideEffectProducer;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.TypedResponseWriter;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.TypedStreamWriter;
import io.camunda.zeebe.engine.state.mutable.MutableZeebeState;
import io.camunda.zeebe.engine.util.StreamProcessorRule;
import io.camunda.zeebe.protocol.impl.record.UnifiedRecordValue;
import io.camunda.zeebe.protocol.record.RecordValue;
import io.camunda.zeebe.protocol.record.RejectionType;
import io.camunda.zeebe.protocol.record.ValueType;
import io.camunda.zeebe.protocol.record.intent.Intent;
import io.camunda.zeebe.util.health.HealthStatus;
import io.camunda.zeebe.util.sched.Actor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class StreamProcessorHealthTest {

  @Rule public final StreamProcessorRule streamProcessorRule = new StreamProcessorRule();

  private StreamProcessor streamProcessor;
  private TypedStreamWriter mockedLogStreamWriter;
  private AtomicBoolean shouldFlushThrowException;
  private AtomicInteger invocation;
  private AtomicBoolean shouldFailErrorHandlingInTransaction;
  private AtomicBoolean shouldProcessingThrowException;

  @Before
  public void before() {
    invocation = new AtomicInteger();
    shouldFlushThrowException = new AtomicBoolean();
    shouldFailErrorHandlingInTransaction = new AtomicBoolean();
    shouldProcessingThrowException = new AtomicBoolean();
  }

  @After
  public void tearDown() {
    shouldFlushThrowException.set(false);
    shouldFailErrorHandlingInTransaction.set(false);
    shouldProcessingThrowException.set(false);
  }

  @Test
  public void shouldBeHealthyOnStart() {
    // when
    streamProcessor =
        streamProcessorRule.startTypedStreamProcessor(
            (processors, context) ->
                processors.onEvent(
                    ValueType.PROCESS_INSTANCE,
                    ELEMENT_ACTIVATING,
                    mock(TypedRecordProcessor.class)));

    // then
    waitUntil(() -> streamProcessor.getHealthStatus() == HealthStatus.HEALTHY);
  }

  @Test
  @Ignore
  // TODO: TEST doesnt work like this anymore
  public void shouldMarkUnhealthyWhenReprocessingRetryLoop() {
    // given
    shouldProcessingThrowException.set(true);
    final long firstPosition = streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING, 1);
    streamProcessorRule.writeProcessInstanceEventWithSource(ELEMENT_ACTIVATED, 1, firstPosition);

    waitUntil(
        () ->
            streamProcessorRule
                .events()
                .onlyProcessInstanceRecords()
                .withIntent(ELEMENT_ACTIVATED)
                .exists());

    streamProcessor = getErrorProneStreamProcessor();
    final var healthStatusCheck = HealthStatusCheck.of(streamProcessor);
    streamProcessorRule.getActorSchedulerRule().submitActor(healthStatusCheck);

    waitUntil(() -> healthStatusCheck.hasHealthStatus(HealthStatus.HEALTHY));
    waitUntil(() -> invocation.get() > 1);

    // when
    streamProcessorRule.getClock().addTime(HEALTH_CHECK_TICK_DURATION.multipliedBy(1));
    // give some time for scheduled timers to get executed
    final int retried = invocation.get();
    waitUntil(() -> retried < invocation.get());
    streamProcessorRule.getClock().addTime(HEALTH_CHECK_TICK_DURATION.multipliedBy(1));

    // then
    waitUntil(() -> healthStatusCheck.hasHealthStatus(HealthStatus.UNHEALTHY));
  }

  @Test
  public void shouldMarkUnhealthyWhenOnErrorHandlingWriteEventFails() {
    // given
    streamProcessor = getErrorProneStreamProcessor();
    final var healthStatusCheck = HealthStatusCheck.of(streamProcessor);
    streamProcessorRule.getActorSchedulerRule().submitActor(healthStatusCheck);

    waitUntil(() -> healthStatusCheck.hasHealthStatus(HealthStatus.HEALTHY));

    // when
    shouldFlushThrowException.set(true);
    streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING, 1);

    // then
    waitUntil(() -> healthStatusCheck.hasHealthStatus(HealthStatus.UNHEALTHY));
  }

  @Test
  public void shouldMarkUnhealthyWhenProcessingOnWriteEventFails() {
    // given
    streamProcessor = getErrorProneStreamProcessor();
    waitUntil(() -> streamProcessor.getHealthStatus() == HealthStatus.HEALTHY);

    // when
    shouldProcessingThrowException.set(false);
    shouldFlushThrowException.set(true);
    streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING, 1);

    // then
    waitUntil(() -> streamProcessor.getHealthStatus() == HealthStatus.UNHEALTHY);
  }

  @Test
  public void shouldMarkUnhealthyWhenExceptionErrorHandlingInTransaction() {
    // given
    shouldProcessingThrowException.set(true);
    streamProcessor = getErrorProneStreamProcessor();
    final var healthStatusCheck = HealthStatusCheck.of(streamProcessor);
    streamProcessorRule.getActorSchedulerRule().submitActor(healthStatusCheck);
    waitUntil(() -> healthStatusCheck.hasHealthStatus(HealthStatus.HEALTHY));

    // when
    // since processing fails we will write error event
    // we want to fail error even transaction
    shouldFailErrorHandlingInTransaction.set(true);
    streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING, 1);

    // then
    waitUntil(() -> healthStatusCheck.hasHealthStatus(HealthStatus.UNHEALTHY));
  }

  @Test
  public void shouldBecomeHealthyWhenErrorIsResolved() {
    // given
    shouldFlushThrowException.set(true);
    streamProcessor = getErrorProneStreamProcessor();
    waitUntil(() -> streamProcessor.getHealthStatus() == HealthStatus.HEALTHY);
    streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING, 1);
    waitUntil(() -> streamProcessor.getHealthStatus() == HealthStatus.UNHEALTHY);

    // when
    shouldFlushThrowException.set(false);

    // then
    waitUntil(() -> streamProcessor.getHealthStatus() == HealthStatus.HEALTHY);
  }

  private StreamProcessor getErrorProneStreamProcessor() {
    streamProcessor =
        streamProcessorRule.startTypedStreamProcessor(
            processingContext -> {
              final MutableZeebeState zeebeState = processingContext.getZeebeState();
              mockedLogStreamWriter = new WrappedStreamWriter();
              processingContext.logStreamWriter(mockedLogStreamWriter);
              return processors(zeebeState.getKeyGenerator(), processingContext.getWriters())
                  .onEvent(
                      ValueType.PROCESS_INSTANCE,
                      ELEMENT_ACTIVATING,
                      new TypedRecordProcessor<>() {
                        @Override
                        public void processRecord(
                            final long position,
                            final TypedRecord<UnifiedRecordValue> record,
                            final TypedResponseWriter responseWriter,
                            final TypedStreamWriter streamWriter,
                            final Consumer<SideEffectProducer> sideEffect) {

                          invocation.getAndIncrement();
                          if (shouldProcessingThrowException.get()) {
                            throw new RuntimeException("Expected failure on processing");
                          }
                        }
                      });
            });

    return streamProcessor;
  }

  private static final class HealthStatusCheck extends Actor {
    private final StreamProcessor streamProcessor;

    private HealthStatusCheck(final StreamProcessor streamProcessor) {
      this.streamProcessor = streamProcessor;
    }

    public static HealthStatusCheck of(final StreamProcessor streamProcessor) {
      return new HealthStatusCheck(streamProcessor);
    }

    public boolean hasHealthStatus(final HealthStatus healthStatus) {
      return actor
          .call(() -> streamProcessor.getHealthStatus() == healthStatus)
          .join(5, TimeUnit.SECONDS);
    }
  }

  private final class WrappedStreamWriter implements TypedStreamWriter {

    @Override
    public void appendRejection(
        final TypedRecord<? extends RecordValue> command,
        final RejectionType type,
        final String reason) {}

    @Override
    public void configureSourceContext(final long sourceRecordPosition) {}

    @Override
    public void appendFollowUpEvent(final long key, final Intent intent, final RecordValue value) {
      if (shouldFailErrorHandlingInTransaction.get()) {
        throw new RuntimeException("Expected failure on append followup event");
      }
    }

    @Override
    public void appendNewCommand(final Intent intent, final RecordValue value) {}

    @Override
    public void appendFollowUpCommand(
        final long key, final Intent intent, final RecordValue value) {}

    @Override
    public void reset() {}

    @Override
    public long flush() {
      if (shouldFlushThrowException.get()) {
        throw new RuntimeException("Expected failure on flush");
      }
      return 1L;
    }
  }
}
