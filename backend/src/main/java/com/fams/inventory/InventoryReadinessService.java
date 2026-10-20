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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Missing evidence remains explicit and never becomes a successful physical observation. */
@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.REPEATABLE_READ, noRollbackFor = InventoryReviewConflict.class)
public class InventoryReadinessService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RequisitionActorContext actors;
    private final AssetInventoryReviewPort assets;
    private final Clock clock;

    public InventoryReadinessService(
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

    public Map<String, Object> detail(Actor actor, long taskId, int page, int size) {
        admin(actor);
        var task = task(taskId);
        var items =
                db.queryForList(
                        "SELECT i.*,r.review_status,r.version AS review_version,"
                            + "n.reason_kind,n.reason,n.evidence,n.actor_username,n.recorded_at,(SELECT"
                            + " COUNT(*) FROM fams_inventory_observation o WHERE"
                            + " o.task_id=i.task_id AND o.item_id=i.id AND"
                            + " o.recheck_generation=i.recheck_generation AND o.outcome<>'EXPIRED')"
                            + " AS valid_raw_count FROM fams_inventory_item i LEFT JOIN"
                            + " fams_inventory_item_review r ON r.item_id=i.id AND"
                            + " r.recheck_generation=i.recheck_generation LEFT JOIN"
                            + " fams_inventory_unobserved_reason n ON n.item_id=i.id AND"
                            + " n.recheck_generation=i.recheck_generation WHERE i.task_id=? ORDER"
                            + " BY i.asset_id LIMIT ? OFFSET ?",
                        taskId,
                        size,
                        (page - 1) * size);
        var contexts =
                assets.contexts(
                        items.stream().map(row -> number(row, "asset_id")).toList(),
                        instant(task.get("published_at")));
        var rows =
                items.stream()
                        .map(
                                row -> {
                                    var value = new LinkedHashMap<String, Object>();
                                    value.put("id", row.get("id").toString());
                                    value.put("item", wire(row));
                                    var context = contexts.get(number(row, "asset_id"));
                                    value.put(
                                            "current",
                                            context == null
                                                    ? null
                                                    : json.readValue(
                                                            context.snapshotJson(), Object.class));
                                    value.put(
                                            "canRecord",
                                            context != null
                                                    && "IN_PROGRESS".equals(task.get("task_status"))
                                                    && row.get("review_status") == null
                                                    && number(row, "valid_raw_count") == 0);
                                    value.put(
                                            "physicalMissingOpen",
                                            "ASSET_NOT_FOUND".equals(row.get("reason_kind")));
                                    return value;
                                })
                        .toList();
        return Map.of(
                "task",
                wire(task),
                "items",
                rows,
                "total",
                count(taskId),
                "page",
                page,
                "pageSize",
                size,
                "missingCount",
                missing(taskId),
                "notObservedCount",
                notObserved(taskId));
    }

    public Map<String, Object> history(Actor actor, long taskId, long itemId, int page, int size) {
        admin(actor);
        task(taskId);
        item(taskId, itemId);
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_unobserved_reason WHERE task_id=? AND"
                                + " item_id=? ORDER BY recheck_generation DESC LIMIT ? OFFSET ?",
                        taskId,
                        itemId,
                        size,
                        (page - 1) * size);
        long total =
                Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM fams_inventory_unobserved_reason WHERE"
                                        + " task_id=? AND item_id=?",
                                Long.class,
                                taskId,
                                itemId));
        return Map.of(
                "items",
                rows.stream().map(this::wire).toList(),
                "total",
                total,
                "page",
                page,
                "pageSize",
                size);
    }

    public Map<String, Object> record(
            Actor actor,
            long taskId,
            long itemId,
            long version,
            UUID key,
            InventoryReadinessInput.Unobserved input) {
        var current = admin(actor);
        var task = task(taskId);
        String operation = "UNOBSERVED:" + itemId;
        String digest = hash(version + ":" + itemId + ":" + json.writeValueAsString(input));
        var prior = original(actor, current, task, operation, key, digest);
        if (prior != null) {
            return prior;
        }
        var item = item(taskId, itemId);
        var review =
                db.queryForList(
                        "SELECT * FROM fams_inventory_item_review WHERE item_id=? AND"
                                + " recheck_generation=? FOR UPDATE",
                        itemId,
                        item.get("recheck_generation"));
        if (!review.isEmpty()) {
            conflict(actor, current, task, "INVENTORY_REVIEW_VERSION_CONFLICT", review.getFirst());
        }
        if (number(task, "version") != version
                || !"IN_PROGRESS".equals(task.get("task_status"))
                || number(item, "recheck_generation") != input.recheckGeneration()) {
            conflict(actor, current, task, "INVENTORY_READINESS_CONTEXT_CHANGED", Map.of());
        }
        if (!List.of("UNABLE_TO_OBSERVE", "EVIDENCE_UNAVAILABLE", "ASSET_NOT_FOUND")
                .contains(input.kind())) {
            throw new InventoryFailure(400, "INVENTORY_UNOBSERVED_KIND_INVALID");
        }
        if (input.reason().strip().length() < 3 || input.evidence().strip().length() < 10) {
            throw new InventoryFailure(400, "INVENTORY_UNOBSERVED_BASIS_REQUIRED");
        }
        long observations =
                Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM fams_inventory_observation WHERE task_id=?"
                                        + " AND item_id=? AND recheck_generation=? AND"
                                        + " outcome<>'EXPIRED'",
                                Long.class,
                                taskId,
                                itemId,
                                input.recheckGeneration()));
        if (observations > 0
                || !db.queryForList(
                                "SELECT item_id FROM fams_inventory_round_result WHERE item_id=?"
                                        + " AND recheck_generation=?",
                                itemId,
                                input.recheckGeneration())
                        .isEmpty()) {
            throw new InventoryFailure(409, "INVENTORY_OBSERVATION_ALREADY_PRESENT");
        }
        if (!assets.matchesVersion(number(item, "asset_id"), input.assetVersion())) {
            conflict(actor, current, task, "INVENTORY_REVIEW_ASSET_CHANGED", Map.of());
        }
        var now = now();
        db.update(
                "INSERT INTO"
                    + " fams_inventory_item_review(item_id,recheck_generation,task_id,observation_id,review_status,verified_observed_at,reviewed_asset_version,reason,evidence,reviewer_account_id,reviewer_username,reviewed_at,version)"
                    + " VALUES (?,?,?,NULL,'NOT_OBSERVED',NULL,?,?,?,?,?,?,1)",
                itemId,
                input.recheckGeneration(),
                taskId,
                input.assetVersion(),
                input.reason().strip(),
                input.evidence().strip(),
                current.id(),
                current.username(),
                now);
        db.update(
                "INSERT INTO"
                    + " fams_inventory_unobserved_reason(task_id,item_id,recheck_generation,reason_kind,reason,evidence,reviewed_asset_version,actor_account_id,actor_username,recorded_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                taskId,
                itemId,
                input.recheckGeneration(),
                input.kind(),
                input.reason().strip(),
                input.evidence().strip(),
                input.assetVersion(),
                current.id(),
                current.username(),
                now);
        long id = Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        db.update(
                "UPDATE fams_inventory_task SET version=version+1,updated_at=? WHERE id=?",
                now,
                taskId);
        var response =
                Map.<String, Object>of(
                        "taskId",
                        Long.toString(taskId),
                        "itemId",
                        Long.toString(itemId),
                        "reasonId",
                        Long.toString(id),
                        "version",
                        version + 1,
                        "reviewVersion",
                        1,
                        "status",
                        "NOT_OBSERVED",
                        "physicalMissingOpen",
                        "ASSET_NOT_FOUND".equals(input.kind()));
        audit(actor, current, task, "INVENTORY_NOT_OBSERVED", version + 1, response);
        save(current, taskId, operation, key, digest, response);
        return response;
    }

    public Map<String, Object> waiting(
            Actor actor,
            long taskId,
            long version,
            UUID key,
            InventoryReadinessInput.Waiting input) {
        var current = admin(actor);
        var task = task(taskId);
        String operation = "WAITING_REVIEW";
        String digest = hash(version + ":" + json.writeValueAsString(input));
        var prior = original(actor, current, task, operation, key, digest);
        if (prior != null) {
            return prior;
        }
        if (number(task, "version") != version || !"IN_PROGRESS".equals(task.get("task_status"))) {
            conflict(actor, current, task, "INVENTORY_READINESS_CONTEXT_CHANGED", Map.of());
        }
        if (input.reason().strip().length() < 3) {
            throw new InventoryFailure(400, "INVENTORY_WAITING_REASON_REQUIRED");
        }
        long total = count(taskId), missing = missing(taskId);
        if (total == 0 || total != number(task, "expected_count") || missing > 0) {
            throw new InventoryFailure(409, "INVENTORY_RESULTS_OR_REASONS_REQUIRED");
        }
        db.update(
                "UPDATE fams_inventory_task SET"
                        + " task_status='WAITING_REVIEW',version=version+1,updated_at=? WHERE id=?",
                now(),
                taskId);
        var response =
                Map.<String, Object>of(
                        "taskId",
                        Long.toString(taskId),
                        "version",
                        version + 1,
                        "status",
                        "WAITING_REVIEW",
                        "notObservedCount",
                        notObserved(taskId));
        audit(
                actor,
                current,
                task,
                "INVENTORY_WAITING_REVIEW",
                version + 1,
                Map.of("reason", input.reason().strip(), "result", response));
        save(current, taskId, operation, key, digest, response);
        return response;
    }

    private long missing(long task) {
        return Objects.requireNonNull(
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_item i LEFT JOIN"
                                + " fams_inventory_round_result r ON r.item_id=i.id AND"
                                + " r.recheck_generation=i.recheck_generation LEFT JOIN"
                                + " fams_inventory_item_review v ON v.item_id=i.id AND"
                                + " v.recheck_generation=i.recheck_generation LEFT JOIN"
                                + " fams_inventory_unobserved_reason n ON n.item_id=i.id AND"
                                + " n.recheck_generation=i.recheck_generation WHERE i.task_id=? AND"
                                + " r.observation_id IS NULL AND (v.review_status IS NULL OR"
                                + " v.review_status<>'NOT_OBSERVED' OR n.id IS NULL)",
                        Long.class,
                        task));
    }

    private long notObserved(long task) {
        return Objects.requireNonNull(
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_item i JOIN"
                                + " fams_inventory_unobserved_reason n ON n.item_id=i.id AND"
                                + " n.recheck_generation=i.recheck_generation WHERE i.task_id=?",
                        Long.class,
                        task));
    }

    private long count(long task) {
        return Objects.requireNonNull(
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_item WHERE task_id=?",
                        Long.class,
                        task));
    }

    private Current admin(Actor actor) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(Role.ASSET_ADMIN)) {
            throw new InventoryFailure(403, "INVENTORY_REVIEW_ADMIN_REQUIRED");
        }
        return current;
    }

    private Map<String, Object> task(long id) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_task WHERE id=? AND task_status NOT IN"
                                + " ('DRAFT','DELETED') FOR UPDATE",
                        id);
        if (rows.isEmpty()) {
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

    private Map<String, Object> original(
            Actor actor,
            Current current,
            Map<String, Object> task,
            String operation,
            UUID key,
            String hash) {
        var rows =
                db.queryForList(
                        "SELECT payload_sha256,CAST(response_json AS CHAR) AS response FROM"
                                + " fams_inventory_review_receipt WHERE task_id=? AND"
                                + " actor_account_id=? AND operation=? AND request_id=?",
                        task.get("id"),
                        current.id(),
                        operation,
                        key.toString());
        if (rows.isEmpty()) {
            return null;
        }
        if (!hash.equals(rows.getFirst().get("payload_sha256"))) {
            conflict(actor, current, task, "INVENTORY_REVIEW_IDEMPOTENCY_CONFLICT", Map.of());
        }
        return json.readValue(
                rows.getFirst().get("response").toString(),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private void save(
            Current actor, long task, String operation, UUID key, String hash, Object response) {
        db.update(
                "INSERT INTO"
                    + " fams_inventory_review_receipt(task_id,actor_account_id,operation,request_id,payload_sha256,response_json,created_at)"
                    + " VALUES (?,?,?,?,?,?,?)",
                task,
                actor.id(),
                operation,
                key.toString(),
                hash,
                json.writeValueAsString(response),
                now());
    }

    private void conflict(
            Actor actor,
            Current current,
            Map<String, Object> task,
            String code,
            Map<String, Object> review) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("taskVersion", task.get("version").toString());
        fields.put("readOnlyUrl", InventoryReviewLinks.task(actor, number(task, "id")));
        if (review.get("reviewer_username") != null) {
            fields.put("processedBy", review.get("reviewer_username").toString());
            fields.put("processedByAccountId", review.get("reviewer_account_id").toString());
            fields.put("processedAt", instant(review.get("reviewed_at")).toString());
            fields.put("result", review.get("review_status").toString());
            fields.put("reviewVersion", review.get("version").toString());
            fields.put("method", reviewMethod(review));
        }
        audit(
                actor,
                current,
                task,
                "INVENTORY_READINESS_CONFLICT_REJECTED",
                number(task, "version"),
                Map.of("code", code, "fields", fields));
        throw new InventoryReviewConflict(code, fields);
    }

    private void audit(
            Actor actor,
            Current current,
            Map<String, Object> task,
            String action,
            long after,
            Object detail) {
        var now = now();
        String value = json.writeValueAsString(detail);
        db.update(
                "INSERT INTO"
                    + " fams_inventory_task_event(task_id,actor_account_id,action,detail_json,occurred_at)"
                    + " VALUES (?,?,?,?,?)",
                task.get("id"),
                current.id(),
                action,
                value,
                now);
        db.update(
                "INSERT INTO"
                    + " fams_audit_event(request_id,actor_account_id,actor_role,action,resource_type,resource_id,before_version,after_version,occurred_at,detail_json)"
                    + " VALUES (?,?,'ASSET_ADMIN',?,'INVENTORY_TASK',?,?,?,?,?)",
                actor.requestId().toString(),
                current.id(),
                action,
                task.get("id"),
                task.get("version"),
                after,
                now,
                value);
    }

    private Object wire(Object value) {
        if (value instanceof Map<?, ?> row) {
            var result = new LinkedHashMap<String, Object>();
            row.forEach(
                    (key, item) -> {
                        String name = key.toString();
                        Object content =
                                name.endsWith("_json") && item instanceof String text
                                        ? json.readValue(text, Object.class)
                                        : item;
                        result.put(
                                name,
                                item != null && (name.equals("id") || name.endsWith("_id"))
                                        ? item.toString()
                                        : wire(content));
                    });
            return result;
        }
        if (value instanceof Timestamp || value instanceof java.time.LocalDateTime) {
            return instant(value).toString();
        }
        return value;
    }

    private String reviewMethod(Map<String, Object> review) {
        if ("SELECTED".equals(review.get("review_status"))) {
            return "SELECT_EVIDENCE";
        }
        if (!"ACCEPTED".equals(review.get("review_status"))) {
            return "NOT_OBSERVED";
        }
        return db.queryForList(
                                "SELECT item_id FROM fams_inventory_selection_event WHERE item_id=?"
                                    + " AND recheck_generation=? AND review_status='ACCEPTED' LIMIT"
                                    + " 1",
                                review.get("item_id"),
                                review.get("recheck_generation"))
                        .isEmpty()
                ? "ACCEPT_NORMAL"
                : "ACCEPT_SELECTED";
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    private static Instant instant(Object value) {
        return value instanceof Timestamp time
                ? time.toInstant()
                : ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC);
    }

    private static long number(Map<String, Object> row, String name) {
        return ((Number) row.get(name)).longValue();
    }

    private static String hash(String value) {
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
