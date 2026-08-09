package com.redblack.identity.api;

import com.redblack.identity.domain.IdentityEnums.DataScope;
import com.redblack.identity.domain.IdentityEnums.EnabledStatus;
import com.redblack.identity.domain.IdentityEnums.Gender;
import com.redblack.identity.domain.IdentityEnums.MenuType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.List;

public final class IdentityApiModels {
    private IdentityApiModels() {
    }

    @Schema(name = "LoginRequest")
    public record LoginRequest(
            @NotBlank @Size(min = 3, max = 50) String username,
            @NotBlank @Size(min = 6, max = 128) String password,
            Boolean rememberMe
    ) {
    }

    public record TokenData(String accessToken, String tokenType, long expiresIn) {
    }

    public record LoginData(String accessToken, String tokenType, long expiresIn, CurrentUser user) {
    }

    public record DepartmentRef(String id, String name) {
    }

    public record UserRef(String id, String name, String departmentId) {
    }

    public record RoleRef(String id, String code, String name) {
    }

    @Schema(name = "CurrentUser")
    public record CurrentUser(
            String id,
            String username,
            String name,
            String avatarUrl,
            DepartmentRef department,
            List<RoleRef> roles,
            List<String> permissions,
            List<MenuNode> menuTree
    ) {
    }

    @Schema(name = "User")
    public record UserView(
            String id,
            String username,
            String name,
            Gender gender,
            String phone,
            String email,
            String avatarUrl,
            DepartmentRef department,
            UserRef leader,
            List<RoleRef> roles,
            EnabledStatus status,
            String remark,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version
    ) {
    }

    public record PageData<T>(List<T> items, int page, int pageSize, long total) {
    }

