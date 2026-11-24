package com.fams.inventory;

import com.fams.asset.AssetInventoryReadPort;
import com.fams.asset.AssetInventoryReviewPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;
import com.fams.inbound.InventoryInboundPort;

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
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Finding closure follows authoritative cards and actual inbound acceptance, never free-text
 * progress.
 */
@Service
@Profile("fams-product")
public class InventoryFindingService {
    private static final Logger LOG = LoggerFactory.getLogger(InventoryFindingService.class);
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RequisitionActorContext actors;
    private final AssetInventoryReviewPort assets;
    private final AssetInventoryReadPort cards;
    private final InventoryInboundPort inbounds;
    private final Clock clock;

    /**
     * Injects the collaborators the finding review flow needs.
     *
     * @param db reads and writes the observation, review subject, decision, link and receipt tables
     * @param json projects the flat finding wire and serializes stored receipt texts
     * @param actors revalidates the caller session and proves the ASSET_ADMIN role
     * @param assets proves the owner version of a duplicate asset card still matches
     * @param cards identifies an asset by code and reads its authoritative current snapshot
     * @param inbounds resolves and validates the formal inbound acceptance a finding is linked to
     * @param clock stamps every persisted decision instant
     */
    public InventoryFindingService(
            JdbcTemplate db,
            ObjectMapper json,
            RequisitionActorContext actors,
            AssetInventoryReviewPort assets,
            AssetInventoryReadPort cards,
            InventoryInboundPort inbounds,
            Clock clock) {
        this.db = db;
        this.json = json;
        this.actors = actors;
        this.assets = assets;
        this.cards = cards;
        this.inbounds = inbounds;
        this.clock = clock;
    }

