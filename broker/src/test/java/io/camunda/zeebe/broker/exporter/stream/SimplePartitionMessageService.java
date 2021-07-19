/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.exporter.stream;

import io.camunda.zeebe.broker.system.partitions.PartitionMessagingService;
import io.camunda.zeebe.util.collection.Tuple;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

final class SimplePartitionMessageService implements PartitionMessagingService {

  public final Map<String, Tuple<Executor, Consumer<ByteBuffer>>> consumers = new HashMap<>();

  @Override
  public void subscribe(
      final String subject, final Consumer<ByteBuffer> consumer, final Executor executor) {
    consumers.put(subject, new Tuple<>(executor, consumer));
  }

  @Override
  public void broadcast(final String subject, final ByteBuffer payload) {
    final var executorConsumerTuple = consumers.get(subject);
    if (executorConsumerTuple != null) {
      final var executor = executorConsumerTuple.getLeft();
      executor.execute(() -> executorConsumerTuple.getRight().accept(payload));
    }
  }

  @Override
  public void unsubscribe(final String subject) {
    consumers.remove(subject);
  }
}
