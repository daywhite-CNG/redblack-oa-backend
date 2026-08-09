package com.redblack.approval.application;

import com.redblack.approval.api.ApprovalApiModels.SaveLeaveApplicationRequest;
import com.redblack.approval.api.ApprovalApiModels.UpdateLeaveApplicationRequest;
import com.redblack.approval.domain.ApprovalEnums.LeaveType;
import com.redblack.approval.domain.LeaveApplicationEntity;
import com.redblack.approval.infrastructure.identity.IdentityClient;
import com.redblack.approval.infrastructure.identity.IdentityClient.ApprovalContext;
import com.redblack.common.api.ErrorDetail;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;

@Service
public class LeaveValidationService {
    private final IdentityClient identityClient;

    public LeaveValidationService(IdentityClient identityClient) {
        this.identityClient = identityClient;
    }

    public DraftValues validate(SaveLeaveApplicationRequest request, long actorId, String requestId) {
        List<Long> attachmentIds = validateAttachments(request.attachmentIds());
        ApprovalContext handover = validateHandover(request.handoverUserId(), actorId, requestId);
        return new DraftValues(request.leaveType(), utc(request.startTime()), utc(request.endTime()),
                duration(request.startTime(), request.endTime()), request.urgency(), trim(request.reason()), handover,
                trim(request.contactPhone()), attachmentIds);
    }

    public DraftValues validate(UpdateLeaveApplicationRequest request, long actorId, String requestId) {
        return validate(new SaveLeaveApplicationRequest(request.leaveType(), request.startTime(), request.endTime(),
                request.urgency(), request.reason(), request.handoverUserId(), request.contactPhone(),
                request.attachmentIds()), actorId, requestId);
    }

    public void validateForSubmit(LeaveApplicationEntity application, List<Long> attachmentIds) {
        if (application.getLeaveType() == null || application.getStartTime() == null || application.getEndTime() == null
                || application.getUrgency() == null || application.getReason() == null
                || application.getReason().length() < 5 || application.getReason().length() > 500
                || application.getHandoverUserId() == null || application.getContactPhone() == null
                || !application.getContactPhone().matches("^1[3-9][0-9]{9}$")) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "请先完整填写请假申请");
        }
        if (application.getLeaveType() == LeaveType.SICK && attachmentIds.isEmpty()) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "SICK_ATTACHMENT_REQUIRED", "病假必须提供证明附件");
        }
        duration(application.getStartTime().atOffset(ZoneOffset.UTC), application.getEndTime().atOffset(ZoneOffset.UTC));
    }

    public ApprovalContext validateHandover(String value, long actorId, String requestId) {
        if (value == null || value.isBlank()) {
            return null;
        }
        long userId;
        try {
            userId = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw handoverInvalid();
        }
        if (userId == actorId) {
            throw handoverInvalid();
        }
        try {
            ApprovalContext context = identityClient.approvalContext(userId, requestId);
            if (!"ENABLED".equals(context.status())) {
                throw handoverInvalid();
            }
            return context;
        } catch (BusinessException exception) {
            if ("RESOURCE_NOT_FOUND".equals(exception.code())) {
                throw handoverInvalid();
            }
            throw exception;
        }
    }

    private List<Long> validateAttachments(List<String> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return List.of();
        }
        if (new HashSet<>(attachmentIds).size() != attachmentIds.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "附件 ID 不能重复",
                    List.of(new ErrorDetail("attachmentIds", "附件 ID 不能重复")));
        }
        try { return attachmentIds.stream().map(Long::parseLong).toList(); }
        catch (RuntimeException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "附件 ID 无效");
        }
    }

    private BigDecimal duration(OffsetDateTime start, OffsetDateTime end) {
        if (start == null || end == null) {
            return null;
        }
        if (!end.isAfter(start)) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "LEAVE_TIME_RANGE_INVALID", "结束时间必须晚于开始时间");
        }
        BigDecimal hours = BigDecimal.valueOf(Duration.between(start, end).toSeconds())
                .divide(BigDecimal.valueOf(3600), 1, RoundingMode.HALF_UP);
        if (hours.compareTo(BigDecimal.valueOf(0.5)) < 0 || hours.compareTo(BigDecimal.valueOf(720)) > 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "LEAVE_DURATION_OUT_OF_RANGE",
                    "请假时长必须在 0.5 到 720 小时之间");
        }
        return hours;
    }

    private LocalDateTime utc(OffsetDateTime value) {
        return value == null ? null : LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BusinessException handoverInvalid() {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "HANDOVER_USER_INVALID", "工作交接人无效");
    }

    public record DraftValues(LeaveType leaveType,
                              LocalDateTime startTime,
                              LocalDateTime endTime,
                              BigDecimal durationHours,
                              com.redblack.approval.domain.ApprovalEnums.Urgency urgency,
                              String reason,
                              ApprovalContext handover,
                              String contactPhone,
                              List<Long> attachmentIds) {
    }
}
