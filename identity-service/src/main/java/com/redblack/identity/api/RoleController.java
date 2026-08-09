package com.redblack.identity.api;

import com.redblack.common.api.ApiResponse;
import com.redblack.identity.api.IdentityApiModels.*;
import com.redblack.identity.application.RoleApplicationService;
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

@Validated
@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {
    private final RoleApplicationService roles;
    private final UserApplicationService users;
    private final IdempotencyService idempotency;

    public RoleController(RoleApplicationService roles, UserApplicationService users, IdempotencyService idempotency) {
        this.roles = roles;
        this.users = users;
        this.idempotency = idempotency;
    }

    @GetMapping
    ApiResponse<PageData<RoleView>> list(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) EnabledStatus status,
                                         @RequestParam(defaultValue = "1") @Min(1) int page,
                                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
                                         HttpServletRequest request) {
        return ApiResponse.success("查询成功", roles.list(jwt, keyword, status, page, pageSize), RequestIds.get(request));
    }

    @PostMapping
    ResponseEntity<ApiResponse<RoleView>> create(@AuthenticationPrincipal Jwt jwt,
                                                  @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                  @Valid @RequestBody CreateRoleRequest body,
                                                  HttpServletRequest request) {
        RoleView result = idempotency.execute(jwt, "POST", "/api/v1/roles", idempotencyKey, body,
                RoleView.class, () -> roles.authorizeCreate(jwt),
                () -> roles.create(jwt, body, RequestIds.get(request)));
        return ResponseEntity.created(URI.create("/api/v1/roles/" + result.id()))
                .body(ApiResponse.success("创建成功", result, RequestIds.get(request)));
    }

    @GetMapping("/{roleId}")
    ApiResponse<RoleView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable long roleId, HttpServletRequest request) {
        return ApiResponse.success("查询成功", roles.get(jwt, roleId), RequestIds.get(request));
    }

    @PutMapping("/{roleId}")
    ApiResponse<RoleView> update(@AuthenticationPrincipal Jwt jwt,
                                 @PathVariable long roleId,
                                 @Valid @RequestBody UpdateRoleRequest body,
                                 HttpServletRequest request) {
        return ApiResponse.success("更新成功", roles.update(jwt, roleId, body, RequestIds.get(request)), RequestIds.get(request));
    }

    @PatchMapping("/{roleId}/status")
    ApiResponse<RoleView> changeStatus(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable long roleId,
                                       @Valid @RequestBody ChangeStatusRequest body,
                                       HttpServletRequest request) {
        return ApiResponse.success("状态更新成功", roles.changeStatus(jwt, roleId, body, RequestIds.get(request)),
                RequestIds.get(request));
    }

    @DeleteMapping("/{roleId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt,
                                @PathVariable long roleId,
                                @RequestParam @Min(1) int version,
                                HttpServletRequest request) {
        roles.delete(jwt, roleId, version, RequestIds.get(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{roleId}/permissions")
    ApiResponse<RolePermissionView> permissions(@AuthenticationPrincipal Jwt jwt,
                                                @PathVariable long roleId,
                                                HttpServletRequest request) {
        return ApiResponse.success("查询成功", roles.permissions(jwt, roleId), RequestIds.get(request));
    }

    @PutMapping("/{roleId}/permissions")
    ApiResponse<RolePermissionView> replacePermissions(@AuthenticationPrincipal Jwt jwt,
                                                       @PathVariable long roleId,
                                                       @Valid @RequestBody ReplaceRolePermissionsRequest body,
                                                       HttpServletRequest request) {
        return ApiResponse.success("更新成功", roles.replacePermissions(jwt, roleId, body, RequestIds.get(request)),
                RequestIds.get(request));
    }

    @GetMapping("/{roleId}/members")
    ApiResponse<PageData<UserView>> members(@AuthenticationPrincipal Jwt jwt,
                                            @PathVariable long roleId,
                                            @RequestParam(defaultValue = "1") @Min(1) int page,
                                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
                                            HttpServletRequest request) {
        return ApiResponse.success("查询成功", users.roleMembers(jwt, roleId, page, pageSize), RequestIds.get(request));
    }
}
