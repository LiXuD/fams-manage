-- Additive per-item target override; original demands and completed custody remain unchanged.
ALTER TABLE fams_requisition_allocation
    ADD COLUMN approved_target_json JSON NULL,
    ADD COLUMN receipt_target_employee_id BIGINT NULL,
    ADD CONSTRAINT fk_req_allocation_receipt_target FOREIGN KEY (receipt_target_employee_id) REFERENCES fams_employee(id),
    ADD INDEX ix_req_allocation_receipt_target (receipt_target_employee_id,allocation_status,id);

CREATE TABLE fams_item_target_change (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    business_type VARCHAR(40) NOT NULL,
    business_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    source_snapshot_json JSON NOT NULL,
    proposed_target_json JSON NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    observed_asset_code VARCHAR(128) NOT NULL,
    physical_verification VARCHAR(1000) NOT NULL,
    change_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    creator_account_id BIGINT NOT NULL,
    approver_account_id BIGINT NULL,
    decision_reason VARCHAR(1000) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    decided_at DATETIME(3) NULL,
    CONSTRAINT ck_item_target_change_status CHECK (change_status IN ('PENDING','APPROVED','REJECTED')),
    CONSTRAINT fk_item_target_change_creator FOREIGN KEY (creator_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT fk_item_target_change_approver FOREIGN KEY (approver_account_id) REFERENCES fams_system_account(id),
    INDEX ix_item_target_change_business (business_type,business_id,item_id,id),
    INDEX ix_item_target_change_pending (change_status,id)
);

ALTER TABLE fams_requisition_handover_event
    DROP CHECK ck_req_handover_event_type,
    ADD CONSTRAINT ck_req_handover_event_type CHECK (event_type IN
        ('DELIVERED','RECEIVED','REFUSED','RECOVERY_REQUESTED','RECOVERED','CANCELLED','PROXY_RECEIVED','TARGET_CHANGED'));
