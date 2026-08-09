package com.redblack.office.api;

import com.redblack.common.api.ApiResponse;
import com.redblack.office.api.OfficeApiModels.WorkbenchData;
import com.redblack.office.application.WorkbenchApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workbench")
public class WorkbenchController {
    private final WorkbenchApplicationService service;
    public WorkbenchController(WorkbenchApplicationService service) { this.service = service; }

    @GetMapping
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "查询成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未认证"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "权限不足")
    })
    ApiResponse<WorkbenchData> get(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        return ApiResponse.success("查询成功", service.get(jwt, requestId), requestId);
    }
}
