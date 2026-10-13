CREATE TABLE fams_proxy_confirmation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    business_type VARCHAR(40) NOT NULL,
    business_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    business_snapshot_json JSON NOT NULL,
    evidence_metadata_json JSON NOT NULL,
    confirmation_status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    version BIGINT NOT NULL DEFAULT 0,
    previous_confirmation_id BIGINT NULL,
    observed_asset_code VARCHAR(128) NOT NULL,
    actual_signer VARCHAR(200) NOT NULL,
    actual_received_at DATETIME(3) NOT NULL,
    cannot_confirm_reason VARCHAR(1000) NOT NULL,
    contact_record VARCHAR(1000) NOT NULL,
    delegated_receipt BOOLEAN NOT NULL DEFAULT FALSE,
    delegation_relationship VARCHAR(1000) NULL,
    delegation_basis VARCHAR(1000) NULL,
    evidence_kind VARCHAR(40) NOT NULL,
    collection_method VARCHAR(32) NOT NULL,
    manual_reason VARCHAR(1000) NULL,
    evidence_snapshot_json JSON NULL,
    creator_account_id BIGINT NOT NULL,
    submitted_at DATETIME(3) NULL,
    approver_account_id BIGINT NULL,
    decision_reason VARCHAR(1000) NULL,
    decided_at DATETIME(3) NULL,
    executor_account_id BIGINT NULL,
    executed_at DATETIME(3) NULL,
    execution_result_json JSON NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT ck_proxy_confirmation_status CHECK (confirmation_status IN
        ('DRAFT','SUBMITTED','APPROVED','REJECTED','EXECUTED','WITHDRAWN')),
    CONSTRAINT ck_proxy_confirmation_version CHECK (version >= 0),
    CONSTRAINT ck_proxy_confirmation_evidence CHECK (evidence_kind IN
        ('SIGNED_RECEIPT','LOGISTICS_RECEIPT','AUTHORIZED_PROXY')),
    CONSTRAINT ck_proxy_confirmation_collection CHECK (collection_method IN
        ('SCAN_CONFIRMED','CODE_CONFIRMED','MANUAL_RECORD')),
    CONSTRAINT fk_proxy_confirmation_creator FOREIGN KEY (creator_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT fk_proxy_confirmation_approver FOREIGN KEY (approver_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT fk_proxy_confirmation_executor FOREIGN KEY (executor_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT fk_proxy_confirmation_previous FOREIGN KEY (previous_confirmation_id) REFERENCES fams_proxy_confirmation(id),
    INDEX ix_proxy_confirmation_business (business_type,business_id,item_id,id),
    INDEX ix_proxy_confirmation_pending (confirmation_status,id)
);

CREATE TABLE fams_business_result_notice (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    execution_type VARCHAR(40) NOT NULL,
    execution_id BIGINT NOT NULL,
    recipient_employee_id BIGINT NOT NULL,
    business_type VARCHAR(40) NOT NULL,
    business_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    content_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    read_at DATETIME(3) NULL,
    CONSTRAINT uk_result_notice_execution_recipient UNIQUE (execution_type,execution_id,recipient_employee_id),
    CONSTRAINT fk_result_notice_recipient FOREIGN KEY (recipient_employee_id) REFERENCES fams_employee(id),
    INDEX ix_result_notice_recipient (recipient_employee_id,id)
);

ALTER TABLE fams_requisition_handover_event
    DROP CHECK ck_req_handover_event_type,
    ADD CONSTRAINT ck_req_handover_event_type CHECK (event_type IN
        ('DELIVERED','RECEIVED','REFUSED','RECOVERY_REQUESTED','RECOVERED','CANCELLED','PROXY_RECEIVED'));