    /**
     * Searches the candidates an admin may bind a finding to. A non-blank receipt number switches to
     * the formal-inbound lookup and returns at most one FORMAL_INBOUND target; otherwise the asset code
     * drives a card lookup and returns at most one DUPLICATE_ASSET target read from the authoritative
     * current snapshot. An unmatched search is an empty list, never an error, because the admin still
     * has to be able to close the finding as NON_ENTERPRISE.
     *
     * @param actor caller who must hold the ASSET_ADMIN role
     * @param taskId inventory task the finding belongs to; locked but not written
     * @param assetCode asset code to search, trimmed before the lookup
     * @param receiptNumber formal inbound receipt number, or null to search by asset code only
     * @return the single matching target, or an empty list when nothing matches
     * @throws InventoryFailure 403 INVENTORY_REVIEW_ADMIN_REQUIRED without the ASSET_ADMIN role, or
     *     404 INVENTORY_REVIEW_TASK_NOT_FOUND when the task is missing, DRAFT or DELETED
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public FindingViews.FindingTargets targets(
            Actor actor, long taskId, String assetCode, String receiptNumber) {
        admin(actor);
        task(taskId);
        if (receiptNumber != null && !receiptNumber.isBlank()) {
            var row = inbounds.find(actor, receiptNumber.strip(), assetCode.strip());
            return new FindingViews.FindingTargets(
                    row == null
                            ? List.of()
                            : List.of(
                                    FindingViews.formalInboundTarget(
                                            row.assetId(),
                                            row.assetCode(),
                                            row.version(),
                                            row.receiptId(),
                                            receiptNumber.strip(),
                                            row.status())));
        }
        var rows = cards.identify("CODE", assetCode.strip());
        if (rows.isEmpty()) {
            return new FindingViews.FindingTargets(List.of());
        }
        String id = rows.getFirst().id();
        String snapshot = cards.currentSnapshot(Long.parseLong(id));
        if (snapshot == null) {
            return new FindingViews.FindingTargets(List.of());
        }
        var row = json.readTree(snapshot);
        LOG.debug("Finding targets searched for task {} code {} receipt {}.", taskId, assetCode, receiptNumber);
        return new FindingViews.FindingTargets(
                List.of(
                        FindingViews.duplicateAssetTarget(
                                id,
                                row.path("assetCode").asText(),
                                row.path("name").asText(),
                                row.path("version").asLong())));
    }

    /**
     * Assembles the finding review page of one observation. The subject is the empty/full union, so a
     * finding that was never prepared is emitted as a zero-key object rather than an all-null row. The
     * read order stays frozen - FINDING subject, decision history, TIME subjects, recollections, history
     * count - because the history is capped at the newest 100 decisions while the total still counts
     * every one.
     *
     * @param actor caller who must hold the ASSET_ADMIN role
     * @param taskId inventory task the finding belongs to
     * @param observationId finding observation, which must be unassigned and in FINDING_REVIEW or
     *     TIME_REVIEW
     * @return TIME subjects, recollections, task version, the observation row, the FINDING subject
     *     projection, the newest decision history and its total
     * @throws InventoryFailure 403 INVENTORY_REVIEW_ADMIN_REQUIRED without the ASSET_ADMIN role, 404
     *     INVENTORY_REVIEW_TASK_NOT_FOUND when the task is missing, DRAFT or DELETED, or 404
     *     INVENTORY_FINDING_NOT_FOUND when the observation is not an unassigned finding of this task
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public FindingViews.FindingDetail detail(Actor actor, long taskId, long observationId) {
        admin(actor);
        var task = task(taskId);
        var observation = observation(taskId, observationId);
        var subject =
                db.queryForList(
                        "SELECT * FROM fams_inventory_review_subject WHERE"
                                + " observation_id=? AND dimension='FINDING'",
                        observationId);
        var history =
                db.queryForList(
                        "SELECT d.*,CAST(l.asset_id AS CHAR) AS assetId, CAST(l.inbound_receipt_id"
                            + " AS CHAR) AS inboundReceiptId,l.link_kind,l.owner_version FROM"
                            + " fams_inventory_review_decision d LEFT JOIN"
                            + " fams_inventory_finding_link l ON l.observation_id=d.observation_id"
                            + " AND l.dimension=d.dimension AND l.decision_version=d.after_version"
                            + " WHERE d.observation_id=? AND d.dimension='FINDING' ORDER BY"
                            + " d.after_version DESC LIMIT 100",
                        observationId);
        // Read order stays frozen: subject, history, TIME subjects, recollections, history count.
        var timeSubject =
                db.queryForList(
                        "SELECT * FROM fams_inventory_review_subject WHERE observation_id=?"
                                + " AND dimension='TIME'",
                        observationId);
        var recollections =
                db.queryForList(
                        "SELECT d.*,CAST(l.counter_observation_id AS CHAR) AS"
                            + " counterObservationId,l.counter_observed_at FROM"
                            + " fams_inventory_finding_recollection l JOIN"
                            + " fams_inventory_review_decision d ON"
                            + " d.observation_id=l.observation_id AND"
                            + " d.dimension=l.dimension AND"
                            + " d.after_version=l.decision_version WHERE l.observation_id=?"
                            + " ORDER BY l.decision_version DESC LIMIT 100",
                        observationId);
        var historyTotal =
                java.util.Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM fams_inventory_review_decision WHERE"
                                        + " observation_id=? AND dimension='FINDING'",
                                Long.class,
                                observationId));
        LOG.debug("Finding detail requested for task {} observation {}.", taskId, observationId);
        return new FindingViews.FindingDetail(
                timeSubject.stream().map(row -> FindingViews.subject(json, row)).toList(),
                recollections.stream()
                        .map(row -> FindingViews.decisionRecollection(json, row))
                        .toList(),
                number(task, "version"),
                FindingViews.observation(json, observation),
                subject.isEmpty()
                        ? FindingViews.noSubject()
                        : FindingViews.subject(json, subject.getFirst()),
                history.stream().map(row -> FindingViews.decisionHistory(json, row)).toList(),
                historyTotal);
    }

    /**
     * Decides one finding subject. The operation key FINDING:{observationId} plus the request id makes
     * the call idempotent: a stored receipt with the same fingerprint replays, a different one is
     * rejected as an idempotency conflict. Closure never trusts free text - NON_ENTERPRISE closes
     * without a target, DUPLICATE_ASSET closes only against a card whose owner version still matches,
     * and LINK_INBOUND derives the status from the actual inbound acceptance: CLOSED for an accepted
     * item on an active card, PROCESSING while the acceptance is still open, OPEN when a refresh meets
     * a cancelled or rejected receipt. REFRESH_INBOUND is the only method allowed to re-decide a
     * PROCESSING subject and must point at the same link.
     *
     * <p>State impact: the FINDING subject row is updated to the new status and version, a decision row
     * and, for a targeted method, a finding link row are appended, the task version is bumped, the
     * decision is audited as INVENTORY_FINDING_DECIDED and stored as the review receipt.
     *
     * @param actor caller who must hold the ASSET_ADMIN role
     * @param taskId inventory task the finding belongs to
     * @param observationId finding observation to decide
     * @param version task version the caller read, compared against the locked task row
     * @param key client request id used for the review receipt
     * @param input method, subject version, reason, evidence and the optional asset, inbound receipt
     *     and owner version of the chosen target
     * @return receipt with the task id, observation id, the bumped task version, the new subject
     *     version and the resulting subject status
     * @throws InventoryReviewConflict INVENTORY_REVIEW_IDEMPOTENCY_CONFLICT when the request id was
     *     already used with a different payload, INVENTORY_REVIEW_TASK_CHANGED when the task version
     *     differs or the task is not IN_PROGRESS or WAITING_REVIEW, or
     *     INVENTORY_REVIEW_VERSION_CONFLICT when the subject version differs or the subject is already
     *     CLOSED or TRANSFERRED; all are audited and do not roll the transaction back
     * @throws InventoryFailure 403 INVENTORY_REVIEW_ADMIN_REQUIRED without the ASSET_ADMIN role, 404
     *     INVENTORY_REVIEW_TASK_NOT_FOUND or INVENTORY_FINDING_NOT_FOUND, 400
     *     INVENTORY_REVIEW_METHOD_INVALID for an unknown method, 400
     *     INVENTORY_FINDING_TARGET_INVALID when the target fields do not match the method, 409
     *     INVENTORY_FINDING_PREPARATION_REQUIRED when no FINDING subject exists yet, 409
     *     INVENTORY_FINDING_PROGRESS_REQUIRED when a PROCESSING subject is re-decided without
     *     REFRESH_INBOUND, 409 INVENTORY_REVIEW_ASSET_CHANGED for a moved duplicate card, 409
     *     INVENTORY_FINDING_LINK_REQUIRED when a refresh does not match the previous link, 409
     *     INVENTORY_FINDING_OWNER_CHANGED for a moved or refused inbound owner, or 409
     *     INVENTORY_FINDING_ALREADY_LINKED when the asset is already linked to another finding
     */
    @Transactional(
        isolation = Isolation.REPEATABLE_READ,
        rollbackFor = Exception.class,
        noRollbackFor = InventoryReviewConflict.class)
    public FindingViews.DecisionReceipt decide(
            Actor actor,
            long taskId,
            long observationId,
            long version,
            UUID key,
            InventoryFindingInput.Decision input) {
        var current = admin(actor);
        var task = task(taskId);
        observation(taskId, observationId);
        String operation = "FINDING:" + observationId;
        String hash = hash(version, input);
        var prior =
                db.queryForList(
                        "SELECT payload_sha256,CAST(response_json AS CHAR) AS response FROM"
                                + " fams_inventory_review_receipt WHERE task_id=? AND"
                                + " actor_account_id=? AND operation=? AND request_id=? FOR UPDATE",
                        taskId,
                        current.id(),
                        operation,
                        key.toString());
        if (!prior.isEmpty()) {
            if (!hash.equals(prior.getFirst().get("payload_sha256"))) {
                reject(actor, current, task, "INVENTORY_REVIEW_IDEMPOTENCY_CONFLICT", Map.of());
            }
            return replay(prior.getFirst(), FindingViews.DecisionReceipt.class);
        }
        var subjects =
                db.queryForList(
                        "SELECT * FROM fams_inventory_review_subject WHERE"
                                + " observation_id=? AND dimension='FINDING' FOR UPDATE",
                        observationId);
        if (subjects.isEmpty()) {
            throw new InventoryFailure(409, "INVENTORY_FINDING_PREPARATION_REQUIRED");
        }
        var subject = subjects.getFirst();
        if (number(task, "version") != version
                || !List.of("IN_PROGRESS", "WAITING_REVIEW").contains(task.get("task_status"))) {
            reject(actor, current, task, "INVENTORY_REVIEW_TASK_CHANGED", subject);
        }
        if (number(subject, "version") != input.subjectVersion()
                || List.of("CLOSED", "TRANSFERRED").contains(subject.get("subject_status"))) {
            reject(actor, current, task, "INVENTORY_REVIEW_VERSION_CONFLICT", subject);
        }
        var previous =
                db.queryForList(
                        "SELECT * FROM fams_inventory_finding_link WHERE observation_id=? ORDER BY"
                                + " decision_version DESC LIMIT 1 FOR UPDATE",
                        observationId);
        if ("PROCESSING".equals(subject.get("subject_status"))
                && !previous.isEmpty()
                && !"REFRESH_INBOUND".equals(input.method())) {
            throw new InventoryFailure(409, "INVENTORY_FINDING_PROGRESS_REQUIRED");
        }
        String status, kind = null;
        Long asset = input.assetId(),
                receipt = input.inboundReceiptId(),
                ownerVersion = input.ownerVersion();
        long assetVersion = 0;
        switch (input.method()) {
            case "NON_ENTERPRISE" -> {
                if (asset != null || receipt != null || ownerVersion != null) {
                    throw new InventoryFailure(400, "INVENTORY_FINDING_TARGET_INVALID");
                }
                status = "CLOSED";
            }
            case "DUPLICATE_ASSET" -> {
                if (asset == null || receipt != null || ownerVersion == null) {
                    throw new InventoryFailure(400, "INVENTORY_FINDING_TARGET_INVALID");
                }
                if (!assets.matchesVersion(asset, ownerVersion)
                        || cards.currentSnapshot(asset) == null) {
                    throw new InventoryFailure(409, "INVENTORY_REVIEW_ASSET_CHANGED");
                }
                assetVersion = ownerVersion;
                kind = "DUPLICATE_ASSET";
                status = "CLOSED";
            }
            case "LINK_INBOUND", "REFRESH_INBOUND" -> {
                if (asset == null || receipt == null || ownerVersion == null) {
                    throw new InventoryFailure(400, "INVENTORY_FINDING_TARGET_INVALID");
                }
                if ("REFRESH_INBOUND".equals(input.method())
                        && (previous.isEmpty()
                                || !"FORMAL_INBOUND".equals(previous.getFirst().get("link_kind"))
                                || number(previous.getFirst(), "asset_id") != asset
                                || number(previous.getFirst(), "inbound_receipt_id") != receipt)) {
                    throw new InventoryFailure(409, "INVENTORY_FINDING_LINK_REQUIRED");
                }
                var context = inbounds.context(actor, receipt, asset);
                if (context == null || context.version() != ownerVersion) {
                    throw new InventoryFailure(409, "INVENTORY_FINDING_OWNER_CHANGED");
                }
                if (!db.queryForList(
                                "SELECT observation_id FROM fams_inventory_finding_link WHERE"
                                        + " asset_id=? AND observation_id<>? LIMIT 1 FOR UPDATE",
                                asset,
                                observationId)
                        .isEmpty()) {
                    throw new InventoryFailure(409, "INVENTORY_FINDING_ALREADY_LINKED");
                }
                if (List.of("CANCELLED", "REJECTED").contains(context.status())) {
                    if (!"REFRESH_INBOUND".equals(input.method())) {
                        throw new InventoryFailure(409, "INVENTORY_FINDING_OWNER_CHANGED");
                    }
                    status = "OPEN";
                } else if (context.accepted()
                        && "ACCEPTED".equals(context.itemStatus())
                        && "ACTIVE".equals(context.cardStage())) {
                    status = "CLOSED";
                } else {
                    status = "PROCESSING";
                }
                assetVersion = context.assetVersion();
                kind = "FORMAL_INBOUND";
            }
            default -> throw new InventoryFailure(400, "INVENTORY_REVIEW_METHOD_INVALID");
        }
        long after = input.subjectVersion() + 1;
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        db.update(
                "UPDATE fams_inventory_review_subject SET subject_status=?,method=?,reason=?,"
                    + " evidence=?,reviewer_account_id=?,reviewer_username=?,reviewed_at=?,version=?"
                    + " WHERE observation_id=? AND dimension='FINDING'",
                status,
                input.method(),
                input.reason().strip(),
                input.evidence().strip(),
                current.id(),
                current.username(),
                now,
                after,
                observationId);
        db.update(
                "INSERT INTO fams_inventory_review_decision(task_id,observation_id,dimension,"
                        + " before_version,after_version,method,subject_status,reason,evidence,"
                        + " reviewed_asset_version,actor_account_id,actor_username,occurred_at)"
                        + " VALUES (?,?,'FINDING',?,?,?,?,?,?,?,?,?,?)",
                taskId,
                observationId,
                input.subjectVersion(),
                after,
                input.method(),
                status,
                input.reason().strip(),
                input.evidence().strip(),
                assetVersion,
                current.id(),
                current.username(),
                now);
        if (kind != null) {
            db.update(
                    "INSERT INTO fams_inventory_finding_link(observation_id,dimension,"
                        + " decision_version,task_id,link_kind,asset_id,inbound_receipt_id,owner_version,"
                        + " created_at) VALUES (?,'FINDING',?,?,?,?,?,?,?)",
                    observationId,
                    after,
                    taskId,
                    kind,
                    asset,
                    receipt,
                    ownerVersion,
                    now);
        }
        db.update(
                "UPDATE fams_inventory_task SET version=version+1,updated_at=? WHERE id=?",
                now,
                taskId);
        var result =
                new FindingViews.DecisionReceipt(
                        Long.toString(taskId),
                        Long.toString(observationId),
                        version + 1,
                        after,
                        status);
        audit(actor, current, task, "INVENTORY_FINDING_DECIDED", result);
        db.update(
                "INSERT INTO fams_inventory_review_receipt(task_id,actor_account_id,operation,"
                    + " request_id,payload_sha256,response_json,created_at) VALUES (?,?,?,?,?,?,?)",
                taskId,
                current.id(),
                operation,
                key.toString(),
                hash,
                json.writeValueAsString(result),
                now);
        LOG.info("Finding decision committed for observation {} by actor {}.", observationId, actor.id());
        return result;
    }

