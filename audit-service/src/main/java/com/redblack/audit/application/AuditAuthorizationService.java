package com.redblack.audit.application;

import com.redblack.audit.infrastructure.identity.IdentityClient;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class AuditAuthorizationService {
    private final IdentityClient identity;
    private final StringRedisTemplate redis;
    public AuditAuthorizationService(IdentityClient identity, StringRedisTemplate redis) {
        this.identity = identity; this.redis = redis;
    }
    public long requireRead(Jwt jwt, String requestId) {
        long userId;
        try { userId = Long.parseLong(jwt.getSubject()); }
        catch (RuntimeException exception) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "访问令牌主体无效");
        }
        if (jwt.getId() != null) {
            try {
                if (Boolean.TRUE.equals(redis.hasKey("redblack:identity:token-deny:" + jwt.getId()))) {
                    throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "访问令牌已失效");
                }
            } catch (DataAccessException exception) {
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "当前无法可靠校验会话状态");
            }
        }
        var snapshot = identity.authorization(userId, requestId);
        Number version = jwt.getClaim("authVersion");
        if (!"ENABLED".equals(snapshot.status()) || version == null || version.longValue() != snapshot.authVersion()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "账号或会话已失效");
        }
        if (!snapshot.permissions().contains("audit:operation-log:read")) throw BusinessException.denied();
        return userId;
    }
}
