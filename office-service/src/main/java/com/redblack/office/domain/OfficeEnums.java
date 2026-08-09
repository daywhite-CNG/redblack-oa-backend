package com.redblack.office.domain;

public final class OfficeEnums {
    private OfficeEnums() { }

    public enum NoticeType { COMPANY, POLICY, EVENT, URGENT }
    public enum NoticeStatus { DRAFT, PUBLISHED, WITHDRAWN }
    public enum NoticeScopeType { ALL, DEPARTMENTS }
    public enum NotificationType {
        APPROVAL_PENDING, APPROVAL_APPROVED, APPROVAL_REJECTED, APPROVAL_TRANSFERRED,
        APPLICATION_WITHDRAWN, NOTICE_PUBLISHED, SYSTEM
    }
    public enum FileStatus { TEMPORARY, RESERVED, BOUND }
}
