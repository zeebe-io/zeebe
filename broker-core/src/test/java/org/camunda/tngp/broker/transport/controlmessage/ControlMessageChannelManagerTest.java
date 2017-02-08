/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.tngp.broker.transport.controlmessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.camunda.tngp.broker.test.util.BufferAssert.assertThatBuffer;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.UnsafeBuffer;
import org.camunda.tngp.broker.logstreams.BrokerEventMetadata;
import org.camunda.tngp.broker.test.util.FluentMock;
import org.camunda.tngp.broker.transport.clientapi.ErrorResponseWriter;
import org.camunda.tngp.dispatcher.Dispatcher;
import org.camunda.tngp.dispatcher.FragmentHandler;
import org.camunda.tngp.dispatcher.Subscription;
import org.camunda.tngp.protocol.clientapi.ControlMessageRequestEncoder;
import org.camunda.tngp.protocol.clientapi.ControlMessageType;
import org.camunda.tngp.protocol.clientapi.ErrorCode;
import org.camunda.tngp.util.agent.AgentRunnerService;
import org.camunda.tngp.util.time.ClockUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

public class ControlMessageChannelManagerTest
{
    private static final ControlMessageType CONTROL_MESSAGE_TYPE = ControlMessageType.TASK_SUBSCRIPTION;
    private static final byte[] CONTROL_MESSAGE_DATA = "foo".getBytes(StandardCharsets.UTF_8);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final int REQ_CHANNEL_ID = 11;
    private static final long REQ_CONNECTION_ID = 12L;
    private static final long REQ_REQUEST_ID = 13L;

    private final UnsafeBuffer requestWriteBuffer = new UnsafeBuffer(new byte[1024]);

    private final ControlMessageRequestEncoder requestEncoder = new ControlMessageRequestEncoder();

    @Mock
    private Dispatcher mockControlMessageBuffer;

    @Mock
    private Subscription mockSubscription;

    @Mock
    private AgentRunnerService mockAgentRunnerService;

    @FluentMock
    private ErrorResponseWriter mockErrorResponseWriter;

    @Mock
    private ControlMessageHandler mockControlMessageHandler;

    private ControlMessageHandlerManager manager;

    @Before
    public void init()
    {
        MockitoAnnotations.initMocks(this);

        when(mockControlMessageBuffer.getSubscriptionByName("control-message-handler")).thenReturn(mockSubscription);

        when(mockControlMessageHandler.getMessageType()).thenReturn(CONTROL_MESSAGE_TYPE);

        manager = new ControlMessageHandlerManager(
                mockControlMessageBuffer,
                mockErrorResponseWriter,
                TIMEOUT.toMillis(),
                mockAgentRunnerService,
                mockControlMessageHandler);

        // fix the current time to calculate the timeout
        ClockUtil.setCurrentTime(Instant.now());
    }

    @After
    public void cleanUp()
    {
        ClockUtil.reset();
    }

    @Test
    public void shouldGetRoleName()
    {
        assertThat(manager.roleName()).isEqualTo("control.message.handler");
    }

    @Test
    public void shouldOpen()
    {
        // given
        assertThat(manager.isOpen()).isFalse();

        // when
        final CompletableFuture<Void> future = manager.openAsync();
        manager.doWork();

        // then
        assertThat(future).isCompleted();
        assertThat(manager.isOpen()).isTrue();

        verify(mockAgentRunnerService).run(any(Agent.class));
    }

    @Test
    public void shouldNotOpenIfNotClosed()
    {
        opened();

        assertThat(manager.isOpen()).isTrue();

        // when try to open again
        final CompletableFuture<Void> future = manager.openAsync();
        manager.doWork();

        // then
        assertThat(future).isCompletedExceptionally();
        assertThat(manager.isOpen()).isTrue();

        verify(mockAgentRunnerService, times(1)).run(any(Agent.class));
    }

    @Test
    public void shouldClose()
    {
        opened();

        assertThat(manager.isClosed()).isFalse();

        // when
        final CompletableFuture<Void> future = manager.closeAsync();
        manager.doWork();

        // then
        assertThat(future).isCompleted();
        assertThat(manager.isClosed()).isTrue();

        verify(mockAgentRunnerService).remove(any(Agent.class));
    }

    @Test
    public void shouldPollControlMessageBufferIfOpen()
    {
        // given
        opened();

        // when poll messages
        manager.doWork();
        manager.doWork();

        // then
        assertThat(manager.isOpen()).isTrue();

        verify(mockSubscription, times(2)).poll(any(FragmentHandler.class), eq(1));
    }

