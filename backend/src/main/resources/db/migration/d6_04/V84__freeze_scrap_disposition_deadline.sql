-- Existing rows and older writers retain the date-only interpretation.
ALTER TABLE fams_scrap_disposition
    ADD COLUMN due_at DATETIME(3) NULL,
    ADD COLUMN due_timezone VARCHAR(64) NULL,
    ADD CONSTRAINT ck_scrap_disposition_frozen_due CHECK (
        (due_at IS NULL AND due_timezone IS NULL)
        OR (due_at IS NOT NULL AND due_timezone IS NOT NULL AND due_date IS NOT NULL)
    );
