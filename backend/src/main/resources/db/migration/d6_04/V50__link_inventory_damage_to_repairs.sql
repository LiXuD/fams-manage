-- Forward expansion only. Inventory owns difference decisions; repair owns formal work and evidence scope.
CREATE TABLE fams_inventory_review_repair (
    observation_id BIGINT NOT NULL,
    dimension VARCHAR(32) NOT NULL,
    decision_version BIGINT NOT NULL,
    repair_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (observation_id,dimension,decision_version),
    CONSTRAINT fk_inventory_repair_observation FOREIGN KEY (observation_id) REFERENCES fams_inventory_observation(id),
    CONSTRAINT fk_inventory_repair_request FOREIGN KEY (repair_id) REFERENCES fams_repair_request(id),
    CONSTRAINT ck_inventory_repair_dimension CHECK (dimension='CONDITION')
);

CREATE TABLE fams_repair_inventory_evidence (
    repair_id BIGINT NOT NULL,
    observation_id BIGINT NOT NULL,
    source_kind VARCHAR(20) NOT NULL,
    source_context_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (repair_id,observation_id),
    CONSTRAINT fk_repair_inventory_request FOREIGN KEY (repair_id) REFERENCES fams_repair_request(id),
    CONSTRAINT fk_repair_inventory_observation FOREIGN KEY (observation_id) REFERENCES fams_inventory_observation(id),
    CONSTRAINT ck_repair_inventory_source CHECK (source_kind IN ('CONDITION','LATE'))
);
