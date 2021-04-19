/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.test;

import io.netty.util.NetUtil;
import io.zeebe.broker.system.configuration.BrokerCfg;
import io.zeebe.client.ClientProperties;
import io.zeebe.client.ZeebeClient;
import io.zeebe.client.api.response.WorkflowInstanceEvent;
import io.zeebe.test.util.record.RecordingExporter;
import io.zeebe.test.util.record.RecordingExporterTestWatcher;
import io.zeebe.util.sched.clock.ControlledActorClock;
import java.util.Properties;
import java.util.function.Supplier;
import org.junit.rules.ExternalResource;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

public class ZeebeTestRule extends ExternalResource {
  protected final RecordingExporterTestWatcher recordingExporterTestWatcher =
      new RecordingExporterTestWatcher();
  private final EmbeddedBrokerRule brokerRule;
  private final ClientRule clientRule;

  public ZeebeTestRule() {
    this(EmbeddedBrokerRule.DEFAULT_CONFIG_FILE, Properties::new);
  }

  public ZeebeTestRule(
      final String configFileClasspathLocation, final Supplier<Properties> propertiesProvider) {
    brokerRule = new EmbeddedBrokerRule(configFileClasspathLocation);
    clientRule =
        new ClientRule(
            () -> {
              final Properties properties = propertiesProvider.get();
              properties.setProperty(
                  ClientProperties.BROKER_CONTACTPOINT,
                  NetUtil.toSocketAddressString(brokerRule.getGatewayAddress()));
              properties.putIfAbsent(ClientProperties.USE_PLAINTEXT_CONNECTION, "true");

              return properties;
            });
  }

  public ZeebeClient getClient() {
    return clientRule.getClient();
  }

  public BrokerCfg getBrokerCfg() {
    return brokerRule.getBrokerCfg();
  }

  public ControlledActorClock getBrokerClock() {
    return brokerRule.getClock();
  }

  @Override
  public Statement apply(final Statement base, final Description description) {
    final Statement statement = recordingExporterTestWatcher.apply(base, description);
    return super.apply(statement, description);
  }

  @Override
  protected void before() {
    brokerRule.before();
    clientRule.before();
  }

  @Override
  protected void after() {
    clientRule.after();
    brokerRule.after();
  }

  public static WorkflowInstanceAssert assertThat(final WorkflowInstanceEvent workflowInstance) {
    return WorkflowInstanceAssert.assertThat(workflowInstance);
  }

  public void printWorkflowInstanceEvents(final long key) {
    RecordingExporter.workflowInstanceRecords()
        .withWorkflowInstanceKey(key)
        .forEach(
            event -> {
              System.out.println("> " + event.toJson());
            });
  }
}
