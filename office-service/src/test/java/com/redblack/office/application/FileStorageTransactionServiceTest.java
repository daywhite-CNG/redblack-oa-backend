package com.redblack.office.application;

import com.redblack.office.domain.FileEntity;
import com.redblack.office.infrastructure.persistence.FileMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileStorageTransactionServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 10, 0, 0);

    @Test
    void claimsExpiredAvailableFileAsDeletePending() {
        FileMapper mapper = mock(FileMapper.class);
        FileStorageTransactionService service = new FileStorageTransactionService(mapper);
        FileEntity file = file("AVAILABLE", 0);
        when(mapper.findCleanupCandidatesForUpdate(NOW, NOW.minusHours(24), 100))
                .thenReturn(List.of(file));
        when(mapper.claimCleanup(1L, "AVAILABLE", "DELETE_PENDING", NOW.plusMinutes(5), NOW)).thenReturn(1);

        List<FileEntity> claimed = service.claimCleanupBatch(
                NOW, NOW.minusHours(24), NOW.plusMinutes(5), 100);

        assertThat(claimed).singleElement().extracting(FileEntity::getStorageStatus)
                .isEqualTo("DELETE_PENDING");
    }

    @Test
    void appliesBoundedCleanupBackoffAndStopsAfterTenFailures() {
        FileMapper mapper = mock(FileMapper.class);
        FileStorageTransactionService service = new FileStorageTransactionService(mapper);
        FileEntity first = file("DELETE_PENDING", 0);
        service.recordCleanupFailure(first, NOW, "temporary failure");
        verify(mapper).recordCleanupFailure(1L, "DELETE_PENDING", 1, NOW.plusMinutes(1),
                "temporary failure", NOW);

        FileEntity last = file("DELETE_PENDING", 9);
        service.recordCleanupFailure(last, NOW, "final failure");
        verify(mapper).recordCleanupFailure(1L, "DELETE_PENDING", 10, null,
                "final failure", NOW);
    }

    private FileEntity file(String storageStatus, int attempts) {
        FileEntity entity = new FileEntity();
        entity.setId(1L);
        entity.setStorageStatus(storageStatus);
        entity.setCleanupAttempts(attempts);
        return entity;
    }
}
