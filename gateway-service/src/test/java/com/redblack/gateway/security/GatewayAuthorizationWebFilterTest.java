package com.redblack.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redblack.gateway.config.GatewaySecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayAuthorizationWebFilterTest {
    @Test
    void validCachedSnapshotInvokesDownstreamExactlyOnce() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redis.hasKey(anyString())).thenReturn(Mono.just(false));
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("redblack:identity:authorization:10001"))
                .thenReturn(Mono.just("{\"userId\":\"10001\",\"status\":\"ENABLED\",\"authVersion\":3}"));
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setIdentityInternalUrl("http://identity.invalid");
        GatewayAuthorizationWebFilter filter = new GatewayAuthorizationWebFilter(redis, new ObjectMapper(),
                WebClient.builder(), properties, mock(ServiceTokenIssuer.class));
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256")
                .subject("10001").claim("jti", "jti").claim("authVersion", 3L).build();
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/auth/me"))
                .mutate().principal(Mono.just(new JwtAuthenticationToken(jwt))).build();
        AtomicInteger calls = new AtomicInteger();

        filter.filter(exchange, value -> {
            calls.incrementAndGet();
            return Mono.empty();
        }).block();

        assertThat(calls).hasValue(1);
    }
}
