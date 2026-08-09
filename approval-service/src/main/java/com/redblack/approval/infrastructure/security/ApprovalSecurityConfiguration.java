package com.redblack.approval.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redblack.common.api.ErrorResponse;
import com.redblack.approval.api.RequestIds;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApprovalSecurityProperties.class)
public class ApprovalSecurityConfiguration {
    @Bean
    SecurityFilterChain approvalSecurityFilterChain(HttpSecurity http,
                                                     JwtDecoder approvalJwtDecoder,
                                                     ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(approvalJwtDecoder))
                        .authenticationEntryPoint((request, response, exception) -> writeSecurityError(
                                response, objectMapper, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                                "访问令牌无效或已过期", RequestIds.get(request))))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, exception) ->
                        writeSecurityError(response, objectMapper, HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                                "权限不足", RequestIds.get(request))))
                .build();
    }

    @Bean
    RSAPublicKey approvalJwtPublicKey(ApprovalSecurityProperties properties) {
        return ApprovalPemKeyReader.readPublic(properties.getPublicKeyPath());
    }

    @Bean
    JwtDecoder approvalJwtDecoder(RSAPublicKey publicKey, ApprovalSecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        OAuth2TokenValidator<Jwt> issuer = JwtValidators.createDefaultWithIssuer(properties.getIssuer());
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>("aud",
                value -> value != null && value.contains(properties.getAudience()));
        OAuth2TokenValidator<Jwt> type = new JwtClaimValidator<String>("typ", "access"::equals);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience, type));
        return decoder;
    }

    @Bean
    Clock approvalClock() {
        return Clock.systemUTC();
    }

    private static void writeSecurityError(jakarta.servlet.http.HttpServletResponse response,
                                           ObjectMapper objectMapper,
                                           HttpStatus status,
                                           String code,
                                           String message,
                                           String requestId) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(
                code, message, List.of(), requestId, OffsetDateTime.now()));
    }
}

