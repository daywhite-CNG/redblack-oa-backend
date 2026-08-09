package com.redblack.approval.infrastructure.office;

import com.redblack.approval.application.BusinessException;
import com.redblack.approval.infrastructure.security.ApprovalSecurityProperties;
import com.redblack.approval.infrastructure.security.ApprovalServiceTokenIssuer;
import com.redblack.common.trace.TraceHeaders;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.util.List;
import java.time.OffsetDateTime;
import com.redblack.approval.api.ApprovalApiModels.FileSummary;

@Component
public class OfficeFileClient {
    private final RestClient client;
    private final ApprovalServiceTokenIssuer issuer;

    public OfficeFileClient(ApprovalSecurityProperties properties, ApprovalServiceTokenIssuer issuer) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.getOfficeConnectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.getOfficeResponseTimeout());
        client = RestClient.builder().baseUrl(properties.getOfficeInternalUrl()).requestFactory(factory).build();
        this.issuer = issuer;
    }

    public void reserve(String reservationId, long ownerId, List<Long> fileIds,
                        Long applicationId, String requestId) {
        if (fileIds.isEmpty()) return;
        try {
            client.post().uri("/internal/v1/files/reservations")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + issuer.issue())
                    .header(TraceHeaders.REQUEST_ID, requestId)
                    .body(new ReservationRequest(reservationId, Long.toString(ownerId),
                            fileIds.stream().map(String::valueOf).toList(), "LEAVE_APPLICATION",
                            applicationId == null ? null : applicationId.toString()))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 422) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_BINDING_INVALID",
                        "附件不存在、已绑定或不属于当前用户");
            }
            throw unavailable();
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw unavailable(); }
    }

    public List<FileSummary> metadata(List<Long> fileIds) {
        if (fileIds.isEmpty()) return List.of();
        try {
            Envelope<List<FileSummary>> result = client.post().uri("/internal/v1/files/metadata")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + issuer.issue())
                    .header(TraceHeaders.REQUEST_ID, "req_file_metadata_" + java.util.UUID.randomUUID())
                    .body(new MetadataRequest(fileIds.stream().map(String::valueOf).toList()))
                    .retrieve().body(new ParameterizedTypeReference<Envelope<List<FileSummary>>>() {});
            if (result == null || result.data() == null) throw unavailable();
            return result.data();
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw unavailable(); }
    }

    private BusinessException unavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "文件服务暂不可用");
    }

    public record ReservationRequest(String reservationId, String ownerId, List<String> fileIds,
                                     String businessType, String businessId) { }
    public record MetadataRequest(List<String> fileIds) { }
    public record Envelope<T>(String code, String message, T data, String requestId, OffsetDateTime timestamp) { }
}
