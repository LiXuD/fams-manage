package com.fams.inventory;

import com.fams.asset.AssetInventoryReviewPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;

import com.fams.loss.InventoryLossPort;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import com.fams.asset.AssetHandoverPort;
import com.fams.loss.LossFailure;
import com.fams.loss.LossInput;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Missing evidence remains explicit and never becomes a successful physical observation. */
@Service
@Profile("fams-product")
public class InventoryReadinessService {
    private static final Logger LOG = LoggerFactory.getLogger(InventoryReadinessService.class);
    /**
     * Request body for escalating an ASSET_NOT_FOUND unobserved reason into a formal loss report.
     * The record is bound directly by {@link #reportMissing} rather than living in
     * {@link InventoryReadinessInput}, because it is the only readiness payload whose validation
     * mirrors the loss domain's own submission rules instead of the review rules.
     *
     * @param assetVersion asset card version the client read; the loss port rejects a stale version,
     *     and the failure is re-thrown as an InventoryFailure with the loss status and code
     * @param lastSeenAt moment the asset was last physically seen, never null
     * @param lastLocation free-text description of the last known place, at most 500 characters
     * @param circumstances description of how the asset went missing, at least 10 characters after
     *     stripping, otherwise INVENTORY_MISSING_LOSS_BASIS_REQUIRED
     */
    public record MissingLossInput(
            @NotNull @PositiveOrZero Long assetVersion,
            @NotNull Instant lastSeenAt,
            @NotBlank @Size(max = 500) String lastLocation,
            @NotBlank @Size(min = 10, max = 2000) String circumstances) {}

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RequisitionActorContext actors;
    private final AssetInventoryReviewPort assets;
    private final Clock clock;
    private final InventoryLossPort losses;

    /**
     * Wires the readiness service. The loss port is injected so that escalating a missing asset
     * stays one transaction, and the clock stamps every review, reason, receipt and audit row with
     * the same millisecond-truncated instant.
     *
     * @param db JDBC template for the readiness reads and the review/reason/receipt/audit writes
     * @param json mapper used to build idempotency digests and to re-read stored snapshot payloads
     * @param actors actor context that revalidates the caller and enforces the ASSET_ADMIN role
     * @param assets port that supplies the live asset context and version checks
     * @param clock time source of every written timestamp
     * @param losses port that reports progress of, and submits, the formal loss report
     */
    public InventoryReadinessService(
            JdbcTemplate db,
            ObjectMapper json,
            RequisitionActorContext actors,
            AssetInventoryReviewPort assets,
            Clock clock, InventoryLossPort losses) {
        this.db = db;
        this.json = json;
        this.actors = actors;
        this.assets = assets;
        this.clock = clock;
        this.losses = losses;
    }

