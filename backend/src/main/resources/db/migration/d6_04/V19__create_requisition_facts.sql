CREATE TABLE fams_requisition (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_no VARCHAR(128) NOT NULL,
    creator_account_id BIGINT NOT NULL,
    creator_username_snapshot VARCHAR(128) NOT NULL,
    request_status VARCHAR(40) NOT NULL,
    request_version BIGINT NOT NULL DEFAULT 0,
    parameter_version_id BIGINT NOT NULL,
    parameter_snapshot_json JSON NOT NULL,
    source_request_id BIGINT NULL,
    source_demand_id BIGINT NULL,
    source_reason_snapshot_json JSON NULL,
    submitted_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_requisition_no (request_no),
    KEY idx_requisition_pool (request_status, updated_at, id),
    KEY idx_requisition_creator (creator_account_id, updated_at, id),
    UNIQUE KEY uk_requisition_source_identity (source_request_id, source_demand_id, id),
    CONSTRAINT fk_requisition_creator FOREIGN KEY (creator_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT fk_requisition_parameter FOREIGN KEY (parameter_version_id)
        REFERENCES fams_business_parameter_version(id),
    CONSTRAINT ck_requisition_status CHECK (request_status IN (
        'DRAFT', 'PENDING_ADMIN_DECISION', 'REJECTED', 'WAITING_WAREHOUSE_CLAIM',
        'WAREHOUSE_PROCESSING', 'WAITING_HANDOVER', 'PARTIALLY_COMPLETED',
        'COMPLETED', 'UNFULFILLABLE', 'CANCELLED')),
    CONSTRAINT ck_requisition_versions CHECK (request_version >= 0 AND version >= 0),
    CONSTRAINT ck_requisition_source CHECK (
        (source_request_id IS NULL AND source_demand_id IS NULL AND source_reason_snapshot_json IS NULL)
        OR (source_request_id IS NOT NULL AND source_demand_id IS NOT NULL
            AND source_reason_snapshot_json IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_requisition_demand (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    line_no INT NOT NULL,
    category_id BIGINT NOT NULL,
    category_version_snapshot BIGINT NOT NULL,
    category_name_snapshot VARCHAR(128) NOT NULL,
    specification VARCHAR(1000) NOT NULL,
    preferred_brand VARCHAR(128) NULL,
    preferred_model VARCHAR(128) NULL,
    purpose VARCHAR(1000) NOT NULL,
    expected_date DATE NOT NULL,
    custody_type VARCHAR(20) NOT NULL,
    target_organization_id BIGINT NOT NULL,
    organization_version_snapshot BIGINT NOT NULL,
    target_employee_id BIGINT NULL,
    target_department_employee_id BIGINT NULL,
    employee_version_snapshot BIGINT NOT NULL,
    target_location_id BIGINT NOT NULL,
    location_version_snapshot BIGINT NOT NULL,
    target_snapshot_json JSON NOT NULL,
    requested_quantity INT NOT NULL,
    pending_allocation_quantity INT NOT NULL,
    pending_handover_quantity INT NOT NULL DEFAULT 0,
    completed_quantity INT NOT NULL DEFAULT 0,
    unfulfillable_quantity INT NOT NULL DEFAULT 0,
    cancelled_quantity INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_requisition_line (request_id, line_no),
    UNIQUE KEY uk_requisition_demand_identity (request_id, id),
    KEY idx_requisition_target_org (target_organization_id, request_id),
    KEY idx_requisition_target_employee (target_employee_id, request_id),
    KEY idx_requisition_target_responsible (target_department_employee_id, request_id),
    KEY idx_requisition_target_location (target_location_id, request_id),
    CONSTRAINT fk_req_demand_request FOREIGN KEY (request_id) REFERENCES fams_requisition(id),
    CONSTRAINT fk_req_demand_category FOREIGN KEY (category_id) REFERENCES fams_asset_category(id),
    CONSTRAINT fk_req_demand_org FOREIGN KEY (target_organization_id) REFERENCES fams_organization(id),
    CONSTRAINT fk_req_demand_employee FOREIGN KEY (target_employee_id) REFERENCES fams_employee(id),
    CONSTRAINT fk_req_demand_responsible FOREIGN KEY (target_department_employee_id) REFERENCES fams_employee(id),
    CONSTRAINT fk_req_demand_location FOREIGN KEY (target_location_id) REFERENCES fams_location(id),
    CONSTRAINT ck_req_demand_versions CHECK (
        version >= 0 AND category_version_snapshot >= 0 AND organization_version_snapshot >= 0
        AND employee_version_snapshot >= 0 AND location_version_snapshot >= 0 AND line_no > 0),
    CONSTRAINT ck_req_demand_target CHECK (
        (custody_type = 'PERSONAL' AND target_employee_id IS NOT NULL
            AND target_department_employee_id IS NULL)
        OR (custody_type = 'SHARED' AND target_employee_id IS NULL
            AND target_department_employee_id IS NOT NULL)),
    CONSTRAINT ck_req_demand_quantities CHECK (
        requested_quantity > 0 AND pending_allocation_quantity >= 0 AND pending_handover_quantity >= 0
        AND completed_quantity >= 0 AND unfulfillable_quantity >= 0 AND cancelled_quantity >= 0
        AND requested_quantity = pending_allocation_quantity + pending_handover_quantity
            + completed_quantity + unfulfillable_quantity + cancelled_quantity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE fams_requisition ADD CONSTRAINT fk_requisition_source_demand
    FOREIGN KEY (source_request_id, source_demand_id) REFERENCES fams_requisition_demand(request_id, id);

CREATE TABLE fams_requisition_decision (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    request_version BIGINT NOT NULL,
    decision_result VARCHAR(20) NOT NULL,
    approved_scope_json JSON NOT NULL,
    comment_text VARCHAR(1000) NULL,
    decided_by_account_id BIGINT NOT NULL,
    decided_at DATETIME(3) NOT NULL,
    audit_event_id BIGINT NOT NULL,
    UNIQUE KEY uk_req_decision_version (request_id, request_version),
    CONSTRAINT fk_req_decision_request FOREIGN KEY (request_id) REFERENCES fams_requisition(id),
    CONSTRAINT fk_req_decision_actor FOREIGN KEY (decided_by_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT fk_req_decision_audit FOREIGN KEY (audit_event_id) REFERENCES fams_audit_event(id),
    CONSTRAINT ck_req_decision_result CHECK (decision_result IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_req_decision_version CHECK (request_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_requisition_ownership (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    processor_account_id BIGINT NOT NULL,
    ownership_version BIGINT NOT NULL DEFAULT 0,
    acquired_at DATETIME(3) NOT NULL,
    archived_at DATETIME(3) NULL,
    archive_reason VARCHAR(500) NULL,
    active_guard TINYINT GENERATED ALWAYS AS (CASE WHEN archived_at IS NULL THEN 1 ELSE NULL END) STORED,
    UNIQUE KEY uk_req_active_owner (request_id, active_guard),
    UNIQUE KEY uk_req_owner_identity (request_id, id),
    CONSTRAINT fk_req_owner_request FOREIGN KEY (request_id) REFERENCES fams_requisition(id),
    CONSTRAINT fk_req_owner_actor FOREIGN KEY (processor_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT ck_req_owner_version CHECK (ownership_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_requisition_allocation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    demand_id BIGINT NOT NULL,
    ownership_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    asset_version_snapshot BIGINT NOT NULL,
    asset_snapshot_json JSON NOT NULL,
    business_lock_id BIGINT NOT NULL,
    allocation_status VARCHAR(40) NOT NULL,
    predecessor_allocation_id BIGINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    allocated_at DATETIME(3) NOT NULL,
    delivered_at DATETIME(3) NULL,
    received_at DATETIME(3) NULL,
    cancelled_at DATETIME(3) NULL,
    cancellation_reason VARCHAR(500) NULL,
    audit_event_id BIGINT NOT NULL,
    active_guard TINYINT GENERATED ALWAYS AS
        (CASE WHEN allocation_status NOT IN ('RECEIVED', 'CANCELLED') THEN 1 ELSE NULL END) STORED,
    UNIQUE KEY uk_req_allocation_asset (asset_id, active_guard),
    UNIQUE KEY uk_req_allocation_lock (business_lock_id),
    UNIQUE KEY uk_req_allocation_identity (request_id, demand_id, id),
    KEY idx_req_allocation_demand (request_id, demand_id, allocation_status, id),
    CONSTRAINT fk_req_alloc_demand FOREIGN KEY (request_id, demand_id)
        REFERENCES fams_requisition_demand(request_id, id),
    CONSTRAINT fk_req_alloc_owner FOREIGN KEY (request_id, ownership_id)
        REFERENCES fams_requisition_ownership(request_id, id),
    CONSTRAINT fk_req_alloc_asset FOREIGN KEY (asset_id) REFERENCES fams_asset(id),
    CONSTRAINT fk_req_alloc_lock FOREIGN KEY (business_lock_id) REFERENCES fams_asset_business_lock(id),
    CONSTRAINT fk_req_alloc_predecessor FOREIGN KEY (request_id, demand_id, predecessor_allocation_id)
        REFERENCES fams_requisition_allocation(request_id, demand_id, id),
    CONSTRAINT fk_req_alloc_audit FOREIGN KEY (audit_event_id) REFERENCES fams_audit_event(id),
    CONSTRAINT ck_req_alloc_versions CHECK (version >= 0 AND asset_version_snapshot >= 0),
    CONSTRAINT ck_req_alloc_status CHECK (allocation_status IN (
        'WAITING_DELIVERY', 'DELIVERED_WAITING_RECEIPT', 'RECEIVED', 'RECOVERY_PENDING_CANCEL', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_requisition_unfulfillable (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    demand_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    reason_code VARCHAR(40) NOT NULL,
    reason_description VARCHAR(1000) NULL,
    requirement_snapshot_json JSON NOT NULL,
    inventory_query_summary_json JSON NOT NULL,
    recorded_by_account_id BIGINT NOT NULL,
    recorded_at DATETIME(3) NOT NULL,
    audit_event_id BIGINT NOT NULL,
    KEY idx_req_unfulfillable_demand (request_id, demand_id, id),
    CONSTRAINT fk_req_unfulfillable_demand FOREIGN KEY (request_id, demand_id)
        REFERENCES fams_requisition_demand(request_id, id),
    CONSTRAINT fk_req_unfulfillable_actor FOREIGN KEY (recorded_by_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT fk_req_unfulfillable_audit FOREIGN KEY (audit_event_id) REFERENCES fams_audit_event(id),
    CONSTRAINT ck_req_unfulfillable_quantity CHECK (quantity > 0),
    CONSTRAINT ck_req_unfulfillable_reason CHECK (reason_code IN (
        'STOCK_SHORTAGE', 'SPECIFICATION_MISMATCH', 'TEMPORARILY_UNAVAILABLE', 'OTHER')),
    CONSTRAINT ck_req_unfulfillable_other CHECK (reason_code <> 'OTHER'
        OR (reason_description IS NOT NULL AND CHAR_LENGTH(TRIM(reason_description)) > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_requisition_followup (
    source_request_id BIGINT NOT NULL,
    source_demand_id BIGINT NOT NULL,
    latest_request_id BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (source_demand_id),
    CONSTRAINT fk_req_followup_source FOREIGN KEY (source_request_id, source_demand_id)
        REFERENCES fams_requisition_demand(request_id, id),
    CONSTRAINT fk_req_followup_latest FOREIGN KEY (source_request_id, source_demand_id, latest_request_id)
        REFERENCES fams_requisition(source_request_id, source_demand_id, id),
    CONSTRAINT ck_req_followup_version CHECK (version >= 0),
    CONSTRAINT ck_req_followup_distinct CHECK (source_request_id <> latest_request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
