/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.logstreams.storage;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * Storage abstraction for the log stream API. The storage is expected to store the given blocks of
 * data atomically (i.e. a block is fully written or not at all), in the order in which they were
 * appended.
 *
 * <p>The main access pattern is via the {@link LogStorageReader}, and is expected to be sequential.
 * The reader should support seek as efficiently as possible, but random access is not the common
 * case.
 *
 * <p>The lifecycle of the storage is expected to be independent of the log stream, and the storage
 * is simply passed along to the stream.
 */
public interface LogStorage {
  /**
   * Creates a new reader initialized at the given address.
   *
   * @return a new stateful storage reader
   */
  LogStorageReader newReader();

  /**
   * Writes a block containing one or multiple log entries in the storage and returns the address at
   * which the block has been written.
   *
   * <p>Storage implementations must guarantee eventually atomicity. When this method completes,
   * either all the bytes must be written or none at all.
   *
   * <p>The caller of this method must guarantee that the provided block contains unfragmented log
   * entries.
   *
   * @param lowestPosition the lowest record position of all records in the block buffer
   * @param highestPosition the highest record position of all records in the block buffer
   * @param blockBuffer the buffer containing a block of log entries to be written into storage
   */
  void append(
      long lowestPosition, long highestPosition, ByteBuffer blockBuffer, AppendListener listener);

  void addCommitListener(Consumer<Long> commitListener);

  /**
   * An append listener can be added to an append call to be notified of different events that can
   * occur during the append operation.
   */
  interface AppendListener {

    /**
     * Called when the entry has been successfully written to the local storage.
     *
     * @param address the address of the written entry
     */
    default void onWrite(final long address) {}

    /**
     * Called when an error occurred while writing to the entry.
     *
     * @param error the error that occurred
     */
    default void onWriteError(final Throwable error) {}

    /**
     * Called when the entry has been successfully committed.
     *
     * @param address the address of the committed entry
     */
    default void onCommit(final long address) {}

    /**
     * Called when an error occurs while committing an entry.
     *
     * @param address the address of the entry to be committed
     * @param error the error that occurred
     */
    default void onCommitError(final long address, final Throwable error) {}
  }
}
