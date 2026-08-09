CREATE TABLE office_notice (
    id BIGINT UNSIGNED NOT NULL,
    title VARCHAR(100) NOT NULL,
    summary VARCHAR(300) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    notice_type VARCHAR(16) NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    publisher_id BIGINT UNSIGNED NOT NULL,
    publisher_name VARCHAR(50) NOT NULL,
    publisher_department_id BIGINT UNSIGNED NOT NULL,
    publisher_department_name VARCHAR(100) NOT NULL,
    is_pinned BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    scheduled_publish_at DATETIME(3) NULL,
    published_at DATETIME(3) NULL,
    withdrawn_at DATETIME(3) NULL,
    withdraw_reason VARCHAR(200) NULL,
    read_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_notice_published (status, is_pinned, published_at),
    KEY idx_notice_publisher (publisher_id, updated_at),
    CONSTRAINT ck_notice_type CHECK (notice_type IN ('COMPANY','POLICY','EVENT','URGENT')),
    CONSTRAINT ck_notice_scope CHECK (scope_type IN ('ALL','DEPARTMENTS')),
    CONSTRAINT ck_notice_status CHECK (status IN ('DRAFT','PUBLISHED','WITHDRAWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE office_notice_department (
    notice_id BIGINT UNSIGNED NOT NULL,
    department_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (notice_id, department_id),
    KEY idx_notice_department_scope (department_id, notice_id),
    CONSTRAINT fk_notice_department_notice FOREIGN KEY (notice_id) REFERENCES office_notice(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE office_notice_read (
    notice_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    read_at DATETIME(3) NOT NULL,
    PRIMARY KEY (notice_id, user_id),
    KEY idx_notice_read_user (user_id, read_at),
    CONSTRAINT fk_notice_read_notice FOREIGN KEY (notice_id) REFERENCES office_notice(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE office_notification (
    id BIGINT UNSIGNED NOT NULL,
    recipient_id BIGINT UNSIGNED NOT NULL,
    notification_type VARCHAR(32) NOT NULL,
    title VARCHAR(100) NOT NULL,
    summary VARCHAR(300) NOT NULL,
    business_type VARCHAR(50) NOT NULL,
    business_id BIGINT UNSIGNED NOT NULL,
    link VARCHAR(255) NOT NULL,
    source_event_id CHAR(36) NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    read_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_source_recipient_type (source_event_id, recipient_id, notification_type),
    KEY idx_notification_recipient_read (recipient_id, is_read, created_at),
    CONSTRAINT ck_notification_type CHECK (notification_type IN ('APPROVAL_PENDING','APPROVAL_APPROVED','APPROVAL_REJECTED','APPROVAL_TRANSFERRED','APPLICATION_WITHDRAWN','NOTICE_PUBLISHED','SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE office_file (
    id BIGINT UNSIGNED NOT NULL,
    owner_id BIGINT UNSIGNED NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    extension VARCHAR(16) NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    sha256 CHAR(64) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'TEMPORARY',
    reserved_by VARCHAR(100) NULL,
    reserved_until DATETIME(3) NULL,
    bound_business_type VARCHAR(50) NULL,
    bound_business_id BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_office_file_object_key (object_key),
    KEY idx_office_file_owner_status (owner_id, status, created_at),
    KEY idx_office_file_bound (bound_business_type, bound_business_id),
    KEY idx_office_file_cleanup (status, reserved_until, created_at),
    CONSTRAINT ck_office_file_status CHECK (status IN ('TEMPORARY','RESERVED','BOUND'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE office_workbench_application (
    application_id BIGINT UNSIGNED NOT NULL,
    application_no VARCHAR(30) NOT NULL,
    applicant_id BIGINT UNSIGNED NOT NULL,
    applicant_name VARCHAR(50) NOT NULL,
    department_id BIGINT UNSIGNED NOT NULL,
    department_name VARCHAR(100) NOT NULL,
    leave_type VARCHAR(24) NULL,
    start_time DATETIME(3) NULL,
    end_time DATETIME(3) NULL,
    duration_hours DECIMAL(6,1) NULL,
    urgency VARCHAR(16) NULL,
    status VARCHAR(16) NOT NULL,
    submission_round INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    submitted_at DATETIME(3) NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (application_id),
    KEY idx_workbench_applicant_updated (applicant_id, updated_at),
    KEY idx_workbench_department_status (department_id, status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE office_workbench_task (
    task_id BIGINT UNSIGNED NOT NULL,
    application_id BIGINT UNSIGNED NOT NULL,
    assignee_id BIGINT UNSIGNED NOT NULL,
    title VARCHAR(200) NOT NULL,
    urgency VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (task_id),
    KEY idx_workbench_task_assignee (assignee_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE office_inbox_event (
    event_id CHAR(36) NOT NULL,
    topic VARCHAR(200) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL,
    received_at DATETIME(3) NOT NULL,
    processed_at DATETIME(3) NULL,
    last_error VARCHAR(1000) NULL,
    PRIMARY KEY (event_id),
    KEY idx_office_inbox_pending (status, next_attempt_at, received_at),
    CONSTRAINT ck_office_inbox_status CHECK (status IN ('PENDING','PROCESSED','DEAD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE office_outbox_event (
    event_id CHAR(36) NOT NULL,
    topic VARCHAR(200) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    message_key VARCHAR(100) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    sent_at DATETIME(3) NULL,
    last_error VARCHAR(1000) NULL,
    PRIMARY KEY (event_id),
    KEY idx_office_outbox_pending (status, next_attempt_at, created_at),
    CONSTRAINT ck_office_outbox_status CHECK (status IN ('PENDING','SENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE office_idempotency_record (
    actor_id BIGINT UNSIGNED NOT NULL,
    http_method VARCHAR(10) NOT NULL,
    request_path VARCHAR(255) NOT NULL,
    idempotency_key CHAR(36) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    response_body JSON NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (actor_id, http_method, request_path, idempotency_key),
    KEY idx_office_idempotency_expiry (expires_at),
    CONSTRAINT ck_office_idempotency_status CHECK (status IN ('PROCESSING','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
