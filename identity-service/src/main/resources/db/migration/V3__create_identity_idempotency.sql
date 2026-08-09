CREATE TABLE identity_idempotency_record (
    actor_id BIGINT NOT NULL,
    http_method VARCHAR(10) NOT NULL,
    request_path VARCHAR(255) NOT NULL,
    idempotency_key CHAR(36) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    response_body JSON NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (actor_id, http_method, request_path, idempotency_key),
    CONSTRAINT chk_identity_idempotency_status CHECK (status IN ('PROCESSING', 'COMPLETED')),
    INDEX idx_identity_idempotency_expiry (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
