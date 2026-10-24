package com.fams.inventory;

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
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public InventoryLateEvidencePort(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public void append(Map<String, Object> task, long observationId) {
        if (!List.of("COMPLETED", "CANCELLED", "TERMINATED").contains(task.get("task_status"))) {
            throw new IllegalArgumentException("Terminal inventory task required");
        }
        var raw =
                db.queryForMap(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=? AND id=? FOR"
                                + " SHARE",
                        task.get("id"),
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
                        task.get("id"));
        var packet = new LinkedHashMap<String, Object>();
        packet.put("taskId", task.get("id").toString());
        packet.put("terminalStatus", task.get("task_status"));
        packet.put("taskVersion", task.get("version"));
        packet.put("observationId", Long.toString(observationId));
        packet.put("classificationContext", "TERMINAL_TASK_AT_REGISTRATION");
        Long assetId =
                raw.get("item_id") == null
                        ? null
                        : db.queryForObject(
                                "SELECT asset_id FROM fams_inventory_item WHERE task_id=? AND id=?"
                                        + " FOR SHARE",
                                Long.class,
                                task.get("id"),
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
                                task.get("id"),
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
                                task.get("id"),
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
                            task.get("id"));
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
        boolean needsReview =
                !"FACTS_MATCH".equals(kind)
                        || InventoryLatePolicy.requiresRecheck(
                                kind, json.readTree(raw.get("facts_json").toString()));
        db.update(
                "INSERT INTO"
                    + " fams_inventory_late_case(observation_id,task_id,comparison_kind,needs_review,report_sha256,accepted_observation_id,snapshot_json,snapshot_sha256,created_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?)",
                observationId,
                task.get("id"),
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
                        + " fams_todo(pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,opened_at)"
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
                        + " fams_todo(pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,opened_at)"
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
