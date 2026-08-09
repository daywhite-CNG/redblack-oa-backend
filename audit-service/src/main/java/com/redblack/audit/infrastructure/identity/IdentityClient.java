package com.redblack.audit.infrastructure.identity;

import com.redblack.audit.application.BusinessException;
import com.redblack.audit.infrastructure.security.AuditSecurityProperties;
import com.redblack.audit.infrastructure.security.AuditServiceTokenIssuer;
import com.redblack.common.trace.TraceHeaders;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.OffsetDateTime;
import java.util.List;

@Component
public class IdentityClient {
    private final RestClient client;
    private final AuditServiceTokenIssuer issuer;
    public IdentityClient(AuditSecurityProperties properties, AuditServiceTokenIssuer issuer) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.getIdentityConnectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.getIdentityResponseTimeout());
        client = RestClient.builder().baseUrl(properties.getIdentityInternalUrl()).requestFactory(factory).build();
        this.issuer = issuer;
    }
    public AuthorizationSnapshot authorization(long userId, String requestId) {
        try {
            ApiEnvelope<AuthorizationSnapshot> result = client.get()
                    .uri("/internal/v1/authorization/users/{userId}", userId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + issuer.issue())
                    .header(TraceHeaders.REQUEST_ID, requestId).retrieve()
                    .body(new ParameterizedTypeReference<ApiEnvelope<AuthorizationSnapshot>>() {});
            if (result == null || result.data() == null) throw unavailable();
            return result.data();
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw unavailable(); }
    }
    private BusinessException unavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "身份服务暂不可用");
    }
    public record ApiEnvelope<T>(String code, String message, T data, String requestId, OffsetDateTime timestamp) { }
    public record AuthorizationSnapshot(String userId, String status, long authVersion, List<Object> roles,
                                        List<String> permissions, List<Object> grants,
                                        long menuTreeVersion, OffsetDateTime expiresAt) { }
}
