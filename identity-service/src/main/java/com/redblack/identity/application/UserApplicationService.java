package com.redblack.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.redblack.identity.api.IdentityApiModels.*;
import com.redblack.identity.domain.*;
import com.redblack.identity.domain.IdentityEnums.DataScope;
import com.redblack.identity.domain.IdentityEnums.EnabledStatus;
import com.redblack.identity.infrastructure.persistence.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;

@Service
public class UserApplicationService {
    private static final String TEMPORARY_PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";

    private final UserMapper userMapper;
    private final DepartmentMapper departmentMapper;
    private final RoleMapper roleMapper;
    private final AssignmentMapper assignmentMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthorizationSnapshotService authorization;
    private final IdentityViewAssembler assembler;
    private final OutboxService outbox;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public UserApplicationService(UserMapper userMapper,
                                  DepartmentMapper departmentMapper,
                                  RoleMapper roleMapper,
                                  AssignmentMapper assignmentMapper,
                                  PasswordEncoder passwordEncoder,
                                  AuthorizationSnapshotService authorization,
                                  IdentityViewAssembler assembler,
                                  OutboxService outbox,
                                  Clock clock) {
        this.userMapper = userMapper;
        this.departmentMapper = departmentMapper;
        this.roleMapper = roleMapper;
        this.assignmentMapper = assignmentMapper;
        this.passwordEncoder = passwordEncoder;
        this.authorization = authorization;
        this.assembler = assembler;
        this.outbox = outbox;
        this.clock = clock;
    }

    public PageData<UserView> list(Jwt jwt,
                                   String keyword,
                                   Long departmentId,
                                   Long roleId,
                                   EnabledStatus status,
                                   int page,
                                   int pageSize,
                                   String sort) {
        AuthorizationSnapshot snapshot = authorization.require(jwt, "system:user:read");
        LambdaQueryWrapper<UserEntity> query = new LambdaQueryWrapper<UserEntity>()
                .eq(departmentId != null, UserEntity::getDepartmentId, departmentId)
                .eq(status != null, UserEntity::getStatus, status);
        if (hasText(keyword)) {
            query.and(term -> term.like(UserEntity::getName, keyword)
                    .or().like(UserEntity::getUsername, keyword)
                    .or().like(UserEntity::getPhone, keyword));
        }
        applyScope(query, snapshot);
        if (roleId != null) {
            query.apply("EXISTS (SELECT 1 FROM sys_user_role ur WHERE ur.user_id = sys_user.id AND ur.role_id = {0})", roleId);
        }
        applySort(query, sort);
        Page<UserEntity> result = userMapper.selectPage(Page.of(page, pageSize), query);
        return new PageData<>(result.getRecords().stream().map(assembler::user).toList(),
                page, pageSize, result.getTotal());
    }

    public UserView get(Jwt jwt, long userId) {
        AuthorizationSnapshot snapshot = authorization.require(jwt, "system:user:read");
        UserEntity user = requiredUser(userId);
        requireVisible(snapshot, user);
        return assembler.user(user);
    }

    public void authorizeCreate(Jwt jwt, CreateUserRequest request) {
        AuthorizationSnapshot actor = authorization.require(jwt, "system:user:create");
        requireWritableDepartment(actor, parseId(request.departmentId()));
    }

    public void authorizeResetPassword(Jwt jwt, long userId) {
        AuthorizationSnapshot actor = authorization.require(jwt, "system:user:reset-password");
        requireVisible(actor, requiredUser(userId));
    }

    @Transactional
    public UserView create(Jwt jwt, CreateUserRequest request, String requestId) {
        AuthorizationSnapshot actor = authorization.require(jwt, "system:user:create");
        long departmentId = parseId(request.departmentId());
        requireWritableDepartment(actor, departmentId);
        if (userMapper.findByUsername(request.username()) != null) {
            throw new BusinessException(HttpStatus.CONFLICT, "USERNAME_ALREADY_EXISTS", "用户名已存在");
        }
        Long leaderId = nullableId(request.leaderId());
        List<Long> roleIds = ids(request.roleIds());
        validateDepartment(departmentId);
        validateLeader(leaderId, null, departmentId);
        validateRoles(roleIds);

        UserEntity user = new UserEntity();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setName(request.name());
        user.setGender(request.gender());
        user.setPhone(request.phone());
        user.setEmail(request.email());
        user.setDepartmentId(departmentId);
        user.setLeaderId(leaderId);
        user.setStatus(request.status());
        user.setAuthVersion(1L);
        user.setRemark(request.remark());
        user.setVersion(1);
        userMapper.insert(user);
        assignmentMapper.insertUserRoles(user.getId(), roleIds);
        authorization.evict(user.getId());
        emitAuthorizationChange(user, actor.userId(), requestId, "ROLE_ASSIGNMENT");
        outbox.audit("USER_CREATED", "USER", Long.toString(user.getId()), actor.userId(), requestId);
        return assembler.user(requiredUser(user.getId()));
    }

