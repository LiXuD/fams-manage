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
    /**
     * One asset movement since the inventory source was published. The timeline event id is
     * stringified because it is only an opaque ordering key for the review UI; {@code type} is the
     * fams_asset_timeline_event.event_type and {@code occurredAt} the instant it was recorded.
     * Pure bookkeeping event types are filtered out before this record is built, so a movement
     * always means something physically changed.
     *
     * @param id timeline event id as an opaque string
     * @param type event type recorded on fams_asset_timeline_event
     * @param occurredAt instant the movement occurred
     */
    public record Movement(String id, String type, Instant occurredAt) {}

    /**
     * Review context of a single asset: the published source snapshot plus everything that moved
     * afterwards. The snapshot is returned as the raw stored JSON text so the reviewer sees the
     * exact bytes that were frozen at publication, and the movement list lets the review decide
     * whether the snapshot is still trustworthy.
     *
     * @param snapshotJson verbatim fams_asset_inventory_source_v1.snapshot_json text
     * @param movements ordered movements recorded at or after the publication instant
     */
    public record Context(String snapshotJson, List<Movement> movements) {
        /**
         * Freezes the movement list of one review context. The copy keeps the published context
         * immutable against later mutation by the caller's collection.
         *
         * @param movements movements to retain; copied, never kept by reference
         */
        public Context {
            movements = List.copyOf(movements);
        }
    }

    private final JdbcTemplate db;

    /**
     * Wires the review port to raw JDBC. The class-level {@code MANDATORY} propagation makes the
     * caller own the transaction, so the {@code FOR SHARE} reads taken here stay consistent with
     * the rest of the inventory review instead of opening a separate read-only unit of work.
     *
     * @param db JDBC handle used for the shared-lock snapshot, movement and version reads
     */
    public AssetInventoryReviewPort(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * Loads the review context for a batch of assets in one pass. Both queries take
     * {@code FOR SHARE} locks in ascending asset id order, which is the same order the seal path
     * uses for its exclusive locks, so a concurrent seal cannot deadlock against a review. Assets
     * without a published snapshot are simply absent from the result. Movements are limited to
     * those recorded at or after {@code publishedAt} and exclude bookkeeping-only event types.
     *
     * @param assets fams_asset primary keys to review; at most 200, all positive, duplicates allowed
     * @param publishedAt instant from which movements count as post-publication changes
     * @return immutable map of asset id to its context, empty when {@code assets} is empty
     * @throws IllegalArgumentException when the batch exceeds 200 ids or contains a null or
     *     non-positive id
     */
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

    /**
     * Confirms under an exclusive lock that one asset is still active at the version the reviewer
     * read. The row lock is deliberately taken here so the review cannot publish a decision against
     * a card that another writer is mid-flight on; a cancelled or version-drifted card reports a
     * mismatch instead of throwing.
     *
     * @param asset fams_asset primary key to lock and compare
     * @param expectedVersion optimistic version the reviewer read earlier
     * @return {@code true} only when the row exists, its card stage is ACTIVE and the stored version
     *     equals {@code expectedVersion}
     */
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
