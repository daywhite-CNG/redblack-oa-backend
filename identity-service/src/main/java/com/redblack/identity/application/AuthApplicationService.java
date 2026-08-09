package com.redblack.identity.application;

import com.redblack.identity.api.IdentityApiModels.*;
import com.redblack.identity.domain.IdentityEnums.EnabledStatus;
import com.redblack.identity.domain.UserEntity;
import com.redblack.identity.infrastructure.persistence.UserMapper;
import com.redblack.identity.infrastructure.security.JwtTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.Map;

@Service
public class AuthApplicationService {
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final AuthorizationSnapshotService authorization;
    private final IdentityViewAssembler assembler;
    private final OutboxService outbox;
    private final Clock clock;

    public AuthApplicationService(UserMapper userMapper,
                                  PasswordEncoder passwordEncoder,
                                  JwtTokenService jwtTokenService,
                                  AuthorizationSnapshotService authorization,
                                  IdentityViewAssembler assembler,
                                  OutboxService outbox,
                                  Clock clock) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.authorization = authorization;
        this.assembler = assembler;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public LoginData login(LoginRequest request, String requestId) {
        UserEntity user = userMapper.findByUsername(request.username());
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())
                || credentialExpired(user)) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
        }
        if (user.getStatus() != EnabledStatus.ENABLED) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账号已禁用");
        }
        AuthorizationSnapshot snapshot = authorization.get(user.getId());
        JwtTokenService.IssuedToken token = jwtTokenService.issue(user);
        outbox.audit("AUTH_LOGIN", "USER", Long.toString(user.getId()), Long.toString(user.getId()), requestId);
        return new LoginData(token.value(), "Bearer", token.expiresIn(), assembler.currentUser(user, snapshot));
    }

    @Transactional
    public void logout(Jwt jwt, String requestId) {
        AuthorizationSnapshot snapshot = authorization.requireActive(jwt);
        Duration remaining = Duration.between(clock.instant(), jwt.getExpiresAt());
        if (!remaining.isNegative() && !remaining.isZero()) {
            authorization.deny(jwt.getId(), remaining);
        }
        outbox.audit("AUTH_LOGOUT", "USER", snapshot.userId(), snapshot.userId(), requestId);
    }

    public CurrentUser currentUser(Jwt jwt) {
        AuthorizationSnapshot snapshot = authorization.requireActive(jwt);
        UserEntity user = requiredUser(Long.parseLong(snapshot.userId()));
        return assembler.currentUser(user, snapshot);
    }

    public UserView profile(Jwt jwt) {
        AuthorizationSnapshot snapshot = authorization.require(jwt, "account:profile:read");
        return assembler.user(requiredUser(Long.parseLong(snapshot.userId())));
    }

    @Transactional
    public UserView updateProfile(Jwt jwt, UpdateProfileRequest request, String requestId) {
        AuthorizationSnapshot snapshot = authorization.require(jwt, "account:profile:update");
        UserEntity user = requiredUser(Long.parseLong(snapshot.userId()));
        if (!user.getVersion().equals(request.version())) {
            throw BusinessException.versionConflict();
        }
        user.setName(request.name());
        user.setGender(request.gender());
        user.setPhone(request.phone());
        user.setEmail(request.email());
        if (userMapper.updateById(user) != 1) {
            throw BusinessException.versionConflict();
        }
        authorization.evict(user.getId());
        outbox.audit("ACCOUNT_PROFILE_UPDATED", "USER", Long.toString(user.getId()), snapshot.userId(), requestId);
        return assembler.user(requiredUser(user.getId()));
    }

    @Transactional
    public void changePassword(Jwt jwt, ChangePasswordRequest request, String requestId) {
        AuthorizationSnapshot snapshot = authorization.requireActive(jwt);
        UserEntity user = requiredUser(Long.parseLong(snapshot.userId()));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "当前密码不正确");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "新密码不能与当前密码相同");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setCredentialExpiresAt(null);
        user.setAuthVersion(user.getAuthVersion() + 1);
        if (userMapper.updateById(user) != 1) {
            throw BusinessException.versionConflict();
        }
        authorization.evict(user.getId());
        outbox.identity("USER_AUTHORIZATION_CHANGED", "USER", Long.toString(user.getId()), snapshot.userId(), requestId,
                Map.of("userId", Long.toString(user.getId()), "authVersion", user.getAuthVersion(),
                        "reason", "PASSWORD_CHANGED"));
        outbox.audit("ACCOUNT_PASSWORD_CHANGED", "USER", Long.toString(user.getId()), snapshot.userId(), requestId);
    }

    private boolean credentialExpired(UserEntity user) {
        return user.getCredentialExpiresAt() != null
                && user.getCredentialExpiresAt().isBefore(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    private UserEntity requiredUser(long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.notFound("用户不存在");
        }
        return user;
    }
}
