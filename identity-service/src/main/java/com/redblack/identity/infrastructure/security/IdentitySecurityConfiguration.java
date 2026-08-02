package com.redblack.identity.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.redblack.common.api.ErrorResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class IdentitySecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain internalSecurityFilterChain(HttpSecurity http,
                                                    @Qualifier("internalJwtDecoder") JwtDecoder internalJwtDecoder,
                                                    ObjectMapper objectMapper) throws Exception {
        return http.securityMatcher("/internal/v1/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(internalJwtDecoder))
                        .authenticationEntryPoint((request, response, exception) ->
                                writeSecurityError(response, objectMapper, HttpStatus.UNAUTHORIZED,
                                        "UNAUTHENTICATED", "内部服务凭据无效", request.getAttribute("requestId"))))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain identitySecurityFilterChain(HttpSecurity http,
                                                     @Qualifier("userJwtDecoder") JwtDecoder userJwtDecoder,
                                                     ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/api/v1/auth/login", "/actuator/health/**", "/actuator/info", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(userJwtDecoder))
                        .authenticationEntryPoint((request, response, exception) ->
                                writeSecurityError(response, objectMapper, HttpStatus.UNAUTHORIZED,
                                        "UNAUTHENTICATED", "访问令牌无效或已过期", request.getAttribute("requestId"))))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, exception) ->
                        writeSecurityError(response, objectMapper, HttpStatus.FORBIDDEN,
                                "ACCESS_DENIED", "权限不足", request.getAttribute("requestId"))))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RSAPublicKey jwtPublicKey(SecurityProperties properties) {
        return PemKeyReader.readPublic(properties.getPublicKeyPath());
    }

    @Bean
    RSAPrivateKey jwtPrivateKey(SecurityProperties properties) {
        return PemKeyReader.readPrivate(properties.getPrivateKeyPath());
    }

    @Bean
    JwtEncoder jwtEncoder(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        RSAKey rsaKey = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
    }

    @Bean
    JwtDecoder userJwtDecoder(RSAPublicKey publicKey, SecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        OAuth2TokenValidator<Jwt> issuer = JwtValidators.createDefaultWithIssuer(properties.getIssuer());
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>("aud",
                value -> value != null && value.contains(properties.getAudience()));
        OAuth2TokenValidator<Jwt> type = new JwtClaimValidator<String>("typ", "access"::equals);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience, type));
        return decoder;
    }

    @Bean
    JwtDecoder internalJwtDecoder(SecurityProperties properties) {
        byte[] secret = requireInternalSecret(properties).getBytes(StandardCharsets.UTF_8);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
                .macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> timestamp = new JwtTimestampValidator();
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>("aud",
                value -> value != null && value.contains("redblack-internal"));
        OAuth2TokenValidator<Jwt> type = new JwtClaimValidator<String>("typ", "service"::equals);
        OAuth2TokenValidator<Jwt> subject = new JwtClaimValidator<String>("sub",
                value -> value != null && properties.getAllowedServiceNames().contains(value));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamp, audience, type, subject));
        return decoder;
    }

    private static String requireInternalSecret(SecurityProperties properties) {
        if (properties.getInternalSecret() == null || properties.getInternalSecret().length() < 32) {
            throw new IllegalStateException("INTERNAL_SERVICE_TOKEN_SECRET must contain at least 32 characters");
        }
        return properties.getInternalSecret();
    }

    private static void writeSecurityError(jakarta.servlet.http.HttpServletResponse response,
                                           ObjectMapper objectMapper,
                                           HttpStatus status,
                                           String code,
                                           String message,
                                           Object requestId) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(
                code, message, List.of(), requestId == null ? "unknown" : requestId.toString(), OffsetDateTime.now()));
    }
}
