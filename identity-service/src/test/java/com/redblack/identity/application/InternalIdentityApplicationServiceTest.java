package com.redblack.identity.application;

import com.redblack.identity.domain.DepartmentEntity;
import com.redblack.identity.domain.IdentityEnums.EnabledStatus;
import com.redblack.identity.domain.UserEntity;
import com.redblack.identity.infrastructure.persistence.DepartmentMapper;
import com.redblack.identity.infrastructure.persistence.UserMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalIdentityApplicationServiceTest {
    @Test
    void approvalContextContainsStableDisplaySnapshotsWithoutSensitiveFields() {
        UserMapper users = mock(UserMapper.class);
        DepartmentMapper departments = mock(DepartmentMapper.class);
        UserEntity employee = new UserEntity();
        employee.setId(10003L);
        employee.setName("普通员工");
        employee.setDepartmentId(20002L);
        employee.setLeaderId(10002L);
        employee.setStatus(EnabledStatus.ENABLED);
        UserEntity leader = new UserEntity();
        leader.setId(10002L);
        leader.setName("部门领导");
        DepartmentEntity department = new DepartmentEntity();
        department.setId(20002L);
        department.setName("研发部");
        when(users.selectById(10003L)).thenReturn(employee);
        when(users.selectById(10002L)).thenReturn(leader);
        when(departments.selectById(20002L)).thenReturn(department);
        InternalIdentityApplicationService service = new InternalIdentityApplicationService(users, departments,
                mock(AuthorizationSnapshotService.class));

        var context = service.approvalContext(10003L);

        assertThat(context.userId()).isEqualTo("10003");
        assertThat(context.departmentName()).isEqualTo("研发部");
        assertThat(context.leaderId()).isEqualTo("10002");
        assertThat(context.leaderName()).isEqualTo("部门领导");
        assertThat(context.status()).isEqualTo(EnabledStatus.ENABLED);
    }
}
