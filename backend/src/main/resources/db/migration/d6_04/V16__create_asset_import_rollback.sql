CREATE TABLE fams_asset_import_rollback (
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    rollback_status VARCHAR(20) NOT NULL,
    processor_account_id BIGINT NOT NULL,
    requested_by BIGINT NOT NULL,
    request_reason VARCHAR(500) NOT NULL,
    requested_at DATETIME(3) NOT NULL,
    decided_by BIGINT NULL,
    decision_reason VARCHAR(500) NULL,
    decided_at DATETIME(3) NULL,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    executed_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT fk_asset_import_rollback_batch FOREIGN KEY (batch_id)
        REFERENCES fams_asset_import_batch(id),
    CONSTRAINT fk_asset_import_rollback_processor FOREIGN KEY (processor_account_id)
        REFERENCES fams_system_account(id),
    CONSTRAINT fk_asset_import_rollback_requester FOREIGN KEY (requested_by)
        REFERENCES fams_system_account(id),
    CONSTRAINT fk_asset_import_rollback_decider FOREIGN KEY (decided_by)
        REFERENCES fams_system_account(id),
    CONSTRAINT fk_asset_import_rollback_job FOREIGN KEY (job_id)
        REFERENCES fams_durable_job(id),
    CONSTRAINT ck_asset_import_rollback_status CHECK (rollback_status IN
        ('PENDING','APPROVED','REJECTED','QUEUED','COMPLETED','FAILED')),
    CONSTRAINT ck_asset_import_rollback_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE fams_asset_import_batch DROP CHECK ck_asset_import_status;
ALTER TABLE fams_asset_import_batch ADD CONSTRAINT ck_asset_import_status CHECK (batch_status IN
    ('UPLOADED','PREVIEWED','QUEUED','PUBLISHING','PUBLISHED','FAILED','ROLLED_BACK'));

ALTER TABLE fams_asset_import_publication
    ADD COLUMN retired_at DATETIME(3) NULL,
    ADD KEY idx_asset_import_publication_retired (batch_id, retired_at);
