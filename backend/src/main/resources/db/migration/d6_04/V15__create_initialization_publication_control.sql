CREATE TABLE fams_initialization_window (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    window_status VARCHAR(12) NOT NULL,
    assigned_operator_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    opened_by BIGINT NOT NULL,
    opened_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    closed_by BIGINT NULL,
    closed_at DATETIME(3) NULL,
    open_slot TINYINT GENERATED ALWAYS AS
        (CASE WHEN window_status='OPEN' THEN 1 ELSE NULL END) STORED,
    UNIQUE KEY uk_initialization_open (open_slot),
    KEY idx_initialization_operator (assigned_operator_id, window_status, expires_at),
    CONSTRAINT fk_initialization_operator FOREIGN KEY (assigned_operator_id)
        REFERENCES fams_system_account(id),
    CONSTRAINT fk_initialization_opened_by FOREIGN KEY (opened_by)
        REFERENCES fams_system_account(id),
    CONSTRAINT fk_initialization_closed_by FOREIGN KEY (closed_by)
        REFERENCES fams_system_account(id),
    CONSTRAINT ck_initialization_status CHECK (window_status IN ('OPEN','CLOSED')),
    CONSTRAINT ck_initialization_time CHECK (expires_at > opened_at),
    CONSTRAINT ck_initialization_closed CHECK
        ((window_status='OPEN' AND closed_by IS NULL AND closed_at IS NULL)
         OR (window_status='CLOSED' AND closed_by IS NOT NULL AND closed_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_asset_import_review (
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    window_id BIGINT NOT NULL,
    processor_account_id BIGINT NOT NULL,
    preview_version BIGINT NOT NULL,
    review_status VARCHAR(20) NOT NULL,
    requested_at DATETIME(3) NOT NULL,
    decided_by BIGINT NULL,
    decided_at DATETIME(3) NULL,
    decision_reason VARCHAR(500) NULL,
    CONSTRAINT fk_asset_import_review_batch FOREIGN KEY (batch_id)
        REFERENCES fams_asset_import_batch(id),
    CONSTRAINT fk_asset_import_review_window FOREIGN KEY (window_id)
        REFERENCES fams_initialization_window(id),
    CONSTRAINT fk_asset_import_review_processor FOREIGN KEY (processor_account_id)
        REFERENCES fams_system_account(id),
    CONSTRAINT fk_asset_import_review_decider FOREIGN KEY (decided_by)
        REFERENCES fams_system_account(id),
    CONSTRAINT ck_asset_import_review_status CHECK
        (review_status IN ('PENDING','APPROVED','REJECTED')),
    CONSTRAINT ck_asset_import_review_version CHECK (preview_version > 0),
    CONSTRAINT ck_asset_import_review_decision CHECK
        ((review_status='PENDING' AND decided_by IS NULL AND decided_at IS NULL)
         OR (review_status IN ('APPROVED','REJECTED')
             AND decided_by IS NOT NULL AND decided_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_asset_import_publication (
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sheet_row INT NOT NULL,
    asset_id BIGINT NOT NULL,
    asset_code VARCHAR(128) NOT NULL,
    published_at DATETIME(3) NOT NULL,
    PRIMARY KEY (batch_id, sheet_row),
    UNIQUE KEY uk_asset_import_publication_asset (asset_id),
    CONSTRAINT fk_asset_import_publication_row FOREIGN KEY (batch_id, sheet_row)
        REFERENCES fams_asset_import_row(batch_id, sheet_row),
    CONSTRAINT fk_asset_import_publication_asset FOREIGN KEY (asset_id)
        REFERENCES fams_asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE fams_asset_import_event DROP CHECK ck_asset_import_event_action;
ALTER TABLE fams_asset_import_event ADD CONSTRAINT ck_asset_import_event_action CHECK (
    action IN ('ASSET_IMPORT_UPLOADED','ASSET_IMPORT_PREVIEWED',
               'ASSET_IMPORT_REVIEW_REQUESTED','ASSET_IMPORT_REVIEW_DECIDED',
               'ASSET_IMPORT_PUBLISHED','ASSET_IMPORT_PUBLICATION_FAILED',
               'ASSET_IMPORT_ROLLBACK_REQUESTED','ASSET_IMPORT_ROLLBACK_DECIDED',
               'ASSET_IMPORT_ROLLED_BACK'));
