CREATE TABLE fams_transfer (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    creator_account_id BIGINT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    request_status VARCHAR(40) NOT NULL DEFAULT 'DRAFT',
    version BIGINT NOT NULL DEFAULT 0,
    decided_by BIGINT NULL,
    decision_reason VARCHAR(1000) NULL,
    approved_version BIGINT NULL,
    processor_account_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    decided_at DATETIME(3) NULL,
    CONSTRAINT fk_transfer_creator FOREIGN KEY (creator_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT fk_transfer_decider FOREIGN KEY (decided_by) REFERENCES fams_system_account(id),
    CONSTRAINT fk_transfer_processor FOREIGN KEY (processor_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT ck_transfer_version CHECK (version >= 0),
    CONSTRAINT ck_transfer_status CHECK (request_status IN ('DRAFT','PENDING_APPROVAL','REJECTED',
        'WAITING_WAREHOUSE_CLAIM','PROCESSING','WAITING_HANDOVER','COMPLETED','PARTIALLY_COMPLETED','CANCELLED')),
    KEY idx_transfer_pool (request_status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_transfer_item (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    asset_version BIGINT NOT NULL,
    source_snapshot_json JSON NOT NULL,
    target_snapshot_json JSON NOT NULL,
    parameter_snapshot_json JSON NOT NULL,
    receiver_employee_id BIGINT NULL,
    receipt_required BOOLEAN NOT NULL,
    item_status VARCHAR(32) NOT NULL DEFAULT 'READY',
    version BIGINT NOT NULL DEFAULT 0,
    destination VARCHAR(500) NULL,
    delivery_evidence VARCHAR(1000) NULL,
    delivered_at DATETIME(3) NULL,
    receipt_deadline_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    completed_by BIGINT NULL,
    after_snapshot_json JSON NULL,
    UNIQUE KEY uk_transfer_asset (request_id,asset_id),
    KEY idx_transfer_receiver (receiver_employee_id,item_status,id),
    CONSTRAINT fk_transfer_item_request FOREIGN KEY (request_id) REFERENCES fams_transfer(id),
    CONSTRAINT fk_transfer_item_asset FOREIGN KEY (asset_id) REFERENCES fams_asset(id),
    CONSTRAINT fk_transfer_receiver FOREIGN KEY (receiver_employee_id) REFERENCES fams_employee(id),
    CONSTRAINT fk_transfer_completed_by FOREIGN KEY (completed_by) REFERENCES fams_system_account(id),
    CONSTRAINT ck_transfer_item_version CHECK (version >= 0 AND asset_version >= 0),
    CONSTRAINT ck_transfer_item_status CHECK (item_status IN ('READY','DELIVERED','REFUSED',
        'RECOVERY_PENDING','COMPLETED','CANCELLED')),
    CONSTRAINT ck_transfer_delivery CHECK ((delivered_at IS NULL AND receipt_deadline_at IS NULL)
        OR (delivered_at IS NOT NULL AND receipt_deadline_at > delivered_at)),
    CONSTRAINT ck_transfer_completion CHECK ((item_status='COMPLETED' AND completed_at IS NOT NULL
        AND completed_by IS NOT NULL AND after_snapshot_json IS NOT NULL)
        OR (item_status<>'COMPLETED' AND completed_at IS NULL AND after_snapshot_json IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_transfer_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    item_id BIGINT NULL,
    event_type VARCHAR(40) NOT NULL,
    actor_account_id BIGINT NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    detail_json JSON NOT NULL,
    CONSTRAINT fk_transfer_event_request FOREIGN KEY (request_id) REFERENCES fams_transfer(id),
    CONSTRAINT fk_transfer_event_item FOREIGN KEY (item_id) REFERENCES fams_transfer_item(id),
    CONSTRAINT fk_transfer_event_actor FOREIGN KEY (actor_account_id) REFERENCES fams_system_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_transfer_receipt_grant (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT NOT NULL,
    item_version BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    auth_epoch BIGINT NOT NULL,
    authorization_version BIGINT NOT NULL,
    token_sha256 CHAR(64) NOT NULL,
    issued_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    consumed_at DATETIME(3) NULL,
    UNIQUE KEY uk_transfer_grant_token (token_sha256),
    CONSTRAINT fk_transfer_grant_item FOREIGN KEY (item_id) REFERENCES fams_transfer_item(id),
    CONSTRAINT fk_transfer_grant_account FOREIGN KEY (account_id) REFERENCES fams_system_account(id),
    CONSTRAINT ck_transfer_grant_version CHECK (item_version >= 0),
    CONSTRAINT ck_transfer_grant_expiry CHECK (expires_at > issued_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
