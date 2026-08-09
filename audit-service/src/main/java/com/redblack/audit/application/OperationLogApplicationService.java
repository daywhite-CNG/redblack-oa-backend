package com.redblack.audit.application;

import com.redblack.audit.api.AuditApiModels.OperationLogView;
import com.redblack.audit.api.AuditApiModels.PageData;
import com.redblack.audit.api.AuditApiModels.UserRef;
import com.redblack.audit.domain.OperationLogEntity;
import com.redblack.audit.infrastructure.persistence.OperationLogMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class OperationLogApplicationService {
    private final OperationLogMapper mapper;
    private final AuditAuthorizationService authorization;
    public OperationLogApplicationService(OperationLogMapper mapper, AuditAuthorizationService authorization) {
        this.mapper = mapper; this.authorization = authorization;
    }
    public PageData<OperationLogView> list(Jwt jwt, String operatorName, String module, String operationType,
                                           String result, OffsetDateTime from, OffsetDateTime to, String sort,
                                           int page, int pageSize, String requestId) {
        authorization.requireRead(jwt, requestId);
        if (result != null && !result.equals("SUCCESS") && !result.equals("FAILURE")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "result 参数无效");
        }
        boolean ascending = switch (sort) {
            case "operatedAt,asc" -> true;
            case "operatedAt,desc", "updatedAt,desc" -> false;
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "sort 参数无效");
        };
        LocalDateTime fromUtc = utc(from);
        LocalDateTime toUtc = utc(to);
        var items = mapper.listLogs(operatorName, module, operationType, result, fromUtc, toUtc, ascending,
                (page - 1) * pageSize, pageSize).stream().map(this::view).toList();
        return new PageData<>(items, page, pageSize,
                mapper.countLogs(operatorName, module, operationType, result, fromUtc, toUtc));
    }
    public OperationLogView get(Jwt jwt, long id, String requestId) {
        authorization.requireRead(jwt, requestId);
        OperationLogEntity entity = mapper.selectById(id);
        if (entity == null) throw BusinessException.notFound("操作日志不存在");
        return view(entity);
    }
    private OperationLogView view(OperationLogEntity entity) {
        return new OperationLogView(String.valueOf(entity.getId()), entity.getModuleName(), entity.getOperationType(),
                new UserRef(String.valueOf(entity.getOperatorId()), entity.getOperatorName(),
                        entity.getOperatorDepartmentId() == null ? null : String.valueOf(entity.getOperatorDepartmentId())),
                entity.getRequestMethod(), entity.getRequestPath(), entity.getIpAddress(), entity.getOperationResult(),
                entity.getBusinessType(), entity.getBusinessId(), entity.getSummary(), entity.getErrorCode(),
                entity.getDurationMs(), entity.getOperatedAt().atOffset(ZoneOffset.UTC), entity.getRequestId());
    }
    private LocalDateTime utc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
