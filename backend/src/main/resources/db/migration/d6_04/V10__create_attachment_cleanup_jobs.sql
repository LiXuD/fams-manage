CREATE TABLE fams_attachment_deletion_job (
    object_id CHAR(36) NOT NULL PRIMARY KEY,
    reason VARCHAR(40) NOT NULL,
    job_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    last_error_code VARCHAR(40) NULL,
    KEY idx_attachment_deletion_pending (job_status, next_attempt_at, object_id),
    CONSTRAINT fk_attachment_deletion_object FOREIGN KEY (object_id) REFERENCES fams_attachment(object_id),
    CONSTRAINT ck_attachment_deletion_status CHECK (job_status IN ('PENDING','DONE')),
    CONSTRAINT ck_attachment_deletion_attempts CHECK (attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_attachment_cleanup_audit (
    object_id CHAR(36) NOT NULL PRIMARY KEY,
    run_id CHAR(36) NOT NULL,
    action VARCHAR(40) NOT NULL,
    executor_kind VARCHAR(40) NOT NULL,
    before_business_version BIGINT NOT NULL,
    after_business_version BIGINT NOT NULL,
    expiry_cutoff DATETIME(3) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_attachment_cleanup_object FOREIGN KEY (object_id) REFERENCES fams_attachment(object_id),
    CONSTRAINT ck_attachment_cleanup_versions CHECK (after_business_version = before_business_version + 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_attachment_expiry ON fams_attachment(deleted_at, created_at, object_id);
