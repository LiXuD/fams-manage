package com.fams.inventory;

import com.fams.asset.AssetInventoryReviewPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Explicit, versioned evidence choices preserve every original collection fact and decision. */
@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.REPEATABLE_READ, noRollbackFor = InventoryReviewConflict.class)
public class InventorySelectionService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RequisitionActorContext actors;
    private final AssetInventoryReviewPort assets;
    private final Clock clock;

    public InventorySelectionService(
            JdbcTemplate db,
            ObjectMapper json,
            RequisitionActorContext actors,
            AssetInventoryReviewPort assets,
            Clock clock) {
        this.db = db;
        this.json = json;
        this.actors = actors;
        this.assets = assets;
        this.clock = clock;
    }

    public Map<String, Object> detail(Actor actor, long taskId, long itemId, int page, int size) {
        admin(actor);
        var task = task(taskId);
        var item = item(taskId, itemId);
        var context = context(task, item);
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_selection_event WHERE task_id=? AND item_id=?"
                                + " ORDER BY recheck_generation,review_version LIMIT ? OFFSET ? FOR"
                                + " SHARE",
                        taskId,
                        itemId,
                        size,
                        ((long) page - 1) * size);
        var result = new LinkedHashMap<String, Object>();
        result.put("taskId", Long.toString(taskId));
        result.put("taskVersion", task.get("version"));
        result.put("itemId", Long.toString(itemId));
        result.put("recheckGeneration", item.get("recheck_generation"));
        var currentReview = review(item);
        result.put("review", wire(currentReview.isEmpty() ? Map.of("version", 0) : currentReview));
        result.put("contextDigest", digest(context));
        result.put("movementEvents", context.movements());
        result.put("history", wire(rows));
        result.put("page", page);
        result.put("pageSize", size);
        result.put(
                "total",
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_selection_event WHERE task_id=?"
                                + " AND item_id=? FOR SHARE",
                        Long.class,
                        taskId,
                        itemId));
        return result;
    }

    public Map<String, Object> select(
            Actor actor,
            long taskId,
            long itemId,
            long version,
            UUID key,
            InventorySelectionInput input) {
        var current = admin(actor);
        var task = task(taskId);
        String hash = hash(version + "\n" + itemId + "\n" + json.writeValueAsString(input));
        var receipts =
                db.queryForList(
                        "SELECT payload_sha256,CAST(response_json AS CHAR) AS response FROM"
                            + " fams_inventory_review_receipt WHERE task_id=? AND"
                            + " actor_account_id=? AND operation='SELECT_EVIDENCE' AND request_id=?"
                            + " FOR UPDATE",
                        taskId,
                        current.id(),
                        key.toString());
        if (!receipts.isEmpty()) {
            if (!hash.equals(receipts.getFirst().get("payload_sha256"))) {
                reject(actor, current, task, "INVENTORY_REVIEW_IDEMPOTENCY_CONFLICT", Map.of());
            }
            return json.readValue(
                    receipts.getFirst().get("response").toString(),
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        }
        var item = item(taskId, itemId);
        var prior = review(item);
        long reviewVersion = prior.isEmpty() ? 0 : number(prior, "version");
        if (number(item, "recheck_generation") != input.recheckGeneration()
                || reviewVersion != input.reviewVersion()
                || (!prior.isEmpty() && !"SELECTED".equals(prior.get("review_status")))) {
            reject(actor, current, task, "INVENTORY_REVIEW_VERSION_CONFLICT", prior);
        }
        if (number(task, "version") != version
                || !List.of("IN_PROGRESS", "WAITING_REVIEW").contains(task.get("task_status"))) {
            reject(actor, current, task, "INVENTORY_REVIEW_TASK_CHANGED", prior);
        }
        if (!assets.matchesVersion(number(item, "asset_id"), input.assetVersion())) {
            reject(actor, current, task, "INVENTORY_REVIEW_ASSET_CHANGED", prior);
        }
        var context = context(task, item);
        if (!digest(context).equals(input.contextDigest())) {
            reject(actor, current, task, "INVENTORY_REVIEW_CONTEXT_CHANGED", prior);
        }
        var observations =
                db.queryForList(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=?"
                                + " AND item_id=? AND id=? AND recheck_generation=? FOR SHARE",
                        taskId,
                        itemId,
                        input.observationId(),
                        input.recheckGeneration());
        if (observations.isEmpty()) {
            throw new InventoryFailure(404, "INVENTORY_OBSERVATION_NOT_FOUND");
        }
        var observation = observations.getFirst();
        String outcome = observation.get("outcome").toString();
        if (!List.of(
                        "ACCEPTED",
                        "DIFFERENCE_REVIEW",
                        "CONFLICT_REVIEW",
                        "OLD_ASSIGNMENT_REVIEW",
                        "TIME_REVIEW")
                .contains(outcome)) {
            throw new InventoryFailure(409, "INVENTORY_SELECTION_EVIDENCE_INELIGIBLE");
        }
        boolean verified = "TIME_REVIEW".equals(outcome);
        Instant selectedTime;
        if (verified) {
            selectedTime = input.verifiedObservedAt();
        } else {
            if (input.verifiedObservedAt() != null) {
                throw new InventoryFailure(400, "INVENTORY_SELECTION_RETIME_FORBIDDEN");
            }
            selectedTime =
                    observation.get("calibrated_observed_at") == null
                            ? null
                            : instant(observation.get("calibrated_observed_at"));
        }
        if (selectedTime == null
                || !selectedTime.equals(selectedTime.truncatedTo(ChronoUnit.MILLIS))
                || selectedTime.isBefore(instant(task.get("published_at")))
                || selectedTime.isAfter(instant(observation.get("received_at")))
                || selectedTime.isAfter(clock.instant())) {
            throw new InventoryFailure(400, "INVENTORY_SELECTION_TIME_INVALID");
        }
        long after = reviewVersion + 1;
        if (prior.isEmpty()) {
            db.update(
                    "INSERT INTO"
                        + " fams_inventory_item_review(item_id,recheck_generation,task_id,observation_id,"
                        + "review_status,verified_observed_at,reviewed_asset_version,reason,evidence,reviewer_account_id,reviewer_username,reviewed_at,version)"
                        + " VALUES (?,?,?,?,'SELECTED',?,?,?,?,?,?,?,?)",
                    itemId,
                    input.recheckGeneration(),
                    taskId,
                    input.observationId(),
                    Timestamp.from(selectedTime),
                    input.assetVersion(),
                    input.reason(),
                    input.evidence(),
                    current.id(),
                    current.username(),
                    now(),
                    after);
        } else {
            db.update(
                    "UPDATE fams_inventory_item_review SET observation_id=?,verified_observed_at=?,"
                        + "reviewed_asset_version=?,reason=?,evidence=?,reviewer_account_id=?,reviewer_username=?,reviewed_at=?,version=?"
                        + " WHERE item_id=? AND recheck_generation=? AND version=?",
                    input.observationId(),
                    Timestamp.from(selectedTime),
                    input.assetVersion(),
                    input.reason(),
                    input.evidence(),
                    current.id(),
                    current.username(),
                    now(),
                    after,
                    itemId,
                    input.recheckGeneration(),
                    reviewVersion);
        }
        db.update(
                "INSERT INTO"
                    + " fams_inventory_selection_event(item_id,recheck_generation,review_version,task_id,"
                    + "observation_id,review_status,verified_observed_at,time_source,reviewed_asset_version,"
                    + "physical_snapshot_json,movement_digest,movement_count,reason,evidence,actor_account_id,actor_username,recorded_at)"
                    + " VALUES (?,?,?,?,?,'SELECTED',?,?,?,?,?,?,?,?,?,?,?)",
                itemId,
                input.recheckGeneration(),
                after,
                taskId,
                input.observationId(),
                Timestamp.from(selectedTime),
                verified ? "ADMIN_VERIFIED" : "CALIBRATED",
                input.assetVersion(),
                json.writeValueAsString(physical(context)),
                digest(context),
                context.movements().size(),
                input.reason(),
                input.evidence(),
                current.id(),
                current.username(),
                now());
        db.update(
                "UPDATE fams_inventory_task SET version=version+1,updated_at=? WHERE id=?",
                now(),
                taskId);
        var response =
                Map.<String, Object>of(
                        "taskId",
                        Long.toString(taskId),
                        "itemId",
                        Long.toString(itemId),
                        "version",
                        version + 1,
                        "review",
                        wire(review(item)),
                        "contextDigest",
                        digest(context));
        audit(actor, current, task, "INVENTORY_EVIDENCE_SELECTED", response);
        db.update(
                "INSERT INTO"
                    + " fams_inventory_review_receipt(task_id,actor_account_id,operation,request_id,payload_sha256,response_json,created_at)"
                    + " VALUES (?,?,'SELECT_EVIDENCE',?,?,?,?)",
                taskId,
                current.id(),
                key.toString(),
                hash,
                json.writeValueAsString(response),
                now());
        return response;
    }

    private AssetInventoryReviewPort.Context context(
            Map<String, Object> task, Map<String, Object> item) {
        var value =
                assets.contexts(
                                List.of(number(item, "asset_id")),
                                instant(task.get("published_at")))
                        .get(number(item, "asset_id"));
        if (value == null) {
            throw new InventoryFailure(409, "INVENTORY_REVIEW_ASSET_UNAVAILABLE");
        }
        return value;
    }

    private Map<String, Object> physical(AssetInventoryReviewPort.Context context) {
        var tree = json.readTree(context.snapshotJson());
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
            var node = tree.path(field);
            result.put(field, node.isMissingNode() || node.isNull() ? null : node.asText());
        }
        return result;
    }

    private String digest(AssetInventoryReviewPort.Context context) {
        // Record component order and sorted physical keys are deterministic across fresh JVMs.
        return hash(
                json.writeValueAsString(physical(context))
                        + "\n"
                        + json.writeValueAsString(context.movements()));
    }

    private Current admin(Actor actor) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(Role.ASSET_ADMIN)) {
            throw new InventoryFailure(403, "INVENTORY_REVIEW_ADMIN_REQUIRED");
        }
        return current;
    }

    private Map<String, Object> task(long id) {
        var rows = db.queryForList("SELECT * FROM fams_inventory_task WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()
                || List.of("DRAFT", "DELETED").contains(rows.getFirst().get("task_status"))) {
            throw new InventoryFailure(404, "INVENTORY_REVIEW_TASK_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private Map<String, Object> item(long task, long item) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_item WHERE task_id=? AND id=? FOR UPDATE",
                        task,
                        item);
        if (rows.isEmpty()) {
            throw new InventoryFailure(404, "INVENTORY_ITEM_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private Map<String, Object> review(Map<String, Object> item) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_item_review WHERE item_id=? AND"
                                + " recheck_generation=? FOR UPDATE",
                        item.get("id"),
                        item.get("recheck_generation"));
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private void reject(
            Actor actor,
            Current current,
            Map<String, Object> task,
            String code,
            Map<String, Object> review) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("taskVersion", task.get("version").toString());
        fields.put("readOnlyUrl", "/inventory-reviews/tasks/" + task.get("id"));
        fields.put(
                "result", review.getOrDefault("review_status", task.get("task_status")).toString());
        if (!review.isEmpty()) {
            fields.put("processedBy", review.get("reviewer_username").toString());
            fields.put("processedAt", instant(review.get("reviewed_at")).toString());
            fields.put("reviewVersion", review.get("version").toString());
        }
        audit(
                actor,
                current,
                task,
                "INVENTORY_SELECTION_CONFLICT_REJECTED",
                Map.of("code", code, "fields", fields));
        throw new InventoryReviewConflict(code, fields);
    }

    private void audit(
            Actor actor, Current current, Map<String, Object> task, String action, Object detail) {
        String body = json.writeValueAsString(detail);
        db.update(
                "INSERT INTO"
                    + " fams_inventory_task_event(task_id,actor_account_id,action,detail_json,occurred_at)"
                    + " VALUES (?,?,?,?,?)",
                task.get("id"),
                current.id(),
                action,
                body,
                now());
        db.update(
                "INSERT INTO"
                    + " fams_audit_event(request_id,actor_account_id,actor_role,action,resource_type,resource_id,before_version,after_version,occurred_at,detail_json)"
                    + " VALUES (?,?,'ASSET_ADMIN',?,'INVENTORY_TASK',?,?,?,?,?)",
                actor.requestId().toString(),
                current.id(),
                action,
                task.get("id"),
                task.get("version"),
                db.queryForObject(
                        "SELECT version FROM fams_inventory_task WHERE id=?",
                        Long.class,
                        task.get("id")),
                now(),
                body);
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            value.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Object wire(Object value) {
        if (value instanceof Map<?, ?> row) {
            var result = new LinkedHashMap<String, Object>();
            row.forEach(
                    (key, child) -> {
                        String name = key.toString();
                        Object data =
                                name.endsWith("_json") && child instanceof String text
                                        ? json.readValue(text, Object.class)
                                        : child;
                        result.put(
                                name,
                                data instanceof Number
                                                && (name.equals("id")
                                                        || name.endsWith("_id")
                                                        || name.endsWith("Id"))
                                        ? data.toString()
                                        : wire(data));
                    });
            return result;
        }
        if (value instanceof List<?> rows) {
            return rows.stream().map(this::wire).toList();
        }
        if (value instanceof Timestamp time) {
            return time.toInstant().toString();
        }
        if (value instanceof java.time.LocalDateTime time) {
            return time.toInstant(java.time.ZoneOffset.UTC).toString();
        }
        return value;
    }

    private long number(Map<String, Object> row, String name) {
        return ((Number) row.get(name)).longValue();
    }

    private Instant instant(Object value) {
        return value instanceof Timestamp time
                ? time.toInstant()
                : ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS));
    }
}
