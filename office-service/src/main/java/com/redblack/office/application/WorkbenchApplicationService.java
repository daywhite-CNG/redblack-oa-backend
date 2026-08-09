package com.redblack.office.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redblack.office.api.OfficeApiModels.ChartPoint;
import com.redblack.office.api.OfficeApiModels.DepartmentRef;
import com.redblack.office.api.OfficeApiModels.QuickEntry;
import com.redblack.office.api.OfficeApiModels.UserRef;
import com.redblack.office.api.OfficeApiModels.WorkbenchData;
import com.redblack.office.api.OfficeApiModels.WorkbenchLeaveApplication;
import com.redblack.office.api.OfficeApiModels.WorkbenchMetric;
import com.redblack.office.api.OfficeApiModels.WorkbenchTodo;
import com.redblack.office.domain.WorkbenchApplicationEntity;
import com.redblack.office.infrastructure.persistence.NoticeMapper;
import com.redblack.office.infrastructure.persistence.WorkbenchApplicationMapper;
import com.redblack.office.infrastructure.persistence.WorkbenchTaskMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkbenchApplicationService {
    private static final String CACHE_PREFIX = "redblack:office:workbench:";
    private final OfficeAuthorizationService authorization;
    private final WorkbenchApplicationMapper applications;
    private final WorkbenchTaskMapper tasks;
    private final NoticeMapper notices;
    private final NoticeApplicationService noticeService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration ttl;

    public WorkbenchApplicationService(OfficeAuthorizationService authorization,
                                       WorkbenchApplicationMapper applications, WorkbenchTaskMapper tasks,
                                       NoticeMapper notices, NoticeApplicationService noticeService,
                                       StringRedisTemplate redis, ObjectMapper objectMapper, Clock clock,
                                       @Value("${redblack.cache.workbench-ttl:60s}") Duration ttl) {
        this.authorization = authorization;
        this.applications = applications;
        this.tasks = tasks;
        this.notices = notices;
        this.noticeService = noticeService;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.ttl = ttl;
    }

    public WorkbenchData get(Jwt jwt, String requestId) {
        var actor = authorization.require(jwt, "dashboard:view", requestId);
        String key = CACHE_PREFIX + actor.id();
        WorkbenchData cached = cached(key);
        if (cached != null) return cached;
        WorkbenchData value = build(actor);
        cache(key, value);
        return value;
    }

    private WorkbenchData build(OfficeAuthorizationService.Actor actor) {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime monthFrom = today.with(TemporalAdjusters.firstDayOfMonth()).atStartOfDay();
        LocalDateTime monthTo = today.plusMonths(1).with(TemporalAdjusters.firstDayOfMonth()).atStartOfDay();
        List<WorkbenchMetric> metrics = List.of(
                metric("PENDING_APPROVALS", "待我审批", tasks.countPending(actor.id()), "/approval/pending"),
                metric("MY_APPLICATIONS", "我的申请", applications.countByApplicant(actor.id()), "/approval/mine"),
                metric("UNREAD_NOTICES", "未读公告", notices.unreadCount(actor.id(), actor.departmentId()), "/notices"),
                metric("MONTHLY_LEAVE_HOURS", "本月已批假时", applications.approvedHours(actor.id(), monthFrom, monthTo), "/approval/mine")
        );
        List<WorkbenchTodo> todos = tasks.pending(actor.id(), 10).stream().map(task -> new WorkbenchTodo(
                String.valueOf(task.getTaskId()), "APPROVAL_TASK", task.getTitle(), task.getUrgency(), null,
                utc(task.getCreatedAt()), "/approval-tasks/" + task.getTaskId())).toList();
        List<QuickEntry> quick = quickEntries(actor);
        List<WorkbenchLeaveApplication> recent = applications.recent(actor.id(), 5).stream()
                .map(entity -> application(entity, actor)).toList();
        return new WorkbenchData(metrics, todos, quick, recent, noticeService.recentFor(actor), trend(actor.id(), today),
                applications.leaveTypeDistribution(actor.id()).stream()
                        .map(row -> new ChartPoint(row.itemKey(), row.label(), row.value())).toList(),
                applications.statusDistribution(actor.id()).stream()
                        .map(row -> new ChartPoint(row.itemKey(), row.label(), row.value())).toList());
    }

    private List<QuickEntry> quickEntries(OfficeAuthorizationService.Actor actor) {
        List<QuickEntry> result = new ArrayList<>();
        if (actor.has("leave:create")) result.add(new QuickEntry("LEAVE_CREATE", "发起请假", "/approval/leave/new"));
        if (actor.has("approval:task:read")) result.add(new QuickEntry("APPROVAL_PENDING", "待我审批", "/approval/pending"));
        if (actor.has("notice:create")) result.add(new QuickEntry("NOTICE_CREATE", "新建公告", "/notices/new"));
        if (actor.has("notice:read")) result.add(new QuickEntry("NOTICE_READ", "查看公告", "/notices"));
        return List.copyOf(result);
    }

    private List<ChartPoint> trend(long userId, LocalDate today) {
        LocalDate from = today.minusDays(6);
        Map<LocalDate, BigDecimal> values = new LinkedHashMap<>();
        tasks.trend(userId, from.atStartOfDay()).forEach(row -> values.put(row.day(), row.value()));
        List<ChartPoint> result = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            LocalDate date = from.plusDays(day);
            result.add(new ChartPoint(date.toString(), date.toString(), values.getOrDefault(date, BigDecimal.ZERO)));
        }
        return result;
    }

    private WorkbenchLeaveApplication application(WorkbenchApplicationEntity entity,
                                                  OfficeAuthorizationService.Actor actor) {
        List<String> actions = new ArrayList<>();
        if (("DRAFT".equals(entity.getStatus()) || "REJECTED".equals(entity.getStatus())
                || "WITHDRAWN".equals(entity.getStatus())) && actor.has("leave:update:self")) {
            actions.add("EDIT");
        }
        if (("DRAFT".equals(entity.getStatus()) || "REJECTED".equals(entity.getStatus())
                || "WITHDRAWN".equals(entity.getStatus())) && actor.has("leave:submit")) {
            actions.add("SUBMIT");
        }
        if (("DRAFT".equals(entity.getStatus()) || "WITHDRAWN".equals(entity.getStatus()))) {
            actions.add("DELETE");
        }
        if ("PENDING".equals(entity.getStatus()) && actor.has("leave:withdraw")) {
            actions.add("WITHDRAW");
        }
        return new WorkbenchLeaveApplication(String.valueOf(entity.getApplicationId()), entity.getApplicationNo(),
                new UserRef(String.valueOf(entity.getApplicantId()), entity.getApplicantName(),
                        String.valueOf(entity.getDepartmentId())),
                new DepartmentRef(String.valueOf(entity.getDepartmentId()), entity.getDepartmentName()), null,
                entity.getLeaveType(), utc(entity.getStartTime()), utc(entity.getEndTime()), entity.getDurationHours(),
                entity.getUrgency(), null, null, null, List.of(), entity.getStatus(), entity.getSubmissionRound(),
                "PENDING".equals(entity.getStatus()) ? "DEPARTMENT_LEADER_APPROVAL" : null, null, actions,
                utc(entity.getCreatedAt()), utc(entity.getSubmittedAt()), utc(entity.getUpdatedAt()),
                entity.getApplicationVersion());
    }

    private WorkbenchMetric metric(String key, String label, long value, String path) {
        return metric(key, label, BigDecimal.valueOf(value), path);
    }
    private WorkbenchMetric metric(String key, String label, BigDecimal value, String path) {
        return new WorkbenchMetric(key, label, value == null ? BigDecimal.ZERO : value, null, "UNKNOWN", path);
    }

    private WorkbenchData cached(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? null : objectMapper.readValue(value, WorkbenchData.class);
        } catch (DataAccessException exception) {
            return null;
        } catch (Exception exception) {
            redis.delete(key);
            return null;
        }
    }
    private void cache(String key, WorkbenchData value) {
        try { redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl); }
        catch (Exception ignored) { }
    }
    private OffsetDateTime utc(LocalDateTime value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
}
