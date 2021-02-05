/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
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
package io.zeebe.client;

import static io.zeebe.client.ClientProperties.USE_PLAINTEXT_CONNECTION;
import static io.zeebe.client.impl.ZeebeClientBuilderImpl.CA_CERTIFICATE_VAR;
import static io.zeebe.client.impl.ZeebeClientBuilderImpl.KEEP_ALIVE_VAR;
import static io.zeebe.client.impl.ZeebeClientBuilderImpl.PLAINTEXT_CONNECTION_VAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.zeebe.client.impl.ZeebeClientBuilderImpl;
import io.zeebe.client.impl.util.Environment;
import io.zeebe.client.impl.util.EnvironmentRule;
import io.zeebe.client.util.ClientTest;
import java.io.FileNotFoundException;
import java.time.Duration;
import java.util.Properties;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public final class ZeebeClientTest extends ClientTest {
  @Rule public final EnvironmentRule environmentRule = new EnvironmentRule();
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void shouldNotFailIfClosedTwice() {
    client.close();
    client.close();
  }

  @Test
  public void shouldHaveDefaultValues() {
    // given
    try (final ZeebeClient client = ZeebeClient.newClient()) {
      // when
      final ZeebeClientConfiguration configuration = client.getConfiguration();

      // then
      assertThat(configuration.getGatewayAddress()).isEqualTo("0.0.0.0:26500");
      assertThat(configuration.getDefaultJobWorkerMaxJobsActive()).isEqualTo(32);
      assertThat(configuration.getDefaultJobWorkerMinJobsActiveRatio()).isEqualTo(0.3f);
      assertThat(configuration.getNumJobWorkerExecutionThreads()).isEqualTo(1);
      assertThat(configuration.getDefaultJobWorkerName()).isEqualTo("default");
      assertThat(configuration.getDefaultJobTimeout()).isEqualTo(Duration.ofMinutes(5));
      assertThat(configuration.getDefaultJobPollInterval()).isEqualTo(Duration.ofMillis(100));
      assertThat(configuration.getDefaultMessageTimeToLive()).isEqualTo(Duration.ofHours(1));
      assertThat(configuration.getDefaultRequestTimeout()).isEqualTo(Duration.ofSeconds(10));
    }
  }

  @Test
  public void shouldFailIfCertificateDoesNotExist() {
    assertThatThrownBy(
            () -> ZeebeClient.newClientBuilder().caCertificatePath("/wrong/path").build())
        .hasCauseInstanceOf(FileNotFoundException.class);
  }

  @Test
  public void shouldFailWithEmptyCertificatePath() {
    assertThatThrownBy(() -> ZeebeClient.newClientBuilder().caCertificatePath("").build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void shouldHaveTlsEnabledByDefault() {
    assertThat(new ZeebeClientBuilderImpl().isPlaintextConnectionEnabled()).isFalse();
  }

  @Test
  public void shouldUseInsecureWithEnvVar() {
    // given
    Environment.system().put(PLAINTEXT_CONNECTION_VAR, "true");
    final ZeebeClientBuilderImpl builder = new ZeebeClientBuilderImpl();

    // when
    builder.build();

    // then
    assertThat(builder.isPlaintextConnectionEnabled()).isTrue();
  }

  @Test
  public void shouldOverridePropertyWithEnvVariable() {
    // given
    Environment.system().put(PLAINTEXT_CONNECTION_VAR, "false");
    final Properties properties = new Properties();
    properties.putIfAbsent(USE_PLAINTEXT_CONNECTION, "true");
    final ZeebeClientBuilderImpl builder = new ZeebeClientBuilderImpl();
    builder.withProperties(properties);

    // when
    builder.build();

    // then
    assertThat(builder.isPlaintextConnectionEnabled()).isFalse();
  }

  @Test
  public void shouldCaCertificateWithEnvVar() {
    // given
    final String certPath = getClass().getClassLoader().getResource("ca.cert.pem").getPath();
    Environment.system().put(CA_CERTIFICATE_VAR, certPath);
    final ZeebeClientBuilderImpl builder = new ZeebeClientBuilderImpl();

    // when
    builder.build();

    // then
    assertThat(builder.getCaCertificatePath()).isEqualTo(certPath);
  }

  @Test
  public void shouldSetKeepAlive() {
    // given
    final ZeebeClientBuilderImpl builder = new ZeebeClientBuilderImpl();
    builder.keepAlive(Duration.ofMinutes(2));

    // when
    builder.build();

    // then
    assertThat(builder.getKeepAlive()).isEqualTo(Duration.ofMinutes(2));
  }

  @Test
  public void shouldOverrideKeepAliveWithEnvVar() {
    // given
    final ZeebeClientBuilderImpl builder = new ZeebeClientBuilderImpl();
    builder.keepAlive(Duration.ofMinutes(2));
    Environment.system().put(KEEP_ALIVE_VAR, "15000");

    // when
    builder.build();

    // then
    assertThat(builder.getKeepAlive()).isEqualTo(Duration.ofSeconds(15));
  }

  @Test
  public void shouldRejectUnsupportedTimeUnitWithEnvVar() {
    // when/then
    Environment.system().put(KEEP_ALIVE_VAR, "30d");
    assertThatThrownBy(() -> new ZeebeClientBuilderImpl().build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void shouldRejectNegativeTime() {
    // when/then
    assertThatThrownBy(() -> new ZeebeClientBuilderImpl().keepAlive(Duration.ofSeconds(-2)).build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void shouldRejectNegativeTimeAsEnvVar() {
    // when/then
    Environment.system().put(KEEP_ALIVE_VAR, "-2s");
    assertThatThrownBy(() -> new ZeebeClientBuilderImpl().build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void shouldBrokerContactPointReturnTheSameAsGatewayAddress() {
    // given
    final String gatewayAddress = "localhost:26500";
    try (final ZeebeClient client =
        ZeebeClient.newClientBuilder().brokerContactPoint(gatewayAddress).build()) {
      // when
      final ZeebeClientConfiguration configuration = client.getConfiguration();
      // then
      assertThat(configuration.getGatewayAddress())
          .isEqualTo(configuration.getBrokerContactPoint())
          .isEqualTo(gatewayAddress);
    }
  }
}
