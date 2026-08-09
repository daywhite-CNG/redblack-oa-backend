package com.redblack.approval.infrastructure.identity;

import com.redblack.approval.application.BusinessException;
import com.redblack.approval.infrastructure.security.ApprovalSecurityProperties;
import com.redblack.approval.infrastructure.security.ApprovalServiceTokenIssuer;
import com.redblack.common.trace.TraceHeaders;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.OffsetDateTime;
import java.util.List;

@Component
public class IdentityClient {
    private final RestClient restClient;
    private final ApprovalServiceTokenIssuer tokenIssuer;

    public IdentityClient(ApprovalSecurityProperties properties, ApprovalServiceTokenIssuer tokenIssuer) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getIdentityConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getIdentityResponseTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getIdentityInternalUrl())
                .requestFactory(requestFactory)
                .build();
        this.tokenIssuer = tokenIssuer;
    }

    public AuthorizationSnapshot authorization(long userId, String requestId) {
        return get("/internal/v1/authorization/users/{userId}", userId, requestId,
                new ParameterizedTypeReference<ApiEnvelope<AuthorizationSnapshot>>() {}).data();
    }

    public ApprovalContext approvalContext(long userId, String requestId) {
        return get("/internal/v1/users/{userId}/approval-context", userId, requestId,
                new ParameterizedTypeReference<ApiEnvelope<ApprovalContext>>() {}).data();
    }

    private <T> T get(String path, long userId, String requestId, ParameterizedTypeReference<T> type) {
        try {
            T result = restClient.get()
                    .uri(path, userId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenIssuer.issue())
                    .header(TraceHeaders.REQUEST_ID, requestId)
                    .retrieve()
                    .body(type);
            if (result == null) {
                throw dependencyUnavailable();
            }
            return result;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw BusinessException.notFound("用户不存在");
            }
            throw dependencyUnavailable();
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw dependencyUnavailable();
        }
    }

    private BusinessException dependencyUnavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "身份服务暂不可用");
    }

    public record ApiEnvelope<T>(String code, String message, T data, String requestId, OffsetDateTime timestamp) {
    }

    public record RoleRef(String id, String code, String name) {
    }

    public record AuthorizationGrant(String scope, List<String> departmentIds) {
    }

    public record AuthorizationSnapshot(String userId,
                                        String status,
                                        long authVersion,
                                        List<RoleRef> roles,
                                        List<String> permissions,
                                        List<AuthorizationGrant> grants,
                                        long menuTreeVersion,
                                        OffsetDateTime expiresAt) {
    }

    public record ApprovalContext(String userId,
                                  String name,
                                  String departmentId,
                                  String departmentName,
                                  String leaderId,
                                  String leaderName,
                                  String status) {
    }
}
