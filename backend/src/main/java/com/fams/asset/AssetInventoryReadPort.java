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

    /**
     * Identified asset card. The row is never serialized wholesale into a wire or storage value;
     * inventory consumers read id/assetCode/name individually and bind tagVersion/issuedAt
     * straight into the tag-recognition insert, so those two components keep the raw JDBC values
     * untouched (JDBC raw-value red line, JAVA-01). They exist only on SCAN rows.
     */
    public record Identification(
            String id, String assetCode, String name, Object tagVersion, Object issuedAt) {

        /** Projection of one identify() JDBC row; CODE rows carry null tag fields. */
        public static Identification fromRow(Map<String, Object> row) {
            return new Identification(
                    (String) row.get("id"),
                    (String) row.get("asset_code"),
                    (String) row.get("name"),
                    row.get("tag_version"),
                    row.get("issued_at"));
        }
    }

    private final JdbcTemplate db;
    private final AssetSecretCodec codec;

    AssetInventoryReadPort(JdbcTemplate db, AssetSecretCodec codec) {
        this.db = db;
        this.codec = codec;
    }

    public List<Identification> identify(String source, String value) {
        if ("SCAN".equals(source)) {
            if (!value.matches("[A-Za-z0-9_-]{43}")) {
                return List.of();
            }
            return db.queryForList(
                            "SELECT CAST(a.id AS CHAR) AS id,a.asset_code,a.name,t.tag_version,t.issued_at FROM fams_asset a JOIN"
                                + " fams_asset_tag t ON t.asset_id=a.id WHERE t.token_hash=? AND"
                                + " a.card_stage='ACTIVE' AND a.lifecycle_status<>'WAITING_INBOUND'",
                            codec.hash(value))
                    .stream()
                    .map(Identification::fromRow)
                    .toList();
        }
        return db.queryForList(
                        "SELECT CAST(id AS CHAR) AS id,asset_code,name FROM fams_asset WHERE asset_code=?"
                            + " AND card_stage='ACTIVE' AND lifecycle_status<>'WAITING_INBOUND'",
                        value)
                .stream()
                .map(Identification::fromRow)
                .toList();
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
