package com.fams.inventory;

import com.fams.asset.AssetInventoryReviewPort;

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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validate durable acceptance lineage without changing a review or inventing an observation. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = InventoryFailure.class)
public class InventorySealEvidence {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final InventoryDifferenceService differences;

    public InventorySealEvidence(
            JdbcTemplate db, ObjectMapper json, InventoryDifferenceService differences) {
        this.db = db;
        this.json = json;
        this.differences = differences;
    }

    public void validate(
            Map<String, Object> task,
            Map<String, Object> item,
            Map<String, Object> review,
            AssetInventoryReviewPort.Context context) {
        if (review.isEmpty()) {
            fail("INVENTORY_SEAL_REVIEW_REQUIRED");
        }
        if (context == null) {
            fail("INVENTORY_SEAL_ASSET_UNAVAILABLE");
        }
        var raw =
                db.queryForList(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=? AND item_id=?"
                                + " AND recheck_generation=? ORDER BY id FOR SHARE",
                        task.get("id"),
                        item.get("id"),
                        item.get("recheck_generation"));
        if ("NOT_OBSERVED".equals(review.get("review_status"))) {
            var reasons =
                    db.queryForList(
                            "SELECT * FROM fams_inventory_unobserved_reason WHERE task_id=? AND"
                                    + " item_id=? AND recheck_generation=? FOR SHARE",
                            task.get("id"),
                            item.get("id"),
                            item.get("recheck_generation"));
            if (reasons.isEmpty()
                    || raw.stream().anyMatch(row -> !"EXPIRED".equals(row.get("outcome")))) {
                fail("INVENTORY_SEAL_UNOBSERVED_EVIDENCE_CHANGED");
            }
            if ("ASSET_NOT_FOUND".equals(reasons.getFirst().get("reason_kind"))) {
                fail("INVENTORY_SEAL_PHYSICAL_MISSING_OPEN");
            }
            return;
        }
        if (!"ACCEPTED".equals(review.get("review_status"))
                || review.get("verified_observed_at") == null) {
            fail("INVENTORY_SEAL_ACCEPTANCE_REQUIRED");
        }
        var selected =
                raw.stream()
                        .filter(row -> Objects.equals(row.get("id"), review.get("observation_id")))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new InventoryFailure(
                                                409, "INVENTORY_SEAL_OBSERVATION_REQUIRED"));
        if (!List.of(
                        "ACCEPTED",
                        "DIFFERENCE_REVIEW",
                        "CONFLICT_REVIEW",
                        "OLD_ASSIGNMENT_REVIEW",
                        "TIME_REVIEW")
                .contains(selected.get("outcome"))) {
            fail("INVENTORY_SEAL_OBSERVATION_INELIGIBLE");
        }
        var acceptedEvents =
                db.queryForList(
                        "SELECT * FROM fams_inventory_selection_event WHERE item_id=? AND"
                                + " recheck_generation=? AND review_version=? AND"
                                + " review_status='ACCEPTED' FOR SHARE",
                        item.get("id"),
                        item.get("recheck_generation"),
                        review.get("version"));
        Long selectedVersion = null;
        if (acceptedEvents.isEmpty()) {
            normal(item, review, selected, context);
        } else {
            var event = acceptedEvents.getFirst();
            selectedVersion = ((Number) review.get("version")).longValue() - 1;
            var predecessor =
                    db.queryForList(
                            "SELECT * FROM fams_inventory_selection_event WHERE item_id=? AND"
                                    + " recheck_generation=? AND review_version=? AND"
                                    + " review_status='SELECTED' FOR SHARE",
                            item.get("id"),
                            item.get("recheck_generation"),
                            selectedVersion);
            if (predecessor.isEmpty()
                    || !Objects.equals(
                            predecessor.getFirst().get("observation_id"),
                            review.get("observation_id"))
                    || !Objects.equals(event.get("observation_id"), review.get("observation_id"))
                    || !instant(event.get("verified_observed_at"))
                            .equals(instant(review.get("verified_observed_at")))
                    || !Objects.equals(
                            event.get("movement_digest"),
                            predecessor.getFirst().get("movement_digest"))
                    || !digest(context).equals(event.get("movement_digest"))) {
                fail("INVENTORY_SEAL_ACCEPTED_CONTEXT_CHANGED");
            }
        }
        // Receipt-time acceptance validated retention. Seal wall time must not expire that history.
        for (var observation : raw) {
            if (List.of("DUPLICATE", "EXPIRED").contains(observation.get("outcome"))) {
                continue;
            }
            if (!Objects.equals(observation.get("id"), review.get("observation_id"))) {
                if (selectedVersion == null
                        || db.queryForList(
                                        "SELECT observation_id FROM"
                                            + " fams_inventory_evidence_disposition WHERE"
                                            + " observation_id=? AND counter_observation_id=? AND"
                                            + " counter_review_version=? FOR SHARE",
                                        observation.get("id"),
                                        review.get("observation_id"),
                                        selectedVersion)
                                .isEmpty()) {
                    fail("INVENTORY_SEAL_UNDECIDED_EVIDENCE");
                }
            }
            subjects(task, observation);
        }
    }

    /** Historical and itemless obligations remain independently visible and nonwaivable. */
    public void subjects(Map<String, Object> task, Map<String, Object> observation) {
        if (List.of("DUPLICATE", "EXPIRED").contains(observation.get("outcome"))) {
            return;
        }
        if (observation.get("item_id") == null
                && "TIME_REVIEW".equals(observation.get("outcome"))) {
            fail("INVENTORY_SEAL_ITEMLESS_TIME_AND_FINDING_REQUIRED");
        }
        var rows =
                db.queryForList(
                        "SELECT dimension,subject_status FROM fams_inventory_review_subject WHERE"
                                + " observation_id=? FOR SHARE",
                        observation.get("id"));
        var required = differences.requiredDimensions(task, observation);
        if (!rows.stream()
                        .map(row -> row.get("dimension").toString())
                        .collect(java.util.stream.Collectors.toSet())
                        .containsAll(required)
                || rows.stream()
                        .anyMatch(
                                row ->
                                        !List.of("CLOSED", "TRANSFERRED")
                                                .contains(row.get("subject_status")))) {
            fail("INVENTORY_SEAL_UNRESOLVED_DIMENSIONS");
        }
    }

    private void normal(
            Map<String, Object> item,
            Map<String, Object> review,
            Map<String, Object> observation,
            AssetInventoryReviewPort.Context context) {
        if (!"ACCEPTED".equals(observation.get("outcome"))
                || observation.get("calibrated_observed_at") == null
                || !instant(observation.get("calibrated_observed_at"))
                        .equals(instant(review.get("verified_observed_at")))
                || !context.movements().isEmpty()
                || !physical(json.readTree(item.get("snapshot_json").toString()))
                        .equals(physical(json.readTree(context.snapshotJson())))) {
            fail("INVENTORY_SEAL_NORMAL_CONTEXT_CHANGED");
        }
        if (db.queryForList(
                        "SELECT observation_id FROM fams_inventory_review_subject WHERE"
                            + " observation_id=? AND dimension='NORMAL' AND subject_status='CLOSED'"
                            + " AND method='ACCEPT_NORMAL' FOR SHARE",
                        observation.get("id"))
                .isEmpty()) {
            fail("INVENTORY_SEAL_NORMAL_LINEAGE_REQUIRED");
        }
    }

    private Map<String, Object> physical(tools.jackson.databind.JsonNode snapshot) {
        var result = new java.util.TreeMap<String, Object>();
        for (String field :
                List.of(
                        "locationId",
                        "custodyType",
                        "responsibleEmployeeId",
                        "departmentResponsibleEmployeeId",
                        "usingOrganizationId",
                        "owningOrganizationId",
                        "lifecycle",
                        "exception",
                        "warehouseId")) {
            var node = snapshot.path(field);
            result.put(field, node.isMissingNode() || node.isNull() ? null : node.asText());
        }
        return result;
    }

    private String digest(AssetInventoryReviewPort.Context context) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (json.writeValueAsString(
                                                                    physical(
                                                                            json.readTree(
                                                                                    context
                                                                                            .snapshotJson())))
                                                            + "\n"
                                                            + json.writeValueAsString(
                                                                    context.movements()))
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Instant instant(Object value) {
        return value instanceof Timestamp time
                ? time.toInstant()
                : ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC);
    }

    private void fail(String code) {
        throw new InventoryFailure(409, code);
    }
}
