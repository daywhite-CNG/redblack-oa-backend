package com.redblack.office.application;

import com.redblack.office.domain.NoticeEntity;
import com.redblack.office.infrastructure.identity.IdentityClient.AuthorizationSnapshot;
import com.redblack.office.infrastructure.identity.IdentityClient.UserContext;
import com.redblack.office.infrastructure.persistence.FileMapper;
import com.redblack.office.infrastructure.persistence.NoticeDepartmentMapper;
import com.redblack.office.infrastructure.persistence.NoticeMapper;
import com.redblack.office.infrastructure.persistence.NoticeReadMapper;
import com.redblack.office.infrastructure.persistence.NotificationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NoticeApplicationServiceTest {
    private NoticeMapper notices;
    private NoticeDepartmentMapper departments;
    private NoticeReadMapper reads;
    private NotificationMapper notifications;
    private NoticeApplicationService service;
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        notices = mock(NoticeMapper.class);
        departments = mock(NoticeDepartmentMapper.class);
        reads = mock(NoticeReadMapper.class);
        notifications = mock(NotificationMapper.class);
        var authorization = mock(OfficeAuthorizationService.class);
        var actor = new OfficeAuthorizationService.Actor(
                10003L,
                new AuthorizationSnapshot("10003", "ENABLED", 1L, List.of(), List.of("notice:read"),
                        List.of(), 1L, null),
                new UserContext("10003", "员工", "20", "研发部", null, null, "ENABLED"));
        jwt = mock(Jwt.class);
        when(authorization.require(jwt, "notice:read", "request-1")).thenReturn(actor);
        when(notices.selectById(1L)).thenReturn(publishedNotice());
        when(departments.includes(1L, 20L)).thenReturn(1);
        when(reads.markRead(eq(1L), eq(10003L), any())).thenReturn(1);
        service = new NoticeApplicationService(notices, departments, reads, notifications,
                mock(FileMapper.class), authorization, mock(OfficeOutboxService.class),
                Clock.fixed(Instant.parse("2026-08-16T05:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void marksTheMatchingNotificationReadWhenNoticeIsRead() {
        service.markRead(jwt, 1L, "request-1");

        ArgumentCaptor<LocalDateTime> readAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(reads).markRead(eq(1L), eq(10003L), readAt.capture());
        verify(notifications).markNoticeRead(1L, 10003L, readAt.getValue());
    }

    private NoticeEntity publishedNotice() {
        NoticeEntity notice = new NoticeEntity();
        notice.setId(1L);
        notice.setStatus("PUBLISHED");
        notice.setScopeType("ALL");
        return notice;
    }
}
