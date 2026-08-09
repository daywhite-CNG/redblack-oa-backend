package com.redblack.gateway.security;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.redblack.gateway.config.GatewaySecurityProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceTokenIssuerTest {
    @Test
    void issuesFiveMinuteHmacServiceCredential() throws Exception {
        String secret = "0123456789abcdef0123456789abcdef";
        Instant now = Instant.parse("2026-08-01T08:00:00Z");
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setInternalSecret(secret);
        ServiceTokenIssuer issuer = new ServiceTokenIssuer(properties, Clock.fixed(now, ZoneOffset.UTC));

        SignedJWT jwt = SignedJWT.parse(issuer.issue());

        assertThat(jwt.verify(new MACVerifier(secret.getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("gateway-service");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("redblack-internal");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("typ")).isEqualTo("service");
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant())
                .isEqualTo(now.plus(Duration.ofMinutes(5)));
    }
}
