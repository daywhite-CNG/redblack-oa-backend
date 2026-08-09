package com.redblack.office.application;

import com.redblack.office.api.OfficeApiModels.NotificationView;
import com.redblack.office.api.OfficeApiModels.PageData;
import com.redblack.office.domain.NotificationEntity;
import com.redblack.office.domain.OfficeEnums.NotificationType;
import com.redblack.office.infrastructure.persistence.NotificationMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class NotificationApplicationService {
    private final NotificationMapper mapper;
    private final OfficeAuthorizationService authorization;
    private final Clock clock;

    public NotificationApplicationService(NotificationMapper mapper, OfficeAuthorizationService authorization,
                                          Clock clock) {
        this.mapper = mapper;
        this.authorization = authorization;
        this.clock = clock;
    }

    public PageData<NotificationView> list(Jwt jwt, String readStatus, NotificationType type,
                                           int page, int pageSize, String requestId) {
        var actor = authorization.requireActive(jwt, requestId);
        var items = mapper.listForUser(actor.id(), readStatus, type == null ? null : type.name(),
                (page - 1) * pageSize, pageSize).stream().map(this::view).toList();
        return new PageData<>(items, page, pageSize,
                mapper.countForUser(actor.id(), readStatus, type == null ? null : type.name()));
    }

    public long unreadCount(Jwt jwt, String requestId) {
        return mapper.unreadCount(authorization.requireActive(jwt, requestId).id());
    }

    @Transactional
    public void markRead(Jwt jwt, long id, String requestId) {
        var actor = authorization.requireActive(jwt, requestId);
        NotificationEntity entity = mapper.findForUser(id, actor.id());
        if (entity == null) {
            throw BusinessException.notFound("通知不存在");
        }
        mapper.markRead(id, actor.id(), now());
    }

    @Transactional
    public long markAllRead(Jwt jwt, String requestId) {
        return mapper.markAllRead(authorization.requireActive(jwt, requestId).id(), now());
    }

    private NotificationView view(NotificationEntity entity) {
        return new NotificationView(String.valueOf(entity.getId()), NotificationType.valueOf(entity.getNotificationType()),
                entity.getTitle(), entity.getSummary(), entity.getBusinessType(), String.valueOf(entity.getBusinessId()),
                entity.getLink(), Boolean.TRUE.equals(entity.getRead()), utc(entity.getReadAt()), utc(entity.getCreatedAt()));
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private OffsetDateTime utc(LocalDateTime value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
}
