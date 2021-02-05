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
package io.zeebe.client.api.worker;

import io.zeebe.client.ZeebeClientConfiguration;
import java.time.Duration;
import java.util.List;

public interface JobWorkerBuilderStep1 {
  /**
   * Set the type of jobs to work on.
   *
   * @param type the type of jobs (e.g. "payment")
   * @return the builder for this worker
   */
  JobWorkerBuilderStep2 jobType(String type);

  interface JobWorkerBuilderStep2 {
    /**
     * Set the handler to process the jobs. At the end of the processing, the handler should
     * complete the job or mark it as failed;
     *
     * <p>Example JobHandler implementation:
     *
     * <pre>
     * public final class PaymentHandler implements JobHandler
     * {
     *   &#64;Override
     *   public void handle(JobClient client, JobEvent jobEvent)
     *   {
     *     String json = jobEvent.getVariables();
     *     // modify variables
     *
     *     client
     *      .newCompleteCommand(jobEvent.getKey())
     *      .variables(json)
     *      .send();
     *   }
     * };
     * </pre>
     *
     * The handler must be thread-safe.
     *
     * @param handler the handle to process the jobs
     * @return the builder for this worker
     */
    JobWorkerBuilderStep3 handler(JobHandler handler);
  }

  interface JobWorkerBuilderStep3 {
    /**
     * Set the time for how long a job is exclusively assigned for this worker.
     *
     * <p>In this time, the job can not be assigned by other workers to ensure that only one worker
     * work on the job. When the time is over then the job can be assigned again by this or other
     * worker if it's not completed yet.
     *
     * <p>If no time is set, the default from {@link
     * ZeebeClientConfiguration#getDefaultJobTimeout()} is used.
     *
     * @param timeout the time in milliseconds
     * @return the builder for this worker
     */
    JobWorkerBuilderStep3 timeout(long timeout);

    /**
     * Set the time for how long a job is exclusively assigned for this worker.
     *
     * <p>In this time, the job can not be assigned by other workers to ensure that only one worker
     * work on the job. When the time is over then the job can be assigned again by this or other
     * worker if it's not completed yet.
     *
     * <p>If no time is set, the default from {@link
     * ZeebeClientConfiguration#getDefaultJobTimeout()} is used.
     *
     * @param timeout the time as duration (e.g. "Duration.ofMinutes(5)")
     * @return the builder for this worker
     */
    JobWorkerBuilderStep3 timeout(Duration timeout);

    /**
     * Set the name of the worker owner.
     *
     * <p>This name is used to identify the worker to which a job is exclusively assigned to.
     *
     * <p>If no name is set, the default from {@link
     * ZeebeClientConfiguration#getDefaultJobWorkerName()} is used.
     *
     * @param workerName the name of the worker (e.g. "payment-service")
     * @return the builder for this worker
     */
    JobWorkerBuilderStep3 name(String workerName);

    /**
     * Set the maximum number of jobs which will be exclusively activated for this worker at the
     * same time.
     *
     * <p>This is used to control the backpressure of the worker. When the maximum is reached then
     * the worker will stop activating new jobs in order to not overwhelm the client and give other
     * workers the chance to work on the jobs. The worker will try to activate new jobs again when
     * jobs are completed (or marked as failed).
     *
     * <p>If no maximum is set, the default from {@link
     * ZeebeClientConfiguration#getDefaultJobWorkerMaxJobsActive()} is used.
     *
     * <p>Considerations:
     *
     * <ul>
     *   <li>A greater value can avoid situations in which the client waits idle for the broker to
     *       provide more jobs. This can improve the worker's throughput.
     *   <li>The memory used by the worker is linear with respect to this value.
     *   <li>The job's timeout starts to run down as soon as the broker pushes the job. Keep in mind
     *       that the following must hold to ensure fluent job handling: <code>
     *       time spent in queue + time job handler needs until job completion < job timeout</code>
     *
     * @param maxJobsActive the maximum jobs active by this worker
     * @return the builder for this worker
     */
    JobWorkerBuilderStep3 maxJobsActive(int maxJobsActive);

    /**
     * Set the minimum number of jobs as a ratio of maxJobsActive.
     *
     * <p>When the number of active jobs drop below this threshold more jobs will be requested from
     * the gateway. As long as more jobs are active, the job activation will be suspended. For
     * instance, if maxJobsActive is 30 and minJobsActiveRatio is 0.3 the job activation will pause
     * until less than 10 jobs are remaining.
     *
     * <p>If no minimum is set, the default from {@link
     * ZeebeClientConfiguration#getDefaultJobWorkerMinJobsActiveRatio()} is used.
     *
     * @param minJobsActiveRatio
     * @return the builder for this worker
     */
    JobWorkerBuilderStep3 minJobsActiveRatio(float minJobsActiveRatio);

    /**
     * Set the maximal interval between polling for new jobs.
     *
     * <p>A job worker will automatically try to always activate new jobs after completing jobs. If
     * no jobs can be activated after completing the worker will periodically poll for new jobs.
     *
     * <p>If no poll interval is set, the default from {@link
     * ZeebeClientConfiguration#getDefaultJobPollInterval()} is used.
     *
     * @param pollInterval the maximal interval to check for new jobs
     * @return the builder for this worker
     */
    JobWorkerBuilderStep3 pollInterval(Duration pollInterval);

    /**
     * Set the request timeout for activate job request used to poll for new job.
     *
     * <p>If no request timeout is set, the default from {@link
     * ZeebeClientConfiguration#getDefaultRequestTimeout()} is used.
     *
     * @param requestTimeout the request timeout for activate jobs request
     * @return the builder for this worker
     */
    JobWorkerBuilderStep3 requestTimeout(Duration requestTimeout);

    /**
     * Set a list of variable names which should be fetch on job activation.
     *
     * <p>The jobs which are activated by this worker will only contain variables from this list.
     *
     * <p>This can be used to limit the number of variables of the activated jobs.
     *
     * @param fetchVariables list of variables names to fetch on activation
     * @return the builder for this worker
     */
    JobWorkerBuilderStep3 fetchVariables(List<String> fetchVariables);

    /**
     * Set a list of variable names which should be fetch on job activation.
     *
     * <p>The jobs which are activated by this worker will only contain variables from this list.
     *
     * <p>This can be used to limit the number of variables of the activated jobs.
     *
     * @param fetchVariables list of variables names to fetch on activation
     * @return the builder for this worker
     */
    JobWorkerBuilderStep3 fetchVariables(String... fetchVariables);

    /**
     * Open the worker and start to work on available tasks.
     *
     * @return the worker
     */
    JobWorker open();
  }
}
