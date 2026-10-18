-- Administrative review is distinct from immutable V32 observations and round candidates.
CREATE TABLE fams_inventory_item_review (
 item_id BIGINT NOT NULL,
 recheck_generation BIGINT NOT NULL,
 task_id BIGINT NOT NULL,
 observation_id BIGINT NULL,
 review_status VARCHAR(24) NOT NULL,
 verified_observed_at DATETIME(3) NULL,
 reviewed_asset_version BIGINT NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 evidence VARCHAR(2000) NOT NULL,
 reviewer_account_id BIGINT NOT NULL,
 reviewer_username VARCHAR(128) NOT NULL,
 reviewed_at DATETIME(3) NOT NULL,
 version BIGINT NOT NULL DEFAULT 1,
 PRIMARY KEY(item_id,recheck_generation),
 CONSTRAINT fk_inventory_review_item FOREIGN KEY(item_id) REFERENCES fams_inventory_item(id),
 CONSTRAINT fk_inventory_review_task FOREIGN KEY(task_id) REFERENCES fams_inventory_task(id),
 CONSTRAINT fk_inventory_review_observation FOREIGN KEY(observation_id) REFERENCES fams_inventory_observation(id),
 CONSTRAINT fk_inventory_review_actor FOREIGN KEY(reviewer_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT ck_inventory_review_status CHECK(review_status IN ('SELECTED','ACCEPTED','NOT_OBSERVED')),
 CONSTRAINT ck_inventory_review_versions CHECK(recheck_generation>=0 AND reviewed_asset_version>=0 AND version>0),
 CONSTRAINT ck_inventory_review_evidence CHECK(
   (review_status='NOT_OBSERVED' AND observation_id IS NULL AND verified_observed_at IS NULL)
   OR (review_status IN ('SELECTED','ACCEPTED') AND observation_id IS NOT NULL AND verified_observed_at IS NOT NULL)),
 INDEX ix_inventory_review_task(task_id,review_status,item_id)
);
CREATE TABLE fams_inventory_review_subject (
 observation_id BIGINT NOT NULL,
 dimension VARCHAR(32) NOT NULL,
 task_id BIGINT NOT NULL,
 item_id BIGINT NULL,
 recheck_generation BIGINT NOT NULL,
 subject_status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
 method VARCHAR(32) NULL,
 reason VARCHAR(1000) NULL,
 evidence VARCHAR(2000) NULL,
 reviewer_account_id BIGINT NULL,
 reviewer_username VARCHAR(128) NULL,
 reviewed_at DATETIME(3) NULL,
 version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(observation_id,dimension),
 CONSTRAINT fk_inventory_subject_observation FOREIGN KEY(observation_id) REFERENCES fams_inventory_observation(id),
 CONSTRAINT fk_inventory_subject_task FOREIGN KEY(task_id) REFERENCES fams_inventory_task(id),
 CONSTRAINT fk_inventory_subject_item FOREIGN KEY(item_id) REFERENCES fams_inventory_item(id),
 CONSTRAINT fk_inventory_subject_actor FOREIGN KEY(reviewer_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT ck_inventory_subject_status CHECK(subject_status IN ('OPEN','PROCESSING','CLOSED','TRANSFERRED')),
 CONSTRAINT ck_inventory_subject_dimension CHECK(dimension IN ('NORMAL','LOCATION','USAGE_MODE','RESPONSIBILITY','CONDITION','UNKNOWN','PERIOD_MOVEMENT','CONFLICT','OLD_ASSIGNMENT','TIME','FINDING','LATE')),
 CONSTRAINT ck_inventory_subject_versions CHECK(recheck_generation>=0 AND version>=0),
 INDEX ix_inventory_subject_task(task_id,subject_status,observation_id)
);
-- Durable original review responses have no expiry-based key reuse.
CREATE TABLE fams_inventory_review_receipt (
 task_id BIGINT NOT NULL,
 actor_account_id BIGINT NOT NULL,
 operation VARCHAR(32) NOT NULL,
 request_id CHAR(36) NOT NULL,
 payload_sha256 CHAR(64) NOT NULL,
 response_json JSON NOT NULL,
 created_at DATETIME(3) NOT NULL,
 PRIMARY KEY(task_id,actor_account_id,operation,request_id),
 CONSTRAINT fk_inventory_review_receipt_task FOREIGN KEY(task_id) REFERENCES fams_inventory_task(id),
 CONSTRAINT fk_inventory_review_receipt_actor FOREIGN KEY(actor_account_id) REFERENCES fams_system_account(id)
);
