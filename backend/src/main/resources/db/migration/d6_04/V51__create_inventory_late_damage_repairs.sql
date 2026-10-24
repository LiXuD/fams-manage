-- Independent damage work never discharges supplemental recheck or changes a sealed report.
CREATE TABLE fams_inventory_late_damage (
    observation_id BIGINT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    damage_status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    repair_id BIGINT NULL,
    repair_status VARCHAR(32) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_inventory_late_damage_case FOREIGN KEY (observation_id) REFERENCES fams_inventory_late_case(observation_id),
    CONSTRAINT fk_inventory_late_damage_repair FOREIGN KEY (repair_id) REFERENCES fams_repair_request(id),
    CONSTRAINT ck_inventory_late_damage_version CHECK (version>=0),
    CONSTRAINT ck_inventory_late_damage_status CHECK (damage_status IN ('OPEN','PROCESSING','CLOSED')),
    CONSTRAINT ck_inventory_late_damage_completion CHECK (
        (damage_status='OPEN' AND ((repair_id IS NULL AND repair_status IS NULL) OR (repair_id IS NOT NULL AND repair_status IN ('REJECTED','CANCELLED'))))
        OR (damage_status='PROCESSING' AND repair_id IS NOT NULL AND repair_status IN ('PENDING_ADMIN_DECISION','WAITING_WAREHOUSE_CLAIM','WAREHOUSE_PROCESSING'))
        OR (damage_status='CLOSED' AND repair_id IS NOT NULL AND repair_status='COMPLETED'))
);

CREATE TABLE fams_inventory_late_damage_decision (
    observation_id BIGINT NOT NULL,
    before_version BIGINT NOT NULL,
    after_version BIGINT NOT NULL,
    method VARCHAR(32) NOT NULL,
    damage_status VARCHAR(16) NOT NULL,
    repair_id BIGINT NOT NULL,
    repair_status VARCHAR(32) NOT NULL,
    reviewed_asset_version BIGINT NULL,
    reason VARCHAR(1000) NOT NULL,
    evidence VARCHAR(2000) NOT NULL,
    actor_account_id BIGINT NOT NULL,
    actor_username VARCHAR(128) NOT NULL,
    decided_at DATETIME(3) NOT NULL,
    PRIMARY KEY (observation_id,after_version),
    CONSTRAINT fk_inventory_late_damage_decision_source FOREIGN KEY (observation_id) REFERENCES fams_inventory_late_damage(observation_id),
    CONSTRAINT fk_inventory_late_damage_decision_repair FOREIGN KEY (repair_id) REFERENCES fams_repair_request(id),
    CONSTRAINT fk_inventory_late_damage_decision_actor FOREIGN KEY (actor_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT ck_inventory_late_damage_decision_version CHECK (before_version>=0 AND after_version=before_version+1),
    CONSTRAINT ck_inventory_late_damage_decision_method CHECK (method IN ('FORMAL_REPAIR','LINK_REPAIR','REFRESH_REPAIR')),
    CONSTRAINT ck_inventory_late_damage_decision_status CHECK (damage_status IN ('OPEN','PROCESSING','CLOSED'))
);

-- Coordinated writer replacement is required. Never reopen a legacy already-reviewed case.
-- The two additive backfill writes form one DML transaction after the expanding DDL.
START TRANSACTION;
INSERT INTO fams_inventory_late_damage(observation_id,created_at,updated_at)
SELECT c.observation_id,NOW(3),NOW(3)
FROM fams_inventory_late_case c
JOIN fams_inventory_task t ON t.id=c.task_id
JOIN fams_inventory_observation o ON o.id=c.observation_id AND o.task_id=c.task_id
JOIN fams_inventory_item i ON i.id=o.item_id AND i.task_id=c.task_id
WHERE t.task_status IN ('COMPLETED','CANCELLED','TERMINATED')
  AND c.needs_review=TRUE
  AND JSON_UNQUOTE(JSON_EXTRACT(c.snapshot_json,'$.assetId'))=CAST(i.asset_id AS CHAR)
  AND JSON_UNQUOTE(JSON_EXTRACT(c.snapshot_json,'$.facts.actual.condition'))='DAMAGED'
  AND NOT EXISTS (
      SELECT 1 FROM fams_inventory_late_decision d
      WHERE d.observation_id=c.observation_id AND d.result='REVIEWED'
        AND d.decision_version=(SELECT MAX(latest.decision_version) FROM fams_inventory_late_decision latest WHERE latest.observation_id=c.observation_id))
  AND NOT EXISTS (SELECT 1 FROM fams_inventory_late_damage present WHERE present.observation_id=c.observation_id);

INSERT INTO fams_todo(pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,opened_at)
SELECT 'ASSET_ADMIN',NULL,'INVENTORY_LATE',d.observation_id,'LATE_DAMAGE_REPAIR','OPEN',d.created_at
FROM fams_inventory_late_damage d
WHERE d.damage_status<>'CLOSED'
  AND NOT EXISTS (SELECT 1 FROM fams_todo todo WHERE todo.business_type='INVENTORY_LATE' AND todo.business_id=d.observation_id AND todo.todo_type='LATE_DAMAGE_REPAIR' AND todo.closed_at IS NULL);
COMMIT;
