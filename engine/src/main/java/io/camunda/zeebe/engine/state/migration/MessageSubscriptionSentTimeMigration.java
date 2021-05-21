/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.state.migration;

import io.camunda.zeebe.engine.state.ZbColumnFamilies;
import io.camunda.zeebe.engine.state.immutable.ZeebeState;
import io.camunda.zeebe.engine.state.mutable.MutableZeebeState;

/**
 * Reads out the sent time for message subscriptions and sets the corresponding fields in {@code
 * ZbColumnFamilies.MESSAGE_SUBSCRIPTION_BY_KEY}
 */
@MigrationMetadata(fromVersion = "1.0.0", toVersion = "1.1.0-alpha1")
public class MessageSubscriptionSentTimeMigration implements MigrationTask {

  @Override
  public String getIdentifier() {
    return MessageSubscriptionSentTimeMigration.class.getSimpleName();
  }

  @Override
  public boolean needsToRun(final ZeebeState zeebeState) {
    return !zeebeState.isEmpty(ZbColumnFamilies.MESSAGE_SUBSCRIPTION_BY_SENT_TIME);
  }

  @Override
  public void runMigration(final MutableZeebeState zeebeState) {
    zeebeState
        .getMigrationState()
        .migrateMessageSubscriptionSentTime(zeebeState.getMessageSubscriptionState());
  }
}
