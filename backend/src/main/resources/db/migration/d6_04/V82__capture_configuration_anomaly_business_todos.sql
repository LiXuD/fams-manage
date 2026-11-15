CREATE TABLE fams_configuration_anomaly_business_todo (
    anomaly_id BIGINT NOT NULL,
    todo_id BIGINT NOT NULL,
    todo_type VARCHAR(40) NOT NULL,
    business_type VARCHAR(40) NOT NULL,
    business_id BIGINT NOT NULL,
    parameter_version_id BIGINT NULL,
    PRIMARY KEY (anomaly_id, todo_id),
    CONSTRAINT fk_fams_anomaly_business_todo FOREIGN KEY (anomaly_id) REFERENCES fams_configuration_anomaly(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
