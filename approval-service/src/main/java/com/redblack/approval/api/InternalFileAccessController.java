package com.redblack.approval.api;

import com.redblack.approval.application.InternalFileAccessService;
import com.redblack.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/leave-applications")
public class InternalFileAccessController {
    private final InternalFileAccessService service;
    public InternalFileAccessController(InternalFileAccessService service) { this.service = service; }

    @GetMapping("/{applicationId}/file-access")
    ApiResponse<AccessResult> canRead(@PathVariable long applicationId, @RequestParam long userId,
                                      HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        return ApiResponse.success("查询成功", new AccessResult(service.canRead(applicationId, userId, requestId)), requestId);
    }

    public record AccessResult(boolean allowed) { }
}
