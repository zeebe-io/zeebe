/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.it.client.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.util.NetUtil;
import io.zeebe.broker.it.util.GrpcClientRule;
import io.zeebe.broker.system.configuration.BrokerCfg;
import io.zeebe.broker.test.EmbeddedBrokerRule;
import io.zeebe.client.ZeebeClient;
import io.zeebe.client.api.ZeebeFuture;
import io.zeebe.client.api.response.ActivateJobsResponse;
import io.zeebe.client.api.response.ActivatedJob;
import io.zeebe.test.util.BrokerClassRuleHelper;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

public class LongPollingActivateJobsTest {

  private static final EmbeddedBrokerRule BROKER_RULE =
      new EmbeddedBrokerRule(LongPollingActivateJobsTest::enableLongPolling);
  private static final GrpcClientRule CLIENT_RULE = new GrpcClientRule(BROKER_RULE);

  @ClassRule
  public static RuleChain ruleChain = RuleChain.outerRule(BROKER_RULE).around(CLIENT_RULE);

  @Rule public final BrokerClassRuleHelper helper = new BrokerClassRuleHelper();

  private String jobType;

  private static void enableLongPolling(final BrokerCfg config) {
    config.getGateway().getLongPolling().setEnabled(true);
  }

  @Before
  public void init() {
    jobType = helper.getJobType();
  }

  @Test
  public void shouldActivateJobsRespectingAmountLimit() {
    // given
    final int availableJobs = 3;
    final int activateJobs = 2;

    CLIENT_RULE.createJobs(jobType, availableJobs);

    // when
    final ActivateJobsResponse response =
        CLIENT_RULE
            .getClient()
            .newActivateJobsCommand()
            .jobType(jobType)
            .maxJobsToActivate(activateJobs)
            .send()
            .join();

    // then
    assertThat(response.getJobs()).hasSize(activateJobs);
  }

  @Test
  public void shouldActivateJobsIfBatchIsTruncated() {
    // given
    final int availableJobs = 10;

    final int maxMessageSize =
        (int) BROKER_RULE.getBrokerCfg().getNetwork().getMaxMessageSizeInBytes();
    final var largeVariableValue = "x".repeat(maxMessageSize / 4);
    final String variablesJson = String.format("{\"variablesJson\":\"%s\"}", largeVariableValue);

    CLIENT_RULE.createJobs(jobType, b -> {}, variablesJson, availableJobs);

    // when
    final var response =
        CLIENT_RULE
            .getClient()
            .newActivateJobsCommand()
            .jobType(jobType)
            .maxJobsToActivate(availableJobs)
            .send()
            .join();

    // then
    assertThat(response.getJobs()).hasSize(availableJobs);
  }

  @Test
  public void shouldWaitUntilJobsAvailable() {
    // given
    final int expectedJobsCount = 1;

    final ZeebeFuture<ActivateJobsResponse> responseFuture =
        CLIENT_RULE
            .getClient()
            .newActivateJobsCommand()
            .jobType(jobType)
            .maxJobsToActivate(expectedJobsCount)
            .send();

    // when
    CLIENT_RULE.createSingleJob(jobType);

    // then
    final ActivateJobsResponse response = responseFuture.join();
    assertThat(response.getJobs()).hasSize(expectedJobsCount);
  }

  @Test
  public void shouldActivatedJobForOpenRequest() throws InterruptedException {
    // given
    sendActivateRequestsAndClose(jobType, 3);

    final var activateJobsResponse =
        CLIENT_RULE
            .getClient()
            .newActivateJobsCommand()
            .jobType(jobType)
            .maxJobsToActivate(5)
            .workerName("open")
            .send();

    sendActivateRequestsAndClose(jobType, 3);

    // when
    CLIENT_RULE.createSingleJob(jobType);

    // then
    final var jobs = activateJobsResponse.join().getJobs();
    assertThat(jobs).hasSize(1).extracting(ActivatedJob::getWorker).contains("open");
  }

  private void sendActivateRequestsAndClose(final String jobType, final int count)
      throws InterruptedException {
    for (int i = 0; i < count; i++) {
      final ZeebeClient client =
          ZeebeClient.newClientBuilder()
              .gatewayAddress(NetUtil.toSocketAddressString(BROKER_RULE.getGatewayAddress()))
              .usePlaintext()
              .build();

      client
          .newActivateJobsCommand()
          .jobType(jobType)
          .maxJobsToActivate(5)
          .workerName("closed-" + i)
          .send();

      Thread.sleep(100);
      client.close();
    }
  }
}
