package com.redblack.identity.application;

import com.redblack.identity.api.IdentityApiModels.*;
import com.redblack.identity.domain.IdentityEnums.DataScope;
import com.redblack.identity.domain.IdentityEnums.EnabledStatus;
import com.redblack.identity.domain.IdentityEnums.Gender;
import com.redblack.identity.domain.UserEntity;
import com.redblack.identity.infrastructure.persistence.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserApplicationServiceAuthorizationTest {
    private final UserMapper users = mock(UserMapper.class);
    private final DepartmentMapper departments = mock(DepartmentMapper.class);
    private final AuthorizationSnapshotService authorization = mock(AuthorizationSnapshotService.class);
    private final UserApplicationService service = new UserApplicationService(
            users, departments, mock(RoleMapper.class), mock(AssignmentMapper.class), mock(PasswordEncoder.class),
            authorization, mock(IdentityViewAssembler.class), mock(OutboxService.class), Clock.systemUTC());
    private final Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("10001").build();

    @BeforeEach
    void authorizationIsLimitedToOneDepartment() {
        AuthorizationSnapshot snapshot = new AuthorizationSnapshot("10001", EnabledStatus.ENABLED, 1,
                List.of(), List.of(), List.of(new AuthorizationGrant(DataScope.DEPARTMENT, List.of("20002"))),
                1, OffsetDateTime.now().plusMinutes(5));
        when(authorization.require(same(jwt), anyString())).thenReturn(snapshot);
    }

    @Test
    void createCannotTargetDepartmentOutsideCurrentDataScope() {
        CreateUserRequest request = new CreateUserRequest("outside", "范围外用户", "Password1", Gender.UNKNOWN,
                null, null, "20003", null, List.of("30003"), EnabledStatus.ENABLED, null);

        assertHidden(() -> service.create(jwt, request, "request-id"));

        verify(users, never()).findByUsername(anyString());
        verify(users, never()).insert(any(UserEntity.class));
    }

    @Test
    void updateStatusResetAndDeleteHideUserOutsideCurrentDataScope() {
        UserEntity outside = new UserEntity();
        outside.setId(10004L);
        outside.setDepartmentId(20003L);
        outside.setStatus(EnabledStatus.ENABLED);
        outside.setVersion(1);
        outside.setAuthVersion(1L);
        when(users.selectById(10004L)).thenReturn(outside);

        UpdateUserRequest update = new UpdateUserRequest("范围外用户", Gender.UNKNOWN, null, null,
                "20003", null, List.of("30003"), EnabledStatus.ENABLED, null, 1);
        assertHidden(() -> service.update(jwt, 10004L, update, "request-id"));
        assertHidden(() -> service.changeStatus(jwt, 10004L,
                new ChangeStatusRequest(EnabledStatus.DISABLED, 1), "request-id"));
        assertHidden(() -> service.resetPassword(jwt, 10004L, new ResetPasswordRequest(1), "request-id"));
        assertHidden(() -> service.delete(jwt, 10004L, 1, "request-id"));

        verify(users, never()).updateById(any(UserEntity.class));
        verify(users, never()).deleteById(anyLong());
    }

    private void assertHidden(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("RESOURCE_NOT_FOUND");
                    assertThat(exception.status().value()).isEqualTo(404);
                });
    }
}
