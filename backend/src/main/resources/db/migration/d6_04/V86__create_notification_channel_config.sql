CREATE TABLE fams_notification_channel_state (
    singleton_id TINYINT NOT NULL,
    current_version BIGINT NOT NULL,
    current_version_id BIGINT NULL,
    PRIMARY KEY (singleton_id),
    CONSTRAINT ck_fams_notification_channel_state_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_fams_notification_channel_state_version CHECK (current_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO fams_notification_channel_state
    (singleton_id, current_version, current_version_id) VALUES (1, 0, NULL);

CREATE TABLE fams_notification_channel_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    active_slot TINYINT GENERATED ALWAYS AS (
        CASE WHEN status = 'ACTIVE' THEN 1 ELSE NULL END
    ) STORED,
    sms_template VARCHAR(80) NOT NULL,
    wechat_template VARCHAR(80) NOT NULL,
    actor_account_id BIGINT NOT NULL,
    actor_username VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fams_notification_channel_version (version),
    UNIQUE KEY uk_fams_notification_channel_active (active_slot),
    CONSTRAINT ck_fams_notification_channel_status CHECK (status IN ('ACTIVE', 'SUPERSEDED')),
    CONSTRAINT ck_fams_notification_channel_version CHECK (version > 0),
    CONSTRAINT ck_fams_notification_channel_sms_template CHECK (
        sms_template = '' OR sms_template REGEXP '^[A-Za-z0-9_-]{1,80}$'),
    CONSTRAINT ck_fams_notification_channel_wechat_template CHECK (
        wechat_template = '' OR wechat_template REGEXP '^[A-Za-z0-9_-]{1,80}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE fams_notification_channel_state
    ADD CONSTRAINT fk_fams_notification_channel_current
        FOREIGN KEY (current_version_id) REFERENCES fams_notification_channel_version(id);
