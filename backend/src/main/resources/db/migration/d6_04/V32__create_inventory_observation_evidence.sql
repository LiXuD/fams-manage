-- Extend immutable V31 snapshots; never rewrite published task/asset facts.
CREATE TABLE fams_inventory_clock_calibration (
 id CHAR(36) PRIMARY KEY,
 actor_account_id BIGINT NOT NULL,
 binding_id BIGINT NULL,
 device_id CHAR(36) NOT NULL,
 client_time DATETIME(3) NOT NULL,
 server_time DATETIME(3) NOT NULL,
 CONSTRAINT fk_inventory_clock_actor FOREIGN KEY(actor_account_id) REFERENCES fams_system_account(id),
 INDEX ix_inventory_clock_actor(actor_account_id,server_time)
);
CREATE TABLE fams_inventory_observation (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 task_id BIGINT NOT NULL,
 client_event_id CHAR(36) NOT NULL,
 actor_account_id BIGINT NOT NULL,
 binding_id BIGINT NULL,
 device_id CHAR(36) NOT NULL,
 item_id BIGINT NULL,
 assignment_version BIGINT NOT NULL,
 recheck_generation BIGINT NOT NULL,
 snapshot_asset_version BIGINT NULL,
 source VARCHAR(12) NOT NULL,
 asset_code VARCHAR(128) NULL,
 raw_observed_at DATETIME(3) NOT NULL,
 first_persisted_at DATETIME(3) NOT NULL,
 client_expires_at DATETIME(3) NOT NULL,
 calibrated_observed_at DATETIME(3) NULL,
 calibrated_persisted_at DATETIME(3) NULL,
 calibration_id CHAR(36) NULL,
 received_at DATETIME(3) NOT NULL,
 facts_json JSON NOT NULL,
 payload_sha256 CHAR(64) NOT NULL,
 fact_sha256 CHAR(64) NOT NULL,
 outcome VARCHAR(32) NOT NULL,
 receipt_json JSON NOT NULL,
 CONSTRAINT uk_inventory_client_event UNIQUE(task_id,client_event_id),
 CONSTRAINT fk_inventory_observation_task FOREIGN KEY(task_id) REFERENCES fams_inventory_task(id),
 CONSTRAINT fk_inventory_observation_item FOREIGN KEY(item_id) REFERENCES fams_inventory_item(id),
 CONSTRAINT fk_inventory_observation_actor FOREIGN KEY(actor_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT fk_inventory_observation_clock FOREIGN KEY(calibration_id) REFERENCES fams_inventory_clock_calibration(id),
 CONSTRAINT ck_inventory_observation_source CHECK(source IN ('SCAN','CODE','MANUAL')),
 CONSTRAINT ck_inventory_observation_versions CHECK(assignment_version>=0 AND recheck_generation>=0),
 INDEX ix_inventory_observation_item(task_id,item_id,recheck_generation,id)
);
-- One count candidate per authorized verification round; all raw events remain append-only.
CREATE TABLE fams_inventory_round_result (
 item_id BIGINT NOT NULL,
 recheck_generation BIGINT NOT NULL,
 observation_id BIGINT NOT NULL,
 fact_sha256 CHAR(64) NOT NULL,
 PRIMARY KEY(item_id,recheck_generation),
 CONSTRAINT fk_inventory_round_item FOREIGN KEY(item_id) REFERENCES fams_inventory_item(id),
 CONSTRAINT fk_inventory_round_observation FOREIGN KEY(observation_id) REFERENCES fams_inventory_observation(id)
);
CREATE TABLE fams_inventory_upload_scope (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 task_id BIGINT NOT NULL,
 client_event_id CHAR(36) NOT NULL,
 actor_account_id BIGINT NOT NULL,
 binding_id BIGINT NULL,
 device_id CHAR(36) NOT NULL,
 created_at DATETIME(3) NOT NULL,
 discarded_at DATETIME(3) NULL,
 submitted_at DATETIME(3) NULL,
 CONSTRAINT uk_inventory_upload_scope UNIQUE(task_id,client_event_id),
 INDEX ix_inventory_upload_retention(discarded_at,submitted_at,created_at,id),
 CONSTRAINT fk_inventory_upload_task FOREIGN KEY(task_id) REFERENCES fams_inventory_task(id),
 CONSTRAINT fk_inventory_upload_actor FOREIGN KEY(actor_account_id) REFERENCES fams_system_account(id)
);
CREATE TABLE fams_inventory_photo_receipt (
 scope_id BIGINT NOT NULL,
 client_photo_id CHAR(36) NOT NULL,
 object_id CHAR(36) NOT NULL,
 content_sha256 CHAR(64) NOT NULL,
 original_filename VARCHAR(255) NOT NULL,
 PRIMARY KEY(scope_id,client_photo_id),
 CONSTRAINT fk_inventory_photo_scope FOREIGN KEY(scope_id) REFERENCES fams_inventory_upload_scope(id),
 CONSTRAINT fk_inventory_photo_object FOREIGN KEY(object_id) REFERENCES fams_attachment(object_id)
);
