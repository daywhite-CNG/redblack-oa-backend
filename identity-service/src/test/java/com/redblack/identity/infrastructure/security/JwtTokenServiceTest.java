package com.redblack.identity.infrastructure.security;

import com.redblack.identity.domain.UserEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtTokenServiceTest {
    @Test
    void issuesEightHourAccessTokenWithStableSecurityClaims() {
        Instant now = Instant.parse("2026-08-01T08:00:00Z");
        JwtEncoder encoder = mock(JwtEncoder.class);
        when(encoder.encode(any())).thenReturn(Jwt.withTokenValue("signed-token")
                .header("alg", "RS256").subject("10001").build());
        SecurityProperties properties = new SecurityProperties();
        properties.setIssuer("https://identity.redblack.local");
        properties.setAudience("redblack-oa");
        properties.setAccessTokenTtl(Duration.ofHours(8));
        JwtTokenService service = new JwtTokenService(encoder, properties, Clock.fixed(now, ZoneOffset.UTC));

        UserEntity user = new UserEntity();
        user.setId(10001L);
        user.setUsername("admin");
        user.setAuthVersion(7L);
        JwtTokenService.IssuedToken token = service.issue(user);

        ArgumentCaptor<JwtEncoderParameters> parameters = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(encoder).encode(parameters.capture());
        var claims = parameters.getValue().getClaims();
        assertThat(parameters.getValue().getJwsHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(claims.getIssuer().toString()).isEqualTo("https://identity.redblack.local");
        assertThat(claims.getAudience()).containsExactly("redblack-oa");
        assertThat(claims.getSubject()).isEqualTo("10001");
        assertThat((Object) claims.getClaim("typ")).isEqualTo("access");
        assertThat((Object) claims.getClaim("username")).isEqualTo("admin");
        assertThat((Object) claims.getClaim("authVersion")).isEqualTo(7L);
        assertThat(claims.getIssuedAt()).isEqualTo(now);
        assertThat(claims.getExpiresAt()).isEqualTo(now.plus(Duration.ofHours(8)));
        assertThat(token.expiresIn()).isEqualTo(28_800);
        assertThat(token.jti()).isNotBlank();
    }
}
