package com.redblack.office.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("office_workbench_application")
public class WorkbenchApplicationEntity {
    @TableId(type = IdType.INPUT)
    private Long applicationId;
    private String applicationNo;
    private Long applicantId;
    private String applicantName;
    private Long departmentId;
    private String departmentName;
    private String leaveType;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private BigDecimal durationHours;
    private String urgency;
    private String status;
    private Integer submissionRound;
    private Integer applicationVersion;
    private LocalDateTime createdAt;
    private LocalDateTime submittedAt;
    private LocalDateTime updatedAt;
}
