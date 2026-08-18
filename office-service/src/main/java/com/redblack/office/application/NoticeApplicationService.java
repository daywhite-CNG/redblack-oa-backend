package com.redblack.office.application;

import com.redblack.office.api.OfficeApiModels.DepartmentRef;
import com.redblack.office.api.OfficeApiModels.FileSummary;
import com.redblack.office.api.OfficeApiModels.NoticeView;
import com.redblack.office.api.OfficeApiModels.PageData;
import com.redblack.office.api.OfficeApiModels.SaveNoticeRequest;
import com.redblack.office.api.OfficeApiModels.UpdateNoticeRequest;
import com.redblack.office.api.OfficeApiModels.UserRef;
import com.redblack.office.domain.FileEntity;
import com.redblack.office.domain.NoticeEntity;
import com.redblack.office.domain.OfficeEnums.FileStatus;
import com.redblack.office.domain.OfficeEnums.NoticeScopeType;
import com.redblack.office.domain.OfficeEnums.NoticeStatus;
import com.redblack.office.domain.OfficeEnums.NoticeType;
import com.redblack.office.infrastructure.persistence.FileMapper;
import com.redblack.office.infrastructure.persistence.NoticeDepartmentMapper;
import com.redblack.office.infrastructure.persistence.NoticeMapper;
import com.redblack.office.infrastructure.persistence.NoticeReadMapper;
import com.redblack.office.infrastructure.persistence.NotificationMapper;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class NoticeApplicationService {
    private final NoticeMapper notices;
    private final NoticeDepartmentMapper departments;
    private final NoticeReadMapper reads;
    private final NotificationMapper notifications;
    private final FileMapper files;
    private final OfficeAuthorizationService authorization;
    private final OfficeOutboxService outbox;
    private final Clock clock;

    public NoticeApplicationService(NoticeMapper notices, NoticeDepartmentMapper departments,
                                    NoticeReadMapper reads, NotificationMapper notifications, FileMapper files,
                                    OfficeAuthorizationService authorization, OfficeOutboxService outbox, Clock clock) {
        this.notices = notices;
        this.departments = departments;
        this.reads = reads;
        this.notifications = notifications;
        this.files = files;
        this.authorization = authorization;
        this.outbox = outbox;
        this.clock = clock;
    }

    public void authorizeCreate(Jwt jwt, String requestId) { authorization.require(jwt, "notice:create", requestId); }
    public void authorizePublish(Jwt jwt, String requestId) { authorization.require(jwt, "notice:publish", requestId); }
    public void authorizeWithdraw(Jwt jwt, String requestId) { authorization.require(jwt, "notice:withdraw", requestId); }

    public PageData<NoticeView> list(Jwt jwt, String view, String keyword, NoticeType type, NoticeStatus status,
                                     String readStatus, int page, int pageSize, String requestId) {
        var actor = authorization.require(jwt, "notice:read", requestId);
        boolean manage = switch (view) {
            case "published" -> false;
            case "manage" -> {
                if (!canManage(actor)) throw BusinessException.denied();
                yield true;
            }
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "view 参数无效");
        };
        if (readStatus != null && !readStatus.equals("READ") && !readStatus.equals("UNREAD")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "readStatus 参数无效");
        }
        int offset = (page - 1) * pageSize;
        List<NoticeView> items = notices.listVisible(manage, actor.id(), actor.departmentId(), keyword,
                        type == null ? null : type.name(), status == null ? null : status.name(), readStatus,
                        offset, pageSize).stream().map(entity -> toView(entity, actor.id())).toList();
        long total = notices.countVisible(manage, actor.id(), actor.departmentId(), keyword,
                type == null ? null : type.name(), status == null ? null : status.name(), readStatus);
        return new PageData<>(items, page, pageSize, total);
    }

    @Transactional
    public NoticeView create(Jwt jwt, SaveNoticeRequest body, String requestId) {
        var actor = authorization.require(jwt, "notice:create", requestId);
        validateScope(body.scopeType(), body.targetDepartmentIds());
        LocalDateTime now = now();
        NoticeEntity entity = new NoticeEntity();
        apply(entity, body.title(), body.summary(), body.content(), body.type(), body.scopeType(), body.isPinned(),
                body.scheduledPublishAt());
        entity.setPublisherId(actor.id());
        entity.setPublisherName(actor.context().name());
        entity.setPublisherDepartmentId(actor.departmentId());
        entity.setPublisherDepartmentName(actor.context().departmentName());
        entity.setStatus(NoticeStatus.DRAFT.name());
        entity.setReadCount(0L);
        entity.setVersion(1);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        notices.insert(entity);
        replaceScope(entity.getId(), body.targetDepartmentIds());
        bindFiles(actor.id(), entity.getId(), body.attachmentIds(), now);
        outbox.audit("NOTICE_CREATED", "NOTICE", entity.getId().toString(), Long.toString(actor.id()),
                actor.context().name(), requestId, "POST", "/api/v1/notices");
        return toView(entity, actor.id());
    }

    public NoticeView get(Jwt jwt, long noticeId, String requestId) {
        var actor = authorization.require(jwt, "notice:read", requestId);
        NoticeEntity entity = require(noticeId);
        if (!visible(entity, actor.departmentId()) && !canManage(actor)) throw BusinessException.notFound("公告不存在");
        return toView(entity, actor.id());
    }

    public List<NoticeView> recentFor(OfficeAuthorizationService.Actor actor) {
        return notices.recentVisible(actor.departmentId(), 5).stream().map(entity -> toView(entity, actor.id())).toList();
    }

    @Transactional
    public NoticeView update(Jwt jwt, long noticeId, UpdateNoticeRequest body, String requestId) {
        var actor = authorization.require(jwt, "notice:update", requestId);
        validateScope(body.scopeType(), body.targetDepartmentIds());
        NoticeEntity entity = require(noticeId);
        if (NoticeStatus.WITHDRAWN.name().equals(entity.getStatus())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_NOTICE_STATUS", "已撤回公告不能编辑");
        }
        if (!entity.getVersion().equals(body.version())) throw BusinessException.versionConflict();
        apply(entity, body.title(), body.summary(), body.content(), body.type(), body.scopeType(), body.isPinned(),
                body.scheduledPublishAt());
        entity.setUpdatedAt(now());
        if (notices.updateById(entity) != 1) throw BusinessException.versionConflict();
        replaceScope(noticeId, body.targetDepartmentIds());
        files.unbindAll("NOTICE", noticeId, now());
        bindFiles(actor.id(), noticeId, body.attachmentIds(), now());
        outbox.audit("NOTICE_UPDATED", "NOTICE", Long.toString(noticeId), Long.toString(actor.id()),
                actor.context().name(), requestId, "PUT", "/api/v1/notices/" + noticeId);
        return toView(entity, actor.id());
    }

    @Transactional
    public void delete(Jwt jwt, long noticeId, int version, String requestId) {
        var actor = authorization.require(jwt, "notice:delete", requestId);
        NoticeEntity entity = require(noticeId);
        if (!NoticeStatus.DRAFT.name().equals(entity.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "NOTICE_STATUS_CONFLICT", "仅草稿公告可以删除");
        }
        if (notices.deleteDraft(noticeId, version) != 1) throw BusinessException.versionConflict();
        files.unbindAll("NOTICE", noticeId, now());
        outbox.audit("NOTICE_DELETED", "NOTICE", Long.toString(noticeId), Long.toString(actor.id()),
                actor.context().name(), requestId, "DELETE", "/api/v1/notices/" + noticeId);
    }

    @Transactional
    public NoticeView publish(Jwt jwt, long noticeId, int version, String requestId) {
        var actor = authorization.require(jwt, "notice:publish", requestId);
        NoticeEntity entity = require(noticeId);
        if (!NoticeStatus.DRAFT.name().equals(entity.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "NOTICE_STATUS_CONFLICT", "仅草稿公告可以发布");
        }
        if (!entity.getVersion().equals(version)) throw BusinessException.versionConflict();
        entity.setStatus(NoticeStatus.PUBLISHED.name());
        entity.setPublishedAt(now());
        entity.setUpdatedAt(entity.getPublishedAt());
        if (notices.updateById(entity) != 1) throw BusinessException.versionConflict();
        outbox.notice("NOTICE_PUBLISHED", entity, Long.toString(actor.id()), requestId,
                departments.findByNotice(noticeId));
        outbox.audit("NOTICE_PUBLISHED", "NOTICE", Long.toString(noticeId), Long.toString(actor.id()),
                actor.context().name(), requestId, "POST", "/api/v1/notices/" + noticeId + "/publish");
        return toView(entity, actor.id());
    }

    @Transactional
    public NoticeView withdraw(Jwt jwt, long noticeId, int version, String reason, String requestId) {
        var actor = authorization.require(jwt, "notice:withdraw", requestId);
        NoticeEntity entity = require(noticeId);
        if (!NoticeStatus.PUBLISHED.name().equals(entity.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "NOTICE_STATUS_CONFLICT", "仅已发布公告可以撤回");
        }
        if (!entity.getVersion().equals(version)) throw BusinessException.versionConflict();
        entity.setStatus(NoticeStatus.WITHDRAWN.name());
        entity.setWithdrawReason(reason);
        entity.setWithdrawnAt(now());
        entity.setUpdatedAt(entity.getWithdrawnAt());
        if (notices.updateById(entity) != 1) throw BusinessException.versionConflict();
        outbox.notice("NOTICE_WITHDRAWN", entity, Long.toString(actor.id()), requestId,
                departments.findByNotice(noticeId));
        outbox.audit("NOTICE_WITHDRAWN", "NOTICE", Long.toString(noticeId), Long.toString(actor.id()),
                actor.context().name(), requestId, "POST", "/api/v1/notices/" + noticeId + "/withdraw");
        return toView(entity, actor.id());
    }

    @Transactional
    public NoticeView changePin(Jwt jwt, long noticeId, boolean pinned, int version, String requestId) {
        var actor = authorization.require(jwt, "notice:update", requestId);
        NoticeEntity entity = require(noticeId);
        if (!entity.getVersion().equals(version)) throw BusinessException.versionConflict();
        entity.setPinned(pinned);
        entity.setUpdatedAt(now());
        if (notices.updateById(entity) != 1) throw BusinessException.versionConflict();
        outbox.audit("NOTICE_PIN_CHANGED", "NOTICE", Long.toString(noticeId), Long.toString(actor.id()),
                actor.context().name(), requestId, "PATCH", "/api/v1/notices/" + noticeId + "/pin");
        return toView(entity, actor.id());
    }

    @Transactional
    public void markRead(Jwt jwt, long noticeId, String requestId) {
        var actor = authorization.require(jwt, "notice:read", requestId);
        NoticeEntity entity = require(noticeId);
        if (!visible(entity, actor.departmentId())) throw BusinessException.notFound("公告不存在");
        LocalDateTime readAt = now();
        if (reads.markRead(noticeId, actor.id(), readAt) == 1) notices.incrementReadCount(noticeId);
        notifications.markNoticeRead(noticeId, actor.id(), readAt);
    }

    private void apply(NoticeEntity entity, String title, String summary, String content, NoticeType type,
                       NoticeScopeType scopeType, boolean pinned, OffsetDateTime scheduledAt) {
        entity.setTitle(title.trim());
        entity.setSummary(summary.trim());
        entity.setContent(Jsoup.clean(content, Safelist.relaxed()));
        entity.setNoticeType(type.name());
        entity.setScopeType(scopeType.name());
        entity.setPinned(pinned);
        entity.setScheduledPublishAt(scheduledAt == null ? null : scheduledAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
    }

    private void validateScope(NoticeScopeType type, List<String> departmentIds) {
        if (type == NoticeScopeType.ALL && !departmentIds.isEmpty()) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "NOTICE_SCOPE_INVALID", "全员公告不能指定部门");
        }
        if (type == NoticeScopeType.DEPARTMENTS && departmentIds.isEmpty()) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "NOTICE_SCOPE_INVALID", "部门公告必须指定部门");
        }
    }

    private void replaceScope(long noticeId, List<String> values) {
        departments.deleteByNotice(noticeId);
        values.stream().map(Long::parseLong).distinct().forEach(id -> departments.insert(noticeId, id));
    }

    private void bindFiles(long ownerId, long noticeId, List<String> values, LocalDateTime updatedAt) {
        for (String value : values) {
            if (files.bind(Long.parseLong(value), ownerId, "NOTICE", noticeId, updatedAt) != 1) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_BINDING_INVALID",
                        "附件不存在、已绑定或不属于当前用户");
            }
        }
    }

    private NoticeEntity require(long id) {
        NoticeEntity entity = notices.selectById(id);
        if (entity == null) throw BusinessException.notFound("公告不存在");
        return entity;
    }

    private boolean visible(NoticeEntity entity, long departmentId) {
        return NoticeStatus.PUBLISHED.name().equals(entity.getStatus())
                && (NoticeScopeType.ALL.name().equals(entity.getScopeType())
                    || departments.includes(entity.getId(), departmentId) > 0);
    }

    private boolean canManage(OfficeAuthorizationService.Actor actor) {
        return actor.has("notice:create") || actor.has("notice:update") || actor.has("notice:publish")
                || actor.has("notice:withdraw") || actor.has("notice:delete");
    }

    private NoticeView toView(NoticeEntity entity, long userId) {
        List<String> targetDepartments = departments.findByNotice(entity.getId()).stream().map(String::valueOf).toList();
        List<FileSummary> attachments = files.findBound("NOTICE", entity.getId()).stream().map(this::file).toList();
        return new NoticeView(String.valueOf(entity.getId()), entity.getTitle(), entity.getSummary(),
                entity.getContent(), NoticeType.valueOf(entity.getNoticeType()),
                NoticeScopeType.valueOf(entity.getScopeType()), targetDepartments,
                new UserRef(String.valueOf(entity.getPublisherId()), entity.getPublisherName(),
                        String.valueOf(entity.getPublisherDepartmentId())),
                new DepartmentRef(String.valueOf(entity.getPublisherDepartmentId()), entity.getPublisherDepartmentName()),
                Boolean.TRUE.equals(entity.getPinned()), NoticeStatus.valueOf(entity.getStatus()),
                entity.getReadCount(), reads.isRead(entity.getId(), userId) > 0,
                utc(entity.getScheduledPublishAt()), utc(entity.getPublishedAt()), attachments,
                utc(entity.getCreatedAt()), utc(entity.getUpdatedAt()), entity.getVersion());
    }

    private FileSummary file(FileEntity entity) {
        FileStatus status = "BOUND".equals(entity.getStatus()) ? FileStatus.BOUND : FileStatus.TEMPORARY;
        return new FileSummary(String.valueOf(entity.getId()), entity.getOriginalName(), entity.getContentType(),
                entity.getSizeBytes(), status, utc(entity.getCreatedAt()));
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private OffsetDateTime utc(LocalDateTime value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
}
