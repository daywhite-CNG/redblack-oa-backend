package com.redblack.office.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("office_file")
public class FileEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long ownerId;
    private String originalName;
    private String contentType;
    private String extension;
    private Long sizeBytes;
    private String sha256;
    private String objectKey;
    private String status;
    private String reservedBy;
    private LocalDateTime reservedUntil;
    private String boundBusinessType;
    private Long boundBusinessId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
