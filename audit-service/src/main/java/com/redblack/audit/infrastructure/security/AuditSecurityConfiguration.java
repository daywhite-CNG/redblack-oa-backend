package com.redblack.audit.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redblack.audit.api.RequestIds;
import com.redblack.common.api.ErrorResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
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
@EnableConfigurationProperties(AuditSecurityProperties.class)
public class AuditSecurityConfiguration {
    @Bean
    SecurityFilterChain auditSecurityFilterChain(HttpSecurity http, JwtDecoder auditJwtDecoder,
                                                 ObjectMapper objectMapper) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(auditJwtDecoder))
                        .authenticationEntryPoint((request, response, exception) -> writeError(response, objectMapper,
                                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "访问令牌无效或已过期",
                                RequestIds.get(request))))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, exception) -> writeError(
                        response, objectMapper, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "权限不足",
                        RequestIds.get(request))))
                .build();
    }
    @Bean
    RSAPublicKey auditJwtPublicKey(AuditSecurityProperties properties) {
        return AuditPemKeyReader.readPublic(properties.getPublicKeyPath());
    }
    @Bean
    JwtDecoder auditJwtDecoder(RSAPublicKey key, AuditSecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key).build();
        var issuer = JwtValidators.createDefaultWithIssuer(properties.getIssuer());
        var audience = new JwtClaimValidator<List<String>>("aud", value -> value != null && value.contains(properties.getAudience()));
        var type = new JwtClaimValidator<String>("typ", "access"::equals);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(issuer, audience, type));
        return decoder;
    }
    @Bean
    Clock auditClock() { return Clock.systemUTC(); }
    private static void writeError(jakarta.servlet.http.HttpServletResponse response, ObjectMapper mapper,
                                   HttpStatus status, String code, String message, String requestId) throws IOException {
        response.setStatus(status.value()); response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getOutputStream(),
                new ErrorResponse(code, message, List.of(), requestId, OffsetDateTime.now()));
    }
}