    @Transactional
    public UserView update(Jwt jwt, long userId, UpdateUserRequest request, String requestId) {
        AuthorizationSnapshot actor = authorization.require(jwt, "system:user:update");
        UserEntity user = requiredUser(userId);
        requireVisible(actor, user);
        if (!user.getVersion().equals(request.version())) {
            throw BusinessException.versionConflict();
        }
        if (userId == Long.parseLong(actor.userId()) && request.status() == EnabledStatus.DISABLED) {
            throw currentUserForbidden();
        }
        long departmentId = parseId(request.departmentId());
        Long leaderId = nullableId(request.leaderId());
        List<Long> roleIds = ids(request.roleIds());
        requireWritableDepartment(actor, departmentId);
        validateDepartment(departmentId);
        validateLeader(leaderId, userId, departmentId);
        validateRoles(roleIds);

        boolean rolesChanged = !new HashSet<>(assignmentMapper.findUserRoleIds(userId)).equals(new HashSet<>(roleIds));
        boolean departmentChanged = !Objects.equals(user.getDepartmentId(), departmentId);
        boolean leaderChanged = !Objects.equals(user.getLeaderId(), leaderId);
        boolean statusChanged = user.getStatus() != request.status();
        user.setName(request.name());
        user.setGender(request.gender());
        user.setPhone(request.phone());
        user.setEmail(request.email());
        user.setDepartmentId(departmentId);
        user.setLeaderId(leaderId);
        user.setStatus(request.status());
        user.setRemark(request.remark());
        if (rolesChanged || departmentChanged || leaderChanged || statusChanged) {
            user.setAuthVersion(user.getAuthVersion() + 1);
        }
        if (userMapper.updateById(user) != 1) {
            throw BusinessException.versionConflict();
        }
        assignmentMapper.deleteUserRoles(userId);
        assignmentMapper.insertUserRoles(userId, roleIds);
        authorization.evict(userId);
        if (statusChanged) {
            outbox.identity("USER_STATUS_CHANGED", "USER", Long.toString(userId), actor.userId(), requestId,
                    Map.of("userId", Long.toString(userId), "status", request.status().name(),
                            "authVersion", user.getAuthVersion()));
        }
        if (rolesChanged || departmentChanged || leaderChanged) {
            String reason = rolesChanged ? "ROLE_ASSIGNMENT" : departmentChanged ? "DEPARTMENT_CHANGED" : "LEADER_CHANGED";
            emitAuthorizationChange(user, actor.userId(), requestId, reason);
        }
        outbox.audit("USER_UPDATED", "USER", Long.toString(userId), actor.userId(), requestId);
        return assembler.user(requiredUser(userId));
    }

    @Transactional
    public UserView changeStatus(Jwt jwt, long userId, ChangeStatusRequest request, String requestId) {
        AuthorizationSnapshot actor = authorization.require(jwt, "system:user:update");
        UserEntity user = requiredUser(userId);
        requireVisible(actor, user);
        if (userId == Long.parseLong(actor.userId())) {
            throw currentUserForbidden();
        }
        if (!user.getVersion().equals(request.version())) {
            throw BusinessException.versionConflict();
        }
        user.setStatus(request.status());
        user.setAuthVersion(user.getAuthVersion() + 1);
        if (userMapper.updateById(user) != 1) {
            throw BusinessException.versionConflict();
        }
        authorization.evict(userId);
        outbox.identity("USER_STATUS_CHANGED", "USER", Long.toString(userId), actor.userId(), requestId,
                Map.of("userId", Long.toString(userId), "status", request.status().name(),
                        "authVersion", user.getAuthVersion()));
        outbox.audit("USER_STATUS_CHANGED", "USER", Long.toString(userId), actor.userId(), requestId);
        return assembler.user(requiredUser(userId));
    }

