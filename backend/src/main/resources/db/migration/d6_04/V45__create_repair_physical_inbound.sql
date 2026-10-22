-- Add physical repair facts without rewriting original custody, approvals or borrowing dates.
CREATE TABLE fams_repair_physical_handling (
 repair_id BIGINT NOT NULL PRIMARY KEY,
 handler_employee_id BIGINT NOT NULL,
 actual_location_id BIGINT NOT NULL,
 location_version BIGINT NOT NULL,
 before_asset_version BIGINT NOT NULL,
 occurred_at DATETIME(3) NOT NULL,
 evidence VARCHAR(2000) NOT NULL,
 actor_account_id BIGINT NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 CONSTRAINT fk_repair_handling_request FOREIGN KEY(repair_id) REFERENCES fams_repair_request(id),
 CONSTRAINT fk_repair_handling_employee FOREIGN KEY(handler_employee_id) REFERENCES fams_employee(id),
 CONSTRAINT fk_repair_handling_location FOREIGN KEY(actual_location_id) REFERENCES fams_location(id),
 CONSTRAINT fk_repair_handling_actor FOREIGN KEY(actor_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT ck_repair_handling_versions CHECK(location_version>=0 AND before_asset_version>=0)
);
CREATE TABLE fams_repair_inbound (
 repair_id BIGINT NOT NULL PRIMARY KEY,
 warehouse_id BIGINT NOT NULL,
 location_id BIGINT NOT NULL,
 warehouse_version BIGINT NOT NULL,
 location_version BIGINT NOT NULL,
 before_asset_version BIGINT NOT NULL,
 occurred_at DATETIME(3) NOT NULL,
 evidence VARCHAR(2000) NOT NULL,
 actor_account_id BIGINT NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 CONSTRAINT fk_repair_inbound_request FOREIGN KEY(repair_id) REFERENCES fams_repair_request(id),
 CONSTRAINT fk_repair_inbound_warehouse FOREIGN KEY(warehouse_id) REFERENCES fams_warehouse(id),
 CONSTRAINT fk_repair_inbound_location FOREIGN KEY(location_id) REFERENCES fams_location(id),
 CONSTRAINT fk_repair_inbound_actor FOREIGN KEY(actor_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT ck_repair_inbound_versions CHECK(warehouse_version>=0 AND location_version>=0 AND before_asset_version>=0)
);
-- The loan domain owns this actual return link and the corresponding OPEN -> RETURNED write.
CREATE TABLE fams_loan_repair_return (
 repair_id BIGINT NOT NULL PRIMARY KEY,
 loan_id BIGINT NOT NULL,
 asset_id BIGINT NOT NULL,
 before_loan_version BIGINT NOT NULL,
 after_loan_version BIGINT NOT NULL,
 returned_at DATETIME(3) NOT NULL,
 actor_account_id BIGINT NOT NULL,
 CONSTRAINT fk_loan_repair_return_request FOREIGN KEY(repair_id) REFERENCES fams_repair_request(id),
 CONSTRAINT fk_loan_repair_return_loan FOREIGN KEY(loan_id) REFERENCES fams_loan(id),
 CONSTRAINT fk_loan_repair_return_asset FOREIGN KEY(asset_id) REFERENCES fams_asset(id),
 CONSTRAINT fk_loan_repair_return_actor FOREIGN KEY(actor_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT ck_loan_repair_return_version CHECK(before_loan_version>=0 AND after_loan_version=before_loan_version+1)
);
