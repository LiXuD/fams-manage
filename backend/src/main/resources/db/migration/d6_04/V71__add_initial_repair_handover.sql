ALTER TABLE fams_repair_request
    ADD COLUMN initial_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN initial_sheet_row INT NULL,
    ADD CONSTRAINT fk_initial_repair_batch FOREIGN KEY (initial_batch_id) REFERENCES fams_asset_import_batch(id),
    ADD CONSTRAINT uk_initial_repair_row UNIQUE (initial_batch_id, initial_sheet_row),
    ADD CONSTRAINT ck_initial_repair_source CHECK (
        (initial_batch_id IS NULL AND initial_sheet_row IS NULL)
        OR (initial_batch_id IS NOT NULL AND initial_sheet_row IS NOT NULL AND initial_sheet_row >= 2)
    );

ALTER TABLE fams_repair_factory_round
    ADD COLUMN initial_source BOOLEAN NOT NULL DEFAULT FALSE,
    DROP CHECK ck_factory_round_status,
    ADD CONSTRAINT ck_factory_round_status CHECK (
        round_status IN ('PENDING_DISPATCH','DISPATCHED','RECEIVED','CANCELLED')
        OR (initial_source AND round_status='INITIAL_RETIRED')
    ),
    DROP CHECK ck_factory_round_dispatch,
    ADD CONSTRAINT ck_factory_round_dispatch CHECK (
        (round_status IN ('PENDING_DISPATCH','CANCELLED')
            AND dispatched_at IS NULL AND dispatched_by IS NULL AND dispatch_evidence IS NULL)
        OR (round_status IN ('DISPATCHED','RECEIVED','INITIAL_RETIRED')
            AND dispatched_at IS NOT NULL AND dispatched_by IS NOT NULL AND dispatch_evidence IS NOT NULL
            AND (initial_source OR dispatched_at>=created_at))
    ),
    DROP CHECK ck_factory_round_cancel,
    ADD CONSTRAINT ck_factory_round_cancel CHECK (
        (round_status IN ('CANCELLED','INITIAL_RETIRED') AND cancel_reason IS NOT NULL)
        OR (round_status NOT IN ('CANCELLED','INITIAL_RETIRED') AND cancel_reason IS NULL)
    );
