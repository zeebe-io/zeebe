/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.streamprocessor;

import io.camunda.zeebe.engine.state.ZbColumnFamilies;
import io.camunda.zeebe.engine.util.EngineRule;
import io.camunda.zeebe.engine.util.ListLogStorage;
import io.camunda.zeebe.model.bpmn.Bpmn;
import org.assertj.core.api.SoftAssertions;
import org.awaitility.Awaitility;
import org.junit.Rule;
import org.junit.Test;

public class ContinuouslyReplayTest {

  private final ListLogStorage listLogStorage = new ListLogStorage();

  @Rule
  public final EngineRule replay =
      EngineRule.withSharedStorage(listLogStorage).withContinuouslyReplay();

  @Rule public final EngineRule processing = EngineRule.withSharedStorage(listLogStorage);

  @Test
  public void shouldEndUpWithTheSameState() {
    // given

    // when
    processing
        .deployment()
        .withXmlResource(Bpmn.createExecutableProcess().startEvent().endEvent().done())
        .deploy();

    // then
    assertStates();
  }

  private void assertStates() {
    Awaitility.await("await that the replay state is equal to the processing state")
        .untilAsserted(
            () -> {
              final var replayState = replay.collectState();
              final var processingState = processing.collectState();

              final var softly = new SoftAssertions();

              processingState.entrySet().stream()
                  .filter(entry -> entry.getKey() != ZbColumnFamilies.DEFAULT)
                  // ignores transient states
                  .filter(entry -> entry.getKey() != ZbColumnFamilies.KEY)
                  // on followers we don't need to reset the key
                  // this will happen anyway then on leader replay
                  .forEach(
                      entry -> {
                        final var column = entry.getKey();
                        final var processingEntries = entry.getValue();
                        final var replayEntries = replayState.get(column);

                        if (processingEntries.isEmpty()) {
                          softly
                              .assertThat(replayEntries)
                              .describedAs(
                                  "The state column '%s' should be empty after replay", column)
                              .isEmpty();
                        } else {
                          softly
                              .assertThat(replayEntries)
                              .describedAs(
                                  "The state column '%s' has different entries after replay",
                                  column)
                              .containsExactlyInAnyOrderEntriesOf(processingEntries);
                        }
                      });

              softly.assertAll();
            });
  }
}
