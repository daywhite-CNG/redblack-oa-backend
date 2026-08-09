package com.redblack.identity.api;

import com.redblack.common.api.ApiResponse;
import com.redblack.identity.api.IdentityApiModels.*;
import com.redblack.identity.application.DepartmentApplicationService;
import com.redblack.identity.application.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/departments")
public class DepartmentController {
    private final DepartmentApplicationService service;
    private final IdempotencyService idempotency;

    public DepartmentController(DepartmentApplicationService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @GetMapping("/tree")
    ApiResponse<List<DepartmentNode>> tree(@AuthenticationPrincipal Jwt jwt,
                                           @RequestParam(defaultValue = "false") boolean enabledOnly,
                                           HttpServletRequest request) {
        return ApiResponse.success("查询成功", service.tree(jwt, enabledOnly), RequestIds.get(request));
    }

    @PostMapping
    ResponseEntity<ApiResponse<DepartmentNode>> create(@AuthenticationPrincipal Jwt jwt,
                                                        @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                        @Valid @RequestBody CreateDepartmentRequest body,
                                                        HttpServletRequest request) {
        DepartmentNode result = idempotency.execute(jwt, "POST", "/api/v1/departments", idempotencyKey, body,
                DepartmentNode.class, () -> service.authorizeCreate(jwt, body),
                () -> service.create(jwt, body, RequestIds.get(request)));
        return ResponseEntity.created(URI.create("/api/v1/departments/" + result.id()))
                .body(ApiResponse.success("创建成功", result, RequestIds.get(request)));
    }

    @GetMapping("/{departmentId}")
    ApiResponse<DepartmentNode> get(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable long departmentId,
                                    HttpServletRequest request) {
        return ApiResponse.success("查询成功", service.get(jwt, departmentId), RequestIds.get(request));
    }

    @PutMapping("/{departmentId}")
    ApiResponse<DepartmentNode> update(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable long departmentId,
                                       @Valid @RequestBody UpdateDepartmentRequest body,
                                       HttpServletRequest request) {
        return ApiResponse.success("更新成功", service.update(jwt, departmentId, body, RequestIds.get(request)),
                RequestIds.get(request));
    }

    @PatchMapping("/{departmentId}/status")
    ApiResponse<DepartmentNode> changeStatus(@AuthenticationPrincipal Jwt jwt,
                                              @PathVariable long departmentId,
                                              @Valid @RequestBody ChangeStatusRequest body,
                                              HttpServletRequest request) {
        return ApiResponse.success("状态更新成功", service.changeStatus(jwt, departmentId, body, RequestIds.get(request)),
                RequestIds.get(request));
    }

    @DeleteMapping("/{departmentId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt,
                                @PathVariable long departmentId,
                                @RequestParam @Min(1) int version,
                                HttpServletRequest request) {
        service.delete(jwt, departmentId, version, RequestIds.get(request));
        return ResponseEntity.noContent().build();
    }
}
