package com.fams.inventory;

import com.fams.asset.AssetInventoryReviewPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;
import com.fams.repair.InventoryRepairPort;

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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Damage and the existing supplemental recheck have independent versions, histories and todos. */
@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.REPEATABLE_READ, noRollbackFor = InventoryReviewConflict.class)
public class InventoryLateDamageService {
    private static final String OPERATION = "LATE_DAMAGE_REPAIR";
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RequisitionActorContext actors;
    private final AssetInventoryReviewPort assets;
    private final InventoryRepairPort repairs;
    private final Clock clock;

    public InventoryLateDamageService(
            JdbcTemplate db,
            ObjectMapper json,
            RequisitionActorContext actors,
            AssetInventoryReviewPort assets,
            InventoryRepairPort repairs,
            Clock clock) {
        this.db = db;
        this.json = json;
        this.actors = actors;
        this.assets = assets;
        this.repairs = repairs;
        this.clock = clock;
    }

    public Map<String, Object> detail(
            Actor actor, long taskId, long observation, int page, int size) {
        admin(actor);
        var task = task(taskId);
        var source = source(taskId, observation);
        var state = state(observation);
        long asset = asset(source);
        var context = assets.contexts(List.of(asset), clock.instant()).get(asset);
        var result = new LinkedHashMap<String, Object>();
        result.put("taskId", Long.toString(taskId));
        result.put("taskVersion", number(task, "version"));
        result.put("observationId", Long.toString(observation));
        result.put("assetId", Long.toString(asset));
        result.put("currentAsset", context == null ? null : json.readTree(context.snapshotJson()));
        result.put("damage", wire(state));
        result.put("originalEvidence", json.readTree(source.get("snapshot_json").toString()));
        result.put(
                "history",
                Map.of(
                        "items",
                        wire(
                                db.queryForList(
                                        "SELECT * FROM fams_inventory_late_damage_decision WHERE"
                                            + " observation_id=? ORDER BY after_version LIMIT ?"
                                            + " OFFSET ? FOR SHARE",
                                        observation,
                                        size,
                                        (page - 1) * size)),
                        "page",
                        page,
                        "pageSize",
                        size,
                        "total",
                        db.queryForObject(
                                "SELECT COUNT(*) FROM fams_inventory_late_damage_decision WHERE"
                                    + " observation_id=?",
                                Long.class,
                                observation)));
        result.put("recheckResult", recheckResult(observation));
        result.put("todoOpen", todoOpen(observation));
        result.put("supplementOnly", true);
        result.put("originalReportChanged", false);
        return result;
    }

