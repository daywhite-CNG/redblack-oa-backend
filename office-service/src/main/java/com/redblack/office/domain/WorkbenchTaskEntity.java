package com.redblack.office.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("office_workbench_task")
public class WorkbenchTaskEntity {
    @TableId(type = IdType.INPUT)
    private Long taskId;
    private Long applicationId;
    private Long assigneeId;
    private String title;
    private String urgency;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
