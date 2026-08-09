package com.redblack.identity.api;

import com.redblack.common.api.ApiResponse;
import com.redblack.identity.api.IdentityApiModels.*;
import com.redblack.identity.application.AuthApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthApplicationService service;

    public AuthController(AuthApplicationService service) {
        this.service = service;
    }

    @PostMapping("/auth/login")
    ApiResponse<LoginData> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return ApiResponse.success("登录成功", service.login(body, RequestIds.get(request)), RequestIds.get(request));
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        service.logout(jwt, RequestIds.get(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/auth/me")
    ApiResponse<CurrentUser> currentUser(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return ApiResponse.success("查询成功", service.currentUser(jwt), RequestIds.get(request));
    }

    @GetMapping("/account/profile")
    ApiResponse<UserView> profile(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return ApiResponse.success("查询成功", service.profile(jwt), RequestIds.get(request));
    }

    @PutMapping("/account/profile")
    ApiResponse<UserView> updateProfile(@AuthenticationPrincipal Jwt jwt,
                                        @Valid @RequestBody UpdateProfileRequest body,
                                        HttpServletRequest request) {
        return ApiResponse.success("更新成功", service.updateProfile(jwt, body, RequestIds.get(request)), RequestIds.get(request));
    }

    @PutMapping("/account/password")
    ResponseEntity<Void> changePassword(@AuthenticationPrincipal Jwt jwt,
                                        @Valid @RequestBody ChangePasswordRequest body,
                                        HttpServletRequest request) {
        service.changePassword(jwt, body, RequestIds.get(request));
        return ResponseEntity.noContent().build();
    }
}
