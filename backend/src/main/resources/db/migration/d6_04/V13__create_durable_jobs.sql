CREATE TABLE fams_durable_job (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    job_kind VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_reference VARCHAR(128) NOT NULL,
    actor_account_id BIGINT NOT NULL,
    request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    execution_generation BIGINT NOT NULL DEFAULT 0,
    attempts INT NOT NULL DEFAULT 0,
    lease_owner VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(3) NULL,
    next_attempt_at DATETIME(3) NOT NULL,
    result_reference VARCHAR(255) NULL,
    last_error_code VARCHAR(100) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_job_request (actor_account_id, job_kind, request_key),
    KEY idx_job_ready (job_kind, job_status, next_attempt_at, created_at, id),
    KEY idx_job_expired (job_kind, job_status, lease_until, id),
    CONSTRAINT fk_job_actor FOREIGN KEY (actor_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT ck_job_status CHECK (job_status IN ('QUEUED','RUNNING','RETRY_WAIT','SUCCEEDED','FAILED')),
    CONSTRAINT ck_job_counters CHECK (execution_generation >= 0 AND attempts >= 0),
    CONSTRAINT ck_job_lease CHECK (
        (job_status = 'RUNNING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (job_status <> 'RUNNING' AND lease_owner IS NULL AND lease_until IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
