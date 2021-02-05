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

import io.grpc.ClientInterceptor;
import io.zeebe.client.api.JsonMapper;
import java.time.Duration;
import java.util.List;

public interface ZeebeClientConfiguration {
  /**
   * @see ZeebeClientBuilder#brokerContactPoint(String)
   * @deprecated Use {@link #getGatewayAddress()}. It's deprecated since 0.25.0, and will be removed
   *     in 0.26.0
   */
  @Deprecated
  String getBrokerContactPoint();

  /** @see ZeebeClientBuilder#gatewayAddress(String) */
  String getGatewayAddress();

  /** @see ZeebeClientBuilder#numJobWorkerExecutionThreads(int) */
  int getNumJobWorkerExecutionThreads();

  /** @see ZeebeClientBuilder#defaultJobWorkerMaxJobsActive(int) */
  int getDefaultJobWorkerMaxJobsActive();

  /** @see ZeebeClientBuilder#defaultJobWorkerMinJobsActiveRatio(float) */
  float getDefaultJobWorkerMinJobsActiveRatio();

  /** @see ZeebeClientBuilder#defaultJobWorkerName(String) */
  String getDefaultJobWorkerName();

  /** @see ZeebeClientBuilder#defaultJobTimeout(Duration) */
  Duration getDefaultJobTimeout();

  /** @see ZeebeClientBuilder#defaultJobPollInterval(Duration) */
  Duration getDefaultJobPollInterval();

  /** @see ZeebeClientBuilder#defaultMessageTimeToLive(Duration) */
  Duration getDefaultMessageTimeToLive();

  /** @see ZeebeClientBuilder#defaultRequestTimeout(Duration) */
  Duration getDefaultRequestTimeout();

  /** @see ZeebeClientBuilder#usePlaintext() */
  boolean isPlaintextConnectionEnabled();

  /** @see ZeebeClientBuilder#caCertificatePath(String) */
  String getCaCertificatePath();

  /** @see ZeebeClientBuilder#credentialsProvider(CredentialsProvider) */
  CredentialsProvider getCredentialsProvider();

  /** @see ZeebeClientBuilder#keepAlive(Duration) */
  Duration getKeepAlive();

  List<ClientInterceptor> getInterceptors();

  /** @see ZeebeClientBuilder#withJsonMapper(io.zeebe.client.api.JsonMapper) */
  JsonMapper getJsonMapper();
}
