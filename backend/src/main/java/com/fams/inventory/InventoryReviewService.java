package com.fams.inventory;

import com.fams.asset.AssetInventoryReviewPort;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Review conclusions never replace original observations or grant custody-changing authority. */
@Service
@Profile("fams-product")
public class InventoryReviewService {
    private static final Logger LOG = LoggerFactory.getLogger(InventoryReviewService.class);
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RequisitionActorContext actors;
    private final AssetInventoryReviewPort assets;
    private final Clock clock;

    /**
     * Injects the collaborators the admin-side normal review flow needs.
     *
     * @param db reads and writes the inventory task, item, observation, review and receipt tables
     * @param json projects the recursive review wire and serializes stored receipt texts
     * @param actors revalidates the caller session and proves the ASSET_ADMIN role
     * @param assets supplies the asset snapshot, period movements and version match for each item
     * @param clock stamps every persisted review instant
     */
    public InventoryReviewService(JdbcTemplate db, ObjectMapper json, RequisitionActorContext actors,
            AssetInventoryReviewPort assets, Clock clock) {
        this.db = db;
        this.json = json;
        this.actors = actors;
        this.assets = assets;
        this.clock = clock;
    }

    /**
     * Projects one page of the review worklist: for each item its book snapshot, the live asset
     * snapshot with its period movements, the counted candidate observation, the review-state union and
     * the reason the item cannot be accepted normally. An item that already has a review row reports
     * ALREADY_REVIEWED instead of the underlying problem, so a reviewer never re-accepts it.
     *
     * @param actor caller who must hold the ASSET_ADMIN role
     * @param taskId inventory task whose items are listed
     * @param page one-based page number
     * @param size maximum number of items per page
     * @return task row, the item page ordered by asset id, the item total, the paging echo and the
     *     accepted review count
     * @throws InventoryFailure 403 INVENTORY_REVIEW_ADMIN_REQUIRED without the ASSET_ADMIN role, or
     *     404 INVENTORY_REVIEW_TASK_NOT_FOUND when the task is missing, DRAFT or DELETED
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public FindingViews.ReviewTaskDetail detail(Actor actor, long taskId, int page, int size) {
        admin(actor);
        var task = task(taskId);
        var rows = db.queryForList("SELECT * FROM fams_inventory_item WHERE task_id=?"
                + " ORDER BY asset_id LIMIT ? OFFSET ? FOR SHARE", taskId, size, (page - 1) * size);
        var contexts = assets.contexts(rows.stream().map(row -> number(row, "asset_id")).toList(),
                instant(task.get("published_at")));
        var items = new ArrayList<FindingViews.ReviewTaskItem>();
        for (var item : rows) {
            var candidate = candidate(item);
            var review = review(item);
            var context = contexts.get(number(item, "asset_id"));
            String problem = normalProblem(item, candidate, context);
            if (review != null) {
                problem = "ALREADY_REVIEWED";
            }
            items.add(new FindingViews.ReviewTaskItem(
                    FindingViews.item(json, item),
                    context == null ? null
                            : FindingViews.wiredTree(json,
                                    json.readValue(context.snapshotJson(), Object.class)),
                    context == null ? List.of() : context.movements(),
                    candidate == null ? null : FindingViews.reviewObservation(json, candidate),
                    FindingViews.reviewState(json, review),
                    problem == null
                            && List.of("IN_PROGRESS", "WAITING_REVIEW").contains(task.get("task_status")),
                    problem));
        }
        LOG.debug("Review task detail requested for task {} page {} size {}.", taskId, page, size);
        return new FindingViews.ReviewTaskDetail(FindingViews.task(json, task), items,
                countItems(taskId), page, size, reviewedCount(taskId));
    }

    /**
     * Lists one page of the observations a reviewer may bind a decision to. Without an item id only the
     * task's unassigned observations are returned, which is the finding pool; with an item id only that
     * item's observations are returned. The rows go through the recursive review wire, so identifier
     * keys inside facts_json leave as text.
     *
     * @param actor caller who must hold the ASSET_ADMIN role
     * @param taskId inventory task the observations belong to
     * @param item inventory item to restrict the list to, or null for the unassigned observations
     * @param page one-based page number
     * @param size maximum number of observations per page
     * @return observation rows in ascending observation id order, the total count and the paging echo
     * @throws InventoryFailure 403 INVENTORY_REVIEW_ADMIN_REQUIRED without the ASSET_ADMIN role, 404
     *     INVENTORY_REVIEW_TASK_NOT_FOUND when the task is missing, DRAFT or DELETED, or 404
     *     INVENTORY_ITEM_NOT_FOUND when the given item does not belong to the task
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public FindingViews.ObservationPage observations(Actor actor, long taskId, Long itemId, int page, int size) {
        admin(actor);
        task(taskId);
        if (itemId != null) {
            var owned = db.queryForList("SELECT id FROM fams_inventory_item WHERE task_id=? AND id=? FOR SHARE",
                    taskId, itemId);
            if (owned.isEmpty()) {
                throw new InventoryFailure(404, "INVENTORY_ITEM_NOT_FOUND");
            }
        }
        String where = itemId == null ? "task_id=? AND item_id IS NULL" : "task_id=? AND item_id=?";
        var args = new ArrayList<Object>();
        args.add(taskId);
        if (itemId != null) {
            args.add(itemId);
        }
        Long total = db.queryForObject("SELECT COUNT(*) FROM fams_inventory_observation WHERE " + where + " FOR SHARE",
                Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        var rows = db.queryForList("SELECT * FROM fams_inventory_observation WHERE " + where
                + " ORDER BY id LIMIT ? OFFSET ? FOR SHARE", args.toArray());
        LOG.debug("Review observations requested for task {} item {} page {} size {}.", taskId, itemId, page, size);
        return new FindingViews.ObservationPage(
                rows.stream().map(row -> FindingViews.reviewObservation(json, row)).toList(),
                Objects.requireNonNull(total), page, size);
    }

    /**
     * Accepts a batch of undisputed items as normal in one transaction. The request id makes the call
     * idempotent: a stored ACCEPT_NORMAL receipt with the same fingerprint replays, a different one is
     * rejected as an idempotency conflict. Items are locked in ascending asset id order so overlapping
     * reviews in different tasks serialize deterministically. Each item is judged on its own and a
     * refusal never aborts the batch: it becomes a CONFLICT outcome line carrying its code, while the
     * accepted ones still write fams_inventory_item_review plus a CLOSED NORMAL review subject.
     *
     * <p>State impact: the task version is bumped once when at least one item was accepted, the batch
     * is audited as INVENTORY_NORMAL_REVIEWED and stored as the ACCEPT_NORMAL receipt.
     *
     * @param actor caller who must hold the ASSET_ADMIN role
     * @param taskId inventory task whose items are accepted
     * @param expectedVersion task version the caller read, compared against the locked task row
     * @param key client request id used for the idempotency receipt
     * @param input shared review reason plus the item id, recheck generation, review version and asset
     *     version of each item to accept
     * @return receipt with the resulting task version, accepted and conflict counters, one outcome line
     *     per requested item in asset order and the new accepted review count
     * @throws InventoryReviewConflict INVENTORY_REVIEW_IDEMPOTENCY_CONFLICT when the request id was
     *     already used with a different payload, or INVENTORY_REVIEW_TASK_CHANGED when the task version
     *     differs or the task is not IN_PROGRESS or WAITING_REVIEW; both are audited and do not roll the
     *     transaction back
     * @throws InventoryFailure 403 INVENTORY_REVIEW_ADMIN_REQUIRED without the ASSET_ADMIN role, 404
     *     INVENTORY_REVIEW_TASK_NOT_FOUND when the task is missing, DRAFT or DELETED, or 400
     *     INVENTORY_REVIEW_ITEM_DUPLICATE when the payload lists one item twice
     */
    @Transactional(
        isolation = Isolation.REPEATABLE_READ,
        rollbackFor = Exception.class,
        noRollbackFor = InventoryReviewConflict.class)
    public FindingViews.NormalBatchReceipt acceptNormal(Actor actor, long taskId, long expectedVersion, UUID key,
            InventoryReviewInput.NormalBatch input) {
        var current = admin(actor);
        var task = task(taskId);
        String fingerprint = fingerprint(expectedVersion, input);
        var prior = db.queryForList("SELECT payload_sha256,CAST(response_json AS CHAR) AS response FROM"
                + " fams_inventory_review_receipt WHERE task_id=? AND actor_account_id=? AND operation='ACCEPT_NORMAL'"
                + " AND request_id=? FOR UPDATE", taskId, current.id(), key.toString());
        if (!prior.isEmpty()) {
            if (!fingerprint.equals(prior.getFirst().get("payload_sha256"))) {
                reject(actor, current, task, "INVENTORY_REVIEW_IDEMPOTENCY_CONFLICT",
                        Map.of("requestId", key.toString()));
            }
            return replay(prior.getFirst(), FindingViews.NormalBatchReceipt.class);
        }
        if (number(task, "version") != expectedVersion
                || !List.of("IN_PROGRESS", "WAITING_REVIEW").contains(task.get("task_status"))) {
            reject(actor, current, task, "INVENTORY_REVIEW_TASK_CHANGED", conflictFields(actor, task, input));
        }
        if (input.items().stream().map(InventoryReviewInput.Item::itemId).distinct().count() != input.items().size()) {
            throw new InventoryFailure(400, "INVENTORY_REVIEW_ITEM_DUPLICATE");
        }
        var ordered = new ArrayList<>(input.items());
        // Stable asset order also serializes overlapping asset reviews in different tasks.
        var args = new ArrayList<Object>();
        args.add(taskId);
        args.addAll(input.items().stream().map(InventoryReviewInput.Item::itemId).toList());
        String marks = String.join(",", java.util.Collections.nCopies(input.items().size(), "?"));
        var orderedRows = db.queryForList("SELECT id,asset_id FROM fams_inventory_item WHERE task_id=?"
                + " AND id IN (" + marks + ") ORDER BY asset_id FOR SHARE", args.toArray());
        var order = new java.util.HashMap<Long, Integer>();
        for (int index = 0; index < orderedRows.size(); index++) {
            order.put(number(orderedRows.get(index), "id"), index);
        }
        ordered.sort(java.util.Comparator.comparingInt(item -> order.getOrDefault(item.itemId(), Integer.MAX_VALUE)));
        var outcomes = new ArrayList<FindingViews.NormalBatchItem>();
        int accepted = 0;
        for (var entry : ordered) {
            var rows = db.queryForList("SELECT * FROM fams_inventory_item WHERE task_id=? AND id=? FOR UPDATE",
                    taskId, entry.itemId());
            if (rows.isEmpty()) {
                outcomes.add(conflict(entry.itemId(), "INVENTORY_ITEM_NOT_FOUND", null));
                continue;
            }
            var item = rows.getFirst();
            var existing = review(item);
            long reviewVersion = existing == null ? 0 : number(existing, "version");
            if (entry.recheckGeneration() != number(item, "recheck_generation")
                    || entry.reviewVersion() != reviewVersion || existing != null) {
                outcomes.add(conflict(entry.itemId(), "INVENTORY_REVIEW_VERSION_CONFLICT", existing));
                continue;
            }
            long assetId = number(item, "asset_id");
            if (!assets.matchesVersion(assetId, entry.assetVersion())) {
                outcomes.add(conflict(entry.itemId(), "INVENTORY_REVIEW_ASSET_CHANGED", null));
                continue;
            }
            var context = assets.contexts(List.of(assetId), instant(task.get("published_at"))).get(assetId);
            var candidate = candidate(item);
            String problem = normalProblem(item, candidate, context);
            if (problem != null) {
                outcomes.add(conflict(entry.itemId(), problem, null));
                continue;
            }
            long observation = number(Objects.requireNonNull(candidate), "id");
            String evidence = "inventory-observation:" + observation;
            db.update("INSERT INTO fams_inventory_item_review(item_id,recheck_generation,task_id,observation_id,"
                    + "review_status,verified_observed_at,reviewed_asset_version,reason,evidence,reviewer_account_id,"
                    + "reviewer_username,reviewed_at,version) VALUES (?,?,?,?,'ACCEPTED',?,?,?,?,?,?,?,1)",
                    entry.itemId(), entry.recheckGeneration(), taskId, observation,
                    candidate.get("calibrated_observed_at"), entry.assetVersion(), input.reason(), evidence,
                    current.id(), current.username(), now());
            db.update("INSERT INTO fams_inventory_review_subject(observation_id,dimension,task_id,item_id,"
                    + "recheck_generation,subject_status,method,reason,evidence,reviewer_account_id,reviewer_username,"
                    + "reviewed_at,version) VALUES (?,'NORMAL',?,?,?,'CLOSED','ACCEPT_NORMAL',?,?,?,?,?,1)",
                    observation, taskId, entry.itemId(), entry.recheckGeneration(), input.reason(), evidence,
                    current.id(), current.username(), now());
            accepted++;
            outcomes.add(new FindingViews.NormalBatchItem(Long.toString(entry.itemId()), "ACCEPTED",
                    FindingViews.reviewNode(json, Objects.requireNonNull(review(item))), null, null));
        }
        long afterVersion = expectedVersion + (accepted > 0 ? 1 : 0);
        if (accepted > 0) {
            db.update("UPDATE fams_inventory_task SET version=version+1,updated_at=? WHERE id=?", now(), taskId);
        }
        var response = new FindingViews.NormalBatchReceipt(Long.toString(taskId), afterVersion, accepted,
                outcomes.size() - accepted, outcomes, reviewedCount(taskId));
        audit(actor, current, taskId, "INVENTORY_NORMAL_REVIEWED", expectedVersion, afterVersion, response);
        db.update("INSERT INTO fams_inventory_review_receipt(task_id,actor_account_id,operation,request_id,"
                + "payload_sha256,response_json,created_at) VALUES (?,?,'ACCEPT_NORMAL',?,?,?,?)",
                taskId, current.id(), key.toString(), fingerprint, json.writeValueAsString(response), now());
        LOG.info("Normal review batch accepted for task {} by actor {}.", taskId, actor.id());
        return response;
    }

