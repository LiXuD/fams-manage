-- Duty escalation and the persistent-unhandled group notice add two tiers to the same ladder table, so the
-- V65 unique key (object, stage, frozen deadline, tier, occurrence date, recipient slot) still bounds repetition.
ALTER TABLE fams_business_reminder
    DROP CHECK ck_business_reminder_type,
    ADD CONSTRAINT ck_business_reminder_type CHECK (reminder_type IN (
        'DUE_IN_3_DAYS','DUE_IN_1_DAY','DUE_IN_24_HOURS','DUE_TODAY','OVERDUE',
        'OVERDUE_EVERY_7_DAYS','UNRESOLVED_AFTER_1_WORKDAY',
        'DUTY_UNREAD_ESCALATION','OVERDUE_UNRESOLVED_WORKDAY'));