    @Schema(name = "CreateUserRequest")
    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9._-]{2,49}$") String username,
            @NotBlank @Size(min = 2, max = 50) String name,
            @NotBlank @Size(min = 8, max = 128) String password,
            @NotNull Gender gender,
            @Pattern(regexp = "^1[3-9][0-9]{9}$") String phone,
            @Email @Size(max = 200) String email,
            @NotBlank String departmentId,
            String leaderId,
            @NotEmpty List<@NotBlank String> roleIds,
            @NotNull EnabledStatus status,
            @Size(max = 500) String remark
    ) {
    }

    @Schema(name = "UpdateUserRequest")
    public record UpdateUserRequest(
            @NotBlank @Size(min = 2, max = 50) String name,
            @NotNull Gender gender,
            @Pattern(regexp = "^1[3-9][0-9]{9}$") String phone,
            @Email @Size(max = 200) String email,
            @NotBlank String departmentId,
            String leaderId,
            @NotEmpty List<@NotBlank String> roleIds,
            @NotNull EnabledStatus status,
            @Size(max = 500) String remark,
            @NotNull @Min(1) Integer version
    ) {
    }

    @Schema(name = "UpdateProfileRequest")
    public record UpdateProfileRequest(
            @NotBlank @Size(min = 2, max = 50) String name,
            @NotNull Gender gender,
            @Pattern(regexp = "^1[3-9][0-9]{9}$") String phone,
            @Email @Size(max = 200) String email,
            @NotNull @Min(1) Integer version
    ) {
    }

    @Schema(name = "ChangePasswordRequest")
    public record ChangePasswordRequest(
            @NotBlank @Size(min = 6, max = 128) String currentPassword,
            @NotBlank @Size(min = 8, max = 128) String newPassword
    ) {
    }

    @Schema(name = "ChangeStatusRequest")
    public record ChangeStatusRequest(@NotNull EnabledStatus status, @NotNull @Min(1) Integer version) {
    }

    @Schema(name = "ResetPasswordRequest")
    public record ResetPasswordRequest(@NotNull @Min(1) Integer version) {
    }

    public record ResetPasswordData(String temporaryPassword, OffsetDateTime expiresAt) {
    }

    @Schema(name = "DepartmentNode")
    public record DepartmentNode(
            String id,
            String parentId,
            String name,
            UserRef leader,
            int sortOrder,
            EnabledStatus status,
            long memberCount,
            OffsetDateTime createdAt,
            int version,
            List<DepartmentNode> children
    ) {
    }

    @Schema(name = "CreateDepartmentRequest")
    public record CreateDepartmentRequest(
            String parentId,
            @NotBlank @Size(min = 2, max = 100) String name,
            String leaderId,
            @NotNull @Min(0) @Max(9999) Integer sortOrder,
            @NotNull EnabledStatus status
    ) {
    }

    @Schema(name = "UpdateDepartmentRequest")
    public record UpdateDepartmentRequest(
            String parentId,
            @NotBlank @Size(min = 2, max = 100) String name,
            String leaderId,
            @NotNull @Min(0) @Max(9999) Integer sortOrder,
            @NotNull EnabledStatus status,
            @NotNull @Min(1) Integer version
    ) {
    }

    @Schema(name = "Role")
    public record RoleView(
            String id,
            String code,
            String name,
            DataScope dataScope,
            List<String> customDepartmentIds,
            EnabledStatus status,
            long memberCount,
            boolean systemRole,
            String remark,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version
    ) {
    }

    @Schema(name = "CreateRoleRequest")
    public record CreateRoleRequest(
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,49}$") String code,
            @NotBlank @Size(min = 2, max = 50) String name,
            @NotNull DataScope dataScope,
            List<@NotBlank String> customDepartmentIds,
            @NotNull EnabledStatus status,
            @Size(max = 500) String remark
    ) {
    }

    @Schema(name = "UpdateRoleRequest")
    public record UpdateRoleRequest(
            @NotBlank @Size(min = 2, max = 50) String name,
            @NotNull DataScope dataScope,
            List<@NotBlank String> customDepartmentIds,
            @NotNull EnabledStatus status,
            @Size(max = 500) String remark,
            @NotNull @Min(1) Integer version
    ) {
    }

    @Schema(name = "RolePermission")
    public record RolePermissionView(
            String roleId,
            List<String> permissionIds,
            DataScope dataScope,
            List<String> customDepartmentIds,
            int version
    ) {
    }

    @Schema(name = "ReplaceRolePermissionsRequest")
    public record ReplaceRolePermissionsRequest(
            @NotNull List<@NotBlank String> permissionIds,
            @NotNull DataScope dataScope,
            @NotNull List<@NotBlank String> customDepartmentIds,
            @NotNull @Min(1) Integer version
    ) {
    }

    @Schema(name = "MenuNode")
    public record MenuNode(
            String id,
            String parentId,
            String name,
            String icon,
            MenuType type,
            String routePath,
            String component,
            String permission,
            int sortOrder,
            EnabledStatus status,
            boolean visible,
            int version,
            List<MenuNode> children
    ) {
    }

    @Schema(name = "CreateMenuRequest")
    public record CreateMenuRequest(
            String parentId,
            @NotBlank @Size(min = 2, max = 50) String name,
            @Size(max = 100) String icon,
            @NotNull MenuType type,
            @Size(max = 200) String routePath,
            @Size(max = 200) String component,
            @Size(max = 100) String permission,
            @NotNull @Min(0) @Max(9999) Integer sortOrder,
            @NotNull EnabledStatus status,
            @NotNull Boolean visible
    ) {
    }

    @Schema(name = "UpdateMenuRequest")
    public record UpdateMenuRequest(
            String parentId,
            @NotBlank @Size(min = 2, max = 50) String name,
            @Size(max = 100) String icon,
            @NotNull MenuType type,
            @Size(max = 200) String routePath,
            @Size(max = 200) String component,
            @Size(max = 100) String permission,
            @NotNull @Min(0) @Max(9999) Integer sortOrder,
            @NotNull EnabledStatus status,
            @NotNull Boolean visible,
            @NotNull @Min(1) Integer version
    ) {
    }

    public record AuthorizationGrant(DataScope scope, List<String> departmentIds) {
    }

    public record AuthorizationSnapshot(
            String userId,
            EnabledStatus status,
            long authVersion,
            List<RoleRef> roles,
            List<String> permissions,
            List<AuthorizationGrant> grants,
            long menuTreeVersion,
            OffsetDateTime expiresAt
    ) {
    }

    public record ApprovalContext(
            String userId,
            String name,
            String departmentId,
            String departmentName,
            String leaderId,
            String leaderName,
            EnabledStatus status
    ) {
    }

    public record UserSummary(String id, String name, String departmentId, String avatarUrl) {
    }
}
