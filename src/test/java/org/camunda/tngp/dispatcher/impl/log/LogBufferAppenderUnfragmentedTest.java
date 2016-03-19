package org.camunda.tngp.dispatcher.impl.log;

import org.camunda.tngp.dispatcher.impl.log.LogBufferAppender;
import org.camunda.tngp.dispatcher.impl.log.LogBufferPartition;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;

import static org.mockito.Mockito.*;
import static uk.co.real_logic.agrona.BitUtil.*;
import static org.assertj.core.api.Assertions.*;
import static org.camunda.tngp.dispatcher.impl.log.DataFrameDescriptor.*;
import static org.camunda.tngp.dispatcher.impl.log.LogBufferDescriptor.*;

import java.nio.charset.Charset;

public class LogBufferAppenderUnfragmentedTest
{
    static final int A_PARTITION_LENGTH = 1024;
    static final byte[] A_MSG_PAYLOAD = "some bytes".getBytes(Charset.forName("utf-8"));
    static final int A_MSG_PAYLOAD_LENGTH = A_MSG_PAYLOAD.length;
    static final int A_FRAGMENT_LENGTH = align(A_MSG_PAYLOAD_LENGTH + HEADER_LENGTH, FRAME_ALIGNMENT);
    static final UnsafeBuffer A_MSG = new UnsafeBuffer(A_MSG_PAYLOAD);
    static final int A_PARTITION_ID = 10;
    static final int A_STREAM_ID = 20;

    UnsafeBuffer metadataBufferMock;
    UnsafeBuffer dataBufferMock;
    LogBufferAppender logBufferAppender;
    LogBufferPartition logBufferPartition;

    @Before
    public void setup()
    {
        dataBufferMock = mock(UnsafeBuffer.class);
        metadataBufferMock = mock(UnsafeBuffer.class);

        when(dataBufferMock.capacity()).thenReturn(A_PARTITION_LENGTH);
        logBufferPartition = new LogBufferPartition(dataBufferMock, metadataBufferMock, null, 0);
        verify(dataBufferMock).verifyAlignment();
        verify(metadataBufferMock).verifyAlignment();

        logBufferAppender = new LogBufferAppender();
    }

    @Test
    public void shouldAppendFrame()
    {
        // given
        // that the message + next message header fit into the buffer and there is more space
        final int currentTail = 0;

        when(metadataBufferMock.getAndAddInt(PARTITION_TAIL_COUNTER_OFFSET, A_FRAGMENT_LENGTH)).thenReturn(currentTail);

        // if
        final int newTail = logBufferAppender.appendFrame(logBufferPartition, A_PARTITION_ID, A_MSG, 0, A_MSG_PAYLOAD_LENGTH, A_STREAM_ID);

        // then
        assertThat(newTail).isEqualTo(currentTail + A_FRAGMENT_LENGTH);

        // the tail is moved by the aligned message length
        verify(metadataBufferMock).getAndAddInt(PARTITION_TAIL_COUNTER_OFFSET, A_FRAGMENT_LENGTH);
        verifyNoMoreInteractions(metadataBufferMock);

        // and the message is appended to the buffer
        InOrder inOrder = inOrder(dataBufferMock);
        inOrder.verify(dataBufferMock).putIntOrdered(currentTail, -A_MSG_PAYLOAD_LENGTH);
        inOrder.verify(dataBufferMock).putShort(typeOffset(currentTail), TYPE_MESSAGE);
        inOrder.verify(dataBufferMock).putInt(streamIdOffset(currentTail), A_STREAM_ID);
        inOrder.verify(dataBufferMock).putBytes(messageOffset(currentTail), A_MSG, 0, A_MSG_PAYLOAD_LENGTH);
        inOrder.verify(dataBufferMock).putIntOrdered(lengthOffset(currentTail), A_MSG_PAYLOAD_LENGTH);
    }

    @Test
    public void shouldAppendFrameIfRemaingCapacityIsEqualHeaderSize()
    {
        // given
        // that the message + next message header EXACTLY fit into the buffer
        final int currentTail = A_PARTITION_LENGTH - HEADER_LENGTH - A_FRAGMENT_LENGTH;

        when(metadataBufferMock.getAndAddInt(PARTITION_TAIL_COUNTER_OFFSET, A_FRAGMENT_LENGTH)).thenReturn(currentTail);

        // if
        final int newTail = logBufferAppender.appendFrame(logBufferPartition, A_PARTITION_ID, A_MSG, 0, A_MSG_PAYLOAD_LENGTH, A_STREAM_ID);

        // then
        assertThat(newTail).isEqualTo(currentTail + A_FRAGMENT_LENGTH);

        // the tail is moved by the aligned message length
        verify(metadataBufferMock).getAndAddInt(PARTITION_TAIL_COUNTER_OFFSET, A_FRAGMENT_LENGTH);
        verifyNoMoreInteractions(metadataBufferMock);

        // and the message is appended to the buffer
        InOrder inOrder = inOrder(dataBufferMock);
        inOrder.verify(dataBufferMock).putIntOrdered(currentTail, -A_MSG_PAYLOAD_LENGTH);
        inOrder.verify(dataBufferMock).putShort(typeOffset(currentTail), TYPE_MESSAGE);
        inOrder.verify(dataBufferMock).putInt(streamIdOffset(currentTail), A_STREAM_ID);
        inOrder.verify(dataBufferMock).putIntOrdered(lengthOffset(currentTail), A_MSG_PAYLOAD_LENGTH);
    }

