package com.redblack.approval.infrastructure.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "redblack.security")
public class ApprovalSecurityProperties {
    private String issuer = "https://identity.redblack.local";
    private String audience = "redblack-oa";
    private String publicKeyPath;
    private String internalSecret;
    private String identityInternalUrl = "http://identity-service:8080";
}
