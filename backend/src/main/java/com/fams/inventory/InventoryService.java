package com.fams.inventory;

import com.fams.auth.InventoryExecutorPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;
import com.fams.masterdata.AssetMasterDataPort;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Task locks serialize lifecycle and assignment; published snapshots never mutate. */
@Service
@Profile("fams-product")
public class InventoryService {
    private static final Logger LOG = LoggerFactory.getLogger(InventoryService.class);
    private static final tools.jackson.core.type.TypeReference<Map<String, Object>> WIRE_MAP =
            new tools.jackson.core.type.TypeReference<>() {};

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final RequisitionActorContext actors;
    private final InventoryExecutorPort executors;
    private final AssetMasterDataPort parameters;
    private final com.fams.masterdata.InventoryReferencePort references;

    public InventoryService(
            JdbcTemplate db,
            ObjectMapper json,
            Clock clock,
            RequisitionActorContext actors,
            InventoryExecutorPort executors,
            AssetMasterDataPort parameters,
            com.fams.masterdata.InventoryReferencePort references) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actors = actors;
        this.executors = executors;
        this.parameters = parameters;
        this.references = references;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public InventoryViews.InventoryReferenceChoicePage referenceChoices(
            Actor actor, String kind, int page, int size) {
        admin(actor);
        LOG.debug("Inventory reference choices requested kind {} page {} size {}.", kind, page, size);
        return new InventoryViews.InventoryReferenceChoicePage(
                references.choices(kind, page, size), page, size);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public InventoryViews.InventoryPreviewDigest preview(
            Actor actor, InventoryInput.Range range) {
        admin(actor);
        var predicate = InventoryRange.predicate(range);
        LOG.debug("Inventory preview digest requested by actor {}.", actor.id());
        return digest(
                "SELECT asset_id,asset_version,SHA2(CAST(snapshot_json AS CHAR),256) AS row_hash"
                        + " FROM "
                        + InventoryRange.SOURCE
                        + predicate.sql()
                        + " ORDER BY asset_id",
                predicate.args());
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public InventoryViews.InventoryTaskRow create(
            Actor actor, String key, InventoryInput.Draft input) {
        var current = admin(actor);
        var prior = replay(current, "create", key, 0, input);
        if (prior != null) {
            return prior;
        }
        validateDraft(input);
        db.update(
                "INSERT INTO"
                    + " fams_inventory_task(name,range_json,starts_at,due_at,creator_account_id,created_at,updated_at)"
                    + " VALUES (?,?,?,?,?,?,?)",
                input.name().strip(),
                InventoryViews.inventoryRangeJson(json, input.range()),
                Timestamp.from(input.startsAt()),
                Timestamp.from(input.dueAt()),
                current.id(),
                now(),
                now());
        long id = Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        event(actor, current.id(), id, "INVENTORY_CREATED", Map.of(), 0, 0);
        LOG.info("Inventory task {} created by actor {}.", id, actor.id());
        return finish(current, "create", key, 0, input, taskRow(id));
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public InventoryViews.InventoryTaskRow copy(
            Actor actor, long sourceId, long version, String key, InventoryInput.Draft input) {
        var current = admin(actor);
        var prior = replay(current, "copy:" + sourceId, key, version, input);
        if (prior != null) {
            return prior;
        }
        var source = task(sourceId, true);
        if (number(source, "version") != version || "DELETED".equals(source.get("task_status"))) {
            throw new InventoryFailure(409, "INVENTORY_VERSION_OR_STATE_CONFLICT");
        }
        validateDraft(input);
        if (!json.readValue(source.get("range_json").toString(), InventoryInput.Range.class)
                .equals(input.range())) {
            throw new InventoryFailure(409, "INVENTORY_COPY_RANGE_CHANGED");
        }
        db.update(
                "INSERT INTO"
                    + " fams_inventory_task(name,range_json,starts_at,due_at,creator_account_id,copied_from_task_id,created_at,updated_at)"
                    + " VALUES (?,?,?,?,?,?,?,?)",
                input.name().strip(),
                InventoryViews.inventoryRangeJson(json, input.range()),
                Timestamp.from(input.startsAt()),
                Timestamp.from(input.dueAt()),
                current.id(),
                sourceId,
                now(),
                now());
        long id = Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        event(
                actor,
                current.id(),
                id,
                "INVENTORY_COPIED",
                Map.of("sourceTaskId", Long.toString(sourceId)),
                0,
                0);
        LOG.info("Inventory task copied from source task {} by actor {}.", sourceId, actor.id());
        return finish(current, "copy:" + sourceId, key, version, input, taskRow(id));
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public InventoryViews.InventoryTaskRow revise(
            Actor actor, long id, long version, String key, InventoryInput.Draft input) {
        var current = admin(actor);
        var prior = replay(current, "revise:" + id, key, version, input);
        if (prior != null) {
            return prior;
        }
        require(task(id, true), version, "DRAFT");
        validateDraft(input);
        db.update(
                "UPDATE fams_inventory_task SET"
                        + " name=?,range_json=?,starts_at=?,due_at=?,version=version+1,updated_at=?"
                        + " WHERE id=?",
                input.name().strip(),
                InventoryViews.inventoryRangeJson(json, input.range()),
                Timestamp.from(input.startsAt()),
                Timestamp.from(input.dueAt()),
                now(),
                id);
        event(actor, current.id(), id, "INVENTORY_REVISED", Map.of(), version, version + 1);
        LOG.info("Inventory task {} draft revised by actor {}.", id, actor.id());
        return finish(current, "revise:" + id, key, version, input, taskRow(id));
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public InventoryViews.InventoryTaskRow publish(
            Actor actor, long id, long version, String key, InventoryInput.Publish input) {
        var current = admin(actor);
        var prior = replay(current, "publish:" + id, key, version, input);
        if (prior != null) {
            return prior;
        }
        var task = task(id, true);
        require(task, version, "DRAFT");
        var range = json.readValue(task.get("range_json").toString(), InventoryInput.Range.class);
        var predicate = InventoryRange.predicate(range);
        var args = new ArrayList<Object>();
        args.add(id);
        args.addAll(predicate.args());
        // InnoDB RR INSERT SELECT holds source shared locks until commit;
        // Import rollback locks assets first.
        db.update(
                "INSERT INTO fams_inventory_item(task_id,asset_id,asset_version,snapshot_json)"
                        + " SELECT ?,asset_id,asset_version,snapshot_json FROM "
                        + InventoryRange.SOURCE
                        + predicate.sql()
                        + " ORDER BY asset_id",
                args.toArray());
        var captured =
                digest(
                        "SELECT asset_id,asset_version,SHA2(CAST(snapshot_json AS CHAR),256) AS"
                                + " row_hash FROM fams_inventory_item WHERE task_id=? ORDER BY"
                                + " asset_id",
                        List.of(id));
        if (input.expectedCount() < 1
                || input.expectedCount() != captured.count()
                || !input.previewDigest().equals(captured.digest())) {
            throw new InventoryFailure(409, "INVENTORY_PREVIEW_CHANGED");
        }
        db.update(
                "UPDATE fams_inventory_task SET"
                    + " task_status='READY',expected_count=?,parameter_version_id=?,timezone_id=?,publisher_account_id=?,published_at=?,updated_at=?,version=version+1"
                    + " WHERE id=?",
                input.expectedCount(),
                parameters.currentParameterVersionId(),
                parameters.currentZone().getId(),
                current.id(),
                now(),
                now(),
                id);
        event(
                actor,
                current.id(),
                id,
                "INVENTORY_PUBLISHED",
                Map.of("count", captured.count(), "digest", captured.digest()),
                version,
                version + 1);
        LOG.info("Inventory task {} published by actor {}.", id, actor.id());
        return finish(current, "publish:" + id, key, version, input, taskRow(id));
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public InventoryViews.InventoryTaskRow start(
            Actor actor, long id, long version, String key) {
        var current = admin(actor);
        var prior = replay(current, "start:" + id, key, version, Map.of());
        if (prior != null) {
            return prior;
        }
        require(task(id, true), version, "READY");
        begin(id);
        event(
                actor,
                current.id(),
                id,
                "INVENTORY_STARTED",
                Map.of("source", "MANUAL"),
                version,
                version + 1);
        LOG.info("Inventory task {} started by actor {}.", id, actor.id());
        return finish(current, "start:" + id, key, version, Map.of(), taskRow(id));
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public InventoryViews.InventoryTaskRow end(
            Actor actor,
            long id,
            long version,
            String key,
            String action,
            InventoryInput.Reason input) {
        var current = admin(actor);
        var prior = replay(current, action + ":" + id, key, version, input);
        if (prior != null) {
            return prior;
        }
        var task = task(id, true);
        require(
                task,
                version,
                "delete".equals(action)
                        ? new String[] {"DRAFT"}
                        : new String[] {"READY", "IN_PROGRESS", "WAITING_REVIEW"});
        long results =
                Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COALESCE(SUM(result_count),0) FROM fams_inventory_item"
                                        + " WHERE task_id=?",
                                Long.class,
                                id));
        results = Math.max(results, number(task, "captured_result_count"));
        if (!db.queryForList("SELECT item_id FROM fams_inventory_item_review WHERE task_id=? LIMIT 1", id).isEmpty()) {
            results = Math.max(results, 1);
        }
        if ("cancel".equals(action) && results != 0) {
            throw new InventoryFailure(409, "INVENTORY_HAS_RESULTS");
        }
        if ("terminate".equals(action) && results == 0) {
            throw new InventoryFailure(409, "INVENTORY_USE_CANCEL");
        }
        String status =
                switch (action) {
                    case "delete" -> "DELETED";
                    case "cancel" -> "CANCELLED";
                    case "terminate" -> "TERMINATED";
                    default -> throw new InventoryFailure(400, "INVENTORY_ACTION_INVALID");
                };
        db.update(
                "UPDATE fams_inventory_task SET"
                    + " task_status=?,end_reason=?,ended_at=?,updated_at=?,version=version+1 WHERE"
                    + " id=?",
                status,
                input.reason().strip(),
                now(),
                now(),
                id);
        event(
                actor,
                current.id(),
                id,
                "INVENTORY_" + status,
                Map.of("reason", input.reason()),
                version,
                version + 1);
        LOG.info("Inventory task {} ended by action {} actor {}.", id, action, actor.id());
        return finish(current, action + ":" + id, key, version, input, taskRow(id));
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public InventoryViews.InventoryTaskRow assign(
            Actor actor, long id, long version, String key, InventoryInput.Assign input) {
        var current = admin(actor);
        var prior = replay(current, "assign:" + id, key, version, input);
        if (prior != null) {
            return prior;
        }
        var task = task(id, true);
        require(task, version, "READY", "IN_PROGRESS", "WAITING_REVIEW");
        if ("WAITING_REVIEW".equals(task.get("task_status")) && !input.recheck()) {
            throw new InventoryFailure(409, "INVENTORY_RECHECK_REQUIRED");
        }
        if (!executors.eligible(input.executorAccountId())) {
            throw new InventoryFailure(409, "INVENTORY_EXECUTOR_UNAVAILABLE");
        }
        if (input.itemIds().stream().distinct().count() != input.itemIds().size()) {
            throw new InventoryFailure(400, "INVENTORY_DUPLICATE_ITEM");
        }
        long assignment = number(task, "assignment_version") + 1;
        for (long itemId : input.itemIds().stream().sorted().toList()) {
            var rows =
                    db.queryForList(
                            "SELECT * FROM fams_inventory_item WHERE id=? AND task_id=? FOR UPDATE",
                            itemId,
                            id);
            if (rows.isEmpty()) {
                throw new InventoryFailure(404, "INVENTORY_ITEM_NOT_FOUND");
            }
            var item = rows.getFirst();
            boolean reviewed = !db.queryForList(
                    "SELECT item_id FROM fams_inventory_item_review WHERE item_id=? AND recheck_generation=?",
                    itemId, item.get("recheck_generation")).isEmpty();
            if ((number(item, "result_count") > 0 || reviewed) && !input.recheck()) {
                throw new InventoryFailure(409, "INVENTORY_RECHECK_REQUIRED");
            }
            if (Objects.equals(item.get("executor_account_id"), input.executorAccountId())
                    && !input.recheck()) {
                throw new InventoryFailure(409, "INVENTORY_EXECUTOR_UNCHANGED");
            }
            long generation = number(item, "recheck_generation") + (input.recheck() ? 1 : 0);
            db.update(
                    "INSERT INTO"
                        + " fams_inventory_assignment(task_id,item_id,old_executor_account_id,new_executor_account_id,assignment_version,recheck_generation,reason,actor_account_id,assigned_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                    id,
                    itemId,
                    item.get("executor_account_id"),
                    input.executorAccountId(),
                    assignment,
                    generation,
                    input.reason().strip(),
                    current.id(),
                    now());
            db.update(
                    "UPDATE fams_inventory_item SET"
                        + " executor_account_id=?,assignment_version=?,recheck_generation=? WHERE"
                        + " id=?",
                    input.executorAccountId(),
                    assignment,
                    generation,
                    itemId);
        }
        db.update(
                "INSERT INTO fams_inventory_participant(task_id,account_id,first_assigned_at)"
                    + " VALUES (?,?,?) ON DUPLICATE KEY UPDATE first_assigned_at=first_assigned_at",
                id,
                input.executorAccountId(),
                now());
        var revoked =
                db.queryForList(
                        "SELECT revoked_at FROM fams_inventory_participant WHERE task_id=? AND"
                                + " account_id=?",
                        id,
                        input.executorAccountId());
        if (revoked.getFirst().get("revoked_at") != null) {
            throw new InventoryFailure(409, "INVENTORY_PARTICIPATION_REVOKED");
        }
        db.update(
                "UPDATE fams_inventory_task SET assignment_version=?,version=version+1,updated_at=?,"
                        + " task_status=CASE WHEN task_status='WAITING_REVIEW' THEN 'IN_PROGRESS' ELSE task_status END"
                        + " WHERE id=?",
                assignment,
                now(),
                id);
        event(
                actor,
                current.id(),
                id,
                "INVENTORY_ASSIGNED",
                Map.of(
                        "items",
                        input.itemIds().stream().map(Object::toString).toList(),
                        "executorAccountId",
                        Long.toString(input.executorAccountId()),
                        "assignmentVersion",
                        assignment,
                        "reason",
                        input.reason(),
                        "recheck",
                        input.recheck()),
                version,
                version + 1);
        LOG.info("Inventory task {} executors assigned by actor {}.", id, actor.id());
        return finish(current, "assign:" + id, key, version, input, taskRow(id));
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public InventoryViews.InventoryTaskPage list(Actor actor, int page, int size) {
        var current = actors.revalidateLocked(actor);
        String where =
                current.has(Role.ASSET_ADMIN)
                        ? " WHERE task_status<>'DELETED'"
                        : " WHERE task_status<>'DELETED' AND EXISTS(SELECT 1 FROM"
                              + " fams_inventory_item i JOIN fams_inventory_participant p ON"
                              + " p.task_id=i.task_id AND p.account_id=i.executor_account_id WHERE"
                              + " i.task_id=t.id AND i.executor_account_id=? AND p.revoked_at IS"
                              + " NULL)";
        var args = new ArrayList<Object>();
        if (!current.has(Role.ASSET_ADMIN)) {
            args.add(current.id());
        }
        Long total =
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_task t" + where,
                        Long.class,
                        args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        var rows =
                db.queryForList(
                        "SELECT t.* FROM fams_inventory_task t"
                                + where
                                + " ORDER BY id DESC LIMIT ? OFFSET ?",
                        args.toArray());
        LOG.debug("Inventory task page requested by actor {} page {} size {}.", actor.id(), page, size);
        return new InventoryViews.InventoryTaskPage(
                rows.stream().map(row -> taskView(row, current.has(Role.ASSET_ADMIN))).toList(),
                Objects.requireNonNull(total),
                page,
                size,
                current.has(Role.ASSET_ADMIN));
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public InventoryViews.InventoryTaskDetail detail(Actor actor, long id, int page, int size) {
        var current = actors.revalidateLocked(actor);
        var task = task(id, false);
        if ("DELETED".equals(task.get("task_status"))) {
            throw new InventoryFailure(404, "INVENTORY_TASK_NOT_FOUND");
        }
        boolean admin = current.has(Role.ASSET_ADMIN);
        String where =
                " WHERE task_id=?"
                        + (admin
                                ? ""
                                : " AND executor_account_id=? AND EXISTS (SELECT 1 FROM"
                                      + " fams_inventory_participant p WHERE"
                                      + " p.task_id=fams_inventory_item.task_id AND p.account_id=?"
                                      + " AND p.revoked_at IS NULL)");
        var args = new ArrayList<Object>();
        args.add(id);
        if (!admin) {
            args.add(current.id());
            args.add(current.id());
        }
        long total =
                Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM fams_inventory_item" + where,
                                Long.class,
                                args.toArray()));
        if (!admin && total == 0) {
            throw new InventoryFailure(404, "INVENTORY_TASK_NOT_FOUND");
        }
        args.add(size);
        args.add((page - 1) * size);
        var items =
                db.queryForList(
                        "SELECT * FROM fams_inventory_item"
                                + where
                                + " ORDER BY id LIMIT ? OFFSET ?",
                        args.toArray());
        LOG.debug("Inventory task detail requested for task {} page {} size {}.", id, page, size);
        return new InventoryViews.InventoryTaskDetail(
                taskView(task, admin),
                items.stream().map(row -> InventoryViews.inventoryItemRow(json, row)).toList(),
                total,
                page,
                size,
                admin);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public List<InventoryExecutorPort.ExecutorChoice> executorChoices(Actor actor, int page, int size) {
        admin(actor);
        LOG.debug("Inventory executor choices requested page {} size {}.", page, size);
        return executors.choices(page, size);
    }

    /**
     * One recoverable scheduled transition per short transaction, independent of process-local
     * timers.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public boolean startDue() {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_task WHERE task_status='READY' AND"
                            + " starts_at<=? ORDER BY starts_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",
                        now());
        if (rows.isEmpty()) {
            return false;
        }
        var task = rows.getFirst();
        long id = number(task, "id"), version = number(task, "version");
        begin(id);
        long publisher = number(task, "publisher_account_id");
        db.update(
                "INSERT INTO"
                    + " fams_inventory_task_event(task_id,actor_account_id,action,detail_json,occurred_at)"
                    + " VALUES (?,?,'INVENTORY_STARTED',?,?)",
                id,
                publisher,
                json.writeValueAsString(
                        Map.of("source", "SCHEDULED", "publisherAttributionOnly", true)),
                now());
        db.update(
                "INSERT INTO"
                    + " fams_audit_event(request_id,actor_account_id,actor_role,action,resource_type,resource_id,before_version,after_version,occurred_at,detail_json)"
                    + " VALUES (?,?,'SYSTEM_SCHEDULE','INVENTORY_STARTED',"
                    + "'INVENTORY_TASK',?,?,?,?,?)",
                java.util.UUID.randomUUID().toString(),
                publisher,
                id,
                version,
                version + 1,
                now(),
                json.writeValueAsString(
                        Map.of("source", "SCHEDULED", "publisherAttributionOnly", true)));
        LOG.info("Scheduled start switched inventory task {} to RUNNING.", id);
        return true;
    }

    private void begin(long id) {
        db.update(
                "UPDATE fams_inventory_task SET"
                    + " task_status='IN_PROGRESS',started_at=?,updated_at=?,version=version+1 WHERE"
                    + " id=?",
                now(),
                now(),
                id);
    }

    private Current admin(Actor actor) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(Role.ASSET_ADMIN)) {
            throw new InventoryFailure(403, "INVENTORY_FORBIDDEN");
        }
        return current;
    }

    private void validateDraft(InventoryInput.Draft input) {
        InventoryRange.predicate(input.range());
        if (input.name() == null
                || input.name().isBlank()
                || input.name().strip().length() > 128
                || input.startsAt() == null
                || input.dueAt() == null
                || !input.dueAt().isAfter(input.startsAt())
                || input.startsAt().getNano() % 1_000_000 != 0
                || input.dueAt().getNano() % 1_000_000 != 0) {
            throw new InventoryFailure(400, "INVENTORY_DRAFT_INVALID");
        }
    }

    private Map<String, Object> task(long id, boolean lock) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_task WHERE id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        id);
        if (rows.isEmpty()) {
            throw new InventoryFailure(404, "INVENTORY_TASK_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private void require(Map<String, Object> task, long version, String... statuses) {
        if (number(task, "version") != version
                || !List.of(statuses).contains(task.get("task_status"))) {
            throw new InventoryFailure(409, "INVENTORY_VERSION_OR_STATE_CONFLICT");
        }
    }

    /**
     * The body every inventory write exit returns and persists as its idempotency reply: the clean
     * projection of the just-written task row.
     */
    private InventoryViews.InventoryTaskRow taskRow(long id) {
        return InventoryViews.inventoryTaskRow(json, task(id, false));
    }

    /**
     * The frozen taskView projection: the clean row plus the derived overdue flag. The legacy code
     * removed the six admin-only keys for an executor caller, so the role decides which of the two
     * {@link InventoryViews.InventoryTaskView} records is built.
     */
    private InventoryViews.InventoryTaskView taskView(Map<String, Object> row, boolean admin) {
        var projected = InventoryViews.inventoryTaskRow(json, row);
        Instant due = instant(row.get("due_at"));
        boolean overdue =
                "IN_PROGRESS".equals(row.get("task_status")) && !due.isAfter(clock.instant());
        return admin
                ? InventoryViews.inventoryTaskAdminView(projected, overdue)
                : InventoryViews.inventoryTaskExecutorView(projected, overdue);
    }

    private Instant instant(Object value) {
        return value instanceof Timestamp stamp
                ? stamp.toInstant()
                : ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC);
    }

    /**
     * Row-set digest of a range: the /preview exit shape and the publication guard source at the
     * same time. The INVENTORY_PUBLISHED audit payload keeps its own Map.of so the stored
     * detail_json text stays exactly as before.
     */
    private InventoryViews.InventoryPreviewDigest digest(String sql, List<Object> args) {
        MessageDigest digest = sha();
        long[] count = {0};
        db.query(
                sql,
                result -> {
                    digest.update(
                            (result.getLong("asset_id")
                                            + ":"
                                            + result.getLong("asset_version")
                                            + ":"
                                            + result.getString("row_hash")
                                            + "\n")
                                    .getBytes(StandardCharsets.UTF_8));
                    count[0]++;
                },
                args.toArray());
        return new InventoryViews.InventoryPreviewDigest(
                count[0], HexFormat.of().formatHex(digest.digest()));
    }

    private MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private String fingerprint(long version, Object input) {
        var canonical = new LinkedHashMap<String, Object>();
        canonical.put("version", version);
        canonical.put("input", input);
        return HexFormat.of()
                .formatHex(
                        sha().digest(
                                        json.writeValueAsString(canonical)
                                                .getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Replay of a stored inventory write reply. Every operation in this service stores the same
     * shape (the clean task row), so one typed exit replaces the generic Class-parameter replay of
     * the earlier domains.
     */
    private InventoryViews.InventoryTaskRow replay(
            Current current, String operation, String key, long version, Object input) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_idempotency_record WHERE actor_account_id=? AND"
                                + " operation_id=? AND idempotency_key=? FOR UPDATE",
                        current.id(),
                        "inventory:" + operation,
                        key);
        if (rows.isEmpty()) {
            return null;
        }
        var row = rows.getFirst();
        if (!instant(row.get("expires_at")).isAfter(clock.instant())) {
            db.update("DELETE FROM fams_idempotency_record WHERE id=?", row.get("id"));
            return null;
        }
        if (!fingerprint(version, input).equals(row.get("payload_sha256"))) {
            throw new InventoryFailure(409, "INVENTORY_IDEMPOTENCY_CONFLICT");
        }
        // The stored body is the clean projection of an earlier run; the same projection factory
        // re-reads it, so a legacy receipt normalizes range_json exactly as before.
        return InventoryViews.inventoryTaskRow(
                json,
                json.readValue(row.get("response_body_json").toString(), WIRE_MAP));
    }

    private InventoryViews.InventoryTaskRow finish(
            Current current,
            String operation,
            String key,
            long version,
            Object input,
            InventoryViews.InventoryTaskRow result) {
        db.update(
                "INSERT INTO"
                    + " fams_idempotency_record(actor_account_id,operation_id,idempotency_key,payload_sha256,result_status,http_status,"
                    + "response_body_json,resource_type,resource_id,resource_version,created_at,completed_at,expires_at)"
                    + " VALUES (?,?,?,?,'SUCCEEDED',200,?,'INVENTORY_TASK',?,?,?,?,?)",
                current.id(),
                "inventory:" + operation,
                key,
                fingerprint(version, input),
                json.writeValueAsString(result),
                Long.parseLong(result.id()),
                result.version(),
                now(),
                now(),
                Timestamp.from(clock.instant().plusSeconds(86400)));
        return result;
    }

    private void event(
            Actor actor,
            long actorId,
            long id,
            String action,
            Object detail,
            long before,
            long after) {
        String serialized = json.writeValueAsString(detail);
        db.update(
                "INSERT INTO"
                    + " fams_inventory_task_event(task_id,actor_account_id,action,detail_json,occurred_at)"
                    + " VALUES (?,?,?,?,?)",
                id,
                actorId,
                action,
                serialized,
                now());
        db.update(
                "INSERT INTO"
                    + " fams_audit_event(request_id,actor_account_id,actor_role,action,resource_type,resource_id,before_version,after_version,occurred_at,detail_json)"
                    + " VALUES (?,?,'ASSET_ADMIN',?,'INVENTORY_TASK',?,?,?,?,?)",
                actor.requestId().toString(),
                actorId,
                action,
                id,
                before,
                after,
                now(),
                serialized);
    }

    private long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }
}
