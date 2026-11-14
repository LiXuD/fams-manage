CREATE TABLE fams_configuration_anomaly (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    anomaly_type VARCHAR(40) NOT NULL,
    dedup_key VARCHAR(100) NOT NULL,
    first_seen_at DATETIME(3) NOT NULL,
    last_seen_at DATETIME(3) NOT NULL,
    resolved_at DATETIME(3) NULL,
    active_slot TINYINT GENERATED ALWAYS AS (IF(resolved_at IS NULL, 1, NULL)) STORED,
    UNIQUE KEY uk_fams_configuration_anomaly_open (dedup_key, active_slot),
    CONSTRAINT ck_fams_configuration_anomaly_type CHECK (
        anomaly_type IN ('NO_ASSET_ADMIN', 'NO_WAREHOUSE_OPERATOR', 'SMS_CHANNEL_MISSING', 'WECHAT_TEMPLATE_MISSING')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_configuration_anomaly_todo (
    anomaly_id BIGINT NOT NULL,
    todo_id BIGINT NOT NULL,
    todo_type VARCHAR(40) NOT NULL,
    parameter_version_id BIGINT NOT NULL,
    deadline DATETIME(3) NOT NULL,
    PRIMARY KEY (anomaly_id, todo_id),
    CONSTRAINT fk_fams_anomaly_todo_anomaly FOREIGN KEY (anomaly_id) REFERENCES fams_configuration_anomaly(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
