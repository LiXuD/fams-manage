-- Canonical JSON text preserves frozen bytes for report and page checksums.
CREATE TABLE fams_inventory_sealed_report (
 task_id BIGINT PRIMARY KEY,
 task_version BIGINT NOT NULL,
 timezone_id VARCHAR(64) NOT NULL,
 expected_count BIGINT NOT NULL,
 accepted_count BIGINT NOT NULL,
 not_observed_count BIGINT NOT NULL,
 captured_count BIGINT NOT NULL,
 actor_account_id BIGINT NOT NULL,
 actor_username VARCHAR(128) NOT NULL,
 seal_reason VARCHAR(1000) NOT NULL,
 missing_sync_reason VARCHAR(2000) NULL,
 sync_digest CHAR(64) NOT NULL,
 report_sha256 CHAR(64) NOT NULL,
 snapshot_json LONGTEXT NOT NULL,
 created_at DATETIME(3) NOT NULL,
 CONSTRAINT fk_inventory_report_task FOREIGN KEY(task_id) REFERENCES fams_inventory_task(id),
 CONSTRAINT fk_inventory_report_actor FOREIGN KEY(actor_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT ck_inventory_report_snapshot CHECK(JSON_VALID(snapshot_json)),
 CONSTRAINT ck_inventory_report_counts CHECK(expected_count>0 AND accepted_count>=0 AND not_observed_count>=0
   AND accepted_count+not_observed_count=expected_count AND captured_count>=0 AND task_version>0)
);
CREATE TABLE fams_inventory_sealed_item (
 task_id BIGINT NOT NULL,
 item_id BIGINT NOT NULL,
 asset_id BIGINT NOT NULL,
 recheck_generation BIGINT NOT NULL,
 snapshot_json LONGTEXT NOT NULL,
 snapshot_sha256 CHAR(64) NOT NULL,
 PRIMARY KEY(task_id,item_id),
 CONSTRAINT fk_inventory_sealed_item_report FOREIGN KEY(task_id) REFERENCES fams_inventory_sealed_report(task_id),
 CONSTRAINT fk_inventory_sealed_item_source FOREIGN KEY(item_id) REFERENCES fams_inventory_item(id),
 CONSTRAINT fk_inventory_sealed_item_asset FOREIGN KEY(asset_id) REFERENCES fams_asset(id),
 CONSTRAINT ck_inventory_sealed_item_snapshot CHECK(JSON_VALID(snapshot_json)),
 CONSTRAINT ck_inventory_sealed_item_round CHECK(recheck_generation>=0),
 INDEX ix_inventory_sealed_asset(task_id,asset_id)
);
CREATE TABLE fams_inventory_sealed_evidence (
 task_id BIGINT NOT NULL,
 observation_id BIGINT NOT NULL,
 snapshot_json LONGTEXT NOT NULL,
 snapshot_sha256 CHAR(64) NOT NULL,
 PRIMARY KEY(task_id,observation_id),
 CONSTRAINT fk_inventory_sealed_evidence_report FOREIGN KEY(task_id) REFERENCES fams_inventory_sealed_report(task_id),
 CONSTRAINT fk_inventory_sealed_evidence_source FOREIGN KEY(observation_id) REFERENCES fams_inventory_observation(id),
 CONSTRAINT ck_inventory_sealed_evidence_snapshot CHECK(JSON_VALID(snapshot_json))
);
CREATE TABLE fams_inventory_sealed_sync (
 task_id BIGINT NOT NULL,
 account_id BIGINT NOT NULL,
 snapshot_json LONGTEXT NOT NULL,
 snapshot_sha256 CHAR(64) NOT NULL,
 PRIMARY KEY(task_id,account_id),
 CONSTRAINT fk_inventory_sealed_sync_report FOREIGN KEY(task_id) REFERENCES fams_inventory_sealed_report(task_id),
 CONSTRAINT fk_inventory_sealed_sync_actor FOREIGN KEY(account_id) REFERENCES fams_system_account(id),
 CONSTRAINT ck_inventory_sealed_sync_snapshot CHECK(JSON_VALID(snapshot_json))
);
