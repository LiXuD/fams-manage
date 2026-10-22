-- Original repair custody and approvals remain frozen; actual delivery and receipt are separate facts.
-- Coordinate replacement of all old writers before allowing these new physical handovers.
CREATE TABLE fams_repair_return_handover (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 repair_id BIGINT NOT NULL,
 round_number INT NOT NULL,
 handover_status VARCHAR(24) NOT NULL DEFAULT 'WAITING_RECEIPT',
 active_repair_id BIGINT GENERATED ALWAYS AS
   (CASE WHEN handover_status IN ('WAITING_RECEIPT','REFUSED') THEN repair_id ELSE NULL END) STORED,
 target_snapshot_json LONGTEXT NOT NULL,
 before_asset_version BIGINT NOT NULL,
 delivered_at DATETIME(3) NOT NULL,
 delivery_evidence VARCHAR(2000) NOT NULL,
 delivered_by BIGINT NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 receipt_at DATETIME(3) NULL,
 receipt_evidence VARCHAR(2000) NULL,
 received_by BIGINT NULL,
 UNIQUE KEY uk_repair_return_round(repair_id,round_number),
 UNIQUE KEY uk_repair_return_active(active_repair_id),
 CONSTRAINT fk_repair_return_request FOREIGN KEY(repair_id) REFERENCES fams_repair_request(id),
 CONSTRAINT fk_repair_return_deliverer FOREIGN KEY(delivered_by) REFERENCES fams_system_account(id),
 CONSTRAINT fk_repair_return_receiver FOREIGN KEY(received_by) REFERENCES fams_system_account(id),
 CONSTRAINT ck_repair_return_target CHECK(JSON_VALID(target_snapshot_json)),
 CONSTRAINT ck_repair_return_version CHECK(before_asset_version>=0 AND round_number>0),
 CONSTRAINT ck_repair_return_status CHECK(handover_status IN ('WAITING_RECEIPT','REFUSED','RECEIVED','RECOLLECTED')),
 INDEX ix_repair_return_history(repair_id,id)
);
CREATE TABLE fams_repair_return_recollection (
 handover_id BIGINT NOT NULL PRIMARY KEY,
 handler_employee_id BIGINT NOT NULL,
 actual_location_id BIGINT NOT NULL,
 location_version BIGINT NOT NULL,
 occurred_at DATETIME(3) NOT NULL,
 evidence VARCHAR(2000) NOT NULL,
 actor_account_id BIGINT NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 CONSTRAINT fk_repair_recollection_handover FOREIGN KEY(handover_id) REFERENCES fams_repair_return_handover(id),
 CONSTRAINT fk_repair_recollection_employee FOREIGN KEY(handler_employee_id) REFERENCES fams_employee(id),
 CONSTRAINT fk_repair_recollection_location FOREIGN KEY(actual_location_id) REFERENCES fams_location(id),
 CONSTRAINT fk_repair_recollection_actor FOREIGN KEY(actor_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT ck_repair_recollection_version CHECK(location_version>=0)
);
