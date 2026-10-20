package com.fams.inventory;

import com.fams.auth.InventoryIdentityPort;
import com.fams.auth.InventorySynchronizationIdentityPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Current synchronization is derived, never inferred from an immutable original receipt. */
@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class InventorySynchronizationService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RequisitionActorContext actors;
    private final InventoryIdentityPort identities;
    private final InventorySynchronizationIdentityPort states;
    private final Clock clock;

    public InventorySynchronizationService(
            JdbcTemplate db,
            ObjectMapper json,
            RequisitionActorContext actors,
            InventoryIdentityPort identities,
            InventorySynchronizationIdentityPort states,
            Clock clock) {
        this.db = db;
        this.json = json;
        this.actors = actors;
        this.identities = identities;
        this.states = states;
        this.clock = clock;
    }

    public Map<String, Object> own(Actor actor, long taskId, UUID device) {
        var current = actors.revalidateLocked(actor);
        Long binding = identities.bindingId(actor);
        if (binding == null) {
            throw new InventoryFailure(403, "INVENTORY_MINI_BINDING_REQUIRED");
        }
        var task = task(taskId);
        participant(taskId, current.id());
        var state = states.states(List.of(current.id())).get(current.id());
        var result = new LinkedHashMap<String, Object>();
        result.put("taskId", Long.toString(taskId));
        result.put("taskVersion", number(task, "version"));
        result.put("taskStatus", task.get("task_status"));
        result.put("assignmentVersion", number(task, "assignment_version"));
        result.put("deviceId", device.toString());
        result.put(
                "observationWatermark",
                Long.toString(watermark(taskId, current.id(), binding, device.toString())));
        result.put("device", device(taskId, current.id(), binding, device.toString(), task, state));
        result.put("declarationOnly", true);
        return result;
    }

    public Map<String, Object> declare(
            Actor actor,
            long taskId,
            long version,
            UUID key,
            InventorySynchronizationInput.Declaration input) {
        var current = actors.revalidateLocked(actor);
        Long binding = identities.bindingId(actor);
        if (binding == null) {
            throw new InventoryFailure(403, "INVENTORY_MINI_BINDING_REQUIRED");
        }
        var task = task(taskId);
        participant(taskId, current.id());
        String payload = sha(version + ":" + json.writeValueAsString(input));
        var prior =
                db.queryForList(
                        "SELECT actor_account_id,binding_id,payload_sha256,CAST(response_json AS"
                            + " CHAR) AS response FROM fams_inventory_sync_receipt WHERE task_id=?"
                            + " AND request_id=?",
                        taskId,
                        key.toString());
        if (!prior.isEmpty()) {
            var row = prior.getFirst();
            if (number(row, "actor_account_id") != current.id()
                    || number(row, "binding_id") != binding
                    || !payload.equals(row.get("payload_sha256"))) {
                throw new InventoryFailure(409, "INVENTORY_SYNC_REQUEST_CONFLICT");
            }
            return json.readValue(
                    row.get("response").toString(),
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        }
        if (!List.of("IN_PROGRESS", "WAITING_REVIEW").contains(task.get("task_status"))
                || number(task, "version") != version
                || number(task, "assignment_version") != input.assignmentVersion()
                || watermark(taskId, current.id(), binding, input.deviceId().toString())
                        != Long.parseLong(input.observationWatermark())) {
            throw new InventoryFailure(409, "INVENTORY_SYNC_CONTEXT_CHANGED");
        }
        var state = states.states(List.of(current.id())).get(current.id());
        if (state == null || !state.enabled() || !Objects.equals(state.bindingId(), binding)) {
            throw new InventoryFailure(403, "INVENTORY_SYNC_IDENTITY_INVALID");
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        db.update(
                "INSERT INTO fams_inventory_sync_declaration"
                    + "(task_id,actor_account_id,binding_id,binding_version,device_id,assignment_version,observation_watermark,queue_digest,unresolved_count,declared_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                taskId,
                current.id(),
                binding,
                state.bindingVersion(),
                input.deviceId().toString(),
                input.assignmentVersion(),
                Long.parseLong(input.observationWatermark()),
                sha(input.queueSnapshot()),
                input.unresolvedCount(),
                Timestamp.from(now));
        long id = Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        db.update(
                "UPDATE fams_inventory_task SET version=version+1,updated_at=? WHERE id=?",
                Timestamp.from(now),
                taskId);
        var response = new LinkedHashMap<String, Object>();
        response.put("declarationId", Long.toString(id));
        response.put("taskId", Long.toString(taskId));
        response.put("taskVersion", version + 1);
        response.put("deviceId", input.deviceId().toString());
        response.put("declaredAt", now.toString());
        response.put("declarationOnly", true);
        db.update(
                "INSERT INTO"
                    + " fams_inventory_task_event(task_id,actor_account_id,action,detail_json,occurred_at)"
                    + " VALUES (?,?,?,?,?)",
                taskId,
                current.id(),
                "SYNCHRONIZATION_DECLARED",
                json.writeValueAsString(response),
                Timestamp.from(now));
        db.update(
                "INSERT INTO fams_audit_event"
                    + "(request_id,actor_account_id,actor_role,action,resource_type,resource_id,"
                    + "before_version,after_version,occurred_at,detail_json) VALUES"
                    + " (?,?,?,'SYNCHRONIZATION_DECLARED','INVENTORY_TASK',?,?,?,?,?)",
                actor.requestId().toString(),
                current.id(),
                current.roles().stream().map(Enum::name).sorted().findFirst().orElseThrow(),
                taskId,
                version,
                version + 1,
                Timestamp.from(now),
                json.writeValueAsString(response));
        db.update(
                "INSERT INTO fams_inventory_sync_receipt"
                    + "(task_id,request_id,actor_account_id,binding_id,payload_sha256,response_json,created_at)"
                    + " VALUES (?,?,?,?,?,?,?)",
                taskId,
                key.toString(),
                current.id(),
                binding,
                payload,
                json.writeValueAsString(response),
                Timestamp.from(now));
        return response;
    }

    public Map<String, Object> status(Actor actor, long taskId, int page, int size) {
        if (!actors.revalidateLocked(actor).has(Role.ASSET_ADMIN)) {
            throw new InventoryFailure(403, "INVENTORY_ADMIN_REQUIRED");
        }
        var task = task(taskId);
        var participants =
                db.queryForList(
                        "SELECT account_id,revoked_at FROM fams_inventory_participant WHERE"
                                + " task_id=? ORDER BY account_id LIMIT ? OFFSET ?",
                        taskId,
                        size,
                        (page - 1) * size);
        var identityStates =
                states.states(participants.stream().map(row -> number(row, "account_id")).toList());
        var rows = new ArrayList<Map<String, Object>>();
        for (var participant : participants) {
            long account = number(participant, "account_id");
            var state = identityStates.get(account);
            var devices =
                    db.queryForList(
                            "SELECT binding_id,device_id FROM fams_inventory_observation WHERE"
                                + " task_id=? AND actor_account_id=? UNION SELECT"
                                + " binding_id,device_id FROM fams_inventory_sync_declaration WHERE"
                                + " task_id=? AND actor_account_id=? ORDER BY binding_id,device_id",
                            taskId,
                            account,
                            taskId,
                            account);
            var declaredDevices = new ArrayList<Map<String, Object>>();
            for (var row : devices) {
                declaredDevices.add(
                        device(
                                taskId,
                                account,
                                row.get("binding_id") == null ? null : number(row, "binding_id"),
                                row.get("device_id").toString(),
                                task,
                                state));
            }
            String reason =
                    participant.get("revoked_at") != null
                            ? "PARTICIPATION_REVOKED"
                            : state == null || !state.enabled()
                                    ? "IDENTITY_DISABLED"
                                    : state.bindingId() == null
                                            ? "BINDING_MISSING"
                                            : devices.isEmpty()
                                                    ? "NO_DEVICE_DECLARATION"
                                                    : declaredDevices.stream()
                                                                    .allMatch(
                                                                            row ->
                                                                                    Boolean.TRUE
                                                                                            .equals(
                                                                                                    row
                                                                                                            .get(
                                                                                                                    "current")))
                                                            ? "DECLARED"
                                                            : "DEVICE_CONFIRMATION_MISSING";
            var row = new LinkedHashMap<String, Object>();
            row.put("accountId", Long.toString(account));
            row.put("name", state == null ? "身份待核实" : state.name());
            row.put("current", "DECLARED".equals(reason));
            row.put("reason", reason);
            row.put("devices", declaredDevices);
            rows.add(row);
        }
        long total =
                Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM fams_inventory_participant WHERE task_id=?",
                                Long.class,
                                taskId));
        return Map.of(
                "items",
                rows,
                "total",
                total,
                "page",
                page,
                "pageSize",
                size,
                "taskVersion",
                number(task, "version"),
                "declarationOnly",
                true);
    }

    private Map<String, Object> device(
            long task,
            long actor,
            Long binding,
            String device,
            Map<String, Object> taskRow,
            InventorySynchronizationIdentityPort.State identity) {
        var rows =
                db.queryForList(
                        "SELECT"
                            + " id,binding_version,assignment_version,observation_watermark,unresolved_count,declared_at"
                            + " FROM fams_inventory_sync_declaration WHERE task_id=? AND"
                            + " actor_account_id=? AND binding_id<=>? AND device_id=? ORDER BY id"
                            + " DESC LIMIT 1",
                        task,
                        actor,
                        binding,
                        device);
        String reason =
                identity == null || !identity.enabled()
                        ? "IDENTITY_DISABLED"
                        : binding == null
                                ? "UNBOUND_OBSERVATION"
                                : !Objects.equals(identity.bindingId(), binding)
                                        ? "BINDING_CHANGED"
                                        : rows.isEmpty()
                                                ? "NO_DECLARATION"
                                                : !Objects.equals(
                                                                identity.bindingVersion(),
                                                                number(
                                                                        rows.getFirst(),
                                                                        "binding_version"))
                                                        ? "BINDING_CHANGED"
                                                        : number(
                                                                                rows.getFirst(),
                                                                                "assignment_version")
                                                                        != number(
                                                                                taskRow,
                                                                                "assignment_version")
                                                                ? "ASSIGNMENT_CHANGED"
                                                                : number(
                                                                                        rows
                                                                                                .getFirst(),
                                                                                        "observation_watermark")
                                                                                != watermark(
                                                                                        task, actor,
                                                                                        binding,
                                                                                        device)
                                                                        ? "NEW_OBSERVATION"
                                                                        : number(
                                                                                                rows
                                                                                                        .getFirst(),
                                                                                                "unresolved_count")
                                                                                        > 0
                                                                                ? "LOCAL_EVIDENCE_MISSING"
                                                                                : "DECLARED";
        var result = new LinkedHashMap<String, Object>();
        result.put("deviceId", device);
        result.put("bindingId", binding == null ? null : binding.toString());
        result.put("current", "DECLARED".equals(reason));
        result.put("reason", reason);
        if (!rows.isEmpty()) {
            result.put("declarationId", rows.getFirst().get("id").toString());
            Object time = rows.getFirst().get("declared_at");
            result.put(
                    "declaredAt",
                    time instanceof Timestamp timestamp
                            ? timestamp.toInstant().toString()
                            : ((java.time.LocalDateTime) time)
                                    .toInstant(java.time.ZoneOffset.UTC)
                                    .toString());
            result.put("unresolvedCount", number(rows.getFirst(), "unresolved_count"));
        }
        return result;
    }

    private long watermark(long task, long actor, Long binding, String device) {
        return Objects.requireNonNull(
                db.queryForObject(
                        "SELECT COALESCE(MAX(id),0) FROM fams_inventory_observation WHERE task_id=?"
                                + " AND actor_account_id=? AND binding_id<=>? AND device_id=?",
                        Long.class,
                        task,
                        actor,
                        binding,
                        device));
    }

    private void participant(long task, long actor) {
        if (db.queryForList(
                        "SELECT account_id FROM fams_inventory_participant"
                                + " WHERE task_id=? AND account_id=? AND revoked_at IS NULL",
                        task,
                        actor)
                .isEmpty()) {
            throw new InventoryFailure(403, "INVENTORY_PARTICIPATION_REQUIRED");
        }
    }

    private Map<String, Object> task(long id) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_task WHERE id=? AND task_status<>'DELETED'"
                                + " FOR UPDATE",
                        id);
        if (rows.isEmpty()) {
            throw new InventoryFailure(404, "INVENTORY_TASK_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static String sha(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
