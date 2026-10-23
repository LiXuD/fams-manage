-- Approval changes only a future physical return destination. Original custody remains frozen.
CREATE TABLE fams_repair_approved_return_target (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    repair_id BIGINT NOT NULL,
    proposal_id BIGINT NOT NULL,
    target_json JSON NOT NULL,
    receiver_account_id BIGINT NOT NULL,
    employee_id BIGINT NOT NULL,
    organization_id BIGINT NOT NULL,
    custody_type VARCHAR(24) NOT NULL,
    approved_by BIGINT NOT NULL,
    approved_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_repair_return_target_repair FOREIGN KEY (repair_id) REFERENCES fams_repair_request(id),
    CONSTRAINT fk_repair_return_target_proposal FOREIGN KEY (proposal_id) REFERENCES fams_item_target_change(id),
    CONSTRAINT fk_repair_return_target_receiver FOREIGN KEY (receiver_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT fk_repair_return_target_employee FOREIGN KEY (employee_id) REFERENCES fams_employee(id),
    CONSTRAINT fk_repair_return_target_org FOREIGN KEY (organization_id) REFERENCES fams_organization(id),
    CONSTRAINT fk_repair_return_target_approver FOREIGN KEY (approved_by) REFERENCES fams_system_account(id),
    CONSTRAINT ck_repair_return_target_custody CHECK (custody_type IN ('PERSONAL','SHARED')),
    UNIQUE KEY uq_repair_return_target_proposal (proposal_id),
    INDEX ix_repair_return_target_latest (repair_id,id),
    INDEX ix_repair_return_target_receiver (receiver_account_id,repair_id,id)
);
