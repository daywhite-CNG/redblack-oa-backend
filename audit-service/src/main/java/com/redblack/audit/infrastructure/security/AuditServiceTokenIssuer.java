package com.redblack.audit.infrastructure.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Component
public class AuditServiceTokenIssuer {
    private final byte[] secret;
    private final Clock clock;
    public AuditServiceTokenIssuer(AuditSecurityProperties properties, Clock clock) {
        if (properties.getInternalSecret() == null || properties.getInternalSecret().length() < 32) {
            throw new IllegalStateException("INTERNAL_SERVICE_TOKEN_SECRET must contain at least 32 characters");
        }
        secret = properties.getInternalSecret().getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }
    public String issue() {
        try {
            var now = clock.instant();
            var claims = new JWTClaimsSet.Builder().subject("audit-service").audience(List.of("redblack-internal"))
                    .jwtID(UUID.randomUUID().toString()).issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(Duration.ofMinutes(5)))).claim("typ", "service").build();
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (Exception exception) { throw new IllegalStateException("Unable to issue service token", exception); }
    }
}
