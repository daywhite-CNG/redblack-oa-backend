package com.redblack.identity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_menu")
public class MenuEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long parentId;
    private String name;
    private String icon;
    private IdentityEnums.MenuType type;
    private String routePath;
    private String component;
    private Long permissionId;
    private Integer sortOrder;
    private IdentityEnums.EnabledStatus status;
    private Boolean visible;
    @TableField(exist = false)
    private String enabledRouteKey;
    @Version
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
