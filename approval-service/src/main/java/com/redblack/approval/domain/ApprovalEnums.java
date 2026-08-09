package com.redblack.approval.domain;

public final class ApprovalEnums {
    private ApprovalEnums() {
    }

    public enum Urgency { NORMAL, URGENT }
    public enum LeaveType { ANNUAL, PERSONAL, SICK, MARRIAGE, MATERNITY, COMPENSATORY, OTHER }
    public enum LeaveStatus { DRAFT, PENDING, APPROVED, REJECTED, WITHDRAWN }
    public enum ApprovalTaskStatus { PENDING, APPROVED, REJECTED, TRANSFERRED, CANCELLED }
    public enum ApprovalAction { CREATE, SUBMIT, APPROVE, REJECT, TRANSFER, WITHDRAW, RESUBMIT }
}
