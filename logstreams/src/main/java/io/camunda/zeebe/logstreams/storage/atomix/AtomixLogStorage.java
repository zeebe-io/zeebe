/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.logstreams.storage.atomix;

import io.atomix.raft.zeebe.ZeebeLogAppender;
import io.camunda.zeebe.logstreams.storage.LogStorage;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Implementation of {@link LogStorage} for the Atomix {@link io.atomix.raft.storage.log.RaftLog}.
 *
 * <p>Note that this class cannot be made final because we currently spy on it in our tests. This
 * should be changed when the log storage implementation is taken out of this module, at which point
 * it can be made final.
 */
public class AtomixLogStorage implements LogStorage {

  private final AtomixReaderFactory readerFactory;
  private final Supplier<Optional<ZeebeLogAppender>> logAppenderSupplier;
  private final Consumer<Consumer<Long>> onCommitListenerRegistry;

  public AtomixLogStorage(
      final AtomixReaderFactory readerFactory,
      final Supplier<Optional<ZeebeLogAppender>> logAppenderSupplier,
      final Consumer<Consumer<Long>> onCommitListenerRegistry) {
    this.readerFactory = readerFactory;
    this.logAppenderSupplier = logAppenderSupplier;
    this.onCommitListenerRegistry = onCommitListenerRegistry;
  }

  public static AtomixLogStorage ofPartition(
      final AtomixReaderFactory readerFactory,
      final Supplier<Optional<ZeebeLogAppender>> appenderSupplier,
      final Consumer<Consumer<Long>> onCommitListenerRegistry) {
    return new AtomixLogStorage(readerFactory, appenderSupplier, onCommitListenerRegistry);
  }

  @Override
  public AtomixLogStorageReader newReader() {
    return new AtomixLogStorageReader(readerFactory.create());
  }

  @Override
  public void append(
      final long lowestPosition,
      final long highestPosition,
      final ByteBuffer buffer,
      final AppendListener listener) {
    final var adapter = new AtomixAppendListenerAdapter(listener);
    final var zeebeLogAppender = logAppenderSupplier.get().orElseThrow();
    zeebeLogAppender.appendEntry(lowestPosition, highestPosition, buffer, adapter);
  }

  @Override
  public void addCommitListener(final Consumer<Long> commitListener) {
    onCommitListenerRegistry.accept(commitListener);
  }
}
