package com.redblack.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redblack.gateway.config.GatewaySecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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

    @Test
    void stalledIdentityServiceReturnsDependencyUnavailableForProtectedWrite() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.ofPlatform().daemon(true).start(() -> acceptWithoutResponding(server, accepted, release));

            ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
            when(redis.hasKey(anyString())).thenReturn(Mono.just(false));
            when(redis.opsForValue()).thenReturn(values);
            when(values.get(anyString())).thenReturn(Mono.empty());
            GatewaySecurityProperties properties = new GatewaySecurityProperties();
            properties.setIdentityInternalUrl("http://127.0.0.1:" + server.getLocalPort());
            properties.setIdentityConnectTimeout(Duration.ofMillis(100));
            properties.setIdentityResponseTimeout(Duration.ofMillis(250));
            ServiceTokenIssuer serviceTokens = mock(ServiceTokenIssuer.class);
            when(serviceTokens.issue()).thenReturn("service-token");
            ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
            GatewayAuthorizationWebFilter filter = new GatewayAuthorizationWebFilter(redis, objectMapper,
                    WebClient.builder(), properties, serviceTokens);
            Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256")
                    .subject("10001").claim("jti", "jti").claim("authVersion", 3L).build();
            ServerWebExchange exchange = MockServerWebExchange.from(
                            MockServerHttpRequest.post("/api/v1/leave-applications"))
                    .mutate().principal(Mono.just(new JwtAuthenticationToken(jwt))).build();
            exchange.getAttributes().put("requestId", "req-stalled-identity");
            AtomicInteger downstreamCalls = new AtomicInteger();

            try {
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> filter.filter(exchange, value -> {
                    downstreamCalls.incrementAndGet();
                    return Mono.empty();
                }).block());
                assertThat(accepted.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(exchange.getResponse().getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
                String body = ((MockServerHttpResponse) exchange.getResponse()).getBodyAsString().block();
                var json = objectMapper.readTree(body == null ? "" : body);
                assertThat(json.path("code").asText()).isEqualTo("DEPENDENCY_UNAVAILABLE");
                assertThat(json.path("requestId").asText()).isEqualTo("req-stalled-identity");
                assertThat(json.path("timestamp").asText()).isNotBlank();
                assertThat(downstreamCalls).hasValue(0);
            } finally {
                release.countDown();
            }
        }
    }

    private void acceptWithoutResponding(ServerSocket server,
                                         CountDownLatch accepted,
                                         CountDownLatch release) {
        try (Socket ignored = server.accept()) {
            accepted.countDown();
            release.await(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // The test owns and closes the socket.
        }
    }
}
