-- Link approved evidence to the actual repair receipt without rewriting its original delivery.
-- Replace old writers together; this expansion does not support direct application downgrade.
CREATE TABLE fams_repair_proxy_return_evidence (
    handover_id BIGINT PRIMARY KEY,
    repair_id BIGINT NOT NULL,
    confirmation_id BIGINT NOT NULL,
    actual_signer VARCHAR(200) NOT NULL,
    actual_signer_employee_id BIGINT NULL,
    delegated_receipt BOOLEAN NOT NULL,
    actual_received_at DATETIME(3) NOT NULL,
    approved_by BIGINT NOT NULL,
    executed_by BIGINT NOT NULL,
    metadata_json JSON NOT NULL,
    evidence_json JSON NOT NULL,
    recorded_at DATETIME(3) NOT NULL,
    UNIQUE KEY uq_repair_proxy_confirmation (confirmation_id),
    INDEX ix_repair_proxy_history (repair_id,handover_id),
    CONSTRAINT fk_repair_proxy_handover FOREIGN KEY (handover_id) REFERENCES fams_repair_return_handover(id),
    CONSTRAINT fk_repair_proxy_request FOREIGN KEY (repair_id) REFERENCES fams_repair_request(id),
    CONSTRAINT fk_repair_proxy_signer FOREIGN KEY (actual_signer_employee_id) REFERENCES fams_employee(id),
    CONSTRAINT fk_repair_proxy_approver FOREIGN KEY (approved_by) REFERENCES fams_system_account(id),
    CONSTRAINT fk_repair_proxy_executor FOREIGN KEY (executed_by) REFERENCES fams_system_account(id),
    CONSTRAINT ck_repair_proxy_confirmation CHECK (confirmation_id>0)
);
