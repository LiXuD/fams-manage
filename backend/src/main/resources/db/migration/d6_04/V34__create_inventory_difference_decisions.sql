-- Append-only decisions retain prior difference conclusions and linked formal business history.
CREATE TABLE fams_inventory_review_decision (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 task_id BIGINT NOT NULL,
 observation_id BIGINT NOT NULL,
 dimension VARCHAR(32) NOT NULL,
 before_version BIGINT NOT NULL,
 after_version BIGINT NOT NULL,
 method VARCHAR(32) NOT NULL,
 subject_status VARCHAR(24) NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 evidence VARCHAR(2000) NOT NULL,
 reviewed_asset_version BIGINT NOT NULL,
 actor_account_id BIGINT NOT NULL,
 actor_username VARCHAR(128) NOT NULL,
 occurred_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_inventory_decision_version(observation_id,dimension,after_version),
 CONSTRAINT fk_inventory_decision_subject FOREIGN KEY(observation_id,dimension)
   REFERENCES fams_inventory_review_subject(observation_id,dimension),
 CONSTRAINT fk_inventory_decision_task FOREIGN KEY(task_id) REFERENCES fams_inventory_task(id),
 CONSTRAINT fk_inventory_decision_actor FOREIGN KEY(actor_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT ck_inventory_decision_versions CHECK(before_version>=0 AND after_version=before_version+1 AND reviewed_asset_version>=0)
);
CREATE TABLE fams_inventory_review_transfer (
 observation_id BIGINT NOT NULL,
 dimension VARCHAR(32) NOT NULL,
 decision_version BIGINT NOT NULL,
 transfer_id BIGINT NOT NULL,
 transfer_item_id BIGINT NOT NULL,
 created_at DATETIME(3) NOT NULL,
 PRIMARY KEY(observation_id,dimension,decision_version),
 CONSTRAINT fk_inventory_link_decision FOREIGN KEY(observation_id,dimension,decision_version)
   REFERENCES fams_inventory_review_decision(observation_id,dimension,after_version),
 INDEX ix_inventory_review_transfer(transfer_id,transfer_item_id)
);
