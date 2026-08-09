package com.redblack.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "redblack.security")
public class GatewaySecurityProperties {
    private String issuer = "https://identity.redblack.local";
    private String audience = "redblack-oa";
    private String publicKeyPath;
    private String internalSecret;
    private String identityInternalUrl = "http://identity-service:8080";

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public String getPublicKeyPath() {
        return publicKeyPath;
    }

    public void setPublicKeyPath(String publicKeyPath) {
        this.publicKeyPath = publicKeyPath;
    }

    public String getInternalSecret() {
        return internalSecret;
    }

    public void setInternalSecret(String internalSecret) {
        this.internalSecret = internalSecret;
    }

    public String getIdentityInternalUrl() {
        return identityInternalUrl;
    }

    public void setIdentityInternalUrl(String identityInternalUrl) {
        this.identityInternalUrl = identityInternalUrl;
    }
}
