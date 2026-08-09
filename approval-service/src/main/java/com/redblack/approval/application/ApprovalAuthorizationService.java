package com.redblack.approval.application;

import com.redblack.approval.infrastructure.identity.IdentityClient;
import com.redblack.approval.infrastructure.identity.IdentityClient.AuthorizationGrant;
import com.redblack.approval.infrastructure.identity.IdentityClient.AuthorizationSnapshot;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

@Service
public class ApprovalAuthorizationService {
    private static final String DENY_KEY = "redblack:identity:token-deny:";
    private final IdentityClient identityClient;
    private final StringRedisTemplate redisTemplate;

    public ApprovalAuthorizationService(IdentityClient identityClient, StringRedisTemplate redisTemplate) {
        this.identityClient = identityClient;
        this.redisTemplate = redisTemplate;
    }

    public ActorAuthorization require(Jwt jwt, String permission, String requestId) {
        ActorAuthorization actor = requireActive(jwt, requestId);
        if (!actor.snapshot().permissions().contains(permission)) {
            throw BusinessException.denied();
        }
        return actor;
    }

    public ActorAuthorization requireActive(Jwt jwt, String requestId) {
        long actorId = parseId(jwt.getSubject());
        if (jwt.getId() != null && denied(jwt.getId())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "访问令牌已失效");
        }
        AuthorizationSnapshot snapshot = identityClient.authorization(actorId, requestId);
        Number tokenVersion = jwt.getClaim("authVersion");
        if (!"ENABLED".equals(snapshot.status()) || tokenVersion == null
                || snapshot.authVersion() != tokenVersion.longValue()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "账号或会话已失效");
        }
        return new ActorAuthorization(actorId, snapshot, dataAccess(snapshot));
    }

    private DataAccess dataAccess(AuthorizationSnapshot snapshot) {
        boolean all = false;
        boolean self = false;
        Set<Long> departments = new HashSet<>();
        for (AuthorizationGrant grant : snapshot.grants()) {
            if ("ALL".equals(grant.scope())) {
                all = true;
            } else if ("SELF".equals(grant.scope())) {
                self = true;
            } else if (grant.departmentIds() != null) {
                grant.departmentIds().forEach(id -> departments.add(parseId(id)));
            }
        }
        return new DataAccess(all, self, Set.copyOf(departments));
    }

    private boolean denied(String jti) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(DENY_KEY + jti));
        } catch (DataAccessException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                    "当前无法可靠校验会话状态");
        }
    }

    private long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException exception) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "访问令牌主体无效");
        }
    }

    public record ActorAuthorization(long actorId, AuthorizationSnapshot snapshot, DataAccess dataAccess) {
        public boolean has(String permission) {
            return snapshot.permissions().contains(permission);
        }
    }

    public record DataAccess(boolean all, boolean self, Set<Long> departmentIds) {
        public boolean includes(long departmentId) {
            return all || departmentIds.contains(departmentId);
        }
    }
}
