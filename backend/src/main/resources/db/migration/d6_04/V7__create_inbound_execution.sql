CREATE TABLE fams_inbound_decision (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    receipt_id BIGINT NOT NULL,
    decision_result VARCHAR(20) NOT NULL,
    comment_text VARCHAR(1000) NULL,
    decided_by_account_id BIGINT NOT NULL,
    decided_by_username_snapshot VARCHAR(128) NOT NULL,
    decided_role VARCHAR(40) NOT NULL,
    receipt_version_before BIGINT NOT NULL,
    decided_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_inbound_effective_decision (receipt_id),
    CONSTRAINT fk_inbound_decision_receipt FOREIGN KEY (receipt_id) REFERENCES fams_inbound_receipt(id),
    CONSTRAINT fk_inbound_decision_actor FOREIGN KEY (decided_by_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT ck_inbound_decision_result CHECK (decision_result IN ('APPROVED', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_inbound_approval_scope_item (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    decision_id BIGINT NOT NULL,
    receipt_item_id BIGINT NOT NULL,
    approved_asset_id BIGINT NOT NULL,
    approved_asset_version BIGINT NOT NULL,
    approved_warehouse_id BIGINT NOT NULL,
    approved_warehouse_version BIGINT NOT NULL,
    approved_location_id BIGINT NOT NULL,
    approved_location_version BIGINT NOT NULL,
    UNIQUE KEY uk_inbound_scope_item (decision_id, receipt_item_id),
    CONSTRAINT fk_inbound_scope_decision FOREIGN KEY (decision_id) REFERENCES fams_inbound_decision(id),
    CONSTRAINT fk_inbound_scope_item FOREIGN KEY (receipt_item_id) REFERENCES fams_inbound_receipt_item(id),
    CONSTRAINT fk_inbound_scope_asset FOREIGN KEY (approved_asset_id) REFERENCES fams_asset(id),
    CONSTRAINT fk_inbound_scope_warehouse FOREIGN KEY (approved_warehouse_id) REFERENCES fams_warehouse(id),
    CONSTRAINT fk_inbound_scope_location FOREIGN KEY (approved_location_id) REFERENCES fams_location(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_inbound_processing_ownership (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    receipt_id BIGINT NOT NULL,
    processor_account_id BIGINT NOT NULL,
    processor_username_snapshot VARCHAR(128) NOT NULL,
    ownership_version BIGINT NOT NULL DEFAULT 0,
    acquired_at DATETIME(3) NOT NULL,
    archived_at DATETIME(3) NULL,
    archive_reason VARCHAR(500) NULL,
    active_guard TINYINT GENERATED ALWAYS AS (CASE WHEN archived_at IS NULL THEN 1 ELSE NULL END) STORED,
    UNIQUE KEY uk_inbound_active_owner (receipt_id, active_guard),
    CONSTRAINT fk_inbound_owner_receipt FOREIGN KEY (receipt_id) REFERENCES fams_inbound_receipt(id),
    CONSTRAINT fk_inbound_owner_actor FOREIGN KEY (processor_account_id) REFERENCES fams_system_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_inbound_acceptance (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    receipt_item_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    ownership_id BIGINT NOT NULL,
    accepted_by_account_id BIGINT NOT NULL,
    accepted_by_username_snapshot VARCHAR(128) NOT NULL,
    accepted_at DATETIME(3) NOT NULL,
    business_date DATE NOT NULL,
    receipt_version_before BIGINT NOT NULL,
    asset_version_before BIGINT NOT NULL,
    asset_version_after BIGINT NOT NULL,
    lock_version BIGINT NOT NULL,
    observed_asset_code VARCHAR(128) NOT NULL,
    storage_warehouse_id BIGINT NOT NULL,
    storage_location_id BIGINT NOT NULL,
    warehouse_version_at_acceptance BIGINT NOT NULL,
    location_version_at_acceptance BIGINT NOT NULL,
    notes VARCHAR(1000) NULL,
    audit_event_id BIGINT NOT NULL,
    UNIQUE KEY uk_inbound_item_acceptance (receipt_item_id),
    UNIQUE KEY uk_inbound_acceptance_audit (audit_event_id),
    CONSTRAINT fk_inbound_accept_item FOREIGN KEY (receipt_item_id) REFERENCES fams_inbound_receipt_item(id),
    CONSTRAINT fk_inbound_accept_asset FOREIGN KEY (asset_id) REFERENCES fams_asset(id),
    CONSTRAINT fk_inbound_accept_owner FOREIGN KEY (ownership_id) REFERENCES fams_inbound_processing_ownership(id),
    CONSTRAINT fk_inbound_accept_actor FOREIGN KEY (accepted_by_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT fk_inbound_accept_warehouse FOREIGN KEY (storage_warehouse_id) REFERENCES fams_warehouse(id),
    CONSTRAINT fk_inbound_accept_location FOREIGN KEY (storage_location_id) REFERENCES fams_location(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_audit_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id CHAR(36) NOT NULL,
    actor_account_id BIGINT NOT NULL,
    actor_role VARCHAR(40) NOT NULL,
    action VARCHAR(80) NOT NULL,
    resource_type VARCHAR(40) NOT NULL,
    resource_id BIGINT NOT NULL,
    before_version BIGINT NULL,
    after_version BIGINT NULL,
    occurred_at DATETIME(3) NOT NULL,
    detail_json JSON NOT NULL,
    KEY idx_fams_audit_request (request_id),
    KEY idx_fams_audit_resource (resource_type, resource_id, occurred_at),
    CONSTRAINT fk_fams_audit_actor FOREIGN KEY (actor_account_id) REFERENCES fams_system_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE fams_inbound_acceptance
    ADD CONSTRAINT fk_inbound_accept_audit FOREIGN KEY (audit_event_id) REFERENCES fams_audit_event(id);

CREATE TABLE fams_todo (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    pool_type VARCHAR(40) NOT NULL,
    assignee_account_id BIGINT NULL,
    business_type VARCHAR(40) NOT NULL,
    business_id BIGINT NOT NULL,
    todo_type VARCHAR(40) NOT NULL,
    todo_status VARCHAR(20) NOT NULL,
    opened_at DATETIME(3) NOT NULL,
    closed_at DATETIME(3) NULL,
    active_guard TINYINT GENERATED ALWAYS AS (CASE WHEN closed_at IS NULL THEN 1 ELSE NULL END) STORED,
    UNIQUE KEY uk_fams_todo_active (business_type, business_id, todo_type, active_guard),
    KEY idx_fams_todo_pool (pool_type, todo_status, opened_at),
    CONSTRAINT fk_fams_todo_assignee FOREIGN KEY (assignee_account_id) REFERENCES fams_system_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_inbound_pool ON fams_inbound_receipt(receipt_status, updated_at, id);
CREATE INDEX idx_inbound_creator ON fams_inbound_receipt(creator_account_id, updated_at, id);
CREATE INDEX idx_inbound_item_asset ON fams_inbound_receipt_item(asset_id, receipt_id);
