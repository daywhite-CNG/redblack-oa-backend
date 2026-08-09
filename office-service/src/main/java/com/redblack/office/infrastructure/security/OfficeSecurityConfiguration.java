package com.redblack.office.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redblack.common.api.ErrorResponse;
import com.redblack.office.api.RequestIds;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OfficeSecurityProperties.class)
public class OfficeSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain officeInternalSecurityFilterChain(HttpSecurity http,
            @Qualifier("officeInternalJwtDecoder") JwtDecoder decoder, ObjectMapper objectMapper) throws Exception {
        return http.securityMatcher("/internal/v1/**").csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(decoder))
                        .authenticationEntryPoint((request, response, exception) -> writeError(response, objectMapper,
                                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "内部服务凭据无效",
                                RequestIds.get(request))))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain officeSecurityFilterChain(HttpSecurity http,
                                                  @Qualifier("officeJwtDecoder") JwtDecoder officeJwtDecoder,
                                                  ObjectMapper objectMapper) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(officeJwtDecoder))
                        .authenticationEntryPoint((request, response, exception) -> writeError(
                                response, objectMapper, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                                "访问令牌无效或已过期", RequestIds.get(request))))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, exception) -> writeError(
                        response, objectMapper, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "权限不足",
                        RequestIds.get(request))))
                .build();
    }

    @Bean
    RSAPublicKey officeJwtPublicKey(OfficeSecurityProperties properties) {
        return OfficePemKeyReader.readPublic(properties.getPublicKeyPath());
    }

    @Bean
    JwtDecoder officeJwtDecoder(RSAPublicKey publicKey, OfficeSecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        var issuer = JwtValidators.createDefaultWithIssuer(properties.getIssuer());
        var audience = new JwtClaimValidator<List<String>>("aud",
                value -> value != null && value.contains(properties.getAudience()));
        var type = new JwtClaimValidator<String>("typ", "access"::equals);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(issuer, audience, type));
        return decoder;
    }

    @Bean
    JwtDecoder officeInternalJwtDecoder(OfficeSecurityProperties properties) {
        if (properties.getInternalSecret() == null || properties.getInternalSecret().length() < 32) {
            throw new IllegalStateException("INTERNAL_SERVICE_TOKEN_SECRET must contain at least 32 characters");
        }
        var key = new SecretKeySpec(properties.getInternalSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
        var timestamp = new JwtTimestampValidator();
        var audience = new JwtClaimValidator<List<String>>("aud",
                value -> value != null && value.contains("redblack-internal"));
        var type = new JwtClaimValidator<String>("typ", "service"::equals);
        var subject = new JwtClaimValidator<String>("sub", "approval-service"::equals);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(timestamp, audience, type, subject));
        return decoder;
    }

    @Bean
    Clock officeClock() { return Clock.systemUTC(); }

    private static void writeError(jakarta.servlet.http.HttpServletResponse response, ObjectMapper objectMapper,
                                   HttpStatus status, String code, String message, String requestId) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(),
                new ErrorResponse(code, message, List.of(), requestId, OffsetDateTime.now()));
    }
}
