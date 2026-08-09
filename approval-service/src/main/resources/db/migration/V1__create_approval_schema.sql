CREATE TABLE leave_application_no_sequence (
    sequence_date DATE NOT NULL,
    next_value INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (sequence_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE leave_application (
    id BIGINT UNSIGNED NOT NULL,
    application_no VARCHAR(30) NOT NULL,
    applicant_id BIGINT UNSIGNED NOT NULL,
    applicant_name VARCHAR(50) NOT NULL,
    department_id BIGINT UNSIGNED NOT NULL,
    department_name VARCHAR(100) NOT NULL,
    leader_id BIGINT UNSIGNED NULL,
    leader_name VARCHAR(50) NULL,
    leave_type VARCHAR(24) NULL,
    start_time DATETIME(3) NULL,
    end_time DATETIME(3) NULL,
    leave_duration_hours DECIMAL(6,1) NULL,
    urgency VARCHAR(16) NULL,
    reason VARCHAR(500) NULL,
    handover_user_id BIGINT UNSIGNED NULL,
    handover_user_name VARCHAR(50) NULL,
    contact_phone VARCHAR(20) NULL,
    status VARCHAR(16) NOT NULL,
    submission_round INT UNSIGNED NOT NULL DEFAULT 0,
    current_approver_id BIGINT UNSIGNED NULL,
    current_approver_name VARCHAR(50) NULL,
    submitted_at DATETIME(3) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at DATETIME(3) NULL,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_leave_application_no (application_no),
    KEY idx_leave_applicant_updated (applicant_id, updated_at),
    KEY idx_leave_department_status (department_id, status),
    KEY idx_leave_submitted (submitted_at),
    KEY idx_leave_deleted_updated (deleted, updated_at),
    CONSTRAINT ck_leave_status CHECK (status IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT ck_leave_type CHECK (leave_type IS NULL OR leave_type IN ('ANNUAL', 'PERSONAL', 'SICK', 'MARRIAGE', 'MATERNITY', 'COMPENSATORY', 'OTHER')),
    CONSTRAINT ck_leave_urgency CHECK (urgency IS NULL OR urgency IN ('NORMAL', 'URGENT')),
    CONSTRAINT ck_leave_duration CHECK (leave_duration_hours IS NULL OR (leave_duration_hours >= 0.5 AND leave_duration_hours <= 720.0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE leave_application_attachment (
    application_id BIGINT UNSIGNED NOT NULL,
    file_id BIGINT UNSIGNED NOT NULL,
    sort_order INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (application_id, file_id),
    CONSTRAINT fk_leave_attachment_application FOREIGN KEY (application_id)
        REFERENCES leave_application (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE approval_task (
    id BIGINT UNSIGNED NOT NULL,
    application_id BIGINT UNSIGNED NOT NULL,
    submission_round INT UNSIGNED NOT NULL,
    assignee_id BIGINT UNSIGNED NOT NULL,
    assignee_name VARCHAR(50) NOT NULL,
    status VARCHAR(16) NOT NULL,
    comment VARCHAR(500) NULL,
    transferred_from_task_id BIGINT UNSIGNED NULL,
    processed_at DATETIME(3) NULL,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    active_round_key VARCHAR(100) GENERATED ALWAYS AS (
        CASE WHEN status = 'PENDING' THEN CONCAT(application_id, ':', submission_round) ELSE NULL END
    ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_approval_active_round (active_round_key),
    KEY idx_approval_assignee_status (assignee_id, status, created_at),
    KEY idx_approval_application_round (application_id, submission_round, created_at),
    CONSTRAINT fk_approval_task_application FOREIGN KEY (application_id)
        REFERENCES leave_application (id),
    CONSTRAINT fk_approval_task_transferred_from FOREIGN KEY (transferred_from_task_id)
        REFERENCES approval_task (id),
    CONSTRAINT ck_approval_task_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'TRANSFERRED', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE approval_record (
    id BIGINT UNSIGNED NOT NULL,
    application_id BIGINT UNSIGNED NOT NULL,
    submission_round INT UNSIGNED NOT NULL,
    action VARCHAR(16) NOT NULL,
    operator_id BIGINT UNSIGNED NOT NULL,
    operator_name VARCHAR(50) NOT NULL,
    operator_department_id BIGINT UNSIGNED NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NOT NULL,
    comment VARCHAR(500) NULL,
    target_user_id BIGINT UNSIGNED NULL,
    target_user_name VARCHAR(50) NULL,
    target_department_id BIGINT UNSIGNED NULL,
    operated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_approval_record_application (application_id, submission_round, operated_at),
    CONSTRAINT fk_approval_record_application FOREIGN KEY (application_id)
        REFERENCES leave_application (id),
    CONSTRAINT ck_approval_record_action CHECK (action IN ('CREATE', 'SUBMIT', 'APPROVE', 'REJECT', 'TRANSFER', 'WITHDRAW', 'RESUBMIT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE approval_idempotency_record (
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
    KEY idx_approval_idempotency_expiry (expires_at),
    CONSTRAINT ck_approval_idempotency_status CHECK (status IN ('PROCESSING', 'COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE outbox_event (
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
    KEY idx_approval_outbox_pending (status, next_attempt_at, created_at),
    CONSTRAINT ck_approval_outbox_status CHECK (status IN ('PENDING', 'SENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
