package com.redblack.identity.api;

import com.redblack.common.api.ApiResponse;
import com.redblack.identity.api.IdentityApiModels.ApprovalContext;
import com.redblack.identity.api.IdentityApiModels.AuthorizationSnapshot;
import com.redblack.identity.api.IdentityApiModels.UserSummary;
import com.redblack.identity.application.InternalIdentityApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1")
public class InternalIdentityController {
    private final InternalIdentityApplicationService service;

    public InternalIdentityController(InternalIdentityApplicationService service) {
        this.service = service;
    }

    @GetMapping("/authorization/users/{userId}")
    ApiResponse<AuthorizationSnapshot> authorization(@PathVariable long userId, HttpServletRequest request) {
        return ApiResponse.success("查询成功", service.authorization(userId), RequestIds.get(request));
    }

    @GetMapping("/users/{userId}/approval-context")
    ApiResponse<ApprovalContext> approvalContext(@PathVariable long userId, HttpServletRequest request) {
        return ApiResponse.success("查询成功", service.approvalContext(userId), RequestIds.get(request));
    }

    @GetMapping("/users/{userId}/summary")
    ApiResponse<UserSummary> summary(@PathVariable long userId, HttpServletRequest request) {
        return ApiResponse.success("查询成功", service.summary(userId), RequestIds.get(request));
    }
}
