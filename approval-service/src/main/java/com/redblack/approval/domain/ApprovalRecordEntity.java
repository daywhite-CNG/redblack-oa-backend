package com.redblack.approval.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redblack.approval.domain.ApprovalEnums.ApprovalAction;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("approval_record")
public class ApprovalRecordEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long applicationId;
    private Integer submissionRound;
    private ApprovalAction action;
    private Long operatorId;
    private String operatorName;
    private Long operatorDepartmentId;
    private String fromStatus;
    private String toStatus;
    private String comment;
    private Long targetUserId;
    private String targetUserName;
    private Long targetDepartmentId;
    private LocalDateTime operatedAt;
}
