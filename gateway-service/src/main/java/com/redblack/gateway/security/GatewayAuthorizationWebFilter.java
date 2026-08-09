package com.redblack.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redblack.common.api.ApiResponse;
import com.redblack.gateway.config.GatewaySecurityProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

public class GatewayAuthorizationWebFilter implements WebFilter {
    private static final String AUTHORIZATION_KEY = "redblack:identity:authorization:";
    private static final String DENY_KEY = "redblack:identity:token-deny:";

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final WebClient identityClient;
    private final ServiceTokenIssuer serviceTokens;

    public GatewayAuthorizationWebFilter(ReactiveStringRedisTemplate redis,
                                         ObjectMapper objectMapper,
                                         WebClient.Builder webClientBuilder,
                                         GatewaySecurityProperties properties,
                                         ServiceTokenIssuer serviceTokens) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.identityClient = webClientBuilder.baseUrl(properties.getIdentityInternalUrl()).build();
        this.serviceTokens = serviceTokens;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return exchange.getPrincipal()
                .filter(Authentication.class::isInstance)
                .cast(Authentication.class)
                .map(Authentication::getPrincipal)
                .filter(Jwt.class::isInstance)
                .cast(Jwt.class)
                .flatMap(jwt -> authorize(exchange, chain, jwt).thenReturn(Boolean.TRUE))
                .switchIfEmpty(Mono.defer(() -> chain.filter(exchange).thenReturn(Boolean.TRUE)))
                .then();
    }

    private Mono<Void> authorize(ServerWebExchange exchange, WebFilterChain chain, Jwt jwt) {
        Mono<Boolean> denied = jwt.getId() == null
                ? Mono.just(false)
                : redis.hasKey(DENY_KEY + jwt.getId()).onErrorReturn(false);

        return denied.flatMap(isDenied -> {
            if (isDenied) {
                return GatewayErrors.write(exchange, objectMapper, HttpStatus.UNAUTHORIZED,
                        "UNAUTHENTICATED", "访问令牌已失效");
            }
            return findSnapshot(jwt.getSubject())
                    .map(java.util.Optional::of)
                    .defaultIfEmpty(java.util.Optional.empty())
                    .onErrorReturn(java.util.Optional.empty())
                    .flatMap(snapshot -> snapshot
                            .map(value -> validateSnapshot(exchange, chain, jwt, value))
                            .orElseGet(() -> dependencyFailure(exchange, chain)));
        });
    }

    private Mono<AuthorizationSnapshot> findSnapshot(String userId) {
        return redis.opsForValue().get(AUTHORIZATION_KEY + userId)
                .flatMap(json -> parseSnapshot(json).onErrorResume(exception -> Mono.empty()))
                .onErrorResume(exception -> Mono.empty())
                .switchIfEmpty(fetchSnapshot(userId));
    }

    private Mono<AuthorizationSnapshot> parseSnapshot(String json) {
        return Mono.fromCallable(() -> objectMapper.readValue(json, AuthorizationSnapshot.class));
    }

    private Mono<AuthorizationSnapshot> fetchSnapshot(String userId) {
        return identityClient.get()
                .uri("/internal/v1/authorization/users/{userId}", userId)
                .headers(headers -> headers.setBearerAuth(serviceTokens.issue()))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<ApiResponse<AuthorizationSnapshot>>() {
                })
                .map(ApiResponse::data);
    }

    private Mono<Void> validateSnapshot(ServerWebExchange exchange,
                                        WebFilterChain chain,
                                        Jwt jwt,
                                        AuthorizationSnapshot snapshot) {
        Number tokenVersion = jwt.getClaim("authVersion");
        if (!"ENABLED".equals(snapshot.status()) || tokenVersion == null
                || snapshot.authVersion() != tokenVersion.longValue()) {
            return GatewayErrors.write(exchange, objectMapper, HttpStatus.UNAUTHORIZED,
                    "UNAUTHENTICATED", "账号或会话已失效");
        }
        return chain.filter(exchange);
    }

    private Mono<Void> dependencyFailure(ServerWebExchange exchange, WebFilterChain chain) {
        HttpMethod method = exchange.getRequest().getMethod();
        if (method != null && !method.matches("GET|HEAD|OPTIONS")) {
            return GatewayErrors.write(exchange, objectMapper, HttpStatus.SERVICE_UNAVAILABLE,
                    "DEPENDENCY_UNAVAILABLE", "当前无法可靠校验授权状态");
        }
        return chain.filter(exchange);
    }

    public record AuthorizationSnapshot(String userId, String status, long authVersion) {
    }
}
