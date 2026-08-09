package com.redblack.approval.infrastructure.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "redblack.security")
public class ApprovalSecurityProperties {
    private String issuer = "https://identity.redblack.local";
    private String audience = "redblack-oa";
    private String publicKeyPath;
    private String internalSecret;
    private String identityInternalUrl = "http://identity-service:8080";
    private Duration identityConnectTimeout = Duration.ofSeconds(1);
    private Duration identityResponseTimeout = Duration.ofSeconds(3);
    private String officeInternalUrl = "http://office-service:8080";
    private Duration officeConnectTimeout = Duration.ofSeconds(1);
    private Duration officeResponseTimeout = Duration.ofSeconds(3);
}
