package com.redblack.approval.api;

import com.redblack.approval.api.ApprovalApiModels.ApprovalActionResult;
import com.redblack.approval.api.ApprovalApiModels.ApprovalTaskDetail;
import com.redblack.approval.api.ApprovalApiModels.ApprovalTaskView;
import com.redblack.approval.api.ApprovalApiModels.ApproveTaskRequest;
import com.redblack.approval.api.ApprovalApiModels.PageData;
import com.redblack.approval.api.ApprovalApiModels.RejectTaskRequest;
import com.redblack.approval.api.ApprovalApiModels.TransferTaskRequest;
import com.redblack.approval.application.ApprovalTaskService;
import com.redblack.approval.application.IdempotencyService;
import com.redblack.approval.domain.ApprovalEnums.LeaveType;
import com.redblack.approval.domain.ApprovalEnums.Urgency;
import com.redblack.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

@Validated
@RestController
@RequestMapping("/api/v1/approval-tasks")
public class ApprovalTaskController {
    private final ApprovalTaskService service;
    private final IdempotencyService idempotency;

    public ApprovalTaskController(ApprovalTaskService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @GetMapping
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "查询成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "请求参数错误"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "权限不足")
    })
    ApiResponse<PageData<ApprovalTaskView>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam String view,
            @RequestParam(required = false) @Size(max = 30) String applicationNo,
            @RequestParam(required = false) @Size(max = 50) String applicantName,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) LeaveType leaveType,
            @RequestParam(required = false) Urgency urgency,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime processedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime processedTo,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(defaultValue = "createdAt,desc") String sort,
            HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        return ApiResponse.success("查询成功", service.list(jwt, view, applicationNo, applicantName, departmentId,
                leaveType, urgency, processedFrom, processedTo, page, pageSize, sort, requestId), requestId);
    }

    @GetMapping("/{taskId}")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "查询成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在")
    })
    ApiResponse<ApprovalTaskDetail> get(@AuthenticationPrincipal Jwt jwt,
                                         @PathVariable long taskId,
                                         HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        return ApiResponse.success("查询成功", service.get(jwt, taskId, requestId), requestId);
    }

    @PostMapping("/{taskId}/approve")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "审批成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "请求参数错误"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "权限不足"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "请求冲突")
    })
    ApiResponse<ApprovalActionResult> approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ApproveTaskRequest body,
            HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        String path = "/api/v1/approval-tasks/" + taskId + "/approve";
        ApprovalActionResult result = idempotency.execute(jwt, "POST", path, idempotencyKey, body,
                ApprovalActionResult.class, () -> service.authorizeApprove(jwt, requestId),
                () -> service.approve(jwt, taskId, body, requestId));
        return ApiResponse.success("审批成功", result, requestId);
    }

    @PostMapping("/{taskId}/reject")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "驳回成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "请求参数错误"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "权限不足"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "请求冲突"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "业务校验失败")
    })
    ApiResponse<ApprovalActionResult> reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RejectTaskRequest body,
            HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        String path = "/api/v1/approval-tasks/" + taskId + "/reject";
        ApprovalActionResult result = idempotency.execute(jwt, "POST", path, idempotencyKey, body,
                ApprovalActionResult.class, () -> service.authorizeReject(jwt, requestId),
                () -> service.reject(jwt, taskId, body, requestId));
        return ApiResponse.success("驳回成功", result, requestId);
    }

    @PostMapping("/{taskId}/transfer")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "转交成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "请求参数错误"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "权限不足"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "请求冲突"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "业务校验失败")
    })
    ApiResponse<ApprovalActionResult> transfer(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferTaskRequest body,
            HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        String path = "/api/v1/approval-tasks/" + taskId + "/transfer";
        ApprovalActionResult result = idempotency.execute(jwt, "POST", path, idempotencyKey, body,
                ApprovalActionResult.class, () -> service.authorizeTransfer(jwt, requestId),
                () -> service.transfer(jwt, taskId, body, requestId));
        return ApiResponse.success("转交成功", result, requestId);
    }
}
