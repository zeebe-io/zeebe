/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.zeebe.broker.exporter.stream.ExportPositionsReq;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

public class ExporterPositionsReqTest {

  @Test
  public void shouldEncodeExporterPositions() {
    // given
    final var exportPositionsReq = new ExportPositionsReq();
    exportPositionsReq.putExporter("elasticsearch", 1001);
    exportPositionsReq.putExporter("metrics", 95);
    final var length = exportPositionsReq.getLength();

    // when
    final var buffer = new UnsafeBuffer(new byte[length]);
    exportPositionsReq.write(buffer, 0);

    // then
    assertThat(buffer.capacity()).isEqualTo(exportPositionsReq.getLength());
  }

  @Test
  public void shouldDecodeExporterPositions() {
    // given
    final var exportPositionsReq = new ExportPositionsReq();
    exportPositionsReq.putExporter("elasticsearch", 1001);
    exportPositionsReq.putExporter("metrics", 95);
    final var length = exportPositionsReq.getLength();
    final var buffer = new UnsafeBuffer(new byte[length]);
    exportPositionsReq.write(buffer, 0);

    // when
    final var otherReq = new ExportPositionsReq();
    otherReq.wrap(buffer, 0, length);

    // then
    assertThat(otherReq.getExporterPositions())
        .isEqualTo(exportPositionsReq.getExporterPositions());
  }
}
