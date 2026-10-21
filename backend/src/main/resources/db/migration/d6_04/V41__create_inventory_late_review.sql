-- Terminal evidence is supplemental; neither table changes the sealed report or original result.
CREATE TABLE fams_inventory_late_case (
 observation_id BIGINT PRIMARY KEY,
 task_id BIGINT NOT NULL,
 comparison_kind VARCHAR(32) NOT NULL,
 needs_review BOOLEAN NOT NULL,
 report_sha256 CHAR(64) NULL,
 accepted_observation_id BIGINT NULL,
 snapshot_json LONGTEXT NOT NULL,
 snapshot_sha256 CHAR(64) NOT NULL,
 created_at DATETIME(3) NOT NULL,
 CONSTRAINT fk_inventory_late_observation FOREIGN KEY(observation_id) REFERENCES fams_inventory_observation(id),
 CONSTRAINT fk_inventory_late_task FOREIGN KEY(task_id) REFERENCES fams_inventory_task(id),
 CONSTRAINT fk_inventory_late_accepted FOREIGN KEY(accepted_observation_id) REFERENCES fams_inventory_observation(id),
 CONSTRAINT ck_inventory_late_comparison CHECK(comparison_kind IN ('FROZEN_CONFLICT','NO_ACCEPTED','TIME_UNVERIFIED','FINDING','FACTS_MATCH')),
 CONSTRAINT ck_inventory_late_snapshot CHECK(JSON_VALID(snapshot_json)),
 INDEX ix_inventory_late_task(task_id,observation_id)
);
CREATE TABLE fams_inventory_late_decision (
 observation_id BIGINT NOT NULL,
 decision_version BIGINT NOT NULL,
 method VARCHAR(32) NOT NULL,
 result VARCHAR(24) NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 evidence VARCHAR(2000) NOT NULL,
 linked_task_id BIGINT NULL,
 actor_account_id BIGINT NOT NULL,
 actor_username VARCHAR(128) NOT NULL,
 decided_at DATETIME(3) NOT NULL,
 PRIMARY KEY(observation_id,decision_version),
 CONSTRAINT fk_inventory_late_decision_source FOREIGN KEY(observation_id) REFERENCES fams_inventory_late_case(observation_id),
 CONSTRAINT fk_inventory_late_decision_link FOREIGN KEY(linked_task_id) REFERENCES fams_inventory_task(id),
 CONSTRAINT fk_inventory_late_decision_actor FOREIGN KEY(actor_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT ck_inventory_late_decision_version CHECK(decision_version>0),
 CONSTRAINT ck_inventory_late_decision_method CHECK(method IN ('NOTE','LINK_RECHECK','REPLACE_RECHECK','CONFIRM_RECHECK')),
 CONSTRAINT ck_inventory_late_decision_result CHECK(result IN ('REVIEWED','FOLLOWUP_OPEN')),
 CONSTRAINT ck_inventory_late_decision_link CHECK((method='NOTE' AND linked_task_id IS NULL AND result='REVIEWED') OR (method<>'NOTE' AND linked_task_id IS NOT NULL))
);
