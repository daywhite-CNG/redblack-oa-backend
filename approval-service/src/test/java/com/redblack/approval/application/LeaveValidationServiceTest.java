package com.redblack.approval.application;

import com.redblack.approval.api.ApprovalApiModels.SaveLeaveApplicationRequest;
import com.redblack.approval.domain.ApprovalEnums.LeaveType;
import com.redblack.approval.domain.ApprovalEnums.Urgency;
import com.redblack.approval.domain.LeaveApplicationEntity;
import com.redblack.approval.infrastructure.identity.IdentityClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class LeaveValidationServiceTest {
    private final LeaveValidationService service = new LeaveValidationService(mock(IdentityClient.class));

    @Test
    void calculatesNaturalDurationRoundedToOneDecimal() {
        OffsetDateTime start = OffsetDateTime.parse("2026-08-09T08:00:00+08:00");
        SaveLeaveApplicationRequest request = new SaveLeaveApplicationRequest(LeaveType.PERSONAL, start,
                start.plusMinutes(95), Urgency.NORMAL, null, null, null, List.of());

        assertThat(service.validate(request, 10003L, "request-id").durationHours())
                .isEqualByComparingTo(new BigDecimal("1.6"));
    }

    @Test
    void rejectsInvalidRangeAndDurationOutsideBounds() {
        OffsetDateTime start = OffsetDateTime.parse("2026-08-09T08:00:00+08:00");
        assertCode(new SaveLeaveApplicationRequest(LeaveType.PERSONAL, start, start, Urgency.NORMAL,
                null, null, null, List.of()), "LEAVE_TIME_RANGE_INVALID");
        assertCode(new SaveLeaveApplicationRequest(LeaveType.PERSONAL, start, start.plusMinutes(10), Urgency.NORMAL,
                null, null, null, List.of()), "LEAVE_DURATION_OUT_OF_RANGE");
        assertCode(new SaveLeaveApplicationRequest(LeaveType.PERSONAL, start, start.plusHours(721), Urgency.NORMAL,
                null, null, null, List.of()), "LEAVE_DURATION_OUT_OF_RANGE");
    }

    @Test
    void sickLeaveRequiresAttachmentAtSubmitBoundary() {
        LeaveApplicationEntity application = completeApplication();
        application.setLeaveType(LeaveType.SICK);

        assertThatThrownBy(() -> service.validateForSubmit(application, List.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("SICK_ATTACHMENT_REQUIRED"));
    }

    @Test
    void acceptsValidAttachmentIdsForOfficeReservation() {
        SaveLeaveApplicationRequest request = new SaveLeaveApplicationRequest(null, null, null, null,
                null, null, null, List.of("50001"));
        assertThat(service.validate(request, 10003L, "request-id").attachmentIds())
                .containsExactly(50001L);
    }

    private void assertCode(SaveLeaveApplicationRequest request, String code) {
        assertThatThrownBy(() -> service.validate(request, 10003L, "request-id"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code));
    }

    private LeaveApplicationEntity completeApplication() {
        LeaveApplicationEntity application = new LeaveApplicationEntity();
        application.setLeaveType(LeaveType.PERSONAL);
        application.setStartTime(java.time.LocalDateTime.parse("2026-08-09T00:00:00"));
        application.setEndTime(java.time.LocalDateTime.parse("2026-08-09T08:00:00"));
        application.setUrgency(Urgency.NORMAL);
        application.setReason("完整的请假原因");
        application.setHandoverUserId(10002L);
        application.setContactPhone("13800138000");
        return application;
    }
}