    /**
     * Reads the readiness page of one task: every item of the page together with its review state,
     * its ASSET_NOT_FOUND reason rows and the loss-report progress of each reason. An item is
     * reported as still collectible only when the task is IN_PROGRESS, it has no review row and it
     * has no non-EXPIRED observation, and a reason is reported as retryable only when the task is
     * IN_PROGRESS or WAITING_REVIEW and the loss port says the report may be retried. Nothing is
     * written.
     *
     * @param actor caller captured by the controller, revalidated and required to hold ASSET_ADMIN
     * @param taskId inventory task whose readiness page is read
     * @param page one-based page number of the item list
     * @param size maximum number of items per page
     * @return task row, the page of readiness items ordered by asset id, the total item count and
     *     the two aggregate counters of items still missing a result and items recorded as
     *     unobserved
     * @throws InventoryFailure 403 INVENTORY_REVIEW_ADMIN_REQUIRED without the ASSET_ADMIN role, or
     *     404 INVENTORY_REVIEW_TASK_NOT_FOUND when the task is missing, DRAFT or DELETED
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public SelectionViews.ReadinessView detail(
            Actor actor, long taskId, int page, int size) {
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
        var missingReasons = items.isEmpty() ? List.<Map<String, Object>>of()
                : new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(db).queryForList("""
                    SELECT id,item_id,recheck_generation,reason FROM fams_inventory_unobserved_reason
                    WHERE task_id=:task AND item_id IN (:items) AND reason_kind='ASSET_NOT_FOUND'
                    ORDER BY item_id,recheck_generation
                    """, Map.of("task", taskId, "items",
                        items.stream().map(row -> number(row, "id")).toList()));
        var progress = losses.progress(missingReasons.stream().map(row -> number(row, "id")).toList());
        var byItem = missingReasons.stream().collect(java.util.stream.Collectors.groupingBy(
                row -> number(row, "item_id")));
        var rows =
                items.stream()
                        .map(
                                row -> {
                                    var context = contexts.get(number(row, "asset_id"));
                                    var reasons = byItem.getOrDefault(number(row, "id"), List.of());
                                    return new SelectionViews.ReadinessItem(
                                            row.get("id").toString(),
                                            SelectionViews.readinessItemRow(json, row),
                                            context == null
                                                    ? null
                                                    : json.readTree(context.snapshotJson()),
                                            context != null
                                                    && "IN_PROGRESS".equals(task.get("task_status"))
                                                    && row.get("review_status") == null
                                                    && number(row, "valid_raw_count") == 0,
                                            reasons.stream()
                                                    .anyMatch(
                                                            reason -> {
                                                                var report =
                                                                        progress.get(
                                                                                number(
                                                                                        reason,
                                                                                        "id"));
                                                                return report == null
                                                                        || !report.transferred();
                                                            }),
                                            reasons.stream()
                                                    .map(
                                                            reason -> {
                                                                var report =
                                                                        progress.get(
                                                                                number(
                                                                                        reason,
                                                                                        "id"));
                                                                return new SelectionViews
                                                                                .MissingLossRow(
                                                                        reason.get("id").toString(),
                                                                        number(reason,
                                                                                "recheck_generation"),
                                                                        (String) reason.get("reason"),
                                                                        report,
                                                                        context != null
                                                                                && List.of(
                                                                                                "IN_PROGRESS",
                                                                                                "WAITING_REVIEW")
                                                                                        .contains(
                                                                                                task.get(
                                                                                                        "task_status"))
                                                                                && (report == null
                                                                                        || report
                                                                                                .canRetry()));
                                                            })
                                                    .toList());
                                })
                        .toList();
        LOG.debug("Readiness view requested for task {} page {} size {}.", taskId, page, size);
        return new SelectionViews.ReadinessView(
                SelectionViews.taskRow(json, task),
                rows,
                count(taskId),
                page,
                size,
                missing(taskId),
                notObserved(taskId));
    }

    /**
     * Pages the unobserved reasons recorded for one item across all its recheck generations, newest
     * generation first. This is the audit trail behind a NOT_OBSERVED review: it shows whether an
     * item was repeatedly unobservable or was escalated to a loss report in an earlier round.
     * Nothing is written.
     *
     * @param actor caller captured by the controller, revalidated and required to hold ASSET_ADMIN
     * @param taskId inventory task owning the item
     * @param itemId fams_inventory_item id whose reason history is read
     * @param page one-based page number
     * @param size maximum number of reason rows per page
     * @return reason rows ordered by descending recheck generation, plus the total row count and the
     *     echoed paging arguments
     * @throws InventoryFailure 403 INVENTORY_REVIEW_ADMIN_REQUIRED without the ASSET_ADMIN role,
     *     404 INVENTORY_REVIEW_TASK_NOT_FOUND for a missing/DRAFT/DELETED task, or
     *     404 INVENTORY_ITEM_NOT_FOUND when the item does not belong to the task
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public SelectionViews.UnobservedHistoryPage history(
            Actor actor, long taskId, long itemId, int page, int size) {
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
        LOG.debug("Unobserved history requested for task {} item {} page {} size {}.", taskId, itemId, page, size);
        return new SelectionViews.UnobservedHistoryPage(
                SelectionViews.unobservedReasons(rows), total, page, size);
    }

    /**
     * Records that an item could not be physically observed, which is an explicit conclusion and
     * never a substitute for an observation. The call is refused when the item already has a review
     * row, when it already carries a non-EXPIRED observation or a round result for the same recheck
     * generation, or when the asset card moved since the client read it. On success it writes a
     * NOT_OBSERVED review row, the matching fams_inventory_unobserved_reason row, bumps the task
     * version and stores the reply as an idempotency receipt keyed by the request id.
     *
     * @param actor caller captured by the controller, revalidated and required to hold ASSET_ADMIN
     * @param taskId inventory task owning the item
     * @param itemId fams_inventory_item id to mark as unobserved
     * @param version task version the client read through If-Match-Version; the reply carries
     *     version+1
     * @param key client idempotency key; a repeat with the same key and digest replays the stored
     *     reply, and the same key with a different digest is rejected as a conflict
     * @param input unobserved payload carrying the recheck generation, the asset version and one of
     *     the kinds UNABLE_TO_OBSERVE, EVIDENCE_UNAVAILABLE or ASSET_NOT_FOUND
     * @return reply naming the created reason row, the new task version and whether the kind was
     *     ASSET_NOT_FOUND and therefore eligible for loss escalation
     * @throws InventoryReviewConflict when the item is already reviewed, the task version or status
     *     changed, the recheck generation no longer matches, or the asset card moved; the conflict
     *     is audited and does not roll the transaction back
     * @throws InventoryFailure 403 without ASSET_ADMIN, 404 for an unknown task or item,
     *     400 INVENTORY_UNOBSERVED_KIND_INVALID for an unknown kind,
     *     400 INVENTORY_UNOBSERVED_BASIS_REQUIRED when the reason is shorter than 3 or the evidence
     *     shorter than 10 characters, and 409 INVENTORY_OBSERVATION_ALREADY_PRESENT when a usable
     *     observation or round result already exists
     */
    @Transactional(
        isolation = Isolation.REPEATABLE_READ,
        rollbackFor = Exception.class,
        noRollbackFor = InventoryReviewConflict.class)
    public SelectionViews.UnobservedReply record(
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
        var prior =
                original(
                        actor,
                        current,
                        task,
                        operation,
                        key,
                        digest,
                        SelectionViews.UnobservedReply.class);
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
                    + " fams_inventory_item_review(item_id,recheck_generation,task_id,observation_id,review_status,"
                    + "verified_observed_at,reviewed_asset_version,reason,evidence,reviewer_account_id,"
                    + "reviewer_username,reviewed_at,version)"
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
                    + " fams_inventory_unobserved_reason(task_id,item_id,recheck_generation,reason_kind,reason,"
                    + "evidence,reviewed_asset_version,actor_account_id,actor_username,recorded_at)"
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
                new SelectionViews.UnobservedReply(
                        Long.toString(taskId),
                        Long.toString(itemId),
                        Long.toString(id),
                        version + 1,
                        1,
                        "NOT_OBSERVED",
                        "ASSET_NOT_FOUND".equals(input.kind()));
        audit(actor, current, task, "INVENTORY_NOT_OBSERVED", version + 1, response);
        save(current, taskId, operation, key, digest, response);
        LOG.info("Unobserved readiness recorded for task {} item {} by actor {}.", taskId, itemId, actor.id());
        return response;
    }

