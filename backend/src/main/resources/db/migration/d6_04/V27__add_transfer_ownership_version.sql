ALTER TABLE fams_transfer
    ADD COLUMN ownership_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_transfer_ownership_version CHECK (ownership_version >= 0);
