-- Recollection resolves a time obligation without rewriting the uncertain original observation.
CREATE TABLE fams_inventory_finding_recollection (
 observation_id BIGINT NOT NULL,
 dimension VARCHAR(32) NOT NULL,
 decision_version BIGINT NOT NULL,
 task_id BIGINT NOT NULL,
 counter_observation_id BIGINT NOT NULL,
 counter_observed_at DATETIME(3) NOT NULL,
 created_at DATETIME(3) NOT NULL,
 PRIMARY KEY(observation_id,dimension,decision_version),
 CONSTRAINT fk_inventory_recollection_decision FOREIGN KEY(observation_id,dimension,decision_version)
  REFERENCES fams_inventory_review_decision(observation_id,dimension,after_version),
 CONSTRAINT fk_inventory_recollection_counter FOREIGN KEY(counter_observation_id)
  REFERENCES fams_inventory_observation(id),
 CONSTRAINT fk_inventory_recollection_task FOREIGN KEY(task_id) REFERENCES fams_inventory_task(id),
 CONSTRAINT ck_inventory_recollection_dimension CHECK(dimension='TIME'),
 CONSTRAINT ck_inventory_recollection_distinct CHECK(observation_id<>counter_observation_id),
 INDEX ix_inventory_recollection_counter(counter_observation_id)
);
