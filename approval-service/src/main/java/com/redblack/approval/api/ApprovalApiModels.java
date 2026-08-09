package com.redblack.approval.api;

import com.redblack.approval.domain.ApprovalEnums.ApprovalAction;
import com.redblack.approval.domain.ApprovalEnums.ApprovalTaskStatus;
import com.redblack.approval.domain.ApprovalEnums.LeaveStatus;
import com.redblack.approval.domain.ApprovalEnums.LeaveType;
import com.redblack.approval.domain.ApprovalEnums.Urgency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class ApprovalApiModels {
    private ApprovalApiModels() {
    }

    public record UserRef(String id, String name, String departmentId) {
    }

    public record DepartmentRef(String id, String name) {
    }

    public record FileSummary(String id,
                              String fileName,
                              String contentType,
                              long size,
                              String status,
                              OffsetDateTime uploadedAt) {
    }

    @Schema(name = "SaveLeaveApplicationRequest")
    public record SaveLeaveApplicationRequest(
            LeaveType leaveType,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            Urgency urgency,
            @Size(max = 500) String reason,
            @Pattern(regexp = "^[1-9][0-9]*$") String handoverUserId,
            @Pattern(regexp = "^1[3-9][0-9]{9}$") String contactPhone,
            @Size(max = 5) List<@Pattern(regexp = "^[1-9][0-9]*$") String> attachmentIds
    ) {
    }

    @Schema(name = "UpdateLeaveApplicationRequest")
    public record UpdateLeaveApplicationRequest(
            LeaveType leaveType,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            Urgency urgency,
            @Size(max = 500) String reason,
            @Pattern(regexp = "^[1-9][0-9]*$") String handoverUserId,
            @Pattern(regexp = "^1[3-9][0-9]{9}$") String contactPhone,
            @Size(max = 5) List<@Pattern(regexp = "^[1-9][0-9]*$") String> attachmentIds,
            @NotNull @Min(1) Integer version
    ) {
    }

    @Schema(name = "VersionRequest")
    public record VersionRequest(@NotNull @Min(1) Integer version) {
    }

    @Schema(name = "WithdrawLeaveRequest")
    public record WithdrawLeaveRequest(
            @NotNull @Min(1) Integer version,
            @NotBlank @Size(min = 2, max = 200) String reason
    ) {
    }

    @Schema(name = "ApproveTaskRequest")
    public record ApproveTaskRequest(
            @NotNull @Min(1) Integer version,
            @Size(max = 500) String comment
    ) {
    }

    @Schema(name = "RejectTaskRequest")
    public record RejectTaskRequest(
            @NotNull @Min(1) Integer version,
            @Schema(minLength = 5, maxLength = 500) String comment
    ) {
    }

    @Schema(name = "TransferTaskRequest")
    public record TransferTaskRequest(
            @NotNull @Min(1) Integer version,
            @NotBlank @Pattern(regexp = "^[1-9][0-9]*$") String targetUserId,
            @Schema(minLength = 5, maxLength = 200) String reason
    ) {
    }

    @Schema(name = "LeaveApplication")
    public record LeaveApplicationView(
            String id,
            String applicationNo,
            UserRef applicant,
            DepartmentRef department,
            UserRef leader,
            LeaveType leaveType,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            BigDecimal leaveDurationHours,
            Urgency urgency,
            String reason,
            UserRef handoverUser,
            String contactPhone,
            List<FileSummary> attachments,
            LeaveStatus status,
            int submissionRound,
            String currentNode,
            UserRef currentApprover,
            List<String> allowedActions,
            OffsetDateTime createdAt,
            OffsetDateTime submittedAt,
            OffsetDateTime updatedAt,
            int version
    ) {
    }

    public record PageData<T>(List<T> items, int page, int pageSize, long total) {
    }

    @Schema(name = "LeaveSubmissionResult")
    public record LeaveSubmissionResult(
            String applicationId,
            String applicationNo,
            LeaveStatus status,
            int submissionRound,
            String currentNode,
            UserRef currentApprover,
            int version
    ) {
    }

    @Schema(name = "ApprovalRecord")
    public record ApprovalRecordView(
            String id,
            String applicationId,
            int submissionRound,
            ApprovalAction action,
            UserRef operator,
            String fromStatus,
            String toStatus,
            String comment,
            UserRef targetUser,
            OffsetDateTime operatedAt
    ) {
    }

    @Schema(name = "ApprovalRound")
    public record ApprovalRoundView(
            int submissionRound,
            OffsetDateTime submittedAt,
            String result,
            List<ApprovalRecordView> records
    ) {
    }

    @Schema(name = "ApprovalTask")
    public record ApprovalTaskView(
            String id,
            String applicationId,
            String applicationNo,
            UserRef applicant,
            DepartmentRef department,
            LeaveType leaveType,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            BigDecimal leaveDurationHours,
            Urgency urgency,
            ApprovalTaskStatus status,
            UserRef assignee,
            String comment,
            OffsetDateTime createdAt,
            OffsetDateTime processedAt,
            int version
    ) {
    }

    @Schema(name = "ApprovalTaskDetail")
    public record ApprovalTaskDetail(
            ApprovalTaskView task,
            LeaveApplicationView application,
            List<ApprovalRoundView> timeline
    ) {
    }

    @Schema(name = "ApprovalActionResult")
    public record ApprovalActionResult(
            String taskId,
            ApprovalTaskStatus taskStatus,
            String newTaskId,
            String applicationId,
            LeaveStatus applicationStatus,
            OffsetDateTime processedAt
    ) {
    }
}
