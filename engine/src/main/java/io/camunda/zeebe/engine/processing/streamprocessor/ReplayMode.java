/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.streamprocessor;

public enum ReplayMode {
  /**
   * Normal replay mode, which is used on Leader side. After the replay the processing normally
   * starts.
   */
  UNTIL_END,

  /**
   * Continuously means it will never stop the replay mode, all new events are replayed and commands
   * are ignored.
   */
  CONTINUOUSLY
}