    public Map<String, Object> decide(
            Actor actor,
            long taskId,
            long observation,
            long version,
            UUID key,
            InventoryLateDamageInput input) {
        var current = admin(actor);
        var task = task(taskId);
        String fingerprint = hash(json.writeValueAsString(List.of(observation, version, input)));
        var prior =
                db.queryForList(
                        "SELECT payload_sha256,response_json FROM fams_inventory_review_receipt"
                            + " WHERE task_id=? AND actor_account_id=? AND operation=? AND"
                            + " request_id=? FOR SHARE",
                        taskId,
                        current.id(),
                        OPERATION,
                        key.toString());
        if (!prior.isEmpty()) {
            if (!fingerprint.equals(prior.getFirst().get("payload_sha256"))) {
                conflict(
                        actor,
                        current,
                        task,
                        observation,
                        "INVENTORY_LATE_DAMAGE_IDEMPOTENCY_CONFLICT");
            }
            return json.readValue(
                    prior.getFirst().get("response_json").toString(),
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        }
        var source = source(taskId, observation);
        var state = state(observation);
        long asset = asset(source);
        if (number(state, "version") != version || "CLOSED".equals(state.get("damage_status"))) {
            conflict(actor, current, task, observation, "INVENTORY_LATE_DAMAGE_VERSION_CONFLICT");
        }
        if (input.method() == null
                || !List.of("FORMAL_REPAIR", "LINK_REPAIR", "REFRESH_REPAIR")
                        .contains(input.method())
                || input.reason().strip().length() < 3
                || input.evidence().strip().length() < 10) {
            throw new InventoryFailure(400, "INVENTORY_LATE_DAMAGE_INPUT_INVALID");
        }
        boolean refresh = "REFRESH_REPAIR".equals(input.method());
        if (!refresh && !"OPEN".equals(state.get("damage_status"))) {
            throw new InventoryFailure(409, "INVENTORY_REPAIR_PROGRESS_REQUIRED");
        }
        if (!refresh && !assets.matchesVersion(asset, input.assetVersion())) {
            conflict(actor, current, task, observation, "INVENTORY_REVIEW_ASSET_CHANGED");
        }
        InventoryRepairPort.Link linked;
        try {
            if (refresh) {
                if (state.get("repair_id") == null) {
                    throw new InventoryFailure(409, "INVENTORY_REPAIR_LINK_REQUIRED");
                }
                linked = repairs.progress(actor, number(state, "repair_id"), asset);
            } else {
                var damage = input.repair();
                if (damage == null
                        || !damage.physicalDamageChecked()
                        || !damage.discoveryTimeVerified()
                        || damage.verifiedDiscoveryAt() == null
                        || damage.verifiedDiscoveryAt().isAfter(clock.instant())
                        || !damage.verifiedDiscoveryAt()
                                .equals(
                                        damage.verifiedDiscoveryAt()
                                                .truncatedTo(ChronoUnit.MILLIS))) {
                    throw new InventoryFailure(400, "INVENTORY_REPAIR_VERIFICATION_REQUIRED");
                }
                var photos = new ArrayList<UUID>();
                for (var photo :
                        json.readTree(source.get("facts_json").toString()).path("photoIds")) {
                    photos.add(UUID.fromString(photo.asText()));
                }
                if (photos.isEmpty()) {
                    throw new InventoryFailure(409, "INVENTORY_REPAIR_ORIGINAL_PHOTO_REQUIRED");
                }
                var origin =
                        new InventoryRepairPort.Evidence(
                                "LATE",
                                Long.toString(taskId),
                                Long.toString(observation),
                                source.get("client_event_id").toString(),
                                photos,
                                instant(source.get("raw_observed_at")),
                                source.get("facts_json").toString(),
                                damage.verifiedDiscoveryAt(),
                                input.evidence().strip());
                if ("FORMAL_REPAIR".equals(input.method())) {
                    if (damage.repairId() != null
                            || damage.fault() == null
                            || damage.fault().strip().length() < 3
                            || damage.fault().length() > 2000
                            || damage.impact() == null
                            || !List.of("LOW", "MEDIUM", "HIGH").contains(damage.impact())) {
                        throw new InventoryFailure(400, "INVENTORY_REPAIR_SUBMISSION_INVALID");
                    }
                    var repairKey =
                            UUID.nameUUIDFromBytes(
                                    ("inventory-late-damage:"
                                                    + taskId
                                                    + ":"
                                                    + observation
                                                    + ":"
                                                    + key)
                                            .getBytes(StandardCharsets.UTF_8));
                    linked =
                            repairs.create(
                                    actor,
                                    repairKey,
                                    asset,
                                    input.assetVersion(),
                                    damage.fault().strip(),
                                    damage.impact(),
                                    origin);
                } else {
                    if (damage.repairId() == null || damage.repairVersion() == null) {
                        throw new InventoryFailure(400, "INVENTORY_REPAIR_LINK_REQUIRED");
                    }
                    linked =
                            repairs.link(
                                    actor,
                                    damage.repairId(),
                                    asset,
                                    damage.repairVersion(),
                                    origin);
                }
            }
        } catch (InventoryRepairPort.Failure failure) {
            throw new InventoryFailure(failure.status(), failure.getMessage());
        }
        String status =
                "COMPLETED".equals(linked.status())
                        ? "CLOSED"
                        : List.of("REJECTED", "CANCELLED").contains(linked.status())
                                ? "OPEN"
                                : "PROCESSING";
        var now = now();
        int changed =
                db.update(
                        "UPDATE fams_inventory_late_damage SET"
                            + " version=?,damage_status=?,repair_id=?,repair_status=?,updated_at=?"
                            + " WHERE observation_id=? AND version=?",
                        version + 1,
                        status,
                        linked.repairId(),
                        linked.status(),
                        now,
                        observation,
                        version);
        if (changed != 1) {
            throw new InventoryFailure(409, "INVENTORY_LATE_DAMAGE_WRITE_CONFLICT");
        }
        db.update(
                "INSERT INTO"
                    + " fams_inventory_late_damage_decision(observation_id,before_version,after_version,method,damage_status,repair_id,repair_status,reviewed_asset_version,reason,evidence,actor_account_id,actor_username,decided_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                observation,
                version,
                version + 1,
                input.method(),
                status,
                linked.repairId(),
                linked.status(),
                refresh ? null : input.assetVersion(),
                input.reason().strip(),
                input.evidence().strip(),
                current.id(),
                current.username(),
                now);
        if ("CLOSED".equals(status)) {
            db.update(
                    "UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE"
                        + " business_type='INVENTORY_LATE' AND business_id=? AND"
                        + " todo_type='LATE_DAMAGE_REPAIR' AND closed_at IS NULL",
                    now,
                    observation);
        } else {
            db.update(
                    "INSERT INTO"
                        + " fams_todo(pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,opened_at)"
                        + " VALUES"
                        + " ('ASSET_ADMIN',NULL,'INVENTORY_LATE',?,'LATE_DAMAGE_REPAIR','OPEN',?)"
                        + " ON DUPLICATE KEY UPDATE id=id",
                    observation,
                    now);
        }
        var response = new LinkedHashMap<String, Object>();
        response.put("taskId", Long.toString(taskId));
        response.put("observationId", Long.toString(observation));
        response.put("version", version + 1);
        response.put("method", input.method());
        response.put("damageStatus", status);
        response.put("repairId", linked.repairId());
        response.put("repairStatus", linked.status());
        response.put("recheckResult", recheckResult(observation));
        response.put("todoOpen", todoOpen(observation));
        response.put("processedBy", current.username());
        response.put("processedAt", now.toInstant().toString());
        response.put("supplementOnly", true);
        response.put("originalReportChanged", false);
        audit(actor, current, task, "INVENTORY_LATE_DAMAGE_DECIDED", response);
        db.update(
                "INSERT INTO"
                    + " fams_inventory_review_receipt(task_id,actor_account_id,operation,request_id,payload_sha256,response_json,created_at)"
                    + " VALUES (?,?,?,?,?,?,?)",
                taskId,
                current.id(),
                OPERATION,
                key.toString(),
                fingerprint,
                json.writeValueAsString(response),
                now);
        return response;
    }

    private Map<String, Object> source(long task, long observation) {
        var rows =
                db.queryForList(
                        "SELECT"
                            + " c.*,o.client_event_id,o.item_id,o.raw_observed_at,o.facts_json,o.outcome"
                            + " FROM fams_inventory_late_case c JOIN fams_inventory_observation o"
                            + " ON o.id=c.observation_id AND o.task_id=c.task_id WHERE c.task_id=?"
                            + " AND c.observation_id=? FOR SHARE",
                        task,
                        observation);
        if (rows.isEmpty()) {
            throw new InventoryFailure(404, "INVENTORY_LATE_CASE_NOT_FOUND");
        }
        var row = rows.getFirst();
        var packet = json.readTree(row.get("snapshot_json").toString());
        var facts = json.readTree(row.get("facts_json").toString());
        if (!hash(row.get("snapshot_json").toString()).equals(row.get("snapshot_sha256"))
                || !facts.equals(packet.path("facts"))
                || !"DAMAGED".equals(facts.path("actual").path("condition").asText())
                || !List.of("LATE_REVIEW", "TIME_REVIEW").contains(row.get("outcome"))
                || row.get("item_id") == null) {
            throw new InventoryFailure(409, "INVENTORY_LATE_DAMAGE_SOURCE_CHANGED");
        }
        long asset = asset(row);
        var items =
                db.queryForList(
                        "SELECT asset_id FROM fams_inventory_item WHERE task_id=? AND id=? FOR"
                            + " SHARE",
                        task,
                        row.get("item_id"));
        if (items.isEmpty() || number(items.getFirst(), "asset_id") != asset) {
            throw new InventoryFailure(409, "INVENTORY_LATE_DAMAGE_SOURCE_CHANGED");
        }
        return row;
    }

    private long asset(Map<String, Object> source) {
        String asset =
                json.readTree(source.get("snapshot_json").toString()).path("assetId").asText();
        if (!asset.matches("[1-9][0-9]{0,18}")) {
            throw new InventoryFailure(409, "INVENTORY_LATE_DAMAGE_ASSET_REQUIRED");
        }
        return Long.parseLong(asset);
    }

    private Map<String, Object> state(long observation) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_late_damage WHERE observation_id=? FOR"
                            + " UPDATE",
                        observation);
        if (rows.isEmpty()) {
            throw new InventoryFailure(404, "INVENTORY_LATE_DAMAGE_NOT_REQUIRED");
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
        var rows = db.queryForList("SELECT * FROM fams_inventory_task WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new InventoryFailure(404, "INVENTORY_REVIEW_TASK_NOT_FOUND");
        }
        if (!List.of("COMPLETED", "CANCELLED", "TERMINATED")
                .contains(rows.getFirst().get("task_status"))) {
            throw new InventoryFailure(409, "INVENTORY_LATE_TERMINAL_TASK_REQUIRED");
        }
        return rows.getFirst();
    }

