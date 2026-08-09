package com.redblack.identity.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redblack.identity.infrastructure.persistence.IdempotencyMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdempotencyServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-01T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void storesFirstResultAndDoesNotRunOperationOnReplay() {
        IdempotencyMapper mapper = mock(IdempotencyMapper.class);
        when(mapper.claim(anyLong(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(1, 0);
        when(mapper.complete(anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        IdempotencyService service = new IdempotencyService(mapper, new ObjectMapper().findAndRegisterModules(), CLOCK);
        AtomicInteger calls = new AtomicInteger();
        String key = UUID.randomUUID().toString();
        SampleRequest request = new SampleRequest("same");

        SampleResponse first = service.execute(jwt(), "POST", "/resource", key, request,
                SampleResponse.class, () -> {}, () -> {
                    calls.incrementAndGet();
                    return new SampleResponse("first");
                });
        var hash = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mapper).claim(eq(10001L), eq("POST"), eq("/resource"), eq(key), hash.capture(), any(), any());
        when(mapper.find(anyLong(), anyString(), anyString(), anyString())).thenReturn(
                new IdempotencyMapper.IdempotencyRecord(hash.getValue(), "COMPLETED", "{\"value\":\"first\"}",
                        LocalDateTime.of(2026, 8, 2, 8, 0)));
        SampleResponse replay = service.execute(jwt(), "POST", "/resource", key, request,
                SampleResponse.class, () -> {}, () -> {
                    calls.incrementAndGet();
                    return new SampleResponse("second");
                });
        assertThat(first.value()).isEqualTo("first");
        assertThat(replay.value()).isEqualTo("first");
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsInvalidKeyBeforeCallingOperation() {
        IdempotencyService service = new IdempotencyService(mock(IdempotencyMapper.class), new ObjectMapper(), CLOCK);
        assertThatThrownBy(() -> service.execute(jwt(), "POST", "/resource", "not-a-uuid",
                new SampleRequest("x"), SampleResponse.class, () -> {}, () -> new SampleResponse("x")))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.code()).isEqualTo("VALIDATION_FAILED");
                });
    }

    @Test
    void rechecksAuthorizationBeforeReturningStoredResponse() {
        IdempotencyMapper mapper = mock(IdempotencyMapper.class);
        IdempotencyService service = new IdempotencyService(mapper, new ObjectMapper(), CLOCK);

        assertThatThrownBy(() -> service.execute(jwt(), "POST", "/resource", UUID.randomUUID().toString(),
                new SampleRequest("same"), SampleResponse.class,
                () -> { throw BusinessException.denied(); }, () -> new SampleResponse("must-not-run")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ACCESS_DENIED"));
        verifyNoInteractions(mapper);
    }

    private Jwt jwt() {
        return Jwt.withTokenValue("token").header("alg", "none").subject("10001").build();
    }

    private record SampleRequest(String value) {
    }

    private record SampleResponse(String value) {
    }
}
