package com.redblack.approval.application;

import com.redblack.approval.domain.LeaveApplicationEntity;
import com.redblack.approval.infrastructure.identity.IdentityClient;
import com.redblack.approval.infrastructure.persistence.ApprovalTaskMapper;
import com.redblack.approval.infrastructure.persistence.LeaveApplicationMapper;
import org.springframework.stereotype.Service;

@Service
public class InternalFileAccessService {
    private final LeaveApplicationMapper applications;
    private final ApprovalTaskMapper tasks;
    private final IdentityClient identity;

    public InternalFileAccessService(LeaveApplicationMapper applications, ApprovalTaskMapper tasks,
                                     IdentityClient identity) {
        this.applications = applications;
        this.tasks = tasks;
        this.identity = identity;
    }

    public boolean canRead(long applicationId, long userId, String requestId) {
        LeaveApplicationEntity application = applications.selectById(applicationId);
        if (application == null || Boolean.TRUE.equals(application.getDeleted())) return false;
        if (application.getApplicantId() == userId || tasks.countHandledBy(applicationId, userId) > 0) return true;
        var snapshot = identity.authorization(userId, requestId);
        if (!"ENABLED".equals(snapshot.status()) || !snapshot.permissions().contains("leave:read:scope")) return false;
        return snapshot.grants().stream().anyMatch(grant -> "ALL".equals(grant.scope())
                || grant.departmentIds() != null && grant.departmentIds().contains(application.getDepartmentId().toString()));
    }
}