    private String recheckResult(long observation) {
        var rows =
                db.queryForList(
                        "SELECT result FROM fams_inventory_late_decision WHERE observation_id=?"
                            + " ORDER BY decision_version DESC LIMIT 1 FOR SHARE",
                        observation);
        return rows.isEmpty() ? "OPEN" : rows.getFirst().get("result").toString();
    }

    private boolean todoOpen(long observation) {
        return !db.queryForList(
                        "SELECT id FROM fams_todo WHERE business_type='INVENTORY_LATE' AND"
                            + " business_id=? AND todo_type IN"
                            + " ('LATE_EVIDENCE_REVIEW','LATE_DAMAGE_REPAIR') AND closed_at IS NULL"
                            + " FOR SHARE",
                        observation)
                .isEmpty();
    }

    private void conflict(
            Actor actor, Current current, Map<String, Object> task, long observation, String code) {
        var fields = new LinkedHashMap<String, String>();
        fields.put(
                "readOnlyUrl",
                (actor.miniToken() == null ? "/api/v1" : "/api/v1/mini")
                        + "/inventory-late/tasks/"
                        + task.get("id")
                        + "/observations/"
                        + observation
                        + "/damage-repair");
        var rows =
                db.queryForList(
                        "SELECT after_version,damage_status,actor_username,decided_at FROM"
                            + " fams_inventory_late_damage_decision WHERE observation_id=? ORDER BY"
                            + " after_version DESC LIMIT 1 FOR SHARE",
                        observation);
        if (!rows.isEmpty()) {
            var row = rows.getFirst();
            fields.put("decisionVersion", row.get("after_version").toString());
            fields.put("result", row.get("damage_status").toString());
            fields.put("processedBy", row.get("actor_username").toString());
            fields.put("processedAt", instant(row.get("decided_at")));
        }
        audit(
                actor,
                current,
                task,
                "INVENTORY_LATE_DAMAGE_CONFLICT",
                Map.of(
                        "observationId",
                        Long.toString(observation),
                        "code",
                        code,
                        "fields",
                        fields));
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
                    (key, item) ->
                            result.put(
                                    key.toString(),
                                    item != null
                                                    && (key.toString().equals("id")
                                                            || key.toString().endsWith("_id"))
                                            ? item.toString()
                                            : wire(item)));
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
        return Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS));
    }

    private String instant(Object value) {
        return (value instanceof Timestamp timestamp
                        ? timestamp.toInstant()
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
