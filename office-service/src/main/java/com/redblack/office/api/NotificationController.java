package com.redblack.office.api;

import com.redblack.common.api.ApiResponse;
import com.redblack.office.api.OfficeApiModels.CountData;
import com.redblack.office.api.OfficeApiModels.NotificationView;
import com.redblack.office.api.OfficeApiModels.PageData;
import com.redblack.office.application.NotificationApplicationService;
import com.redblack.office.domain.OfficeEnums.NotificationType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationApplicationService service;
    public NotificationController(NotificationApplicationService service) { this.service = service; }

    @GetMapping
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "查询成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未认证")
    })
    ApiResponse<PageData<NotificationView>> list(@AuthenticationPrincipal Jwt jwt,
                                                 @RequestParam(required = false) String readStatus,
                                                 @RequestParam(required = false) NotificationType type,
                                                 @RequestParam(defaultValue = "1") @Min(1) int page,
                                                 @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
                                                 HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        return ApiResponse.success("查询成功", service.list(jwt, readStatus, type, page, pageSize, requestId), requestId);
    }

    @GetMapping("/unread-count")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "查询成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未认证")
    })
    ApiResponse<CountData> unread(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        return ApiResponse.success("查询成功", new CountData(service.unreadCount(jwt, requestId)), requestId);
    }

    @PostMapping("/{notificationId}/read")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "已读"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在")
    })
    ResponseEntity<Void> read(@AuthenticationPrincipal Jwt jwt, @PathVariable long notificationId,
                              HttpServletRequest request) {
        service.markRead(jwt, notificationId, RequestIds.get(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "操作成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未认证")
    })
    ApiResponse<CountData> readAll(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        return ApiResponse.success("操作成功", new CountData(service.markAllRead(jwt, requestId)), requestId);
    }
}
