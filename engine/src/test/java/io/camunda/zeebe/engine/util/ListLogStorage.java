/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.util;

import io.camunda.zeebe.logstreams.storage.LogStorage;
import io.camunda.zeebe.logstreams.storage.LogStorageReader;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongConsumer;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

public class ListLogStorage implements LogStorage {

  private final ConcurrentNavigableMap<Long, Integer> positionIndexMapping;
  private final List<Entry> entries;
  private LongConsumer positionListener;
  private final Set<CommitListener> commitListeners = new HashSet<>();

  public ListLogStorage() {
    entries = new CopyOnWriteArrayList<>();
    positionIndexMapping = new ConcurrentSkipListMap<>();
  }

  public void setPositionListener(final LongConsumer positionListener) {
    this.positionListener = positionListener;
  }

  @Override
  public LogStorageReader newReader() {
    return new ListLogStorageReader();
  }

  @Override
  public void append(
      final long lowestPosition,
      final long highestPosition,
      final ByteBuffer blockBuffer,
      final AppendListener listener) {
    try {
      final var entry = new Entry(blockBuffer);
      entries.add(entry);
      final var index = entries.size();
      positionIndexMapping.put(lowestPosition, index);
      listener.onWrite(index);

      if (positionListener != null) {
        positionListener.accept(highestPosition);
      }
      listener.onCommit(index);
      commitListeners.forEach(CommitListener::onCommit);
    } catch (final Exception e) {
      listener.onWriteError(e);
    }
  }

  @Override
  public void addCommitListener(final CommitListener listener) {
    commitListeners.add(listener);
  }

  @Override
  public void removeCommitListener(final CommitListener listener) {
    commitListeners.remove(listener);
  }

  private static final class Entry {
    private final ByteBuffer data;

    public Entry(final ByteBuffer data) {
      this.data = data;
    }

    public ByteBuffer getData() {
      return data;
    }
  }

  private class ListLogStorageReader implements LogStorageReader {
    int currentIndex;

    @Override
    public void seek(final long position) {
      currentIndex =
          Optional.ofNullable(positionIndexMapping.lowerEntry(position))
              .map(Map.Entry::getValue)
              .map(index -> index - 1)
              .orElse(0);
    }

    @Override
    public void close() {}

    @Override
    public boolean hasNext() {
      return currentIndex >= 0 && currentIndex < entries.size();
    }

    @Override
    public DirectBuffer next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }

      final int index = currentIndex;
      currentIndex++;

      return new UnsafeBuffer(entries.get(index).data);
    }
  }
}
