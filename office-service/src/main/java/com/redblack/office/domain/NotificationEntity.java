package com.redblack.office.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("office_notification")
public class NotificationEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long recipientId;
    private String notificationType;
    private String title;
    private String summary;
    private String businessType;
    private Long businessId;
    private String link;
    private String sourceEventId;
    @TableField("is_read")
    private Boolean read;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
