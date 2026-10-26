-- Forward-only. Coordinate replacement of old writers; no old facts or receipts are rewritten.
ALTER TABLE fams_repair_factory_evidence
 DROP CHECK ck_factory_evidence_purpose,
 ADD CONSTRAINT ck_factory_evidence_purpose CHECK(purpose IN ('FAULT','FACTORY_ACK','RECEIPT','QUOTE','COST_RECEIPT','PARTS_CHANGE'));

-- Append-only observations of the same physical asset, never a replacement asset card.
CREATE TABLE fams_repair_factory_parts_change (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 repair_id BIGINT NOT NULL,
 round_id BIGINT NOT NULL,
 asset_id BIGINT NOT NULL,
 part_name VARCHAR(100) NOT NULL,
 before_info VARCHAR(2000) NOT NULL,
 after_info VARCHAR(2000) NOT NULL,
 observed_at DATETIME(3) NOT NULL,
 evidence_ids_json LONGTEXT NOT NULL,
 snapshot_json LONGTEXT NOT NULL,
 recorded_by BIGINT NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 INDEX ix_factory_parts_round(round_id,id),
 INDEX ix_factory_parts_asset(asset_id,part_name,id),
 CONSTRAINT fk_factory_parts_repair FOREIGN KEY(repair_id) REFERENCES fams_repair_request(id),
 CONSTRAINT fk_factory_parts_round FOREIGN KEY(round_id) REFERENCES fams_repair_factory_round(id),
 CONSTRAINT fk_factory_parts_asset FOREIGN KEY(asset_id) REFERENCES fams_asset(id),
 CONSTRAINT fk_factory_parts_recorder FOREIGN KEY(recorded_by) REFERENCES fams_system_account(id),
 CONSTRAINT ck_factory_parts_info CHECK(CHAR_LENGTH(TRIM(part_name))>0 AND CHAR_LENGTH(TRIM(before_info))>0 AND CHAR_LENGTH(TRIM(after_info))>0 AND BINARY before_info<>BINARY after_info),
 CONSTRAINT ck_factory_parts_time CHECK(observed_at<=recorded_at),
 CONSTRAINT ck_factory_parts_json CHECK(JSON_VALID(snapshot_json) AND JSON_VALID(evidence_ids_json) AND JSON_TYPE(evidence_ids_json)='ARRAY' AND JSON_LENGTH(evidence_ids_json) BETWEEN 1 AND 14)
);
