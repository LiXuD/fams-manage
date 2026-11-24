package com.fams.inventory;

import com.fams.auth.InventoryIdentityPort;
import com.fams.auth.InventorySynchronizationIdentityPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.Role;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Current synchronization is derived, never inferred from an immutable original receipt. */
@Service
@Profile("fams-product")
public class InventorySynchronizationService {
    private static final Logger LOG = LoggerFactory.getLogger(InventorySynchronizationService.class);
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RequisitionActorContext actors;
    private final InventoryIdentityPort identities;
    private final InventorySynchronizationIdentityPort states;
    private final Clock clock;

    /**
     * Injects the collaborators the device synchronization flow needs.
     *
     * @param db reads and writes the participant, observation, declaration and receipt tables
     * @param json serializes declaration payloads into the digest and the stored receipt text
     * @param actors revalidates the caller session and proves the ASSET_ADMIN role for the roster
     * @param identities resolves the device binding id behind the caller session
     * @param states supplies the current identity state and binding version of each participant
     * @param clock stamps the declaration instant
     */
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

    /**
     * Reports the caller's own device synchronization state as the mini app needs it before it may
     * declare. The watermark is the highest observation id this binding and device already submitted,
     * and the device chip carries the derived reason - DECLARED, NO_DECLARATION, NEW_OBSERVATION,
     * BINDING_CHANGED, ASSIGNMENT_CHANGED, LOCAL_EVIDENCE_MISSING, IDENTITY_DISABLED or
     * UNBOUND_OBSERVATION. Nothing is written.
     *
     * @param actor caller whose session is revalidated under the task lock and who must be a
     *     non-revoked participant
     * @param taskId inventory task the device state is scoped to
     * @param device device id the caller is synchronizing from
     * @return task id, version, status and assignment version, the device id, its observation
     *     watermark, the device chip and the declaration-only flag
     * @throws InventoryFailure 403 INVENTORY_MINI_BINDING_REQUIRED when the session has no device
     *     binding, 404 INVENTORY_TASK_NOT_FOUND when the task is missing or DELETED, or 403
     *     INVENTORY_PARTICIPATION_REQUIRED when the caller is not a live participant of the task
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public SealViews.OwnDeviceState own(Actor actor, long taskId, UUID device) {
        var current = actors.revalidateLocked(actor);
        Long binding = identities.bindingId(actor);
        if (binding == null) {
            throw new InventoryFailure(403, "INVENTORY_MINI_BINDING_REQUIRED");
        }
        var task = task(taskId);
        participant(taskId, current.id());
        var state = states.states(List.of(current.id())).get(current.id());
        LOG.debug("Own device synchronization state requested for task {}.", taskId);
        return new SealViews.OwnDeviceState(
                Long.toString(taskId),
                number(task, "version"),
                (String) task.get("task_status"),
                number(task, "assignment_version"),
                device.toString(),
                Long.toString(watermark(taskId, current.id(), binding, device.toString())),
                device(taskId, current.id(), binding, device.toString(), task, state),
                true);
    }

    /**
     * Records one device synchronization declaration after proving the caller still holds the context
     * it read. The request id makes the call idempotent: a stored fams_inventory_sync_receipt with the
     * same fingerprint replays, a different one is rejected as a request conflict. The context check
     * covers the task status and version, the assignment version and the observation watermark, so a
     * device that fell behind cannot declare against a stale view.
     *
     * <p>State impact: appends the declaration row with the binding version and the queue digest,
     * bumps the task version, writes the SYNCHRONIZATION_DECLARED task event and audit event, and
     * stores the reply as the sync receipt.
     *
     * @param actor caller whose session is revalidated under the task lock and who must be a
     *     non-revoked participant
     * @param taskId inventory task the declaration belongs to
     * @param version task version the caller read, compared against the locked task row
     * @param key client request id used for the sync receipt
     * @param input device id, assignment version, observation watermark, queue snapshot and unresolved
     *     local evidence count as the device reported them
     * @return declaration id, task id, the bumped task version, device id, declaration time and the
     *     declaration-only flag
     * @throws InventoryFailure 403 INVENTORY_MINI_BINDING_REQUIRED when the session has no device
     *     binding, 404 INVENTORY_TASK_NOT_FOUND when the task is missing or DELETED, 403
     *     INVENTORY_PARTICIPATION_REQUIRED for a caller who is not a live participant, 409
     *     INVENTORY_SYNC_CONTEXT_CHANGED when the task status, version, assignment version or
     *     watermark moved, 403 INVENTORY_SYNC_IDENTITY_INVALID when the identity state is missing,
     *     disabled or bound elsewhere, or 409 INVENTORY_SYNC_REQUEST_CONFLICT when the request id was
     *     already used by a different actor, binding or payload
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public SealViews.SyncDeclarationReceipt declare(
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
        var replay = replay(taskId, key, payload, current, binding,
                SealViews.SyncDeclarationReceipt.class);
        if (replay != null) {
            return replay;
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
                    + "(task_id,actor_account_id,binding_id,binding_version,device_id,assignment_version,"
                    + "observation_watermark,queue_digest,unresolved_count,declared_at)"
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
        var response =
                new SealViews.SyncDeclarationReceipt(
                        Long.toString(id),
                        Long.toString(taskId),
                        version + 1,
                        input.deviceId().toString(),
                        now.toString(),
                        true);
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
        LOG.info("Device synchronization declaration committed for task {} by actor {}.", taskId, actor.id());
        return response;
    }

    /**
     * Derives one page of the participant synchronization roster for an admin. Every participant is
     * judged from current facts - identity state, binding, the union of observed and declared devices
     * and the live watermark - and gets a reason: PARTICIPATION_REVOKED, IDENTITY_DISABLED,
     * BINDING_MISSING, NO_DEVICE_DECLARATION, DEVICE_CONFIRMATION_MISSING or DECLARED. The seal flow
     * walks this exit page by page and hashes the accumulated roster into sync_digest, so the same
     * roster must be reproducible within one transaction.
     *
     * @param actor caller who must hold the ASSET_ADMIN role
     * @param taskId inventory task whose participants are listed
     * @param page one-based page number
     * @param size maximum number of participants per page
     * @return roster page in ascending account id order, the participant total, the paging echo, the
     *     current task version and the declaration-only flag
     * @throws InventoryFailure 403 INVENTORY_ADMIN_REQUIRED without the ASSET_ADMIN role, or 404
     *     INVENTORY_TASK_NOT_FOUND when the task is missing or DELETED
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public SealViews.SynchronizationStatus status(
            Actor actor, long taskId, int page, int size) {
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
        var rows = new ArrayList<SealViews.SyncParticipant>();
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
            var declaredDevices = new ArrayList<SealViews.DeviceRow>();
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
                                                                            SealViews.DeviceRow
                                                                                    ::current)
                                                            ? "DECLARED"
                                                            : "DEVICE_CONFIRMATION_MISSING";
            rows.add(
                    new SealViews.SyncParticipant(
                            Long.toString(account),
                            state == null ? "身份待核实" : state.name(),
                            "DECLARED".equals(reason),
                            reason,
                            declaredDevices));
        }
        long total =
                Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM fams_inventory_participant WHERE task_id=?",
                                Long.class,
                                taskId));
        LOG.debug("Synchronization status requested for task {} page {} size {}.", taskId, page, size);
        return new SealViews.SynchronizationStatus(
                rows, total, page, size, number(task, "version"), true);
    }

    private SealViews.DeviceRow device(
            long task,
            long actor,
            Long binding,
            String device,
            Map<String, Object> taskRow,
            InventorySynchronizationIdentityPort.State identity) {
        var rows =
                db.queryForList(
                        "SELECT"
                            + " id,binding_version,assignment_version,observation_watermark,unresolved_count,"
                            + "declared_at"
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
        return SealViews.device(
                device, binding, reason, rows.isEmpty() ? null : rows.getFirst());
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

    /**
     * Replays the stored fams_inventory_sync_receipt reply of an already accepted declaration.
     * The query text, the three identity/payload checks and their evaluation order are the
     * pre-refactor inline block verbatim; only the reply projection became typed.
     */
    private <T> T replay(
            long taskId,
            UUID key,
            String payload,
            RequisitionActorContext.Current current,
            Long binding,
            Class<T> type) {
        var prior =
                db.queryForList(
                        "SELECT actor_account_id,binding_id,payload_sha256,CAST(response_json AS"
                            + " CHAR) AS response FROM fams_inventory_sync_receipt WHERE task_id=?"
                            + " AND request_id=?",
                        taskId,
                        key.toString());
        if (prior.isEmpty()) {
            return null;
        }
        var row = prior.getFirst();
        if (number(row, "actor_account_id") != current.id()
                || number(row, "binding_id") != binding
                || !payload.equals(row.get("payload_sha256"))) {
            throw new InventoryFailure(409, "INVENTORY_SYNC_REQUEST_CONFLICT");
        }
        return json.readValue(row.get("response").toString(), type);
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
