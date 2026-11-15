-- The evidence belongs to a published inventory item and identifies the exact issued tag.
CREATE TABLE fams_inventory_tag_recognition (
    item_id BIGINT NOT NULL,
    recheck_generation BIGINT NOT NULL,
    tag_version BIGINT NOT NULL,
    tag_issued_at DATETIME(3) NOT NULL,
    actor_account_id BIGINT NOT NULL,
    recognized_at DATETIME(3) NOT NULL,
    PRIMARY KEY (item_id, recheck_generation, tag_version, tag_issued_at),
    CONSTRAINT fk_inventory_recognition_item FOREIGN KEY (item_id)
        REFERENCES fams_inventory_item(id) ON DELETE CASCADE,
    CONSTRAINT fk_inventory_recognition_actor FOREIGN KEY (actor_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT ck_inventory_recognition_versions CHECK (recheck_generation >= 0 AND tag_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
