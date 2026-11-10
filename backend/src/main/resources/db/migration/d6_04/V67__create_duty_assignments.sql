-- One immutable duty row per role sequence; a role keeps exactly one ACTIVE row.
-- The duty person only receives notification escalation and never gains approval rights.
CREATE TABLE fams_duty_assignment (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    role_code VARCHAR(24) NOT NULL,
    sequence_no BIGINT NOT NULL,
    primary_account_id BIGINT NOT NULL,
    backup_account_id BIGINT NULL,
    duty_status VARCHAR(16) NOT NULL,
    active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN duty_status='ACTIVE' THEN 1 ELSE NULL END) STORED,
    effective_at DATETIME(3) NOT NULL,
    superseded_at DATETIME(3) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_duty_assignment_sequence (role_code,sequence_no),
    UNIQUE KEY uk_duty_assignment_active (role_code,active_slot),
    KEY idx_duty_assignment_account (primary_account_id,backup_account_id,id),
    CONSTRAINT ck_duty_assignment_role CHECK (role_code IN ('ASSET_ADMIN','WAREHOUSE_OPERATOR')),
    CONSTRAINT ck_duty_assignment_status CHECK (duty_status IN ('ACTIVE','SUPERSEDED')),
    CONSTRAINT ck_duty_assignment_sequence CHECK (sequence_no>=1),
    CONSTRAINT ck_duty_assignment_distinct CHECK (
        backup_account_id IS NULL OR backup_account_id<>primary_account_id),
    CONSTRAINT ck_duty_assignment_supersede CHECK (
        (duty_status='ACTIVE' AND superseded_at IS NULL)
        OR (duty_status='SUPERSEDED' AND superseded_at IS NOT NULL)),
    CONSTRAINT fk_duty_assignment_primary FOREIGN KEY (primary_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT fk_duty_assignment_backup FOREIGN KEY (backup_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT fk_duty_assignment_creator FOREIGN KEY (created_by) REFERENCES fams_system_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
