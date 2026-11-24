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

    /**
     * Wires the read-only lineage validator. It holds no state of its own and reuses the difference
     * service purely for its {@code requiredDimensions} derivation, so the sealing check and the
     * review-time preparation always agree on which dimensions an observation owes.
     *
     * @param db JDBC template used to read observations, review subjects, selection events and
     *     unobserved reasons under FOR SHARE
     * @param json mapper used to re-read stored snapshots and to compute the movement digest
     * @param differences source of the authoritative required-dimension derivation
     */
    public InventorySealEvidence(
            JdbcTemplate db, ObjectMapper json, InventoryDifferenceService differences) {
        this.db = db;
        this.json = json;
        this.differences = differences;
    }

    /**
     * Proves that one item's stored review lineage still supports the conclusion about to be sealed,
     * without writing anything. A NOT_OBSERVED review must have a stored unobserved reason and only
     * EXPIRED observations; any other review must be ACCEPTED with a verified observation time and
     * must be traceable either to a closed NORMAL subject with an unmoved physical snapshot, or to a
     * SELECTED/ACCEPTED selection-event pair whose movement digest still equals the live asset
     * context. Every non-selected observation of the item must additionally carry a disposition for
     * the selected review version, and all its subjects must be CLOSED or TRANSFERRED.
     *
     * @param task locked fams_inventory_task row supplying the task id for the dimension derivation
     * @param item locked fams_inventory_item row whose snapshot_json and recheck_generation are
     *     compared against the live asset context
     * @param review fams_inventory_item_review row of the current recheck generation, empty when the
     *     item was never reviewed
     * @param context live asset context and movement list read through the asset review port, null
     *     when the asset card is no longer available
     * @throws InventoryFailure with a 409 status and one of the INVENTORY_SEAL_* codes when any part
     *     of the lineage is missing, stale or contradictory
     */
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
            var links =
                    db.queryForList(
                            "SELECT l.counter_observation_id FROM"
                                + " fams_inventory_finding_recollection l JOIN"
                                + " fams_inventory_review_subject s ON"
                                + " s.observation_id=l.observation_id AND s.dimension='TIME' AND"
                                + " s.version=l.decision_version AND s.subject_status='CLOSED' AND"
                                + " s.method='RECOLLECTED' WHERE l.observation_id=? FOR SHARE",
                            observation.get("id"));
            if (links.isEmpty()) {
                fail("INVENTORY_SEAL_ITEMLESS_TIME_AND_FINDING_REQUIRED");
            }
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
