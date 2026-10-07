CREATE TABLE fams_loan_extension (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    loan_id BIGINT NULL,
    request_id BIGINT NULL,
    target_version BIGINT NOT NULL,
    original_due_date DATE NOT NULL,
    requested_due_date DATE NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    proposed_by BIGINT NOT NULL,
    extension_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    decided_by BIGINT NULL,
    decision_reason VARCHAR(1000) NULL,
    decided_business_date DATE NULL,
    overdue_from DATE NULL,
    overdue_through DATE NULL,
    created_at DATETIME(3) NOT NULL,
    decided_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    pending_target VARCHAR(80) GENERATED ALWAYS AS (
        CASE WHEN extension_status='PENDING' THEN
            IF(loan_id IS NULL, CONCAT('REQUEST:',request_id), CONCAT('LOAN:',loan_id))
        ELSE NULL END) STORED,
    UNIQUE KEY uk_loan_extension_pending (pending_target),
    KEY idx_loan_extension_pool (extension_status,id),
    CONSTRAINT fk_loan_extension_loan FOREIGN KEY (loan_id) REFERENCES fams_loan(id),
    CONSTRAINT fk_loan_extension_request FOREIGN KEY (request_id) REFERENCES fams_requisition(id),
    CONSTRAINT fk_loan_extension_proposer FOREIGN KEY (proposed_by) REFERENCES fams_system_account(id),
    CONSTRAINT fk_loan_extension_decider FOREIGN KEY (decided_by) REFERENCES fams_system_account(id),
    CONSTRAINT ck_loan_extension_target CHECK ((loan_id IS NULL) <> (request_id IS NULL)),
    CONSTRAINT ck_loan_extension_dates CHECK (requested_due_date > original_due_date),
    CONSTRAINT ck_loan_extension_status CHECK (extension_status IN ('PENDING','APPROVED','REJECTED')),
    CONSTRAINT ck_loan_extension_decision CHECK (
        (extension_status='PENDING' AND decided_by IS NULL AND decided_at IS NULL)
        OR (extension_status<>'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT ck_loan_extension_version CHECK (target_version >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
