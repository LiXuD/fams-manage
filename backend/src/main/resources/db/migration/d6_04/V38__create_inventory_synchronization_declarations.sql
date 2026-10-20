-- Executor assertions are append-only. A declaration never proves an unknown offline device empty.
CREATE TABLE fams_inventory_sync_declaration (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 task_id BIGINT NOT NULL,
 actor_account_id BIGINT NOT NULL,
 binding_id BIGINT NOT NULL,
 binding_version BIGINT NOT NULL,
 device_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 assignment_version BIGINT NOT NULL,
 observation_watermark BIGINT NOT NULL,
 queue_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 unresolved_count INT NOT NULL,
 declared_at DATETIME(3) NOT NULL,
 CONSTRAINT fk_inventory_sync_task FOREIGN KEY(task_id) REFERENCES fams_inventory_task(id),
 CONSTRAINT fk_inventory_sync_actor FOREIGN KEY(actor_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT fk_inventory_sync_binding FOREIGN KEY(binding_id) REFERENCES fams_mini_binding(id),
 CONSTRAINT ck_inventory_sync_version CHECK(binding_version>=0 AND assignment_version>=0 AND observation_watermark>=0 AND unresolved_count>=0),
 INDEX ix_inventory_sync_device(task_id,actor_account_id,binding_id,device_id,id)
);
CREATE TABLE fams_inventory_sync_receipt (
 task_id BIGINT NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_account_id BIGINT NOT NULL,
 binding_id BIGINT NOT NULL,
 payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 response_json JSON NOT NULL,
 created_at DATETIME(3) NOT NULL,
 PRIMARY KEY(task_id,request_id),
 CONSTRAINT fk_inventory_sync_receipt_task FOREIGN KEY(task_id) REFERENCES fams_inventory_task(id),
 CONSTRAINT fk_inventory_sync_receipt_actor FOREIGN KEY(actor_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT fk_inventory_sync_receipt_binding FOREIGN KEY(binding_id) REFERENCES fams_mini_binding(id)
);
