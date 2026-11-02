-- Expand only: preserve all V56 facts and permanent receipts; old writers remain status-guarded.
ALTER TABLE fams_loss_report
 ADD COLUMN confirmed_lost_at DATETIME(3) NULL,
 ADD COLUMN confirmation_snapshot_json LONGTEXT NULL,
 ADD COLUMN last_follow_up_at DATETIME(3) NULL,
 ADD CONSTRAINT ck_loss_confirmation_json CHECK(confirmation_snapshot_json IS NULL OR JSON_VALID(confirmation_snapshot_json)),
 DROP CHECK ck_loss_status,
 ADD CONSTRAINT ck_loss_status CHECK(report_status IN ('PENDING_ADMIN_DECISION','WAITING_WAREHOUSE_CLAIM','VERIFYING','SEARCHING','REJECTED','CANCELLED','CONFIRMED_LOST'));
