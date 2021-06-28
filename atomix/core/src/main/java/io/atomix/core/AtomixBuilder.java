/*
 * Copyright 2018-present Open Networking Foundation
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

import static java.util.Objects.requireNonNull;

import io.atomix.cluster.AtomixClusterBuilder;
import io.atomix.cluster.ClusterConfig;
import io.atomix.cluster.MemberId;
import io.atomix.cluster.discovery.NodeDiscoveryProvider;
import io.atomix.cluster.protocol.GroupMembershipProtocol;
import io.atomix.utils.net.Address;
import java.util.Collection;
import java.util.Properties;

/**
 * Builder for {@link Atomix} instance.
 *
 * <p>This builder is used to configure an {@link Atomix} instance programmatically. To create a new
 * builder, use one of the {@link Atomix#builder()} static methods.
 *
 * <pre>{@code
 * AtomixBuilder builder = Atomix.builder();
 *
 * }</pre>
 *
 * The instance is configured by calling the {@code with*} methods on this builder. Once the
 * instance has been configured, call {@link #build()} to build the instance:
 *
 * <pre>{@code
 * Atomix atomix = Atomix.builder()
 *   .withMemberId("member-1")
 *   .withHost("192.168.10.2")
 *   .withPort(5000)
 *   .build();
 *
 * }</pre>
 *
 * Backing the builder is an {@link AtomixConfig} which is loaded when the builder is initially
 * constructed. To load a configuration from a file, use {@link Atomix#builder(String)}.
 */
public class AtomixBuilder extends AtomixClusterBuilder {

  private final ClusterConfig clsuterConfig;

  AtomixBuilder(final ClusterConfig clusterConfig) {
    super(clusterConfig);
    clsuterConfig = requireNonNull(clusterConfig);
  }

  @Override
  public AtomixBuilder withClusterId(final String clusterId) {
    super.withClusterId(clusterId);
    return this;
  }

  @Override
  public AtomixBuilder withMemberId(final String localMemberId) {
    super.withMemberId(localMemberId);
    return this;
  }

  @Override
  public AtomixBuilder withMemberId(final MemberId localMemberId) {
    super.withMemberId(localMemberId);
    return this;
  }

  @Override
  public AtomixBuilder withHost(final String host) {
    super.withHost(host);
    return this;
  }

  @Override
  public AtomixBuilder withPort(final int port) {
    super.withPort(port);
    return this;
  }

  @Override
  public AtomixBuilder withAddress(final Address address) {
    super.withAddress(address);
    return this;
  }

  @Override
  public AtomixBuilder withProperties(final Properties properties) {
    super.withProperties(properties);
    return this;
  }

  @Override
  public AtomixBuilder withMessagingInterface(final String iface) {
    super.withMessagingInterface(iface);
    return this;
  }

  @Override
  public AtomixBuilder withMessagingInterfaces(final Collection<String> ifaces) {
    super.withMessagingInterfaces(ifaces);
    return this;
  }

  @Override
  public AtomixBuilder withMessagingPort(final int bindPort) {
    super.withMessagingPort(bindPort);
    return this;
  }

  @Override
  public AtomixBuilder withMembershipProtocol(final GroupMembershipProtocol protocol) {
    super.withMembershipProtocol(protocol);
    return this;
  }

  @Override
  public AtomixBuilder withMembershipProvider(final NodeDiscoveryProvider locationProvider) {
    super.withMembershipProvider(locationProvider);
    return this;
  }

  /**
   * Builds a new Atomix instance.
   *
   * <p>The returned instance will be configured with the initial builder configuration plus any
   * overrides that were made via the builder. The returned instance will not be running. To start
   * the instance call the {@link Atomix#start()} method:
   *
   * <pre>{@code
   * Atomix atomix = Atomix.builder()
   *   .withMemberId("member-1")
   *   .withHost("192.168.10.2")
   *   .withPort(5000)
   *   .build();
   * atomix.start().join();
   *
   * }</pre>
   *
   * @return a new Atomix instance
   */
  @Override
  public Atomix build() {
    return new Atomix(clsuterConfig);
  }
}
