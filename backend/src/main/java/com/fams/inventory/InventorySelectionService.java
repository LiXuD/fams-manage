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

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Explicit, versioned evidence choices preserve every original collection fact and decision. */
@Service
@Profile("fams-product")
public class InventorySelectionService {
    private static final Logger LOG = LoggerFactory.getLogger(InventorySelectionService.class);
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RequisitionActorContext actors;
    private final AssetInventoryReviewPort assets;
    private final Clock clock;
    private final InventoryDifferenceService differences;

    public InventorySelectionService(
            JdbcTemplate db,
            ObjectMapper json,
            RequisitionActorContext actors,
            AssetInventoryReviewPort assets,
            Clock clock,
            InventoryDifferenceService differences) {
        this.db = db;
        this.json = json;
        this.actors = actors;
        this.assets = assets;
        this.clock = clock;
        this.differences = differences;
    }

    /** The frozen detail exit has no total without its COUNT row. */
    private static final String TOTAL_REQUIRED = "INVENTORY_SELECTION_TOTAL_REQUIRED";

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public SelectionViews.SelectionDetailView detail(
            Actor actor, long taskId, long itemId, int page, int size) {
        admin(actor);
        var task = task(taskId);
        var item = item(taskId, itemId);
        var context = context(task, item);
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_selection_event WHERE task_id=? AND item_id=?"
                                + " ORDER BY recheck_generation,review_version LIMIT ? OFFSET ? FOR"
                                + " SHARE",
                        taskId,
                        itemId,
                        size,
                        ((long) page - 1) * size);
        var currentReview = review(item);
        // The COUNT FOR SHARE stays the last locked read of this exit (legacy argument order).
        var total =
                java.util.Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM fams_inventory_selection_event WHERE task_id=?"
                                        + " AND item_id=? FOR SHARE",
                                Long.class,
                                taskId,
                                itemId),
                        TOTAL_REQUIRED);
        LOG.debug("Selection detail requested for task {} item {} page {} size {}.", taskId, itemId, page, size);
        return new SelectionViews.SelectionDetailView(
                Long.toString(taskId),
                number(task, "version"),
                Long.toString(itemId),
                number(item, "recheck_generation"),
                SelectionViews.reviewChip(currentReview),
                digest(context),
                context.movements(),
                SelectionViews.selectionEvents(json, rows),
                page,
                size,
                total);
    }

    @Transactional(
        isolation = Isolation.REPEATABLE_READ,
        rollbackFor = Exception.class,
        noRollbackFor = InventoryReviewConflict.class)
    public SelectionViews.SelectionReply select(
            Actor actor,
            long taskId,
            long itemId,
            long version,
            UUID key,
            InventorySelectionInput input) {
        var current = admin(actor);
        var task = task(taskId);
        String hash = hash(version + "\n" + itemId + "\n" + json.writeValueAsString(input));
        var receipts =
                db.queryForList(
                        "SELECT payload_sha256,CAST(response_json AS CHAR) AS response FROM"
                            + " fams_inventory_review_receipt WHERE task_id=? AND"
                            + " actor_account_id=? AND operation='SELECT_EVIDENCE' AND request_id=?"
                            + " FOR UPDATE",
                        taskId,
                        current.id(),
                        key.toString());
        if (!receipts.isEmpty()) {
            if (!hash.equals(receipts.getFirst().get("payload_sha256"))) {
                reject(actor, current, task, "INVENTORY_REVIEW_IDEMPOTENCY_CONFLICT", Map.of());
            }
            return json.readValue(
                    receipts.getFirst().get("response").toString(),
                    SelectionViews.SelectionReply.class);
        }
        var item = item(taskId, itemId);
        var prior = review(item);
        long reviewVersion = prior.isEmpty() ? 0 : number(prior, "version");
        if (number(item, "recheck_generation") != input.recheckGeneration()
                || reviewVersion != input.reviewVersion()
                || (!prior.isEmpty() && !"SELECTED".equals(prior.get("review_status")))) {
            reject(actor, current, task, "INVENTORY_REVIEW_VERSION_CONFLICT", prior);
        }
        if (number(task, "version") != version
                || !List.of("IN_PROGRESS", "WAITING_REVIEW").contains(task.get("task_status"))) {
            reject(actor, current, task, "INVENTORY_REVIEW_TASK_CHANGED", prior);
        }
        if (!assets.matchesVersion(number(item, "asset_id"), input.assetVersion())) {
            reject(actor, current, task, "INVENTORY_REVIEW_ASSET_CHANGED", prior);
        }
        var context = context(task, item);
        if (!digest(context).equals(input.contextDigest())) {
            reject(actor, current, task, "INVENTORY_REVIEW_CONTEXT_CHANGED", prior);
        }
        var observations =
                db.queryForList(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=?"
                                + " AND item_id=? AND id=? AND recheck_generation=? FOR SHARE",
                        taskId,
                        itemId,
                        input.observationId(),
                        input.recheckGeneration());
        if (observations.isEmpty()) {
            throw new InventoryFailure(404, "INVENTORY_OBSERVATION_NOT_FOUND");
        }
        var observation = observations.getFirst();
        String outcome = observation.get("outcome").toString();
        if (!List.of(
                        "ACCEPTED",
                        "DIFFERENCE_REVIEW",
                        "CONFLICT_REVIEW",
                        "OLD_ASSIGNMENT_REVIEW",
                        "TIME_REVIEW")
                .contains(outcome)) {
            throw new InventoryFailure(409, "INVENTORY_SELECTION_EVIDENCE_INELIGIBLE");
        }
        boolean verified = "TIME_REVIEW".equals(outcome);
        Instant selectedTime;
        if (verified) {
            selectedTime = input.verifiedObservedAt();
        } else {
            if (input.verifiedObservedAt() != null) {
                throw new InventoryFailure(400, "INVENTORY_SELECTION_RETIME_FORBIDDEN");
            }
            selectedTime =
                    observation.get("calibrated_observed_at") == null
                            ? null
                            : instant(observation.get("calibrated_observed_at"));
        }
        if (selectedTime == null
                || !selectedTime.equals(selectedTime.truncatedTo(ChronoUnit.MILLIS))
                || selectedTime.isBefore(instant(task.get("published_at")))
                || selectedTime.isAfter(instant(observation.get("received_at")))
                || selectedTime.isAfter(clock.instant())) {
            throw new InventoryFailure(400, "INVENTORY_SELECTION_TIME_INVALID");
        }
        Long decided =
                db.queryForObject(
                        "SELECT (SELECT COUNT(*) FROM fams_inventory_evidence_disposition WHERE"
                                + " task_id=? AND item_id=? AND recheck_generation=?) + (SELECT"
                                + " COUNT(*) FROM fams_inventory_review_decision d JOIN"
                                + " fams_inventory_observation o ON o.id=d.observation_id WHERE"
                                + " d.task_id=? AND o.item_id=? AND o.recheck_generation=? AND"
                                + " d.method='VERIFY_SELECTED')",
                        Long.class,
                        taskId,
                        itemId,
                        input.recheckGeneration(),
                        taskId,
                        itemId,
                        input.recheckGeneration());
        if (decided != null && decided > 0) {
            reject(actor, current, task, "INVENTORY_REVIEW_RECHECK_REQUIRED", prior);
        }
        long after = reviewVersion + 1;
        if (prior.isEmpty()) {
            db.update(
                    "INSERT INTO"
                        + " fams_inventory_item_review(item_id,recheck_generation,task_id,observation_id,"
                        + "review_status,verified_observed_at,reviewed_asset_version,reason,evidence,"
                        + "reviewer_account_id,reviewer_username,reviewed_at,version)"
                        + " VALUES (?,?,?,?,'SELECTED',?,?,?,?,?,?,?,?)",
                    itemId,
                    input.recheckGeneration(),
                    taskId,
                    input.observationId(),
                    Timestamp.from(selectedTime),
                    input.assetVersion(),
                    input.reason(),
                    input.evidence(),
                    current.id(),
                    current.username(),
                    now(),
                    after);
        } else {
            db.update(
                    "UPDATE fams_inventory_item_review SET observation_id=?,verified_observed_at=?,"
                        + "reviewed_asset_version=?,reason=?,evidence=?,reviewer_account_id=?,reviewer_username=?,"
                        + "reviewed_at=?,version=?"
                        + " WHERE item_id=? AND recheck_generation=? AND version=?",
                    input.observationId(),
                    Timestamp.from(selectedTime),
                    input.assetVersion(),
                    input.reason(),
                    input.evidence(),
                    current.id(),
                    current.username(),
                    now(),
                    after,
                    itemId,
                    input.recheckGeneration(),
                    reviewVersion);
        }
        db.update(
                "INSERT INTO"
                    + " fams_inventory_selection_event(item_id,recheck_generation,review_version,task_id,"
                    + "observation_id,review_status,verified_observed_at,time_source,reviewed_asset_version,"
                    + "physical_snapshot_json,movement_digest,movement_count,reason,evidence,actor_account_id,"
                    + "actor_username,recorded_at)"
                    + " VALUES (?,?,?,?,?,'SELECTED',?,?,?,?,?,?,?,?,?,?,?)",
                itemId,
                input.recheckGeneration(),
                after,
                taskId,
                input.observationId(),
                Timestamp.from(selectedTime),
                verified ? "ADMIN_VERIFIED" : "CALIBRATED",
                input.assetVersion(),
                json.writeValueAsString(physical(context)),
                digest(context),
                context.movements().size(),
                input.reason(),
                input.evidence(),
                current.id(),
                current.username(),
                now());
        db.update(
                "UPDATE fams_inventory_task SET version=version+1,updated_at=? WHERE id=?",
                now(),
                taskId);
        var response =
                new SelectionViews.SelectionReply(
                        Long.toString(taskId),
                        Long.toString(itemId),
                        version + 1,
                        SelectionViews.reviewState(review(item)),
                        digest(context));
        audit(actor, current, task, "INVENTORY_EVIDENCE_SELECTED", response);
        db.update(
                "INSERT INTO"
                    + " fams_inventory_review_receipt(task_id,actor_account_id,operation,request_id,payload_sha256,"
                    + "response_json,created_at)"
                    + " VALUES (?,?,'SELECT_EVIDENCE',?,?,?,?)",
                taskId,
                current.id(),
                key.toString(),
                hash,
                json.writeValueAsString(response),
                now());
        LOG.info("Inventory selection committed for task {} item {} by actor {}.", taskId, itemId, actor.id());
        return response;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public SelectionViews.EvidenceSubjectView evidenceSubjects(
            Actor actor, long taskId, long itemId, long observationId) {
        admin(actor);
        var task = task(taskId);
        var item = item(taskId, itemId);
        var raw = observation(taskId, itemId, observationId, number(item, "recheck_generation"));
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_review_subject WHERE observation_id=? ORDER"
                                + " BY dimension FOR SHARE",
                        observationId);
        var result = new java.util.ArrayList<SelectionViews.EvidenceSubjectRow>();
        for (var row : rows) {
            // decisions, links and repairLinks are read per subject in the legacy append order.
            var decisionRows =
                    db.queryForList(
                            "SELECT * FROM fams_inventory_review_decision WHERE observation_id=?"
                                    + " AND dimension=? ORDER BY after_version FOR SHARE",
                            observationId,
                            row.get("dimension"));
            var linkRows =
                    db.queryForList(
                            "SELECT * FROM fams_inventory_review_transfer WHERE observation_id=?"
                                    + " AND dimension=? ORDER BY decision_version FOR SHARE",
                            observationId,
                            row.get("dimension"));
            var repairRows =
                    db.queryForList(
                            "SELECT * FROM fams_inventory_review_repair WHERE observation_id=? AND"
                                + " dimension=? ORDER BY decision_version FOR SHARE",
                            observationId,
                            row.get("dimension"));
            result.add(
                    SelectionViews.evidenceSubject(row, decisionRows, linkRows, repairRows));
        }
        LOG.debug("Evidence subjects requested for task {} item {} observation {}.", taskId, itemId, observationId);
        return new SelectionViews.EvidenceSubjectView(
                result,
                differences.requiredDimensions(task, raw).stream().sorted().toList());
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public SelectionViews.DispositionPage dispositions(
            Actor actor, long taskId, long itemId, int page, int size) {
        admin(actor);
        task(taskId);
        item(taskId, itemId);
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_evidence_disposition WHERE task_id=?"
                            + " AND item_id=? ORDER BY observation_id LIMIT ? OFFSET ? FOR"
                            + " SHARE",
                        taskId,
                        itemId,
                        size,
                        ((long) page - 1) * size);
        var total =
                java.util.Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM fams_inventory_evidence_disposition WHERE"
                                        + " task_id=? AND item_id=? FOR SHARE",
                                Long.class,
                                taskId,
                                itemId),
                        TOTAL_REQUIRED);
        LOG.debug("Dispositions requested for task {} item {} page {} size {}.", taskId, itemId, page, size);
        return new SelectionViews.DispositionPage(
                SelectionViews.evidenceDispositions(rows), total, page, size);
    }

    @Transactional(
        isolation = Isolation.REPEATABLE_READ,
        rollbackFor = Exception.class,
        noRollbackFor = InventoryReviewConflict.class)
    public SelectionViews.EvidenceDecisionReply decideEvidence(
            Actor actor,
            long taskId,
            long itemId,
            long version,
            UUID key,
            InventoryEvidenceInput.Decision input) {
        var current = admin(actor);
        var task = task(taskId);
        String fingerprint = hash(version + "\n" + itemId + "\n" + json.writeValueAsString(input));
        var original =
                original(
                        actor,
                        current,
                        task,
                        key,
                        "DECIDE_EVIDENCE",
                        fingerprint,
                        SelectionViews.EvidenceDecisionReply.class);
        if (original != null) {
            return original;
        }
        var item = item(taskId, itemId);
        var review = review(item);
        guardReview(
                actor,
                current,
                task,
                item,
                review,
                input.recheckGeneration(),
                input.reviewVersion());
        if (!List.of("VERIFY_SELECTED", "REJECT_EVIDENCE", "SUPPLEMENTAL")
                .contains(input.method())) {
            throw new InventoryFailure(400, "INVENTORY_REVIEW_METHOD_INVALID");
        }
        var observation =
                observation(taskId, itemId, input.observationId(), input.recheckGeneration());
        boolean chosen = number(review, "observation_id") == input.observationId();
        if (chosen != "VERIFY_SELECTED".equals(input.method())
                || chosen && !"SELECTED".equals(review.get("review_status"))) {
            throw new InventoryFailure(409, "INVENTORY_REVIEW_COUNTEREVIDENCE_REQUIRED");
        }
        if (!chosen) {
            var prior =
                    db.queryForList(
                            "SELECT * FROM fams_inventory_evidence_disposition WHERE"
                                    + " observation_id=? FOR UPDATE",
                            input.observationId());
            if (!prior.isEmpty()) {
                rejectSubject(
                        actor,
                        current,
                        task,
                        prior.getFirst(),
                        "INVENTORY_REVIEW_EVIDENCE_ALREADY_DECIDED");
            }
        }
        var subjects =
                db.queryForList(
                        "SELECT * FROM fams_inventory_review_subject WHERE observation_id=? ORDER"
                                + " BY dimension FOR UPDATE",
                        input.observationId());
        var provided = new java.util.TreeMap<String, Long>();
        for (var reference : input.subjects()) {
            if (provided.put(reference.dimension(), reference.version()) != null) {
                throw new InventoryFailure(400, "INVENTORY_REVIEW_SUBJECT_DUPLICATE");
            }
        }
        var required = differences.requiredDimensions(task, observation);
        if (!subjects.stream()
                .map(row -> row.get("dimension").toString())
                .collect(java.util.stream.Collectors.toSet())
                .containsAll(required)) {
            throw new InventoryFailure(409, "INVENTORY_REVIEW_PREPARATION_REQUIRED");
        }
        var involved =
                chosen
                        ? subjects.stream()
                                .filter(row -> special(row.get("dimension").toString()))
                                .toList()
                        : subjects;
        if (provided.size() != involved.size()
                || involved.stream()
                        .anyMatch(row -> !provided.containsKey(row.get("dimension").toString()))) {
            throw new InventoryFailure(409, "INVENTORY_REVIEW_ALL_DIMENSIONS_REQUIRED");
        }
        for (var subject : involved) {
            if (number(subject, "version") != provided.get(subject.get("dimension").toString())) {
                rejectSubject(actor, current, task, subject, "INVENTORY_REVIEW_VERSION_CONFLICT");
            }
        }
        if (chosen && involved.isEmpty()) {
            throw new InventoryFailure(400, "INVENTORY_REVIEW_NO_SPECIAL_DIMENSIONS");
        }
        if (chosen
                && involved.stream()
                        .allMatch(
                                row ->
                                        List.of("CLOSED", "TRANSFERRED")
                                                .contains(row.get("subject_status")))) {
            var winner =
                    involved.stream()
                            .filter(row -> row.get("reviewed_at") != null)
                            .max(
                                    java.util.Comparator.comparing(
                                            row -> instant(row.get("reviewed_at"))))
                            .orElse(involved.getFirst());
            rejectSubject(
                    actor, current, task, winner, "INVENTORY_REVIEW_EVIDENCE_ALREADY_DECIDED");
        }
        guard(
                actor,
                current,
                task,
                item,
                review,
                version,
                input.recheckGeneration(),
                input.reviewVersion(),
                input.assetVersion(),
                input.contextDigest());
        if (chosen) {
            var event = selectionEvent(item, review);
            if (!input.contextDigest().equals(event.get("movement_digest"))) {
                throw new InventoryFailure(409, "INVENTORY_REVIEW_CONTEXT_CHANGED");
            }
            retention(observation, instant(review.get("verified_observed_at")));
        }
        if ("REJECT_EVIDENCE".equals(input.method())) {
            Long links =
                    db.queryForObject(
                            "SELECT (SELECT COUNT(*) FROM fams_inventory_review_transfer WHERE"
                                + " observation_id=?) + (SELECT COUNT(*) FROM"
                                + " fams_inventory_review_repair WHERE observation_id=?)",
                            Long.class,
                            input.observationId(),
                            input.observationId());
            if (links != null && links > 0) {
                throw new InventoryFailure(409, "INVENTORY_REVIEW_LINKED_EVIDENCE_REQUIRED");
            }
        }
        int closed = 0;
        for (var subject : involved) {
            String dimension = subject.get("dimension").toString();
            boolean close =
                    chosen || "REJECT_EVIDENCE".equals(input.method()) || special(dimension);
            if (!close
                    || List.of("CLOSED", "TRANSFERRED").contains(subject.get("subject_status"))) {
                continue;
            }
            Long activeLinks =
                    db.queryForObject(
                            "SELECT (SELECT COUNT(*) FROM fams_inventory_review_transfer WHERE"
                                + " observation_id=? AND dimension=?) + (SELECT COUNT(*) FROM"
                                + " fams_inventory_review_repair WHERE observation_id=? AND"
                                + " dimension=?)",
                            Long.class,
                            input.observationId(),
                            dimension,
                            input.observationId(),
                            dimension);
            if ("PROCESSING".equals(subject.get("subject_status"))
                    && activeLinks != null
                    && activeLinks > 0) {
                throw new InventoryFailure(409, "INVENTORY_REVIEW_LINKED_EVIDENCE_REQUIRED");
            }
            long before = number(subject, "version");
            db.update(
                    "UPDATE fams_inventory_review_subject SET"
                        + " subject_status='CLOSED',method=?,reason=?,evidence=?,reviewer_account_id=?,"
                        + "reviewer_username=?,reviewed_at=?,version=version+1"
                        + " WHERE observation_id=? AND dimension=? AND version=?",
                    input.method(),
                    input.reason(),
                    input.evidence(),
                    current.id(),
                    current.username(),
                    now(),
                    input.observationId(),
                    dimension,
                    before);
            db.update(
                    "INSERT INTO"
                        + " fams_inventory_review_decision(task_id,observation_id,dimension,before_version,"
                        + "after_version,method,subject_status,reason,evidence,reviewed_asset_version,actor_account_id,"
                        + "actor_username,occurred_at)"
                        + " VALUES (?,?,?,?,?,?,'CLOSED',?,?,?,?,?,?)",
                    taskId,
                    input.observationId(),
                    dimension,
                    before,
                    before + 1,
                    input.method(),
                    input.reason(),
                    input.evidence(),
                    input.assetVersion(),
                    current.id(),
                    current.username(),
                    now());
            closed++;
        }
        if (!chosen) {
            db.update(
                    "INSERT INTO"
                        + " fams_inventory_evidence_disposition(observation_id,task_id,item_id,recheck_generation,"
                        + "counter_observation_id,counter_review_version,disposition,reviewed_asset_version,"
                        + "context_digest,reason,evidence,actor_account_id,actor_username,recorded_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    input.observationId(),
                    taskId,
                    itemId,
                    input.recheckGeneration(),
                    review.get("observation_id"),
                    input.reviewVersion(),
                    "REJECT_EVIDENCE".equals(input.method()) ? "REJECTED" : "SUPPLEMENTAL",
                    input.assetVersion(),
                    input.contextDigest(),
                    input.reason(),
                    input.evidence(),
                    current.id(),
                    current.username(),
                    now());
        }
        LOG.info("Evidence decision committed for task {} item {} by actor {}.", taskId, itemId, actor.id());
        return finishEvidence(
                actor,
                current,
                task,
                key,
                "DECIDE_EVIDENCE",
                fingerprint,
                new SelectionViews.EvidenceDecisionReply(
                        Long.toString(input.observationId()),
                        input.method(),
                        closed,
                        task.get("id").toString(),
                        Long.toString(itemId),
                        version + 1));
    }

    @Transactional(
        isolation = Isolation.REPEATABLE_READ,
        rollbackFor = Exception.class,
        noRollbackFor = InventoryReviewConflict.class)
    public SelectionViews.EvidenceAcceptanceReply acceptSelected(
            Actor actor,
            long taskId,
            long itemId,
            long version,
            UUID key,
            InventoryEvidenceInput.Acceptance input) {
        var current = admin(actor);
        var task = task(taskId);
        String fingerprint = hash(version + "\n" + itemId + "\n" + json.writeValueAsString(input));
        var original =
                original(
                        actor,
                        current,
                        task,
                        key,
                        "ACCEPT_SELECTED",
                        fingerprint,
                        SelectionViews.EvidenceAcceptanceReply.class);
        if (original != null) {
            return original;
        }
        var item = item(taskId, itemId);
        var review = review(item);
        guard(
                actor,
                current,
                task,
                item,
                review,
                version,
                input.recheckGeneration(),
                input.reviewVersion(),
                input.assetVersion(),
                input.contextDigest());
        if (!"SELECTED".equals(review.get("review_status"))) {
            reject(actor, current, task, "INVENTORY_REVIEW_VERSION_CONFLICT", review);
        }
        var event = selectionEvent(item, review);
        if (!input.contextDigest().equals(event.get("movement_digest"))) {
            throw new InventoryFailure(409, "INVENTORY_REVIEW_CONTEXT_CHANGED");
        }
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=? AND item_id=? AND"
                                + " recheck_generation=? ORDER BY id FOR SHARE",
                        taskId,
                        itemId,
                        input.recheckGeneration());
        for (var raw : rows) {
            if (List.of("DUPLICATE", "EXPIRED").contains(raw.get("outcome"))) {
                continue;
            }
            boolean selected = number(raw, "id") == number(review, "observation_id");
            if (selected) {
                retention(raw, instant(review.get("verified_observed_at")));
            } else {
                var rejected =
                        db.queryForList(
                                "SELECT * FROM fams_inventory_evidence_disposition WHERE"
                                        + " observation_id=? AND counter_observation_id=? AND"
                                        + " counter_review_version=? FOR SHARE",
                                raw.get("id"),
                                review.get("observation_id"),
                                input.reviewVersion());
                if (rejected.isEmpty()) {
                    throw new InventoryFailure(409, "INVENTORY_REVIEW_UNDECIDED_EVIDENCE");
                }
            }
            var subjects =
                    db.queryForList(
                            "SELECT dimension,subject_status FROM fams_inventory_review_subject"
                                    + " WHERE observation_id=? FOR SHARE",
                            raw.get("id"));
            var required = differences.requiredDimensions(task, raw);
            if (!subjects.stream()
                            .map(row -> row.get("dimension").toString())
                            .collect(java.util.stream.Collectors.toSet())
                            .containsAll(required)
                    || subjects.stream()
                            .anyMatch(
                                    row ->
                                            !List.of("CLOSED", "TRANSFERRED")
                                                    .contains(row.get("subject_status")))) {
                throw new InventoryFailure(409, "INVENTORY_REVIEW_UNRESOLVED_DIMENSIONS");
            }
        }
        long after = input.reviewVersion() + 1;
        db.update(
                "UPDATE fams_inventory_item_review SET"
                    + " review_status='ACCEPTED',reviewed_asset_version=?,reason=?,evidence=?,reviewer_account_id=?,"
                    + "reviewer_username=?,reviewed_at=?,version=?"
                    + " WHERE item_id=? AND recheck_generation=? AND version=?",
                input.assetVersion(),
                input.reason(),
                input.evidence(),
                current.id(),
                current.username(),
                now(),
                after,
                itemId,
                input.recheckGeneration(),
                input.reviewVersion());
        db.update(
                "INSERT INTO"
                    + " fams_inventory_selection_event(item_id,recheck_generation,review_version,task_id,"
                    + "observation_id,review_status,verified_observed_at,time_source,reviewed_asset_version,"
                    + "physical_snapshot_json,movement_digest,movement_count,reason,evidence,actor_account_id,"
                    + "actor_username,recorded_at)"
                    + " VALUES (?,?,?,?,?,'ACCEPTED',?,?,?,?,?,?,?,?,?,?,?)",
                itemId,
                input.recheckGeneration(),
                after,
                taskId,
                review.get("observation_id"),
                review.get("verified_observed_at"),
                event.get("time_source"),
                input.assetVersion(),
                event.get("physical_snapshot_json"),
                event.get("movement_digest"),
                event.get("movement_count"),
                input.reason(),
                input.evidence(),
                current.id(),
                current.username(),
                now());
        // The legacy payload argument re-read the review row here, before finishEvidence bumped
        // the task version; the read keeps that exact position.
        var accepted = SelectionViews.reviewState(review(item));
        LOG.info("Selected evidence accepted for task {} item {} by actor {}.", taskId, itemId, actor.id());
        return finishEvidence(
                actor,
                current,
                task,
                key,
                "ACCEPT_SELECTED",
                fingerprint,
                new SelectionViews.EvidenceAcceptanceReply(
                        accepted, task.get("id").toString(), Long.toString(itemId), version + 1));
    }

    private boolean special(String dimension) {
        return List.of("CONFLICT", "OLD_ASSIGNMENT", "TIME").contains(dimension);
    }

    private Map<String, Object> observation(
            long task, long item, long observation, long generation) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=? AND item_id=? AND"
                                + " id=? AND recheck_generation=? FOR SHARE",
                        task,
                        item,
                        observation,
                        generation);
        if (rows.isEmpty()) {
            throw new InventoryFailure(404, "INVENTORY_OBSERVATION_NOT_FOUND");
        }
        var row = rows.getFirst();
        if (!List.of(
                        "ACCEPTED",
                        "DIFFERENCE_REVIEW",
                        "CONFLICT_REVIEW",
                        "OLD_ASSIGNMENT_REVIEW",
                        "TIME_REVIEW")
                .contains(row.get("outcome"))) {
            throw new InventoryFailure(409, "INVENTORY_SELECTION_EVIDENCE_INELIGIBLE");
        }
        return row;
    }

    private Map<String, Object> selectionEvent(
            Map<String, Object> item, Map<String, Object> review) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_selection_event WHERE item_id=? AND"
                                + " recheck_generation=? AND review_version=? FOR SHARE",
                        item.get("id"),
                        item.get("recheck_generation"),
                        review.get("version"));
        if (rows.isEmpty() || !"SELECTED".equals(rows.getFirst().get("review_status"))) {
            throw new InventoryFailure(409, "INVENTORY_REVIEW_SELECTION_REQUIRED");
        }
        return rows.getFirst();
    }

    private void retention(Map<String, Object> observation, Instant verified) {
        if (observation.get("calibrated_persisted_at") == null) {
            throw new InventoryFailure(409, "INVENTORY_REVIEW_RECOLLECTION_REQUIRED");
        }
        var calibrations =
                db.queryForList(
                        "SELECT client_time,server_time FROM fams_inventory_clock_calibration WHERE"
                                + " id=? FOR SHARE",
                        observation.get("calibration_id"));
        Instant first = instant(observation.get("first_persisted_at"));
        if (calibrations.isEmpty()
                || !instant(observation.get("client_expires_at"))
                        .equals(first.plus(java.time.Duration.ofHours(72)))) {
            throw new InventoryFailure(409, "INVENTORY_REVIEW_RECOLLECTION_REQUIRED");
        }
        var calibration = calibrations.getFirst();
        var elapsed = java.time.Duration.between(instant(calibration.get("client_time")), first);
        var offset =
                java.time.Duration.between(
                                instant(calibration.get("client_time")),
                                instant(calibration.get("server_time")))
                        .abs();
        if (elapsed.isNegative()
                || elapsed.compareTo(java.time.Duration.ofHours(72)) > 0
                || offset.compareTo(java.time.Duration.ofMinutes(5)) > 0) {
            throw new InventoryFailure(409, "INVENTORY_REVIEW_RECOLLECTION_REQUIRED");
        }
        Instant persisted = instant(observation.get("calibrated_persisted_at"));
        Instant received = instant(observation.get("received_at"));
        if (!received.isBefore(persisted.plus(java.time.Duration.ofHours(72)))
                || persisted.isAfter(received.plusSeconds(2))
                || verified.isAfter(persisted.plusSeconds(2))) {
            throw new InventoryFailure(409, "INVENTORY_REVIEW_RECOLLECTION_REQUIRED");
        }
    }

    private void guardReview(
            Actor actor,
            Current current,
            Map<String, Object> task,
            Map<String, Object> item,
            Map<String, Object> review,
            long generation,
            long reviewVersion) {
        if (review.isEmpty()
                || number(review, "version") != reviewVersion
                || number(item, "recheck_generation") != generation
                || !List.of("SELECTED", "ACCEPTED").contains(review.get("review_status"))) {
            reject(actor, current, task, "INVENTORY_REVIEW_VERSION_CONFLICT", review);
        }
    }

    private void rejectSubject(
            Actor actor,
            Current current,
            Map<String, Object> task,
            Map<String, Object> row,
            String code) {
        var fields = new LinkedHashMap<String, String>();
        Object name =
                row.get("reviewer_username") == null
                        ? row.get("actor_username")
                        : row.get("reviewer_username");
        Object time =
                row.get("reviewed_at") == null ? row.get("recorded_at") : row.get("reviewed_at");
        if (name != null) {
            fields.put("processedBy", name.toString());
        }
        if (time != null) {
            fields.put("processedAt", instant(time).toString());
        }
        boolean disposition = row.get("disposition") != null;
        fields.put("result", row.get(disposition ? "disposition" : "subject_status").toString());
        fields.put(
                "method",
                disposition
                        ? ("REJECTED".equals(row.get("disposition"))
                                ? "REJECT_EVIDENCE"
                                : "SUPPLEMENTAL")
                        : java.util.Objects.toString(row.get("method"), ""));
        fields.put("subjectVersion", row.get("version").toString());
        fields.put("taskVersion", task.get("version").toString());
        fields.put(
                "readOnlyUrl",
                InventoryReviewLinks.task(actor, number(task, "id"))
                        + (disposition
                                ? "/items/" + row.get("item_id") + "/evidence-dispositions"
                                : "/differences"));
        audit(
                actor,
                current,
                task,
                "INVENTORY_SELECTION_CONFLICT_REJECTED",
                Map.of("code", code, "fields", fields));
        throw new InventoryReviewConflict(code, fields);
    }

    private void guard(
            Actor actor,
            Current current,
            Map<String, Object> task,
            Map<String, Object> item,
            Map<String, Object> review,
            long taskVersion,
            long generation,
            long reviewVersion,
            long assetVersion,
            String expectedDigest) {
        guardReview(actor, current, task, item, review, generation, reviewVersion);
        if (number(task, "version") != taskVersion
                || !List.of("IN_PROGRESS", "WAITING_REVIEW").contains(task.get("task_status"))) {
            reject(actor, current, task, "INVENTORY_REVIEW_TASK_CHANGED", review);
        }
        if (!assets.matchesVersion(number(item, "asset_id"), assetVersion)) {
            reject(actor, current, task, "INVENTORY_REVIEW_ASSET_CHANGED", review);
        }
        if (!digest(context(task, item)).equals(expectedDigest)) {
            reject(actor, current, task, "INVENTORY_REVIEW_CONTEXT_CHANGED", review);
        }
    }

    /** Replays a stored receipt into the typed exit of its own operation (JAVA-16a blueprint). */
    private <T> T original(
            Actor actor,
            Current current,
            Map<String, Object> task,
            UUID key,
            String operation,
            String fingerprint,
            Class<T> type) {
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
        if (!fingerprint.equals(rows.getFirst().get("payload_sha256"))) {
            reject(actor, current, task, "INVENTORY_REVIEW_IDEMPOTENCY_CONFLICT", Map.of());
        }
        return json.readValue(rows.getFirst().get("response").toString(), type);
    }

    /**
     * Persists the already assembled reply. The caller builds it in place of the legacy
     * LinkedHashMap merge (payload keys, then taskId/itemId/version), which read no row, so the
     * UPDATE/audit/receipt order below is the frozen one.
     */
    private <T> T finishEvidence(
            Actor actor,
            Current current,
            Map<String, Object> task,
            UUID key,
            String operation,
            String fingerprint,
            T response) {
        db.update(
                "UPDATE fams_inventory_task SET version=version+1,updated_at=? WHERE id=?",
                now(),
                task.get("id"));
        audit(actor, current, task, operation, response);
        db.update(
                "INSERT INTO"
                    + " fams_inventory_review_receipt(task_id,actor_account_id,operation,request_id,payload_sha256,"
                    + "response_json,created_at)"
                    + " VALUES (?,?,?,?,?,?,?)",
                task.get("id"),
                current.id(),
                operation,
                key.toString(),
                fingerprint,
                json.writeValueAsString(response),
                now());
        return response;
    }

    private AssetInventoryReviewPort.Context context(
            Map<String, Object> task, Map<String, Object> item) {
        var value =
                assets.contexts(
                                List.of(number(item, "asset_id")),
                                instant(task.get("published_at")))
                        .get(number(item, "asset_id"));
        if (value == null) {
            throw new InventoryFailure(409, "INVENTORY_REVIEW_ASSET_UNAVAILABLE");
        }
        return value;
    }

    private Map<String, Object> physical(AssetInventoryReviewPort.Context context) {
        var tree = json.readTree(context.snapshotJson());
        var result = new java.util.TreeMap<String, Object>();
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
            var node = tree.path(field);
            result.put(field, node.isMissingNode() || node.isNull() ? null : node.asText());
        }
        return result;
    }

    private String digest(AssetInventoryReviewPort.Context context) {
        // Record component order and sorted physical keys are deterministic across fresh JVMs.
        return hash(
                json.writeValueAsString(physical(context))
                        + "\n"
                        + json.writeValueAsString(context.movements()));
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

    private Map<String, Object> review(Map<String, Object> item) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_item_review WHERE item_id=? AND"
                                + " recheck_generation=? FOR UPDATE",
                        item.get("id"),
                        item.get("recheck_generation"));
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private void reject(
            Actor actor,
            Current current,
            Map<String, Object> task,
            String code,
            Map<String, Object> review) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("taskVersion", task.get("version").toString());
        fields.put("readOnlyUrl", InventoryReviewLinks.task(actor, number(task, "id")));
        fields.put(
                "result", review.getOrDefault("review_status", task.get("task_status")).toString());
        if (!review.isEmpty()) {
            fields.put("processedBy", review.get("reviewer_username").toString());
            fields.put("processedAt", instant(review.get("reviewed_at")).toString());
            fields.put("reviewVersion", review.get("version").toString());
        }
        audit(
                actor,
                current,
                task,
                "INVENTORY_SELECTION_CONFLICT_REJECTED",
                Map.of("code", code, "fields", fields));
        throw new InventoryReviewConflict(code, fields);
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
                    + " fams_audit_event(request_id,actor_account_id,actor_role,action,resource_type,resource_id,"
                    + "before_version,after_version,occurred_at,detail_json)"
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

    private String hash(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            value.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private long number(Map<String, Object> row, String name) {
        return ((Number) row.get(name)).longValue();
    }

    private Instant instant(Object value) {
        return value instanceof Timestamp time
                ? time.toInstant()
                : ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS));
    }
}
