-- Finding decisions bind existing formal owners; raw observations and old decisions stay immutable.
CREATE TABLE fams_inventory_finding_link (
 observation_id BIGINT NOT NULL,
 dimension VARCHAR(32) NOT NULL DEFAULT 'FINDING',
 decision_version BIGINT NOT NULL,
 task_id BIGINT NOT NULL,
 link_kind VARCHAR(24) NOT NULL,
 asset_id BIGINT NOT NULL,
 inbound_receipt_id BIGINT NULL,
 owner_version BIGINT NOT NULL,
 created_at DATETIME(3) NOT NULL,
 PRIMARY KEY(observation_id,dimension,decision_version),
 CONSTRAINT fk_inventory_finding_decision FOREIGN KEY(observation_id,dimension,decision_version)
  REFERENCES fams_inventory_review_decision(observation_id,dimension,after_version),
 CONSTRAINT fk_inventory_finding_task FOREIGN KEY(task_id) REFERENCES fams_inventory_task(id),
 CONSTRAINT fk_inventory_finding_asset FOREIGN KEY(asset_id) REFERENCES fams_asset(id),
 CONSTRAINT fk_inventory_finding_inbound FOREIGN KEY(inbound_receipt_id) REFERENCES fams_inbound_receipt(id),
 CONSTRAINT ck_inventory_finding_link CHECK(dimension='FINDING' AND decision_version>0 AND owner_version>=0
  AND ((link_kind='DUPLICATE_ASSET' AND inbound_receipt_id IS NULL)
    OR (link_kind='FORMAL_INBOUND' AND inbound_receipt_id IS NOT NULL))),
 INDEX ix_inventory_finding_asset(asset_id,observation_id),
 INDEX ix_inventory_finding_inbound(inbound_receipt_id,observation_id)
);
