package com.redblack.audit.api;

import com.redblack.audit.api.AuditApiModels.OperationLogView;
import com.redblack.audit.api.AuditApiModels.PageData;
import com.redblack.audit.application.OperationLogApplicationService;
import com.redblack.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

@Validated
@RestController
@RequestMapping("/api/v1/operation-logs")
public class OperationLogController {
    private final OperationLogApplicationService service;
    public OperationLogController(OperationLogApplicationService service) { this.service = service; }
    @GetMapping
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "查询成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "权限不足")
    })
    ApiResponse<PageData<OperationLogView>> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Size(max = 50) String operatorName,
            @RequestParam(required = false) @Size(max = 50) String module,
            @RequestParam(required = false) @Size(max = 50) String operationType,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime operatedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime operatedTo,
            @RequestParam(defaultValue = "operatedAt,desc") String sort,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        return ApiResponse.success("查询成功", service.list(jwt, operatorName, module, operationType, result,
                operatedFrom, operatedTo, sort, page, pageSize, requestId), requestId);
    }
    @GetMapping("/{logId}")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "查询成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "权限不足"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在")
    })
    ApiResponse<OperationLogView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable long logId,
                                      HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        return ApiResponse.success("查询成功", service.get(jwt, logId, requestId), requestId);
    }
}
