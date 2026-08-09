package com.redblack.identity.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redblack.identity.infrastructure.persistence.IdempotencyMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class IdempotencyService {
    private static final java.time.Duration RETENTION = java.time.Duration.ofHours(24);

    private final IdempotencyMapper mapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(IdempotencyMapper mapper, ObjectMapper objectMapper, Clock clock) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public <T> T execute(Jwt jwt,
                         String method,
                         String path,
                         String idempotencyKey,
                         Object request,
                         Class<T> responseType,
                         Runnable authorizationCheck,
                         Supplier<T> operation) {
        authorizationCheck.run();
        String key = normalizeKey(idempotencyKey);
        long actorId = parseActor(jwt);
        String requestHash = hash(request);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        for (int attempt = 0; attempt < 2; attempt++) {
            if (mapper.claim(actorId, method, path, key, requestHash, now, now.plus(RETENTION)) == 1) {
                T response = operation.get();
                String responseBody = serialize(response);
                if (mapper.complete(actorId, method, path, key, responseBody) != 1) {
                    throw new IllegalStateException("Failed to complete idempotency record");
                }
                return response;
            }

            IdempotencyMapper.IdempotencyRecord existing = mapper.find(actorId, method, path, key);
            if (existing != null && !existing.expiresAt().isAfter(now)) {
                mapper.deleteExpired(actorId, method, path, key, now);
                continue;
            }
            if (existing == null) {
                continue;
            }
            if (!existing.requestHash().equals(requestHash)) {
                throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "同一幂等键不能用于不同请求");
            }
            if (!"COMPLETED".equals(existing.status()) || existing.responseBody() == null) {
                throw new BusinessException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "相同请求正在处理中");
            }
            return deserialize(existing.responseBody(), responseType);
        }
        throw new BusinessException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "相同请求正在处理中");
    }

    private String normalizeKey(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (RuntimeException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Idempotency-Key 必须是 UUID");
        }
    }

    private long parseActor(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "访问令牌主体无效");
        }
    }

    private String hash(Object request) {
        try {
            byte[] body = objectMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to hash idempotent request", exception);
        }
    }

    private String serialize(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize idempotent response", exception);
        }
    }

    private <T> T deserialize(String response, Class<T> responseType) {
        try {
            return objectMapper.readValue(response, responseType);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to deserialize idempotent response", exception);
        }
    }
}
