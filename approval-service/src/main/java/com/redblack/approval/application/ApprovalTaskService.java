package com.redblack.approval.application;

import com.redblack.approval.api.ApprovalApiModels.ApprovalActionResult;
import com.redblack.approval.api.ApprovalApiModels.ApprovalTaskDetail;
import com.redblack.approval.api.ApprovalApiModels.ApprovalTaskView;
import com.redblack.approval.api.ApprovalApiModels.ApproveTaskRequest;
import com.redblack.approval.api.ApprovalApiModels.PageData;
import com.redblack.approval.api.ApprovalApiModels.RejectTaskRequest;
import com.redblack.approval.api.ApprovalApiModels.TransferTaskRequest;
import com.redblack.approval.application.ApprovalAuthorizationService.ActorAuthorization;
import com.redblack.approval.domain.ApprovalEnums.ApprovalAction;
import com.redblack.approval.domain.ApprovalEnums.ApprovalTaskStatus;
import com.redblack.approval.domain.ApprovalEnums.LeaveStatus;
import com.redblack.approval.domain.ApprovalEnums.LeaveType;
import com.redblack.approval.domain.ApprovalEnums.Urgency;
import com.redblack.approval.domain.ApprovalTaskEntity;
import com.redblack.approval.domain.LeaveApplicationEntity;
import com.redblack.approval.infrastructure.identity.IdentityClient;
import com.redblack.approval.infrastructure.identity.IdentityClient.ApprovalContext;
import com.redblack.approval.infrastructure.identity.IdentityClient.AuthorizationSnapshot;
import com.redblack.approval.infrastructure.persistence.ApprovalTaskMapper;
import com.redblack.approval.infrastructure.persistence.LeaveApplicationMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ApprovalTaskService {
    private final ApprovalTaskMapper tasks;
    private final LeaveApplicationMapper applications;
    private final ApprovalAuthorizationService authorization;
    private final IdentityClient identityClient;
    private final ApprovalRecordService records;
    private final ApprovalViewAssembler assembler;
    private final OutboxService outbox;
    private final Clock clock;

    public ApprovalTaskService(ApprovalTaskMapper tasks,
                               LeaveApplicationMapper applications,
                               ApprovalAuthorizationService authorization,
                               IdentityClient identityClient,
                               ApprovalRecordService records,
                               ApprovalViewAssembler assembler,
                               OutboxService outbox,
                               Clock clock) {
        this.tasks = tasks;
        this.applications = applications;
        this.authorization = authorization;
        this.identityClient = identityClient;
        this.records = records;
        this.assembler = assembler;
        this.outbox = outbox;
        this.clock = clock;
    }

    public void authorizeApprove(Jwt jwt, String requestId) {
        authorization.require(jwt, "approval:task:approve", requestId);
    }

    public void authorizeReject(Jwt jwt, String requestId) {
        authorization.require(jwt, "approval:task:reject", requestId);
    }

    public void authorizeTransfer(Jwt jwt, String requestId) {
        authorization.require(jwt, "approval:task:transfer", requestId);
    }

    public PageData<ApprovalTaskView> list(Jwt jwt,
                                           String view,
                                           String applicationNo,
                                           String applicantName,
                                           Long departmentId,
                                           LeaveType leaveType,
                                           Urgency urgency,
                                           OffsetDateTime processedFrom,
                                           OffsetDateTime processedTo,
                                           int page,
                                           int pageSize,
                                           String sort,
                                           String requestId) {
        ActorAuthorization actor = authorization.require(jwt, "approval:task:read", requestId);
        boolean pending;
        if ("pending".equals(view)) {
            pending = true;
        } else if ("completed".equals(view)) {
            pending = false;
        } else {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "view 必须为 pending 或 completed");
        }
        long offset = (long) (page - 1) * pageSize;
        List<ApprovalTaskEntity> rows = tasks.findPage(actor.actorId(), pending, applicationNo, applicantName,
                departmentId, leaveType, urgency, utc(processedFrom), utc(processedTo), taskOrder(sort), offset,
                pageSize);
        List<ApprovalTaskView> items = rows.stream()
                .map(task -> assembler.task(task, requiredApplication(task.getApplicationId())))
                .toList();
        long total = tasks.countPage(actor.actorId(), pending, applicationNo, applicantName, departmentId,
                leaveType, urgency, utc(processedFrom), utc(processedTo));
        return new PageData<>(items, page, pageSize, total);
    }

    public ApprovalTaskDetail get(Jwt jwt, long taskId, String requestId) {
        ActorAuthorization actor = authorization.require(jwt, "approval:task:read", requestId);
        ApprovalTaskEntity task = requiredTask(taskId);
        requireAssignee(actor, task);
        LeaveApplicationEntity application = requiredApplication(task.getApplicationId());
        return assembler.detail(task, application, actor);
    }

    @Transactional
    public ApprovalActionResult approve(Jwt jwt, long taskId, ApproveTaskRequest request, String requestId) {
        ActorAuthorization actor = authorization.require(jwt, "approval:task:approve", requestId);
        Locked locked = lock(actor, taskId, request.version());
        LocalDateTime now = now();
        locked.task().setStatus(ApprovalTaskStatus.APPROVED);
        locked.task().setComment(trimToNull(request.comment()));
        locked.task().setProcessedAt(now);
        updateTask(locked.task());
        locked.application().setStatus(LeaveStatus.APPROVED);
        locked.application().setCurrentApproverId(null);
        locked.application().setCurrentApproverName(null);
        locked.application().setUpdatedAt(now);
        updateApplication(locked.application());
        ApprovalContext operator = identityClient.approvalContext(actor.actorId(), requestId);
        records.append(locked.application().getId(), locked.application().getSubmissionRound(),
                ApprovalAction.APPROVE, operator, LeaveStatus.PENDING.name(), LeaveStatus.APPROVED.name(),
                request.comment(), null);
        emitFinalEvents("LEAVE_APPROVED", locked, actor.actorId(), requestId);
        return assembler.action(locked.task(), locked.application(), null);
    }

    @Transactional
    public ApprovalActionResult reject(Jwt jwt, long taskId, RejectTaskRequest request, String requestId) {
        ActorAuthorization actor = authorization.require(jwt, "approval:task:reject", requestId);
        if (request.comment() == null || request.comment().trim().length() < 5
                || request.comment().trim().length() > 500) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "APPROVAL_COMMENT_REQUIRED",
                    "驳回意见长度必须为 5 到 500 个字符");
        }
        Locked locked = lock(actor, taskId, request.version());
        LocalDateTime now = now();
        locked.task().setStatus(ApprovalTaskStatus.REJECTED);
        locked.task().setComment(request.comment().trim());
        locked.task().setProcessedAt(now);
        updateTask(locked.task());
        locked.application().setStatus(LeaveStatus.REJECTED);
        locked.application().setCurrentApproverId(null);
        locked.application().setCurrentApproverName(null);
        locked.application().setUpdatedAt(now);
        updateApplication(locked.application());
        ApprovalContext operator = identityClient.approvalContext(actor.actorId(), requestId);
        records.append(locked.application().getId(), locked.application().getSubmissionRound(),
                ApprovalAction.REJECT, operator, LeaveStatus.PENDING.name(), LeaveStatus.REJECTED.name(),
                request.comment(), null);
        emitFinalEvents("LEAVE_REJECTED", locked, actor.actorId(), requestId);
        return assembler.action(locked.task(), locked.application(), null);
    }

    @Transactional
    public ApprovalActionResult transfer(Jwt jwt, long taskId, TransferTaskRequest request, String requestId) {
        ActorAuthorization actor = authorization.require(jwt, "approval:task:transfer", requestId);
        if (request.reason() == null || request.reason().trim().length() < 5
                || request.reason().trim().length() > 200) {
            throw transferInvalid();
        }
        Locked locked = lock(actor, taskId, request.version());
        ApprovalContext target = validateTransferTarget(request.targetUserId(), locked, actor.actorId(), requestId);
        LocalDateTime now = now();
        locked.task().setStatus(ApprovalTaskStatus.TRANSFERRED);
        locked.task().setComment(request.reason().trim());
        locked.task().setProcessedAt(now);
        updateTask(locked.task());

        ApprovalTaskEntity newTask = new ApprovalTaskEntity();
        newTask.setApplicationId(locked.application().getId());
        newTask.setSubmissionRound(locked.application().getSubmissionRound());
        newTask.setAssigneeId(Long.parseLong(target.userId()));
        newTask.setAssigneeName(target.name());
        newTask.setStatus(ApprovalTaskStatus.PENDING);
        newTask.setTransferredFromTaskId(locked.task().getId());
        newTask.setVersion(1);
        newTask.setCreatedAt(now);
        tasks.insert(newTask);
        locked.application().setCurrentApproverId(newTask.getAssigneeId());
        locked.application().setCurrentApproverName(newTask.getAssigneeName());
        locked.application().setUpdatedAt(now);
        updateApplication(locked.application());
        ApprovalContext operator = identityClient.approvalContext(actor.actorId(), requestId);
        records.append(locked.application().getId(), locked.application().getSubmissionRound(),
                ApprovalAction.TRANSFER, operator, ApprovalTaskStatus.PENDING.name(),
                ApprovalTaskStatus.PENDING.name(), request.reason(), target);
        Map<String, String> additions = Map.of(
                "taskId", locked.task().getId().toString(),
                "newTaskId", newTask.getId().toString(),
                "targetUserId", target.userId());
        outbox.approval("TASK_TRANSFERRED", locked.application().getId().toString(),
                Long.toString(actor.actorId()), requestId, eventPayload(locked.application(), additions));
        outbox.audit("TASK_TRANSFERRED", "APPROVAL_TASK", locked.task().getId().toString(),
                Long.toString(actor.actorId()), requestId);
        return assembler.action(locked.task(), locked.application(), newTask.getId());
    }

    private Locked lock(ActorAuthorization actor, long taskId, int expectedVersion) {
        ApprovalTaskEntity initial = requiredTask(taskId);
        LeaveApplicationEntity application = applications.selectForUpdate(initial.getApplicationId());
        if (application == null) {
            throw BusinessException.notFound("审批任务不存在");
        }
        ApprovalTaskEntity task = tasks.selectForUpdate(taskId);
        if (task == null || !task.getApplicationId().equals(application.getId())) {
            throw BusinessException.notFound("审批任务不存在");
        }
        requireCurrentAssignee(actor, task);
        if (task.getStatus() != ApprovalTaskStatus.PENDING) {
            throw new BusinessException(HttpStatus.CONFLICT, "APPROVAL_TASK_ALREADY_PROCESSED", "审批任务已处理");
        }
        if (application.getStatus() != LeaveStatus.PENDING
                || !task.getSubmissionRound().equals(application.getSubmissionRound())
                || !task.getAssigneeId().equals(application.getCurrentApproverId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "APPLICATION_STATUS_CONFLICT", "申请已不在当前审批节点");
        }
        if (task.getVersion() != expectedVersion) {
            throw BusinessException.versionConflict();
        }
        return new Locked(task, application);
    }

    private ApprovalContext validateTransferTarget(String targetUserId,
                                                   Locked locked,
                                                   long actorId,
                                                   String requestId) {
        try {
            long targetId = Long.parseLong(targetUserId);
            if (targetId == actorId || targetId == locked.application().getApplicantId()) {
                throw transferInvalid();
            }
            ApprovalContext target = identityClient.approvalContext(targetId, requestId);
            AuthorizationSnapshot snapshot = identityClient.authorization(targetId, requestId);
            boolean sameDepartment = Long.parseLong(target.departmentId()) == locked.application().getDepartmentId();
            boolean permissions = snapshot.permissions().contains("approval:task:read")
                    && snapshot.permissions().contains("approval:task:approve")
                    && snapshot.permissions().contains("approval:task:reject")
                    && snapshot.permissions().contains("approval:task:transfer");
            if (!sameDepartment || !"ENABLED".equals(target.status()) || !"ENABLED".equals(snapshot.status())
                    || !permissions) {
                throw transferInvalid();
            }
            return target;
        } catch (BusinessException exception) {
            if ("DEPENDENCY_UNAVAILABLE".equals(exception.code())) {
                throw exception;
            }
            throw transferInvalid();
        } catch (RuntimeException exception) {
            throw transferInvalid();
        }
    }

    private void emitFinalEvents(String eventType, Locked locked, long actorId, String requestId) {
        outbox.approval(eventType, locked.application().getId().toString(), Long.toString(actorId), requestId,
                eventPayload(locked.application(), Map.of("taskId", locked.task().getId().toString())));
        outbox.audit(eventType, "APPROVAL_TASK", locked.task().getId().toString(), Long.toString(actorId), requestId);
    }

    private Map<String, Object> eventPayload(LeaveApplicationEntity application, Map<String, String> additions) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("applicationId", application.getId().toString());
        payload.put("applicationNo", application.getApplicationNo());
        payload.put("applicantId", application.getApplicantId().toString());
        put(payload, "applicantName", application.getApplicantName());
        put(payload, "departmentId", application.getDepartmentId());
        put(payload, "departmentName", application.getDepartmentName());
        payload.put("status", application.getStatus().name());
        payload.put("submissionRound", application.getSubmissionRound());
        payload.put("version", application.getVersion());
        put(payload, "leaveType", application.getLeaveType());
        put(payload, "startTime", application.getStartTime());
        put(payload, "endTime", application.getEndTime());
        put(payload, "durationHours", application.getLeaveDurationHours());
        put(payload, "urgency", application.getUrgency());
        put(payload, "createdAt", application.getCreatedAt());
        put(payload, "submittedAt", application.getSubmittedAt());
        put(payload, "updatedAt", application.getUpdatedAt());
        payload.putAll(additions);
        return Map.copyOf(payload);
    }

    private void put(Map<String, Object> payload, String key, Object value) {
        if (value != null) payload.put(key, value);
    }

    private ApprovalTaskEntity requiredTask(long taskId) {
        ApprovalTaskEntity task = tasks.selectById(taskId);
        if (task == null) {
            throw BusinessException.notFound("审批任务不存在");
        }
        return task;
    }

    private LeaveApplicationEntity requiredApplication(long applicationId) {
        LeaveApplicationEntity application = applications.selectById(applicationId);
        if (application == null) {
            throw BusinessException.notFound("审批任务不存在");
        }
        return application;
    }

    private void requireAssignee(ActorAuthorization actor, ApprovalTaskEntity task) {
        if (task.getAssigneeId() != actor.actorId()) {
            throw BusinessException.notFound("审批任务不存在");
        }
    }

    private void requireCurrentAssignee(ActorAuthorization actor, ApprovalTaskEntity task) {
        if (task.getAssigneeId() != actor.actorId()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "APPROVAL_TASK_NOT_ASSIGNEE",
                    "只有当前任务处理人可以执行此操作");
        }
    }

    private void updateTask(ApprovalTaskEntity task) {
        if (tasks.updateState(task) != 1) {
            throw BusinessException.versionConflict();
        }
        task.setVersion(task.getVersion() + 1);
    }

    private void updateApplication(LeaveApplicationEntity application) {
        if (applications.updateAll(application) != 1) {
            throw BusinessException.versionConflict();
        }
        application.setVersion(application.getVersion() + 1);
    }

    private String taskOrder(String sort) {
        if (sort == null || sort.isBlank() || "createdAt,desc".equals(sort)) return "t.created_at DESC";
        return switch (sort) {
            case "createdAt,asc" -> "t.created_at ASC";
            case "processedAt,desc" -> "t.processed_at DESC";
            case "processedAt,asc" -> "t.processed_at ASC";
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "不支持的排序字段");
        };
    }

    private LocalDateTime utc(OffsetDateTime value) {
        return value == null ? null : LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BusinessException transferInvalid() {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "TRANSFER_TARGET_INVALID", "转交目标无效");
    }

    private record Locked(ApprovalTaskEntity task, LeaveApplicationEntity application) {
    }
}
