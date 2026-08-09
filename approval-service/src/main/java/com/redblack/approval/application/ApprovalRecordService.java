package com.redblack.approval.application;

import com.redblack.approval.domain.ApprovalEnums.ApprovalAction;
import com.redblack.approval.domain.ApprovalRecordEntity;
import com.redblack.approval.infrastructure.identity.IdentityClient.ApprovalContext;
import com.redblack.approval.infrastructure.persistence.ApprovalRecordMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class ApprovalRecordService {
    private final ApprovalRecordMapper mapper;
    private final Clock clock;

    public ApprovalRecordService(ApprovalRecordMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public void append(long applicationId,
                       int submissionRound,
                       ApprovalAction action,
                       ApprovalContext operator,
                       String fromStatus,
                       String toStatus,
                       String comment,
                       ApprovalContext target) {
        ApprovalRecordEntity record = new ApprovalRecordEntity();
        record.setApplicationId(applicationId);
        record.setSubmissionRound(submissionRound);
        record.setAction(action);
        record.setOperatorId(Long.parseLong(operator.userId()));
        record.setOperatorName(operator.name());
        record.setOperatorDepartmentId(nullableId(operator.departmentId()));
        record.setFromStatus(fromStatus);
        record.setToStatus(toStatus);
        record.setComment(blankToNull(comment));
        if (target != null) {
            record.setTargetUserId(Long.parseLong(target.userId()));
            record.setTargetUserName(target.name());
            record.setTargetDepartmentId(nullableId(target.departmentId()));
        }
        record.setOperatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        mapper.insert(record);
    }

    private Long nullableId(String value) {
        return value == null ? null : Long.parseLong(value);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
