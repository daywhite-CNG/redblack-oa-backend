package com.redblack.identity.application;

import com.redblack.identity.api.IdentityApiModels.AuthorizationGrant;
import com.redblack.identity.api.IdentityApiModels.AuthorizationSnapshot;
import com.redblack.identity.api.IdentityApiModels.ChangeStatusRequest;
import com.redblack.identity.api.IdentityApiModels.CreateDepartmentRequest;
import com.redblack.identity.api.IdentityApiModels.UpdateDepartmentRequest;
import com.redblack.identity.domain.DepartmentEntity;
import com.redblack.identity.domain.IdentityEnums.DataScope;
import com.redblack.identity.domain.IdentityEnums.EnabledStatus;
import com.redblack.identity.infrastructure.persistence.DepartmentMapper;
import com.redblack.identity.infrastructure.persistence.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DepartmentApplicationServiceAuthorizationTest {
    private final DepartmentMapper departments = mock(DepartmentMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final AuthorizationSnapshotService authorization = mock(AuthorizationSnapshotService.class);
    private final DepartmentApplicationService service = new DepartmentApplicationService(
            departments, users, authorization, mock(IdentityViewAssembler.class), mock(OutboxService.class));
    private final Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("10001").build();

    @BeforeEach
    void authorizationIsLimitedToOneDepartment() {
        AuthorizationSnapshot snapshot = new AuthorizationSnapshot("10001", EnabledStatus.ENABLED, 1,
                List.of(), List.of(), List.of(new AuthorizationGrant(DataScope.DEPARTMENT, List.of("20002"))),
                1, OffsetDateTime.now().plusMinutes(5));
        when(authorization.require(same(jwt), anyString())).thenReturn(snapshot);
    }

    @Test
    void createCannotTargetParentOutsideCurrentDataScopeOrCreateRoot() {
        assertHidden(() -> service.authorizeCreate(jwt,
                new CreateDepartmentRequest("20003", "范围外子部门", null, 10, EnabledStatus.ENABLED)));
        assertHidden(() -> service.create(jwt,
                new CreateDepartmentRequest(null, "范围外根部门", null, 10, EnabledStatus.ENABLED), "request-id"));

        verify(departments, never()).insert(any(DepartmentEntity.class));
    }

    @Test
    void updateStatusAndDeleteHideDepartmentOutsideCurrentDataScope() {
        DepartmentEntity outside = department(20003L);
        when(departments.selectById(20003L)).thenReturn(outside);

        assertHidden(() -> service.update(jwt, 20003L,
                new UpdateDepartmentRequest("20001", "范围外部门", null, 10, EnabledStatus.ENABLED, 1),
                "request-id"));
        assertHidden(() -> service.changeStatus(jwt, 20003L,
                new ChangeStatusRequest(EnabledStatus.DISABLED, 1), "request-id"));
        assertHidden(() -> service.delete(jwt, 20003L, 1, "request-id"));

        verify(departments, never()).updateById(any(DepartmentEntity.class));
        verify(departments, never()).deleteById(anyLong());
    }

    @Test
    void updateCannotMoveDepartmentToParentOutsideCurrentDataScope() {
        when(departments.selectById(20002L)).thenReturn(department(20002L));

        assertHidden(() -> service.update(jwt, 20002L,
                new UpdateDepartmentRequest("20003", "本部门", null, 10, EnabledStatus.ENABLED, 1),
                "request-id"));

        verify(departments, never()).updateById(any(DepartmentEntity.class));
    }

    private DepartmentEntity department(long id) {
        DepartmentEntity department = new DepartmentEntity();
        department.setId(id);
        department.setName("测试部门");
        department.setStatus(EnabledStatus.ENABLED);
        department.setVersion(1);
        return department;
    }

    private void assertHidden(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("RESOURCE_NOT_FOUND");
                    assertThat(exception.status().value()).isEqualTo(404);
                });
    }
}
