package com.redblack.identity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_permission")
public class PermissionEntity {
    @TableId(type = IdType.INPUT)
    private Long id;
    private String code;
    private String name;
    private IdentityEnums.EnabledStatus status;
    private LocalDateTime createdAt;
}
