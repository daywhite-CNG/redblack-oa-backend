package com.redblack.office.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redblack.office.api.OfficeApiModels.FileSummary;
import com.redblack.office.domain.OfficeEnums.FileStatus;
import com.redblack.office.infrastructure.persistence.IdempotencyMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyServiceTest {
    @Test
    void associatesCompletedUploadWithFileId() {
        IdempotencyMapper mapper = mock(IdempotencyMapper.class);
        IdempotencyService service = new IdempotencyService(mapper, new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC));
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("10003");
        when(mapper.claim(eq(10003L), eq("POST"), eq("/api/v1/files"), any(), any(), any(), any()))
                .thenReturn(1);
        when(mapper.complete(eq(10003L), eq("POST"), eq("/api/v1/files"), any(), any(), eq(90001L)))
                .thenReturn(1);
        FileSummary uploaded = new FileSummary("90001", "evidence.pdf", "application/pdf", 4,
                FileStatus.TEMPORARY, OffsetDateTime.parse("2026-08-10T00:00:00Z"));

        FileSummary result = service.execute(jwt, "POST", "/api/v1/files", UUID.randomUUID().toString(),
                "request", FileSummary.class, () -> { }, () -> uploaded,
                response -> Long.parseLong(response.id()));

        assertThat(result).isSameAs(uploaded);
        verify(mapper).complete(eq(10003L), eq("POST"), eq("/api/v1/files"), any(), any(), eq(90001L));
    }
}
