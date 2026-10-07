CREATE TABLE fams_requisition_change_proposal (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    request_version BIGINT NOT NULL,
    previous_request_version BIGINT NOT NULL,
    previous_request_status VARCHAR(40) NOT NULL,
    proposal_json JSON NOT NULL,
    proposal_status VARCHAR(20) NOT NULL,
    proposed_by_account_id BIGINT NOT NULL,
    proposed_at DATETIME(3) NOT NULL,
    decided_at DATETIME(3) NULL,
    audit_event_id BIGINT NOT NULL,
    UNIQUE KEY uk_req_change_version (request_id, request_version),
    CONSTRAINT fk_req_change_request FOREIGN KEY (request_id) REFERENCES fams_requisition(id),
    CONSTRAINT fk_req_change_actor FOREIGN KEY (proposed_by_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT fk_req_change_audit FOREIGN KEY (audit_event_id) REFERENCES fams_audit_event(id),
    CONSTRAINT ck_req_change_versions CHECK (request_version > previous_request_version),
    CONSTRAINT ck_req_change_status CHECK (proposal_status IN ('PENDING', 'APPROVED', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