    @Test
    public void shouldRejectAndFillWithPaddingIfTrippsEndOfBuffer()
    {
        // given
        // that the message + next message header do NOT fit into the buffer
        final int currentTail = A_PARTITION_LENGTH - HEADER_LENGTH - A_FRAGMENT_LENGTH +1;

        when(metadataBufferMock.getAndAddInt(PARTITION_TAIL_COUNTER_OFFSET, A_FRAGMENT_LENGTH)).thenReturn(currentTail);

        // if
        final int newTail = logBufferAppender.appendFrame(logBufferPartition, A_PARTITION_ID, A_MSG, 0, A_MSG_PAYLOAD_LENGTH, A_STREAM_ID);

        // then
        assertThat(newTail).isEqualTo(-2);

        // the tail is moved by the aligned message length
        verify(metadataBufferMock).getAndAddInt(PARTITION_TAIL_COUNTER_OFFSET, A_FRAGMENT_LENGTH);
        verifyNoMoreInteractions(metadataBufferMock);

        // and the buffer is filled with padding
        final int padLength = A_PARTITION_LENGTH - currentTail - HEADER_LENGTH;
        InOrder inOrder = inOrder(dataBufferMock);
        inOrder.verify(dataBufferMock).putIntOrdered(currentTail, -padLength);
        inOrder.verify(dataBufferMock).putShort(typeOffset(currentTail), TYPE_PADDING);
        inOrder.verify(dataBufferMock).putIntOrdered(lengthOffset(currentTail), padLength);
    }

    @Test
    public void shouldRejectAndFillWithZeroLengthPaddingIfExactlyHitsTrippPoint()
    {
        // given
        // that the current tail is that we exactly hit the trip point (ie. only a zero-length padding header fits the buffer)
        final int currentTail = A_PARTITION_LENGTH - HEADER_LENGTH;

        when(metadataBufferMock.getAndAddInt(PARTITION_TAIL_COUNTER_OFFSET, A_FRAGMENT_LENGTH)).thenReturn(currentTail);

        // if
        final int newTail = logBufferAppender.appendFrame(logBufferPartition, A_PARTITION_ID, A_MSG, 0, A_MSG_PAYLOAD_LENGTH, A_STREAM_ID);

        // then
        assertThat(newTail).isEqualTo(-2);

        // the tail is moved by the aligned message length
        verify(metadataBufferMock).getAndAddInt(PARTITION_TAIL_COUNTER_OFFSET, A_FRAGMENT_LENGTH);
        verifyNoMoreInteractions(metadataBufferMock);

        // and the buffer is filled with padding
        final int padLength = 0;
        InOrder inOrder = inOrder(dataBufferMock);
        inOrder.verify(dataBufferMock).putIntOrdered(currentTail, -padLength);
        inOrder.verify(dataBufferMock).putShort(typeOffset(currentTail), TYPE_PADDING);
        inOrder.verify(dataBufferMock).putIntOrdered(lengthOffset(currentTail), padLength);
    }

    @Test
    public void shouldRejectIfTailIsBeyondTripPoint()
    {
        // given
        // that the tail is beyond the trip point
        final int currentTail = A_PARTITION_LENGTH - HEADER_LENGTH + 1;

        when(metadataBufferMock.getAndAddInt(PARTITION_TAIL_COUNTER_OFFSET, A_FRAGMENT_LENGTH)).thenReturn(currentTail);

        // if
        final int newTail = logBufferAppender.appendFrame(logBufferPartition, A_PARTITION_ID, A_MSG, 0, A_MSG_PAYLOAD_LENGTH, A_STREAM_ID);

        // then
        assertThat(newTail).isEqualTo(-1);

        // the tail is moved by the aligned message length
        verify(metadataBufferMock).getAndAddInt(PARTITION_TAIL_COUNTER_OFFSET, A_FRAGMENT_LENGTH);
        verifyNoMoreInteractions(metadataBufferMock);

        // and no message / padding is written
        verify(dataBufferMock, times(0)).putIntOrdered(anyInt(), anyInt());
    }

}
