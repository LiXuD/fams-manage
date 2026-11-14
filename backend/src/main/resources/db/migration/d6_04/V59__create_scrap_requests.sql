-- Add the scrap document and its separate disposition facts without altering existing asset or loan rows.
CREATE TABLE fams_scrap_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL,
    creator_account_id BIGINT NOT NULL,
    processor_account_id BIGINT NULL,
    source_type VARCHAR(24) NOT NULL,
    source_business_id BIGINT NULL,
    source_item_id BIGINT NULL,
    source_snapshot_json LONGTEXT NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    observed_condition VARCHAR(2000) NOT NULL,
    proposed_disposition VARCHAR(500) NOT NULL,
    request_status VARCHAR(40) NOT NULL DEFAULT 'PENDING_ADMIN_DECISION',
    decision_snapshot_json LONGTEXT NULL,
    execution_snapshot_json LONGTEXT NULL,
    asset_version BIGINT NOT NULL,
    ownership_version BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    active_guard TINYINT GENERATED ALWAYS AS (
        CASE WHEN request_status IN ('PENDING_ADMIN_DECISION','WAITING_WAREHOUSE_CLAIM','PROCESSING','RESTORE_REQUIRED')
             THEN 1 ELSE NULL END
    ) STORED,
    UNIQUE KEY uk_scrap_active_asset (asset_id,active_guard),
    INDEX ix_scrap_request_status (request_status,id),
    INDEX ix_scrap_request_creator (creator_account_id,id),
    INDEX ix_scrap_request_source (source_type,source_business_id,source_item_id,id),
    CONSTRAINT fk_scrap_request_asset FOREIGN KEY (asset_id) REFERENCES fams_asset(id),
    CONSTRAINT fk_scrap_request_creator FOREIGN KEY (creator_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT fk_scrap_request_processor FOREIGN KEY (processor_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT ck_scrap_request_source CHECK (
        (source_type='STANDALONE' AND source_business_id IS NULL AND source_item_id IS NULL)
        OR (source_type IN ('REPAIR','LOSS') AND source_business_id IS NOT NULL AND source_item_id IS NULL)
        OR (source_type='FACTORY_REPLACEMENT' AND source_business_id IS NOT NULL AND source_item_id IS NOT NULL)
    ),
    CONSTRAINT ck_scrap_request_status CHECK (request_status IN (
        'PENDING_ADMIN_DECISION','WAITING_WAREHOUSE_CLAIM','PROCESSING','RESTORE_REQUIRED',
        'REJECTED','CANCELLED','EXECUTED'
    )),
    CONSTRAINT ck_scrap_request_versions CHECK (asset_version>=0 AND ownership_version>=0 AND version>=0),
    CONSTRAINT ck_scrap_request_json CHECK (
        JSON_VALID(source_snapshot_json)
        AND (decision_snapshot_json IS NULL OR JSON_VALID(decision_snapshot_json))
        AND (execution_snapshot_json IS NULL OR JSON_VALID(execution_snapshot_json))
    )
);

CREATE TABLE fams_scrap_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    action VARCHAR(64) NOT NULL,
    before_version BIGINT NOT NULL,
    after_version BIGINT NOT NULL,
    actor_account_id BIGINT NOT NULL,
    actor_username VARCHAR(128) NOT NULL,
    actor_role VARCHAR(40) NOT NULL,
    detail_json LONGTEXT NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    INDEX ix_scrap_event_request (request_id,id),
    CONSTRAINT fk_scrap_event_request FOREIGN KEY (request_id) REFERENCES fams_scrap_request(id),
    CONSTRAINT fk_scrap_event_actor FOREIGN KEY (actor_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT ck_scrap_event_json CHECK (JSON_VALID(detail_json))
);

CREATE TABLE fams_scrap_receipt (
    actor_account_id BIGINT NOT NULL,
    operation VARCHAR(64) NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scrap_request_id BIGINT NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    response_json LONGTEXT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (actor_account_id,operation,request_id),
    CONSTRAINT fk_scrap_receipt_request FOREIGN KEY (scrap_request_id) REFERENCES fams_scrap_request(id),
    CONSTRAINT fk_scrap_receipt_actor FOREIGN KEY (actor_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT ck_scrap_receipt_json CHECK (JSON_VALID(response_json))
);

CREATE TABLE fams_scrap_disposition (
    request_id BIGINT NOT NULL PRIMARY KEY,
    processor_account_id BIGINT NOT NULL,
    disposition_status VARCHAR(24) NOT NULL,
    due_date DATE NULL,
    custody_snapshot_json LONGTEXT NOT NULL,
    completion_snapshot_json LONGTEXT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    INDEX ix_scrap_disposition_due (disposition_status,due_date,request_id),
    CONSTRAINT fk_scrap_disposition_request FOREIGN KEY (request_id) REFERENCES fams_scrap_request(id),
    CONSTRAINT fk_scrap_disposition_processor FOREIGN KEY (processor_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT ck_scrap_disposition_status CHECK (disposition_status IN (
        'PENDING_DISPOSITION','DISPOSED','LOSS_WRITTEN_OFF','REVERSED'
    )),
    CONSTRAINT ck_scrap_disposition_pending_due CHECK (disposition_status<>'PENDING_DISPOSITION' OR due_date IS NOT NULL),
    CONSTRAINT ck_scrap_disposition_version CHECK (version>=0),
    CONSTRAINT ck_scrap_disposition_json CHECK (
        JSON_VALID(custody_snapshot_json)
        AND (completion_snapshot_json IS NULL OR JSON_VALID(completion_snapshot_json))
    )
);
