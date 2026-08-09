package com.redblack.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redblack.common.api.ErrorResponse;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

public final class GatewayErrors {
    private GatewayErrors() {
    }

    public static Mono<Void> write(ServerWebExchange exchange,
                                   ObjectMapper objectMapper,
                                   HttpStatus status,
                                   String code,
                                   String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String requestId = exchange.getAttributeOrDefault("requestId", "unknown");
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(new ErrorResponse(
                    code, message, List.of(), requestId, OffsetDateTime.now(ZoneOffset.UTC)));
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception exception) {
            return exchange.getResponse().setComplete();
        }
    }
}
