package com.redblack.identity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class UserEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String username;
    private String passwordHash;
    private String name;
    private IdentityEnums.Gender gender;
    private String phone;
    private String email;
    private String avatarUrl;
    private Long departmentId;
    private Long leaderId;
    private IdentityEnums.EnabledStatus status;
    private Long authVersion;
    private LocalDateTime credentialExpiresAt;
    private String remark;
    @Version
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
