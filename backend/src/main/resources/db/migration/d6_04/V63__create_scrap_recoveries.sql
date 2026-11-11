-- Recoveries register a written-off scrap physical return and re-accept the original card through their own approval.
CREATE TABLE fams_scrap_recovery (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 request_id BIGINT NOT NULL,
 asset_id BIGINT NOT NULL,
 creator_account_id BIGINT NOT NULL,
 processor_account_id BIGINT NULL,
 reason VARCHAR(1000) NOT NULL,
 found_at DATETIME(3) NOT NULL,
 temporary_location_id BIGINT NOT NULL,
 temporary_location_version BIGINT NOT NULL,
 temporary_custodian_employee_id BIGINT NOT NULL,
 asset_version BIGINT NOT NULL,
 target_location_id BIGINT NOT NULL,
 target_location_version BIGINT NOT NULL,
 recovery_status VARCHAR(40) NOT NULL DEFAULT 'PENDING_ADMIN_DECISION',
 request_snapshot_json LONGTEXT NOT NULL,
 approval_snapshot_json LONGTEXT NULL,
 execution_snapshot_json LONGTEXT NULL,
 ownership_version BIGINT NOT NULL DEFAULT 0,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 active_guard TINYINT GENERATED ALWAYS AS (CASE WHEN recovery_status IN
  ('PENDING_ADMIN_DECISION','WAITING_WAREHOUSE_CLAIM','PROCESSING') THEN 1 ELSE NULL END) STORED,
 UNIQUE KEY uk_scrap_recovery_active (request_id,active_guard),
 INDEX ix_scrap_recovery_asset (asset_id,id),
 CONSTRAINT fk_scrap_recovery_request FOREIGN KEY (request_id) REFERENCES fams_scrap_request(id),
 CONSTRAINT fk_scrap_recovery_asset FOREIGN KEY (asset_id) REFERENCES fams_asset(id),
 CONSTRAINT fk_scrap_recovery_creator FOREIGN KEY (creator_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT fk_scrap_recovery_processor FOREIGN KEY (processor_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT ck_scrap_recovery_status CHECK (recovery_status IN (
  'PENDING_ADMIN_DECISION','WAITING_WAREHOUSE_CLAIM','PROCESSING','COMPLETED','REJECTED')),
 CONSTRAINT ck_scrap_recovery_versions CHECK (asset_version>=0 AND temporary_location_version>=0
  AND target_location_version>=0 AND ownership_version>=0 AND version>=0),
 CONSTRAINT ck_scrap_recovery_json CHECK (JSON_VALID(request_snapshot_json)
  AND (approval_snapshot_json IS NULL OR JSON_VALID(approval_snapshot_json))
  AND (execution_snapshot_json IS NULL OR JSON_VALID(execution_snapshot_json)))
);
