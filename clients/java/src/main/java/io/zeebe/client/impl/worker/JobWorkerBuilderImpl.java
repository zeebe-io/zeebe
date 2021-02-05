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
package io.zeebe.client.impl.worker;

import static io.zeebe.client.impl.command.ArgumentUtil.ensureGreaterThan;
import static io.zeebe.client.impl.command.ArgumentUtil.ensureGreaterThanOrEqualTo;
import static io.zeebe.client.impl.command.ArgumentUtil.ensureNotNull;
import static io.zeebe.client.impl.command.ArgumentUtil.ensureNotNullNorEmpty;

import io.zeebe.client.ZeebeClientConfiguration;
import io.zeebe.client.api.JsonMapper;
import io.zeebe.client.api.worker.JobClient;
import io.zeebe.client.api.worker.JobHandler;
import io.zeebe.client.api.worker.JobWorker;
import io.zeebe.client.api.worker.JobWorkerBuilderStep1;
import io.zeebe.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep2;
import io.zeebe.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3;
import io.zeebe.gateway.protocol.GatewayGrpc.GatewayStub;
import io.zeebe.gateway.protocol.GatewayOuterClass.ActivateJobsRequest;
import io.zeebe.gateway.protocol.GatewayOuterClass.ActivateJobsRequest.Builder;
import java.io.Closeable;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Predicate;

public final class JobWorkerBuilderImpl
    implements JobWorkerBuilderStep1, JobWorkerBuilderStep2, JobWorkerBuilderStep3 {

  private static final Duration DEADLINE_OFFSET = Duration.ofSeconds(10);

  private final GatewayStub gatewayStub;
  private final JobClient jobClient;
  private final JsonMapper jsonMapper;
  private final ScheduledExecutorService executorService;
  private final List<Closeable> closeables;
  private final Predicate<Throwable> retryPredicate;
  private String jobType;
  private JobHandler handler;
  private long timeout;
  private String workerName;
  private int maxJobsActive;
  private Duration pollInterval;
  private Duration requestTimeout;
  private List<String> fetchVariables;

  private float minJobsActiveRatio;

  public JobWorkerBuilderImpl(
      final ZeebeClientConfiguration configuration,
      final GatewayStub gatewayStub,
      final JobClient jobClient,
      final JsonMapper jsonMapper,
      final ScheduledExecutorService executorService,
      final List<Closeable> closeables,
      final Predicate<Throwable> retryPredicate) {
    this.gatewayStub = gatewayStub;
    this.jobClient = jobClient;
    this.jsonMapper = jsonMapper;
    this.executorService = executorService;
    this.closeables = closeables;

    timeout = configuration.getDefaultJobTimeout().toMillis();
    workerName = configuration.getDefaultJobWorkerName();
    maxJobsActive = configuration.getDefaultJobWorkerMaxJobsActive();
    minJobsActiveRatio = configuration.getDefaultJobWorkerMinJobsActiveRatio();
    pollInterval = configuration.getDefaultJobPollInterval();
    requestTimeout = configuration.getDefaultRequestTimeout();
    this.retryPredicate = retryPredicate;
  }

  @Override
  public JobWorkerBuilderStep2 jobType(final String type) {
    jobType = type;
    return this;
  }

  @Override
  public JobWorkerBuilderStep3 handler(final JobHandler handler) {
    this.handler = handler;
    return this;
  }

  @Override
  public JobWorkerBuilderStep3 timeout(final long timeout) {
    this.timeout = timeout;
    return this;
  }

  @Override
  public JobWorkerBuilderStep3 timeout(final Duration timeout) {
    return timeout(timeout.toMillis());
  }

  @Override
  public JobWorkerBuilderStep3 name(final String workerName) {
    this.workerName = workerName;
    return this;
  }

  @Override
  public JobWorkerBuilderStep3 maxJobsActive(final int maxJobsActive) {
    this.maxJobsActive = maxJobsActive;
    return this;
  }

  @Override
  public JobWorkerBuilderStep3 minJobsActiveRatio(float minJobsActiveRatio) {
    this.minJobsActiveRatio = minJobsActiveRatio;
    return this;
  }

  @Override
  public JobWorkerBuilderStep3 pollInterval(final Duration pollInterval) {
    this.pollInterval = pollInterval;
    return this;
  }

  public JobWorkerBuilderStep3 requestTimeout(final Duration requestTimeout) {
    this.requestTimeout = requestTimeout;
    return this;
  }

  @Override
  public JobWorkerBuilderStep3 fetchVariables(final List<String> fetchVariables) {
    this.fetchVariables = fetchVariables;
    return this;
  }

  @Override
  public JobWorkerBuilderStep3 fetchVariables(final String... fetchVariables) {
    return fetchVariables(Arrays.asList(fetchVariables));
  }

  @Override
  public JobWorker open() {
    ensureNotNullNorEmpty("jobType", jobType);
    ensureNotNull("jobHandler", handler);
    ensureGreaterThan("timeout", timeout, 0L);
    ensureNotNullNorEmpty("workerName", workerName);
    ensureGreaterThan("maxJobsActive", maxJobsActive, 0);
    ensureGreaterThanOrEqualTo("minJobsActiveRatio", minJobsActiveRatio, 0.0f);

    final Builder requestBuilder =
        ActivateJobsRequest.newBuilder()
            .setType(jobType)
            .setTimeout(timeout)
            .setWorker(workerName)
            .setMaxJobsToActivate(maxJobsActive)
            .setRequestTimeout(requestTimeout.toMillis());

    if (fetchVariables != null) {
      requestBuilder.addAllFetchVariable(fetchVariables);
    }

    final Duration deadline = requestTimeout.plus(DEADLINE_OFFSET);

    final JobRunnableFactory jobRunnableFactory = new JobRunnableFactory(jobClient, handler);
    final JobPoller jobPoller =
        new JobPoller(gatewayStub, requestBuilder, jsonMapper, deadline, retryPredicate);

    final JobWorkerImpl jobWorker =
        new JobWorkerImpl(
            maxJobsActive,
            minJobsActiveRatio,
            executorService,
            pollInterval,
            jobRunnableFactory,
            jobPoller);
    closeables.add(jobWorker);
    return jobWorker;
  }
}
