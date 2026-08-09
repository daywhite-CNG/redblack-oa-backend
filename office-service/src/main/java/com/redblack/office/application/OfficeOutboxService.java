package com.redblack.office.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redblack.common.event.EventActor;
import com.redblack.common.event.EventEnvelope;
import com.redblack.office.domain.NoticeEntity;
import com.redblack.office.domain.OutboxEventEntity;
import com.redblack.office.infrastructure.persistence.OutboxEventMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OfficeOutboxService {
    private final OutboxEventMapper mapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String officeTopic;
    private final String auditTopic;

    public OfficeOutboxService(OutboxEventMapper mapper, ObjectMapper objectMapper, Clock clock,
                               @Value("${redblack.events.office-topic}") String officeTopic,
                               @Value("${redblack.events.audit-topic}") String auditTopic) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.officeTopic = officeTopic;
        this.auditTopic = auditTopic;
    }

    public void notice(String eventType, NoticeEntity notice, String actorId, String requestId,
                       List<Long> departmentIds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("noticeId", notice.getId().toString());
        payload.put("title", notice.getTitle());
        payload.put("summary", notice.getSummary());
        payload.put("scopeType", notice.getScopeType());
        payload.put("targetDepartmentIds", departmentIds.stream().map(String::valueOf).toList());
        payload.put("publisherId", notice.getPublisherId().toString());
        append(officeTopic, eventType, "NOTICE", notice.getId().toString(), actorId, requestId, payload);
    }

    public void audit(String action, String resourceType, String resourceId, String actorId,
                      String actorName, String requestId, String method, String path) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("resourceType", resourceType);
        payload.put("resourceId", resourceId);
        payload.put("operatorName", actorName);
        payload.put("requestMethod", method);
        payload.put("requestPath", path);
        payload.put("result", "SUCCESS");
        append(auditTopic, "OPERATION_AUDIT_REQUESTED", resourceType, resourceId, actorId, requestId, payload);
    }

    private void append(String topic, String type, String aggregateType, String aggregateId, String actorId,
                        String requestId, Map<String, ?> payload) {
        try {
            UUID eventId = UUID.randomUUID();
            OffsetDateTime occurredAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            var envelope = new EventEnvelope<>(eventId, type, 1, occurredAt, "office-service", aggregateType,
                    aggregateId, requestId, requestId, new EventActor(actorId), payload);
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
            throw new IllegalStateException("Unable to append office outbox event", exception);
        }
    }
}
