-- One immutable in-app notice per object, stage, frozen deadline, tier, occurrence date and recipient.
CREATE TABLE fams_business_reminder (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    business_type VARCHAR(32) NOT NULL,
    business_id BIGINT NOT NULL,
    stage VARCHAR(32) NOT NULL,
    deadline_at DATETIME(3) NOT NULL,
    notice_date DATE NOT NULL,
    reminder_business_date DATE NOT NULL,
    reminder_type VARCHAR(32) NOT NULL,
    recipient_pool_type VARCHAR(24) NOT NULL,
    recipient_account_id BIGINT NULL,
    recipient_slot BIGINT GENERATED ALWAYS AS (COALESCE(recipient_account_id,0)) STORED,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_business_reminder_notice
        (business_type,business_id,stage,deadline_at,reminder_type,notice_date,recipient_slot),
    KEY idx_business_reminder_object (business_type,business_id,stage,id),
    KEY idx_business_reminder_recipient (recipient_pool_type,recipient_slot,reminder_business_date,id),
    CONSTRAINT ck_business_reminder_stage CHECK (stage IN (
        'ADMIN_DECISION','WAREHOUSE_CLAIM','FIRST_EXECUTION','HANDOVER_RECEIPT',
        'INVENTORY_COMPLETION','FACTORY_RETURN','LOSS_REVIEW','SCRAP_DISPOSITION')),
    CONSTRAINT ck_business_reminder_type CHECK (reminder_type IN (
        'DUE_IN_3_DAYS','DUE_IN_1_DAY','DUE_IN_24_HOURS','DUE_TODAY','OVERDUE',
        'OVERDUE_EVERY_7_DAYS','UNRESOLVED_AFTER_1_WORKDAY')),
    CONSTRAINT ck_business_reminder_pool CHECK (recipient_pool_type IN ('ASSET_ADMIN','WAREHOUSE_OPERATOR','EMPLOYEE')),
    CONSTRAINT ck_business_reminder_notice CHECK (reminder_business_date>=notice_date),
    CONSTRAINT fk_business_reminder_recipient FOREIGN KEY (recipient_account_id) REFERENCES fams_system_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
