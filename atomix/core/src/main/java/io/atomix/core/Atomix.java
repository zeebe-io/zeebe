/*
 * Copyright 2017-present Open Networking Foundation
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.core;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.io.Resources;
import io.atomix.cluster.AtomixCluster;
import io.atomix.cluster.ClusterConfig;
import io.atomix.cluster.messaging.ManagedMessagingService;
import io.atomix.cluster.messaging.ManagedUnicastService;
import io.atomix.utils.Version;
import io.atomix.utils.concurrent.Futures;
import io.atomix.utils.config.ConfigurationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Primary interface for managing Atomix clusters and operating on distributed primitives.
 *
 * <p>The {@code Atomix} class is the primary interface to all Atomix features. To construct an
 * {@code Atomix} instance, either configure the instance with a configuration file or construct a
 * new instance from an {@link AtomixBuilder}. Builders can be created via various {@link
 * #builder()} methods:
 *
 * <pre>{@code
 * Atomix atomix = Atomix.builder()
 *   .withMemberId("member-1")
 *   .withHost("192.168.10.2")
 *   .build();
 *
 * }</pre>
 *
 * Once an {@code Atomix} instance has been constructed, start the instance by calling {@link
 * #start()}:
 *
 * <pre>{@code
 * atomix.start().join();
 *
 * }</pre>
 *
 * The returned {@link CompletableFuture} will be completed once the node has been bootstrapped and
 * all services are available.
 */
public class Atomix extends AtomixCluster {

  private static final Logger LOGGER = LoggerFactory.getLogger(Atomix.class);
  private static final String VERSION_RESOURCE = "VERSION";
  private static final String BUILD;
  private static final Version VERSION;

  static {
    try {
      BUILD =
          Resources.toString(
              checkNotNull(
                  Atomix.class.getClassLoader().getResource(VERSION_RESOURCE),
                  VERSION_RESOURCE + " resource is null"),
              StandardCharsets.UTF_8);
    } catch (final IOException | NullPointerException e) {
      throw new ConfigurationException("Failed to load Atomix version", e);
    }
    VERSION = BUILD.trim().length() > 0 ? Version.from(BUILD.trim().split("\\s+")[0]) : null;
  }

  protected Atomix(final ClusterConfig config) {
    this(config, null, null);
  }

  protected Atomix(
      final ClusterConfig config,
      final ManagedMessagingService messagingService,
      final ManagedUnicastService unicastService) {
    super(config, VERSION, messagingService, unicastService);
  }

  /**
   * Returns a new Atomix builder.
   *
   * <p>The builder will be initialized with the configuration in {@code atomix.conf}, {@code
   * atomix.json}, or {@code atomix.properties} if located on the classpath.
   *
   * @return a new Atomix builder
   */
  public static AtomixBuilder builder() {
    return builder(new ClusterConfig());
  }

  /**
   * Returns a new Atomix builder.
   *
   * <p>The returned builder will be initialized with the provided configuration.
   *
   * @param config the Atomix configuration
   * @return the Atomix builder
   */
  public static AtomixBuilder builder(final ClusterConfig config) {
    return new AtomixBuilder(config);
  }

  /**
   * Starts the Atomix instance.
   *
   * <p>The returned future will be completed once this instance completes startup. Note that in
   * order to complete startup, all partitions must be able to form. For Raft partitions, that
   * requires that a majority of the nodes in each partition be started concurrently.
   *
   * @return a future to be completed once the instance has completed startup
   */
  @Override
  public synchronized CompletableFuture<Void> start() {
    if (closeFuture != null) {
      return Futures.exceptionalFuture(
          new IllegalStateException(
              "Atomix instance " + (closeFuture.isDone() ? "shutdown" : "shutting down")));
    }

    LOGGER.info(BUILD);
    return super.start();
  }
}
