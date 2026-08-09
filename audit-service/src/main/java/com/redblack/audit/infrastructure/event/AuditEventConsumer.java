package com.redblack.audit.infrastructure.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redblack.audit.domain.OperationLogEntity;
import com.redblack.audit.infrastructure.persistence.InboxEventMapper;
import com.redblack.audit.infrastructure.persistence.OperationLogMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

@Component
public class AuditEventConsumer {
    private final ObjectMapper objectMapper;
    private final InboxEventMapper inbox;
    private final OperationLogMapper logs;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AuditEventConsumer(ObjectMapper objectMapper, InboxEventMapper inbox, OperationLogMapper logs,
                              KafkaTemplate<String, String> kafka, TransactionTemplate transactions, Clock clock) {
        this.objectMapper = objectMapper;
        this.inbox = inbox;
        this.logs = logs;
        this.kafka = kafka;
        this.transactions = transactions;
        this.clock = clock;
    }

    @KafkaListener(topics = "${redblack.events.audit-topic}")
    public void consume(String message, @Header("kafka_receivedTopic") String topic) {
        JsonNode event = parse(message);
        String eventId = required(event, "eventId");
        String eventType = required(event, "eventType");
        LocalDateTime now = now();
        inbox.receive(eventId, topic, eventType, message, now);
        InboxEventMapper.State state = inbox.state(eventId);
        if (state == null || "PROCESSED".equals(state.status()) || "DEAD".equals(state.status())) return;
        if (state.attempts() >= 3) { deadLetter(topic, eventId, message, "retry limit reached"); return; }
        try {
            transactions.executeWithoutResult(status -> {
                if ("OPERATION_AUDIT_REQUESTED".equals(eventType)) logs.insert(log(event, eventId, now));
                inbox.processed(eventId, now());
            });
        } catch (RuntimeException exception) {
            String error = error(exception);
            inbox.failed(eventId, now.plus(Duration.ofSeconds(1L << Math.min(state.attempts(), 4))), error);
            InboxEventMapper.State failed = inbox.state(eventId);
            if (failed != null && failed.attempts() >= 3) { deadLetter(topic, eventId, message, error); return; }
            throw exception;
        }
    }

    private OperationLogEntity log(JsonNode event, String eventId, LocalDateTime fallback) {
        JsonNode payload = event.path("payload");
        String actorId = event.path("actor").path("userId").asText("0");
        OperationLogEntity entity = new OperationLogEntity();
        entity.setSourceEventId(eventId);
        entity.setModuleName(module(event.path("producer").asText("unknown")));
        entity.setOperationType(value(payload, "action", event.path("eventType").asText("UNKNOWN")));
        entity.setOperatorId(parseLong(actorId, 0L));
        entity.setOperatorName(value(payload, "operatorName", "用户#" + actorId));
        String departmentId = text(payload, "operatorDepartmentId");
        entity.setOperatorDepartmentId(departmentId == null ? null : parseLong(departmentId, null));
        entity.setRequestMethod(value(payload, "requestMethod", "EVENT"));
        entity.setRequestPath(value(payload, "requestPath", "/events/" + event.path("eventType").asText("unknown")));
        entity.setIpAddress(value(payload, "ipAddress", "0.0.0.0"));
        entity.setOperationResult(value(payload, "result", "SUCCESS"));
        entity.setBusinessType(text(payload, "resourceType"));
        entity.setBusinessId(text(payload, "resourceId"));
        entity.setSummary(value(payload, "summary", entity.getOperationType()));
        entity.setErrorCode(text(payload, "errorCode"));
        entity.setDurationMs(payload.path("durationMs").asLong(0));
        entity.setOperatedAt(time(event.get("occurredAt"), fallback));
        entity.setRequestId(event.path("requestId").asText("event_" + eventId));
        entity.setCreatedAt(fallback);
        return entity;
    }

    private String module(String producer) {
        return producer.endsWith("-service")
                ? producer.substring(0, producer.length() - "-service".length()).toUpperCase()
                : producer.toUpperCase();
    }
    private void deadLetter(String sourceTopic, String eventId, String message, String error) {
        try { kafka.send(sourceTopic + ".dlq", eventId, message).get(5, TimeUnit.SECONDS); inbox.dead(eventId, error); }
        catch (Exception exception) { throw new IllegalStateException("Unable to publish audit dead letter", exception); }
    }
    private JsonNode parse(String value) {
        try { return objectMapper.readTree(value); }
        catch (Exception exception) { throw new IllegalArgumentException("Invalid event JSON", exception); }
    }
    private String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing event field " + field);
        return value;
    }
    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
    private String value(JsonNode node, String field, String fallback) {
        String value = text(node, field); return value == null || value.isBlank() ? fallback : value;
    }
    private Long parseLong(String value, Long fallback) {
        try { return Long.parseLong(value); } catch (RuntimeException exception) { return fallback; }
    }
    private LocalDateTime time(JsonNode node, LocalDateTime fallback) {
        if (node == null || node.isNull() || node.asText().isBlank()) return fallback;
        try { return OffsetDateTime.parse(node.asText()).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(); }
        catch (RuntimeException ignored) { return LocalDateTime.parse(node.asText()); }
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private String error(Throwable exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return message.substring(0, Math.min(message.length(), 1000));
    }
}
