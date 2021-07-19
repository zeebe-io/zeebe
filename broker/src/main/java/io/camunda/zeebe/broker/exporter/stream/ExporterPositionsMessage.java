/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.exporter.stream;

import io.camunda.zeebe.clustering.management.ExporterPositionsDecoder;
import io.camunda.zeebe.clustering.management.ExporterPositionsEncoder;
import io.camunda.zeebe.clustering.management.ExporterPositionsEncoder.PositionsEncoder;
import io.camunda.zeebe.protocol.impl.encoding.SbeBufferWriterReader;
import io.camunda.zeebe.util.buffer.BufferUtil;
import java.util.HashMap;
import java.util.Map;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.MutableInteger;

public class ExporterPositionsMessage
    extends SbeBufferWriterReader<ExporterPositionsEncoder, ExporterPositionsDecoder> {

  final Map<String, Long> exporterPositions = new HashMap<>();
  private final ExporterPositionsEncoder encoder = new ExporterPositionsEncoder();
  private final ExporterPositionsDecoder decoder = new ExporterPositionsDecoder();

  @Override
  protected ExporterPositionsEncoder getBodyEncoder() {
    return encoder;
  }

  @Override
  protected ExporterPositionsDecoder getBodyDecoder() {
    return decoder;
  }

  @Override
  public void reset() {
    super.reset();
    exporterPositions.clear();
  }

  @Override
  public int getLength() {

    final var length = new MutableInteger();
    exporterPositions.forEach(
        (id, pos) ->
            length.addAndGet(
                PositionsEncoder.positionEncodingLength()
                    + PositionsEncoder.exporterIdHeaderLength()
                    + id.length()));

    return super.getLength() + PositionsEncoder.sbeHeaderSize() + length.get();
  }

  @Override
  public void write(final MutableDirectBuffer buffer, final int offset) {
    super.write(buffer, offset);

    final var positionsEncoder = encoder.positionsCount(exporterPositions.size());

    exporterPositions.forEach(
        (id, pos) -> {
          final var idBuffer = BufferUtil.wrapString(id);
          positionsEncoder.next().position(pos).putExporterId(idBuffer, 0, idBuffer.capacity());
        });
  }

  @Override
  public void wrap(final DirectBuffer buffer, final int offset, final int length) {
    super.wrap(buffer, offset, length);

    final var positionsDecoder = decoder.positions();

    while (positionsDecoder.hasNext()) {
      final var next = positionsDecoder.next();
      final var position = next.position();

      final var exporterIdLength = next.exporterIdLength();
      final var bytes = new byte[exporterIdLength];
      next.getExporterId(bytes, 0, exporterIdLength);
      exporterPositions.put(new String(bytes), position);
    }
  }

  public void putExporter(final String exporterId, final long lastExportedPosition) {
    exporterPositions.put(exporterId, lastExportedPosition);
  }

  public Map<String, Long> getExporterPositions() {
    return exporterPositions;
  }
}
