-- Final evidence disposition preserves raw observations and binds its counterevidence selection.
CREATE TABLE fams_inventory_evidence_disposition (
 observation_id BIGINT NOT NULL PRIMARY KEY,
 task_id BIGINT NOT NULL,
 item_id BIGINT NOT NULL,
 recheck_generation BIGINT NOT NULL,
 counter_observation_id BIGINT NOT NULL,
 counter_review_version BIGINT NOT NULL,
 disposition VARCHAR(24) NOT NULL,
 reviewed_asset_version BIGINT NOT NULL,
 context_digest CHAR(64) NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 evidence VARCHAR(2000) NOT NULL,
 actor_account_id BIGINT NOT NULL,
 actor_username VARCHAR(128) NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 version BIGINT NOT NULL DEFAULT 1,
 CONSTRAINT fk_inventory_disposition_observation FOREIGN KEY(observation_id) REFERENCES fams_inventory_observation(id),
 CONSTRAINT fk_inventory_disposition_counter FOREIGN KEY(counter_observation_id) REFERENCES fams_inventory_observation(id),
 CONSTRAINT fk_inventory_disposition_review FOREIGN KEY(item_id,recheck_generation)
   REFERENCES fams_inventory_item_review(item_id,recheck_generation),
 CONSTRAINT fk_inventory_disposition_task FOREIGN KEY(task_id) REFERENCES fams_inventory_task(id),
 CONSTRAINT fk_inventory_disposition_actor FOREIGN KEY(actor_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT ck_inventory_disposition_status CHECK(disposition IN ('REJECTED','SUPPLEMENTAL')),
 CONSTRAINT ck_inventory_disposition_versions CHECK(recheck_generation>=0 AND counter_review_version>0 AND reviewed_asset_version>=0 AND version=1 AND observation_id<>counter_observation_id),
 INDEX ix_inventory_disposition_task(task_id,item_id,observation_id)
);
