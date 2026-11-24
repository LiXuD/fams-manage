package com.fams.inventory;

import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Supplemental decisions never update the terminal source or its frozen result. */
@Service
@Profile("fams-product")
public class InventoryLateService {
    private static final Logger LOG = LoggerFactory.getLogger(InventoryLateService.class);
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RequisitionActorContext actors;
    private final InventoryLateEvidencePort evidence;
    private final InventoryService inventory;
    private final Clock clock;

    /**
     * Injects the collaborators the supplemental late review flow needs.
     *
     * @param db reads and writes the late case, late decision, todo and receipt tables
     * @param json projects the recursive late wire and serializes stored receipt texts
     * @param actors revalidates the caller session and proves the ASSET_ADMIN role
     * @param evidence appends a terminal observation as a new late case with its frozen snapshot
     * @param inventory copies the terminal task into the recheck draft a follow-up decision opens
     * @param clock stamps every persisted decision instant
     */
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

    /**
     * Lists one page of the late cases of a terminal task. Each case row is assembled with its three
     * FOR SHARE companion reads - latest decision, damage row and todo probe - before the COUNT query
     * runs, which keeps the frozen lock read order. Nothing is written.
     *
     * @param actor caller who must hold the ASSET_ADMIN role
     * @param taskId inventory task, which must already be COMPLETED, CANCELLED or TERMINATED
     * @param page one-based page number
     * @param size maximum number of cases per page
     * @return task header, the case page in ascending observation id order, the paging echo, the case
     *     total and the supplement-only flag
     * @throws InventoryFailure 403 INVENTORY_REVIEW_ADMIN_REQUIRED without the ASSET_ADMIN role, 409
     *     INVENTORY_LATE_RECHECK_NOT_FOUND for an unknown task, or 409
     *     INVENTORY_LATE_TERMINAL_TASK_REQUIRED while the task is not terminal
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public InventoryViews.InventoryLateCasePage list(Actor actor, long taskId, int page, int size) {
        admin(actor);
        var task = task(taskId);
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_late_case WHERE task_id=? ORDER BY"
                                + " observation_id LIMIT ? OFFSET ? FOR SHARE",
                        taskId,
                        size,
                        (page - 1) * size);
        // The legacy Map.of evaluated the case items (each running its own three FOR SHARE reads)
        // before the COUNT query, so items materialize first and the lock read order is unchanged.
        var items = rows.stream().map(this::view).toList();
        LOG.debug("Late case page requested for task {} page {} size {}.", taskId, page, size);
        return new InventoryViews.InventoryLateCasePage(
                InventoryViews.inventoryLateTaskHeader(json, task),
                items,
                page,
                size,
                Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM fams_inventory_late_case WHERE task_id=? FOR"
                                        + " SHARE",
                                Long.class,
                                taskId)),
                true);
    }

    /**
     * Assembles one late case with its decision history. The read order stays frozen - decision page
     * rows then its COUNT, then the case view with the latest decision, the damage row and the todo
     * probe - and the history is paged in ascending decision_version order while the case chip always
     * shows the newest decision. Nothing is written.
     *
     * @param actor caller who must hold the ASSET_ADMIN role
     * @param taskId inventory task, which must already be COMPLETED, CANCELLED or TERMINATED
     * @param observationId observation whose late case is requested
     * @param page one-based page number of the decision history
     * @param size maximum number of decisions per page
     * @return task header, the case with its review chip, damage chip and todo state, the decision
     *     history page and the supplement-only flag
     * @throws InventoryFailure 403 INVENTORY_REVIEW_ADMIN_REQUIRED without the ASSET_ADMIN role, 409
     *     INVENTORY_LATE_RECHECK_NOT_FOUND for an unknown task, 409
     *     INVENTORY_LATE_TERMINAL_TASK_REQUIRED while the task is not terminal, or 404
     *     INVENTORY_LATE_CASE_NOT_FOUND when the observation has no late case in this task
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public InventoryViews.InventoryLateCaseDetail detail(
            Actor actor, long taskId, long observationId, int page, int size) {
        admin(actor);
        var task = task(taskId);
        var row = lateCase(taskId, observationId);
        // Read order of the legacy assembly: decision page (rows, then COUNT), then the case view
        // (latest decision, damage row, todo probe). Not one query moved.
        var history =
                new InventoryViews.InventoryLateDecisionPage(
                        InventoryViews.inventoryLateDecisions(
                                db.queryForList(
                                        "SELECT * FROM fams_inventory_late_decision WHERE"
                                            + " observation_id=? ORDER BY decision_version LIMIT ?"
                                            + " OFFSET ? FOR SHARE",
                                        observationId,
                                        size,
                                        (page - 1) * size)),
                        page,
                        size,
                        Objects.requireNonNull(
                                db.queryForObject(
                                        "SELECT COUNT(*) FROM fams_inventory_late_decision WHERE"
                                                + " observation_id=? FOR SHARE",
                                        Long.class,
                                        observationId)));
        var previous = latest(observationId);
        var damage = damageRow(observationId);
        LOG.debug("Late case detail requested for observation {} page {} size {}.", observationId, page, size);
        return new InventoryViews.InventoryLateCaseDetail(
                InventoryViews.inventoryLateTaskHeader(json, task),
                InventoryViews.inventoryLateCaseView(
                        json, row, previous, damage, todoOpen(observationId)),
                history,
                true);
    }

    /**
     * Opens late cases for the terminal task's unhandled supplemental evidence. One run takes at most
     * 100 LATE_REVIEW or TIME_REVIEW observations that have neither a late case nor sealed evidence
     * yet, in ascending observation id order, and appends each through the late evidence port; the
     * receipt reports whether another run may still be needed. The request id makes the call idempotent
     * against the stored PREPARE_LATE receipt.
     *
     * <p>State impact: appends one fams_inventory_late_case row per newly handled observation, audits
     * INVENTORY_LATE_PREPARED and stores the receipt. The task version is not bumped.
     *
     * @param actor caller who must hold the ASSET_ADMIN role
     * @param taskId inventory task, which must already be COMPLETED, CANCELLED or TERMINATED
     * @param version task version the caller read, compared against the locked task row
     * @param key client request id used for the review receipt
     * @return receipt with the task id, the number of cases prepared, whether more may remain, the task
     *     version and the supplement-only flag
     * @throws InventoryReviewConflict INVENTORY_LATE_TASK_CHANGED when the task version differs, or
     *     INVENTORY_LATE_IDEMPOTENCY_CONFLICT when the request id was already used with a different
     *     payload; both are audited and do not roll the transaction back
     * @throws InventoryFailure 403 INVENTORY_REVIEW_ADMIN_REQUIRED without the ASSET_ADMIN role, 409
     *     INVENTORY_LATE_RECHECK_NOT_FOUND for an unknown task, or 409
     *     INVENTORY_LATE_TERMINAL_TASK_REQUIRED while the task is not terminal
     */
    @Transactional(
        isolation = Isolation.REPEATABLE_READ,
        rollbackFor = Exception.class,
        noRollbackFor = InventoryReviewConflict.class)
    public InventoryViews.InventoryLatePrepareReceipt prepare(
            Actor actor, long taskId, long version, UUID key) {
        var current = admin(actor);
        var task = task(taskId);
        String payload = hash("prepare\n" + version);
        var prior =
                original(
                        actor,
                        current,
                        task,
                        "PREPARE_LATE",
                        key,
                        payload,
                        InventoryViews.InventoryLatePrepareReceipt.class);
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
            evidence.append(InventoryLateEvidencePort.Task.fromRow(task), number(row, "id"));
        }
        var response =
                new InventoryViews.InventoryLatePrepareReceipt(
                        Long.toString(taskId), rows.size(), rows.size() == 100, version, true);
        audit(actor, current, task, "INVENTORY_LATE_PREPARED", response);
        receipt(current, taskId, "PREPARE_LATE", key, payload, response);
        LOG.info("Late case preparation committed for task {} by actor {}.", taskId, actor.id());
        return response;
    }

    /**
     * Records one supplemental decision on a late case, always as a new decision version and never as an
     * update of the terminal source. The expected version is the latest decision_version of the case, so
     * two reviewers cannot append the same version. NOTE closes a case that needs no recheck, LINK_RECHECK
     * copies the terminal task into a new recheck draft and leaves a follow-up open, REPLACE_RECHECK opens
     * a fresh draft only when the previous follow-up task is already terminal, and CONFIRM_RECHECK closes
     * the case after proving the linked recheck completed with an ACCEPTED sealed snapshot of the same
     * asset. The request id makes the call idempotent against the stored LATE_DECISION receipt.
     *
     * <p>State impact: appends the decision row, opens or closes the LATE_EVIDENCE_REVIEW todo, creates
     * the recheck draft task through {@code InventoryService.copy} when the method asks for one, audits
     * INVENTORY_LATE_DECIDED and stores the receipt.
     *
     * @param actor caller who must hold the ASSET_ADMIN role
     * @param taskId inventory task, which must already be COMPLETED, CANCELLED or TERMINATED
     * @param observationId observation whose late case is decided
     * @param version latest decision version the caller read, 0 for a never-decided case
     * @param key client request id used for the review receipt
     * @param input method, reason of at least three characters, evidence of at least three characters
     *     and, for the two recheck-opening methods, the draft of the recheck task
     * @return receipt with the task id, observation id, the new decision version, the result, method,
     *     linked task id, decider name and time, and the two supplement flags
     * @throws InventoryReviewConflict INVENTORY_LATE_VERSION_CONFLICT when the case was decided again
     *     in the meantime, or INVENTORY_LATE_IDEMPOTENCY_CONFLICT when the request id was already used
     *     with a different payload; both are audited and do not roll the transaction back
     * @throws InventoryFailure 403 INVENTORY_REVIEW_ADMIN_REQUIRED without the ASSET_ADMIN role, 409
     *     INVENTORY_LATE_RECHECK_NOT_FOUND for an unknown task or missing recheck task, 409
     *     INVENTORY_LATE_TERMINAL_TASK_REQUIRED while the task is not terminal, 404
     *     INVENTORY_LATE_CASE_NOT_FOUND when the observation has no late case, 400
     *     INVENTORY_LATE_DECISION_INVALID for an unknown method or a reason/evidence shorter than three
     *     characters, 400 INVENTORY_LATE_UNEXPECTED_RECHECK when NOTE or CONFIRM_RECHECK carries a
     *     draft, 400 INVENTORY_LATE_RECHECK_INPUT_REQUIRED when a recheck method carries none, 409
     *     INVENTORY_LATE_RECHECK_REQUIRED when the case still needs a recheck, 409
     *     INVENTORY_LATE_FOLLOWUP_ALREADY_OPEN, 409 INVENTORY_LATE_FOLLOWUP_REQUIRED, 409
     *     INVENTORY_LATE_RECHECK_REPLACEMENT_REFUSED, 409 INVENTORY_LATE_FINDING_FOLLOWUP_REQUIRED, 409
     *     INVENTORY_LATE_RECHECK_NOT_COMPLETED or 409 INVENTORY_LATE_RECHECK_ACCEPTED_ASSET_REQUIRED
     */
    @Transactional(
        isolation = Isolation.REPEATABLE_READ,
        rollbackFor = Exception.class,
        noRollbackFor = InventoryReviewConflict.class)
    public InventoryViews.InventoryLateDecisionReceipt decide(
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
        var prior =
                original(
                        actor,
                        current,
                        task,
                        "LATE_DECISION",
                        key,
                        payload,
                        InventoryViews.InventoryLateDecisionReceipt.class);
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
                linked = Long.valueOf(draft.id());
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
                    + " fams_inventory_late_decision(observation_id,decision_version,method,result,reason,evidence,"
                    + "linked_task_id,actor_account_id,actor_username,decided_at)"
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
                        + " fams_todo(pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,"
                        + "opened_at)"
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
        // Declaration order is the legacy LinkedHashMap put order, so the receipt text written
        // into fams_inventory_review_receipt.response_json stays byte-identical.
        var response =
                new InventoryViews.InventoryLateDecisionReceipt(
                        Long.toString(taskId),
                        Long.toString(observationId),
                        version + 1,
                        result,
                        input.method(),
                        linked == null ? null : linked.toString(),
                        current.username(),
                        now.toInstant().toString(),
                        true,
                        false);
        audit(actor, current, task, "INVENTORY_LATE_DECIDED", response);
        receipt(current, taskId, "LATE_DECISION", key, payload, response);
        LOG.info("Late case decision committed for observation {} by actor {}.", observationId, actor.id());
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

    /** One late case row plus its three FOR SHARE companion reads, in the legacy order. */
    private InventoryViews.InventoryLateCaseView view(Map<String, Object> row) {
        long observation = number(row, "observation_id");
        var previous = latest(observation);
        var damage = damageRow(observation);
        return InventoryViews.inventoryLateCaseView(
                json, row, previous, damage, todoOpen(observation));
    }

    private Map<String, Object> damageRow(long observation) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_late_damage WHERE observation_id=? FOR SHARE",
                        observation);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean todoOpen(long observation) {
        return !db.queryForList(
                        "SELECT id FROM fams_todo WHERE business_type='INVENTORY_LATE' AND"
                            + " business_id=? AND todo_type IN"
                            + " ('LATE_EVIDENCE_REVIEW','LATE_DAMAGE_REPAIR') AND closed_at"
                            + " IS NULL FOR SHARE",
                        observation)
                .isEmpty();
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

    /**
     * Stored-reply replay. The two operations of this service store different bodies, so the caller
     * names the type to replay into (JAVA-11a/15 {@code Class<T>} blueprint).
     */
    private <T> T original(
            Actor actor,
            Current current,
            Map<String, Object> task,
            String operation,
            UUID key,
            String payload,
            Class<T> type) {
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
        return json.readValue(rows.getFirst().get("response").toString(), type);
    }

    private void receipt(
            Current current,
            long task,
            String operation,
            UUID key,
            String payload,
            Object response) {
        db.update(
                "INSERT INTO"
                    + " fams_inventory_review_receipt(task_id,actor_account_id,operation,request_id,payload_sha256,"
                    + "response_json,created_at)"
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
                    + " fams_audit_event(request_id,actor_account_id,actor_role,action,resource_type,resource_id,"
                    + "before_version,after_version,occurred_at,detail_json)"
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
