package com.redblack.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redblack.gateway.security.GatewayAuthorizationWebFilter;
import com.redblack.gateway.security.GatewayErrors;
import com.redblack.gateway.security.ServiceTokenIssuer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.reactive.function.client.WebClient;

import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewaySecurityProperties.class)
public class GatewaySecurityConfiguration {

    @Bean
    SecurityWebFilterChain gatewaySecurityWebFilterChain(ServerHttpSecurity http,
                                                         ReactiveJwtDecoder userJwtDecoder,
                                                         GatewayAuthorizationWebFilter authorizationFilter,
                                                         ObjectMapper objectMapper) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/api/v1/auth/login", "/actuator/health/**", "/actuator/info").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.jwtDecoder(userJwtDecoder))
                        .authenticationEntryPoint((exchange, exception) -> GatewayErrors.write(
                                exchange, objectMapper, HttpStatus.UNAUTHORIZED,
                                "UNAUTHENTICATED", "访问令牌无效或已过期")))
                .exceptionHandling(errors -> errors.accessDeniedHandler((exchange, exception) -> GatewayErrors.write(
                        exchange, objectMapper, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "权限不足")))
                .addFilterAfter(authorizationFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    RSAPublicKey gatewayJwtPublicKey(GatewaySecurityProperties properties) {
        return GatewayPemKeyReader.readPublic(properties.getPublicKeyPath());
    }

    @Bean
    ReactiveJwtDecoder userJwtDecoder(RSAPublicKey gatewayJwtPublicKey,
                                      GatewaySecurityProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey(gatewayJwtPublicKey).build();
        OAuth2TokenValidator<Jwt> issuer = JwtValidators.createDefaultWithIssuer(properties.getIssuer());
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>("aud",
                value -> value != null && value.contains(properties.getAudience()));
        OAuth2TokenValidator<Jwt> type = new JwtClaimValidator<String>("typ", "access"::equals);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience, type));
        return decoder;
    }

    @Bean
    Clock gatewayClock() {
        return Clock.systemUTC();
    }

    @Bean
    GatewayAuthorizationWebFilter gatewayAuthorizationWebFilter(
            org.springframework.data.redis.core.ReactiveStringRedisTemplate redis,
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder,
            GatewaySecurityProperties properties,
            ServiceTokenIssuer serviceTokens) {
        return new GatewayAuthorizationWebFilter(redis, objectMapper, webClientBuilder, properties, serviceTokens);
    }
}
