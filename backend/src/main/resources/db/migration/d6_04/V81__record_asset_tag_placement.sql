CREATE TABLE fams_asset_tag_placement (
    asset_id BIGINT NOT NULL,
    tag_version BIGINT NOT NULL,
    tag_issued_at DATETIME(3) NOT NULL,
    actor_account_id BIGINT NOT NULL,
    confirmed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (asset_id, tag_version, tag_issued_at),
    CONSTRAINT fk_asset_placement_asset FOREIGN KEY (asset_id) REFERENCES fams_asset(id) ON DELETE CASCADE,
    CONSTRAINT fk_asset_placement_actor FOREIGN KEY (actor_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT ck_asset_placement_tag_version CHECK (tag_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