    /**
     * Escalates a recorded ASSET_NOT_FOUND reason into a formal loss report through the loss domain.
     * The reason row is re-read under FOR SHARE to confirm it belongs to this task and item and
     * really carries the ASSET_NOT_FOUND kind, the loss port is asked whether an existing report may
     * be retried, and only then is the submission created. The inventory side writes nothing but the
     * task version bump, the audit rows and the idempotency receipt, so the loss report itself
     * remains the single owner of the missing-asset lifecycle.
     *
     * @param actor caller captured by the controller, revalidated and required to hold ASSET_ADMIN
     * @param taskId inventory task owning the item
     * @param itemId fams_inventory_item id whose asset is missing
     * @param reasonId fams_inventory_unobserved_reason id to escalate, which must carry the
     *     ASSET_NOT_FOUND kind
     * @param version task version the client read through If-Match-Version; the reply carries
     *     version+1
     * @param key client idempotency key; a repeat with the same key and digest replays the stored
     *     reply, and the same key with a different digest is rejected as a conflict
     * @param input loss escalation payload with the pinned asset version, the last sighting time and
     *     place and the circumstances
     * @return reply naming the created loss report id and the new task version
     * @throws InventoryReviewConflict when the task version changed or the task is not IN_PROGRESS
     *     or WAITING_REVIEW; the conflict is audited and does not roll the transaction back
     * @throws InventoryFailure 403 without ASSET_ADMIN, 404 for an unknown task, item or reason,
     *     409 INVENTORY_MISSING_ALREADY_LINKED when the loss port refuses a retry,
     *     400 INVENTORY_MISSING_LOSS_BASIS_REQUIRED for circumstances shorter than 10 characters,
     *     and any loss-domain or asset-handover failure re-raised with its original status and code
     */
    @Transactional(
        isolation = Isolation.REPEATABLE_READ,
        rollbackFor = Exception.class,
        noRollbackFor = InventoryReviewConflict.class)
    public SelectionViews.MissingLossReply reportMissing(
            Actor actor,
            long taskId,
            long itemId,
            long reasonId,
            long version,
            UUID key,
            MissingLossInput input) {
        var current = admin(actor);
        var task = task(taskId);
        String operation = "MISSING_LOSS:" + reasonId;
        String digest = hash(version + ":" + itemId + ":" + json.writeValueAsString(input));
        var prior =
                original(
                        actor,
                        current,
                        task,
                        operation,
                        key,
                        digest,
                        SelectionViews.MissingLossReply.class);
        if (prior != null) {
            return prior;
        }
        if (number(task, "version") != version
                || !List.of("IN_PROGRESS", "WAITING_REVIEW").contains(task.get("task_status"))) {
            conflict(actor, current, task, "INVENTORY_READINESS_CONTEXT_CHANGED", Map.of());
        }
        var reasons = db.queryForList("""
                SELECT recheck_generation FROM fams_inventory_unobserved_reason
                WHERE id=? AND task_id=? AND item_id=? AND reason_kind='ASSET_NOT_FOUND' FOR SHARE
                """, reasonId, taskId, itemId);
        if (reasons.isEmpty()) {
            throw new InventoryFailure(404, "INVENTORY_MISSING_REASON_NOT_FOUND");
        }
        var report = losses.progress(List.of(reasonId)).get(reasonId);
        if (report != null && !report.canRetry()) {
            throw new InventoryFailure(409, "INVENTORY_MISSING_ALREADY_LINKED");
        }
        if (input.circumstances().strip().length() < 10) {
            throw new InventoryFailure(400, "INVENTORY_MISSING_LOSS_BASIS_REQUIRED");
        }
        var source = new InventoryLossPort.Source(taskId, itemId, reasonId,
                number(reasons.getFirst(), "recheck_generation"));
        String reportId;
        try {
            reportId = losses.submit(actor, source, new LossInput.Submission(
                    number(item(taskId, itemId), "asset_id"), input.assetVersion(), input.lastSeenAt(),
                    input.lastLocation(), input.circumstances()));
        } catch (LossFailure failure) {
            throw new InventoryFailure(failure.status(), failure.getMessage());
        } catch (AssetHandoverPort.Failure failure) {
            throw new InventoryFailure(failure.status(), failure.getMessage());
        }
        db.update("UPDATE fams_inventory_task SET version=version+1,updated_at=? WHERE id=?", now(), taskId);
        var response =
                new SelectionViews.MissingLossReply(
                        Long.toString(taskId),
                        Long.toString(itemId),
                        Long.toString(reasonId),
                        reportId,
                        version + 1);
        audit(actor, current, task, "INVENTORY_MISSING_REPORTED", version + 1, response);
        save(current, taskId, operation, key, digest, response);
        LOG.info("Missing loss reported for item {} of task {} reason {}.", itemId, taskId, reasonId);
        return response;
    }

