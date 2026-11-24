package com.fams.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Record terminal supplemental evidence under the uploader's existing task lock and transaction.
 */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryLateEvidencePort {
    private static final Logger LOG = LoggerFactory.getLogger(InventoryLateEvidencePort.class);
    /**
     * Narrow task identity the evidence chain reads (JAVA-10c): id and version are BIGINT columns
     * bound into FOR SHARE/INSERT statements verbatim as before and projected into the stored
     * fams_inventory_late_case.snapshot_json packet keys exactly like the previous raw row Map.
     */
    public record Task(long id, String taskStatus, long version) {
        /**
         * Projects the three identity columns out of a raw fams_inventory_task row so callers keep
         * passing whole {@code Map<String,Object>} rows around without widening the stored-shape
         * contract. The BIGINT id and version are narrowed to long exactly as the previous raw-row
         * reads did, and task_status stays the literal column value the terminal-status check
         * compares against.
         *
         * @param row raw fams_inventory_task row containing id, task_status and version
         * @return narrow task identity carrying only the columns the evidence chain reads
         */
        public static Task fromRow(Map<String, Object> row) {
            return new Task(
                    ((Number) row.get("id")).longValue(),
                    (String) row.get("task_status"),
                    ((Number) row.get("version")).longValue());
        }
    }

    private final JdbcTemplate db;
    private final ObjectMapper json;

    /**
     * Wires the port. It runs inside the caller's transaction because the late case must commit or
     * roll back together with the observation row that triggered it, so no transaction manager is
     * injected here.
     *
     * @param db JDBC template used to read observations, items, reviews and sealed reports and to
     *     insert the late case, todo and late-damage rows
     * @param json mapper used to re-read facts_json, to serialize the snapshot packet and to project
     *     the stored review row into its wire shape
     */
    public InventoryLateEvidencePort(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    /**
     * Registers one observation that arrived after its task reached a terminal status. The row is
     * supplemental only: it never reopens the sealed report, never proves the original sealing
     * condition and is inserted with the observation's own received_at so the audit timeline stays
     * monotonic. The comparison kind is derived from the frozen or sealed state of the item
     * (TIME_UNVERIFIED, FINDING, NO_ACCEPTED, FACTS_MATCH or FROZEN_CONFLICT) and drives whether an
     * ASSET_ADMIN late-evidence review todo is opened; a DAMAGED condition additionally opens a
     * late-damage row and its own repair todo. An EXPIRED observation or an observation that already
     * has a late case returns without writing, which makes the call idempotent under retries.
     *
     * @param task terminal task identity already locked by the caller's transaction
     * @param observationId id of the freshly inserted fams_inventory_observation row to register
     * @throws IllegalArgumentException when the task is not COMPLETED, CANCELLED or TERMINATED, or
     *     when the observation outcome is neither TIME_REVIEW nor LATE_REVIEW, because only those two
     *     are supplemental evidence for an already closed task
     */
    public void append(Task task, long observationId) {
        if (!List.of("COMPLETED", "CANCELLED", "TERMINATED").contains(task.taskStatus())) {
            throw new IllegalArgumentException("Terminal inventory task required");
        }
        var raw =
                db.queryForMap(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=? AND id=? FOR"
                                + " SHARE",
                        task.id(),
                        observationId);
        if ("EXPIRED".equals(raw.get("outcome"))) {
            return;
        }
        if (!List.of("TIME_REVIEW", "LATE_REVIEW").contains(raw.get("outcome"))) {
            throw new IllegalArgumentException("Terminal supplemental outcome required");
        }
        if (!db.queryForList(
                        "SELECT observation_id FROM fams_inventory_late_case WHERE observation_id=?"
                                + " FOR SHARE",
                        observationId)
                .isEmpty()) {
            return;
        }
        var reports =
                db.queryForList(
                        "SELECT report_sha256,created_at FROM fams_inventory_sealed_report WHERE"
                                + " task_id=? FOR SHARE",
                        task.id());
        var packet = new LinkedHashMap<String, Object>();
        packet.put("taskId", Long.toString(task.id()));
        packet.put("terminalStatus", task.taskStatus());
        packet.put("taskVersion", task.version());
        packet.put("observationId", Long.toString(observationId));
        packet.put("classificationContext", "TERMINAL_TASK_AT_REGISTRATION");
        Long assetId =
                raw.get("item_id") == null
                        ? null
                        : db.queryForObject(
                                "SELECT asset_id FROM fams_inventory_item WHERE task_id=? AND id=?"
                                        + " FOR SHARE",
                                Long.class,
                                task.id(),
                                raw.get("item_id"));
        packet.put("assetId", assetId == null ? null : assetId.toString());
        packet.put("rawObservedAt", instant(raw.get("raw_observed_at")));
        packet.put("calibratedObservedAt", instant(raw.get("calibrated_observed_at")));
        packet.put("receivedAt", instant(raw.get("received_at")));
        packet.put("outcome", raw.get("outcome"));
        packet.put("facts", json.readValue(raw.get("facts_json").toString(), Object.class));
        packet.put(
                "sealedAt",
                reports.isEmpty() ? null : instant(reports.getFirst().get("created_at")));
        String reportHash =
                reports.isEmpty() ? null : reports.getFirst().get("report_sha256").toString();
        packet.put("reportSha256", reportHash);
        Long acceptedId = null;
        if (raw.get("item_id") != null) {
            if (!reports.isEmpty()) {
                var frozen =
                        db.queryForList(
                                "SELECT snapshot_json FROM fams_inventory_sealed_item WHERE"
                                        + " task_id=? AND item_id=? FOR SHARE",
                                task.id(),
                                raw.get("item_id"));
                if (!frozen.isEmpty()) {
                    var snapshot = json.readTree(frozen.getFirst().get("snapshot_json").toString());
                    packet.put(
                            "frozenItem",
                            json.readValue(
                                    frozen.getFirst().get("snapshot_json").toString(),
                                    Object.class));
                    if ("ACCEPTED".equals(snapshot.path("review").path("review_status").asText())) {
                        acceptedId =
                                Long.valueOf(
                                        snapshot.path("review").path("observation_id").asText());
                    }
                }
            } else {
                var reviews =
                        db.queryForList(
                                "SELECT * FROM fams_inventory_item_review WHERE task_id=? AND"
                                        + " item_id=? AND review_status='ACCEPTED' ORDER BY"
                                        + " recheck_generation DESC LIMIT 1 FOR SHARE",
                                task.id(),
                                raw.get("item_id"));
                if (!reviews.isEmpty()) {
                    acceptedId = ((Number) reviews.getFirst().get("observation_id")).longValue();
                    packet.put("acceptedReviewAtTerminal", wire(reviews.getFirst()));
                }
            }
        }
        String kind;
        if ("TIME_REVIEW".equals(raw.get("outcome"))) {
            kind = "TIME_UNVERIFIED";
        } else if (raw.get("item_id") == null) {
            kind = "FINDING";
        } else if (acceptedId == null) {
            kind = "NO_ACCEPTED";
        } else {
            String acceptedHash =
                    db.queryForObject(
                            "SELECT fact_sha256 FROM fams_inventory_observation WHERE id=? AND"
                                    + " task_id=? FOR SHARE",
                            String.class,
                            acceptedId,
                            task.id());
            kind =
                    acceptedHash != null && acceptedHash.equals(raw.get("fact_sha256"))
                            ? "FACTS_MATCH"
                            : "FROZEN_CONFLICT";
        }
        packet.put("acceptedObservationId", acceptedId == null ? null : acceptedId.toString());
        packet.put("comparisonKind", kind);
        packet.put("supplementOnly", true);
        packet.put("provesOriginalSealCondition", false);
        String snapshot = json.writeValueAsString(packet);
        LOG.debug("Recording late supplemental evidence rows for task {} observation {}.", task.id(), observationId);
        boolean needsReview =
                !"FACTS_MATCH".equals(kind)
                        || InventoryLatePolicy.requiresRecheck(
                                kind, json.readTree(raw.get("facts_json").toString()));
        db.update(
                "INSERT INTO"
                    + " fams_inventory_late_case(observation_id,task_id,comparison_kind,needs_review,report_sha256,"
                    + "accepted_observation_id,snapshot_json,snapshot_sha256,created_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?)",
                observationId,
                task.id(),
                kind,
                needsReview,
                reportHash,
                acceptedId,
                snapshot,
                hash(snapshot),
                raw.get("received_at"));
        if (needsReview) {
            db.update(
                    "INSERT INTO"
                        + " fams_todo(pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,"
                        + "opened_at)"
                        + " VALUES"
                        + " ('ASSET_ADMIN',NULL,'INVENTORY_LATE',?,'LATE_EVIDENCE_REVIEW','OPEN',?)"
                        + " ON DUPLICATE KEY UPDATE id=id",
                    observationId,
                    raw.get("received_at"));
        }
        if (assetId != null
                && "DAMAGED"
                        .equals(
                                json.readTree(raw.get("facts_json").toString())
                                        .path("actual")
                                        .path("condition")
                                        .asText())) {
            db.update(
                    "INSERT INTO fams_inventory_late_damage(observation_id,created_at,updated_at)"
                        + " VALUES (?,?,?)",
                    observationId,
                    raw.get("received_at"),
                    raw.get("received_at"));
            db.update(
                    "INSERT INTO"
                        + " fams_todo(pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,"
                        + "opened_at)"
                        + " VALUES"
                        + " ('ASSET_ADMIN',NULL,'INVENTORY_LATE',?,'LATE_DAMAGE_REPAIR','OPEN',?)",
                    observationId,
                    raw.get("received_at"));
        }
    }

    private Map<String, Object> wire(Map<String, Object> source) {
        var target = new LinkedHashMap<String, Object>();
        source.forEach(
                (key, value) ->
                        target.put(
                                key,
                                value == null
                                        ? null
                                        : key.equals("id") || key.endsWith("_id")
                                                ? value.toString()
                                                : value instanceof Timestamp
                                                                || value
                                                                        instanceof
                                                                        java.time.LocalDateTime
                                                        ? instant(value)
                                                        : value));
        return target;
    }

    private String instant(Object value) {
        if (value == null) {
            return null;
        }
        return (value instanceof Timestamp time
                        ? time.toInstant()
                        : ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC))
                .toString();
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
