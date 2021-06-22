/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.state.mutable;

import io.camunda.zeebe.engine.processing.streamprocessor.TypedRecord;
import io.camunda.zeebe.engine.state.immutable.BlackListState;
import java.util.function.Consumer;

public interface MutableBlackListState extends BlackListState {

  boolean tryToBlacklist(
      final TypedRecord<?> typedRecord, final Consumer<Long> onBlacklistingInstance);

  void blacklistProcessInstance(final long processInstanceKey);
}
