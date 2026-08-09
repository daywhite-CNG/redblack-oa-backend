package com.redblack.office.api;

import com.redblack.office.domain.OfficeEnums.FileStatus;
import com.redblack.office.domain.OfficeEnums.NoticeScopeType;
import com.redblack.office.domain.OfficeEnums.NoticeStatus;
import com.redblack.office.domain.OfficeEnums.NoticeType;
import com.redblack.office.domain.OfficeEnums.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class OfficeApiModels {
    private OfficeApiModels() { }

    public record UserRef(String id, String name, String departmentId) { }
    public record DepartmentRef(String id, String name) { }

    @Schema(name = "FileSummary")
    public record FileSummary(String id, String fileName, String contentType, long size,
                              FileStatus status, OffsetDateTime uploadedAt) { }

    @Schema(name = "SaveNoticeRequest")
    public record SaveNoticeRequest(
            @NotBlank @Size(min = 2, max = 100) String title,
            @NotBlank @Size(min = 2, max = 300) String summary,
            @NotBlank @Size(max = 20000) String content,
            @NotNull NoticeType type,
            @NotNull NoticeScopeType scopeType,
            @NotNull @Size(max = 200) List<@Pattern(regexp = "^[1-9][0-9]*$") String> targetDepartmentIds,
            @NotNull Boolean isPinned,
            OffsetDateTime scheduledPublishAt,
            @NotNull @Size(max = 10) List<@Pattern(regexp = "^[1-9][0-9]*$") String> attachmentIds
    ) { }

    @Schema(name = "UpdateNoticeRequest")
    public record UpdateNoticeRequest(
            @NotBlank @Size(min = 2, max = 100) String title,
            @NotBlank @Size(min = 2, max = 300) String summary,
            @NotBlank @Size(max = 20000) String content,
            @NotNull NoticeType type,
            @NotNull NoticeScopeType scopeType,
            @NotNull @Size(max = 200) List<@Pattern(regexp = "^[1-9][0-9]*$") String> targetDepartmentIds,
            @NotNull Boolean isPinned,
            OffsetDateTime scheduledPublishAt,
            @NotNull @Size(max = 10) List<@Pattern(regexp = "^[1-9][0-9]*$") String> attachmentIds,
            @NotNull @Min(1) Integer version
    ) { }

    @Schema(name = "VersionRequest")
    public record VersionRequest(@NotNull @Min(1) Integer version) { }

    @Schema(name = "NoticeWithdrawRequest")
    public record NoticeWithdrawRequest(@NotNull @Min(1) Integer version,
                                        @NotBlank @Size(min = 2, max = 200) String reason) { }

    @Schema(name = "ChangePinRequest")
    public record ChangePinRequest(@NotNull Boolean isPinned, @NotNull @Min(1) Integer version) { }

    @Schema(name = "Notice")
    public record NoticeView(String id, String title, String summary, String content, NoticeType type,
                             NoticeScopeType scopeType, List<String> targetDepartmentIds,
                             UserRef publisher, DepartmentRef publisherDepartment, boolean isPinned,
                             NoticeStatus status, long readCount, boolean isRead,
                             OffsetDateTime scheduledPublishAt, OffsetDateTime publishedAt,
                             List<FileSummary> attachments, OffsetDateTime createdAt,
                             OffsetDateTime updatedAt, int version) { }

    public record PageData<T>(List<T> items, int page, int pageSize, long total) { }

    @Schema(name = "Notification")
    public record NotificationView(String id, NotificationType type, String title, String summary,
                                   String businessType, String businessId, String link, boolean isRead,
                                   OffsetDateTime readAt, OffsetDateTime createdAt) { }

    public record CountData(long count) { }

    @Schema(name = "WorkbenchMetric")
    public record WorkbenchMetric(String key, String label, BigDecimal value, BigDecimal trendValue,
                                  String trendDirection, String targetPath) { }
    @Schema(name = "WorkbenchTodo")
    public record WorkbenchTodo(String id, String type, String title, String urgency,
                                OffsetDateTime dueAt, OffsetDateTime createdAt, String targetPath) { }
    public record QuickEntry(String key, String label, String targetPath) { }
    @Schema(name = "ChartPoint")
    public record ChartPoint(String key, String label, BigDecimal value) { }

    @Schema(name = "WorkbenchData")
    public record WorkbenchData(List<WorkbenchMetric> metrics, List<WorkbenchTodo> todos,
                                List<QuickEntry> quickEntries, List<WorkbenchLeaveApplication> recentApplications,
                                List<NoticeView> recentNotices, List<ChartPoint> approvalTrend,
                                List<ChartPoint> leaveTypeDistribution,
                                List<ChartPoint> applicationStatusDistribution) { }

    @Schema(name = "LeaveApplication")
    public record WorkbenchLeaveApplication(
            String id, String applicationNo, UserRef applicant, DepartmentRef department, UserRef leader,
            String leaveType, OffsetDateTime startTime, OffsetDateTime endTime, BigDecimal leaveDurationHours,
            String urgency, String reason, UserRef handoverUser, String contactPhone,
            List<FileSummary> attachments, String status, int submissionRound, String currentNode,
            UserRef currentApprover, List<String> allowedActions, OffsetDateTime createdAt,
            OffsetDateTime submittedAt, OffsetDateTime updatedAt, int version) { }
}
