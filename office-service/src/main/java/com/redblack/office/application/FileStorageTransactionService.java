package com.redblack.office.application;

import com.redblack.office.domain.FileEntity;
import com.redblack.office.infrastructure.persistence.FileMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class FileStorageTransactionService {
    private static final int MAX_CLEANUP_ATTEMPTS = 10;
    private final FileMapper files;

    public FileStorageTransactionService(FileMapper files) {
        this.files = files;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long createPending(FileEntity entity) {
        if (files.insert(entity) != 1) throw new IllegalStateException("Failed to create pending file");
        return entity.getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAvailable(long fileId, String etag, LocalDateTime now) {
        if (files.markAvailable(fileId, etag, now) == 1) return;
        FileEntity current = files.selectById(fileId);
        if (current == null || !"AVAILABLE".equals(current.getStorageStatus())) {
            throw new IllegalStateException("Failed to mark file available");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markDeletePending(long fileId, long ownerId, LocalDateTime now) {
        return files.markDeletePending(fileId, ownerId, now) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<FileEntity> claimCleanupBatch(LocalDateTime now, LocalDateTime temporaryCutoff,
                                              LocalDateTime leaseUntil, int limit) {
        List<FileEntity> claimed = new ArrayList<>();
        for (FileEntity entity : files.findCleanupCandidatesForUpdate(now, temporaryCutoff, limit)) {
            String expected = entity.getStorageStatus();
            String claimedStatus = "AVAILABLE".equals(expected) ? "DELETE_PENDING" : expected;
            if (files.claimCleanup(entity.getId(), expected, claimedStatus, leaseUntil, now) == 1) {
                entity.setStorageStatus(claimedStatus);
                entity.setCleanupNextAttemptAt(leaseUntil);
                claimed.add(entity);
            }
        }
        return claimed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCleanupFailure(FileEntity entity, LocalDateTime now, String error) {
        int attempts = Math.min(entity.getCleanupAttempts() == null ? 1 : entity.getCleanupAttempts() + 1,
                MAX_CLEANUP_ATTEMPTS);
        long delayMinutes = Math.min(60, 1L << Math.min(attempts - 1, 6));
        LocalDateTime next = attempts >= MAX_CLEANUP_ATTEMPTS ? null : now.plusMinutes(delayMinutes);
        String message = error == null ? "对象存储清理失败" : error.substring(0, Math.min(error.length(), 1000));
        files.recordCleanupFailure(entity.getId(), entity.getStorageStatus(), attempts, next, message, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean delete(long fileId, String storageStatus) {
        return files.deleteByStorageStatus(fileId, storageStatus) == 1;
    }
}
