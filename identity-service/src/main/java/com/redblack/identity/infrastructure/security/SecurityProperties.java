package com.redblack.identity.infrastructure.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "redblack.security")
public class SecurityProperties {
    private String issuer = "https://identity.redblack.local";
    private String audience = "redblack-oa";
    private String privateKeyPath;
    private String publicKeyPath;
    private String internalSecret;
    private Duration accessTokenTtl = Duration.ofHours(8);
    private Duration authorizationCacheTtl = Duration.ofMinutes(5);
    private List<String> allowedServiceNames = List.of(
            "gateway-service", "approval-service", "office-service", "audit-service");
}
