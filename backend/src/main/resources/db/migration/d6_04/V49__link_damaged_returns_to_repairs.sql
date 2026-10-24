-- Expand only; preserve completed return and borrowing facts. Replace old writers together.
ALTER TABLE fams_return_followup
    ADD COLUMN linked_repair_id BIGINT NULL,
    ADD COLUMN last_repair_id BIGINT NULL,
    ADD COLUMN repair_status VARCHAR(32) NULL,
    ADD CONSTRAINT fk_return_followup_repair FOREIGN KEY (linked_repair_id) REFERENCES fams_repair_request(id),
    ADD CONSTRAINT fk_return_followup_last_repair FOREIGN KEY (last_repair_id) REFERENCES fams_repair_request(id);

CREATE TABLE fams_repair_return_origin (
    repair_id BIGINT PRIMARY KEY,
    followup_id BIGINT NOT NULL,
    source_context_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    INDEX ix_repair_return_origin (followup_id,repair_id),
    CONSTRAINT fk_repair_return_origin_repair FOREIGN KEY (repair_id) REFERENCES fams_repair_request(id),
    CONSTRAINT fk_repair_return_origin_followup FOREIGN KEY (followup_id) REFERENCES fams_return_followup(id)
);
