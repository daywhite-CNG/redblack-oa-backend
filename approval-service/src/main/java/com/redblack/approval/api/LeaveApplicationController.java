package com.redblack.approval.api;

import com.redblack.approval.api.ApprovalApiModels.ApprovalRoundView;
import com.redblack.approval.api.ApprovalApiModels.LeaveApplicationView;
import com.redblack.approval.api.ApprovalApiModels.LeaveSubmissionResult;
import com.redblack.approval.api.ApprovalApiModels.PageData;
import com.redblack.approval.api.ApprovalApiModels.SaveLeaveApplicationRequest;
import com.redblack.approval.api.ApprovalApiModels.UpdateLeaveApplicationRequest;
import com.redblack.approval.api.ApprovalApiModels.VersionRequest;
import com.redblack.approval.api.ApprovalApiModels.WithdrawLeaveRequest;
import com.redblack.approval.application.IdempotencyService;
import com.redblack.approval.application.LeaveApplicationService;
import com.redblack.approval.domain.ApprovalEnums.LeaveStatus;
import com.redblack.approval.domain.ApprovalEnums.LeaveType;
import com.redblack.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/leave-applications")
public class LeaveApplicationController {
    private final LeaveApplicationService service;
    private final IdempotencyService idempotency;

    public LeaveApplicationController(LeaveApplicationService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @GetMapping
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "查询成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "请求参数错误"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "权限不足")
    })
    ApiResponse<PageData<LeaveApplicationView>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam String scope,
            @RequestParam(required = false) @Size(max = 30) String applicationNo,
            @RequestParam(required = false) @Size(max = 50) String applicantName,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) LeaveType leaveType,
            @RequestParam(required = false) LeaveStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime submittedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime submittedTo,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(defaultValue = "updatedAt,desc") String sort,
            HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        return ApiResponse.success("查询成功", service.list(jwt, scope, applicationNo, applicantName,
                departmentId, leaveType, status, submittedFrom, submittedTo, page, pageSize, sort, requestId),
                requestId);
    }

    @PostMapping
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "草稿创建成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "请求参数错误"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "权限不足"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "请求冲突"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "业务校验失败")
    })
    ResponseEntity<ApiResponse<LeaveApplicationView>> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SaveLeaveApplicationRequest body,
            HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        LeaveApplicationView result = idempotency.execute(jwt, "POST", "/api/v1/leave-applications",
                idempotencyKey, body, LeaveApplicationView.class,
                () -> service.authorizeCreate(jwt, requestId), () -> service.create(jwt, body, requestId));
        return ResponseEntity.created(URI.create("/api/v1/leave-applications/" + result.id()))
                .body(ApiResponse.success("创建成功", result, requestId));
    }

    @GetMapping("/{applicationId}")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "查询成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在")
    })
    ApiResponse<LeaveApplicationView> get(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable long applicationId,
                                           HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        return ApiResponse.success("查询成功", service.get(jwt, applicationId, requestId), requestId);
    }

    @PutMapping("/{applicationId}")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "保存成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "请求参数错误"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "权限不足"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "请求冲突"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "业务校验失败")
    })
    ApiResponse<LeaveApplicationView> update(@AuthenticationPrincipal Jwt jwt,
                                              @PathVariable long applicationId,
                                              @Valid @RequestBody UpdateLeaveApplicationRequest body,
                                              HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        return ApiResponse.success("保存成功", service.update(jwt, applicationId, body, requestId), requestId);
    }

    @DeleteMapping("/{applicationId}")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "删除成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "权限不足"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "请求冲突")
    })
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt,
                                @PathVariable long applicationId,
                                @RequestParam @Min(1) int version,
                                HttpServletRequest request) {
        service.delete(jwt, applicationId, version, RequestIds.get(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{applicationId}/submit")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "提交成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "请求参数错误"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "权限不足"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "请求冲突"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "业务校验失败")
    })
    ApiResponse<LeaveSubmissionResult> submit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long applicationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody VersionRequest body,
            HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        String path = "/api/v1/leave-applications/" + applicationId + "/submit";
        LeaveSubmissionResult result = idempotency.execute(jwt, "POST", path, idempotencyKey, body,
                LeaveSubmissionResult.class, () -> service.authorizeSubmit(jwt, requestId),
                () -> service.submit(jwt, applicationId, body, requestId));
        return ApiResponse.success("提交成功", result, requestId);
    }

    @PostMapping("/{applicationId}/withdraw")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "撤回成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "请求参数错误"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "权限不足"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "请求冲突")
    })
    ApiResponse<LeaveSubmissionResult> withdraw(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long applicationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WithdrawLeaveRequest body,
            HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        String path = "/api/v1/leave-applications/" + applicationId + "/withdraw";
        LeaveSubmissionResult result = idempotency.execute(jwt, "POST", path, idempotencyKey, body,
                LeaveSubmissionResult.class, () -> service.authorizeWithdraw(jwt, requestId),
                () -> service.withdraw(jwt, applicationId, body, requestId));
        return ApiResponse.success("撤回成功", result, requestId);
    }

    @GetMapping("/{applicationId}/timeline")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "查询成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在")
    })
    ApiResponse<List<ApprovalRoundView>> timeline(@AuthenticationPrincipal Jwt jwt,
                                                   @PathVariable long applicationId,
                                                   HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        return ApiResponse.success("查询成功", service.timeline(jwt, applicationId, requestId), requestId);
    }
}
