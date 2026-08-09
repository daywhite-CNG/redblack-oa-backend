package com.redblack.identity.application;

import com.redblack.identity.api.IdentityApiModels.ApprovalContext;
import com.redblack.identity.api.IdentityApiModels.AuthorizationSnapshot;
import com.redblack.identity.api.IdentityApiModels.UserSummary;
import com.redblack.identity.domain.UserEntity;
import com.redblack.identity.infrastructure.persistence.UserMapper;
import org.springframework.stereotype.Service;

@Service
public class InternalIdentityApplicationService {
    private final UserMapper userMapper;
    private final AuthorizationSnapshotService authorizationSnapshots;

    public InternalIdentityApplicationService(UserMapper userMapper,
                                              AuthorizationSnapshotService authorizationSnapshots) {
        this.userMapper = userMapper;
        this.authorizationSnapshots = authorizationSnapshots;
    }

    public AuthorizationSnapshot authorization(long userId) {
        requireUser(userId);
        return authorizationSnapshots.get(userId);
    }

    public ApprovalContext approvalContext(long userId) {
        UserEntity user = requireUser(userId);
        return new ApprovalContext(Long.toString(user.getId()), user.getName(),
                Long.toString(user.getDepartmentId()), nullableId(user.getLeaderId()), user.getStatus());
    }

    public UserSummary summary(long userId) {
        UserEntity user = requireUser(userId);
        return new UserSummary(Long.toString(user.getId()), user.getName(),
                Long.toString(user.getDepartmentId()), user.getAvatarUrl());
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
