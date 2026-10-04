CREATE TABLE fams_asset_import_batch (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    actor_account_id BIGINT NOT NULL,
    upload_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_bytes INT NOT NULL,
    source_xlsx LONGBLOB NOT NULL,
    batch_status VARCHAR(24) NOT NULL DEFAULT 'UPLOADED',
    mapping_json JSON NULL,
    preview_version BIGINT NOT NULL DEFAULT 0,
    total_rows INT NOT NULL DEFAULT 0,
    ready_rows INT NOT NULL DEFAULT 0,
    error_rows INT NOT NULL DEFAULT 0,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_asset_import_upload (actor_account_id, upload_key),
    KEY idx_asset_import_actor (actor_account_id, created_at, id),
    CONSTRAINT fk_asset_import_actor FOREIGN KEY (actor_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT fk_asset_import_job FOREIGN KEY (job_id) REFERENCES fams_durable_job(id),
    CONSTRAINT ck_asset_import_status CHECK (batch_status IN (
        'UPLOADED','PREVIEWED','QUEUED','PUBLISHING','PUBLISHED','FAILED')),
    CONSTRAINT ck_asset_import_source_bytes CHECK (source_bytes > 0 AND source_bytes <= 20971520),
    CONSTRAINT ck_asset_import_counts CHECK (preview_version >= 0 AND total_rows >= 0
        AND ready_rows >= 0 AND error_rows >= 0 AND ready_rows + error_rows <= total_rows)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_asset_import_row (
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sheet_row INT NOT NULL,
    source_json JSON NOT NULL,
    resolved_json JSON NULL,
    errors_json JSON NULL,
    row_status VARCHAR(12) NOT NULL,
    PRIMARY KEY (batch_id, sheet_row),
    KEY idx_asset_import_row_status (batch_id, row_status, sheet_row),
    CONSTRAINT fk_asset_import_row_batch FOREIGN KEY (batch_id)
        REFERENCES fams_asset_import_batch(id),
    CONSTRAINT ck_asset_import_row_number CHECK (sheet_row >= 2 AND sheet_row <= 20001),
    CONSTRAINT ck_asset_import_row_status CHECK (row_status IN ('READY','ERROR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_asset_import_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_account_id BIGINT NOT NULL,
    action VARCHAR(40) NOT NULL,
    detail_json JSON NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    KEY idx_asset_import_event_batch (batch_id, id),
    CONSTRAINT fk_asset_import_event_batch FOREIGN KEY (batch_id)
        REFERENCES fams_asset_import_batch(id),
    CONSTRAINT fk_asset_import_event_actor FOREIGN KEY (actor_account_id)
        REFERENCES fams_system_account(id),
    CONSTRAINT ck_asset_import_event_action CHECK (action IN ('ASSET_IMPORT_UPLOADED','ASSET_IMPORT_PREVIEWED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
