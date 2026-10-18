package com.fams.asset;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The caller authorizes inventory review; asset data and movement history remain asset-owned. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class AssetInventoryReviewPort {
    public record Movement(String id, String type, Instant occurredAt) {}

    public record Context(String snapshotJson, List<Movement> movements) {
        public Context {
            movements = List.copyOf(movements);
        }
    }

    private final JdbcTemplate db;

    public AssetInventoryReviewPort(JdbcTemplate db) {
        this.db = db;
    }

    public Map<Long, Context> contexts(List<Long> assets, Instant publishedAt) {
        if (assets.isEmpty()) {
            return Map.of();
        }
        if (assets.size() > 200 || assets.stream().anyMatch(id -> id == null || id < 1)) {
            throw new IllegalArgumentException("Inventory asset batch invalid");
        }
        var sorted = assets.stream().distinct().sorted().toList();
        String marks = String.join(",", java.util.Collections.nCopies(sorted.size(), "?"));
        var rows =
                db.queryForList(
                        "SELECT asset_id,CAST(snapshot_json AS CHAR) AS snapshot FROM"
                                + " fams_asset_inventory_source_v1 WHERE asset_id IN ("
                                + marks
                                + ") ORDER BY asset_id FOR SHARE",
                        sorted.toArray());
        var args = new ArrayList<Object>(sorted);
        args.add(Timestamp.from(publishedAt));
        var movements =
                db.queryForList(
                        "SELECT id,asset_id,event_type,occurred_at FROM fams_asset_timeline_event"
                            + " WHERE asset_id IN ("
                                + marks
                                + ") AND occurred_at>=? AND event_type NOT IN"
                                + " ('ASSET_UPDATED','ASSET_TAG_REPLACED','ASSET_TAG_REPRINT_REQUESTED',"
                                + " 'ASSET_TAG_BATCH_PRINT_REQUESTED') ORDER BY asset_id,id FOR"
                                + " SHARE",
                        args.toArray());
        Map<Long, Context> result = new LinkedHashMap<>();
        for (var row : rows) {
            long id = ((Number) row.get("asset_id")).longValue();
            var history =
                    movements.stream()
                            .filter(event -> ((Number) event.get("asset_id")).longValue() == id)
                            .map(
                                    event ->
                                            new Movement(
                                                    event.get("id").toString(),
                                                    event.get("event_type").toString(),
                                                    instant(event.get("occurred_at"))))
                            .toList();
            result.put(id, new Context(row.get("snapshot").toString(), history));
        }
        return Map.copyOf(result);
    }

    public boolean matchesVersion(long asset, long expectedVersion) {
        var rows =
                db.queryForList(
                        "SELECT version,card_stage FROM fams_asset WHERE id=? FOR UPDATE", asset);
        return !rows.isEmpty()
                && "ACTIVE".equals(rows.getFirst().get("card_stage"))
                && ((Number) rows.getFirst().get("version")).longValue() == expectedVersion;
    }

    private Instant instant(Object value) {
        return value instanceof Timestamp timestamp
                ? timestamp.toInstant()
                : ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC);
    }
}
