package com.redblack.identity.application;

import com.redblack.identity.domain.OutboxEventEntity;
import com.redblack.identity.infrastructure.persistence.OutboxEventMapper;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxPublisherTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-01T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void marksSentOnlyAfterKafkaAcknowledges() {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        OutboxEventEntity event = event();
        when(mapper.findPending(any(), eq(50))).thenReturn(List.of(event));
        CompletableFuture<SendResult<String, String>> acknowledged = CompletableFuture.completedFuture(mock(SendResult.class));
        when(kafka.send(event.getTopic(), event.getMessageKey(), event.getPayload())).thenReturn(acknowledged);

        new OutboxPublisher(mapper, kafka, CLOCK).publishPending();

        verify(mapper).markSent(eq(event.getEventId()), any());
        verify(mapper, never()).markRetry(anyString(), any(), anyString());
    }

    @Test
    void retainsPendingEventAndSchedulesRetryWhenKafkaFails() {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        OutboxEventEntity event = event();
        when(mapper.findPending(any(), eq(50))).thenReturn(List.of(event));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("kafka unavailable"));
        when(kafka.send(event.getTopic(), event.getMessageKey(), event.getPayload())).thenReturn(failed);

        new OutboxPublisher(mapper, kafka, CLOCK).publishPending();

        verify(mapper, never()).markSent(anyString(), any());
        verify(mapper).markRetry(eq(event.getEventId()), any(), contains("kafka unavailable"));
    }

    private OutboxEventEntity event() {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setEventId("11111111-1111-1111-1111-111111111111");
        event.setTopic("redblack.identity.events.v1");
        event.setMessageKey("10001");
        event.setPayload("{}");
        event.setAttempts(0);
        return event;
    }
}
