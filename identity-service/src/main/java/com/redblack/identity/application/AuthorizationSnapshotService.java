package com.redblack.identity.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redblack.identity.api.IdentityApiModels.AuthorizationGrant;
import com.redblack.identity.api.IdentityApiModels.AuthorizationSnapshot;
import com.redblack.identity.api.IdentityApiModels.RoleRef;
import com.redblack.identity.domain.IdentityEnums.DataScope;
import com.redblack.identity.domain.IdentityEnums.EnabledStatus;
import com.redblack.identity.domain.RoleEntity;
import com.redblack.identity.domain.UserEntity;
import com.redblack.identity.infrastructure.persistence.AssignmentMapper;
import com.redblack.identity.infrastructure.persistence.DepartmentMapper;
import com.redblack.identity.infrastructure.persistence.MenuMapper;
import com.redblack.identity.infrastructure.persistence.UserMapper;
import com.redblack.identity.infrastructure.security.SecurityProperties;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class AuthorizationSnapshotService {
    private static final String AUTHORIZATION_KEY = "redblack:identity:authorization:";
    private static final String DENY_KEY = "redblack:identity:token-deny:";

    private final UserMapper userMapper;
    private final DepartmentMapper departmentMapper;
    private final MenuMapper menuMapper;
    private final AssignmentMapper assignmentMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SecurityProperties properties;
    private final Clock clock;

    public AuthorizationSnapshotService(UserMapper userMapper,
                                        DepartmentMapper departmentMapper,
                                        MenuMapper menuMapper,
                                        AssignmentMapper assignmentMapper,
                                        StringRedisTemplate redisTemplate,
                                        ObjectMapper objectMapper,
                                        SecurityProperties properties,
                                        Clock clock) {
        this.userMapper = userMapper;
        this.departmentMapper = departmentMapper;
        this.menuMapper = menuMapper;
        this.assignmentMapper = assignmentMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public AuthorizationSnapshot get(long userId) {
        String key = AUTHORIZATION_KEY + userId;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, AuthorizationSnapshot.class);
            }
        } catch (RedisConnectionFailureException ignored) {
            // MySQL is the authorization fact source.
        } catch (Exception invalidCache) {
            evict(userId);
        }

        AuthorizationSnapshot snapshot = rebuild(userId);
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(snapshot),
                    properties.getAuthorizationCacheTtl());
        } catch (Exception ignored) {
            // A cache failure must not replace a valid MySQL authorization result.
        }
        return snapshot;
    }

    public AuthorizationSnapshot requireActive(Jwt jwt) {
        long userId = parseId(jwt.getSubject());
        String jti = jwt.getId();
        if (jti != null && denied(jti)) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "访问令牌已失效");
        }
        AuthorizationSnapshot snapshot = get(userId);
        Number tokenVersion = jwt.getClaim("authVersion");
        if (snapshot.status() != EnabledStatus.ENABLED || tokenVersion == null
                || snapshot.authVersion() != tokenVersion.longValue()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "账号或会话已失效");
        }
        return snapshot;
    }

    public AuthorizationSnapshot require(Jwt jwt, String permission) {
        AuthorizationSnapshot snapshot = requireActive(jwt);
        if (!snapshot.permissions().contains(permission)) {
            throw BusinessException.denied();
        }
        return snapshot;
    }

    public void evict(long userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteCache(userId);
                }
            });
            return;
        }
        deleteCache(userId);
    }

    private void deleteCache(long userId) {
        try {
            redisTemplate.delete(AUTHORIZATION_KEY + userId);
        } catch (RedisConnectionFailureException ignored) {
            // The short-lived cache will be reconstructed later.
        }
    }

    public void deny(String jti, java.time.Duration ttl) {
        try {
            redisTemplate.opsForValue().set(DENY_KEY + jti, "1", ttl);
        } catch (RedisConnectionFailureException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                    "当前无法可靠结束会话，请稍后重试");
        }
    }

    private boolean denied(String jti) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(DENY_KEY + jti));
        } catch (RedisConnectionFailureException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                    "当前无法校验会话状态");
        }
    }

    private AuthorizationSnapshot rebuild(long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "账号不存在");
        }
        List<RoleEntity> roles = userMapper.findRoles(userId).stream()
                .filter(role -> role.getStatus() == EnabledStatus.ENABLED)
                .toList();
        List<RoleRef> roleRefs = roles.stream()
                .map(role -> new RoleRef(Long.toString(role.getId()), role.getCode(), role.getName()))
                .toList();
        List<String> permissions = List.copyOf(new TreeSet<>(userMapper.findPermissionCodes(userId)));
        List<AuthorizationGrant> grants = roles.stream()
                .map(role -> new AuthorizationGrant(role.getDataScope(), departmentsFor(role, user)))
                .toList();
        OffsetDateTime expiresAt = OffsetDateTime.ofInstant(
                clock.instant().plus(properties.getAuthorizationCacheTtl()), ZoneOffset.UTC);
        return new AuthorizationSnapshot(Long.toString(userId), user.getStatus(), user.getAuthVersion(),
                roleRefs, permissions, grants, menuMapper.findMenuTreeVersion(), expiresAt);
    }

    private List<String> departmentsFor(RoleEntity role, UserEntity user) {
        List<Long> ids = switch (role.getDataScope()) {
            case DEPARTMENT -> List.of(user.getDepartmentId());
            case DEPARTMENT_AND_CHILDREN -> departmentMapper.findDepartmentAndDescendantIds(user.getDepartmentId());
            case CUSTOM -> assignmentMapper.findRoleDepartmentIds(role.getId());
            case ALL, SELF -> List.of();
        };
        return ids.stream().map(String::valueOf).distinct().toList();
    }

    private long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (RuntimeException exception) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "访问令牌主体无效");
        }
    }
}
