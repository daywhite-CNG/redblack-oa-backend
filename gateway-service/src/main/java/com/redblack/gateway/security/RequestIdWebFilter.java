package com.redblack.gateway.security;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@Component
public class RequestIdWebFilter implements WebFilter, Ordered {
    private static final String REQUEST_ID = "X-Request-Id";
    private static final List<String> INTERNAL_HEADERS = List.of(
            "X-Internal-Authorization", "X-Authenticated-User-Id", "X-Authenticated-Username",
            "X-Authenticated-Auth-Version", "X-Service-Name");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(REQUEST_ID);
        String requestId = valid(incoming) ? incoming : UUID.randomUUID().toString();
        ServerWebExchange mutated = exchange.mutate().request(request -> request.headers(headers -> {
            INTERNAL_HEADERS.forEach(headers::remove);
            headers.set(REQUEST_ID, requestId);
        })).build();
        mutated.getAttributes().put("requestId", requestId);
        mutated.getResponse().getHeaders().set(REQUEST_ID, requestId);
        return chain.filter(mutated);
    }

    private boolean valid(String value) {
        return value != null && value.length() <= 128 && value.matches("[A-Za-z0-9._:-]+");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
