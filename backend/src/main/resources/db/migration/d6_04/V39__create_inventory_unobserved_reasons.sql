-- Explicit absence of an observation is not a successful inventory result.
CREATE TABLE fams_inventory_unobserved_reason (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 task_id BIGINT NOT NULL,
 item_id BIGINT NOT NULL,
 recheck_generation BIGINT NOT NULL,
 reason_kind VARCHAR(32) NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 evidence VARCHAR(2000) NOT NULL,
 reviewed_asset_version BIGINT NOT NULL,
 actor_account_id BIGINT NOT NULL,
 actor_username VARCHAR(128) NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 CONSTRAINT uk_inventory_unobserved_round UNIQUE(item_id,recheck_generation),
 CONSTRAINT fk_inventory_unobserved_task FOREIGN KEY(task_id) REFERENCES fams_inventory_task(id),
 CONSTRAINT fk_inventory_unobserved_item FOREIGN KEY(item_id,recheck_generation)
   REFERENCES fams_inventory_item_review(item_id,recheck_generation),
 CONSTRAINT fk_inventory_unobserved_actor FOREIGN KEY(actor_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT ck_inventory_unobserved_kind CHECK(reason_kind IN ('UNABLE_TO_OBSERVE','EVIDENCE_UNAVAILABLE','ASSET_NOT_FOUND')),
 CONSTRAINT ck_inventory_unobserved_version CHECK(recheck_generation>=0 AND reviewed_asset_version>=0),
 INDEX ix_inventory_unobserved_task(task_id,reason_kind,item_id)
);
