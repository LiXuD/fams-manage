-- Corrections close invalid frozen scrap cancellations with their own approval and exclusive execution.
ALTER TABLE fams_scrap_request
 DROP CHECK ck_scrap_request_status,
 ADD CONSTRAINT ck_scrap_request_status CHECK (request_status IN (
  'PENDING_ADMIN_DECISION','WAITING_WAREHOUSE_CLAIM','PROCESSING','RESTORE_REQUIRED',
  'REJECTED','CANCELLED','EXECUTED','CORRECTED'));
CREATE TABLE fams_scrap_correction (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 request_id BIGINT NOT NULL,
 asset_id BIGINT NOT NULL,
 creator_account_id BIGINT NOT NULL,
 processor_account_id BIGINT NULL,
 reason VARCHAR(1000) NOT NULL,
 asset_version BIGINT NOT NULL,
 lock_version BIGINT NOT NULL,
 target_location_id BIGINT NOT NULL,
 target_location_version BIGINT NOT NULL,
 correction_status VARCHAR(40) NOT NULL DEFAULT 'PENDING_ADMIN_DECISION',
 request_snapshot_json LONGTEXT NOT NULL,
 approval_snapshot_json LONGTEXT NULL,
 execution_snapshot_json LONGTEXT NULL,
 ownership_version BIGINT NOT NULL DEFAULT 0,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 active_guard TINYINT GENERATED ALWAYS AS (CASE WHEN correction_status IN
  ('PENDING_ADMIN_DECISION','WAITING_WAREHOUSE_CLAIM','PROCESSING') THEN 1 ELSE NULL END) STORED,
 UNIQUE KEY uk_scrap_correction_active (request_id,active_guard),
 INDEX ix_scrap_correction_asset (asset_id,id),
 CONSTRAINT fk_scrap_correction_request FOREIGN KEY (request_id) REFERENCES fams_scrap_request(id),
 CONSTRAINT fk_scrap_correction_asset FOREIGN KEY (asset_id) REFERENCES fams_asset(id),
 CONSTRAINT fk_scrap_correction_creator FOREIGN KEY (creator_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT fk_scrap_correction_processor FOREIGN KEY (processor_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT ck_scrap_correction_status CHECK (correction_status IN (
  'PENDING_ADMIN_DECISION','WAITING_WAREHOUSE_CLAIM','PROCESSING','COMPLETED','REJECTED')),
 CONSTRAINT ck_scrap_correction_versions CHECK (asset_version>=0 AND lock_version>=0
  AND target_location_version>=0 AND ownership_version>=0 AND version>=0),
 CONSTRAINT ck_scrap_correction_json CHECK (JSON_VALID(request_snapshot_json)
  AND (approval_snapshot_json IS NULL OR JSON_VALID(approval_snapshot_json))
  AND (execution_snapshot_json IS NULL OR JSON_VALID(execution_snapshot_json)))
);
