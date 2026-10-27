-- Forward-only expansion. Stop old writers before applying; V1 through V54 remain immutable.
ALTER TABLE fams_repair_factory_evidence
 DROP CHECK ck_factory_evidence_purpose,
 ADD CONSTRAINT ck_factory_evidence_purpose CHECK(purpose IN ('FAULT','FACTORY_ACK','RECEIPT','QUOTE','COST_RECEIPT','PARTS_CHANGE','REPLACEMENT'));

-- Each owner keeps its own immutable provenance; repair never writes asset/inbound tables.
CREATE TABLE fams_asset_factory_replacement_origin (
 source_id CHAR(36) NOT NULL PRIMARY KEY,
 asset_id BIGINT NOT NULL UNIQUE,
 original_asset_id BIGINT NOT NULL,
 repair_id BIGINT NOT NULL,
 round_id BIGINT NOT NULL,
 original_code VARCHAR(128) NOT NULL,
 original_serial VARCHAR(128),
 replacement_serial VARCHAR(128) NOT NULL,
 recorded_by BIGINT NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 INDEX ix_asset_replacement_original(original_asset_id,asset_id),
 CONSTRAINT fk_asset_replacement_new FOREIGN KEY(asset_id) REFERENCES fams_asset(id),
 CONSTRAINT fk_asset_replacement_old FOREIGN KEY(original_asset_id) REFERENCES fams_asset(id),
 CONSTRAINT fk_asset_replacement_actor FOREIGN KEY(recorded_by) REFERENCES fams_system_account(id),
 CONSTRAINT ck_asset_replacement_distinct CHECK(asset_id<>original_asset_id),
 CONSTRAINT ck_asset_replacement_serial CHECK(CHAR_LENGTH(TRIM(replacement_serial))>0)
);

CREATE TABLE fams_inbound_factory_replacement_origin (
 receipt_id BIGINT NOT NULL PRIMARY KEY,
 source_id CHAR(36) NOT NULL,
 asset_id BIGINT NOT NULL,
 original_asset_id BIGINT NOT NULL,
 repair_id BIGINT NOT NULL,
 round_id BIGINT NOT NULL,
 recorded_by BIGINT NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 INDEX ix_inbound_replacement_source(source_id,receipt_id),
 INDEX ix_inbound_replacement_asset(asset_id,receipt_id),
 CONSTRAINT fk_inbound_replacement_receipt FOREIGN KEY(receipt_id) REFERENCES fams_inbound_receipt(id),
 CONSTRAINT fk_inbound_replacement_asset FOREIGN KEY(asset_id) REFERENCES fams_asset(id),
 CONSTRAINT fk_inbound_replacement_actor FOREIGN KEY(recorded_by) REFERENCES fams_system_account(id)
);

CREATE TABLE fams_repair_factory_replacement (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 source_id CHAR(36) NOT NULL UNIQUE,
 repair_id BIGINT NOT NULL,
 round_id BIGINT NOT NULL UNIQUE,
 original_asset_id BIGINT NOT NULL,
 replacement_asset_id BIGINT NOT NULL UNIQUE,
 inbound_receipt_id BIGINT NOT NULL UNIQUE,
 original_disposition VARCHAR(32) NOT NULL,
 observed_at DATETIME(3) NOT NULL,
 evidence_ids_json LONGTEXT NOT NULL,
 snapshot_json LONGTEXT NOT NULL,
 recorded_by BIGINT NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 CONSTRAINT fk_repair_replacement_repair FOREIGN KEY(repair_id) REFERENCES fams_repair_request(id),
 CONSTRAINT fk_repair_replacement_round FOREIGN KEY(round_id) REFERENCES fams_repair_factory_round(id),
 CONSTRAINT fk_repair_replacement_actor FOREIGN KEY(recorded_by) REFERENCES fams_system_account(id),
 CONSTRAINT ck_repair_replacement_distinct CHECK(original_asset_id<>replacement_asset_id),
 CONSTRAINT ck_repair_replacement_disposition CHECK(original_disposition IN ('FACTORY_RETAINED','FACTORY_DESTROYED','CONTINUES_REPAIR')),
 CONSTRAINT ck_repair_replacement_time CHECK(observed_at<=recorded_at),
 CONSTRAINT ck_repair_replacement_json CHECK(JSON_VALID(snapshot_json) AND JSON_VALID(evidence_ids_json) AND JSON_TYPE(evidence_ids_json)='ARRAY' AND JSON_LENGTH(evidence_ids_json) BETWEEN 1 AND 14)
);
