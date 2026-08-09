package com.redblack.office.infrastructure.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redblack.office.domain.NotificationEntity;
import com.redblack.office.domain.WorkbenchApplicationEntity;
import com.redblack.office.domain.WorkbenchTaskEntity;
import com.redblack.office.infrastructure.identity.IdentityClient;
import com.redblack.office.application.InternalFileApplicationService;
import com.redblack.office.infrastructure.persistence.InboxEventMapper;
import com.redblack.office.infrastructure.persistence.NotificationMapper;
import com.redblack.office.infrastructure.persistence.WorkbenchApplicationMapper;
import com.redblack.office.infrastructure.persistence.WorkbenchTaskMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OfficeEventConsumer {
    private static final String CACHE_PREFIX = "redblack:office:workbench:";
    private final ObjectMapper objectMapper;
    private final InboxEventMapper inbox;
    private final WorkbenchApplicationMapper applications;
    private final WorkbenchTaskMapper tasks;
    private final NotificationMapper notifications;
    private final IdentityClient identity;
    private final InternalFileApplicationService fileBindings;
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public OfficeEventConsumer(ObjectMapper objectMapper, InboxEventMapper inbox,
                               WorkbenchApplicationMapper applications, WorkbenchTaskMapper tasks,
                               NotificationMapper notifications, IdentityClient identity,
                               InternalFileApplicationService fileBindings,
                               StringRedisTemplate redis, KafkaTemplate<String, String> kafka,
                               TransactionTemplate transactions, Clock clock) {
        this.objectMapper = objectMapper;
        this.inbox = inbox;
        this.applications = applications;
        this.tasks = tasks;
        this.notifications = notifications;
        this.identity = identity;
        this.fileBindings = fileBindings;
        this.redis = redis;
        this.kafka = kafka;
        this.transactions = transactions;
        this.clock = clock;
    }

    @KafkaListener(topics = {"${redblack.events.approval-topic}", "${redblack.events.office-topic}"})
    public void consume(String message, @Header("kafka_receivedTopic") String topic) {
        JsonNode event = parse(message);
        String eventId = required(event, "eventId");
        String eventType = required(event, "eventType");
        LocalDateTime now = now();
        inbox.receive(eventId, topic, eventType, message, now);
        InboxEventMapper.State state = inbox.state(eventId);
        if (state == null || "PROCESSED".equals(state.status()) || "DEAD".equals(state.status())) return;
        if (state.attempts() >= 3) {
            deadLetter(topic, eventId, message, "retry limit reached");
            return;
        }
        try {
            transactions.executeWithoutResult(status -> {
                process(event, eventId, eventType);
                inbox.markProcessed(eventId, now());
            });
        } catch (RuntimeException exception) {
            String error = error(exception);
            inbox.markFailure(eventId, now.plus(Duration.ofSeconds(1L << Math.min(state.attempts(), 4))), error);
            InboxEventMapper.State failed = inbox.state(eventId);
            if (failed != null && failed.attempts() >= 3) {
                deadLetter(topic, eventId, message, error);
                return;
            }
            throw exception;
        }
    }

    private void process(JsonNode event, String eventId, String eventType) {
        JsonNode payload = event.path("payload");
        if (!payload.hasNonNull("applicationId") && event.path("aggregateType").asText().equals("LEAVE_APPLICATION")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("applicationId", event.path("aggregateId").asText());
        }
        LocalDateTime occurredAt = time(event.path("occurredAt"), now());
        switch (eventType) {
            case "LEAVE_SUBMITTED" -> submitted(payload, eventId, occurredAt);
            case "LEAVE_APPROVED" -> completed(payload, eventId, occurredAt, "APPROVED", "APPROVAL_APPROVED", "请假申请已通过");
            case "LEAVE_REJECTED" -> completed(payload, eventId, occurredAt, "REJECTED", "APPROVAL_REJECTED", "请假申请已驳回");
            case "LEAVE_WITHDRAWN" -> completed(payload, eventId, occurredAt, "WITHDRAWN", "APPLICATION_WITHDRAWN", "请假申请已撤回");
            case "TASK_TRANSFERRED" -> transferred(payload, eventId, occurredAt);
            case "LEAVE_ATTACHMENTS_CHANGED" -> attachmentsChanged(payload);
            case "NOTICE_PUBLISHED" -> noticePublished(event, payload, eventId, occurredAt);
            case "NOTICE_WITHDRAWN" -> { }
            default -> { }
        }
    }

    private void attachmentsChanged(JsonNode payload) {
        List<String> values = objectMapper.convertValue(payload.path("fileIds"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        fileBindings.confirm(required(payload, "reservationId"), id(payload, "ownerId"),
                values.stream().map(Long::parseLong).toList(), "LEAVE_APPLICATION", id(payload, "applicationId"));
    }

    private void submitted(JsonNode payload, String eventId, LocalDateTime occurredAt) {
        WorkbenchApplicationEntity application = application(payload, occurredAt);
        applications.upsert(application);
        long taskId = id(payload, "taskId");
        long assigneeId = id(payload, "assigneeId");
        tasks.upsert(task(taskId, application, assigneeId, "PENDING", occurredAt));
        notify(eventId, assigneeId, "APPROVAL_PENDING", "待处理请假审批",
                application.getApplicationNo(), "LEAVE_APPLICATION", application.getApplicationId(),
                "/approval-tasks/" + taskId, occurredAt);
        invalidate(application.getApplicantId(), assigneeId);
    }

    private void completed(JsonNode payload, String eventId, LocalDateTime occurredAt, String taskStatus,
                           String notificationType, String title) {
        WorkbenchApplicationEntity application = application(payload, occurredAt);
        applications.upsert(application);
        JsonNode taskId = payload.get("taskId");
        if (taskId == null) taskId = payload.get("cancelledTaskId");
        if (taskId != null && !taskId.isNull()) tasks.updateStatus(Long.parseLong(taskId.asText()), taskStatus, occurredAt);
        notify(eventId, application.getApplicantId(), notificationType, title, application.getApplicationNo(),
                "LEAVE_APPLICATION", application.getApplicationId(),
                "/leave-applications/" + application.getApplicationId(), occurredAt);
        invalidate(application.getApplicantId());
    }

    private void transferred(JsonNode payload, String eventId, LocalDateTime occurredAt) {
        WorkbenchApplicationEntity application = application(payload, occurredAt);
        applications.upsert(application);
        long oldTaskId = id(payload, "taskId");
        long newTaskId = id(payload, "newTaskId");
        long targetId = id(payload, "targetUserId");
        tasks.updateStatus(oldTaskId, "TRANSFERRED", occurredAt);
        tasks.upsert(task(newTaskId, application, targetId, "PENDING", occurredAt));
        notify(eventId, targetId, "APPROVAL_TRANSFERRED", "转交的请假审批", application.getApplicationNo(),
                "LEAVE_APPLICATION", application.getApplicationId(), "/approval-tasks/" + newTaskId, occurredAt);
        invalidate(application.getApplicantId(), targetId);
    }

    private void noticePublished(JsonNode event, JsonNode payload, String eventId, LocalDateTime occurredAt) {
        String noticeId = required(payload, "noticeId");
        List<String> departments = objectMapper.convertValue(payload.path("targetDepartmentIds"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        String requestId = event.path("requestId").asText("event_" + eventId);
        for (IdentityClient.AudienceUser user : identity.audience(required(payload, "scopeType"), departments, requestId)) {
            long recipientId = Long.parseLong(user.id());
            notify(eventId, recipientId, "NOTICE_PUBLISHED", "新公告：" + required(payload, "title"),
                    payload.path("summary").asText(""), "NOTICE", Long.parseLong(noticeId),
                    "/notices/" + noticeId, occurredAt);
            invalidate(recipientId);
        }
    }

    private WorkbenchApplicationEntity application(JsonNode payload, LocalDateTime occurredAt) {
        WorkbenchApplicationEntity entity = new WorkbenchApplicationEntity();
        entity.setApplicationId(id(payload, "applicationId"));
        entity.setApplicationNo(payload.path("applicationNo").asText(""));
        entity.setApplicantId(id(payload, "applicantId"));
        entity.setApplicantName(payload.path("applicantName").asText(""));
        entity.setDepartmentId(longValue(payload, "departmentId", 0));
        entity.setDepartmentName(payload.path("departmentName").asText(""));
        entity.setLeaveType(text(payload, "leaveType"));
        entity.setStartTime(time(payload.get("startTime"), null));
        entity.setEndTime(time(payload.get("endTime"), null));
        entity.setDurationHours(decimal(payload, "durationHours"));
        entity.setUrgency(value(payload, "urgency", "NORMAL"));
        entity.setStatus(value(payload, "status", "PENDING"));
        entity.setSubmissionRound(payload.path("submissionRound").asInt(0));
        entity.setApplicationVersion(payload.path("version").asInt(1));
        entity.setCreatedAt(time(payload.get("createdAt"), occurredAt));
        entity.setSubmittedAt(time(payload.get("submittedAt"), occurredAt));
        entity.setUpdatedAt(time(payload.get("updatedAt"), occurredAt));
        return entity;
    }

    private WorkbenchTaskEntity task(long id, WorkbenchApplicationEntity application, long assignee,
                                     String status, LocalDateTime occurredAt) {
        WorkbenchTaskEntity entity = new WorkbenchTaskEntity();
        entity.setTaskId(id);
        entity.setApplicationId(application.getApplicationId());
        entity.setAssigneeId(assignee);
        entity.setTitle("请假审批 " + application.getApplicationNo());
        entity.setUrgency(application.getUrgency() == null ? "NORMAL" : application.getUrgency());
        entity.setStatus(status);
        entity.setCreatedAt(occurredAt);
        entity.setUpdatedAt(occurredAt);
        return entity;
    }

    private void notify(String eventId, long recipient, String type, String title, String summary,
                        String businessType, long businessId, String link, LocalDateTime createdAt) {
        NotificationEntity entity = new NotificationEntity();
        entity.setRecipientId(recipient);
        entity.setNotificationType(type);
        entity.setTitle(title);
        entity.setSummary(summary);
        entity.setBusinessType(businessType);
        entity.setBusinessId(businessId);
        entity.setLink(link);
        entity.setSourceEventId(eventId);
        entity.setRead(false);
        entity.setCreatedAt(createdAt);
        notifications.insert(entity);
    }

    private void deadLetter(String sourceTopic, String eventId, String message, String error) {
        try {
            kafka.send(sourceTopic + ".dlq", eventId, message).get(5, TimeUnit.SECONDS);
            inbox.markDead(eventId, error);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to publish office dead letter", exception);
        }
    }

    private void invalidate(long... userIds) {
        try {
            for (long userId : userIds) redis.delete(CACHE_PREFIX + userId);
        } catch (DataAccessException ignored) { }
    }

    private JsonNode parse(String message) {
        try { return objectMapper.readTree(message); }
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
        String value = text(node, field);
        return value == null || value.isBlank() ? fallback : value;
    }
    private long id(JsonNode node, String field) { return Long.parseLong(required(node, field)); }
    private long longValue(JsonNode node, String field, long fallback) {
        String value = text(node, field);
        return value == null || value.isBlank() ? fallback : Long.parseLong(value);
    }
    private BigDecimal decimal(JsonNode node, String field) {
        String value = text(node, field);
        return value == null || value.isBlank() ? null : new BigDecimal(value);
    }
    private LocalDateTime time(JsonNode node, LocalDateTime fallback) {
        if (node == null || node.isNull() || node.asText().isBlank()) return fallback;
        String value = node.asText();
        try { return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(); }
        catch (RuntimeException ignored) { return LocalDateTime.parse(value); }
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private String error(Throwable exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return message.substring(0, Math.min(message.length(), 1000));
    }
}
