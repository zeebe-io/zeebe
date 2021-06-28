/*
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.core;

import io.atomix.cluster.ClusterConfig;
import io.atomix.cluster.Node;
import io.atomix.cluster.discovery.BootstrapDiscoveryProvider;
import io.atomix.utils.net.Address;
import io.camunda.zeebe.test.util.socket.SocketUtil;
import io.netty.util.NetUtil;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

public final class AtomixRule extends ExternalResource {

  private static final int TIMEOUT_IN_S = 90;
  private final TemporaryFolder temporaryFolder = new TemporaryFolder();
  private File dataDir;
  private Map<Integer, Address> addressMap;
  private List<Atomix> instances;

  @Override
  public Statement apply(final Statement base, final Description description) {
    return temporaryFolder.apply(super.apply(base, description), description);
  }

  @Override
  public void before() throws IOException {
    dataDir = temporaryFolder.newFolder();
    addressMap = new HashMap<>();
    instances = new ArrayList<>();
  }

  @Override
  protected void after() {
    final List<CompletableFuture<Void>> futures =
        instances.stream().map(Atomix::stop).collect(Collectors.toList());
    try {
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
          .get(TIMEOUT_IN_S, TimeUnit.SECONDS);
    } catch (final Exception e) {
      // Do nothing
    }
  }

  public File getDataDir() {
    return dataDir;
  }

  /** Creates an Atomix instance. */
  public AtomixBuilder buildAtomix(
      final int id, final List<Integer> memberIds, final Properties properties) {
    final Collection<Node> nodes =
        memberIds.stream()
            .map(
                memberId -> {
                  final var address = getAddress(memberId);

                  return Node.builder()
                      .withId(String.valueOf(memberId))
                      .withAddress(address)
                      .build();
                })
            .collect(Collectors.toList());

    return Atomix.builder(new ClusterConfig())
        .withClusterId("test")
        .withMemberId(String.valueOf(id))
        .withHost("localhost")
        .withPort(getAddress(id).port())
        .withProperties(properties)
        .withMembershipProvider(new BootstrapDiscoveryProvider(nodes));
  }

  private Address getAddress(final Integer memberId) {
    return addressMap.computeIfAbsent(
        memberId,
        newId -> {
          final var nextInetAddress = SocketUtil.getNextAddress();
          final var addressString = NetUtil.toSocketAddressString(nextInetAddress);
          return Address.from(addressString);
        });
  }

  /** Creates an Atomix instance. */
  private Atomix createAtomix(
      final int id,
      final List<Integer> bootstrapIds,
      final Function<AtomixBuilder, Atomix> builderFunction) {
    return createAtomix(id, bootstrapIds, new Properties(), builderFunction);
  }

  /** Creates an Atomix instance. */
  private Atomix createAtomix(
      final int id,
      final List<Integer> bootstrapIds,
      final Properties properties,
      final Function<AtomixBuilder, Atomix> builderFunction) {
    return builderFunction.apply(buildAtomix(id, bootstrapIds, properties));
  }

  public CompletableFuture<Atomix> startAtomix(
      final int id,
      final List<Integer> persistentIds,
      final Function<AtomixBuilder, Atomix> builderFunction) {
    final Atomix atomix = createAtomix(id, persistentIds, builderFunction);
    instances.add(atomix);
    return atomix.start().thenApply(v -> atomix);
  }
}
