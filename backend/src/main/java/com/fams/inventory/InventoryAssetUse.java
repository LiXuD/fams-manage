package com.fams.inventory;

import com.fams.asset.AssetDownstreamUsePort;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Database-only adapter avoids a dependency from the asset owner to inventory orchestration. */
@Component
@Profile("fams-product")
final class InventoryAssetUse implements AssetDownstreamUsePort {
    private final JdbcTemplate db;

    InventoryAssetUse(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public boolean hasPublishedUse(long assetId) {
        return !db.queryForList(
                        "SELECT id FROM fams_inventory_item WHERE asset_id=? LIMIT 1 FOR SHARE",
                        assetId)
                .isEmpty();
    }
}
