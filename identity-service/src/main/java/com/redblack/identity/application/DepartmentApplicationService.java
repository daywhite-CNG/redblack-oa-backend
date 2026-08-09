package com.redblack.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redblack.identity.api.IdentityApiModels.*;
import com.redblack.identity.domain.DepartmentEntity;
import com.redblack.identity.domain.IdentityEnums.DataScope;
import com.redblack.identity.domain.IdentityEnums.EnabledStatus;
import com.redblack.identity.domain.UserEntity;
import com.redblack.identity.infrastructure.persistence.DepartmentMapper;
import com.redblack.identity.infrastructure.persistence.UserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class DepartmentApplicationService {
    private final DepartmentMapper departmentMapper;
    private final UserMapper userMapper;
    private final AuthorizationSnapshotService authorization;
    private final IdentityViewAssembler assembler;
    private final OutboxService outbox;

    public DepartmentApplicationService(DepartmentMapper departmentMapper,
                                        UserMapper userMapper,
                                        AuthorizationSnapshotService authorization,
                                        IdentityViewAssembler assembler,
                                        OutboxService outbox) {
        this.departmentMapper = departmentMapper;
        this.userMapper = userMapper;
        this.authorization = authorization;
        this.assembler = assembler;
        this.outbox = outbox;
    }

    public List<DepartmentNode> tree(Jwt jwt, boolean enabledOnly) {
        AuthorizationSnapshot snapshot = authorization.requireActive(jwt);
        boolean canRead = snapshot.permissions().contains("system:department:read");
        Set<Long> allowed = allowedDepartments(snapshot, canRead);
        List<DepartmentNode> tree = assembler.departmentTree(allowed);
        return enabledOnly ? filterEnabled(tree) : tree;
    }

    public DepartmentNode get(Jwt jwt, long departmentId) {
        AuthorizationSnapshot snapshot = authorization.require(jwt, "system:department:read");
        DepartmentEntity department = required(departmentId);
        if (!allowedDepartments(snapshot, true).contains(departmentId)
                && snapshot.grants().stream().noneMatch(grant -> grant.scope() == DataScope.ALL)) {
            throw BusinessException.notFound("部门不存在");
        }
        return assembler.department(department);
    }

    public void authorizeCreate(Jwt jwt, CreateDepartmentRequest request) {
        AuthorizationSnapshot actor = authorization.require(jwt, "system:department:create");
        requireWritableDepartment(actor, nullableId(request.parentId()));
    }

    @Transactional
    public DepartmentNode create(Jwt jwt, CreateDepartmentRequest request, String requestId) {
        AuthorizationSnapshot actor = authorization.require(jwt, "system:department:create");
        Long parentId = nullableId(request.parentId());
        requireWritableDepartment(actor, parentId);
        if (parentId != null) {
            required(parentId);
        }
        ensureUnique(parentId, request.name(), null);
        DepartmentEntity department = new DepartmentEntity();
        department.setParentId(parentId);
        department.setName(request.name());
        department.setSortOrder(request.sortOrder());
        department.setStatus(request.status());
        department.setVersion(1);
        departmentMapper.insert(department);
        if (request.leaderId() != null) {
            long leaderId = parseId(request.leaderId());
            validateLeader(leaderId, department.getId());
            department.setLeaderId(leaderId);
            departmentMapper.updateById(department);
        }
        outbox.audit("DEPARTMENT_CREATED", "DEPARTMENT", Long.toString(department.getId()), actor.userId(), requestId);
        return assembler.department(required(department.getId()));
    }

    @Transactional
    public DepartmentNode update(Jwt jwt, long departmentId, UpdateDepartmentRequest request, String requestId) {
        AuthorizationSnapshot actor = authorization.require(jwt, "system:department:update");
        DepartmentEntity department = required(departmentId);
        requireWritableDepartment(actor, departmentId);
        if (!department.getVersion().equals(request.version())) {
            throw BusinessException.versionConflict();
        }
        Long parentId = nullableId(request.parentId());
        requireWritableDepartment(actor, parentId);
        if (parentId != null) {
            required(parentId);
            if (parentId == departmentId || departmentMapper.candidateIsDescendant(departmentId, parentId) > 0) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "DEPARTMENT_CYCLE_DETECTED",
                        "部门不能移动到自身或下级部门");
            }
        }
        ensureUnique(parentId, request.name(), departmentId);
        Long leaderId = nullableId(request.leaderId());
        if (leaderId != null) {
            validateLeader(leaderId, departmentId);
        }
        department.setParentId(parentId);
        department.setName(request.name());
        department.setLeaderId(leaderId);
        department.setSortOrder(request.sortOrder());
        department.setStatus(request.status());
        if (departmentMapper.updateById(department) != 1) {
            throw BusinessException.versionConflict();
        }
        evictAllUsers();
        outbox.audit("DEPARTMENT_UPDATED", "DEPARTMENT", Long.toString(departmentId), actor.userId(), requestId);
        return assembler.department(required(departmentId));
    }

    @Transactional
    public DepartmentNode changeStatus(Jwt jwt, long departmentId, ChangeStatusRequest request, String requestId) {
        AuthorizationSnapshot actor = authorization.require(jwt, "system:department:update");
        DepartmentEntity department = required(departmentId);
        requireWritableDepartment(actor, departmentId);
        if (!department.getVersion().equals(request.version())) {
            throw BusinessException.versionConflict();
        }
        department.setStatus(request.status());
        if (departmentMapper.updateById(department) != 1) {
            throw BusinessException.versionConflict();
        }
        evictAllUsers();
        outbox.audit("DEPARTMENT_STATUS_CHANGED", "DEPARTMENT", Long.toString(departmentId), actor.userId(), requestId);
        return assembler.department(required(departmentId));
    }

    @Transactional
    public void delete(Jwt jwt, long departmentId, int version, String requestId) {
        AuthorizationSnapshot actor = authorization.require(jwt, "system:department:delete");
        DepartmentEntity department = required(departmentId);
        requireWritableDepartment(actor, departmentId);
        if (!department.getVersion().equals(version)) {
            throw BusinessException.versionConflict();
        }
        if (departmentMapper.countChildren(departmentId) > 0
                || userMapper.countDepartmentMembers(departmentId) > 0
                || departmentMapper.countRoleReferences(departmentId) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "DEPARTMENT_NOT_EMPTY", "部门包含子部门或启用用户");
        }
        departmentMapper.deleteById(departmentId);
        outbox.audit("DEPARTMENT_DELETED", "DEPARTMENT", Long.toString(departmentId), actor.userId(), requestId);
    }

    private Set<Long> allowedDepartments(AuthorizationSnapshot snapshot, boolean canRead) {
        if (canRead && snapshot.grants().stream().anyMatch(grant -> grant.scope() == DataScope.ALL)) {
            return new HashSet<>(departmentMapper.selectList(null).stream().map(DepartmentEntity::getId).toList());
        }
        Set<Long> ids = new HashSet<>();
        snapshot.grants().forEach(grant -> grant.departmentIds().forEach(id -> ids.add(parseId(id))));
        if (ids.isEmpty()) {
            UserEntity user = userMapper.selectById(parseId(snapshot.userId()));
            if (user != null) {
                ids.add(user.getDepartmentId());
            }
        }
        return ids;
    }

    private List<DepartmentNode> filterEnabled(List<DepartmentNode> nodes) {
        return nodes.stream().filter(node -> node.status() == EnabledStatus.ENABLED)
                .map(node -> new DepartmentNode(node.id(), node.parentId(), node.name(), node.leader(), node.sortOrder(),
                        node.status(), node.memberCount(), node.createdAt(), node.version(), filterEnabled(node.children())))
                .toList();
    }

    private void requireWritableDepartment(AuthorizationSnapshot snapshot, Long departmentId) {
        if (snapshot.grants().stream().anyMatch(grant -> grant.scope() == DataScope.ALL)) {
            return;
        }
        boolean allowed = departmentId != null && snapshot.grants().stream()
                .flatMap(grant -> grant.departmentIds().stream())
                .anyMatch(id -> id.equals(Long.toString(departmentId)));
        if (!allowed) {
            throw BusinessException.notFound("部门不存在");
        }
    }

    private void validateLeader(long leaderId, long departmentId) {
        UserEntity leader = userMapper.selectById(leaderId);
        if (leader == null || leader.getStatus() != EnabledStatus.ENABLED
                || !leader.getDepartmentId().equals(departmentId)) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_LEADER", "部门负责人不符合规则");
        }
    }

    private void ensureUnique(Long parentId, String name, Long excludedId) {
        long count = departmentMapper.selectCount(new LambdaQueryWrapper<DepartmentEntity>()
                .isNull(parentId == null, DepartmentEntity::getParentId)
                .eq(parentId != null, DepartmentEntity::getParentId, parentId)
                .eq(DepartmentEntity::getName, name)
                .ne(excludedId != null, DepartmentEntity::getId, excludedId));
        if (count > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "DEPARTMENT_NAME_EXISTS", "同级部门名称已存在");
        }
    }

    private void evictAllUsers() {
        userMapper.findAllIds().forEach(authorization::evict);
    }

    private DepartmentEntity required(long id) {
        DepartmentEntity department = departmentMapper.selectById(id);
        if (department == null) {
            throw BusinessException.notFound("部门不存在");
        }
        return department;
    }

    private Long nullableId(String value) {
        return value == null || value.isBlank() ? null : parseId(value);
    }

    private long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "ID 格式不正确");
        }
    }
}
