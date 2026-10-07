ALTER TABLE fams_requisition
    ADD COLUMN request_type VARCHAR(20) NOT NULL DEFAULT 'REQUISITION',
    ADD COLUMN expected_return_date DATE NULL,
    ADD CONSTRAINT ck_requisition_type CHECK (
        (request_type='REQUISITION' AND expected_return_date IS NULL)
        OR (request_type='BORROW' AND expected_return_date IS NOT NULL));

CREATE TABLE fams_loan (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL,
    borrower_employee_id BIGINT NOT NULL,
    using_organization_id BIGINT NOT NULL,
    source_type VARCHAR(24) NOT NULL,
    source_request_id BIGINT NULL,
    source_allocation_id BIGINT NULL,
    initial_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    initial_sheet_row INT NULL,
    borrowed_date DATE NOT NULL,
    original_return_date DATE NOT NULL,
    expected_return_date DATE NOT NULL,
    parameter_snapshot_json JSON NOT NULL,
    relation_status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    closed_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    open_asset_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN relation_status='OPEN' THEN asset_id ELSE NULL END) STORED,
    UNIQUE KEY uk_loan_open_asset (open_asset_id),
    UNIQUE KEY uk_loan_allocation (source_allocation_id),
    UNIQUE KEY uk_loan_initial_row (initial_batch_id,initial_sheet_row),
    KEY idx_loan_due (relation_status,expected_return_date,id),
    KEY idx_loan_borrower (borrower_employee_id,relation_status,id),
    CONSTRAINT fk_loan_asset FOREIGN KEY (asset_id) REFERENCES fams_asset(id),
    CONSTRAINT fk_loan_borrower FOREIGN KEY (borrower_employee_id) REFERENCES fams_employee(id),
    CONSTRAINT fk_loan_organization FOREIGN KEY (using_organization_id) REFERENCES fams_organization(id),
    CONSTRAINT fk_loan_request FOREIGN KEY (source_request_id) REFERENCES fams_requisition(id),
    CONSTRAINT fk_loan_allocation FOREIGN KEY (source_allocation_id) REFERENCES fams_requisition_allocation(id),
    CONSTRAINT fk_loan_initial_batch FOREIGN KEY (initial_batch_id) REFERENCES fams_asset_import_batch(id),
    CONSTRAINT ck_loan_source CHECK (
        (source_type='RECEIPT' AND source_request_id IS NOT NULL AND source_allocation_id IS NOT NULL
            AND initial_batch_id IS NULL AND initial_sheet_row IS NULL)
        OR (source_type='INITIAL_IMPORT' AND source_request_id IS NULL AND source_allocation_id IS NULL
            AND initial_batch_id IS NOT NULL AND initial_sheet_row >= 2)),
    CONSTRAINT ck_loan_dates CHECK (original_return_date > borrowed_date
        AND expected_return_date >= original_return_date),
    CONSTRAINT ck_loan_status CHECK (relation_status IN ('OPEN','RETURNED','VOID')),
    CONSTRAINT ck_loan_closed CHECK (
        (relation_status='OPEN' AND closed_at IS NULL)
        OR (relation_status IN ('RETURNED','VOID') AND closed_at IS NOT NULL)),
    CONSTRAINT ck_loan_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
