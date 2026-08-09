package com.redblack.identity.api;

import com.redblack.common.api.ApiResponse;
import com.redblack.identity.api.IdentityApiModels.*;
import com.redblack.identity.application.UserApplicationService;
import com.redblack.identity.application.IdempotencyService;
import com.redblack.identity.domain.IdentityEnums.EnabledStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserApplicationService service;
    private final IdempotencyService idempotency;

    public UserController(UserApplicationService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @GetMapping
    ApiResponse<PageData<UserView>> list(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) Long departmentId,
                                         @RequestParam(required = false) Long roleId,
                                         @RequestParam(required = false) EnabledStatus status,
                                         @RequestParam(defaultValue = "1") @Min(1) int page,
                                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
                                         @RequestParam(defaultValue = "createdAt,desc") String sort,
                                         HttpServletRequest request) {
        PageData<UserView> result = service.list(jwt, keyword, departmentId, roleId, status,
                page, pageSize, sort);
        return ApiResponse.success("查询成功", result, RequestIds.get(request));
    }

    @PostMapping
    ResponseEntity<ApiResponse<UserView>> create(@AuthenticationPrincipal Jwt jwt,
                                                  @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                  @Valid @RequestBody CreateUserRequest body,
                                                  HttpServletRequest request) {
        UserView result = idempotency.execute(jwt, "POST", "/api/v1/users", idempotencyKey, body,
                UserView.class, () -> service.authorizeCreate(jwt, body),
                () -> service.create(jwt, body, RequestIds.get(request)));
        return ResponseEntity.created(URI.create("/api/v1/users/" + result.id()))
                .body(ApiResponse.success("创建成功", result, RequestIds.get(request)));
    }

    @GetMapping("/options")
    ApiResponse<List<UserRef>> options(@AuthenticationPrincipal Jwt jwt,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) Long departmentId,
                                       @RequestParam(defaultValue = "true") boolean enabledOnly,
                                       @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
                                       HttpServletRequest request) {
        List<UserRef> result = service.options(jwt, keyword, departmentId,
                enabledOnly ? EnabledStatus.ENABLED : null, limit);
        return ApiResponse.success("查询成功", result, RequestIds.get(request));
    }

    @GetMapping("/{userId}")
    ApiResponse<UserView> get(@AuthenticationPrincipal Jwt jwt,
                              @PathVariable long userId,
                              HttpServletRequest request) {
        return ApiResponse.success("查询成功", service.get(jwt, userId), RequestIds.get(request));
    }

    @PutMapping("/{userId}")
    ApiResponse<UserView> update(@AuthenticationPrincipal Jwt jwt,
                                 @PathVariable long userId,
                                 @Valid @RequestBody UpdateUserRequest body,
                                 HttpServletRequest request) {
        return ApiResponse.success("更新成功", service.update(jwt, userId, body, RequestIds.get(request)),
                RequestIds.get(request));
    }

    @PatchMapping("/{userId}/status")
    ApiResponse<UserView> changeStatus(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable long userId,
                                       @Valid @RequestBody ChangeStatusRequest body,
                                       HttpServletRequest request) {
        return ApiResponse.success("状态更新成功", service.changeStatus(jwt, userId, body, RequestIds.get(request)),
                RequestIds.get(request));
    }

    @PostMapping("/{userId}/reset-password")
    ApiResponse<ResetPasswordData> resetPassword(@AuthenticationPrincipal Jwt jwt,
                                                 @PathVariable long userId,
                                                 @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                 @Valid @RequestBody ResetPasswordRequest body,
                                                 HttpServletRequest request) {
        ResetPasswordData result = idempotency.execute(jwt, "POST",
                "/api/v1/users/" + userId + "/reset-password", idempotencyKey, body,
                ResetPasswordData.class, () -> service.authorizeResetPassword(jwt, userId),
                () -> service.resetPassword(jwt, userId, body, RequestIds.get(request)));
        return ApiResponse.success("重置成功", result,
                RequestIds.get(request));
    }

    @DeleteMapping("/{userId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt,
                                @PathVariable long userId,
                                @RequestParam @Min(1) int version,
                                HttpServletRequest request) {
        service.delete(jwt, userId, version, RequestIds.get(request));
        return ResponseEntity.noContent().build();
    }
}
