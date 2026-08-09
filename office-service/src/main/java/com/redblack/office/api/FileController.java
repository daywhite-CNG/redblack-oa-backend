package com.redblack.office.api;

import com.redblack.common.api.ApiResponse;
import com.redblack.office.api.OfficeApiModels.FileSummary;
import com.redblack.office.application.FileApplicationService;
import com.redblack.office.application.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.nio.charset.StandardCharsets;

@Validated
@RestController
@RequestMapping("/api/v1/files")
public class FileController {
    private final FileApplicationService service;
    private final IdempotencyService idempotency;

    public FileController(FileApplicationService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "上传成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "请求参数错误"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413", description = "文件过大"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415", description = "文件类型不支持"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "依赖不可用")
    })
    ResponseEntity<ApiResponse<FileSummary>> upload(@AuthenticationPrincipal Jwt jwt,
                                                     @RequestHeader("Idempotency-Key") String key,
                                                     @RequestPart("file") MultipartFile file,
                                                     HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        var prepared = service.prepare(file);
        FileSummary result = idempotency.execute(jwt, "POST", "/api/v1/files", key, prepared.fingerprint(),
                FileSummary.class, () -> service.authorizeUpload(jwt, requestId),
                () -> service.upload(jwt, prepared, requestId));
        return ResponseEntity.created(URI.create("/api/v1/files/" + result.id()))
                .body(ApiResponse.success("上传成功", result, requestId));
    }

    @GetMapping("/{fileId}")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "查询成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在")
    })
    ApiResponse<FileSummary> metadata(@AuthenticationPrincipal Jwt jwt, @PathVariable long fileId,
                                      HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        return ApiResponse.success("查询成功", service.metadata(jwt, fileId, requestId), requestId);
    }

    @DeleteMapping("/{fileId}")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "删除成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "请求冲突"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "依赖不可用")
    })
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long fileId,
                                HttpServletRequest request) {
        service.deleteTemporary(jwt, fileId, RequestIds.get(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{fileId}/content")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "文件内容"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "依赖不可用")
    })
    ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt, @PathVariable long fileId,
                                    HttpServletRequest request) {
        var result = service.download(jwt, fileId, RequestIds.get(request));
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(result.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(result.contentType()))
                .contentLength(result.content().length)
                .body(result.content());
    }
}
