package com.redblack.office.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redblack.office.api.OfficeApiModels.FileSummary;
import com.redblack.office.domain.OfficeEnums.FileStatus;
import com.redblack.office.infrastructure.persistence.IdempotencyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 10, 0, 0);
    private IdempotencyTransactionService transactions;
    private IdempotencyService service;
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        transactions = mock(IdempotencyTransactionService.class);
        service = new IdempotencyService(mock(IdempotencyMapper.class), transactions,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC));
        jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("10003");
    }

    @Test
    void associatesPendingFileBeforeCompletingUpload() {
        when(transactions.claim(eq(10003L), eq("POST"), eq("/api/v1/files"), any(), any(), any(), any()))
                .thenAnswer(invocation -> new IdempotencyTransactionService.Claim(true,
                        record(invocation.getArgument(4), null, NOW)));
        when(transactions.complete(eq(10003L), eq("POST"), eq("/api/v1/files"), any(), any(), eq(90001L)))
                .thenReturn(true);
        FileSummary uploaded = uploaded();

        FileSummary result = service.executeFileUpload(jwt, "POST", "/api/v1/files", UUID.randomUUID().toString(),
                "request", FileSummary.class, () -> { }, () -> 90001L, ignored -> uploaded);

        assertThat(result).isSameAs(uploaded);
        verify(transactions).associateFile(eq(10003L), eq("POST"), eq("/api/v1/files"), any(), eq(90001L));
        verify(transactions).complete(eq(10003L), eq("POST"), eq("/api/v1/files"), any(), any(), eq(90001L));
    }

    @Test
    void resumesStaleProcessingUploadWithOriginalFileId() {
        when(transactions.claim(eq(10003L), eq("POST"), eq("/api/v1/files"), any(), any(), any(), any()))
                .thenAnswer(invocation -> new IdempotencyTransactionService.Claim(false,
                        record(invocation.getArgument(4), 90001L, NOW.minusMinutes(1))));
        when(transactions.complete(anyLong(), any(), any(), any(), any(), eq(90001L))).thenReturn(true);

        FileSummary result = service.executeFileUpload(jwt, "POST", "/api/v1/files", UUID.randomUUID().toString(),
                "request", FileSummary.class, () -> { },
                () -> { throw new AssertionError("must not create a second file"); }, ignored -> uploaded());

        assertThat(result.id()).isEqualTo("90001");
        verify(transactions, never()).associateFile(anyLong(), any(), any(), any(), anyLong());
    }

    @Test
    void rejectsConcurrentRetryWhileFirstUploadIsActive() {
        when(transactions.claim(eq(10003L), eq("POST"), eq("/api/v1/files"), any(), any(), any(), any()))
                .thenAnswer(invocation -> new IdempotencyTransactionService.Claim(false,
                        record(invocation.getArgument(4), 90001L, NOW.minusSeconds(5))));

        assertThatThrownBy(() -> service.executeFileUpload(jwt, "POST", "/api/v1/files",
                UUID.randomUUID().toString(), "request", FileSummary.class, () -> { },
                () -> 90002L, ignored -> uploaded()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("REQUEST_IN_PROGRESS"));
    }

    private IdempotencyMapper.Record record(String hash, Long fileId, LocalDateTime createdAt) {
        return new IdempotencyMapper.Record(hash, "PROCESSING", null, fileId, createdAt, NOW.plusHours(24));
    }

    private FileSummary uploaded() {
        return new FileSummary("90001", "evidence.pdf", "application/pdf", 4,
                FileStatus.TEMPORARY, OffsetDateTime.parse("2026-08-10T00:00:00Z"));
    }
}