    @Transactional
    public ResetPasswordData resetPassword(Jwt jwt, long userId, ResetPasswordRequest request, String requestId) {
        AuthorizationSnapshot actor = authorization.require(jwt, "system:user:reset-password");
        UserEntity user = requiredUser(userId);
        requireVisible(actor, user);
        if (!user.getVersion().equals(request.version())) {
            throw BusinessException.versionConflict();
        }
        String temporary = temporaryPassword();
        LocalDateTime expiresAt = LocalDateTime.ofInstant(clock.instant().plus(Duration.ofHours(24)), ZoneOffset.UTC);
        user.setPasswordHash(passwordEncoder.encode(temporary));
        user.setCredentialExpiresAt(expiresAt);
        user.setAuthVersion(user.getAuthVersion() + 1);
        if (userMapper.updateById(user) != 1) {
            throw BusinessException.versionConflict();
        }
        authorization.evict(userId);
        emitAuthorizationChange(user, actor.userId(), requestId, "PASSWORD_RESET");
        outbox.audit("USER_PASSWORD_RESET", "USER", Long.toString(userId), actor.userId(), requestId);
        return new ResetPasswordData(temporary, expiresAt.atOffset(ZoneOffset.UTC));
    }

    @Transactional
    public void delete(Jwt jwt, long userId, int version, String requestId) {
        AuthorizationSnapshot actor = authorization.require(jwt, "system:user:delete");
        if (userId == Long.parseLong(actor.userId())) {
            throw currentUserForbidden();
        }
        UserEntity user = requiredUser(userId);
        requireVisible(actor, user);
        if (!user.getVersion().equals(version)) {
            throw BusinessException.versionConflict();
        }
        if (userMapper.countDirectReports(userId) > 0 || departmentMapper.countLeaderReferences(userId) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "USER_HAS_BUSINESS_DATA", "用户仍被组织数据引用，不能删除");
        }
        assignmentMapper.deleteUserRoles(userId);
        userMapper.deleteById(userId);
        authorization.evict(userId);
        outbox.audit("USER_DELETED", "USER", Long.toString(userId), actor.userId(), requestId);
    }

    public List<UserRef> options(Jwt jwt, String keyword, Long departmentId, EnabledStatus status, int limit) {
        AuthorizationSnapshot snapshot = authorization.requireActive(jwt);
        LambdaQueryWrapper<UserEntity> query = new LambdaQueryWrapper<UserEntity>()
                .like(hasText(keyword), UserEntity::getName, keyword)
                .eq(departmentId != null, UserEntity::getDepartmentId, departmentId)
                .eq(status != null, UserEntity::getStatus, status)
                .orderByAsc(UserEntity::getName)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 100));
        applyOptionScope(query, snapshot);
        return userMapper.selectList(query).stream().map(assembler::ref).toList();
    }

    public PageData<UserView> roleMembers(Jwt jwt, long roleId, int page, int pageSize) {
        authorization.require(jwt, "system:role:read");
        if (roleMapper.selectById(roleId) == null) {
            throw BusinessException.notFound("角色不存在");
        }
        long total = userMapper.countRoleMembers(roleId);
        List<UserView> items = userMapper.findRoleMembers(roleId, (long) (page - 1) * pageSize, pageSize)
                .stream().map(assembler::user).toList();
        return new PageData<>(items, page, pageSize, total);
    }

    private void applyScope(LambdaQueryWrapper<UserEntity> query, AuthorizationSnapshot snapshot) {
        if (snapshot.grants().stream().anyMatch(grant -> grant.scope() == DataScope.ALL)) {
            return;
        }
        Set<Long> departments = snapshot.grants().stream()
                .flatMap(grant -> grant.departmentIds().stream())
                .map(this::parseId).collect(java.util.stream.Collectors.toSet());
        boolean self = snapshot.grants().stream().anyMatch(grant -> grant.scope() == DataScope.SELF);
        if (departments.isEmpty() && !self) {
            query.eq(UserEntity::getId, -1L);
        } else {
            query.and(scope -> {
                boolean first = true;
                if (!departments.isEmpty()) {
                    scope.in(UserEntity::getDepartmentId, departments);
                    first = false;
                }
                if (self) {
                    if (first) {
                        scope.eq(UserEntity::getId, parseId(snapshot.userId()));
                    } else {
                        scope.or().eq(UserEntity::getId, parseId(snapshot.userId()));
                    }
                }
            });
        }
    }

    private void applyOptionScope(LambdaQueryWrapper<UserEntity> query, AuthorizationSnapshot snapshot) {
        boolean selfOnly = snapshot.grants().stream().anyMatch(grant -> grant.scope() == DataScope.SELF)
                && snapshot.grants().stream().noneMatch(grant -> grant.scope() != DataScope.SELF);
        if (!selfOnly) {
            applyScope(query, snapshot);
            return;
        }
        UserEntity currentUser = userMapper.selectById(parseId(snapshot.userId()));
        if (currentUser == null || currentUser.getDepartmentId() == null) {
            query.eq(UserEntity::getId, -1L);
            return;
        }
        query.eq(UserEntity::getDepartmentId, currentUser.getDepartmentId());
    }

    private void requireVisible(AuthorizationSnapshot snapshot, UserEntity user) {
        if (snapshot.grants().stream().anyMatch(grant -> grant.scope() == DataScope.ALL)) {
            return;
        }
        boolean self = snapshot.grants().stream().anyMatch(grant -> grant.scope() == DataScope.SELF)
                && user.getId().toString().equals(snapshot.userId());
        boolean department = snapshot.grants().stream().flatMap(grant -> grant.departmentIds().stream())
                .anyMatch(id -> id.equals(user.getDepartmentId().toString()));
        if (!self && !department) {
            throw BusinessException.notFound("用户不存在");
        }
    }

    private void requireWritableDepartment(AuthorizationSnapshot snapshot, long departmentId) {
        if (snapshot.grants().stream().anyMatch(grant -> grant.scope() == DataScope.ALL)) {
            return;
        }
        boolean allowed = snapshot.grants().stream()
                .flatMap(grant -> grant.departmentIds().stream())
                .anyMatch(id -> id.equals(Long.toString(departmentId)));
        if (!allowed) {
            throw BusinessException.notFound("部门不存在");
        }
    }

    private void validateDepartment(long departmentId) {
        DepartmentEntity department = departmentMapper.selectById(departmentId);
        if (department == null || department.getStatus() != EnabledStatus.ENABLED) {
            throw BusinessException.notFound("部门不存在或已禁用");
        }
    }

    private void validateLeader(Long leaderId, Long userId, long departmentId) {
        if (leaderId == null) {
            return;
        }
        if (leaderId.equals(userId)) {
            throw invalidLeader();
        }
        UserEntity leader = userMapper.selectById(leaderId);
        if (leader == null || leader.getStatus() != EnabledStatus.ENABLED
                || !leader.getDepartmentId().equals(departmentId)) {
            throw invalidLeader();
        }
    }

    private void validateRoles(List<Long> roleIds) {
        if (roleIds.isEmpty() || roleMapper.selectBatchIds(roleIds).stream()
                .filter(role -> role.getStatus() == EnabledStatus.ENABLED).count() != new HashSet<>(roleIds).size()) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "角色不存在或已禁用");
        }
    }

    private UserEntity requiredUser(long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.notFound("用户不存在");
        }
        return user;
    }

    private void emitAuthorizationChange(UserEntity user, String actorId, String requestId, String reason) {
        outbox.identity("USER_AUTHORIZATION_CHANGED", "USER", Long.toString(user.getId()), actorId, requestId,
                Map.of("userId", Long.toString(user.getId()), "authVersion", user.getAuthVersion(), "reason", reason));
    }

    private void applySort(LambdaQueryWrapper<UserEntity> query, String sort) {
        String value = sort == null ? "createdAt,desc" : sort;
        boolean asc = value.endsWith(",asc");
        if (value.startsWith("name,")) {
            query.orderBy(true, asc, UserEntity::getName);
        } else if (value.startsWith("username,")) {
            query.orderBy(true, asc, UserEntity::getUsername);
        } else {
            query.orderBy(true, asc, UserEntity::getCreatedAt);
        }
    }

    private String temporaryPassword() {
        StringBuilder value = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            value.append(TEMPORARY_PASSWORD_ALPHABET.charAt(secureRandom.nextInt(TEMPORARY_PASSWORD_ALPHABET.length())));
        }
        return value.toString();
    }

    private List<Long> ids(List<String> values) {
        return values.stream().map(this::parseId).distinct().toList();
    }

    private Long nullableId(String value) {
        return hasText(value) ? parseId(value) : null;
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

    private BusinessException invalidLeader() {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_LEADER", "直属领导不符合规则");
    }

    private BusinessException currentUserForbidden() {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENT_USER_OPERATION_FORBIDDEN",
                "不能禁用或删除当前用户");
    }
}
