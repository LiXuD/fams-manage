CREATE TABLE fams_asset_business_lock (
    id BIGINT NOT NULL AUTO_INCREMENT,
    asset_id BIGINT NOT NULL,
    business_type VARCHAR(40) NOT NULL,
    business_id BIGINT NOT NULL,
    business_item_id BIGINT NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    acquired_at DATETIME(3) NOT NULL,
    released_at DATETIME(3) NULL,
    release_reason VARCHAR(500) NULL,
    active_guard TINYINT GENERATED ALWAYS AS
        (CASE WHEN released_at IS NULL THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_asset_active_lock (asset_id, active_guard),
    CONSTRAINT fk_asset_business_lock_asset FOREIGN KEY (asset_id) REFERENCES fams_asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_asset_tag (
    asset_id BIGINT NOT NULL,
    token_ciphertext VARBINARY(512) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    tag_version BIGINT NOT NULL DEFAULT 0,
    issued_at DATETIME(3) NOT NULL,
    rotated_at DATETIME(3) NULL,
    PRIMARY KEY (asset_id),
    UNIQUE KEY uk_asset_tag_hash (token_hash),
    CONSTRAINT fk_asset_tag_asset FOREIGN KEY (asset_id) REFERENCES fams_asset(id),
    CONSTRAINT ck_asset_tag_version CHECK (tag_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_asset_tag_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    asset_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    valid_from DATETIME(3) NOT NULL,
    invalidated_at DATETIME(3) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_asset_tag_history_hash (token_hash),
    KEY idx_tag_history_asset_time (asset_id, invalidated_at DESC, id DESC),
    CONSTRAINT fk_asset_tag_history_asset FOREIGN KEY (asset_id) REFERENCES fams_asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_asset_timeline_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    asset_id BIGINT NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    business_type VARCHAR(40) NOT NULL,
    business_id BIGINT NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    summary_json JSON NOT NULL,
    actor_account_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_asset_timeline (asset_id, occurred_at DESC, id DESC),
    CONSTRAINT fk_asset_timeline_asset FOREIGN KEY (asset_id) REFERENCES fams_asset(id),
    CONSTRAINT fk_asset_timeline_actor FOREIGN KEY (actor_account_id) REFERENCES fams_system_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_asset_saved_filter (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_account_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    name_normalized VARCHAR(64) NOT NULL,
    criteria_json JSON NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_saved_filter_owner_name (owner_account_id, name_normalized),
    CONSTRAINT fk_saved_filter_owner FOREIGN KEY (owner_account_id) REFERENCES fams_system_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_asset_tag_print_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    requester_account_id BIGINT NOT NULL,
    job_type VARCHAR(10) NOT NULL,
    job_status VARCHAR(20) NOT NULL,
    template_version VARCHAR(32) NOT NULL,
    error_code VARCHAR(80) NULL,
    created_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_tag_print_requester FOREIGN KEY (requester_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT ck_tag_print_type CHECK (job_type IN ('SINGLE', 'BATCH')),
    CONSTRAINT ck_tag_print_status CHECK (job_status IN ('QUEUED', 'COMPLETED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_asset_tag_print_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    print_job_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    asset_version BIGINT NOT NULL,
    tag_version BIGINT NOT NULL,
    asset_code_snapshot VARCHAR(64) NOT NULL,
    asset_name_snapshot VARCHAR(256) NOT NULL,
    token_ciphertext_snapshot VARBINARY(512) NOT NULL,
    item_status VARCHAR(20) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_print_job_asset (print_job_id, asset_id),
    CONSTRAINT fk_print_item_job FOREIGN KEY (print_job_id) REFERENCES fams_asset_tag_print_job(id),
    CONSTRAINT fk_print_item_asset FOREIGN KEY (asset_id) REFERENCES fams_asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
