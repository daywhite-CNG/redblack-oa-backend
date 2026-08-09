package com.redblack.office.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("office_notice")
public class NoticeEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String title;
    private String summary;
    private String content;
    private String noticeType;
    private String scopeType;
    private Long publisherId;
    private String publisherName;
    private Long publisherDepartmentId;
    private String publisherDepartmentName;
    @TableField("is_pinned")
    private Boolean pinned;
    private String status;
    private LocalDateTime scheduledPublishAt;
    private LocalDateTime publishedAt;
    private LocalDateTime withdrawnAt;
    private String withdrawReason;
    private Long readCount;
    @Version
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
