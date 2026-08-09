package com.redblack.approval.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import com.redblack.approval.domain.ApprovalEnums.LeaveStatus;
import com.redblack.approval.domain.ApprovalEnums.LeaveType;
import com.redblack.approval.domain.ApprovalEnums.Urgency;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("leave_application")
public class LeaveApplicationEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String applicationNo;
    private Long applicantId;
    private String applicantName;
    private Long departmentId;
    private String departmentName;
    private Long leaderId;
    private String leaderName;
    private LeaveType leaveType;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private BigDecimal leaveDurationHours;
    private Urgency urgency;
    private String reason;
    private Long handoverUserId;
    private String handoverUserName;
    private String contactPhone;
    private LeaveStatus status;
    private Integer submissionRound;
    private Long currentApproverId;
    private String currentApproverName;
    private LocalDateTime submittedAt;
    @TableLogic(value = "false", delval = "true")
    private Boolean deleted;
    private LocalDateTime deletedAt;
    @Version
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
