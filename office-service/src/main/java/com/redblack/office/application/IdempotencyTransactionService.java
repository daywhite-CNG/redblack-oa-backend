package com.redblack.office.application;

import com.redblack.office.infrastructure.persistence.IdempotencyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class IdempotencyTransactionService {
    private final IdempotencyMapper mapper;

    public IdempotencyTransactionService(IdempotencyMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(long actorId, String method, String path, String key, String hash,
                       LocalDateTime now, LocalDateTime expiresAt) {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (mapper.claim(actorId, method, path, key, hash, now, expiresAt) == 1) {
                return new Claim(true, mapper.find(actorId, method, path, key));
            }
            IdempotencyMapper.Record existing = mapper.find(actorId, method, path, key);
            if (existing != null && !existing.expiresAt().isAfter(now)) {
                mapper.deleteExpired(actorId, method, path, key, now);
                continue;
            }
            return new Claim(false, existing);
        }
        return new Claim(false, mapper.find(actorId, method, path, key));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void associateFile(long actorId, String method, String path, String key, long fileId) {
        if (mapper.associateFile(actorId, method, path, key, fileId) != 1) {
            IdempotencyMapper.Record current = mapper.find(actorId, method, path, key);
            if (current == null || current.fileId() == null || current.fileId() != fileId) {
                throw new IllegalStateException("Failed to associate idempotency record with file");
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(long actorId, String method, String path, String key, String body, long fileId) {
        return mapper.complete(actorId, method, path, key, body, fileId) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public IdempotencyMapper.Record find(long actorId, String method, String path, String key) {
        return mapper.find(actorId, method, path, key);
    }

    public record Claim(boolean created, IdempotencyMapper.Record record) { }
}