    /**
     * Moves a task from IN_PROGRESS to WAITING_REVIEW once collection is provably complete. The gate
     * is arithmetic, not a flag: the item count must be non-zero, must equal the expected_count
     * frozen at publication, and no item may be left without either a round result or a matching
     * NOT_OBSERVED review plus reason row. Passing the gate freezes further assignment without a
     * recheck and hands the task to the review routes.
     *
     * @param actor caller captured by the controller, revalidated and required to hold ASSET_ADMIN
     * @param taskId inventory task to move to WAITING_REVIEW
     * @param version task version the client read through If-Match-Version; the reply carries
     *     version+1
     * @param key client idempotency key; a repeat with the same key and digest replays the stored
     *     reply, and the same key with a different digest is rejected as a conflict
     * @param input waiting payload whose reason must be at least 3 characters after stripping
     * @return reply naming the new task version, the WAITING_REVIEW status and the number of items
     *     recorded as unobserved
     * @throws InventoryReviewConflict when the task version changed or the task is not IN_PROGRESS;
     *     the conflict is audited and does not roll the transaction back
     * @throws InventoryFailure 403 without ASSET_ADMIN, 404 for an unknown task,
     *     400 INVENTORY_WAITING_REASON_REQUIRED for a too-short reason, and
     *     409 INVENTORY_RESULTS_OR_REASONS_REQUIRED while items still lack a result or an explicit
     *     unobserved reason
     */
    @Transactional(
        isolation = Isolation.REPEATABLE_READ,
        rollbackFor = Exception.class,
        noRollbackFor = InventoryReviewConflict.class)
    public SelectionViews.WaitingReply waiting(
            Actor actor,
            long taskId,
            long version,
            UUID key,
            InventoryReadinessInput.Waiting input) {
        var current = admin(actor);
        var task = task(taskId);
        String operation = "WAITING_REVIEW";
        String digest = hash(version + ":" + json.writeValueAsString(input));
        var prior =
                original(
                        actor,
                        current,
                        task,
                        operation,
                        key,
                        digest,
                        SelectionViews.WaitingReply.class);
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
                new SelectionViews.WaitingReply(
                        Long.toString(taskId), version + 1, "WAITING_REVIEW", notObserved(taskId));
        audit(
                actor,
                current,
                task,
                "INVENTORY_WAITING_REVIEW",
                version + 1,
                Map.of("reason", input.reason().strip(), "result", response));
        save(current, taskId, operation, key, digest, response);
        LOG.info("Waiting readiness recorded for task {} by actor {}.", taskId, actor.id());
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

    /** Replays a stored receipt into the typed exit of its own operation (JAVA-16a blueprint). */
    private <T> T original(
            Actor actor,
            Current current,
            Map<String, Object> task,
            String operation,
            UUID key,
            String hash,
            Class<T> type) {
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
        return json.readValue(rows.getFirst().get("response").toString(), type);
    }

    private void save(
            Current actor, long task, String operation, UUID key, String hash, Object response) {
        db.update(
                "INSERT INTO"
                    + " fams_inventory_review_receipt(task_id,actor_account_id,operation,request_id,payload_sha256,"
                    + "response_json,created_at)"
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
                    + " fams_audit_event(request_id,actor_account_id,actor_role,action,resource_type,resource_id,"
                    + "before_version,after_version,occurred_at,detail_json)"
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
