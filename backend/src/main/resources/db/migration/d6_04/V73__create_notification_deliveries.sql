CREATE TABLE fams_notification_delivery (
    reminder_id BIGINT NOT NULL,
    recipient_account_id BIGINT NOT NULL,
    channel VARCHAR(16) NOT NULL,
    template_code VARCHAR(80) NOT NULL,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_at DATETIME(3) NOT NULL,
    accepted_at DATETIME(3) NULL,
    PRIMARY KEY (reminder_id, recipient_account_id, channel),
    UNIQUE KEY uk_fams_notification_delivery_job (job_id),
    CONSTRAINT fk_fams_notification_delivery_reminder FOREIGN KEY (reminder_id)
        REFERENCES fams_business_reminder(id),
    CONSTRAINT fk_fams_notification_delivery_recipient FOREIGN KEY (recipient_account_id)
        REFERENCES fams_system_account(id),
    CONSTRAINT fk_fams_notification_delivery_job FOREIGN KEY (job_id)
        REFERENCES fams_durable_job(id),
    CONSTRAINT ck_fams_notification_delivery_channel CHECK (channel = 'SMS')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
