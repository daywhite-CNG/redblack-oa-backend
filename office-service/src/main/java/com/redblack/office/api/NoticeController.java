package com.redblack.office.api;

import com.redblack.common.api.ApiResponse;
import com.redblack.office.api.OfficeApiModels.ChangePinRequest;
import com.redblack.office.api.OfficeApiModels.NoticeView;
import com.redblack.office.api.OfficeApiModels.NoticeWithdrawRequest;
import com.redblack.office.api.OfficeApiModels.PageData;
import com.redblack.office.api.OfficeApiModels.SaveNoticeRequest;
import com.redblack.office.api.OfficeApiModels.UpdateNoticeRequest;
import com.redblack.office.api.OfficeApiModels.VersionRequest;
import com.redblack.office.application.IdempotencyService;
import com.redblack.office.application.NoticeApplicationService;
import com.redblack.office.domain.OfficeEnums.NoticeStatus;
import com.redblack.office.domain.OfficeEnums.NoticeType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Validated
@RestController
@RequestMapping("/api/v1/notices")
public class NoticeController {
    private final NoticeApplicationService service;
    private final IdempotencyService idempotency;

    public NoticeController(NoticeApplicationService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @GetMapping
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "查询成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "请求参数错误"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "权限不足")
    })
    ApiResponse<PageData<NoticeView>> list(@AuthenticationPrincipal Jwt jwt, @RequestParam String view,
                                           @RequestParam(required = false) @Size(max = 100) String keyword,
                                           @RequestParam(required = false) NoticeType type,
                                           @RequestParam(required = false) NoticeStatus status,
                                           @RequestParam(required = false) String readStatus,
                                           @RequestParam(defaultValue = "1") @Min(1) int page,
                                           @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
                                           HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        return ApiResponse.success("查询成功", service.list(jwt, view, keyword, type, status, readStatus,
                page, pageSize, requestId), requestId);
    }

    @PostMapping
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "创建成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "请求参数错误"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "权限不足"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "请求冲突"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "业务校验失败")
    })
    ResponseEntity<ApiResponse<NoticeView>> create(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestHeader("Idempotency-Key") String key,
                                                    @Valid @RequestBody SaveNoticeRequest body,
                                                    HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        NoticeView result = idempotency.execute(jwt, "POST", "/api/v1/notices", key, body, NoticeView.class,
                () -> service.authorizeCreate(jwt, requestId), () -> service.create(jwt, body, requestId));
        return ResponseEntity.created(URI.create("/api/v1/notices/" + result.id()))
                .body(ApiResponse.success("创建成功", result, requestId));
    }

    @GetMapping("/{noticeId}")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "查询成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在")
    })
    ApiResponse<NoticeView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable long noticeId,
                                HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        return ApiResponse.success("查询成功", service.get(jwt, noticeId, requestId), requestId);
    }

    @PutMapping("/{noticeId}")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "请求参数错误"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "权限不足"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "请求冲突"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "业务校验失败")
    })
    ApiResponse<NoticeView> update(@AuthenticationPrincipal Jwt jwt, @PathVariable long noticeId,
                                   @Valid @RequestBody UpdateNoticeRequest body, HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        return ApiResponse.success("更新成功", service.update(jwt, noticeId, body, requestId), requestId);
    }

    @DeleteMapping("/{noticeId}")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "删除成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "权限不足"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "请求冲突")
    })
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long noticeId,
                                @RequestParam @Min(1) int version, HttpServletRequest request) {
        service.delete(jwt, noticeId, version, RequestIds.get(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{noticeId}/publish")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "发布成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "权限不足"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "请求冲突"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "业务校验失败")
    })
    ApiResponse<NoticeView> publish(@AuthenticationPrincipal Jwt jwt, @PathVariable long noticeId,
                                    @RequestHeader("Idempotency-Key") String key,
                                    @Valid @RequestBody VersionRequest body, HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        String path = "/api/v1/notices/" + noticeId + "/publish";
        NoticeView result = idempotency.execute(jwt, "POST", path, key, body, NoticeView.class,
                () -> service.authorizePublish(jwt, requestId),
                () -> service.publish(jwt, noticeId, body.version(), requestId));
        return ApiResponse.success("发布成功", result, requestId);
    }

    @PostMapping("/{noticeId}/withdraw")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "撤回成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "权限不足"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "请求冲突")
    })
    ApiResponse<NoticeView> withdraw(@AuthenticationPrincipal Jwt jwt, @PathVariable long noticeId,
                                     @RequestHeader("Idempotency-Key") String key,
                                     @Valid @RequestBody NoticeWithdrawRequest body, HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        String path = "/api/v1/notices/" + noticeId + "/withdraw";
        NoticeView result = idempotency.execute(jwt, "POST", path, key, body, NoticeView.class,
                () -> service.authorizeWithdraw(jwt, requestId),
                () -> service.withdraw(jwt, noticeId, body.version(), body.reason(), requestId));
        return ApiResponse.success("撤回成功", result, requestId);
    }

    @PatchMapping("/{noticeId}/pin")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "权限不足"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "请求冲突")
    })
    ApiResponse<NoticeView> pin(@AuthenticationPrincipal Jwt jwt, @PathVariable long noticeId,
                                @Valid @RequestBody ChangePinRequest body, HttpServletRequest request) {
        String requestId = RequestIds.get(request);
        return ApiResponse.success("更新成功", service.changePin(jwt, noticeId, body.isPinned(),
                body.version(), requestId), requestId);
    }

    @PostMapping("/{noticeId}/read")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "已读"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在")
    })
    ResponseEntity<Void> read(@AuthenticationPrincipal Jwt jwt, @PathVariable long noticeId,
                              HttpServletRequest request) {
        service.markRead(jwt, noticeId, RequestIds.get(request));
        return ResponseEntity.noContent().build();
    }
}
