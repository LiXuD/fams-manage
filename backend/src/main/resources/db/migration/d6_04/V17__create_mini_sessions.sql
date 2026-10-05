CREATE TABLE fams_mini_binding (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    identity_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    revoked_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN revoked_at IS NULL THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mini_binding_account (account_id, active_slot),
    UNIQUE KEY uk_mini_binding_identity (identity_hash, active_slot),
    CONSTRAINT fk_mini_binding_account FOREIGN KEY (account_id) REFERENCES fams_system_account(id),
    CONSTRAINT ck_mini_binding_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_mini_session (
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    binding_id BIGINT NOT NULL,
    binding_version BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    auth_epoch BIGINT NOT NULL,
    authorization_version BIGINT NOT NULL,
    idle_expires_at DATETIME(3) NOT NULL,
    absolute_expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    revoked_at DATETIME(3) NULL,
    PRIMARY KEY (token_hash),
    KEY idx_mini_session_expiry (absolute_expires_at),
    CONSTRAINT fk_mini_session_binding FOREIGN KEY (binding_id) REFERENCES fams_mini_binding(id),
    CONSTRAINT fk_mini_session_account FOREIGN KEY (account_id) REFERENCES fams_system_account(id),
    CONSTRAINT ck_mini_session_versions CHECK (binding_version >= 0 AND auth_epoch >= 0 AND authorization_version >= 0),
    CONSTRAINT ck_mini_session_time CHECK (idle_expires_at <= absolute_expires_at AND created_at < absolute_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
