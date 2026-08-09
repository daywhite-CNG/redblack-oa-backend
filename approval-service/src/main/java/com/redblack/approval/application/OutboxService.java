package com.redblack.approval.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redblack.approval.domain.OutboxEventEntity;
import com.redblack.approval.infrastructure.persistence.OutboxEventMapper;
import com.redblack.common.event.EventActor;
import com.redblack.common.event.EventEnvelope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@Service
public class OutboxService {
    private final OutboxEventMapper mapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String approvalTopic;
    private final String auditTopic;

    public OutboxService(OutboxEventMapper mapper,
                         ObjectMapper objectMapper,
                         Clock clock,
                         @Value("${redblack.outbox.topic}") String approvalTopic,
                         @Value("${redblack.outbox.audit-topic}") String auditTopic) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.approvalTopic = approvalTopic;
        this.auditTopic = auditTopic;
    }

    public void approval(String type,
                         String applicationId,
                         String actorId,
                         String requestId,
                         Map<String, ?> payload) {
        append(approvalTopic, type, "LEAVE_APPLICATION", applicationId, actorId, requestId, payload);
    }

    public void audit(String action,
                      String aggregateType,
                      String aggregateId,
                      String actorId,
                      String requestId) {
        append(auditTopic, "OPERATION_AUDIT_REQUESTED", aggregateType, aggregateId, actorId, requestId,
                Map.of("action", action, "resourceType", aggregateType, "resourceId", aggregateId));
    }

    private void append(String topic,
                        String type,
                        String aggregateType,
                        String aggregateId,
                        String actorId,
                        String requestId,
                        Map<String, ?> payload) {
        try {
            UUID eventId = UUID.randomUUID();
            OffsetDateTime occurredAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            EventEnvelope<Map<String, ?>> envelope = new EventEnvelope<>(eventId, type, 1, occurredAt,
                    "approval-service", aggregateType, aggregateId, requestId, requestId,
                    new EventActor(actorId), payload);
            OutboxEventEntity entity = new OutboxEventEntity();
            entity.setEventId(eventId.toString());
            entity.setTopic(topic);
            entity.setEventType(type);
            entity.setAggregateType(aggregateType);
            entity.setAggregateId(aggregateId);
            entity.setMessageKey(aggregateId);
            entity.setPayload(objectMapper.writeValueAsString(envelope));
            entity.setStatus("PENDING");
            entity.setAttempts(0);
            entity.setNextAttemptAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            entity.setCreatedAt(entity.getNextAttemptAt());
            mapper.insert(entity);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to append approval outbox event", exception);
        }
    }
}
