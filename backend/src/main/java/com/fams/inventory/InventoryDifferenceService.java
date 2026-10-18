package com.fams.inventory;

import com.fams.asset.AssetInventoryReviewPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;
import com.fams.transfer.InventoryTransferPort;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Each dimension competes independently; a joint action commits every version or none. */
@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.REPEATABLE_READ, noRollbackFor = InventoryReviewConflict.class)
public class InventoryDifferenceService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RequisitionActorContext actors;
    private final AssetInventoryReviewPort assets;
    private final InventoryTransferPort transfers;
    private final Clock clock;

    public InventoryDifferenceService(
            JdbcTemplate db,
            ObjectMapper json,
            RequisitionActorContext actors,
            AssetInventoryReviewPort assets,
            InventoryTransferPort transfers,
            Clock clock) {
        this.db = db;
        this.json = json;
        this.actors = actors;
        this.assets = assets;
        this.transfers = transfers;
        this.clock = clock;
    }

    public Map<String, Object> list(Actor actor, long taskId, int page, int size) {
        admin(actor);
        task(taskId);
        var rows =
                db.queryForList(
                        "SELECT s.*,o.client_event_id FROM fams_inventory_review_subject s JOIN"
                            + " fams_inventory_observation o ON o.id=s.observation_id WHERE"
                            + " s.task_id=? ORDER BY s.observation_id,s.dimension LIMIT ? OFFSET ?"
                            + " FOR SHARE",
                        taskId,
                        size,
                        (page - 1) * size);
        var values = new ArrayList<Object>();
        for (var row : rows) {
            var value = new LinkedHashMap<String, Object>(row);
            value.put(
                    "decisions",
                    db.queryForList(
                            "SELECT * FROM fams_inventory_review_decision WHERE observation_id=?"
                                    + " AND dimension=? ORDER BY after_version FOR SHARE",
                            row.get("observation_id"),
                            row.get("dimension")));
            value.put(
                    "links",
                    db.queryForList(
                            "SELECT * FROM fams_inventory_review_transfer WHERE observation_id=?"
                                    + " AND dimension=? ORDER BY decision_version FOR SHARE",
                            row.get("observation_id"),
                            row.get("dimension")));
            values.add(wire(value));
        }
        return Map.of(
                "items",
                values,
                "page",
                page,
                "pageSize",
                size,
                "total",
                Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM fams_inventory_review_subject WHERE task_id=?"
                                        + " FOR SHARE",
                                Long.class,
                                taskId)));
    }

    public Map<String, Object> prepare(
            Actor actor,
            long taskId,
            long version,
            UUID key,
            InventoryDifferenceInput.Prepare input) {
        var current = admin(actor);
        var task = task(taskId);
        String hash = hash(version, input);
        var prior = replay(actor, current, task, "PREPARE_DIFFERENCES", key, hash);
        if (prior != null) {
            return prior;
        }
        reviewable(actor, current, task, version);
        if (input.observationIds().stream().distinct().count() != input.observationIds().size()) {
            throw new InventoryFailure(400, "INVENTORY_REVIEW_OBSERVATION_DUPLICATE");
        }
        int created = 0;
        for (long id : input.observationIds().stream().sorted().toList()) {
            var rows =
                    db.queryForList(
                            "SELECT * FROM fams_inventory_observation WHERE task_id=? AND id=? FOR"
                                    + " SHARE",
                            taskId,
                            id);
            if (rows.isEmpty()) {
                throw new InventoryFailure(404, "INVENTORY_OBSERVATION_NOT_FOUND");
            }
            var observation = rows.getFirst();
            if ("EXPIRED".equals(observation.get("outcome"))
                    || "DUPLICATE".equals(observation.get("outcome"))) {
                continue;
            }
            var dimensions = requiredDimensions(task, observation);
            for (String dimension : dimensions) {
                var present =
                        db.queryForList(
                                "SELECT version FROM fams_inventory_review_subject WHERE"
                                        + " observation_id=? AND dimension=? FOR UPDATE",
                                id,
                                dimension);
                if (present.isEmpty()) {
                    db.update(
                            "INSERT INTO"
                                + " fams_inventory_review_subject(observation_id,dimension,task_id,item_id,recheck_generation)"
                                + " VALUES (?,?,?,?,?)",
                            id,
                            dimension,
                            taskId,
                            observation.get("item_id"),
                            observation.get("recheck_generation"));
                    created++;
                }
            }
        }
        return finish(
                actor,
                current,
                task,
                version,
                key,
                "PREPARE_DIFFERENCES",
                hash,
                Map.of("created", created),
                created > 0);
    }

    /**
     * Pure derivation used again at final acceptance, including evidence never explicitly listed.
     */
    public java.util.Set<String> requiredDimensions(
            Map<String, Object> task, Map<String, Object> observation) {
        long taskId = number(task, "id");
        var dimensions = new LinkedHashSet<String>();
        var receipt = json.readTree(observation.get("receipt_json").toString());
        for (var dimension : receipt.path("differences")) {
            dimensions.add(dimension.asText());
        }
        String special =
                switch (observation.get("outcome").toString()) {
                    case "CONFLICT_REVIEW" -> "CONFLICT";
                    case "OLD_ASSIGNMENT_REVIEW" -> "OLD_ASSIGNMENT";
                    case "TIME_REVIEW" -> "TIME";
                    case "FINDING_REVIEW" -> "FINDING";
                    case "LATE_REVIEW" -> "LATE";
                    default -> null;
                };
        if (special != null) {
            dimensions.add(special);
        }
        if (observation.get("item_id") != null) {
            var item =
                    db.queryForMap(
                            "SELECT * FROM fams_inventory_item WHERE task_id=? AND id=? FOR"
                                    + " SHARE",
                            taskId,
                            observation.get("item_id"));
            Long variants =
                    db.queryForObject(
                            "SELECT COUNT(DISTINCT fact_sha256) FROM fams_inventory_observation"
                                    + " WHERE task_id=? AND item_id=? AND recheck_generation=? AND"
                                    + " outcome NOT IN ('EXPIRED','DUPLICATE','LATE_REVIEW') FOR"
                                    + " SHARE",
                            Long.class,
                            taskId,
                            item.get("id"),
                            observation.get("recheck_generation"));
            if (variants != null && variants > 1) {
                dimensions.add("CONFLICT");
            }
            var book = json.readTree(item.get("snapshot_json").toString());
            var actual = json.readTree(observation.get("facts_json").toString()).path("actual");
            if (!Objects.equals(
                    scalar(book.path("locationId")), scalar(actual.path("locationId")))) {
                dimensions.add("LOCATION");
            }
            if (!Objects.equals(
                    scalar(book.path("custodyType")), scalar(actual.path("custodyType")))) {
                dimensions.add("USAGE_MODE");
            }
            var owner =
                    "SHARED".equals(scalar(book.path("custodyType")))
                            ? book.path("departmentResponsibleEmployeeId")
                            : book.path("responsibleEmployeeId");
            if (!Objects.equals(scalar(owner), scalar(actual.path("responsibleEmployeeId")))) {
                dimensions.add("RESPONSIBILITY");
            }
            if (!"GOOD".equals(scalar(actual.path("condition")))) {
                dimensions.add("CONDITION");
            }
            if (scalar(actual.path("unknownReason")) != null
                    && !actual.path("unknownReason").asText().isBlank()) {
                dimensions.add("UNKNOWN");
            }
            var context =
                    assets.contexts(
                                    List.of(number(item, "asset_id")),
                                    instant(task.get("published_at")))
                            .get(number(item, "asset_id"));
            if (context == null || !context.movements().isEmpty()) {
                dimensions.add("PERIOD_MOVEMENT");
            }
            if (context != null) {
                var latest = json.readTree(context.snapshotJson());
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
                    if (!Objects.equals(scalar(book.path(field)), scalar(latest.path(field)))) {
                        dimensions.add("PERIOD_MOVEMENT");
                        break;
                    }
                }
            }
        }
        return java.util.Set.copyOf(dimensions);
    }

    public Map<String, Object> decide(
            Actor actor,
            long taskId,
            long version,
            UUID key,
            InventoryDifferenceInput.Decision input) {
        var current = admin(actor);
        var task = task(taskId);
        String hash = hash(version, input);
        var prior = replay(actor, current, task, "DECIDE_DIFFERENCES", key, hash);
        if (prior != null) {
            return prior;
        }
        if (!List.of("BOOK_CORRECT", "CONTINUE_SEARCH", "FORMAL_TRANSFER", "REFRESH_TRANSFER")
                .contains(input.method())) {
            throw new InventoryFailure(400, "INVENTORY_REVIEW_METHOD_INVALID");
        }
        var rows = new ArrayList<Map<String, Object>>();
        var identities = new LinkedHashSet<String>();
        Long itemId = null;
        for (var ref : input.subjects()) {
            if (!identities.add(ref.observationId() + ":" + ref.dimension())) {
                throw new InventoryFailure(400, "INVENTORY_REVIEW_SUBJECT_DUPLICATE");
            }
            var found =
                    db.queryForList(
                            "SELECT * FROM fams_inventory_review_subject WHERE task_id=? AND"
                                    + " observation_id=? AND dimension=? FOR UPDATE",
                            taskId,
                            ref.observationId(),
                            ref.dimension());
            if (found.isEmpty()) {
                throw new InventoryFailure(404, "INVENTORY_REVIEW_SUBJECT_NOT_FOUND");
            }
            var subject = found.getFirst();
            if (number(subject, "version") != ref.version()
                    || List.of("CLOSED", "TRANSFERRED").contains(subject.get("subject_status"))) {
                reject(actor, current, task, "INVENTORY_REVIEW_VERSION_CONFLICT", subject);
            }
            if (subject.get("item_id") == null) {
                throw new InventoryFailure(409, "INVENTORY_FINDING_BUSINESS_REQUIRED");
            }
            long owner = number(subject, "item_id");
            if (itemId != null && itemId != owner) {
                throw new InventoryFailure(400, "INVENTORY_REVIEW_JOINT_ASSET_REQUIRED");
            }
            itemId = owner;
            rows.add(subject);
        }
        reviewable(actor, current, task, version);
        var item =
                db.queryForMap(
                        "SELECT * FROM fams_inventory_item WHERE task_id=? AND id=? FOR UPDATE",
                        taskId,
                        itemId);
        if (!assets.matchesVersion(number(item, "asset_id"), input.assetVersion())) {
            reject(actor, current, task, "INVENTORY_REVIEW_ASSET_CHANGED", Map.of());
        }
        var links = new ArrayList<InventoryTransferPort.Link>();
        for (var row : rows) {
            if ("PROCESSING".equals(row.get("subject_status"))
                    && !db.queryForList(
                                    "SELECT transfer_id FROM fams_inventory_review_transfer WHERE"
                                            + " observation_id=? AND dimension=? LIMIT 1 FOR SHARE",
                                    row.get("observation_id"),
                                    row.get("dimension"))
                            .isEmpty()
                    && !"REFRESH_TRANSFER".equals(input.method())) {
                throw new InventoryFailure(409, "INVENTORY_TRANSFER_PROGRESS_REQUIRED");
            }
            if ("REFRESH_TRANSFER".equals(input.method())) {
                var existing =
                        db.queryForList(
                                "SELECT transfer_id FROM fams_inventory_review_transfer WHERE"
                                    + " observation_id=? AND dimension=? ORDER BY decision_version"
                                    + " DESC LIMIT 1 FOR SHARE",
                                row.get("observation_id"),
                                row.get("dimension"));
                if (existing.isEmpty()) {
                    throw new InventoryFailure(409, "INVENTORY_TRANSFER_LINK_REQUIRED");
                }
                links.add(progress(actor, number(existing.getFirst(), "transfer_id")));
            } else if ("CONTINUE_SEARCH".equals(input.method())) {
                if (!List.of("UNKNOWN", "CONFLICT", "OLD_ASSIGNMENT", "TIME")
                        .contains(row.get("dimension"))) {
                    throw new InventoryFailure(400, "INVENTORY_SEARCH_DIMENSION_INVALID");
                }
            } else if (!List.of("LOCATION", "USAGE_MODE", "RESPONSIBILITY", "PERIOD_MOVEMENT")
                    .contains(row.get("dimension"))) {
                throw new InventoryFailure(409, "INVENTORY_SPECIAL_EVIDENCE_REQUIRED");
            }
        }
        InventoryTransferPort.Link created = null;
        if ("FORMAL_TRANSFER".equals(input.method())) {
            if (input.transferTarget() == null) {
                throw new InventoryFailure(400, "INVENTORY_TRANSFER_TARGET_REQUIRED");
            }
            try {
                created =
                        transfers.createAndSubmit(
                                actor,
                                "inventory:" + taskId + ":" + key,
                                number(item, "asset_id"),
                                input.assetVersion(),
                                input.transferTarget(),
                                input.reason());
            } catch (InventoryTransferPort.Failure failure) {
                throw new InventoryFailure(failure.status(), failure.getMessage());
            }
        }
        int index = 0;
        var decisions = new ArrayList<Object>();
        for (var row : rows) {
            var linked = created != null ? created : links.isEmpty() ? null : links.get(index);
            String status = "BOOK_CORRECT".equals(input.method()) ? "CLOSED" : "PROCESSING";
            if ("REFRESH_TRANSFER".equals(input.method())) {
                status =
                        "COMPLETED".equals(Objects.requireNonNull(linked).status())
                                ? "CLOSED"
                                : List.of("CANCELLED", "REJECTED").contains(linked.status())
                                        ? "OPEN"
                                        : "PROCESSING";
            }
            long before = number(row, "version"), after = before + 1;
            db.update(
                    "UPDATE fams_inventory_review_subject SET"
                        + " subject_status=?,method=?,reason=?,evidence=?,reviewer_account_id=?,reviewer_username=?,reviewed_at=?,version=?"
                        + " WHERE observation_id=? AND dimension=?",
                    status,
                    input.method(),
                    input.reason(),
                    input.evidence(),
                    current.id(),
                    current.username(),
                    now(),
                    after,
                    row.get("observation_id"),
                    row.get("dimension"));
            db.update(
                    "INSERT INTO"
                        + " fams_inventory_review_decision(task_id,observation_id,dimension,before_version,after_version,method,subject_status,reason,evidence,reviewed_asset_version,actor_account_id,actor_username,occurred_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    taskId,
                    row.get("observation_id"),
                    row.get("dimension"),
                    before,
                    after,
                    input.method(),
                    status,
                    input.reason(),
                    input.evidence(),
                    input.assetVersion(),
                    current.id(),
                    current.username(),
                    now());
            if (created != null) {
                db.update(
                        "INSERT INTO"
                            + " fams_inventory_review_transfer(observation_id,dimension,decision_version,transfer_id,transfer_item_id,created_at)"
                            + " VALUES (?,?,?,?,?,?)",
                        row.get("observation_id"),
                        row.get("dimension"),
                        after,
                        Long.parseLong(created.requestId()),
                        Long.parseLong(created.itemId()),
                        now());
            }
            decisions.add(
                    Map.of(
                            "observationId",
                            row.get("observation_id").toString(),
                            "dimension",
                            row.get("dimension"),
                            "version",
                            after,
                            "status",
                            status,
                            "linkedRequestId",
                            linked == null ? "" : linked.requestId()));
            index++;
        }
        return finish(
                actor,
                current,
                task,
                version,
                key,
                "DECIDE_DIFFERENCES",
                hash,
                Map.of("items", decisions),
                true);
    }

    private String scalar(tools.jackson.databind.JsonNode node) {
        return node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    private InventoryTransferPort.Link progress(Actor actor, long id) {
        try {
            return transfers.progress(actor, id);
        } catch (InventoryTransferPort.Failure failure) {
            throw new InventoryFailure(failure.status(), failure.getMessage());
        }
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

    private void reviewable(Actor actor, Current current, Map<String, Object> task, long version) {
        if (number(task, "version") != version
                || !List.of("IN_PROGRESS", "WAITING_REVIEW").contains(task.get("task_status"))) {
            reject(actor, current, task, "INVENTORY_REVIEW_TASK_CHANGED", Map.of());
        }
    }

    private void reject(
            Actor actor,
            Current current,
            Map<String, Object> task,
            String code,
            Map<String, Object> subject) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("taskVersion", task.get("version").toString());
        fields.put("readOnlyUrl", "/inventory-reviews/tasks/" + task.get("id") + "/differences");
        for (String name :
                List.of(
                        "reviewer_username",
                        "reviewed_at",
                        "method",
                        "subject_status",
                        "version")) {
            if (subject.get(name) != null) {
                fields.put(name, wire(subject.get(name)).toString());
            }
        }
        if (subject.get("reviewer_username") != null) {
            fields.put("processedBy", subject.get("reviewer_username").toString());
        }
        if (subject.get("reviewed_at") != null) {
            fields.put("processedAt", wire(subject.get("reviewed_at")).toString());
        }
        fields.put(
                "result",
                subject.getOrDefault("subject_status", task.get("task_status")).toString());
        audit(
                actor,
                current,
                task,
                "INVENTORY_DIFFERENCE_CONFLICT_REJECTED",
                Map.of("code", code, "fields", fields));
        throw new InventoryReviewConflict(code, fields);
    }

    private Map<String, Object> replay(
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
                                + " actor_account_id=? AND operation=? AND request_id=? FOR UPDATE",
                        task.get("id"),
                        current.id(),
                        operation,
                        key.toString());
        if (rows.isEmpty()) {
            return null;
        }
        if (!hash.equals(rows.getFirst().get("payload_sha256"))) {
            reject(actor, current, task, "INVENTORY_REVIEW_IDEMPOTENCY_CONFLICT", Map.of());
        }
        return json.readValue(
                rows.getFirst().get("response").toString(),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> finish(
            Actor actor,
            Current current,
            Map<String, Object> task,
            long version,
            UUID key,
            String operation,
            String hash,
            Map<String, Object> payload,
            boolean changed) {
        long after = version + (changed ? 1 : 0);
        if (changed) {
            db.update(
                    "UPDATE fams_inventory_task SET version=?,updated_at=? WHERE id=?",
                    after,
                    now(),
                    task.get("id"));
        }
        var result = new LinkedHashMap<>(payload);
        result.put("taskId", task.get("id").toString());
        result.put("version", after);
        audit(actor, current, task, operation, result);
        db.update(
                "INSERT INTO"
                    + " fams_inventory_review_receipt(task_id,actor_account_id,operation,request_id,payload_sha256,response_json,created_at)"
                    + " VALUES (?,?,?,?,?,?,?)",
                task.get("id"),
                current.id(),
                operation,
                key.toString(),
                hash,
                json.writeValueAsString(result),
                now());
        return result;
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

    private String hash(long version, Object input) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (version
                                                            + "\n"
                                                            + json.writeValueAsString(
                                                                    canonical(
                                                                            json.readValue(
                                                                                    json
                                                                                            .writeValueAsString(
                                                                                                    input),
                                                                                    Object.class))))
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new java.util.TreeMap<String, Object>();
            map.forEach((key, child) -> sorted.put(key.toString(), canonical(child)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonical).toList();
        }
        return value;
    }

    private Object wire(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach(
                    (key, child) ->
                            result.put(
                                    key.toString(),
                                    child instanceof Number
                                                    && (key.toString().equals("id")
                                                            || key.toString().endsWith("_id"))
                                            ? child.toString()
                                            : wire(child)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::wire).toList();
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

    private java.time.Instant instant(Object value) {
        return value instanceof Timestamp time
                ? time.toInstant()
                : ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }
}