    private String normalProblem(Map<String, Object> item, Map<String, Object> candidate,
            AssetInventoryReviewPort.Context context) {
        if (candidate == null || !"ACCEPTED".equals(candidate.get("outcome"))
                || candidate.get("calibrated_observed_at") == null) {
            return "INVENTORY_REVIEW_OBSERVATION_REQUIRED";
        }
        if (context == null) {
            return "INVENTORY_REVIEW_ASSET_UNAVAILABLE";
        }
        var book = json.readTree(item.get("snapshot_json").toString());
        var latest = json.readTree(context.snapshotJson());
        if (!context.movements().isEmpty()) {
            return "INVENTORY_REVIEW_PERIOD_MOVEMENT";
        }
        for (String field : List.of("locationId", "custodyType", "responsibleEmployeeId",
                "departmentResponsibleEmployeeId", "usingOrganizationId", "owningOrganizationId", "lifecycle",
                "exception", "warehouseId")) {
            if (!Objects.equals(book.path(field), latest.path(field))) {
                return "INVENTORY_REVIEW_PERIOD_MOVEMENT";
            }
        }
        Long conflict = db.queryForObject("SELECT COUNT(*) FROM fams_inventory_observation WHERE task_id=?"
                + " AND item_id=? AND recheck_generation=?"
                + " AND outcome NOT IN ('ACCEPTED','DUPLICATE','EXPIRED','LATE_REVIEW') FOR SHARE",
                Long.class, item.get("task_id"), item.get("id"), item.get("recheck_generation"));
        return conflict != null && conflict > 0 ? "INVENTORY_REVIEW_EVIDENCE_CONFLICT" : null;
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
        if (rows.isEmpty() || List.of("DRAFT", "DELETED").contains(rows.getFirst().get("task_status"))) {
            throw new InventoryFailure(404, "INVENTORY_REVIEW_TASK_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private Map<String, Object> candidate(Map<String, Object> item) {
        var rows = db.queryForList("SELECT o.* FROM fams_inventory_round_result r JOIN fams_inventory_observation o"
                + " ON o.id=r.observation_id WHERE r.item_id=? AND r.recheck_generation=? FOR SHARE",
                item.get("id"), item.get("recheck_generation"));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> review(Map<String, Object> item) {
        var rows = db.queryForList(
                "SELECT * FROM fams_inventory_item_review WHERE item_id=? AND recheck_generation=? FOR UPDATE",
                item.get("id"), item.get("recheck_generation"));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private FindingViews.NormalBatchItem conflict(long id, String code, Map<String, Object> current) {
        return new FindingViews.NormalBatchItem(Long.toString(id), "CONFLICT", null, code,
                FindingViews.reviewNode(json, current));
    }

    private long countItems(long task) {
        return Objects.requireNonNull(
                db.queryForObject("SELECT COUNT(*) FROM fams_inventory_item WHERE task_id=? FOR SHARE",
                        Long.class, task));
    }

    private long reviewedCount(long task) {
        return Objects.requireNonNull(db.queryForObject("SELECT COUNT(*) FROM fams_inventory_item_review r JOIN"
                + " fams_inventory_item i ON i.id=r.item_id AND i.recheck_generation=r.recheck_generation"
                + " WHERE r.task_id=? AND r.review_status='ACCEPTED' FOR SHARE", Long.class, task));
    }

    private Map<String, String> conflictFields(
            Actor actor, Map<String, Object> task, InventoryReviewInput.NormalBatch input) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("taskVersion", Long.toString(number(task, "version")));
        fields.put("taskStatus", task.get("task_status").toString());
        fields.put("readOnlyUrl", InventoryReviewLinks.task(actor, number(task, "id")));
        for (var item : input.items()) {
            var rows = db.queryForList("SELECT reviewer_account_id,reviewer_username,reviewed_at,review_status,version"
                    + " FROM fams_inventory_item_review WHERE task_id=? AND item_id=? AND recheck_generation=? "
                    + "FOR SHARE",
                    task.get("id"), item.itemId(), item.recheckGeneration());
            if (!rows.isEmpty()) {
                var winner = rows.getFirst();
                fields.put("processedBy", winner.get("reviewer_username").toString());
                fields.put("processedByAccountId", winner.get("reviewer_account_id").toString());
                fields.put("processedAt", instant(winner.get("reviewed_at")).toString());
                fields.put("result", winner.get("review_status").toString());
                fields.put("reviewVersion", winner.get("version").toString());
                fields.put("method", "ACCEPT_NORMAL");
                break;
            }
        }
        return fields;
    }

    private void reject(
            Actor actor, Current current, Map<String, Object> task, String code, Map<String, String> fields) {
        audit(actor, current, number(task, "id"), "INVENTORY_REVIEW_CONFLICT_REJECTED",
                number(task, "version"), number(task, "version"), Map.of("code", code, "fields", fields));
        throw new InventoryReviewConflict(code, fields);
    }

    private void audit(Actor actor, Current current, long task, String action, long before, long after, Object detail) {
        String serialized = json.writeValueAsString(detail);
        db.update("INSERT INTO fams_inventory_task_event(task_id,actor_account_id,action,detail_json,occurred_at) "
                + "VALUES (?,?,?,?,?)",
                task, current.id(), action, serialized, now());
        db.update("INSERT INTO fams_audit_event(request_id,actor_account_id,actor_role,action,"
                + "resource_type,resource_id,before_version,after_version,occurred_at,detail_json) "
                + "VALUES (?,?,'ASSET_ADMIN',?,'INVENTORY_TASK',?,?,?,?,?)",
                actor.requestId().toString(), current.id(), action, task, before, after, now(), serialized);
    }

    private String fingerprint(long version, Object input) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((version + "\n" + json.writeValueAsString(input)).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * Replay of one stored fams_inventory_review_receipt.response_json through the typed exit shape
     * (JAVA-17c blueprint). The lookup, FOR UPDATE lock and payload comparison stay in the calling
     * flow untouched.
     */
    private <T> T replay(Map<String, Object> prior, Class<T> type) {
        return json.readValue(prior.get("response").toString(), type);
    }

    private long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    private Instant instant(Object value) {
        return value instanceof Timestamp timestamp ? timestamp.toInstant()
                : ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }
}
