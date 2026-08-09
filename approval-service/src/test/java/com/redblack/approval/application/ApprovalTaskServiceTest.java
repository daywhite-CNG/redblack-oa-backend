package com.redblack.approval.application;

import com.redblack.approval.api.ApprovalApiModels.ApprovalActionResult;
import com.redblack.approval.api.ApprovalApiModels.ApproveTaskRequest;
import com.redblack.approval.application.ApprovalAuthorizationService.ActorAuthorization;
import com.redblack.approval.application.ApprovalAuthorizationService.DataAccess;
import com.redblack.approval.domain.ApprovalEnums.ApprovalTaskStatus;
import com.redblack.approval.domain.ApprovalEnums.LeaveStatus;
import com.redblack.approval.domain.ApprovalTaskEntity;
import com.redblack.approval.domain.LeaveApplicationEntity;
import com.redblack.approval.infrastructure.identity.IdentityClient;
import com.redblack.approval.infrastructure.identity.IdentityClient.ApprovalContext;
import com.redblack.approval.infrastructure.identity.IdentityClient.AuthorizationSnapshot;
import com.redblack.approval.infrastructure.persistence.ApprovalTaskMapper;
import com.redblack.approval.infrastructure.persistence.LeaveApplicationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalTaskServiceTest {
    private final ApprovalTaskMapper tasks = mock(ApprovalTaskMapper.class);
    private final LeaveApplicationMapper applications = mock(LeaveApplicationMapper.class);
    private final ApprovalAuthorizationService authorization = mock(ApprovalAuthorizationService.class);
    private final IdentityClient identity = mock(IdentityClient.class);
    private final ApprovalRecordService records = mock(ApprovalRecordService.class);
    private final ApprovalViewAssembler assembler = mock(ApprovalViewAssembler.class);
    private final OutboxService outbox = mock(OutboxService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-09T08:00:00Z"), ZoneOffset.UTC);
    private final ApprovalTaskService service = new ApprovalTaskService(tasks, applications, authorization,
            identity, records, assembler, outbox, clock);
    private final Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("10002").build();
    private final ActorAuthorization actor = actor(10002L);
    private ApprovalTaskEntity task;
    private LeaveApplicationEntity application;

    @BeforeEach
    void setUpPendingTask() {
        task = new ApprovalTaskEntity();
        task.setId(50001L);
        task.setApplicationId(40001L);
        task.setSubmissionRound(1);
        task.setAssigneeId(10002L);
        task.setAssigneeName("部门领导");
        task.setStatus(ApprovalTaskStatus.PENDING);
        task.setVersion(1);

        application = new LeaveApplicationEntity();
        application.setId(40001L);
        application.setApplicationNo("LV202608090001");
        application.setApplicantId(10003L);
        application.setStatus(LeaveStatus.PENDING);
        application.setSubmissionRound(1);
        application.setCurrentApproverId(10002L);
        application.setVersion(2);

        when(authorization.require(eq(jwt), anyString(), anyString())).thenReturn(actor);
        when(tasks.selectById(50001L)).thenReturn(task);
        when(applications.selectForUpdate(40001L)).thenReturn(application);
        when(tasks.selectForUpdate(50001L)).thenReturn(task);
        when(tasks.updateState(task)).thenReturn(1);
        when(applications.updateAll(application)).thenReturn(1);
        when(identity.approvalContext(10002L, "request-id")).thenReturn(
                new ApprovalContext("10002", "部门领导", "20002", "研发部", null, null, "ENABLED"));
    }

    @Test
    void approveLocksApplicationBeforeTaskAndCommitsOneTerminalState() {
        ApprovalActionResult expected = new ApprovalActionResult("50001", ApprovalTaskStatus.APPROVED, null,
                "40001", LeaveStatus.APPROVED, OffsetDateTime.parse("2026-08-09T08:00:00Z"));
        when(assembler.action(task, application, null)).thenReturn(expected);

        ApprovalActionResult result = service.approve(jwt, 50001L, new ApproveTaskRequest(1, "同意"), "request-id");

        assertThat(result).isSameAs(expected);
        assertThat(task.getStatus()).isEqualTo(ApprovalTaskStatus.APPROVED);
        assertThat(application.getStatus()).isEqualTo(LeaveStatus.APPROVED);
        assertThat(task.getVersion()).isEqualTo(2);
        assertThat(application.getVersion()).isEqualTo(3);
        InOrder lockOrder = inOrder(applications, tasks);
        lockOrder.verify(applications).selectForUpdate(40001L);
        lockOrder.verify(tasks).selectForUpdate(50001L);
        verify(records).append(eq(40001L), eq(1), any(), any(), eq("PENDING"), eq("APPROVED"),
                eq("同意"), eq(null));
        verify(outbox).approval(eq("LEAVE_APPROVED"), eq("40001"), eq("10002"), eq("request-id"), anyMap());
    }

    @Test
    void actionCannotBeBypassedByAdministratorOrOtherUser() {
        task.setAssigneeId(10004L);

        assertThatThrownBy(() -> service.approve(jwt, 50001L, new ApproveTaskRequest(1, null), "request-id"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("APPROVAL_TASK_NOT_ASSIGNEE");
                    assertThat(exception.status().value()).isEqualTo(403);
                });
    }

    @Test
    void alreadyProcessedTaskReturnsConflict() {
        task.setStatus(ApprovalTaskStatus.APPROVED);

        assertThatThrownBy(() -> service.approve(jwt, 50001L, new ApproveTaskRequest(1, null), "request-id"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("APPROVAL_TASK_ALREADY_PROCESSED");
                    assertThat(exception.status().value()).isEqualTo(409);
                });
    }

    private ActorAuthorization actor(long actorId) {
        AuthorizationSnapshot snapshot = new AuthorizationSnapshot(Long.toString(actorId), "ENABLED", 1,
                List.of(), List.of("approval:task:read", "approval:task:approve", "approval:task:reject",
                "approval:task:transfer"), List.of(), 1, OffsetDateTime.now().plusMinutes(5));
        return new ActorAuthorization(actorId, snapshot, new DataAccess(false, false, Set.of()));
    }
}
