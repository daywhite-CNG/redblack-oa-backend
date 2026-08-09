package com.redblack.office.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redblack.office.infrastructure.persistence.IdempotencyMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class IdempotencyService {
    private static final Duration RETENTION = Duration.ofHours(24);
    private final IdempotencyMapper mapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(IdempotencyMapper mapper, ObjectMapper objectMapper, Clock clock) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public <T> T execute(Jwt jwt, String method, String path, String idempotencyKey, Object request,
                         Class<T> responseType, Runnable authorization, Supplier<T> operation) {
        authorization.run();
        String key = normalize(idempotencyKey);
        long actorId = actor(jwt);
        String requestHash = hash(request);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (int attempt = 0; attempt < 2; attempt++) {
            if (mapper.claim(actorId, method, path, key, requestHash, now, now.plus(RETENTION)) == 1) {
                T response = operation.get();
                if (mapper.complete(actorId, method, path, key, write(response)) != 1) {
                    throw new IllegalStateException("Failed to complete idempotency record");
                }
                return response;
            }
            IdempotencyMapper.Record existing = mapper.find(actorId, method, path, key);
            if (existing != null && !existing.expiresAt().isAfter(now)) {
                mapper.deleteExpired(actorId, method, path, key, now);
                continue;
            }
            if (existing == null) continue;
            if (!existing.requestHash().equals(requestHash)) {
                throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "同一幂等键不能用于不同请求");
            }
            if (!"COMPLETED".equals(existing.status()) || existing.responseBody() == null) {
                throw new BusinessException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "相同请求正在处理中");
            }
            return read(existing.responseBody(), responseType);
        }
        throw new BusinessException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "相同请求正在处理中");
    }

    private String normalize(String value) {
        try { return UUID.fromString(value).toString(); }
        catch (RuntimeException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Idempotency-Key 必须是 UUID");
        }
    }
    private long actor(Jwt jwt) {
        try { return Long.parseLong(jwt.getSubject()); }
        catch (RuntimeException exception) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "访问令牌主体无效");
        }
    }
    private String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("Failed to hash request", exception); }
    }
    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Failed to serialize response", exception); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return objectMapper.readValue(value, type); }
        catch (Exception exception) { throw new IllegalStateException("Failed to deserialize response", exception); }
    }
}
