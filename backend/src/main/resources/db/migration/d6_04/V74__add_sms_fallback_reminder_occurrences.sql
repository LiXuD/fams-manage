-- Expand the reminder vocabulary without rewriting existing reminder or delivery history.
ALTER TABLE fams_business_reminder
    DROP CHECK ck_business_reminder_stage,
    ADD CONSTRAINT ck_business_reminder_stage CHECK (stage IN (
        'ADMIN_DECISION','WAREHOUSE_CLAIM','FIRST_EXECUTION','HANDOVER_RECEIPT',
        'INVENTORY_COMPLETION','FACTORY_RETURN','LOSS_REVIEW','SCRAP_DISPOSITION','LOAN_RETURN')),
    DROP CHECK ck_business_reminder_type,
    ADD CONSTRAINT ck_business_reminder_type CHECK (reminder_type IN (
        'DUE_IN_3_DAYS','DUE_IN_1_DAY','DUE_IN_24_HOURS','DUE_TODAY','OVERDUE',
        'OVERDUE_EVERY_7_DAYS','UNRESOLVED_AFTER_1_WORKDAY','DUTY_UNREAD_ESCALATION',
        'OVERDUE_UNRESOLVED_WORKDAY','SMS_FALLBACK'));
