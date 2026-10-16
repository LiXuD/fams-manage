package com.fams.asset;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Asset-owned identification, never a grant to expose responsibility outside a task. */
@Component
@Profile("fams-product")
public final class AssetInventoryReadPort {
    private final JdbcTemplate db;
    private final AssetSecretCodec codec;

    AssetInventoryReadPort(JdbcTemplate db, AssetSecretCodec codec) {
        this.db = db;
        this.codec = codec;
    }

    public List<Map<String, Object>> identify(String source, String value) {
        if ("SCAN".equals(source)) {
            if (!value.matches("[A-Za-z0-9_-]{43}")) {
                return List.of();
            }
            return db.queryForList(
                    "SELECT CAST(a.id AS CHAR) AS id,a.asset_code,a.name FROM fams_asset a JOIN"
                        + " fams_asset_tag t ON t.asset_id=a.id WHERE t.token_hash=? AND"
                        + " a.card_stage='ACTIVE' AND a.lifecycle_status<>'WAITING_INBOUND'",
                    codec.hash(value));
        }
        return db.queryForList(
                "SELECT CAST(id AS CHAR) AS id,asset_code,name FROM fams_asset WHERE asset_code=?"
                        + " AND card_stage='ACTIVE' AND lifecycle_status<>'WAITING_INBOUND'",
                value);
    }

    public String currentSnapshot(long assetId) {
        var rows =
                db.queryForList(
                        "SELECT CAST(snapshot_json AS CHAR) AS snapshot FROM"
                            + " fams_asset_inventory_source_v1 WHERE asset_id=?",
                        assetId);
        return rows.isEmpty() ? null : rows.getFirst().get("snapshot").toString();
    }
}
