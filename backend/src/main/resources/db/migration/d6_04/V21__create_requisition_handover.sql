CREATE TABLE fams_requisition_handover (
    allocation_id BIGINT NOT NULL PRIMARY KEY,
    delivered_by_account_id BIGINT NOT NULL,
    delivered_at DATETIME(3) NOT NULL,
    receipt_deadline_at DATETIME(3) NOT NULL,
    destination_text VARCHAR(1000) NOT NULL,
    temporary_employee_id BIGINT NOT NULL,
    delivery_evidence VARCHAR(1000) NOT NULL,
    target_snapshot_json JSON NOT NULL,
    formal_custody_snapshot_json JSON NOT NULL,
    CONSTRAINT fk_req_handover_allocation FOREIGN KEY (allocation_id) REFERENCES fams_requisition_allocation(id),
    CONSTRAINT fk_req_handover_actor FOREIGN KEY (delivered_by_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT fk_req_handover_employee FOREIGN KEY (temporary_employee_id) REFERENCES fams_employee(id),
    CONSTRAINT ck_req_handover_deadline CHECK (receipt_deadline_at > delivered_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_requisition_handover_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    allocation_id BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    actor_account_id BIGINT NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    observed_asset_code VARCHAR(128) NULL,
    evidence_text VARCHAR(1000) NOT NULL,
    allocation_version_before BIGINT NOT NULL,
    allocation_version_after BIGINT NOT NULL,
    audit_event_id BIGINT NOT NULL,
    KEY idx_req_handover_event (allocation_id,id),
    CONSTRAINT fk_req_handover_event_allocation FOREIGN KEY (allocation_id) REFERENCES fams_requisition_allocation(id),
    CONSTRAINT fk_req_handover_event_actor FOREIGN KEY (actor_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT fk_req_handover_event_audit FOREIGN KEY (audit_event_id) REFERENCES fams_audit_event(id),
    CONSTRAINT ck_req_handover_event_type CHECK (event_type IN ('DELIVERED','RECEIVED','REFUSED','RECOVERY_REQUESTED','RECOVERED','CANCELLED')),
    CONSTRAINT ck_req_handover_event_version CHECK (allocation_version_before >= 0 AND allocation_version_after=allocation_version_before+1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_requisition_receipt_grant (
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    allocation_id BIGINT NOT NULL,
    receiver_account_id BIGINT NOT NULL,
    receiver_employee_id BIGINT NOT NULL,
    auth_epoch BIGINT NOT NULL,
    authorization_version BIGINT NOT NULL,
    request_version BIGINT NOT NULL,
    allocation_version BIGINT NOT NULL,
    issued_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    consumed_at DATETIME(3) NULL,
    KEY idx_req_receipt_grant (allocation_id,receiver_account_id,consumed_at),
    CONSTRAINT fk_req_receipt_grant_allocation FOREIGN KEY (allocation_id) REFERENCES fams_requisition_allocation(id),
    CONSTRAINT fk_req_receipt_grant_actor FOREIGN KEY (receiver_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT fk_req_receipt_grant_employee FOREIGN KEY (receiver_employee_id) REFERENCES fams_employee(id),
    CONSTRAINT ck_req_receipt_grant_expiry CHECK (expires_at>issued_at),
    CONSTRAINT ck_req_receipt_grant_versions CHECK (auth_epoch>=0 AND authorization_version>=0 AND request_version>=0 AND allocation_version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
