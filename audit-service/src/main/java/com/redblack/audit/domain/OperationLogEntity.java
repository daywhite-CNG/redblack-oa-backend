package com.redblack.audit.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("audit_operation_log")
public class OperationLogEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String sourceEventId;
    private String moduleName;
    private String operationType;
    private Long operatorId;
    private String operatorName;
    private Long operatorDepartmentId;
    private String requestMethod;
    private String requestPath;
    private String ipAddress;
    private String operationResult;
    private String businessType;
    private String businessId;
    private String summary;
    private String errorCode;
    private Long durationMs;
    private LocalDateTime operatedAt;
    private String requestId;
    private LocalDateTime createdAt;
}
