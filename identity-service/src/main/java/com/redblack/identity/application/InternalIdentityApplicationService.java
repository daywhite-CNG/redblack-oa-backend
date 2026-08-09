package com.redblack.identity.application;

import com.redblack.identity.api.IdentityApiModels.ApprovalContext;
import com.redblack.identity.api.IdentityApiModels.AuthorizationSnapshot;
import com.redblack.identity.api.IdentityApiModels.UserSummary;
import com.redblack.identity.api.IdentityApiModels.AudienceRequest;
import com.redblack.identity.api.IdentityApiModels.AudienceUser;
import com.redblack.identity.domain.DepartmentEntity;
import com.redblack.identity.domain.UserEntity;
import com.redblack.identity.infrastructure.persistence.DepartmentMapper;
import com.redblack.identity.infrastructure.persistence.UserMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class InternalIdentityApplicationService {
    private final UserMapper userMapper;
    private final DepartmentMapper departmentMapper;
    private final AuthorizationSnapshotService authorizationSnapshots;

    public InternalIdentityApplicationService(UserMapper userMapper,
                                              DepartmentMapper departmentMapper,
                                              AuthorizationSnapshotService authorizationSnapshots) {
        this.userMapper = userMapper;
        this.departmentMapper = departmentMapper;
        this.authorizationSnapshots = authorizationSnapshots;
    }

    public AuthorizationSnapshot authorization(long userId) {
        requireUser(userId);
        return authorizationSnapshots.get(userId);
    }

    public ApprovalContext approvalContext(long userId) {
        UserEntity user = requireUser(userId);
        DepartmentEntity department = departmentMapper.selectById(user.getDepartmentId());
        UserEntity leader = user.getLeaderId() == null ? null : userMapper.selectById(user.getLeaderId());
        return new ApprovalContext(Long.toString(user.getId()), user.getName(),
                Long.toString(user.getDepartmentId()), department == null ? null : department.getName(),
                nullableId(user.getLeaderId()), leader == null ? null : leader.getName(), user.getStatus());
    }

    public UserSummary summary(long userId) {
        UserEntity user = requireUser(userId);
        return new UserSummary(Long.toString(user.getId()), user.getName(),
                Long.toString(user.getDepartmentId()), user.getAvatarUrl());
    }

    public List<AudienceUser> audience(AudienceRequest request) {
        if (request == null || request.scopeType() == null || request.departmentIds() == null) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INTERNAL_VALIDATION_FAILED", "公告受众参数无效");
        }
        List<Long> departments;
        if ("ALL".equals(request.scopeType())) {
            if (!request.departmentIds().isEmpty()) {
                throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "INTERNAL_VALIDATION_FAILED", "全员受众不能指定部门");
            }
            departments = List.of();
        } else if ("DEPARTMENTS".equals(request.scopeType()) && !request.departmentIds().isEmpty()) {
            try { departments = request.departmentIds().stream().map(Long::parseLong).distinct().toList(); }
            catch (RuntimeException exception) {
                throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "INTERNAL_VALIDATION_FAILED", "部门 ID 无效");
            }
        } else {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INTERNAL_VALIDATION_FAILED", "公告受众范围无效");
        }
        return userMapper.findEnabledAudience(departments).stream()
                .map(user -> new AudienceUser(user.getId().toString(), user.getName(), user.getDepartmentId().toString()))
                .toList();
    }

    private UserEntity requireUser(long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.notFound("用户不存在");
        }
        return user;
    }

    private String nullableId(Long value) {
        return value == null ? null : value.toString();
    }
}
