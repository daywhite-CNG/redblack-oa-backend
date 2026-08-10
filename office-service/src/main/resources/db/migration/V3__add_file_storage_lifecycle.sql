ALTER TABLE office_file
    ADD COLUMN storage_provider VARCHAR(32) NOT NULL DEFAULT 'ALIYUN_OSS' AFTER object_key,
    ADD COLUMN bucket VARCHAR(255) NULL AFTER storage_provider,
    ADD COLUMN etag VARCHAR(128) NULL AFTER bucket,
    ADD COLUMN storage_status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE' AFTER etag,
    ADD COLUMN cleanup_attempts INT UNSIGNED NOT NULL DEFAULT 0 AFTER storage_status,
    ADD COLUMN cleanup_next_attempt_at DATETIME(3) NULL AFTER cleanup_attempts,
    ADD COLUMN cleanup_last_error VARCHAR(1000) NULL AFTER cleanup_next_attempt_at,
    ADD KEY idx_office_file_storage_cleanup (storage_status, cleanup_next_attempt_at),
    ADD CONSTRAINT ck_office_file_storage_provider CHECK (storage_provider = 'ALIYUN_OSS'),
    ADD CONSTRAINT ck_office_file_storage_status CHECK (storage_status IN ('PENDING','AVAILABLE','DELETE_PENDING'));

ALTER TABLE office_idempotency_record
    ADD COLUMN file_id BIGINT UNSIGNED NULL AFTER response_body,
    ADD KEY idx_office_idempotency_file (file_id),
    ADD CONSTRAINT fk_office_idempotency_file FOREIGN KEY (file_id) REFERENCES office_file(id) ON DELETE SET NULL;
