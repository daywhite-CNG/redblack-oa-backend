package com.redblack.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.redblack.identity.api.IdentityApiModels.*;
import com.redblack.identity.domain.*;
import com.redblack.identity.domain.IdentityEnums.DataScope;
import com.redblack.identity.domain.IdentityEnums.EnabledStatus;
import com.redblack.identity.infrastructure.persistence.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class RoleApplicationService {
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final DepartmentMapper departmentMapper;
    private final AssignmentMapper assignmentMapper;
    private final UserMapper userMapper;
    private final AuthorizationSnapshotService authorization;
    private final IdentityViewAssembler assembler;
    private final OutboxService outbox;

    public RoleApplicationService(RoleMapper roleMapper,
                                  PermissionMapper permissionMapper,
                                  DepartmentMapper departmentMapper,
                                  AssignmentMapper assignmentMapper,
                                  UserMapper userMapper,
                                  AuthorizationSnapshotService authorization,
                                  IdentityViewAssembler assembler,
                                  OutboxService outbox) {
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.departmentMapper = departmentMapper;
        this.assignmentMapper = assignmentMapper;
        this.userMapper = userMapper;
        this.authorization = authorization;
        this.assembler = assembler;
        this.outbox = outbox;
    }

    public PageData<RoleView> list(Jwt jwt, String keyword, EnabledStatus status, int page, int pageSize) {
        authorization.require(jwt, "system:role:read");
        LambdaQueryWrapper<RoleEntity> query = new LambdaQueryWrapper<RoleEntity>()
                .and(hasText(keyword), wrapper -> wrapper.like(RoleEntity::getName, keyword)
                        .or().like(RoleEntity::getCode, keyword))
                .eq(status != null, RoleEntity::getStatus, status)
                .orderByAsc(RoleEntity::getId);
        Page<RoleEntity> result = roleMapper.selectPage(Page.of(page, pageSize), query);
        return new PageData<>(result.getRecords().stream().map(assembler::role).toList(),
                page, pageSize, result.getTotal());
    }

    public RoleView get(Jwt jwt, long roleId) {
        authorization.require(jwt, "system:role:read");
        return assembler.role(required(roleId));
    }

    public void authorizeCreate(Jwt jwt) {
        authorization.require(jwt, "system:role:create");
    }

    @Transactional
    public RoleView create(Jwt jwt, CreateRoleRequest request, String requestId) {
        AuthorizationSnapshot actor = authorization.require(jwt, "system:role:create");
        if (roleMapper.selectCount(new LambdaQueryWrapper<RoleEntity>().eq(RoleEntity::getCode, request.code())) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "ROLE_CODE_EXISTS", "角色编码已存在");
        }
        List<Long> customDepartments = validateScope(request.dataScope(), request.customDepartmentIds());
        RoleEntity role = new RoleEntity();
        role.setCode(request.code());
        role.setName(request.name());
        role.setDataScope(request.dataScope());
        role.setStatus(request.status());
        role.setSystemRole(false);
        role.setRemark(request.remark());
        role.setVersion(1);
        roleMapper.insert(role);
        replaceDepartments(role.getId(), customDepartments);
        outbox.audit("ROLE_CREATED", "ROLE", Long.toString(role.getId()), actor.userId(), requestId);
        return assembler.role(required(role.getId()));
    }

    @Transactional
    public RoleView update(Jwt jwt, long roleId, UpdateRoleRequest request, String requestId) {
        AuthorizationSnapshot actor = authorization.require(jwt, "system:role:update");
        RoleEntity role = required(roleId);
        if (!role.getVersion().equals(request.version())) {
            throw BusinessException.versionConflict();
        }
        List<Long> customDepartments = validateScope(request.dataScope(), request.customDepartmentIds());
        List<Long> affected = assignmentMapper.findRoleUserIds(roleId);
        boolean authorizationChanged = role.getDataScope() != request.dataScope() || role.getStatus() != request.status()
                || !new HashSet<>(assignmentMapper.findRoleDepartmentIds(roleId)).equals(new HashSet<>(customDepartments));
        role.setName(request.name());
        role.setDataScope(request.dataScope());
        role.setStatus(request.status());
        role.setRemark(request.remark());
        if (roleMapper.updateById(role) != 1) {
            throw BusinessException.versionConflict();
        }
        replaceDepartments(roleId, customDepartments);
        if (authorizationChanged) {
            invalidateRole(roleId, affected, actor.userId(), requestId);
        }
        outbox.audit("ROLE_UPDATED", "ROLE", Long.toString(roleId), actor.userId(), requestId);
        return assembler.role(required(roleId));
    }

    @Transactional
    public RoleView changeStatus(Jwt jwt, long roleId, ChangeStatusRequest request, String requestId) {
        AuthorizationSnapshot actor = authorization.require(jwt, "system:role:update");
        RoleEntity role = required(roleId);
        if (!role.getVersion().equals(request.version())) {
            throw BusinessException.versionConflict();
        }
        List<Long> affected = assignmentMapper.findRoleUserIds(roleId);
        role.setStatus(request.status());
        if (roleMapper.updateById(role) != 1) {
            throw BusinessException.versionConflict();
        }
        invalidateRole(roleId, affected, actor.userId(), requestId);
        outbox.audit("ROLE_STATUS_CHANGED", "ROLE", Long.toString(roleId), actor.userId(), requestId);
        return assembler.role(required(roleId));
    }

    @Transactional
    public void delete(Jwt jwt, long roleId, int version, String requestId) {
        AuthorizationSnapshot actor = authorization.require(jwt, "system:role:delete");
        RoleEntity role = required(roleId);
        if (!role.getVersion().equals(version)) {
            throw BusinessException.versionConflict();
        }
        if (Boolean.TRUE.equals(role.getSystemRole())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "SYSTEM_ROLE_PROTECTED", "预置系统角色不能删除");
        }
        if (userMapper.countRoleMembers(roleId) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "ROLE_IN_USE", "角色仍有关联用户");
        }
        roleMapper.deleteById(roleId);
        outbox.audit("ROLE_DELETED", "ROLE", Long.toString(roleId), actor.userId(), requestId);
    }

    public RolePermissionView permissions(Jwt jwt, long roleId) {
        authorization.require(jwt, "system:role:read");
        return assembler.rolePermissions(required(roleId));
    }

    @Transactional
    public RolePermissionView replacePermissions(Jwt jwt,
                                                  long roleId,
                                                  ReplaceRolePermissionsRequest request,
                                                  String requestId) {
        AuthorizationSnapshot actor = authorization.require(jwt, "system:role:grant");
        RoleEntity role = required(roleId);
        if (!role.getVersion().equals(request.version())) {
            throw BusinessException.versionConflict();
        }
        List<Long> permissionIds = request.permissionIds().stream().map(this::parseId).distinct().toList();
        if (permissionMapper.selectBatchIds(permissionIds).size() != permissionIds.size()) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "包含不存在的权限");
        }
        List<Long> customDepartments = validateScope(request.dataScope(), request.customDepartmentIds());
        List<Long> affected = assignmentMapper.findRoleUserIds(roleId);
        role.setDataScope(request.dataScope());
        if (roleMapper.updateById(role) != 1) {
            throw BusinessException.versionConflict();
        }
        assignmentMapper.deleteRolePermissions(roleId);
        if (!permissionIds.isEmpty()) {
            assignmentMapper.insertRolePermissions(roleId, permissionIds);
        }
        replaceDepartments(roleId, customDepartments);
        assignmentMapper.deleteRoleMenus(roleId);
        assignmentMapper.insertRoleMenusForPermissions(roleId);
        while (assignmentMapper.insertRoleMenuParents(roleId) > 0) {
            // Build the complete ancestor closure for arbitrarily deep menus.
        }
        invalidateRole(roleId, affected, actor.userId(), requestId);
        outbox.audit("ROLE_PERMISSIONS_REPLACED", "ROLE", Long.toString(roleId), actor.userId(), requestId);
        return assembler.rolePermissions(required(roleId));
    }

    private List<Long> validateScope(DataScope scope, List<String> requested) {
        List<Long> ids = requested == null ? List.of() : requested.stream().map(this::parseId).distinct().toList();
        if (scope != DataScope.CUSTOM && !ids.isEmpty()) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                    "CUSTOM 以外的数据范围不能包含自定义部门");
        }
        if (scope == DataScope.CUSTOM && ids.isEmpty()) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "CUSTOM 数据范围至少需要一个部门");
        }
        if (!ids.isEmpty() && departmentMapper.selectBatchIds(ids).size() != ids.size()) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "包含不存在的部门");
        }
        return ids;
    }

    private void replaceDepartments(long roleId, List<Long> departmentIds) {
        assignmentMapper.deleteRoleDepartments(roleId);
        if (!departmentIds.isEmpty()) {
            assignmentMapper.insertRoleDepartments(roleId, departmentIds);
        }
    }

    private void invalidateRole(long roleId,
                                List<Long> affected,
                                String actorId,
                                String requestId) {
        affected.forEach(authorization::evict);
        outbox.identity("ROLE_PERMISSION_CHANGED", "ROLE", Long.toString(roleId), actorId, requestId,
                Map.of("roleId", Long.toString(roleId), "affectedUserIds",
                        affected.stream().map(String::valueOf).toList()));
    }

    private RoleEntity required(long roleId) {
        RoleEntity role = roleMapper.selectById(roleId);
        if (role == null) {
            throw BusinessException.notFound("角色不存在");
        }
        return role;
    }

    private long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "ID 格式不正确");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
