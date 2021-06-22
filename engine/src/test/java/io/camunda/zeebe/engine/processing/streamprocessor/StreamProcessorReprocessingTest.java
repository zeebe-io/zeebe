/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.streamprocessor;

import static org.mockito.Mockito.timeout;

import org.mockito.verification.VerificationWithTimeout;

public final class StreamProcessorReprocessingTest {
  private static final long TIMEOUT_MILLIS = 2_000L;
  private static final VerificationWithTimeout TIMEOUT = timeout(TIMEOUT_MILLIS);
  //
  //  @Rule public final StreamProcessorRule streamProcessorRule = new StreamProcessorRule();
  //
  //  @Test
  //  public void shouldCallRecordProcessorLifecycle() {
  //    // given
  //    final long position = streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING, 1);
  //    final long secondPosition =
  //        streamProcessorRule.writeProcessInstanceEventWithSource(
  //            ProcessInstanceIntent.ELEMENT_ACTIVATED, 1, position);
  //    waitUntil(
  //        () ->
  //            streamProcessorRule
  //                .events()
  //                .onlyProcessInstanceRecords()
  //                .withIntent(ELEMENT_ACTIVATED)
  //                .exists());
  //
  //    // when
  //    final TypedRecordProcessor typedRecordProcessor = mock(TypedRecordProcessor.class);
  //    streamProcessorRule.startTypedStreamProcessor(
  //        (processors, context) ->
  //            processors
  //                .onEvent(ValueType.PROCESS_INSTANCE, ELEMENT_ACTIVATING, typedRecordProcessor)
  //                .onEvent(ValueType.PROCESS_INSTANCE, ELEMENT_ACTIVATED, typedRecordProcessor));
  //
  //    verify(typedRecordProcessor, TIMEOUT.times(1))
  //        .processRecord(eq(secondPosition), any(), any(), any(), any());
  //    streamProcessorRule.closeStreamProcessor();
  //
  //    // then
  //    final InOrder inOrder = inOrder(typedRecordProcessor);
  //    // reprocessing
  //    inOrder
  //        .verify(typedRecordProcessor, TIMEOUT.times(1))
  //        .processRecord(eq(position), any(), any(), any(), any());
  //    inOrder.verify(typedRecordProcessor, TIMEOUT.times(2)).onRecovered(any());
  //    // normal processing
  //    inOrder
  //        .verify(typedRecordProcessor, TIMEOUT.times(1))
  //        .processRecord(eq(secondPosition), any(), any(), any(), any());
  //    inOrder.verify(typedRecordProcessor, TIMEOUT.times(2)).onClose();
  //
  //    inOrder.verifyNoMoreInteractions();
  //  }
  //
  //  @Test
  //  public void shouldStopProcessingWhenPaused() throws Exception {
  //    // given - bunch of events to reprocess
  //    IntStream.range(0, 5000)
  //        .forEach(i -> streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING, i));
  //    final long sourceEvent = streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING,
  // 1);
  //    streamProcessorRule.writeProcessInstanceEventWithSource(
  //        ProcessInstanceIntent.ELEMENT_ACTIVATED, 1, sourceEvent);
  //
  //    Awaitility.await()
  //        .until(
  //            () ->
  //                streamProcessorRule
  //                    .events()
  //                    .onlyProcessInstanceRecords()
  //                    .withIntent(ELEMENT_ACTIVATED),
  //            StreamWrapper::exists);
  //
  //    final var onRecoveredLatch = new CountDownLatch(1);
  //    final var typedRecordProcessor = mock(TypedRecordProcessor.class);
  //    final var streamProcessor =
  //        streamProcessorRule.startTypedStreamProcessor(
  //            (processors, context) ->
  //                processors
  //                    .onEvent(ValueType.PROCESS_INSTANCE, ELEMENT_ACTIVATING,
  // typedRecordProcessor)
  //                    .withListener(
  //                        new StreamProcessorLifecycleAware() {
  //                          @Override
  //                          public void onRecovered(final ReadonlyProcessingContext context) {
  //                            onRecoveredLatch.countDown();
  //                          }
  //                        }));
  //
  //    // when
  //    streamProcessor.pauseProcessing();
  //    final var success = onRecoveredLatch.await(15, TimeUnit.SECONDS);
  //
  //    // then
  //    assertThat(success).isTrue();
  //    Mockito.clearInvocations(typedRecordProcessor);
  //    streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING, 0xcafe);
  //
  //    verify(typedRecordProcessor, TIMEOUT.times(0))
  //        .processRecord(anyLong(), any(), any(), any(), any());
  //    verify(typedRecordProcessor, TIMEOUT.times(0)).processRecord(any(), any(), any(), any());
  //    verify(typedRecordProcessor, TIMEOUT.times(0)).processRecord(any(), any(), any());
  //  }
  //
  //  @Test
  //  public void shouldContinueToProcessWhenResumed() throws Exception {
  //    // given - bunch of events to reprocess
  //    IntStream.range(0, 5000)
  //        .forEach(i -> streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING, i));
  //    final long sourceEvent = streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING,
  // 1);
  //    streamProcessorRule.writeProcessInstanceEventWithSource(
  //        ProcessInstanceIntent.ELEMENT_ACTIVATED, 1, sourceEvent);
  //
  //    Awaitility.await()
  //        .until(
  //            () ->
  //                streamProcessorRule
  //                    .events()
  //                    .onlyProcessInstanceRecords()
  //                    .withIntent(ELEMENT_ACTIVATED),
  //            StreamWrapper::exists);
  //
  //    // when
  //    final var countDownLatch = new CountDownLatch(1);
  //    final var typedRecordProcessor = mock(TypedRecordProcessor.class);
  //    final var streamProcessor =
  //        streamProcessorRule.startTypedStreamProcessor(
  //            (processors, context) ->
  //                processors
  //                    .onEvent(ValueType.PROCESS_INSTANCE, ELEMENT_ACTIVATING,
  // typedRecordProcessor)
  //                    .withListener(
  //                        new StreamProcessorLifecycleAware() {
  //                          @Override
  //                          public void onRecovered(final ReadonlyProcessingContext context) {
  //                            countDownLatch.countDown();
  //                          }
  //                        }));
  //    streamProcessor.pauseProcessing();
  //    streamProcessor.resumeProcessing();
  //    final var success = countDownLatch.await(15, TimeUnit.SECONDS);
  //
  //    // then
  //    assertThat(success).isTrue();
  //    Mockito.clearInvocations(typedRecordProcessor);
  //    streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING, 0xcafe);
  //
  //    verify(typedRecordProcessor, TIMEOUT.times(1))
  //        .processRecord(anyLong(), any(), any(), any(), any());
  //  }
  //
  //  @Test
  //  public void shouldCallOnPausedAfterOnRecovered() {
  //    // given - bunch of events to reprocess
  //    IntStream.range(0, 5000)
  //        .forEach(i -> streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING, i));
  //    final long sourceEvent = streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING,
  // 1);
  //    streamProcessorRule.writeProcessInstanceEventWithSource(
  //        ProcessInstanceIntent.ELEMENT_ACTIVATED, 1, sourceEvent);
  //
  //    Awaitility.await()
  //        .until(
  //            () ->
  //                streamProcessorRule
  //                    .events()
  //                    .onlyProcessInstanceRecords()
  //                    .withIntent(ELEMENT_ACTIVATED),
  //            StreamWrapper::exists);
  //
  //    // when
  //    final var lifecycleAware = mock(StreamProcessorLifecycleAware.class);
  //    final var streamProcessor =
  //        streamProcessorRule.startTypedStreamProcessor(
  //            (processors, context) -> processors.withListener(lifecycleAware));
  //    streamProcessor.pauseProcessing();
  //    streamProcessor.resumeProcessing();
  //
  //    // then
  //    final InOrder inOrder = inOrder(lifecycleAware);
  //    // reprocessing
  //    inOrder.verify(lifecycleAware, TIMEOUT.times(1)).onRecovered(any());
  //    inOrder.verify(lifecycleAware, TIMEOUT.times(1)).onPaused();
  //    inOrder.verify(lifecycleAware, TIMEOUT.times(1)).onResumed();
  //    inOrder.verifyNoMoreInteractions();
  //  }
  //
  //  @Test
  //  public void shouldCallOnPausedBeforeOnResumedNoMatterWhenResumedWasCalled() {
  //    // given - bunch of events to reprocess
  //    IntStream.range(0, 5000)
  //        .forEach(i -> streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING, i));
  //    final long sourceEvent = streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING,
  // 1);
  //    streamProcessorRule.writeProcessInstanceEventWithSource(
  //        ProcessInstanceIntent.ELEMENT_ACTIVATED, 1, sourceEvent);
  //
  //    Awaitility.await()
  //        .until(
  //            () ->
  //                streamProcessorRule
  //                    .events()
  //                    .onlyProcessInstanceRecords()
  //                    .withIntent(ELEMENT_ACTIVATED),
  //            StreamWrapper::exists);
  //
  //    // when
  //    final var lifecycleAware = mock(StreamProcessorLifecycleAware.class);
  //    final var streamProcessor =
  //        streamProcessorRule.startTypedStreamProcessor(
  //            (processors, context) -> processors.withListener(lifecycleAware));
  //    streamProcessor.resumeProcessing();
  //    streamProcessor.pauseProcessing();
  //    streamProcessor.resumeProcessing();
  //
  //    // then
  //    final InOrder inOrder = inOrder(lifecycleAware);
  //    // reprocessing
  //    inOrder.verify(lifecycleAware, TIMEOUT.times(1)).onRecovered(any());
  //    inOrder.verify(lifecycleAware, TIMEOUT.times(1)).onPaused();
  //    inOrder.verify(lifecycleAware, TIMEOUT.times(1)).onResumed();
  //    inOrder.verifyNoMoreInteractions();
  //  }
  //
  //  @Test
  //  public void shouldReprocessUntilLastSource() {
  //    // given
  //    final long firstEvent = streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING,
  // 1);
  //    final long secondEvent = streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING,
  // 1);
  //    final long lastSourceEvent =
  //        streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING, 1);
  //    final long normalProcessingPosition =
  //        streamProcessorRule.writeProcessInstanceEventWithSource(
  //            ProcessInstanceIntent.ELEMENT_ACTIVATED, 1, lastSourceEvent);
  //    waitUntil(
  //        () ->
  //            streamProcessorRule
  //                .events()
  //                .onlyProcessInstanceRecords()
  //                .withIntent(ELEMENT_ACTIVATED)
  //                .exists());
  //
  //    // when
  //    final TypedRecordProcessor typedRecordProcessor = mock(TypedRecordProcessor.class);
  //    streamProcessorRule.startTypedStreamProcessor(
  //        (processors, context) ->
  //            processors
  //                .onEvent(ValueType.PROCESS_INSTANCE, ELEMENT_ACTIVATING, typedRecordProcessor)
  //                .onEvent(ValueType.PROCESS_INSTANCE, ELEMENT_ACTIVATED, typedRecordProcessor));
  //
  //    verify(typedRecordProcessor, TIMEOUT.times(1))
  //        .processRecord(eq(normalProcessingPosition), any(), any(), any(), any());
  //    streamProcessorRule.closeStreamProcessor();
  //
  //    // then
  //    final InOrder inOrder = inOrder(typedRecordProcessor);
  //    // reprocessing
  //    inOrder
  //        .verify(typedRecordProcessor, TIMEOUT.times(1))
  //        .processRecord(eq(firstEvent), any(), any(), any(), any());
  //    inOrder
  //        .verify(typedRecordProcessor, TIMEOUT.times(1))
  //        .processRecord(eq(secondEvent), any(), any(), any(), any());
  //    inOrder
  //        .verify(typedRecordProcessor, TIMEOUT.times(1))
  //        .processRecord(eq(lastSourceEvent), any(), any(), any(), any());
  //    inOrder.verify(typedRecordProcessor, TIMEOUT.times(2)).onRecovered(any());
  //    // normal processing
  //    inOrder
  //        .verify(typedRecordProcessor, TIMEOUT.times(1))
  //        .processRecord(eq(normalProcessingPosition), any(), any(), any(), any());
  //    inOrder.verify(typedRecordProcessor, TIMEOUT.times(2)).onClose();
  //
  //    inOrder.verifyNoMoreInteractions();
  //  }
  //
  //  @Test
  //  public void shouldNotReprocessWithoutSourcePosition() {
  //    // given
  //    final long position = streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING, 1);
  //    final long secondPosition =
  //        streamProcessorRule.writeProcessInstanceEvent(ProcessInstanceIntent.ELEMENT_ACTIVATED,
  // 1);
  //    waitUntil(
  //        () ->
  //            streamProcessorRule
  //                .events()
  //                .onlyProcessInstanceRecords()
  //                .withIntent(ELEMENT_ACTIVATED)
  //                .exists());
  //
  //    // when
  //    final TypedRecordProcessor typedRecordProcessor = mock(TypedRecordProcessor.class);
  //    streamProcessorRule.startTypedStreamProcessor(
  //        (processors, context) ->
  //            processors
  //                .onEvent(ValueType.PROCESS_INSTANCE, ELEMENT_ACTIVATING, typedRecordProcessor)
  //                .onEvent(ValueType.PROCESS_INSTANCE, ELEMENT_ACTIVATED, typedRecordProcessor));
  //
  //    verify(typedRecordProcessor, TIMEOUT.times(1))
  //        .processRecord(eq(secondPosition), any(), any(), any(), any());
  //    streamProcessorRule.closeStreamProcessor();
  //
  //    // then
  //    final InOrder inOrder = inOrder(typedRecordProcessor);
  //    // no reprocessing
  //    inOrder.verify(typedRecordProcessor, TIMEOUT.times(2)).onRecovered(any());
  //    // normal processing
  //    inOrder
  //        .verify(typedRecordProcessor, TIMEOUT.times(1))
  //        .processRecord(eq(position), any(), any(), any(), any());
  //    inOrder
  //        .verify(typedRecordProcessor, TIMEOUT.times(1))
  //        .processRecord(eq(secondPosition), any(), any(), any(), any());
  //    inOrder.verify(typedRecordProcessor, TIMEOUT.times(2)).onClose();
  //
  //    inOrder.verifyNoMoreInteractions();
  //  }
  //
  //  @Test
  //  public void shouldRetryProcessingRecordOnException() {
  //    // given
  //    final long firstPosition = streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING,
  // 1);
  //    streamProcessorRule.writeProcessInstanceEventWithSource(ELEMENT_ACTIVATED, 1,
  // firstPosition);
  //
  //    waitUntil(
  //        () ->
  //            streamProcessorRule
  //                .events()
  //                .onlyProcessInstanceRecords()
  //                .withIntent(ELEMENT_ACTIVATED)
  //                .exists());
  //
  //    // when
  //    final TypedRecordProcessor<?> typedRecordProcessor = mock(TypedRecordProcessor.class);
  //    final AtomicInteger count = new AtomicInteger(0);
  //    doAnswer(
  //            (invocationOnMock -> {
  //              if (count.getAndIncrement() == 0) {
  //                throw new RuntimeException("recoverable");
  //              }
  //              return null;
  //            }))
  //        .when(typedRecordProcessor)
  //        .processRecord(anyLong(), any(), any(), any(), any());
  //    streamProcessorRule.startTypedStreamProcessor(
  //        (processors, context) ->
  //            processors.onEvent(
  //                ValueType.PROCESS_INSTANCE, ELEMENT_ACTIVATING, typedRecordProcessor));
  //
  //    // then
  //    final InOrder inOrder = inOrder(typedRecordProcessor);
  //    inOrder
  //        .verify(typedRecordProcessor, TIMEOUT.times(2))
  //        .processRecord(eq(firstPosition), any(), any(), any(), any());
  //    inOrder.verify(typedRecordProcessor, TIMEOUT.times(1)).onRecovered(any());
  //
  //    inOrder.verifyNoMoreInteractions();
  //  }
  //
  //  @Test
  //  public void shouldIgnoreRecordWhenNoProcessorExistForThisType() {
  //    // given
  //    final long firstPosition = streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATED,
  // 1);
  //    final long secondPosition =
  //        streamProcessorRule.writeProcessInstanceEventWithSource(
  //            ProcessInstanceIntent.ELEMENT_ACTIVATING, 1, firstPosition);
  //
  //    // when
  //    final TypedRecordProcessor<?> typedRecordProcessor = mock(TypedRecordProcessor.class);
  //    streamProcessorRule.startTypedStreamProcessor(
  //        (processors, context) ->
  //            processors.onEvent(
  //                ValueType.PROCESS_INSTANCE, ELEMENT_ACTIVATING, typedRecordProcessor));
  //
  //    // then
  //    final InOrder inOrder = inOrder(typedRecordProcessor);
  //    inOrder
  //        .verify(typedRecordProcessor, never())
  //        .processRecord(eq(firstPosition), any(), any(), any(), any());
  //    inOrder.verify(typedRecordProcessor, TIMEOUT.times(1)).onRecovered(any());
  //    inOrder
  //        .verify(typedRecordProcessor, TIMEOUT.times(1))
  //        .processRecord(eq(secondPosition), any(), any(), any(), any());
  //
  //    inOrder.verifyNoMoreInteractions();
  //  }
  //
  //  @Test
  //  public void shouldNotWriteFollowUpEvent() throws Exception {
  //    // given
  //    final long firstPosition = streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING,
  // 1);
  //    streamProcessorRule.writeProcessInstanceEventWithSource(ELEMENT_ACTIVATED, 1,
  // firstPosition);
  //
  //    waitUntil(
  //        () ->
  //            streamProcessorRule
  //                .events()
  //                .onlyProcessInstanceRecords()
  //                .withIntent(ELEMENT_ACTIVATED)
  //                .exists());
  //
  //    // when
  //    final CountDownLatch processLatch = new CountDownLatch(1);
  //    streamProcessorRule.startTypedStreamProcessor(
  //        (processors, context) ->
  //            processors
  //                .onEvent(
  //                    ValueType.PROCESS_INSTANCE,
  //                    ELEMENT_ACTIVATING,
  //                    new TypedRecordProcessor<>() {
  //                      @Override
  //                      public void processRecord(
  //                          final long position,
  //                          final TypedRecord<UnifiedRecordValue> record,
  //                          final TypedResponseWriter responseWriter,
  //                          final TypedStreamWriter streamWriter,
  //                          final Consumer<SideEffectProducer> sideEffect) {
  //                        streamWriter.appendFollowUpEvent(
  //                            record.getKey(),
  //                            ProcessInstanceIntent.ELEMENT_ACTIVATED,
  //                            record.getValue());
  //                      }
  //                    })
  //                .onEvent(
  //                    ValueType.PROCESS_INSTANCE,
  //                    ELEMENT_ACTIVATED,
  //                    new TypedRecordProcessor<UnifiedRecordValue>() {
  //                      @Override
  //                      public void processRecord(
  //                          final long position,
  //                          final TypedRecord<UnifiedRecordValue> record,
  //                          final TypedResponseWriter responseWriter,
  //                          final TypedStreamWriter streamWriter,
  //                          final Consumer<SideEffectProducer> sideEffect) {
  //                        processLatch.countDown();
  //                      }
  //                    }));
  //
  //    // then
  //    processLatch.await();
  //
  //    final long eventCount =
  //        streamProcessorRule
  //            .events()
  //            .onlyProcessInstanceRecords()
  //            .withIntent(ELEMENT_ACTIVATED)
  //            .count();
  //    assertThat(eventCount).isEqualTo(1);
  //  }
  //
  //  @Test
  //  public void shouldStartAfterLastProcessedEventInSnapshot() throws Exception {
  //    // given
  //    final CountDownLatch onProcessedListenerLatch = new CountDownLatch(2);
  //    streamProcessorRule.startTypedStreamProcessor(
  //        (processors, context) ->
  //            processors.onEvent(
  //                ValueType.PROCESS_INSTANCE,
  //                ELEMENT_ACTIVATING,
  //                new TypedRecordProcessor<UnifiedRecordValue>() {
  //                  @Override
  //                  public void processRecord(
  //                      final long position,
  //                      final TypedRecord<UnifiedRecordValue> record,
  //                      final TypedResponseWriter responseWriter,
  //                      final TypedStreamWriter streamWriter,
  //                      final Consumer<SideEffectProducer> sideEffect) {}
  //                }),
  //        (t) -> onProcessedListenerLatch.countDown());
  //
  //    streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING);
  //    streamProcessorRule.writeProcessInstanceEvent(
  //        ELEMENT_ACTIVATING); // should be processed and included in the snapshot
  //    onProcessedListenerLatch.await();
  //    streamProcessorRule.snapshot();
  //    streamProcessorRule.closeStreamProcessor();
  //
  //    // when
  //    // The processor restarts with a snapshot that was the state of the processor before it
  //    // was closed.
  //    final List<Long> processedPositions = new ArrayList<>();
  //    final CountDownLatch newProcessLatch = new CountDownLatch(1);
  //    streamProcessorRule.startTypedStreamProcessor(
  //        (processors, context) ->
  //            processors.onEvent(
  //                ValueType.PROCESS_INSTANCE,
  //                ELEMENT_ACTIVATING,
  //                new TypedRecordProcessor<UnifiedRecordValue>() {
  //                  @Override
  //                  public void processRecord(
  //                      final long position,
  //                      final TypedRecord<UnifiedRecordValue> record,
  //                      final TypedResponseWriter responseWriter,
  //                      final TypedStreamWriter streamWriter,
  //                      final Consumer<SideEffectProducer> sideEffect) {
  //                    processedPositions.add(position);
  //                    newProcessLatch.countDown();
  //                  }
  //                }));
  //    final long position = streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING);
  //
  //    // then
  //    newProcessLatch.await();
  //
  //    assertThat(processedPositions).containsExactly(position);
  //  }
  //
  //  @Test
  //  public void shouldNotReprocessEventAtLastProcessedEvent() throws Exception {
  //    // given
  //    final CountDownLatch onProcessedListenerLatch = new CountDownLatch(2);
  //    streamProcessorRule.startTypedStreamProcessor(
  //        (processors, context) ->
  //            processors.onEvent(
  //                ValueType.PROCESS_INSTANCE,
  //                ELEMENT_ACTIVATING,
  //                new TypedRecordProcessor<UnifiedRecordValue>() {
  //                  @Override
  //                  public void processRecord(
  //                      final long position,
  //                      final TypedRecord<UnifiedRecordValue> record,
  //                      final TypedResponseWriter responseWriter,
  //                      final TypedStreamWriter streamWriter,
  //                      final Consumer<SideEffectProducer> sideEffect) {}
  //                }),
  //        t -> onProcessedListenerLatch.countDown());
  //
  //    streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING, 1);
  //    final long snapshotPosition =
  //        streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING, 1);
  //    onProcessedListenerLatch.await();
  //    streamProcessorRule.snapshot();
  //    streamProcessorRule.closeStreamProcessor();
  //    final long lastSourceEvent =
  //        streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING, 1);
  //    final long lastEvent =
  //        streamProcessorRule.writeProcessInstanceEventWithSource(
  //            ProcessInstanceIntent.ELEMENT_ACTIVATING, 1, lastSourceEvent);
  //
  //    // when
  //    final List<Long> processedPositions = new ArrayList<>();
  //    final CountDownLatch newProcessLatch = new CountDownLatch(2);
  //    streamProcessorRule.startTypedStreamProcessor(
  //        (processors, context) ->
  //            processors.onEvent(
  //                ValueType.PROCESS_INSTANCE,
  //                ELEMENT_ACTIVATING,
  //                new TypedRecordProcessor<UnifiedRecordValue>() {
  //                  @Override
  //                  public void processRecord(
  //                      final long position,
  //                      final TypedRecord<UnifiedRecordValue> record,
  //                      final TypedResponseWriter responseWriter,
  //                      final TypedStreamWriter streamWriter,
  //                      final Consumer<SideEffectProducer> sideEffect) {
  //                    processedPositions.add(position);
  //                    newProcessLatch.countDown();
  //                  }
  //                }));
  //
  //    // then
  //    newProcessLatch.await();
  //
  //    assertThat(processedPositions).doesNotContain(snapshotPosition);
  //    assertThat(processedPositions).endsWith(lastSourceEvent, lastEvent);
  //  }
  //
  //  @Test
  //  public void shouldUpdateLastProcessedPositionAfterReprocessing() throws Exception {
  //    // given
  //    final long firstPosition = streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING,
  // 1);
  //    streamProcessorRule.writeProcessInstanceEventWithSource(ELEMENT_ACTIVATED, 1,
  // firstPosition);
  //
  //    waitUntil(
  //        () ->
  //            streamProcessorRule
  //                .events()
  //                .onlyProcessInstanceRecords()
  //                .withIntent(ELEMENT_ACTIVATED)
  //                .exists());
  //
  //    // when
  //    final CountDownLatch recoveredLatch = new CountDownLatch(1);
  //    final var streamProcessor =
  //        streamProcessorRule.startTypedStreamProcessor(
  //            (processors, context) ->
  //                processors.withListener(
  //                    new StreamProcessorLifecycleAware() {
  //                      @Override
  //                      public void onRecovered(final ReadonlyProcessingContext context) {
  //                        recoveredLatch.countDown();
  //                      }
  //                    }));
  //
  //    // then
  //    recoveredLatch.await();
  //
  //    assertThat(streamProcessor.getLastProcessedPositionAsync().get()).isEqualTo(firstPosition);
  //  }
  //
  //  @Test
  //  public void shouldUpdateLastProcessedEventWhenSnapshot() throws Exception {
  //    // given
  //    final CountDownLatch onProcessedListenerLatch = new CountDownLatch(2);
  //    streamProcessorRule.startTypedStreamProcessor(
  //        (processors, context) ->
  //            processors.onEvent(
  //                ValueType.PROCESS_INSTANCE,
  //                ELEMENT_ACTIVATING,
  //                new TypedRecordProcessor<UnifiedRecordValue>() {
  //                  @Override
  //                  public void processRecord(
  //                      final long position,
  //                      final TypedRecord<UnifiedRecordValue> record,
  //                      final TypedResponseWriter responseWriter,
  //                      final TypedStreamWriter streamWriter,
  //                      final Consumer<SideEffectProducer> sideEffect) {}
  //                }),
  //        (t) -> onProcessedListenerLatch.countDown());
  //
  //    streamProcessorRule.writeProcessInstanceEvent(ELEMENT_ACTIVATING);
  //    final var snapshotPosition =
  //        streamProcessorRule.writeProcessInstanceEvent(
  //            ELEMENT_ACTIVATING); // should be processed and included in the snapshot
  //    onProcessedListenerLatch.await();
  //
  //    streamProcessorRule.snapshot();
  //    streamProcessorRule.closeStreamProcessor();
  //
  //    // when
  //    // The processor restarts with a snapshot that was the state of the processor before it
  //    // was closed.
  //    final var recoveredLatch = new CountDownLatch(1);
  //    final var streamProcessor =
  //        streamProcessorRule.startTypedStreamProcessor(
  //            (processors, context) ->
  //                processors.withListener(
  //                    new StreamProcessorLifecycleAware() {
  //                      @Override
  //                      public void onRecovered(final ReadonlyProcessingContext context) {
  //                        recoveredLatch.countDown();
  //                      }
  //                    }));
  //
  //    // then
  //    recoveredLatch.await();
  //
  //
  // assertThat(streamProcessor.getLastProcessedPositionAsync().get()).isEqualTo(snapshotPosition);
  //  }
}
