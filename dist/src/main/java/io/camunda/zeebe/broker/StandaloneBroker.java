/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker;

import static java.lang.Runtime.getRuntime;

import io.camunda.zeebe.broker.system.configuration.BrokerCfg;
import io.camunda.zeebe.shared.EnvironmentHelper;
import io.camunda.zeebe.util.FileUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.apache.logging.log4j.LogManager;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.Environment;

@SpringBootApplication(exclude = ElasticsearchRestClientAutoConfiguration.class)
@ComponentScan({"io.camunda.zeebe.broker", "io.camunda.zeebe.shared"})
public class StandaloneBroker implements CommandLineRunner {
  private static final Logger LOG = Loggers.SYSTEM_LOGGER;

  @Autowired BrokerCfg configuration;
  @Autowired Environment springEnvironment;
  @Autowired SpringBrokerBridge springBrokerBridge;

  private String tempFolder;

  public static void main(final String[] args) throws Exception {
    System.setProperty("spring.banner.location", "classpath:/assets/zeebe_broker_banner.txt");

    EnvironmentHelper.disableGatewayHealthIndicatorsAndProbes();

    SpringApplication.run(StandaloneBroker.class, args);
  }

  @Override
  public void run(final String... args) throws Exception {
    final Broker broker;

    if (EnvironmentHelper.isProductionEnvironment(springEnvironment)) {
      broker = createBrokerInBaseDirectory();
    } else {
      broker = createBrokerInTempDirectory();
    }

    broker.start();
    getRuntime()
        .addShutdownHook(
            new Thread("Broker close Thread") {
              @Override
              public void run() {
                try {
                  broker.close();
                } finally {
                  deleteTempDirectory();
                  LogManager.shutdown();
                }
              }
            });
  }

  private Broker createBrokerInBaseDirectory() {
    String basePath = System.getProperty("basedir");

    if (basePath == null) {
      basePath = Paths.get(".").toAbsolutePath().normalize().toString();
    }

    return new Broker(configuration, basePath, null, springBrokerBridge);
  }

  private Broker createBrokerInTempDirectory() {
    Loggers.SYSTEM_LOGGER.info("Launching broker in temporary folder.");

    try {
      tempFolder = Files.createTempDirectory("zeebe").toAbsolutePath().normalize().toString();
      return new Broker(configuration, tempFolder, null, springBrokerBridge);
    } catch (final IOException e) {
      throw new UncheckedIOException("Could not start broker", e);
    }
  }

  private void deleteTempDirectory() {
    if (tempFolder != null) {
      try {
        FileUtil.deleteFolder(tempFolder);
      } catch (final IOException e) {
        LOG.error("Failed to delete temporary folder {}", tempFolder, e);
      }
    }
  }
}
