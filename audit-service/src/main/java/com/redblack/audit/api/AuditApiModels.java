package com.redblack.audit.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

public final class AuditApiModels {
    private AuditApiModels() { }
    public record UserRef(String id, String name, String departmentId) { }
    @Schema(name = "OperationLog")
    public record OperationLogView(String id, String module, String operationType, UserRef operator,
                                   String requestMethod, String requestPath, String ipAddress, String result,
                                   String businessType, String businessId, String summary, String errorCode,
                                   long durationMs, OffsetDateTime operatedAt, String requestId) { }
    public record PageData<T>(List<T> items, int page, int pageSize, long total) { }
}
