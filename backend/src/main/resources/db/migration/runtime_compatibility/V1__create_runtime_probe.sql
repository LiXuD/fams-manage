CREATE TABLE d6_02b_runtime_probe (
    id BIGINT NOT NULL AUTO_INCREMENT,
    probe_key VARCHAR(128) NOT NULL,
    probe_value VARCHAR(512) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_d6_02b_runtime_probe PRIMARY KEY (id),
    CONSTRAINT uk_d6_02b_runtime_probe_key UNIQUE (probe_key)
) ENGINE=InnoDB;
