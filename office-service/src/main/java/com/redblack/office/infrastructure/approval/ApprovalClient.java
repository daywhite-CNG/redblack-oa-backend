package com.redblack.office.infrastructure.approval;

import com.redblack.common.trace.TraceHeaders;
import com.redblack.office.application.BusinessException;
import com.redblack.office.infrastructure.security.OfficeSecurityProperties;
import com.redblack.office.infrastructure.security.OfficeServiceTokenIssuer;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.OffsetDateTime;

@Component
public class ApprovalClient {
    private final RestClient client;
    private final OfficeServiceTokenIssuer issuer;
    public ApprovalClient(OfficeSecurityProperties properties, OfficeServiceTokenIssuer issuer) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.getApprovalConnectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.getApprovalResponseTimeout());
        client = RestClient.builder().baseUrl(properties.getApprovalInternalUrl()).requestFactory(factory).build();
        this.issuer = issuer;
    }
    public boolean canReadFile(long applicationId, long userId, String requestId) {
        try {
            Envelope<AccessResult> result = client.get()
                    .uri(builder -> builder.path("/internal/v1/leave-applications/{id}/file-access")
                            .queryParam("userId", userId).build(applicationId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + issuer.issue())
                    .header(TraceHeaders.REQUEST_ID, requestId).retrieve()
                    .body(new ParameterizedTypeReference<Envelope<AccessResult>>() {});
            if (result == null || result.data() == null) throw unavailable();
            return result.data().allowed();
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw unavailable(); }
    }
    private BusinessException unavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "审批服务暂不可用");
    }
    public record Envelope<T>(String code, String message, T data, String requestId, OffsetDateTime timestamp) { }
    public record AccessResult(boolean allowed) { }
}
