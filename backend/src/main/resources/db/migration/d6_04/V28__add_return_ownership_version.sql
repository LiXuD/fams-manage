ALTER TABLE fams_asset_return
    ADD COLUMN ownership_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_return_ownership_version CHECK (ownership_version >= 0);
