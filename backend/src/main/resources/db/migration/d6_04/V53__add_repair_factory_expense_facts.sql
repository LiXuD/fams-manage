-- Forward-only expense expansion. Stop old writers before coordinated application replacement.
-- Existing approvals, receipt JSON, actual dates and verified zero amounts are never rewritten.
ALTER TABLE fams_repair_factory_scope
 ADD COLUMN cost_policy VARCHAR(16) NOT NULL DEFAULT 'NO_COST',
 ADD COLUMN cost_limit DECIMAL(14,2) NULL DEFAULT 0.00,
 DROP CHECK ck_factory_scope_method,
 ADD CONSTRAINT ck_factory_scope_method CHECK(allowed_method IN ('FACTORY_NO_COST','FACTORY_COST_ALLOWED')),
 ADD CONSTRAINT ck_factory_scope_cost_policy CHECK(
  (allowed_method='FACTORY_NO_COST' AND cost_policy='NO_COST' AND cost_limit IS NOT NULL AND cost_limit=0)
  OR (allowed_method='FACTORY_COST_ALLOWED' AND cost_policy='LIMITED' AND cost_limit IS NOT NULL AND cost_limit>0)
  OR (allowed_method='FACTORY_COST_ALLOWED' AND cost_policy='UNLIMITED' AND cost_limit IS NULL)
 );

ALTER TABLE fams_repair_factory_round
 ADD COLUMN dispatch_scope_id BIGINT NULL,
 ADD INDEX ix_factory_round_dispatch_scope(dispatch_scope_id),
 ADD CONSTRAINT fk_factory_round_dispatch_scope FOREIGN KEY(dispatch_scope_id) REFERENCES fams_repair_factory_scope(id),
 DROP CHECK ck_factory_round_receipt,
 ADD CONSTRAINT ck_factory_round_receipt CHECK(
  (round_status<>'RECEIVED' AND received_at IS NULL AND received_by IS NULL AND receive_location_id IS NULL AND receive_location_version IS NULL AND condition_result IS NULL AND treatment_result IS NULL)
  OR (round_status='RECEIVED' AND received_at IS NOT NULL AND received_at>=dispatched_at AND received_by IS NOT NULL AND receive_location_id IS NOT NULL AND receive_location_version IS NOT NULL AND condition_result IS NOT NULL AND condition_result IN ('RESTORED','STILL_FAULTY') AND treatment_result IS NOT NULL)
 ),
 ADD CONSTRAINT ck_factory_round_actual_cost CHECK(actual_cost IS NULL OR actual_cost>=0),
 ADD CONSTRAINT ck_factory_round_dispatch_scope CHECK(dispatch_scope_id IS NULL OR dispatched_at IS NOT NULL);

ALTER TABLE fams_repair_factory_evidence
 DROP CHECK ck_factory_evidence_purpose,
 ADD CONSTRAINT ck_factory_evidence_purpose CHECK(purpose IN ('FAULT','FACTORY_ACK','RECEIPT','QUOTE','COST_RECEIPT'));

CREATE TABLE fams_repair_factory_quote (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 round_id BIGINT NOT NULL,
 amount DECIMAL(14,2) NOT NULL,
 evidence_ids_json LONGTEXT NOT NULL,
 snapshot_json LONGTEXT NOT NULL,
 created_by BIGINT NOT NULL,
 created_at DATETIME(3) NOT NULL,
 INDEX ix_factory_quote_round(round_id,id),
 CONSTRAINT fk_factory_quote_round FOREIGN KEY(round_id) REFERENCES fams_repair_factory_round(id),
 CONSTRAINT fk_factory_quote_creator FOREIGN KEY(created_by) REFERENCES fams_system_account(id),
 CONSTRAINT ck_factory_quote_amount CHECK(amount>=0),
 CONSTRAINT ck_factory_quote_json CHECK(JSON_VALID(snapshot_json) AND JSON_VALID(evidence_ids_json) AND JSON_TYPE(evidence_ids_json)='ARRAY' AND JSON_LENGTH(evidence_ids_json) BETWEEN 1 AND 14)
);

-- One immutable final actual-cost fact per round. Quotes and uploaded copies never add money.
-- Legacy received zero remains confirmed in round.actual_cost without fabricated cost facts.
CREATE TABLE fams_repair_factory_cost (
 round_id BIGINT NOT NULL PRIMARY KEY,
 amount DECIMAL(14,2) NOT NULL,
 evidence_ids_json LONGTEXT NOT NULL,
 snapshot_json LONGTEXT NOT NULL,
 recorded_by BIGINT NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 CONSTRAINT fk_factory_cost_round FOREIGN KEY(round_id) REFERENCES fams_repair_factory_round(id),
 CONSTRAINT fk_factory_cost_recorder FOREIGN KEY(recorded_by) REFERENCES fams_system_account(id),
 CONSTRAINT ck_factory_cost_amount CHECK(amount>=0),
 CONSTRAINT ck_factory_cost_json CHECK(JSON_VALID(snapshot_json) AND JSON_VALID(evidence_ids_json) AND JSON_TYPE(evidence_ids_json)='ARRAY' AND JSON_LENGTH(evidence_ids_json)<=14 AND (amount=0 OR JSON_LENGTH(evidence_ids_json)>0))
);
