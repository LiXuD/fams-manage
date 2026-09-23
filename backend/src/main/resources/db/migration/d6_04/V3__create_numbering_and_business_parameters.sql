CREATE TABLE fams_numbering_rule (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    code_normalized VARCHAR(64) NOT NULL,
    target VARCHAR(32) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    fixed_prefix VARCHAR(32) NULL,
    date_part VARCHAR(16) NOT NULL,
    sequence_length INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    active_target VARCHAR(32) GENERATED ALWAYS AS (
        CASE WHEN status = 'ACTIVE' THEN target ELSE NULL END
    ) STORED,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fams_numbering_rule_code (code_normalized),
    UNIQUE KEY uk_fams_numbering_rule_active_target (active_target),
    CONSTRAINT ck_fams_numbering_rule_target CHECK (target IN ('ASSET', 'INBOUND_RECEIPT')),
    CONSTRAINT ck_fams_numbering_rule_mode CHECK (mode IN ('ENTERPRISE', 'CATEGORY_PREFIX')),
    CONSTRAINT ck_fams_numbering_rule_date_part CHECK (date_part IN ('NONE', 'YYYY', 'YYYYMM', 'YYYYMMDD')),
    CONSTRAINT ck_fams_numbering_rule_sequence_length CHECK (sequence_length BETWEEN 1 AND 16),
    CONSTRAINT ck_fams_numbering_rule_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_fams_numbering_rule_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_number_sequence (
    namespace_key VARCHAR(160) NOT NULL,
    last_allocated_value BIGINT NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (namespace_key),
    CONSTRAINT ck_fams_number_sequence_value CHECK (last_allocated_value >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_number_allocation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    namespace_key VARCHAR(160) NOT NULL,
    sequence_value BIGINT NOT NULL,
    rendered_number VARCHAR(128) NOT NULL,
    state VARCHAR(16) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fams_number_allocation_sequence (namespace_key, sequence_value),
    UNIQUE KEY uk_fams_number_allocation_rendered (rendered_number),
    CONSTRAINT ck_fams_number_allocation_state CHECK (state IN ('RESERVED', 'USED', 'VOID')),
    CONSTRAINT ck_fams_number_allocation_sequence CHECK (sequence_value > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_business_parameter_state (
    singleton_id TINYINT NOT NULL,
    current_version BIGINT NOT NULL,
    current_version_id BIGINT NULL,
    PRIMARY KEY (singleton_id),
    CONSTRAINT ck_fams_business_parameter_state_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_fams_business_parameter_state_version CHECK (current_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO fams_business_parameter_state
    (singleton_id, current_version, current_version_id) VALUES (1, 0, NULL);

CREATE TABLE fams_business_parameter_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    active_slot TINYINT GENERATED ALWAYS AS (
        CASE WHEN status = 'ACTIVE' THEN 1 ELSE NULL END
    ) STORED,
    effective_at DATETIME(3) NOT NULL,
    actor_account_id BIGINT NOT NULL,
    actor_username VARCHAR(128) NOT NULL,
    snapshot_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fams_business_parameter_version (version),
    UNIQUE KEY uk_fams_business_parameter_active (active_slot),
    CONSTRAINT ck_fams_business_parameter_status CHECK (status IN ('ACTIVE', 'SUPERSEDED')),
    CONSTRAINT ck_fams_business_parameter_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE fams_business_parameter_state
    ADD CONSTRAINT fk_fams_business_parameter_current
        FOREIGN KEY (current_version_id) REFERENCES fams_business_parameter_version(id);