    @Test
    public void shouldHandlePolledControlMessage()
    {
        // given
        opened();

        when(mockSubscription.poll(any(FragmentHandler.class), eq(1))).thenAnswer(pollControlMessage(CONTROL_MESSAGE_TYPE));

        // when poll a message
        manager.doWork();

        // then
        final ArgumentCaptor<DirectBuffer> bufferCaptor = ArgumentCaptor.forClass(DirectBuffer.class);
        final ArgumentCaptor<BrokerEventMetadata> metadataCaptor = ArgumentCaptor.forClass(BrokerEventMetadata.class);

        verify(mockControlMessageHandler).handle(bufferCaptor.capture(), metadataCaptor.capture());

        assertThatBuffer(bufferCaptor.getValue()).hasBytes(CONTROL_MESSAGE_DATA);

        final BrokerEventMetadata metadata = metadataCaptor.getValue();
        assertThat(metadata.getReqChannelId()).isEqualTo(REQ_CHANNEL_ID);
        assertThat(metadata.getReqConnectionId()).isEqualTo(REQ_CONNECTION_ID);
        assertThat(metadata.getReqRequestId()).isEqualTo(REQ_REQUEST_ID);
    }

    @Test
    public void shouldWaitUntilPolledControlMessageIsHandled()
    {
        // given a polled message
        opened();

        when(mockSubscription.poll(any(FragmentHandler.class), eq(1))).thenAnswer(pollControlMessage(CONTROL_MESSAGE_TYPE));

        final CompletableFuture<Void> spyFuture = spy(new CompletableFuture<Void>());
        when(mockControlMessageHandler.handle(any(DirectBuffer.class), any(BrokerEventMetadata.class))).thenReturn(spyFuture);

        manager.doWork();

        // when wait for completion
        manager.doWork();

        spyFuture.complete(null);

        // and continue polling
        manager.doWork();
        manager.doWork();

        // then
        assertThat(manager.isOpen()).isTrue();

        verify(spyFuture, times(2)).isDone();
        verify(mockSubscription, times(2)).poll(any(FragmentHandler.class), eq(1));
    }

    @Test
    public void shouldWriteErrorResponseIfHandleControlMessageTakesLongerThanTimeout()
    {
        // given a polled message
        opened();

        when(mockSubscription.poll(any(FragmentHandler.class), eq(1))).thenAnswer(pollControlMessage(CONTROL_MESSAGE_TYPE));

        final CompletableFuture<Void> spyFuture = spy(new CompletableFuture<Void>());
        when(mockControlMessageHandler.handle(any(DirectBuffer.class), any(BrokerEventMetadata.class))).thenReturn(spyFuture);

        manager.doWork();

        // when wait for completion until timeout
        manager.doWork();

        ClockUtil.setCurrentTime(ClockUtil.getCurrentTime().plus(TIMEOUT));

        // and continue polling
        manager.doWork();
        manager.doWork();

        // then
        assertThat(manager.isOpen()).isTrue();

        verify(mockErrorResponseWriter).errorCode(ErrorCode.REQUEST_TIMEOUT);
        verify(mockErrorResponseWriter).errorMessage("Timeout while handle control message.");
        verify(mockErrorResponseWriter).tryWriteResponseOrLogFailure();

        verify(spyFuture, times(2)).isDone();
        verify(mockSubscription, times(2)).poll(any(FragmentHandler.class), eq(1));
    }

    @Test
    public void shouldWriteErrorResponseIfNotSupportedMessageType()
    {
        // given
        opened();

        when(mockSubscription.poll(any(FragmentHandler.class), eq(1))).thenAnswer(pollControlMessage(ControlMessageType.SBE_UNKNOWN));

        // when handle the message
        manager.doWork();
        manager.doWork();
        // and continue polling
        manager.doWork();

        // then
        assertThat(manager.isOpen()).isTrue();

        verify(mockErrorResponseWriter).errorCode(ErrorCode.MESSAGE_NOT_SUPPORTED);
        verify(mockErrorResponseWriter).tryWriteResponseOrLogFailure();

        verify(mockSubscription, times(2)).poll(any(FragmentHandler.class), eq(1));
    }

    private void opened()
    {
        manager.openAsync();
        manager.doWork();
    }

    private Answer<?> pollControlMessage(ControlMessageType type)
    {
        return invocation ->
        {
            final FragmentHandler fragmentHandler = (FragmentHandler) invocation.getArguments()[0];

            requestEncoder.wrap(requestWriteBuffer, 0);

            requestEncoder
                .messageType(type)
                .reqChannelId(REQ_CHANNEL_ID)
                .reqConnectionId(REQ_CONNECTION_ID)
                .reqRequestId(REQ_REQUEST_ID)
                .putData(CONTROL_MESSAGE_DATA, 0, CONTROL_MESSAGE_DATA.length);

            fragmentHandler.onFragment(requestWriteBuffer, 0, requestWriteBuffer.capacity(), 0, false);

            return 1;
        };
    }
}
