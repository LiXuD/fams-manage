CREATE TABLE fams_reminder_followup (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    reminder_id BIGINT NOT NULL,
    business_type VARCHAR(32) NOT NULL,
    business_id BIGINT NOT NULL,
    stage VARCHAR(32) NOT NULL,
    deadline_at DATETIME(3) NOT NULL,
    actor_account_id BIGINT NOT NULL,
    request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    findings VARCHAR(1000) NOT NULL,
    next_followup_at DATETIME(3) NOT NULL,
    recorded_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_reminder_followup_request (actor_account_id,request_key),
    KEY ix_reminder_followup_occurrence (business_type,business_id,stage,deadline_at,id),
    CONSTRAINT fk_reminder_followup_reminder FOREIGN KEY (reminder_id) REFERENCES fams_business_reminder(id),
    CONSTRAINT fk_reminder_followup_actor FOREIGN KEY (actor_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT ck_reminder_followup_time CHECK (next_followup_at > recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
