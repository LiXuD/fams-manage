-- Preserve prior loss facts and receipts. Recovery has its own approval and exclusive ownership.
ALTER TABLE fams_loss_report
 ADD COLUMN recovered_at DATETIME(3) NULL,
 ADD COLUMN recovery_snapshot_json LONGTEXT NULL,
 ADD CONSTRAINT ck_loss_recovered_json CHECK(recovery_snapshot_json IS NULL OR JSON_VALID(recovery_snapshot_json)),
 DROP CHECK ck_loss_status,
 ADD CONSTRAINT ck_loss_status CHECK(report_status IN ('PENDING_ADMIN_DECISION','WAITING_WAREHOUSE_CLAIM','VERIFYING','SEARCHING','REJECTED','CANCELLED','CONFIRMED_LOST','RECOVERED'));
CREATE TABLE fams_loss_recovery (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 report_id BIGINT NOT NULL,
 asset_id BIGINT NOT NULL,
 creator_account_id BIGINT NOT NULL,
 asset_version BIGINT NOT NULL,
 request_snapshot_json LONGTEXT NOT NULL,
 recovery_status VARCHAR(40) NOT NULL DEFAULT 'PENDING_ADMIN_DECISION',
 approval_snapshot_json LONGTEXT NULL,
 processor_account_id BIGINT NULL,
 ownership_version BIGINT NOT NULL DEFAULT 0,
 version BIGINT NOT NULL DEFAULT 0,
 execution_snapshot_json LONGTEXT NULL,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 active_guard TINYINT GENERATED ALWAYS AS (CASE WHEN recovery_status IN ('PENDING_ADMIN_DECISION','WAITING_WAREHOUSE_CLAIM','VERIFYING') THEN 1 ELSE NULL END) STORED,
 UNIQUE KEY uk_loss_recovery_active(asset_id,active_guard),
 CONSTRAINT fk_loss_recovery_report FOREIGN KEY(report_id) REFERENCES fams_loss_report(id),
 CONSTRAINT fk_loss_recovery_asset FOREIGN KEY(asset_id) REFERENCES fams_asset(id),
 CONSTRAINT fk_loss_recovery_creator FOREIGN KEY(creator_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT fk_loss_recovery_processor FOREIGN KEY(processor_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT ck_loss_recovery_status CHECK(recovery_status IN ('PENDING_ADMIN_DECISION','WAITING_WAREHOUSE_CLAIM','VERIFYING','COMPLETED','REJECTED','CANCELLED')),
 CONSTRAINT ck_loss_recovery_json CHECK(JSON_VALID(request_snapshot_json) AND (approval_snapshot_json IS NULL OR JSON_VALID(approval_snapshot_json)) AND (execution_snapshot_json IS NULL OR JSON_VALID(execution_snapshot_json))),
 CONSTRAINT ck_loss_recovery_versions CHECK(asset_version>=0 AND version>=0 AND ownership_version>=0),
 INDEX ix_loss_recovery_report(report_id,id)
);
