package com.redblack.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdWebFilterTest {
    @Test
    void replacesInvalidRequestIdAndRemovesExternallySuppliedIdentityHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/auth/me")
                .header("X-Request-Id", "contains spaces")
                .header("X-Authenticated-User-Id", "forged")
                .header("X-Internal-Authorization", "forged")
                .build());
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        new RequestIdWebFilter().filter(exchange, value -> {
            forwarded.set(value);
            return reactor.core.publisher.Mono.empty();
        }).block();

        String requestId = forwarded.get().getRequest().getHeaders().getFirst("X-Request-Id");
        assertThat(requestId).matches("[0-9a-f-]{36}");
        assertThat(forwarded.get().getRequest().getHeaders().containsKey("X-Authenticated-User-Id")).isFalse();
        assertThat(forwarded.get().getRequest().getHeaders().containsKey("X-Internal-Authorization")).isFalse();
        assertThat(forwarded.get().getResponse().getHeaders().getFirst("X-Request-Id")).isEqualTo(requestId);
    }
}
