package com.redblack.approval.application;

import com.redblack.approval.api.ApprovalApiModels.LeaveApplicationView;
import com.redblack.approval.api.ApprovalApiModels.LeaveSubmissionResult;
import com.redblack.approval.api.ApprovalApiModels.PageData;
import com.redblack.approval.api.ApprovalApiModels.SaveLeaveApplicationRequest;
import com.redblack.approval.api.ApprovalApiModels.UpdateLeaveApplicationRequest;
import com.redblack.approval.api.ApprovalApiModels.VersionRequest;
import com.redblack.approval.api.ApprovalApiModels.WithdrawLeaveRequest;
import com.redblack.approval.application.ApprovalAuthorizationService.ActorAuthorization;
import com.redblack.approval.application.LeaveValidationService.DraftValues;
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
import com.redblack.approval.infrastructure.office.OfficeFileClient;
import com.redblack.approval.infrastructure.persistence.ApprovalTaskMapper;
import com.redblack.approval.infrastructure.persistence.AttachmentMapper;
import com.redblack.approval.infrastructure.persistence.LeaveApplicationMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LeaveApplicationService {
    private final LeaveApplicationMapper applications;
    private final ApprovalTaskMapper tasks;
    private final AttachmentMapper attachments;
    private final ApprovalAuthorizationService authorization;
    private final IdentityClient identityClient;
    private final LeaveValidationService validation;
    private final ApplicationNumberService applicationNumbers;
    private final ApprovalRecordService records;
    private final ApprovalViewAssembler assembler;
    private final OutboxService outbox;
    private final OfficeFileClient officeFiles;
    private final Clock clock;

    public LeaveApplicationService(LeaveApplicationMapper applications,
                                   ApprovalTaskMapper tasks,
                                   AttachmentMapper attachments,
                                   ApprovalAuthorizationService authorization,
                                   IdentityClient identityClient,
                                   LeaveValidationService validation,
                                   ApplicationNumberService applicationNumbers,
                                   ApprovalRecordService records,
                                   ApprovalViewAssembler assembler,
                                   OutboxService outbox,
                                   OfficeFileClient officeFiles,
                                   Clock clock) {
        this.applications = applications;
        this.tasks = tasks;
        this.attachments = attachments;
        this.authorization = authorization;
        this.identityClient = identityClient;
        this.validation = validation;
        this.applicationNumbers = applicationNumbers;
        this.records = records;
        this.assembler = assembler;
        this.outbox = outbox;
        this.officeFiles = officeFiles;
        this.clock = clock;
    }

    public void authorizeCreate(Jwt jwt, String requestId) {
        authorization.require(jwt, "leave:create", requestId);
    }

    public void authorizeSubmit(Jwt jwt, String requestId) {
        authorization.require(jwt, "leave:submit", requestId);
    }

    public void authorizeWithdraw(Jwt jwt, String requestId) {
        authorization.require(jwt, "leave:withdraw", requestId);
    }

    public PageData<LeaveApplicationView> list(Jwt jwt,
                                               String scope,
                                               String applicationNo,
                                               String applicantName,
                                               Long departmentId,
                                               LeaveType leaveType,
                                               LeaveStatus status,
                                               OffsetDateTime submittedFrom,
                                               OffsetDateTime submittedTo,
                                               int page,
                                               int pageSize,
                                               String sort,
                                               String requestId) {
        boolean mine = "mine".equals(scope);
        if (!mine && !"accessible".equals(scope)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "scope 必须为 mine 或 accessible");
        }
        ActorAuthorization actor = mine
                ? authorization.require(jwt, "leave:read:self", requestId)
                : authorization.requireActive(jwt, requestId);
        if (!mine && !actor.has("leave:read:self") && !actor.has("leave:read:scope")
                && !actor.has("approval:task:read")) {
            throw BusinessException.denied();
        }
        boolean allowScoped = !mine && actor.has("leave:read:scope");
        List<Long> departmentIds = actor.dataAccess().departmentIds().stream().sorted().toList();
        long offset = (long) (page - 1) * pageSize;
        String orderBy = leaveOrder(sort);
        List<LeaveApplicationEntity> rows = applications.findPage(actor.actorId(), mine, allowScoped,
                actor.dataAccess().all(), departmentIds, applicationNo, applicantName, departmentId, leaveType,
                status, utc(submittedFrom), utc(submittedTo), orderBy, offset, pageSize);
        long total = applications.countPage(actor.actorId(), mine, allowScoped, actor.dataAccess().all(),
                departmentIds, applicationNo, applicantName, departmentId, leaveType, status,
                utc(submittedFrom), utc(submittedTo));
        return new PageData<>(rows.stream().map(row -> assembler.leave(row, actor)).toList(), page, pageSize, total);
    }

    @Transactional
    public LeaveApplicationView create(Jwt jwt, SaveLeaveApplicationRequest request, String requestId) {
        ActorAuthorization actor = authorization.require(jwt, "leave:create", requestId);
        ApprovalContext applicant = identityClient.approvalContext(actor.actorId(), requestId);
        DraftValues values = validation.validate(request, actor.actorId(), requestId);
        String reservationId = UUID.randomUUID().toString();
        officeFiles.reserve(reservationId, actor.actorId(), values.attachmentIds(), null, requestId);
        LocalDateTime now = now();
        LeaveApplicationEntity application = new LeaveApplicationEntity();
        application.setApplicationNo(applicationNumbers.next());
        application.setApplicantId(actor.actorId());
        application.setApplicantName(applicant.name());
        application.setDepartmentId(Long.parseLong(applicant.departmentId()));
        application.setDepartmentName(requireDepartmentName(applicant));
        apply(application, values);
        application.setStatus(LeaveStatus.DRAFT);
        application.setSubmissionRound(0);
        application.setDeleted(false);
        application.setVersion(1);
        application.setCreatedAt(now);
        application.setUpdatedAt(now);
        applications.insert(application);
        replaceAttachments(application.getId(), values.attachmentIds(), now);
        emitAttachmentChange(application.getId(), actor.actorId(), reservationId, values.attachmentIds(), requestId);
        records.append(application.getId(), 0, ApprovalAction.CREATE, applicant, null, LeaveStatus.DRAFT.name(),
                null, null);
        outbox.audit("LEAVE_CREATED", "LEAVE_APPLICATION", application.getId().toString(),
                Long.toString(actor.actorId()), requestId);
        return assembler.leave(application, actor);
    }

    public LeaveApplicationView get(Jwt jwt, long applicationId, String requestId) {
        ActorAuthorization actor = authorization.requireActive(jwt, requestId);
        LeaveApplicationEntity application = required(applicationId);
        requireReadable(actor, application);
        return assembler.leave(application, actor);
    }

    @Transactional
    public LeaveApplicationView update(Jwt jwt,
                                       long applicationId,
                                       UpdateLeaveApplicationRequest request,
                                       String requestId) {
        ActorAuthorization actor = authorization.require(jwt, "leave:update:self", requestId);
        LeaveApplicationEntity application = requiredForUpdate(applicationId);
        requireApplicant(actor, application);
        requireEditable(application);
        requireVersion(application.getVersion(), request.version());
        DraftValues values = validation.validate(request, actor.actorId(), requestId);
        String reservationId = UUID.randomUUID().toString();
        officeFiles.reserve(reservationId, actor.actorId(), values.attachmentIds(), applicationId, requestId);
        apply(application, values);
        application.setUpdatedAt(now());
        update(application);
        replaceAttachments(applicationId, values.attachmentIds(), now());
        emitAttachmentChange(applicationId, actor.actorId(), reservationId, values.attachmentIds(), requestId);
        outbox.audit("LEAVE_UPDATED", "LEAVE_APPLICATION", application.getId().toString(),
                Long.toString(actor.actorId()), requestId);
        return assembler.leave(application, actor);
    }

    @Transactional
    public void delete(Jwt jwt, long applicationId, int version, String requestId) {
        ActorAuthorization actor = authorization.requireActive(jwt, requestId);
        LeaveApplicationEntity application = requiredForUpdate(applicationId);
        requireApplicant(actor, application);
        if (application.getStatus() != LeaveStatus.DRAFT && application.getStatus() != LeaveStatus.WITHDRAWN) {
            throw statusConflict();
        }
        requireVersion(application.getVersion(), version);
        LocalDateTime now = now();
        if (applications.softDelete(application.getId(), application.getVersion(), now) != 1) {
            throw BusinessException.versionConflict();
        }
        attachments.deleteByApplication(applicationId);
        emitAttachmentChange(applicationId, actor.actorId(), UUID.randomUUID().toString(), List.of(), requestId);
        outbox.audit("LEAVE_DELETED", "LEAVE_APPLICATION", application.getId().toString(),
                Long.toString(actor.actorId()), requestId);
    }

    @Transactional
    public LeaveSubmissionResult submit(Jwt jwt,
                                        long applicationId,
                                        VersionRequest request,
                                        String requestId) {
        ActorAuthorization actor = authorization.require(jwt, "leave:submit", requestId);
        LeaveApplicationEntity application = requiredForUpdate(applicationId);
        requireApplicant(actor, application);
        if (application.getStatus() != LeaveStatus.DRAFT && application.getStatus() != LeaveStatus.REJECTED
                && application.getStatus() != LeaveStatus.WITHDRAWN) {
            throw statusConflict();
        }
        requireVersion(application.getVersion(), request.version());
        validation.validateForSubmit(application, attachments.findIds(applicationId));
        ApprovalContext applicant = identityClient.approvalContext(actor.actorId(), requestId);
        ApprovalContext handover = validation.validateHandover(application.getHandoverUserId().toString(),
                actor.actorId(), requestId);
        ApprovalContext leader = requireLeader(applicant, requestId);
        LeaveStatus previous = application.getStatus();
        int round = application.getSubmissionRound() + 1;
        LocalDateTime now = now();
        application.setApplicantName(applicant.name());
        application.setDepartmentId(Long.parseLong(applicant.departmentId()));
        application.setDepartmentName(requireDepartmentName(applicant));
        application.setLeaderId(Long.parseLong(leader.userId()));
        application.setLeaderName(leader.name());
        application.setHandoverUserName(handover.name());
        application.setStatus(LeaveStatus.PENDING);
        application.setSubmissionRound(round);
        application.setCurrentApproverId(Long.parseLong(leader.userId()));
        application.setCurrentApproverName(leader.name());
        application.setSubmittedAt(now);
        application.setUpdatedAt(now);
        update(application);

        ApprovalTaskEntity task = new ApprovalTaskEntity();
        task.setApplicationId(applicationId);
        task.setSubmissionRound(round);
        task.setAssigneeId(Long.parseLong(leader.userId()));
        task.setAssigneeName(leader.name());
        task.setStatus(ApprovalTaskStatus.PENDING);
        task.setVersion(1);
        task.setCreatedAt(now);
        tasks.insert(task);
        records.append(applicationId, round,
                previous == LeaveStatus.DRAFT ? ApprovalAction.SUBMIT : ApprovalAction.RESUBMIT,
                applicant, previous.name(), LeaveStatus.PENDING.name(), null, leader);
        outbox.approval("LEAVE_SUBMITTED", applicationId + "", Long.toString(actor.actorId()), requestId,
                eventPayload(application, Map.of("taskId", task.getId().toString(), "assigneeId", leader.userId())));
        outbox.audit(previous == LeaveStatus.DRAFT ? "LEAVE_SUBMITTED" : "LEAVE_RESUBMITTED",
                "LEAVE_APPLICATION", applicationId + "", Long.toString(actor.actorId()), requestId);
        return assembler.submission(application);
    }

    @Transactional
    public LeaveSubmissionResult withdraw(Jwt jwt,
                                          long applicationId,
                                          WithdrawLeaveRequest request,
                                          String requestId) {
        ActorAuthorization actor = authorization.require(jwt, "leave:withdraw", requestId);
        LeaveApplicationEntity application = requiredForUpdate(applicationId);
        requireApplicant(actor, application);
        requireVersion(application.getVersion(), request.version());
        if (application.getStatus() != LeaveStatus.PENDING) {
            throw statusConflict();
        }
        ApprovalTaskEntity task = tasks.findPendingForUpdate(applicationId, application.getSubmissionRound());
        if (task == null || task.getStatus() != ApprovalTaskStatus.PENDING) {
            throw statusConflict();
        }
        LocalDateTime now = now();
        task.setStatus(ApprovalTaskStatus.CANCELLED);
        task.setComment(request.reason().trim());
        task.setProcessedAt(now);
        updateTask(task);
        application.setStatus(LeaveStatus.WITHDRAWN);
        application.setCurrentApproverId(null);
        application.setCurrentApproverName(null);
        application.setUpdatedAt(now);
        update(application);
        ApprovalContext applicant = identityClient.approvalContext(actor.actorId(), requestId);
        records.append(applicationId, application.getSubmissionRound(), ApprovalAction.WITHDRAW, applicant,
                LeaveStatus.PENDING.name(), LeaveStatus.WITHDRAWN.name(), request.reason(), null);
        outbox.approval("LEAVE_WITHDRAWN", applicationId + "", Long.toString(actor.actorId()), requestId,
                eventPayload(application, Map.of("cancelledTaskId", task.getId().toString())));
        outbox.audit("LEAVE_WITHDRAWN", "LEAVE_APPLICATION", applicationId + "",
                Long.toString(actor.actorId()), requestId);
        return assembler.submission(application);
    }

    public List<com.redblack.approval.api.ApprovalApiModels.ApprovalRoundView> timeline(
            Jwt jwt, long applicationId, String requestId) {
        ActorAuthorization actor = authorization.requireActive(jwt, requestId);
        LeaveApplicationEntity application = required(applicationId);
        requireReadable(actor, application);
        return assembler.timeline(applicationId);
    }

    private ApprovalContext requireLeader(ApprovalContext applicant, String requestId) {
        if (applicant.leaderId() == null) {
            throw leaderInvalid();
        }
        try {
            ApprovalContext leader = identityClient.approvalContext(Long.parseLong(applicant.leaderId()), requestId);
            AuthorizationSnapshot snapshot = identityClient.authorization(Long.parseLong(applicant.leaderId()), requestId);
            boolean canProcess = snapshot.permissions().contains("approval:task:read")
                    && snapshot.permissions().contains("approval:task:approve")
                    && snapshot.permissions().contains("approval:task:reject");
            if (!"ENABLED".equals(leader.status()) || !"ENABLED".equals(snapshot.status()) || !canProcess
                    || leader.userId().equals(applicant.userId())) {
                throw leaderInvalid();
            }
            return leader;
        } catch (BusinessException exception) {
            if ("DEPENDENCY_UNAVAILABLE".equals(exception.code())) {
                throw exception;
            }
            throw leaderInvalid();
        }
    }

    private Map<String, Object> eventPayload(LeaveApplicationEntity application, Map<String, String> additions) {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
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

    private void requireReadable(ActorAuthorization actor, LeaveApplicationEntity application) {
        boolean readable = application.getApplicantId() == actor.actorId()
                || tasks.countHandledBy(application.getId(), actor.actorId()) > 0
                || (actor.has("leave:read:scope") && actor.dataAccess().includes(application.getDepartmentId()));
        if (!readable) {
            throw BusinessException.notFound("请假申请不存在");
        }
    }

    private void requireApplicant(ActorAuthorization actor, LeaveApplicationEntity application) {
        if (application.getApplicantId() != actor.actorId()) {
            throw BusinessException.notFound("请假申请不存在");
        }
    }

    private void requireEditable(LeaveApplicationEntity application) {
        if (application.getStatus() != LeaveStatus.DRAFT && application.getStatus() != LeaveStatus.REJECTED
                && application.getStatus() != LeaveStatus.WITHDRAWN) {
            throw statusConflict();
        }
    }

    private LeaveApplicationEntity required(long id) {
        LeaveApplicationEntity application = applications.selectById(id);
        if (application == null) {
            throw BusinessException.notFound("请假申请不存在");
        }
        return application;
    }

    private LeaveApplicationEntity requiredForUpdate(long id) {
        LeaveApplicationEntity application = applications.selectForUpdate(id);
        if (application == null) {
            throw BusinessException.notFound("请假申请不存在");
        }
        return application;
    }

    private void apply(LeaveApplicationEntity application, DraftValues values) {
        application.setLeaveType(values.leaveType());
        application.setStartTime(values.startTime());
        application.setEndTime(values.endTime());
        application.setLeaveDurationHours(values.durationHours());
        application.setUrgency(values.urgency());
        application.setReason(values.reason());
        application.setHandoverUserId(values.handover() == null ? null : Long.parseLong(values.handover().userId()));
        application.setHandoverUserName(values.handover() == null ? null : values.handover().name());
        application.setContactPhone(values.contactPhone());
    }

    private void replaceAttachments(long applicationId, List<Long> fileIds, LocalDateTime createdAt) {
        attachments.deleteByApplication(applicationId);
        for (int index = 0; index < fileIds.size(); index++) {
            attachments.insert(applicationId, fileIds.get(index), index, createdAt);
        }
    }

    private void emitAttachmentChange(long applicationId, long ownerId, String reservationId,
                                      List<Long> fileIds, String requestId) {
        outbox.approval("LEAVE_ATTACHMENTS_CHANGED", Long.toString(applicationId), Long.toString(ownerId), requestId,
                Map.of("applicationId", Long.toString(applicationId), "ownerId", Long.toString(ownerId),
                        "reservationId", reservationId,
                        "fileIds", fileIds.stream().map(String::valueOf).toList()));
    }

    private void update(LeaveApplicationEntity application) {
        if (applications.updateAll(application) != 1) {
            throw BusinessException.versionConflict();
        }
        application.setVersion(application.getVersion() + 1);
    }

    private void updateTask(ApprovalTaskEntity task) {
        if (tasks.updateState(task) != 1) {
            throw BusinessException.versionConflict();
        }
        task.setVersion(task.getVersion() + 1);
    }

    private void requireVersion(int actual, int expected) {
        if (actual != expected) {
            throw BusinessException.versionConflict();
        }
    }

    private String requireDepartmentName(ApprovalContext context) {
        if (context.departmentName() == null || context.departmentName().isBlank()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                    "身份服务未返回部门快照");
        }
        return context.departmentName();
    }

    private BusinessException statusConflict() {
        return new BusinessException(HttpStatus.CONFLICT, "APPLICATION_STATUS_CONFLICT", "当前申请状态不允许此操作");
    }

    private BusinessException leaderInvalid() {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "LEADER_NOT_CONFIGURED", "未配置有效直属领导");
    }

    private LocalDateTime utc(OffsetDateTime value) {
        return value == null ? null : LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }

    private String leaveOrder(String sort) {
        if (sort == null || sort.isBlank() || "updatedAt,desc".equals(sort)) return "a.updated_at DESC";
        return switch (sort) {
            case "updatedAt,asc" -> "a.updated_at ASC";
            case "createdAt,desc" -> "a.created_at DESC";
            case "createdAt,asc" -> "a.created_at ASC";
            case "submittedAt,desc" -> "a.submitted_at DESC";
            case "submittedAt,asc" -> "a.submitted_at ASC";
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "不支持的排序字段");
        };
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
