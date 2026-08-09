package com.redblack.identity.api;

import com.redblack.common.api.ApiResponse;
import com.redblack.identity.api.IdentityApiModels.*;
import com.redblack.identity.application.MenuApplicationService;
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
@RequestMapping("/api/v1/menus")
public class MenuController {
    private final MenuApplicationService service;
    private final IdempotencyService idempotency;

    public MenuController(MenuApplicationService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @GetMapping("/tree")
    ApiResponse<List<MenuNode>> tree(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return ApiResponse.success("查询成功", service.tree(jwt), RequestIds.get(request));
    }

    @PostMapping
    ResponseEntity<ApiResponse<MenuNode>> create(@AuthenticationPrincipal Jwt jwt,
                                                  @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                  @Valid @RequestBody CreateMenuRequest body,
                                                  HttpServletRequest request) {
        MenuNode result = idempotency.execute(jwt, "POST", "/api/v1/menus", idempotencyKey, body,
                MenuNode.class, () -> service.authorizeCreate(jwt),
                () -> service.create(jwt, body, RequestIds.get(request)));
        return ResponseEntity.created(URI.create("/api/v1/menus/" + result.id()))
                .body(ApiResponse.success("创建成功", result, RequestIds.get(request)));
    }

    @GetMapping("/{menuId}")
    ApiResponse<MenuNode> get(@AuthenticationPrincipal Jwt jwt, @PathVariable long menuId, HttpServletRequest request) {
        return ApiResponse.success("查询成功", service.get(jwt, menuId), RequestIds.get(request));
    }

    @PutMapping("/{menuId}")
    ApiResponse<MenuNode> update(@AuthenticationPrincipal Jwt jwt,
                                 @PathVariable long menuId,
                                 @Valid @RequestBody UpdateMenuRequest body,
                                 HttpServletRequest request) {
        return ApiResponse.success("更新成功", service.update(jwt, menuId, body, RequestIds.get(request)), RequestIds.get(request));
    }

    @PatchMapping("/{menuId}/status")
    ApiResponse<MenuNode> changeStatus(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable long menuId,
                                       @Valid @RequestBody ChangeStatusRequest body,
                                       HttpServletRequest request) {
        return ApiResponse.success("状态更新成功", service.changeStatus(jwt, menuId, body, RequestIds.get(request)),
                RequestIds.get(request));
    }

    @DeleteMapping("/{menuId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt,
                                @PathVariable long menuId,
                                @RequestParam @Min(1) int version,
                                HttpServletRequest request) {
        service.delete(jwt, menuId, version, RequestIds.get(request));
        return ResponseEntity.noContent().build();
    }
}
