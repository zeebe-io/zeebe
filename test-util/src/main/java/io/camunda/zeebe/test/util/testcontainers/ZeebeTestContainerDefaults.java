/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.test.util.testcontainers;

import io.camunda.zeebe.util.Environment;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

public final class ZeebeTestContainerDefaults {
  private static final Logger LOGGER = LoggerFactory.getLogger(ZeebeTestContainerDefaults.class);
  private static final DockerImageName DEFAULT_TEST_IMAGE =
      DockerImageName.parse("camunda/zeebe:current-test");
  private static final boolean SHOULD_USE_EXISTING_IMAGE =
      new Environment().getBool("ZEEBE_USE_EXISTING_DOCKER_IMAGE").orElse(false);
  private static boolean builtLocalImage = false;

  private ZeebeTestContainerDefaults() {}

  /**
   * Returns the default Zeebe image to use for integration tests. If {@link
   * #SHOULD_USE_EXISTING_IMAGE} is false (the default), it will build a fresh image from the code
   * at most once.
   */
  public static DockerImageName defaultTestImage() {
    if (!SHOULD_USE_EXISTING_IMAGE) {
      buildLocaLImage();
    }

    return DEFAULT_TEST_IMAGE;
  }

  private static synchronized void buildLocaLImage() {
    if (builtLocalImage) {
      return;
    }

    final var dockerfile = Path.of("/home/nicolas/src/github.com/camunda-cloud/zeebe/Dockerfile");
    final var image =
        new ImageFromDockerfile(DEFAULT_TEST_IMAGE.asCanonicalNameString())
            .withDockerfile(dockerfile)
            .withBuildArg("BASE", "maven")
            .withBuildArg("APP_ENV", "dev");

    LOGGER.info("Building test image {} from {}", DEFAULT_TEST_IMAGE, dockerfile);

    try {
      image.get(10, TimeUnit.MINUTES);
      builtLocalImage = true;
    } catch (final TimeoutException e) {
      LOGGER.warn(
          "Timed out waiting to build the test Docker image {}; it may not exist",
          DEFAULT_TEST_IMAGE,
          e);
    }
  }
}
