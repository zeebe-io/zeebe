/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.exporter.stream;

import static org.assertj.core.api.Assertions.assertThat;

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

public class ExporterPositionsMessageTest {

  @Test
  public void shouldEncodeExporterPositions() {
    // given
    final var exportPositionsMessage = new ExporterPositionsMessage();
    exportPositionsMessage.putExporter("elasticsearch", 1001);
    exportPositionsMessage.putExporter("metrics", 95);
    final var length = exportPositionsMessage.getLength();

    // when
    final var bytes = new byte[length];
    final var buffer = new UnsafeBuffer(bytes);
    exportPositionsMessage.write(buffer, 0);

    // then
    assertThat(buffer.capacity()).isEqualTo(exportPositionsMessage.getLength());
    assertThat(bytes)
        .containsSubsequence("elasticsearch".getBytes())
        .containsSubsequence("metrics".getBytes());
  }

  @Test
  public void shouldDecodeExporterPositions() {
    // given
    final var exportPositionsMessage = new ExporterPositionsMessage();
    exportPositionsMessage.putExporter("elasticsearch", 1001);
    exportPositionsMessage.putExporter("metrics", 95);
    final var length = exportPositionsMessage.getLength();
    final var buffer = new UnsafeBuffer(new byte[length]);
    exportPositionsMessage.write(buffer, 0);

    // when
    final var otherMessage = new ExporterPositionsMessage();
    otherMessage.wrap(buffer, 0, length);

    // then
    assertThat(otherMessage.getExporterPositions())
        .isEqualTo(exportPositionsMessage.getExporterPositions());
  }
}
