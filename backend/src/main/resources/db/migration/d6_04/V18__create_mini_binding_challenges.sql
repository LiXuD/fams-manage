CREATE TABLE fams_mini_binding_limit (
    subject_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    quota_day DATE NOT NULL,
    requests INT NOT NULL DEFAULT 0,
    last_requested_at DATETIME(3) NULL,
    failures INT NOT NULL DEFAULT 0,
    locked_until DATETIME(3) NULL,
    CONSTRAINT ck_mini_limit_counts CHECK (requests >= 0 AND failures >= 0)
) ENGINE=InnoDB;

CREATE TABLE fams_mini_phone_receipt (
    actor_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_version BIGINT NOT NULL,
    PRIMARY KEY (actor_id, idempotency_key),
    CONSTRAINT fk_mini_phone_actor FOREIGN KEY (actor_id) REFERENCES fams_system_account(id),
    CONSTRAINT ck_mini_phone_version CHECK (result_version >= 0)
) ENGINE=InnoDB;

CREATE TABLE fams_mini_binding_challenge (
    challenge_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    employee_version BIGINT NOT NULL,
    identity_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    device_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    otp_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(10) NOT NULL,
    state VARCHAR(16) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    completed_binding_id BIGINT NULL,
    idempotency_key VARCHAR(128) NULL,
    KEY idx_mini_challenge_account (account_id, created_at),
    CONSTRAINT fk_mini_challenge_account FOREIGN KEY (account_id) REFERENCES fams_system_account(id),
    CONSTRAINT fk_mini_challenge_binding FOREIGN KEY (completed_binding_id) REFERENCES fams_mini_binding(id),
    CONSTRAINT ck_mini_challenge_action CHECK (action IN ('BIND', 'REBIND')),
    CONSTRAINT ck_mini_challenge_state CHECK (state IN ('PENDING', 'SENT', 'FAILED', 'COMPLETED')),
    CONSTRAINT ck_mini_challenge_version CHECK (employee_version >= 0),
    CONSTRAINT ck_mini_challenge_time CHECK (created_at < expires_at)
) ENGINE=InnoDB;

CREATE TABLE fams_mini_unbind_receipt (
    actor_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (actor_id, idempotency_key),
    CONSTRAINT fk_mini_unbind_actor FOREIGN KEY (actor_id) REFERENCES fams_system_account(id)
) ENGINE=InnoDB;
