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

import java.time.Duration;

public final class ClientProperties {
  /**
   * @see ZeebeClientBuilder#brokerContactPoint(String)
   * @deprecated Use {@link #GATEWAY_ADDRESS}. It's deprecated since 0.25.0, and will be removed in
   *     0.26.0
   */
  @Deprecated public static final String BROKER_CONTACTPOINT = "zeebe.client.broker.contactPoint";

  /** @see ZeebeClientBuilder#gatewayAddress(String) */
  public static final String GATEWAY_ADDRESS = "zeebe.client.gateway.address";

  /** @see ZeebeClientBuilder#numJobWorkerExecutionThreads(int) */
  public static final String JOB_WORKER_EXECUTION_THREADS = "zeebe.client.worker.threads";

  /** @see ZeebeClientBuilder#defaultJobWorkerMaxJobsActive(int) */
  public static final String JOB_WORKER_MAX_JOBS_ACTIVE = "zeebe.client.worker.maxJobsActive";

  /** @see ZeebeClientBuilder#defaultJobWorkerMinJobsActiveRatio(float) */
  public static final String JOB_WORKER_MIN_JOBS_ACTIVE_RATIO =
      "zeebe.client.worker.minJobsActiveRatio";

  /** @see ZeebeClientBuilder#defaultJobWorkerName(String) */
  public static final String DEFAULT_JOB_WORKER_NAME = "zeebe.client.worker.name";

  /** @see ZeebeClientBuilder#defaultJobTimeout(java.time.Duration) */
  public static final String DEFAULT_JOB_TIMEOUT = "zeebe.client.job.timeout";

  /** @see ZeebeClientBuilder#defaultJobPollInterval(Duration) */
  public static final String DEFAULT_JOB_POLL_INTERVAL = "zeebe.client.job.pollinterval";

  /** @see ZeebeClientBuilder#defaultMessageTimeToLive(java.time.Duration) */
  public static final String DEFAULT_MESSAGE_TIME_TO_LIVE = "zeebe.client.message.timeToLive";

  /** @see ZeebeClientBuilder#defaultRequestTimeout(Duration) */
  public static final String DEFAULT_REQUEST_TIMEOUT = "zeebe.client.requestTimeout";

  /** @see ZeebeClientBuilder#usePlaintext() */
  public static final String USE_PLAINTEXT_CONNECTION = "zeebe.client.security.plaintext";

  /** @see ZeebeClientBuilder#caCertificatePath(String) */
  public static final String CA_CERTIFICATE_PATH = "zeebe.client.security.certpath";

  /** @see io.zeebe.client.ZeebeClientBuilder#keepAlive(Duration) */
  public static final String KEEP_ALIVE = "zeebe.client.keepalive";
}
