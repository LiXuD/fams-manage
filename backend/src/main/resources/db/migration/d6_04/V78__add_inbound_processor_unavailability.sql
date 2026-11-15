ALTER TABLE fams_inbound_processing_ownership
    ADD COLUMN unavailable_at DATETIME(3) NULL,
    ADD COLUMN unavailable_reason VARCHAR(1000) NULL,
    ADD COLUMN unavailable_ownership_version BIGINT NULL,
    ADD CONSTRAINT ck_inbound_unavailability_fields CHECK (
        (unavailable_at IS NULL AND unavailable_reason IS NULL AND unavailable_ownership_version IS NULL)
        OR (unavailable_at IS NOT NULL AND unavailable_reason IS NOT NULL
            AND unavailable_ownership_version IS NOT NULL AND unavailable_ownership_version >= 0)
    );
