package com.redblack.office.application;

import com.redblack.office.infrastructure.identity.IdentityClient;
import com.redblack.office.infrastructure.identity.IdentityClient.AuthorizationSnapshot;
import com.redblack.office.infrastructure.identity.IdentityClient.UserContext;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class OfficeAuthorizationService {
    private static final String DENY_KEY = "redblack:identity:token-deny:";
    private final IdentityClient identityClient;
    private final StringRedisTemplate redisTemplate;

    public OfficeAuthorizationService(IdentityClient identityClient, StringRedisTemplate redisTemplate) {
        this.identityClient = identityClient;
        this.redisTemplate = redisTemplate;
    }

    public Actor require(Jwt jwt, String permission, String requestId) {
        Actor actor = requireActive(jwt, requestId);
        if (!actor.authorization().permissions().contains(permission)) throw BusinessException.denied();
        return actor;
    }

    public Actor requireActive(Jwt jwt, String requestId) {
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
        UserContext context = identityClient.userContext(actorId, requestId);
        return new Actor(actorId, snapshot, context);
    }

    private boolean denied(String jti) {
        try { return Boolean.TRUE.equals(redisTemplate.hasKey(DENY_KEY + jti)); }
        catch (DataAccessException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                    "当前无法可靠校验会话状态");
        }
    }

    private long parseId(String value) {
        try { return Long.parseLong(value); }
        catch (RuntimeException exception) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "访问令牌主体无效");
        }
    }

    public record Actor(long id, AuthorizationSnapshot authorization, UserContext context) {
        public boolean has(String permission) { return authorization.permissions().contains(permission); }
        public long departmentId() { return Long.parseLong(context.departmentId()); }
    }
}
