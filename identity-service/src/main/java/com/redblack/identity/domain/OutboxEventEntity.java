package com.redblack.identity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("outbox_event")
public class OutboxEventEntity {
    @TableId(type = IdType.INPUT)
    private String eventId;
    private String topic;
    private String eventType;
    private String aggregateType;
    private String aggregateId;
    private String messageKey;
    private String payload;
    private String status;
    private Integer attempts;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;
    private String lastError;
}
