/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.logstreams.storage.atomix;

import static org.assertj.core.api.Assertions.assertThat;

import io.atomix.raft.storage.log.IndexedRaftLogEntry;
import io.atomix.raft.storage.log.RaftLog;
import io.atomix.raft.storage.log.entry.ApplicationEntry;
import io.atomix.raft.storage.log.entry.RaftLogEntry;
import io.atomix.raft.zeebe.ZeebeLogAppender;
import io.camunda.zeebe.logstreams.storage.LogStorage.AppendListener;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Optional;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AtomixLogStorageReaderTest {

  private RaftLog log;
  private AtomixLogStorage logStorage;
  private AtomixLogStorageReader reader;

  @BeforeEach
  void beforeEach(final @TempDir File tempDir) {
    log = RaftLog.builder().withDirectory(tempDir).build();
    final Appender appender = new Appender();
    logStorage = new AtomixLogStorage(log::openReader, () -> Optional.of(appender), (c) -> {});
    reader = logStorage.newReader();
  }

  @AfterEach
  void afterEach() {
    CloseHelper.quietCloseAll(log, reader);
  }

  @Test
  void shouldBeInitializedAtFirstBlock() {
    // given
    appendIntegerBlock(1);
    appendIntegerBlock(2);

    // when
    // then
    assertThat(reader).hasNext();
    assertThat(reader.next()).isEqualTo(mapIntegerToBuffer(1));
  }

  @Test
  void shouldSeekToFirstBlock() {
    // given
    appendIntegerBlock(1);
    appendIntegerBlock(2);

    // when
    reader.seek(Long.MIN_VALUE);

    // then
    assertThat(reader).hasNext();
    assertThat(reader.next()).isEqualTo(mapIntegerToBuffer(1));
  }

  @Test
  void shouldSeekToLastBlock() {
    // given
    appendIntegerBlock(1);
    appendIntegerBlock(2);

    // when
    reader.seek(Long.MAX_VALUE);

    // then
    assertThat(reader).hasNext();
    assertThat(reader.next()).isEqualTo(mapIntegerToBuffer(2));
  }

  @Test
  void shouldSeekToHighestPositionThatIsLessThanTheGivenOne() {
    // given
    appendIntegerBlock(1);
    appendIntegerBlock(3);

    // when
    reader.seek(2);

    // then
    assertThat(reader).hasNext();
    assertThat(reader.next()).isEqualTo(mapIntegerToBuffer(1));
  }

  @Test
  void shouldSeekToBlockContainingPosition() {
    // given
    appendIntegerBlock(1);
    appendIntegerBlock(2, 4, 2);
    appendIntegerBlock(5);

    // when
    reader.seek(3);

    // then
    assertThat(reader).hasNext();
    assertThat(reader.next()).isEqualTo(mapIntegerToBuffer(2));
  }

  @Test
  void shouldNotHaveNextIfEndIsReached() {
    // given
    appendIntegerBlock(1);
    appendIntegerBlock(2);

    // when
    reader.seek(2);
    reader.next();

    // then
    assertThat(reader.hasNext()).isFalse();
  }

  @Test
  void shouldNotHaveNextIfEmpty() {
    // given an empty log
    // when
    // then
    assertThat(reader.hasNext()).isFalse();
  }

  @Test
  void shouldHaveNextAfterNewBlockAppended() {
    // given an empty log
    // when
    appendIntegerBlock(1);

    // then
    assertThat(reader).hasNext();
    assertThat(reader.next()).isEqualTo(mapIntegerToBuffer(1));
  }

  private void appendIntegerBlock(final int positionAndValue) {
    appendIntegerBlock(positionAndValue, positionAndValue, positionAndValue);
  }

  private void appendIntegerBlock(
      final long lowestPosition, final long highestPosition, final int value) {
    logStorage.append(
        lowestPosition,
        highestPosition,
        mapIntegerToBuffer(value).byteBuffer(),
        new AppendListener() {});
  }

  private DirectBuffer mapIntegerToBuffer(final int value) {
    return new UnsafeBuffer(ByteBuffer.allocateDirect(Integer.BYTES).putInt(0, value));
  }

  private final class Appender implements ZeebeLogAppender {

    @Override
    public void appendEntry(
        final long lowestPosition,
        final long highestPosition,
        final ByteBuffer data,
        final AppendListener appendListener) {
      final ApplicationEntry entry = new ApplicationEntry(lowestPosition, highestPosition, data);
      final IndexedRaftLogEntry indexedEntry = log.append(new RaftLogEntry(1, entry));

      appendListener.onWrite(indexedEntry);
      log.setCommitIndex(indexedEntry.index());
      appendListener.onCommit(indexedEntry);
    }
  }
}
