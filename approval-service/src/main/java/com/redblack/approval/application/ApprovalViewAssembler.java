package com.redblack.approval.application;

import com.redblack.approval.api.ApprovalApiModels.ApprovalActionResult;
import com.redblack.approval.api.ApprovalApiModels.ApprovalRecordView;
import com.redblack.approval.api.ApprovalApiModels.ApprovalRoundView;
import com.redblack.approval.api.ApprovalApiModels.ApprovalTaskDetail;
import com.redblack.approval.api.ApprovalApiModels.ApprovalTaskView;
import com.redblack.approval.api.ApprovalApiModels.DepartmentRef;
import com.redblack.approval.api.ApprovalApiModels.LeaveApplicationView;
import com.redblack.approval.api.ApprovalApiModels.LeaveSubmissionResult;
import com.redblack.approval.api.ApprovalApiModels.UserRef;
import com.redblack.approval.application.ApprovalAuthorizationService.ActorAuthorization;
import com.redblack.approval.domain.ApprovalEnums.ApprovalAction;
import com.redblack.approval.domain.ApprovalRecordEntity;
import com.redblack.approval.domain.ApprovalTaskEntity;
import com.redblack.approval.domain.LeaveApplicationEntity;
import com.redblack.approval.infrastructure.persistence.ApprovalRecordMapper;
import com.redblack.approval.infrastructure.persistence.AttachmentMapper;
import com.redblack.approval.infrastructure.office.OfficeFileClient;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ApprovalViewAssembler {
    private final ApprovalRecordMapper recordMapper;
    private final AttachmentMapper attachmentMapper;
    private final OfficeFileClient officeFiles;

    public ApprovalViewAssembler(ApprovalRecordMapper recordMapper, AttachmentMapper attachmentMapper,
                                 OfficeFileClient officeFiles) {
        this.recordMapper = recordMapper;
        this.attachmentMapper = attachmentMapper;
        this.officeFiles = officeFiles;
    }

    public LeaveApplicationView leave(LeaveApplicationEntity application, ActorAuthorization actor) {
        List<String> allowed = allowedActions(application, actor);
        return new LeaveApplicationView(
                application.getId().toString(),
                application.getApplicationNo(),
                user(application.getApplicantId(), application.getApplicantName(), application.getDepartmentId()),
                new DepartmentRef(application.getDepartmentId().toString(), application.getDepartmentName()),
                user(application.getLeaderId(), application.getLeaderName(), application.getDepartmentId()),
                application.getLeaveType(),
                time(application.getStartTime()),
                time(application.getEndTime()),
                application.getLeaveDurationHours(),
                application.getUrgency(),
                application.getReason(),
                user(application.getHandoverUserId(), application.getHandoverUserName(), null),
                application.getContactPhone(),
                officeFiles.metadata(attachmentMapper.findIds(application.getId())),
                application.getStatus(),
                application.getSubmissionRound(),
                application.getStatus() == com.redblack.approval.domain.ApprovalEnums.LeaveStatus.PENDING
                        ? "直属领导审批" : null,
                user(application.getCurrentApproverId(), application.getCurrentApproverName(), application.getDepartmentId()),
                allowed,
                time(application.getCreatedAt()),
                time(application.getSubmittedAt()),
                time(application.getUpdatedAt()),
                application.getVersion());
    }

    public ApprovalTaskView task(ApprovalTaskEntity task, LeaveApplicationEntity application) {
        return new ApprovalTaskView(
                task.getId().toString(),
                application.getId().toString(),
                application.getApplicationNo(),
                user(application.getApplicantId(), application.getApplicantName(), application.getDepartmentId()),
                new DepartmentRef(application.getDepartmentId().toString(), application.getDepartmentName()),
                application.getLeaveType(),
                time(application.getStartTime()),
                time(application.getEndTime()),
                application.getLeaveDurationHours(),
                application.getUrgency(),
                task.getStatus(),
                user(task.getAssigneeId(), task.getAssigneeName(), application.getDepartmentId()),
                task.getComment(),
                time(task.getCreatedAt()),
                time(task.getProcessedAt()),
                task.getVersion());
    }

    public ApprovalTaskDetail detail(ApprovalTaskEntity task,
                                     LeaveApplicationEntity application,
                                     ActorAuthorization actor) {
        return new ApprovalTaskDetail(task(task, application), leave(application, actor), timeline(application.getId()));
    }

    public LeaveSubmissionResult submission(LeaveApplicationEntity application) {
        return new LeaveSubmissionResult(
                application.getId().toString(), application.getApplicationNo(), application.getStatus(),
                application.getSubmissionRound(),
                application.getStatus() == com.redblack.approval.domain.ApprovalEnums.LeaveStatus.PENDING
                        ? "直属领导审批" : null,
                user(application.getCurrentApproverId(), application.getCurrentApproverName(), application.getDepartmentId()),
                application.getVersion());
    }

    public ApprovalActionResult action(ApprovalTaskEntity task,
                                       LeaveApplicationEntity application,
                                       Long newTaskId) {
        return new ApprovalActionResult(task.getId().toString(), task.getStatus(),
                newTaskId == null ? null : newTaskId.toString(), application.getId().toString(),
                application.getStatus(), time(task.getProcessedAt()));
    }

    public List<ApprovalRoundView> timeline(long applicationId) {
        List<ApprovalRecordEntity> records = recordMapper.findByApplication(applicationId);
        Map<Integer, List<ApprovalRecordEntity>> grouped = new LinkedHashMap<>();
        records.forEach(record -> grouped.computeIfAbsent(record.getSubmissionRound(), ignored -> new ArrayList<>())
                .add(record));
        return grouped.entrySet().stream().map(entry -> round(entry.getKey(), entry.getValue())).toList();
    }

    private ApprovalRoundView round(int round, List<ApprovalRecordEntity> records) {
        OffsetDateTime submittedAt = records.stream()
                .filter(record -> record.getAction() == ApprovalAction.SUBMIT
                        || record.getAction() == ApprovalAction.RESUBMIT)
                .map(ApprovalRecordEntity::getOperatedAt)
                .map(this::time)
                .findFirst().orElse(null);
        String result = records.isEmpty() ? null : records.get(records.size() - 1).getToStatus();
        return new ApprovalRoundView(round, submittedAt, result,
                records.stream().map(this::record).toList());
    }

    private ApprovalRecordView record(ApprovalRecordEntity record) {
        return new ApprovalRecordView(
                record.getId().toString(), record.getApplicationId().toString(), record.getSubmissionRound(),
                record.getAction(), user(record.getOperatorId(), record.getOperatorName(),
                record.getOperatorDepartmentId()), record.getFromStatus(), record.getToStatus(), record.getComment(),
                user(record.getTargetUserId(), record.getTargetUserName(), record.getTargetDepartmentId()),
                time(record.getOperatedAt()));
    }

    private List<String> allowedActions(LeaveApplicationEntity application, ActorAuthorization actor) {
        List<String> actions = new ArrayList<>();
        boolean applicant = application.getApplicantId() == actor.actorId();
        if (applicant && actor.has("leave:update:self") && switch (application.getStatus()) {
            case DRAFT, REJECTED, WITHDRAWN -> true;
            default -> false;
        }) {
            actions.add("EDIT");
        }
        if (applicant && actor.has("leave:submit") && switch (application.getStatus()) {
            case DRAFT, REJECTED, WITHDRAWN -> true;
            default -> false;
        }) {
            actions.add("SUBMIT");
        }
        if (applicant && actor.has("leave:withdraw")
                && application.getStatus() == com.redblack.approval.domain.ApprovalEnums.LeaveStatus.PENDING) {
            actions.add("WITHDRAW");
        }
        if (applicant && switch (application.getStatus()) {
            case DRAFT, WITHDRAWN -> true;
            default -> false;
        }) {
            actions.add("DELETE");
        }
        if (application.getStatus() == com.redblack.approval.domain.ApprovalEnums.LeaveStatus.PENDING
                && application.getCurrentApproverId() != null
                && application.getCurrentApproverId() == actor.actorId()) {
            if (actor.has("approval:task:approve")) actions.add("APPROVE");
            if (actor.has("approval:task:reject")) actions.add("REJECT");
            if (actor.has("approval:task:transfer")) actions.add("TRANSFER");
        }
        return List.copyOf(actions);
    }

    private UserRef user(Long id, String name, Long departmentId) {
        return id == null ? null : new UserRef(id.toString(), name,
                departmentId == null ? null : departmentId.toString());
    }

    private OffsetDateTime time(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
