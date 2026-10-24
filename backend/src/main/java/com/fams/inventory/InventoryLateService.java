package com.fams.inventory;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Supplemental decisions never update the terminal source or its frozen result. */
@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.REPEATABLE_READ, noRollbackFor = InventoryReviewConflict.class)
public class InventoryLateService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RequisitionActorContext actors;
    private final InventoryLateEvidencePort evidence;
    private final InventoryService inventory;
    private final Clock clock;

    public InventoryLateService(
            JdbcTemplate db,
            ObjectMapper json,
            RequisitionActorContext actors,
            InventoryLateEvidencePort evidence,
            InventoryService inventory,
            Clock clock) {
        this.db = db;
        this.json = json;
        this.actors = actors;
        this.evidence = evidence;
        this.inventory = inventory;
        this.clock = clock;
    }

    public Map<String, Object> list(Actor actor, long taskId, int page, int size) {
        admin(actor);
        var task = task(taskId);
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_late_case WHERE task_id=? ORDER BY"
                                + " observation_id LIMIT ? OFFSET ? FOR SHARE",
                        taskId,
                        size,
                        (page - 1) * size);
        return Map.of(
                "task",
                wire(task),
                "items",
                rows.stream().map(this::view).toList(),
                "page",
                page,
                "pageSize",
                size,
                "total",
                Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM fams_inventory_late_case WHERE task_id=? FOR"
                                        + " SHARE",
                                Long.class,
                                taskId)),
                "supplementOnly",
                true);
    }

    public Map<String, Object> detail(
            Actor actor, long taskId, long observationId, int page, int size) {
        admin(actor);
        var task = task(taskId);
        var row = lateCase(taskId, observationId);
        var history =
                Map.of(
                        "items",
                        wire(
                                db.queryForList(
                                        "SELECT * FROM fams_inventory_late_decision WHERE"
                                            + " observation_id=? ORDER BY decision_version LIMIT ?"
                                            + " OFFSET ? FOR SHARE",
                                        observationId,
                                        size,
                                        (page - 1) * size)),
                        "page",
                        page,
                        "pageSize",
                        size,
                        "total",
                        Objects.requireNonNull(
                                db.queryForObject(
                                        "SELECT COUNT(*) FROM fams_inventory_late_decision WHERE"
                                                + " observation_id=? FOR SHARE",
                                        Long.class,
                                        observationId)));
        return Map.of(
                "task", wire(task), "case", view(row), "history", history, "supplementOnly", true);
    }

    public Map<String, Object> prepare(Actor actor, long taskId, long version, UUID key) {
        var current = admin(actor);
        var task = task(taskId);
        String payload = hash("prepare\n" + version);
        var prior = original(actor, current, task, "PREPARE_LATE", key, payload);
        if (prior != null) {
            return prior;
        }
        if (version != number(task, "version")) {
            conflict(actor, current, task, Map.of(), "INVENTORY_LATE_TASK_CHANGED");
        }
        var rows =
                db.queryForList(
                        "SELECT o.id FROM fams_inventory_observation o WHERE o.task_id=? AND"
                            + " o.outcome IN ('LATE_REVIEW','TIME_REVIEW') AND NOT EXISTS (SELECT 1"
                            + " FROM fams_inventory_late_case c WHERE c.observation_id=o.id) AND"
                            + " NOT EXISTS (SELECT 1 FROM fams_inventory_sealed_evidence s WHERE"
                            + " s.task_id=o.task_id AND s.observation_id=o.id) ORDER BY o.id LIMIT"
                            + " 100 FOR SHARE",
                        taskId);
        for (var row : rows) {
            evidence.append(task, number(row, "id"));
        }
        var response =
                Map.<String, Object>of(
                        "taskId",
                        Long.toString(taskId),
                        "prepared",
                        rows.size(),
                        "moreMayRemain",
                        rows.size() == 100,
                        "taskVersion",
                        version,
                        "supplementOnly",
                        true);
        audit(actor, current, task, "INVENTORY_LATE_PREPARED", response);
        receipt(current, taskId, "PREPARE_LATE", key, payload, response);
        return response;
    }

    public Map<String, Object> decide(
            Actor actor,
            long taskId,
            long observationId,
            long version,
            UUID key,
            InventoryLateInput input) {
        var current = admin(actor);
        var task = task(taskId);
        String payload =
                hash(observationId + "\n" + version + "\n" + json.writeValueAsString(input));
        var prior = original(actor, current, task, "LATE_DECISION", key, payload);
        if (prior != null) {
            return prior;
        }
        var row = lateCase(taskId, observationId);
        var previous = latest(observationId);
        long actualVersion = previous.isEmpty() ? 0 : number(previous, "decision_version");
        if (version != actualVersion) {
            conflict(actor, current, task, previous, "INVENTORY_LATE_VERSION_CONFLICT");
        }
        if (!List.of("NOTE", "LINK_RECHECK", "REPLACE_RECHECK", "CONFIRM_RECHECK")
                        .contains(input.method())
                || input.reason().strip().length() < 3
                || input.evidence().strip().length() < 3) {
            throw new InventoryFailure(400, "INVENTORY_LATE_DECISION_INVALID");
        }
        boolean followupOpen = "FOLLOWUP_OPEN".equals(previous.get("result"));
        Long linked =
                previous.get("linked_task_id") == null ? null : number(previous, "linked_task_id");
        String result;
        switch (input.method()) {
            case "NOTE" -> {
                if (input.recheck() != null) {
                    throw new InventoryFailure(400, "INVENTORY_LATE_UNEXPECTED_RECHECK");
                }
                if (followupOpen
                        || InventoryLatePolicy.requiresRecheck(
                                row.get("comparison_kind").toString(),
                                json.readTree(row.get("snapshot_json").toString()).path("facts"))) {
                    throw new InventoryFailure(409, "INVENTORY_LATE_RECHECK_REQUIRED");
                }
                linked = null;
                result = "REVIEWED";
            }
            case "LINK_RECHECK", "REPLACE_RECHECK" -> {
                if (input.recheck() == null) {
                    throw new InventoryFailure(400, "INVENTORY_LATE_RECHECK_INPUT_REQUIRED");
                }
                if ("REPLACE_RECHECK".equals(input.method())) {
                    if (!followupOpen
                            || linked == null
                            || !List.of("COMPLETED", "CANCELLED", "TERMINATED", "DELETED")
                                    .contains(linkedTask(linked).get("task_status"))) {
                        throw new InventoryFailure(
                                409, "INVENTORY_LATE_RECHECK_REPLACEMENT_REFUSED");
                    }
                } else if (followupOpen) {
                    throw new InventoryFailure(409, "INVENTORY_LATE_FOLLOWUP_ALREADY_OPEN");
                }
                var draft =
                        inventory.copy(
                                actor,
                                taskId,
                                number(task, "version"),
                                UUID.nameUUIDFromBytes(
                                                ("inventory-late:" + observationId + ":" + key)
                                                        .getBytes(StandardCharsets.UTF_8))
                                        .toString(),
                                input.recheck());
                linked = Long.valueOf(draft.get("id").toString());
                result = "FOLLOWUP_OPEN";
            }
            case "CONFIRM_RECHECK" -> {
                if (input.recheck() != null) {
                    throw new InventoryFailure(400, "INVENTORY_LATE_UNEXPECTED_RECHECK");
                }
                if (!followupOpen || linked == null) {
                    throw new InventoryFailure(409, "INVENTORY_LATE_FOLLOWUP_REQUIRED");
                }
                requireCompletedRecheck(row, linked);
                result = "REVIEWED";
            }
            default -> throw new InventoryFailure(400, "INVENTORY_LATE_DECISION_INVALID");
        }
        var now = now();
        db.update(
                "INSERT INTO"
                    + " fams_inventory_late_decision(observation_id,decision_version,method,result,reason,evidence,linked_task_id,actor_account_id,actor_username,decided_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                observationId,
                version + 1,
                input.method(),
                result,
                input.reason().strip(),
                input.evidence().strip(),
                linked,
                current.id(),
                current.username(),
                now);
        if ("FOLLOWUP_OPEN".equals(result)) {
            db.update(
                    "INSERT INTO"
                        + " fams_todo(pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,opened_at)"
                        + " VALUES"
                        + " ('ASSET_ADMIN',NULL,'INVENTORY_LATE',?,'LATE_EVIDENCE_REVIEW','OPEN',?)"
                        + " ON DUPLICATE KEY UPDATE id=id",
                    observationId,
                    now);
        } else {
            db.update(
                    "UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE"
                            + " business_type='INVENTORY_LATE' AND business_id=? AND"
                            + " todo_type='LATE_EVIDENCE_REVIEW' AND closed_at IS NULL",
                    now,
                    observationId);
        }
        var response = new LinkedHashMap<String, Object>();
        response.put("taskId", Long.toString(taskId));
        response.put("observationId", Long.toString(observationId));
        response.put("version", version + 1);
        response.put("result", result);
        response.put("method", input.method());
        response.put("linkedTaskId", linked == null ? null : linked.toString());
        response.put("processedBy", current.username());
        response.put("processedAt", now.toInstant().toString());
        response.put("supplementOnly", true);
        response.put("originalReportChanged", false);
        audit(actor, current, task, "INVENTORY_LATE_DECIDED", response);
        receipt(current, taskId, "LATE_DECISION", key, payload, response);
        return response;
    }

    private void requireCompletedRecheck(Map<String, Object> row, long linked) {
        var task = linkedTask(linked);
        var packet = json.readTree(row.get("snapshot_json").toString());
        var assetNode = packet.path("assetId");
        String asset = assetNode.isMissingNode() || assetNode.isNull() ? "" : assetNode.asText();
        if (asset.isBlank()) {
            throw new InventoryFailure(409, "INVENTORY_LATE_FINDING_FOLLOWUP_REQUIRED");
        }
        if (!"COMPLETED".equals(task.get("task_status"))) {
            throw new InventoryFailure(409, "INVENTORY_LATE_RECHECK_NOT_COMPLETED");
        }
        var items =
                db.queryForList(
                        "SELECT i.snapshot_json FROM fams_inventory_sealed_item i JOIN"
                                + " fams_inventory_sealed_report r ON r.task_id=i.task_id AND"
                                + " r.task_version=? WHERE i.task_id=? AND i.asset_id=? FOR SHARE",
                        task.get("version"),
                        linked,
                        Long.valueOf(asset));
        if (items.isEmpty()
                || !"ACCEPTED"
                        .equals(
                                json.readTree(items.getFirst().get("snapshot_json").toString())
                                        .path("review")
                                        .path("review_status")
                                        .asText())) {
            throw new InventoryFailure(409, "INVENTORY_LATE_RECHECK_ACCEPTED_ASSET_REQUIRED");
        }
    }

    private Map<String, Object> view(Map<String, Object> row) {
        long observation = number(row, "observation_id");
        var previous = latest(observation);
        var damage =
                db.queryForList(
                        "SELECT * FROM fams_inventory_late_damage WHERE observation_id=? FOR SHARE",
                        observation);
        return Map.of(
                "evidence",
                wire(row),
                "review",
                previous.isEmpty()
                        ? Map.of(
                                "decision_version",
                                0,
                                "result",
                                Boolean.TRUE.equals(row.get("needs_review"))
                                        ? "OPEN"
                                        : "INFORMATION")
                        : wire(previous),
                "damageFollowup",
                damage.isEmpty() ? Map.of() : wire(damage.getFirst()),
                "todoOpen",
                !db.queryForList(
                                "SELECT id FROM fams_todo WHERE business_type='INVENTORY_LATE' AND"
                                    + " business_id=? AND todo_type IN"
                                    + " ('LATE_EVIDENCE_REVIEW','LATE_DAMAGE_REPAIR') AND closed_at"
                                    + " IS NULL FOR SHARE",
                                observation)
                        .isEmpty());
    }

    private Map<String, Object> latest(long observation) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_late_decision WHERE observation_id=? ORDER BY"
                                + " decision_version DESC LIMIT 1 FOR SHARE",
                        observation);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private Map<String, Object> lateCase(long task, long observation) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_late_case WHERE task_id=? AND"
                                + " observation_id=? FOR SHARE",
                        task,
                        observation);
        if (rows.isEmpty()) {
            throw new InventoryFailure(404, "INVENTORY_LATE_CASE_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private Map<String, Object> linkedTask(long id) {
        var rows = db.queryForList("SELECT * FROM fams_inventory_task WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new InventoryFailure(409, "INVENTORY_LATE_RECHECK_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private Current admin(Actor actor) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(Role.ASSET_ADMIN)) {
            throw new InventoryFailure(403, "INVENTORY_REVIEW_ADMIN_REQUIRED");
        }
        return current;
    }

    private Map<String, Object> task(long id) {
        var row = linkedTask(id);
        if (!List.of("COMPLETED", "CANCELLED", "TERMINATED").contains(row.get("task_status"))) {
            throw new InventoryFailure(409, "INVENTORY_LATE_TERMINAL_TASK_REQUIRED");
        }
        return row;
    }

    private Map<String, Object> original(
            Actor actor,
            Current current,
            Map<String, Object> task,
            String operation,
            UUID key,
            String payload) {
        var rows =
                db.queryForList(
                        "SELECT payload_sha256,CAST(response_json AS CHAR) AS response FROM"
                                + " fams_inventory_review_receipt WHERE task_id=? AND"
                                + " actor_account_id=? AND operation=? AND request_id=? FOR SHARE",
                        task.get("id"),
                        current.id(),
                        operation,
                        key.toString());
        if (rows.isEmpty()) {
            return null;
        }
        if (!payload.equals(rows.getFirst().get("payload_sha256"))) {
            conflict(actor, current, task, Map.of(), "INVENTORY_LATE_IDEMPOTENCY_CONFLICT");
        }
        return json.readValue(
                rows.getFirst().get("response").toString(),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private void receipt(
            Current current,
            long task,
            String operation,
            UUID key,
            String payload,
            Map<String, Object> response) {
        db.update(
                "INSERT INTO"
                    + " fams_inventory_review_receipt(task_id,actor_account_id,operation,request_id,payload_sha256,response_json,created_at)"
                    + " VALUES (?,?,?,?,?,?,?)",
                task,
                current.id(),
                operation,
                key.toString(),
                payload,
                json.writeValueAsString(response),
                now());
    }

    private void conflict(
            Actor actor,
            Current current,
            Map<String, Object> task,
            Map<String, Object> previous,
            String code) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("taskVersion", task.get("version").toString());
        fields.put(
                "result",
                previous.isEmpty()
                        ? task.get("task_status").toString()
                        : previous.get("result").toString());
        fields.put(
                "readOnlyUrl",
                (actor.miniToken() == null ? "/api/v1" : "/api/v1/mini")
                        + "/inventory-late/tasks/"
                        + number(task, "id"));
        if (!previous.isEmpty()) {
            fields.put("processedBy", previous.get("actor_username").toString());
            fields.put("processedAt", instant(previous.get("decided_at")));
            fields.put("method", previous.get("method").toString());
            fields.put("decisionVersion", previous.get("decision_version").toString());
        }
        audit(
                actor,
                current,
                task,
                "INVENTORY_LATE_CONFLICT_REJECTED",
                Map.of("code", code, "fields", fields));
        throw new InventoryReviewConflict(code, fields);
    }

    private void audit(
            Actor actor, Current current, Map<String, Object> task, String action, Object details) {
        String value = json.writeValueAsString(details);
        var now = now();
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
                task.get("version"),
                now,
                value);
    }

    private Object wire(Object value) {
        if (value instanceof Map<?, ?> source) {
            var result = new LinkedHashMap<String, Object>();
            source.forEach(
                    (key, item) -> {
                        String field = key.toString();
                        Object content =
                                field.endsWith("_json") && item instanceof String text
                                        ? json.readValue(text, Object.class)
                                        : item;
                        result.put(
                                field,
                                item != null && (field.equals("id") || field.endsWith("_id"))
                                        ? item.toString()
                                        : wire(content));
                    });
            return result;
        }
        if (value instanceof List<?> rows) {
            return rows.stream().map(this::wire).toList();
        }
        if (value instanceof Timestamp || value instanceof java.time.LocalDateTime) {
            return instant(value);
        }
        return value;
    }

    private long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    private String instant(Object value) {
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
