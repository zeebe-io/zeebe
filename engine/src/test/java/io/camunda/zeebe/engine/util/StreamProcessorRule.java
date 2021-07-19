/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.util;

import static io.camunda.zeebe.engine.util.StreamProcessingComposite.getLogName;

import io.camunda.zeebe.db.ZeebeDbFactory;
import io.camunda.zeebe.engine.processing.streamprocessor.StreamProcessor;
import io.camunda.zeebe.engine.processing.streamprocessor.TypedRecord;
import io.camunda.zeebe.engine.processing.streamprocessor.TypedRecordProcessorFactory;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.CommandResponseWriter;
import io.camunda.zeebe.engine.state.DefaultZeebeDbFactory;
import io.camunda.zeebe.engine.state.EventApplier;
import io.camunda.zeebe.engine.state.mutable.MutableZeebeState;
import io.camunda.zeebe.engine.util.StreamProcessingComposite.StreamProcessorTestFactory;
import io.camunda.zeebe.engine.util.TestStreams.FluentLogWriter;
import io.camunda.zeebe.logstreams.log.LogStreamRecordWriter;
import io.camunda.zeebe.logstreams.util.SynchronousLogStream;
import io.camunda.zeebe.msgpack.UnpackedObject;
import io.camunda.zeebe.protocol.record.RecordType;
import io.camunda.zeebe.protocol.record.intent.Intent;
import io.camunda.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.camunda.zeebe.test.util.AutoCloseableRule;
import io.camunda.zeebe.util.FileUtil;
import io.camunda.zeebe.util.allocation.DirectBufferAllocator;
import io.camunda.zeebe.util.sched.clock.ControlledActorClock;
import io.camunda.zeebe.util.sched.testing.ActorSchedulerRule;
import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.junit.rules.ExternalResource;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StreamProcessorRule implements TestRule {

  private static final Logger LOG = LoggerFactory.getLogger("io.camunda.zeebe.broker.test");

  private static final int PARTITION_ID = 0;

  // environment
  private final TemporaryFolder tempFolder;
  private final AutoCloseableRule closeables = new AutoCloseableRule();
  private final ControlledActorClock clock = new ControlledActorClock();
  private final ActorSchedulerRule actorSchedulerRule = new ActorSchedulerRule(clock);
  private final ZeebeDbFactory zeebeDbFactory;
  private final SetupRule rule;
  private final int startPartitionId;
  private final int partitionCount;
  private final RuleChain chain;
  private TestStreams streams;
  private StreamProcessingComposite streamProcessingComposite;
  private ListLogStorage sharedStorage = null;

  public StreamProcessorRule() {
    this(new TemporaryFolder());
  }

  public StreamProcessorRule(final TemporaryFolder temporaryFolder) {
    this(PARTITION_ID, temporaryFolder);
  }

  public StreamProcessorRule(final int partitionId) {
    this(partitionId, 1, DefaultZeebeDbFactory.defaultFactory(), new TemporaryFolder());
  }

  public StreamProcessorRule(final int partitionId, final TemporaryFolder temporaryFolder) {
    this(partitionId, 1, DefaultZeebeDbFactory.defaultFactory(), temporaryFolder);
  }

  public StreamProcessorRule(
      final int startPartitionId,
      final int partitionCount,
      final ZeebeDbFactory dbFactory,
      final ListLogStorage listLogStorage) {
    this(startPartitionId, partitionCount, dbFactory, new TemporaryFolder());
    sharedStorage = listLogStorage;
  }

  public StreamProcessorRule(
      final int startPartitionId,
      final int partitionCount,
      final ZeebeDbFactory dbFactory,
      final TemporaryFolder temporaryFolder) {
    this.startPartitionId = startPartitionId;
    this.partitionCount = partitionCount;

    rule = new SetupRule(startPartitionId, partitionCount);

    tempFolder = temporaryFolder;
    zeebeDbFactory = dbFactory;
    chain =
        RuleChain.outerRule(tempFolder)
            .around(actorSchedulerRule)
            .around(new CleanUpRule(tempFolder::getRoot))
            .around(closeables)
            .around(rule);
  }

  public ActorSchedulerRule getActorSchedulerRule() {
    return actorSchedulerRule;
  }

  @Override
  public Statement apply(final Statement base, final Description description) {
    return chain.apply(base, description);
  }

  public StreamProcessorRule withEventApplierFactory(
      final Function<MutableZeebeState, EventApplier> eventApplierFactory) {
    streams.withEventApplierFactory(eventApplierFactory);
    return this;
  }

  public LogStreamRecordWriter getLogStreamRecordWriter(final int partitionId) {
    return streamProcessingComposite.getLogStreamRecordWriter(partitionId);
  }

  public StreamProcessor startTypedStreamProcessor(final StreamProcessorTestFactory factory) {
    return streamProcessingComposite.startTypedStreamProcessor(factory, r -> {});
  }

  public StreamProcessor startTypedStreamProcessor(
      final StreamProcessorTestFactory factory,
      final Consumer<TypedRecord<?>> onProcessedListener) {
    return streamProcessingComposite.startTypedStreamProcessor(factory, onProcessedListener);
  }

  public StreamProcessor startTypedStreamProcessor(final TypedRecordProcessorFactory factory) {
    return startTypedStreamProcessor(startPartitionId, factory);
  }

  public StreamProcessor startTypedStreamProcessorNotAwaitOpening(
      final StreamProcessorTestFactory factory) {
    return streamProcessingComposite.startTypedStreamProcessorNotAwaitOpening(factory, r -> {});
  }

  public StreamProcessor startTypedStreamProcessorNotAwaitOpening(
      final TypedRecordProcessorFactory factory) {
    return streamProcessingComposite.startTypedStreamProcessorNotAwaitOpening(
        startPartitionId, factory);
  }

  public StreamProcessor startTypedStreamProcessor(
      final int partitionId, final TypedRecordProcessorFactory factory) {
    return streamProcessingComposite.startTypedStreamProcessor(partitionId, factory);
  }

  public void pauseProcessing(final int partitionId) {
    streamProcessingComposite.pauseProcessing(partitionId);
  }

  public void resumeProcessing(final int partitionId) {
    streamProcessingComposite.resumeProcessing(partitionId);
  }

  public void closeStreamProcessor(final int partitionId) {
    streamProcessingComposite.closeStreamProcessor(partitionId);
  }

  public void closeStreamProcessor() {
    closeStreamProcessor(startPartitionId);
  }

  public StreamProcessor getStreamProcessor(final int partitionId) {
    return streamProcessingComposite.getStreamProcessor(partitionId);
  }

  public CommandResponseWriter getCommandResponseWriter() {
    return streams.getMockedResponseWriter();
  }

  public Consumer<TypedRecord<?>> getProcessedListener() {
    return streams.getMockedOnProcessedListener();
  }

  public ControlledActorClock getClock() {
    return clock;
  }

  public MutableZeebeState getZeebeState() {
    return streamProcessingComposite.getZeebeState();
  }

  public long getLastSuccessfulProcessedRecordPosition() {
    return streamProcessingComposite.getLastSuccessfulProcessedRecordPosition();
  }

  public long getLastWrittenPosition(final int partitionId) {
    return streams.getLastWrittenPosition(getLogName(partitionId));
  }

  public RecordStream events() {
    return new RecordStream(streams.events(getLogName(startPartitionId)));
  }

  public void printAllRecords() {
    int partitionId = startPartitionId;
    for (int i = 0; i < partitionCount; i++) {
      final SynchronousLogStream logStream = streams.getLogStream(getLogName(partitionId++));
      LogStreamPrinter.printRecords(logStream);
    }
  }

  public long writeProcessInstanceEvent(final ProcessInstanceIntent intent) {
    return writeProcessInstanceEvent(intent, 1);
  }

  public long writeProcessInstanceEventWithSource(
      final ProcessInstanceIntent intent, final int instanceKey, final long sourceEventPosition) {
    return streamProcessingComposite.writeProcessInstanceEventWithSource(
        intent, instanceKey, sourceEventPosition);
  }

  public long writeProcessInstanceEvent(final ProcessInstanceIntent intent, final int instanceKey) {
    return streamProcessingComposite.writeProcessInstanceEvent(intent, instanceKey);
  }

  public long writeEvent(final long key, final Intent intent, final UnpackedObject value) {
    return streamProcessingComposite.writeEvent(key, intent, value);
  }

  public long writeEvent(final Intent intent, final UnpackedObject value) {
    return streamProcessingComposite.writeEvent(intent, value);
  }

  public long writeBatch(final RecordToWrite... recordToWrites) {
    return streamProcessingComposite.writeBatch(recordToWrites);
  }

  public long writeCommandOnPartition(
      final int partition, final Intent intent, final UnpackedObject value) {
    return streamProcessingComposite.writeCommandOnPartition(partition, intent, value);
  }

  public long writeCommandOnPartition(
      final int partition, final long key, final Intent intent, final UnpackedObject value) {
    return streamProcessingComposite.writeCommandOnPartition(partition, key, intent, value);
  }

  public long writeCommand(final long key, final Intent intent, final UnpackedObject value) {
    return streamProcessingComposite.writeCommand(key, intent, value);
  }

  public long writeCommand(final Intent intent, final UnpackedObject value) {
    return streamProcessingComposite.writeCommand(intent, value);
  }

  public long writeCommand(
      final int requestStreamId,
      final long requestId,
      final Intent intent,
      final UnpackedObject value) {
    return streamProcessingComposite.writeCommand(requestStreamId, requestId, intent, value);
  }

  public long writeCommandRejection(final Intent intent, final UnpackedObject value) {
    return streamProcessingComposite.writeCommandRejection(intent, value);
  }

  public long writeEvent(
      final Intent intent,
      final UnpackedObject value,
      final UnaryOperator<FluentLogWriter> writer) {
    return writeRecord(intent, value, w -> writer.apply(w.recordType(RecordType.EVENT)));
  }

  public long writeCommandRejection(
      final Intent intent,
      final UnpackedObject value,
      final UnaryOperator<FluentLogWriter> writer) {
    return writeRecord(
        intent, value, w -> writer.apply(w.recordType(RecordType.COMMAND_REJECTION)));
  }

  private long writeRecord(
      final Intent intent,
      final UnpackedObject value,
      final UnaryOperator<FluentLogWriter> writer) {
    final var recordWriter =
        streams
            .newRecord(getLogName(startPartitionId))
            .recordType(RecordType.EVENT)
            .intent(intent)
            .event(value);
    return writer.apply(recordWriter).write();
  }

  public void snapshot() {
    final var partitionId = startPartitionId;
    streamProcessingComposite.snapshot(partitionId);
  }

  private class SetupRule extends ExternalResource {

    private final int startPartitionId;
    private final int partitionCount;

    SetupRule(final int startPartitionId, final int partitionCount) {
      this.startPartitionId = startPartitionId;
      this.partitionCount = partitionCount;
    }

    @Override
    protected void before() {
      streams = new TestStreams(tempFolder, closeables, actorSchedulerRule.get());

      int partitionId = startPartitionId;
      for (int i = 0; i < partitionCount; i++) {
        if (sharedStorage != null) {
          streams.createLogStream(getLogName(partitionId), partitionId++, sharedStorage);
        } else {
          streams.createLogStream(getLogName(partitionId), partitionId++);
        }
      }

      streamProcessingComposite =
          new StreamProcessingComposite(streams, startPartitionId, zeebeDbFactory);
    }

    @Override
    protected void after() {
      streams = null;
      streamProcessingComposite = null;
    }
  }

  private class CleanUpRule extends ExternalResource {

    private File root;
    private final Supplier<File> rootSupplier;

    CleanUpRule(final Supplier<File> rootSupplier) {
      this.rootSupplier = rootSupplier;
    }

    @Override
    protected void before() {
      root = rootSupplier.get();
    }

    @Override
    protected void after() {
      try {
        LOG.debug("Clean up test files on path {}", root);
        FileUtil.deleteFolder(root.toPath());

        final long allocatedMemoryInKb = DirectBufferAllocator.getAllocatedMemoryInKb();
        if (allocatedMemoryInKb > 0) {
          LOG.warn(
              "There are still allocated direct buffers of a total size of {}kB.",
              allocatedMemoryInKb);
        }
      } catch (final IOException e) {
        LOG.error("Error on deleting root test folder", e);
      }
    }
  }
}