    /**
     * Lists the unassigned FINDING_REVIEW observations a TIME_REVIEW finding may be recollected
     * against. Only observations of the same task that arrived after the original one and whose
     * calibrated observed time is at or after the original reception are eligible, so a recollection can
     * never borrow an earlier fact. Nothing is written.
     *
     * @param actor caller who must hold the ASSET_ADMIN role
     * @param taskId inventory task both observations belong to
     * @param observationId original TIME_REVIEW observation the recollection starts from
     * @param page one-based page number
     * @param size maximum number of observations per page
     * @return candidate observation rows in ascending observation id order, the total count and the
     *     paging echo
     * @throws InventoryFailure 403 INVENTORY_REVIEW_ADMIN_REQUIRED without the ASSET_ADMIN role, 404
     *     INVENTORY_REVIEW_TASK_NOT_FOUND when the task is missing, DRAFT or DELETED, 404
     *     INVENTORY_FINDING_NOT_FOUND when the observation is not an unassigned finding of this task,
     *     or 409 INVENTORY_FINDING_TIME_REQUIRED when its outcome is not TIME_REVIEW
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public FindingViews.ObservationPage recollectionTargets(
            Actor actor, long taskId, long observationId, int page, int size) {
        admin(actor);
        task(taskId);
        var original = observation(taskId, observationId);
        if (!"TIME_REVIEW".equals(original.get("outcome"))) {
            throw new InventoryFailure(409, "INVENTORY_FINDING_TIME_REQUIRED");
        }
        String where =
                " FROM fams_inventory_observation WHERE task_id=? AND item_id IS NULL AND id>? AND"
                    + " outcome='FINDING_REVIEW' AND calibrated_observed_at IS NOT NULL AND"
                    + " calibrated_persisted_at IS NOT NULL AND calibrated_observed_at>=?";
        LOG.debug("Recollection targets requested for observation {} page {} size {}.", observationId, page, size);
        return new FindingViews.ObservationPage(
                db
                        .queryForList(
                                "SELECT *" + where + " ORDER BY id LIMIT ? OFFSET ? FOR SHARE",
                                taskId,
                                observationId,
                                original.get("received_at"),
                                size,
                                ((long) page - 1) * size)
                        .stream()
                        .map(row -> FindingViews.observation(json, row))
                        .toList(),
                java.util.Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*)" + where,
                                Long.class,
                                taskId,
                                observationId,
                                original.get("received_at"))),
                page,
                size);
    }

    /** Recollection resolves uncertainty without assigning a new timestamp to the original fact. */
    @Transactional(
        isolation = Isolation.REPEATABLE_READ,
        rollbackFor = Exception.class,
        noRollbackFor = InventoryReviewConflict.class)
    public FindingViews.RecollectionReceipt recollect(
            Actor actor,
            long taskId,
            long observationId,
            long version,
            UUID key,
            InventoryFindingInput.Recollection input) {
        var current = admin(actor);
        var task = task(taskId);
        var observation = observation(taskId, observationId);
        String operation = "FINDING_TIME:" + observationId;
        String fingerprint = hash(version, input);
        var prior =
                db.queryForList(
                        "SELECT payload_sha256,CAST(response_json AS CHAR) AS response FROM"
                                + " fams_inventory_review_receipt WHERE task_id=? AND"
                                + " actor_account_id=? AND operation=? AND request_id=? FOR UPDATE",
                        taskId,
                        current.id(),
                        operation,
                        key.toString());
        if (!prior.isEmpty()) {
            if (!fingerprint.equals(prior.getFirst().get("payload_sha256"))) {
                reject(actor, current, task, "INVENTORY_REVIEW_IDEMPOTENCY_CONFLICT", Map.of());
            }
            return replay(prior.getFirst(), FindingViews.RecollectionReceipt.class);
        }
        if (!"TIME_REVIEW".equals(observation.get("outcome"))) {
            throw new InventoryFailure(409, "INVENTORY_FINDING_TIME_REQUIRED");
        }
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_review_subject WHERE"
                                + " observation_id=? AND dimension='TIME' FOR UPDATE",
                        observationId);
        if (rows.isEmpty()) {
            throw new InventoryFailure(409, "INVENTORY_FINDING_PREPARATION_REQUIRED");
        }
        var subject = rows.getFirst();
        if (number(task, "version") != version
                || !List.of("IN_PROGRESS", "WAITING_REVIEW").contains(task.get("task_status"))) {
            reject(actor, current, task, "INVENTORY_REVIEW_TASK_CHANGED", subject);
        }
        if (number(subject, "version") != input.subjectVersion()
                || !List.of("OPEN", "PROCESSING").contains(subject.get("subject_status"))) {
            reject(actor, current, task, "INVENTORY_REVIEW_VERSION_CONFLICT", subject);
        }
        if (input.counterObservationId() == observationId) {
            throw new InventoryFailure(400, "INVENTORY_FINDING_RECOLLECTION_INVALID");
        }
        var counters =
                db.queryForList(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=? AND id=? AND"
                                + " item_id IS NULL AND outcome='FINDING_REVIEW' FOR SHARE",
                        taskId,
                        input.counterObservationId());
        if (counters.isEmpty()) {
            throw new InventoryFailure(409, "INVENTORY_FINDING_RECOLLECTION_INVALID");
        }
        var counter = counters.getFirst();
        if (counter.get("calibrated_observed_at") == null
                || counter.get("calibrated_persisted_at") == null) {
            throw new InventoryFailure(409, "INVENTORY_REVIEW_RECOLLECTION_REQUIRED");
        }
        var observed = instant(counter.get("calibrated_observed_at"));
        var persisted = instant(counter.get("calibrated_persisted_at"));
        var received = instant(counter.get("received_at"));
        if (observed.isBefore(instant(observation.get("received_at")))) {
            throw new InventoryFailure(409, "INVENTORY_FINDING_RECOLLECTION_INVALID");
        }
        if (observed.isBefore(instant(task.get("published_at")))
                || observed.isAfter(persisted.plusSeconds(2))
                || persisted.isAfter(received.plusSeconds(2))
                || !received.isBefore(persisted.plus(java.time.Duration.ofHours(72)))) {
            throw new InventoryFailure(409, "INVENTORY_REVIEW_RECOLLECTION_REQUIRED");
        }
        long after = input.subjectVersion() + 1;
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        db.update(
                "UPDATE fams_inventory_review_subject SET"
                    + " subject_status='CLOSED',method='RECOLLECTED',"
                    + " reason=?,evidence=?,reviewer_account_id=?,reviewer_username=?,reviewed_at=?,version=?"
                    + " WHERE observation_id=? AND dimension='TIME'",
                input.reason().strip(),
                input.evidence().strip(),
                current.id(),
                current.username(),
                now,
                after,
                observationId);
        db.update(
                "INSERT INTO"
                    + " fams_inventory_review_decision(task_id,observation_id,dimension,before_version,"
                    + "after_version,method,subject_status,reason,evidence,reviewed_asset_version,actor_account_id,"
                    + "actor_username,occurred_at)"
                    + " VALUES (?,?,'TIME',?,?,'RECOLLECTED','CLOSED',?,?,0,?,?,?)",
                taskId,
                observationId,
                input.subjectVersion(),
                after,
                input.reason().strip(),
                input.evidence().strip(),
                current.id(),
                current.username(),
                now);
        db.update(
                "INSERT INTO"
                    + " fams_inventory_finding_recollection(observation_id,dimension,decision_version,task_id,"
                    + "counter_observation_id,counter_observed_at,created_at)"
                    + " VALUES (?,'TIME',?,?,?,?,?)",
                observationId,
                after,
                taskId,
                input.counterObservationId(),
                observed,
                now);
        db.update(
                "UPDATE fams_inventory_task SET version=version+1,updated_at=? WHERE id=?",
                now,
                taskId);
        var result =
                new FindingViews.RecollectionReceipt(
                        Long.toString(taskId),
                        Long.toString(observationId),
                        version + 1,
                        after,
                        "CLOSED",
                        "RECOLLECTED",
                        Long.toString(input.counterObservationId()),
                        "UNVERIFIED");
        audit(actor, current, task, "INVENTORY_FINDING_TIME_RECOLLECTED", result);
        db.update(
                "INSERT INTO"
                    + " fams_inventory_review_receipt(task_id,actor_account_id,operation,request_id,payload_sha256,"
                    + "response_json,created_at)"
                    + " VALUES (?,?,?,?,?,?,?)",
                taskId,
                current.id(),
                operation,
                key.toString(),
                fingerprint,
                json.writeValueAsString(result),
                now);
        LOG.info("Observation recollection committed for observation {} actor {}.", observationId, actor.id());
        return result;
    }

    private Instant instant(Object value) {
        return value instanceof java.sql.Timestamp time
                ? time.toInstant()
                : ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC);
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

    private Map<String, Object> observation(long task, long id) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=? AND id=? AND"
                            + " item_id IS NULL AND outcome IN ('FINDING_REVIEW','TIME_REVIEW') FOR"
                            + " SHARE",
                        task,
                        id);
        if (rows.isEmpty()) {
            throw new InventoryFailure(404, "INVENTORY_FINDING_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private void reject(
            Actor actor,
            Current current,
            Map<String, Object> task,
            String code,
            Map<String, Object> subject) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("taskVersion", task.get("version").toString());
        fields.put(
                "result",
                subject.getOrDefault("subject_status", task.get("task_status")).toString());
        if (subject.get("reviewer_username") != null) {
            fields.put("processedBy", subject.get("reviewer_username").toString());
            // The legacy assembly stringified the wired value, so a reviewed_at without a
            // reviewer would fail the same way here.
            fields.put(
                    "processedAt",
                    FindingViews.subject(json, subject).reviewedAt().toString());
        }
        fields.put(
                "readOnlyUrl", InventoryReviewLinks.task(actor, number(task, "id")) + "/findings");
        audit(
                actor,
                current,
                task,
                "INVENTORY_FINDING_CONFLICT_REJECTED",
                Map.of("code", code, "fields", fields));
        throw new InventoryReviewConflict(code, fields);
    }

    private void audit(
            Actor actor, Current current, Map<String, Object> task, String action, Object detail) {
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        String body = json.writeValueAsString(detail);
        db.update(
                "INSERT INTO fams_inventory_task_event(task_id,actor_account_id,action,detail_json,"
                        + " occurred_at) VALUES (?,?,?,?,?)",
                task.get("id"),
                current.id(),
                action,
                body,
                now);
        db.update(
                "INSERT INTO fams_audit_event(request_id,actor_account_id,actor_role,action,"
                    + " resource_type,resource_id,before_version,after_version,occurred_at,detail_json)"
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
                now,
                body);
    }

    private String hash(long version, Object input) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (version + "\n" + json.writeValueAsString(input))
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    /**
     * Replay of one stored fams_inventory_review_receipt.response_json through the typed exit shape
     * (JAVA-17c blueprint: the pre-batch read used an untyped {@code Map} TypeReference, so old
     * stored rows are re-read through the record that now produces the response). The row lookup,
     * FOR UPDATE lock and payload comparison stay in the calling flow untouched.
     */
    private <T> T replay(Map<String, Object> prior, Class<T> type) {
        return json.readValue(prior.get("response").toString(), type);
    }
}
