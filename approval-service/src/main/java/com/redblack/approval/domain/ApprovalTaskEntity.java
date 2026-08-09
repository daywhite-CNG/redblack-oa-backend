package com.redblack.approval.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.redblack.approval.domain.ApprovalEnums.ApprovalTaskStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("approval_task")
public class ApprovalTaskEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long applicationId;
    private Integer submissionRound;
    private Long assigneeId;
    private String assigneeName;
    private ApprovalTaskStatus status;
    private String comment;
    private Long transferredFromTaskId;
    private LocalDateTime processedAt;
    @Version
    private Integer version;
    private LocalDateTime createdAt;
}
