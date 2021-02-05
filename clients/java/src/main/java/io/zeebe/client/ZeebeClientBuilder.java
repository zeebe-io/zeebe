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
import io.zeebe.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3;
import java.time.Duration;
import java.util.Properties;

public interface ZeebeClientBuilder {

  /**
   * Sets all the properties from a {@link Properties} object. Can be used to configure the client
   * from a properties file.
   *
   * <p>See {@link ClientProperties} for valid property names.
   */
  ZeebeClientBuilder withProperties(Properties properties);

  /**
   * @param contactPoint the IP socket address of a broker that the client can initially connect to.
   *     Must be in format <code>host:port</code>. The default value is <code>0.0.0.0:26500</code> .
   * @deprecated Use {@link #gatewayAddress(java.lang.String)}. It's deprecated since 0.25.0, and
   *     will be removed in 0.26.0
   */
  @Deprecated
  ZeebeClientBuilder brokerContactPoint(String contactPoint);

  /**
   * @param gatewayAddress the IP socket address of a gateway that the client can initially connect
   *     to. Must be in format <code>host:port</code>. The default value is <code>0.0.0.0:26500
   *     </code> .
   */
  ZeebeClientBuilder gatewayAddress(String gatewayAddress);

  /**
   * @param maxJobsActive Default value for {@link JobWorkerBuilderStep3#maxJobsActive(int)}.
   *     Default value is 32.
   */
  ZeebeClientBuilder defaultJobWorkerMaxJobsActive(int maxJobsActive);

  /**
   * @param minJobsActiveRatio Default value for {@link
   *     JobWorkerBuilderStep3#minJobsActiveRatio(float)}. Default value is 0.3.
   */
  ZeebeClientBuilder defaultJobWorkerMinJobsActiveRatio(float minJobsActiveRatio);

  /**
   * @param numThreads The number of threads for invocation of job workers. Setting this value to 0
   *     effectively disables subscriptions and workers. Default value is 1.
   */
  ZeebeClientBuilder numJobWorkerExecutionThreads(int numThreads);

  /**
   * The name of the worker which is used when none is set for a job worker. Default is 'default'.
   */
  ZeebeClientBuilder defaultJobWorkerName(String workerName);

  /** The timeout which is used when none is provided for a job worker. Default is 5 minutes. */
  ZeebeClientBuilder defaultJobTimeout(Duration timeout);

  /**
   * The interval which a job worker is periodically polling for new jobs. Default is 100
   * milliseconds.
   */
  ZeebeClientBuilder defaultJobPollInterval(Duration pollInterval);

  /** The time-to-live which is used when none is provided for a message. Default is 1 hour. */
  ZeebeClientBuilder defaultMessageTimeToLive(Duration timeToLive);

  /** The request timeout used if not overridden by the command. Default is 20 seconds. */
  ZeebeClientBuilder defaultRequestTimeout(Duration requestTimeout);

  /** Use a plaintext connection between the client and the gateway. */
  ZeebeClientBuilder usePlaintext();

  /**
   * Path to a root CA certificate to be used instead of the certificate in the default default
   * store.
   */
  ZeebeClientBuilder caCertificatePath(String certificatePath);

  /**
   * A custom {@link CredentialsProvider} which will be used to apply authentication credentials to
   * requests.
   */
  ZeebeClientBuilder credentialsProvider(CredentialsProvider credentialsProvider);

  /** Time interval between keep alive messages sent to the gateway. The default is 45 seconds. */
  ZeebeClientBuilder keepAlive(Duration keepAlive);

  ZeebeClientBuilder withInterceptors(ClientInterceptor... interceptor);

  ZeebeClientBuilder withJsonMapper(JsonMapper jsonMapper);

  /** @return a new {@link ZeebeClient} with the provided configuration options. */
  ZeebeClient build();
}
