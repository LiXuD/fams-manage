package com.fams.loss;

import com.fams.asset.AssetLossPort;
import com.fams.attachment.AttachmentEvidencePort;
import com.fams.attachment.AttachmentIntake;
import com.fams.attachment.AttachmentRegistry;
import com.fams.attachment.MalwareScanner;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;
import com.fams.repair.RepairFactoryService;
import com.fams.transfer.LossTransferPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Independent loss reporting. Approval authorizes verification; it never confirms a loss. */
@Service
@Profile("fams-product")
public class LossService {
    private static final Logger LOG = LoggerFactory.getLogger(LossService.class);
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final RequisitionActorContext actors;
    private final AssetLossPort assets;
    private final com.fams.repair.LossRepairPort repairs;
    private final com.fams.loan.LoanLossPort loans;
    private final com.fams.loan.LossReturnPort returns;
    private final LossTransferPort transfers;
    private final RepairFactoryService factoryRepairs;
    private final com.fams.repair.RepairService internalRepairs;
    private final ObjectProvider<AttachmentRegistry> attachments;
    private final ObjectProvider<AttachmentEvidencePort> evidence;

    /**
     * Wires the loss service to its database, serialization, time and cross-domain collaborators.
     *
     * @param db JDBC template for loss report, recovery, event, todo, lock and receipt tables
     * @param json mapper used to serialize snapshots and canonicalize idempotency fingerprints
     * @param clock time source for timestamps and review-date arithmetic
     * @param actors context revalidating the captured actor under pessimistic locking
     * @param assets asset-domain port validating and mutating asset lifecycle/exception state
     * @param repairs port creating the linked repair application for damaged recovered assets
     * @param loans loan port enforcing recovery preconditions and refreshing liability
     * @param returns return port restoring missing follow-ups and creating linked returns
     * @param transfers transfer port creating linked transfers for recovered assets
     * @param factoryRepairs factory repair service supplying loss sources, handoff and restoration
     * @param internalRepairs internal repair service supplying loss sources and handoff/resume hooks
     * @param attachments provider of the attachment registry used to register photo evidence
     * @param evidence provider of the attachment evidence port used to list and grant photo access
     */
    public LossService(
            JdbcTemplate db,
            ObjectMapper json,
            Clock clock,
            RequisitionActorContext actors,
            AssetLossPort assets,
            com.fams.repair.LossRepairPort repairs,
            com.fams.loan.LoanLossPort loans,
            com.fams.loan.LossReturnPort returns,
            LossTransferPort transfers,
            RepairFactoryService factoryRepairs,
            com.fams.repair.RepairService internalRepairs,
            ObjectProvider<AttachmentRegistry> attachments,
            ObjectProvider<AttachmentEvidencePort> evidence) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actors = actors;
        this.assets = assets;
        this.repairs = repairs;
        this.loans = loans;
        this.returns = returns;
        this.transfers = transfers;
        this.factoryRepairs = factoryRepairs;
        this.internalRepairs = internalRepairs;
        this.attachments = attachments;
        this.evidence = evidence;
    }

    /**
     * Pages the assets the caller is currently allowed to report as lost. Scope filtering and
     * eligibility rules live in the asset domain port; the actor is revalidated under lock first.
     *
     * @param actor captured actor whose identity and permissions are revalidated
     * @param page 1-based page number
     * @param size maximum number of rows per page
     * @return page of loss-eligible asset rows with the total count
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public AssetLossPort.EligiblePage eligible(Actor actor, int page, int size) {
        LOG.debug("Eligible loss assets requested page {} size {}.", page, size);
        return assets.eligible(actors.revalidateLocked(actor), page, size);
    }

    /**
     * Pages the loss reports visible to the caller: asset admins see all reports; other callers
     * see reports they created or that name their employee as (shared) responsible; department
     * asset managers also see their organization's reports and warehouse operators see reports
     * that already carry an approval snapshot.
     *
     * @param actor captured actor whose visibility scope is derived from roles and identity
     * @param page 1-based page number
     * @param size maximum number of rows per page
     * @return page of report views ordered by descending report id, with the total count
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public LossViews.Page<LossViews.LossReport> list(Actor actor, int page, int size) {
        var current = actors.revalidateLocked(actor);
        String where =
                " FROM fams_loss_report WHERE (? OR creator_account_id=? OR"
                    + " responsible_employee_id=? OR shared_responsible_employee_id=? OR (? AND"
                    + " using_organization_id=?) OR (? AND approval_snapshot_json IS NOT NULL))";
        Object[] scope = {
            current.has(Role.ASSET_ADMIN),
            current.id(),
            current.employeeId(),
            current.employeeId(),
            current.has(Role.DEPARTMENT_ASSET_MANAGER),
            current.organizationId(),
            current.has(Role.WAREHOUSE_OPERATOR)
        };
        var args = new java.util.ArrayList<Object>(java.util.Arrays.asList(scope));
        args.add(size);
        args.add(((long) page - 1) * size);
        LOG.debug("Loss report page requested by actor {} page {} size {}.", actor.id(), page, size);
        return new LossViews.Page<>(
                db
                        .queryForList(
                                "SELECT *" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                                args.toArray())
                        .stream()
                        .map(row -> LossViews.lossReport(json, row))
                        .toList(),
                java.util.Objects.requireNonNull(
                        db.queryForObject("SELECT COUNT(*)" + where, Long.class, scope)),
                page,
                size);
    }

    /**
     * Loads the full detail of one loss report: header, active business-lock version, photo
     * evidence list, the 100 most recent events, the confirmation snapshot, recovery requests,
     * the recovery asset view when recovery is still open, and the caller-specific action flags
     * deciding which operations the UI may offer.
     *
     * @param actor captured actor used for visibility and per-action role checks
     * @param id loss report id to load
     * @return assembled detail view for the report
     * @throws LossFailure with code {@code LOSS_NOT_FOUND} if the report does not exist, or
     *         {@code LOSS_NOT_VISIBLE} if the caller is outside the report's visibility scope
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public LossViews.LossDetail get(Actor actor, long id) {
        var current = actors.revalidateLocked(actor);
        var row = header(id);
        visible(current, row);
        var base = LossViews.lossReport(json, row);
        var locks =
                db.queryForList(
                        "SELECT lock_version FROM fams_asset_business_lock WHERE asset_id=? AND"
                            + " business_type='LOST_REPORT' AND business_id=? AND released_at IS"
                            + " NULL",
                        row.get("asset_id"),
                        id);
        Long lockVersion =
                locks.isEmpty()
                        ? null
                        : ((Number) locks.getFirst().get("lock_version")).longValue();
        var photos = evidence.getIfAvailable();
        var photoList =
                photos == null ? List.<Photo>of() : photoProjection(photos.list("LOST_REPORT", id));
        var history =
                db.queryForList(
                                "SELECT * FROM fams_loss_event WHERE report_id=? ORDER BY id DESC"
                                        + " LIMIT 100",
                                id)
                        .stream()
                        .map(LossViews::lossEvent)
                        .toList();
        long historyTotal =
                java.util.Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM fams_loss_event WHERE report_id=?",
                                Long.class,
                                id));
        boolean pending = "PENDING_ADMIN_DECISION".equals(row.get("report_status"));
        Long factoryProcessor = factoryProcessor(row);
        Long returnProcessor = returnProcessor(row);
        Long internal = internalRepairProcessor(row);
        var confirmation =
                row.get("confirmation_snapshot_json") == null
                        ? null
                        : json.readTree(row.get("confirmation_snapshot_json").toString());
        boolean recoveryOpen =
                row.get("recovered_at") == null
                        && List.of("SEARCHING", "CONFIRMED_LOST")
                                .contains(row.get("report_status"));
        var recoveryAsset = recoveryOpen ? assets.recoveryView(number(row, "asset_id")) : null;
        String currentLifecycle = recoveryAsset == null ? "" : recoveryAsset.lifecycle();
        var recoveries =
                db.queryForList(
                                "SELECT * FROM fams_loss_recovery WHERE report_id=?"
                                        + " ORDER BY (active_guard IS NOT NULL) DESC,id DESC LIMIT 20",
                                id)
                        .stream()
                        .map(item -> recoveryView(current, item))
                        .toList();
        long recoveriesTotal =
                java.util.Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM fams_loss_recovery WHERE report_id=?",
                                Long.class,
                                id));
        var original = json.readTree(row.get("source_snapshot_json").toString());
        var actions =
                new LossViews.ReportActions(
                        "SEARCHING".equals(row.get("report_status"))
                                && factoryProcessor == null && internal == null
                                && current.has(Role.WAREHOUSE_OPERATOR)
                                && java.util.Objects.equals(
                                        current.id(), row.get("processor_account_id")),
                        "CONFIRMED_LOST".equals(row.get("report_status"))
                                && recoveryOpen
                                && applicant(current)
                                && recoveryAsset.eligible()
                                && !recoveryAsset.locked(),
                        "SEARCHING".equals(row.get("report_status"))
                                && current.has(Role.WAREHOUSE_OPERATOR)
                                && java.util.Objects.equals(
                                        current.id(), row.get("processor_account_id")),
                        "CONFIRMED_LOST".equals(row.get("report_status"))
                                && row.get("recovered_at") == null
                                && !List.of("PENDING_SCRAP", "SCRAPPED").contains(currentLifecycle)
                                && current.has(Role.WAREHOUSE_OPERATOR)
                                && java.util.Objects.equals(
                                        current.id(), row.get("processor_account_id")),
                        pending && current.has(Role.ASSET_ADMIN),
                        pending
                                && (current.has(Role.ASSET_ADMIN)
                                        || current.id() == number(row, "creator_account_id")),
                        pending
                                && photos != null
                                && current.id() == number(row, "creator_account_id"),
                        "WAITING_WAREHOUSE_CLAIM".equals(row.get("report_status"))
                                && current.has(Role.WAREHOUSE_OPERATOR)
                                && (factoryProcessor == null || factoryProcessor == current.id())
                                && (returnProcessor == null || returnProcessor == current.id())
                                && (internal == null || internal == current.id()),
                        "VERIFYING".equals(row.get("report_status"))
                                && current.has(Role.WAREHOUSE_OPERATOR)
                                && java.util.Objects.equals(
                                        current.id(), row.get("processor_account_id")),
                        factoryProcessor != null && factoryProcessor == current.id()
                                && current.has(Role.WAREHOUSE_OPERATOR)
                                && "SEARCHING".equals(row.get("report_status")),
                        factoryProcessor != null && recoveryOpen && applicant(current)
                                && "CONFIRMED_LOST".equals(row.get("report_status"))
                                && recoveryAsset.factoryScrapEligible()
                                && !recoveryAsset.locked(),
                        internal != null && internal == current.id()
                                && current.has(Role.WAREHOUSE_OPERATOR)
                                && "SEARCHING".equals(row.get("report_status")),
                        internal != null && recoveryOpen && applicant(current)
                                && "CONFIRMED_LOST".equals(row.get("report_status"))
                                && "UNDER_REPAIR".equals(currentLifecycle)
                                && !recoveryAsset.locked(),
                        recoveryAsset != null && currentLifecycle.equals(original.path("lifecycle").asText())
                                && "LOST".equals(recoveryAsset.exceptionStatus())
                                && (recoveryAsset.eligible() || recoveryAsset.factoryScrapEligible())
                                && !recoveryAsset.locked()
                                && LossScrapSourcePort.canSubmit(current, row)
                                && LossScrapSourcePort.confirmedScrapSource(row, original));
        LOG.debug("Loss detail requested for report {}.", id);
        return LossViews.lossDetail(
                base,
                lockVersion,
                photos != null,
                photoList,
                history,
                historyTotal,
                confirmation,
                recoveryAsset,
                recoveries,
                recoveriesTotal,
                actions);
    }

    /**
     * Submits a new loss report for an ordinary asset: replays a stored receipt when the same
     * idempotency key and payload fingerprint were already processed, otherwise validates the
     * last-seen timestamp, freezes the asset submission snapshot, opens the report in
     * PENDING_ADMIN_DECISION, acquires the asset business lock and files the approval todo.
     *
     * @param actor captured actor revalidated as the report creator
     * @param key idempotency key identifying this submission attempt
     * @param input validated submission payload (asset reference, last-seen facts)
     * @return the created report view, or the remembered view of an identical earlier submission
     * @throws LossFailure with code {@code LOSS_TIME_INVALID} if the last-seen timestamp has
     *         sub-millisecond precision or lies in the future, or with the code raised by the
     *         asset port when the asset cannot be submitted for loss
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReport submit(Actor actor, UUID key, LossInput.Submission input) {
        var current = actors.revalidateLocked(actor);
        String hash = LossPayloads.fingerprint(json, "SUBMIT", 0, input);
        var prior = replay(current, "SUBMIT", key, hash, LossViews.LossReport.class);
        if (prior != null) {
            return prior;
        }
        validTime(input.lastSeenAt());
        String source = assets.submission(current, input.assetId(), input.assetVersion());
        var row = openReport(actor, current, input, source, "LOSS_REPORTED");
        LOG.info("Loss report submitted by actor {}.", actor.id());
        return remember(current, "SUBMIT", key, hash, row);
    }

    /**
     * Submits a loss report on behalf of an inventory missing reason. Runs inside the inventory
     * caller's transaction and requires the asset-admin role; the inventory task, item, reason and
     * recheck generation are embedded into the stored source snapshot as {@code inventorySource}.
     * No idempotency receipt is written because the enclosing import flow owns retry semantics.
     *
     * @param actor captured actor, must hold the asset-admin role
     * @param inventory source reference identifying the counted task item and reason
     * @param input validated submission payload for the loss report
     * @return id of the created report as a string
     * @throws LossFailure with code {@code LOSS_ROLE_REQUIRED} if the actor is not an asset admin,
     *         {@code LOSS_TIME_INVALID} for a malformed last-seen timestamp, or the code raised by
     *         the asset port when the asset cannot be submitted for loss
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String submitInventory(Actor actor, InventoryLossPort.Source inventory,
            LossInput.Submission input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.ASSET_ADMIN);
        validTime(input.lastSeenAt());
        var source = (tools.jackson.databind.node.ObjectNode) json.readTree(
                assets.submission(current, input.assetId(), input.assetVersion()));
        source.putObject("inventorySource")
                .put("taskId", Long.toString(inventory.taskId()))
                .put("itemId", Long.toString(inventory.itemId()))
                .put("reasonId", Long.toString(inventory.reasonId()))
                .put("recheckGeneration", inventory.recheckGeneration());
        var row = openReport(actor, current, input, json.writeValueAsString(source),
                "LOSS_REPORTED_FROM_INVENTORY");
        return row.get("id").toString();
    }

    private Map<String, Object> openReport(Actor actor, Current current, LossInput.Submission input,
            String source, String event) {
        long id = insertReport(current, input, source);
        db.update(
                "INSERT INTO"
                    + " fams_asset_business_lock(asset_id,business_type,business_id,business_item_id,acquired_at)"
                    + " VALUES (?,'LOST_REPORT',?,?,?)",
                input.assetId(),
                id,
                id,
                now());
        todo(id, "APPROVAL", "ASSET_ADMIN", null);
        var row = header(id);
        audit(actor, current, row, event, 0, input);
        return row;
    }

    /**
     * Reports an asset lost from a factory repair round. Requires the warehouse-operator role,
     * resolves the repair source under the caller-supplied optimistic versions, freezes the asset
     * snapshot with the factory origin, opens the report and hands the repair round off to loss so
     * the repair side releases its custody.
     *
     * @param actor captured actor, must hold the warehouse-operator role
     * @param repairId factory repair id the asset was dispatched under
     * @param roundId repair round id within that repair
     * @param repairVersion expected repair version for optimistic concurrency on the repair row
     * @param key idempotency key identifying this submission attempt
     * @param input factory submission payload (round/asset/ownership/lock versions and facts)
     * @return the created report view, or the remembered view of an identical earlier submission
     * @throws LossFailure with code {@code LOSS_ROLE_REQUIRED} if the actor is not a warehouse
     *         operator, {@code LOSS_FACTORY_TIME_INVALID} if the last-seen timestamp predates the
     *         factory dispatch, {@code LOSS_TIME_INVALID} for a malformed timestamp, or the code
     *         raised by the repair or asset ports on version/state conflicts
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReport submitFactory(Actor actor, long repairId, long roundId, long repairVersion,
            UUID key, LossInput.FactorySubmission input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String operation = "FACTORY_SUBMIT:" + repairId + ":" + roundId;
        String hash = LossPayloads.fingerprint(json, operation, repairVersion, input);
        var prior = replay(current, operation, key, hash, LossViews.LossReport.class);
        if (prior != null) {
            return prior;
        }
        var source = factoryRepairs.lossSource(actor, repairId, roundId, repairVersion,
                input.roundVersion(), input.assetVersion(), input.ownershipVersion(), input.lockVersion());
        validTime(input.lastSeenAt());
        if (input.lastSeenAt().isBefore(source.dispatchedAt())) {
            throw new LossFailure(400, "LOSS_FACTORY_TIME_INVALID");
        }
        var snapshot = assets.factorySubmission(source.assetId(), source.assetVersion());
        var stored = new AssetLossPort.StoredSource(
                snapshot,
                null,
                Map.of("repairId", Long.toString(repairId), "roundId", Long.toString(roundId),
                        "processorAccountId", Long.toString(source.processorAccountId())),
                null,
                null);
        var submission = new LossInput.Submission(source.assetId(), source.assetVersion(),
                input.lastSeenAt(), input.lastLocation(), input.circumstances());
        long id = insertReport(current, submission, json.writeValueAsString(stored));
        factoryRepairs.handoffToLoss(actor, source, id, input.lockVersion());
        todo(id, "APPROVAL", "ASSET_ADMIN", null);
        audit(actor, current, header(id), "LOSS_REPORTED_FROM_FACTORY", 0, input);
        LOG.info("Loss reported from factory repair {} by actor {}.", repairId, actor.id());
        return remember(current, operation, key, hash, header(id));
    }

    /**
     * Reports an asset lost from an internal repair order. Requires the warehouse-operator role,
     * resolves the repair source under the caller-supplied optimistic versions, freezes the asset
     * snapshot together with the repair handling context and hands the repair off to loss.
     *
     * @param actor captured actor, must hold the warehouse-operator role
     * @param repairId internal repair id the asset was accepted under
     * @param repairVersion expected repair version for optimistic concurrency on the repair row
     * @param key idempotency key identifying this submission attempt
     * @param input internal-repair submission payload (asset/ownership/lock versions and facts)
     * @return the created report view, or the remembered view of an identical earlier submission
     * @throws LossFailure with code {@code LOSS_ROLE_REQUIRED} if the actor is not a warehouse
     *         operator, {@code LOSS_INTERNAL_REPAIR_TIME_INVALID} if the last-seen timestamp
     *         predates repair acceptance, {@code LOSS_TIME_INVALID} for a malformed timestamp, or
     *         the code raised by the repair or asset ports on version/state conflicts
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReport submitInternalRepair(Actor actor, long repairId, long repairVersion, UUID key,
            LossInput.InternalRepairSubmission input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String operation = "INTERNAL_REPAIR_SUBMIT:" + repairId;
        String hash = LossPayloads.fingerprint(json, operation, repairVersion, input);
        var prior = replay(current, operation, key, hash, LossViews.LossReport.class);
        if (prior != null) {
            return prior;
        }
        var source = internalRepairs.lossSource(actor, repairId, repairVersion,
                input.assetVersion(), input.ownershipVersion(), input.lockVersion());
        validTime(input.lastSeenAt());
        if (input.lastSeenAt().isBefore(source.acceptedAt())) {
            throw new LossFailure(400, "LOSS_INTERNAL_REPAIR_TIME_INVALID");
        }
        var snapshot = assets.internalRepairSubmission(source.assetId(), input.assetVersion());
        var stored = new AssetLossPort.StoredSource(
                snapshot,
                null,
                null,
                Map.of("repairId", Long.toString(repairId),
                        "processorAccountId", Long.toString(source.processorAccountId()),
                        "handling", json.readTree(source.contextJson())),
                null);
        var submission = new LossInput.Submission(source.assetId(), input.assetVersion(),
                input.lastSeenAt(), input.lastLocation(), input.circumstances());
        long id = insertReport(current, submission, json.writeValueAsString(stored));
        internalRepairs.handoffToLoss(actor, current, source, id, input.lockVersion());
        todo(id, "APPROVAL", "ASSET_ADMIN", null);
        audit(actor, current, header(id), "LOSS_REPORTED_FROM_INTERNAL_REPAIR", 0, input);
        LOG.info("Loss reported from internal repair {} by actor {}.", repairId, actor.id());
        return remember(current, operation, key, hash, header(id));
    }

    long submitReturn(Actor actor, com.fams.business.ReturnLossOpeningPort.Opening opening) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        if (current.id() != opening.processorAccountId()) {
            throw new LossFailure(403, "LOSS_RETURN_PROCESSOR_REQUIRED");
        }
        var facts = opening.facts();
        validTime(facts.lastSeenAt());
        var snapshot = assets.returnSubmission(opening.assetId(), opening.assetVersion());
        var stored = new AssetLossPort.StoredSource(snapshot,
                Map.of("returnId", Long.toString(opening.returnId()),
                        "followupId", Long.toString(opening.followupId()),
                        "processorAccountId", Long.toString(opening.processorAccountId())),
                null, null, null);
        var input = new LossInput.Submission(opening.assetId(), opening.assetVersion(), facts.lastSeenAt(),
                facts.lastLocation(), facts.circumstances());
        long id = insertReport(current, input, json.writeValueAsString(stored));
        todo(id, "APPROVAL", "ASSET_ADMIN", null);
        audit(actor, current, header(id), "LOSS_REPORTED_FROM_RETURN", 0, input);
        return id;
    }

    private long insertReport(Current current, LossInput.Submission input, String source) {
        var snapshot = json.readTree(source);
        db.update(
                "INSERT INTO"
                    + " fams_loss_report(asset_id,creator_account_id,using_organization_id,responsible_employee_id,"
                    + "shared_responsible_employee_id,asset_version,source_snapshot_json,last_seen_at,last_location,"
                    + "circumstances,created_at,updated_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                input.assetId(),
                current.id(),
                reference(snapshot, "usingOrganizationId"),
                reference(snapshot, "responsibleEmployeeId"),
                reference(snapshot, "departmentResponsibleEmployeeId"),
                input.assetVersion(),
                source,
                input.lastSeenAt(),
                input.lastLocation().strip(),
                input.circumstances().strip(),
                now(),
                now());
        return java.util.Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
    }

    private Long factoryProcessor(Map<String, Object> row) {
        var source = json.readTree(row.get("source_snapshot_json").toString()).path("factorySource");
        return source.isMissingNode() ? null : Long.valueOf(source.path("processorAccountId").asText());
    }

    private Long internalRepairProcessor(Map<String, Object> row) {
        var source = json.readTree(row.get("source_snapshot_json").toString()).path("internalRepairSource");
        return source.isMissingNode() ? null : Long.valueOf(source.path("processorAccountId").asText());
    }

    private Long retainedRepairProcessor(Map<String, Object> row) {
        Long internal = internalRepairProcessor(row);
        return internal == null ? factoryProcessor(row) : internal;
    }

    private String retainedRepairLifecycle(Map<String, Object> row) {
        if (internalRepairProcessor(row) != null) {
            return "UNDER_REPAIR";
        }
        return factoryProcessor(row) == null ? null : "FACTORY_REPAIR";
    }

    private Long returnProcessor(Map<String, Object> row) {
        var source = json.readTree(row.get("source_snapshot_json").toString()).path("returnSource");
        return source.isMissingNode() ? null : Long.valueOf(source.path("processorAccountId").asText());
    }

    private void releaseInitialReport(Actor actor, Map<String, Object> row, String reason) {
        var origin = json.readTree(row.get("source_snapshot_json").toString());
        var returned = origin.path("returnSource");
        if (!returned.isMissingNode()) {
            returns.restoreMissing(Long.parseLong(returned.path("followupId").asText()), number(row, "asset_id"),
                    number(row, "id"));
            return;
        }
        var internal = origin.path("internalRepairSource");
        if (!internal.isMissingNode()) {
            internalRepairs.resumeFromLoss(actor, actors.revalidateLocked(actor),
                    Long.parseLong(internal.path("repairId").asText()), number(row, "asset_id"),
                    Long.parseLong(internal.path("processorAccountId").asText()),
                    number(row, "id"), number(row, "id"), null);
            return;
        }
        var source = origin.path("factorySource");
        if (source.isMissingNode()) {
            release(row, reason);
            return;
        }
        factoryRepairs.restoreFromLoss(actor, Long.parseLong(source.path("repairId").asText()),
                Long.parseLong(source.path("roundId").asText()), number(row, "asset_id"), number(row, "id"));
    }

    /**
     * Records the asset-admin decision on a pending report. Approval freezes the report and photo
     * evidence into the approval snapshot, requires the asset to still be in the submitted loss
     * state and at least one uploaded photo, then moves the report to WAITING_WAREHOUSE_CLAIM and
     * files the claim todo. Rejection restores the originating source (return follow-up, internal
     * repair, factory round or plain asset lock) and marks the report REJECTED.
     *
     * @param actor captured actor, must hold the asset-admin role
     * @param id loss report id
     * @param version expected report version (If-Match)
     * @param key idempotency key identifying this decision attempt
     * @param input decision payload with result APPROVE or REJECT and a reason
     * @return the updated report view, or the remembered view of an identical earlier decision
     * @throws LossFailure with code {@code LOSS_ROLE_REQUIRED}, {@code LOSS_NOT_FOUND},
     *         {@code LOSS_REPORT_CHANGED} on version/status drift, {@code LOSS_LOCK_CHANGED} when
     *         the business lock moved, {@code LOSS_DECISION_INVALID} for a result other than
     *         APPROVE/REJECT, or {@code LOSS_PHOTO_REQUIRED} when approving without photo evidence
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReport decide(
            Actor actor, long id, long version, UUID key, LossInput.Decision input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.ASSET_ADMIN);
        String operation = "DECIDE:" + id,
                hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash, LossViews.LossReport.class);
        if (prior != null) {
            return prior;
        }
        var row = header(id);
        guard(row, version, "PENDING_ADMIN_DECISION");
        lock(row, null);
        if (!List.of("APPROVE", "REJECT").contains(input.result())) {
            throw new LossFailure(400, "LOSS_DECISION_INVALID");
        }
        boolean approve = "APPROVE".equals(input.result());
        Long repairProcessor = retainedRepairProcessor(row);
        var photos = evidence.getIfAvailable();
        var frozen = new LinkedHashMap<String, Object>();
        frozen.put("report", LossViews.lossReport(json, row));
        frozen.put("reason", input.reason());
        frozen.put("photos", photos == null ? List.of() : photoProjection(photos.list("LOST_REPORT", id)));
        if (approve) {
            assets.requireSubmitted(number(row, "asset_id"), number(row, "asset_version"),
                    retainedRepairLifecycle(row));
            if (photos == null || photos.list("LOST_REPORT", id).isEmpty()) {
                throw new LossFailure(409, "LOSS_PHOTO_REQUIRED");
            }
        }
        db.update(
                "UPDATE fams_loss_report SET"
                        + " report_status=?,approval_snapshot_json=?,version=version+1,updated_at=?"
                        + " WHERE id=?",
                approve ? "WAITING_WAREHOUSE_CLAIM" : "REJECTED",
                approve ? json.writeValueAsString(frozen) : null,
                now(),
                id);
        closeTodos(id);
        if (approve) {
            todo(id, "CLAIM", "WAREHOUSE_OPERATOR", repairProcessor == null ? returnProcessor(row) : repairProcessor);
        } else {
            releaseInitialReport(actor, row, "LOSS_REJECTED");
        }
        audit(actor, current, row, "LOSS_DECIDED", version + 1, input);
        LOG.info("Loss report {} decision recorded by actor {}.", id, actor.id());
        return remember(current, operation, key, hash, header(id));
    }

    /**
     * Cancels a report that is still awaiting the admin decision. Only the creator or an asset
     * admin may cancel; the originating source is restored exactly as on rejection, todos are
     * closed and the report is marked CANCELLED.
     *
     * @param actor captured actor, must be the report creator or hold the asset-admin role
     * @param id loss report id
     * @param version expected report version (If-Match)
     * @param key idempotency key identifying this cancellation attempt
     * @param input payload carrying the cancellation reason
     * @return the updated report view, or the remembered view of an identical earlier cancellation
     * @throws LossFailure with code {@code LOSS_CREATOR_REQUIRED} when the caller is neither the
     *         creator nor an asset admin, {@code LOSS_NOT_FOUND}, {@code LOSS_REPORT_CHANGED} on
     *         version/status drift, or {@code LOSS_LOCK_CHANGED} when the business lock moved
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReport cancel(
            Actor actor, long id, long version, UUID key, LossInput.Reason input) {
        var current = actors.revalidateLocked(actor);
        var row = header(id);
        if (current.id() != number(row, "creator_account_id") && !current.has(Role.ASSET_ADMIN)) {
            throw new LossFailure(403, "LOSS_CREATOR_REQUIRED");
        }
        String operation = "CANCEL:" + id,
                hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash, LossViews.LossReport.class);
        if (prior != null) {
            return prior;
        }
        guard(row, version, "PENDING_ADMIN_DECISION");
        lock(row, null);
        releaseInitialReport(actor, row, "LOSS_CANCELLED");
        closeTodos(id);
        db.update(
                "UPDATE fams_loss_report SET"
                        + " report_status='CANCELLED',version=version+1,updated_at=? WHERE id=?",
                now(),
                id);
        audit(actor, current, row, "LOSS_CANCELLED", version + 1, input);
        LOG.info("Loss report {} cancelled by actor {}.", id, actor.id());
        return remember(current, operation, key, hash, header(id));
    }

    /**
     * Claims an approved report for warehouse verification. When the report originated from a
     * factory round, an internal repair or an asset return, only that source's processor may
     * claim it. The report moves to VERIFYING, the caller becomes the processor, the ownership
     * version advances and a verify todo is filed.
     *
     * @param actor captured actor, must hold the warehouse-operator role
     * @param id loss report id
     * @param version expected report version (If-Match)
     * @param key idempotency key identifying this claim attempt
     * @return the updated report view, or the remembered view of an identical earlier claim
     * @throws LossFailure with code {@code LOSS_ROLE_REQUIRED}, {@code LOSS_NOT_FOUND},
     *         {@code LOSS_REPORT_CHANGED} on version/status drift, {@code LOSS_LOCK_CHANGED} when
     *         the business lock moved, {@code LOSS_FACTORY_PROCESSOR_REQUIRED},
     *         {@code LOSS_INTERNAL_REPAIR_PROCESSOR_REQUIRED} or
     *         {@code LOSS_RETURN_PROCESSOR_REQUIRED} when a different processor claims a sourced
     *         report
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReport claim(Actor actor, long id, long version, UUID key) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String operation = "CLAIM:" + id,
                hash = LossPayloads.fingerprint(json, operation, version, Map.of());
        var prior = replay(current, operation, key, hash, LossViews.LossReport.class);
        if (prior != null) {
            return prior;
        }
        var row = header(id);
        guard(row, version, "WAITING_WAREHOUSE_CLAIM");
        lock(row, null);
        Long factoryProcessor = factoryProcessor(row);
        if (factoryProcessor != null && factoryProcessor != current.id()) {
            throw new LossFailure(403, "LOSS_FACTORY_PROCESSOR_REQUIRED");
        }
        Long internal = internalRepairProcessor(row);
        if (internal != null && internal != current.id()) {
            throw new LossFailure(403, "LOSS_INTERNAL_REPAIR_PROCESSOR_REQUIRED");
        }
        Long returnProcessor = returnProcessor(row);
        if (returnProcessor != null && returnProcessor != current.id()) {
            throw new LossFailure(403, "LOSS_RETURN_PROCESSOR_REQUIRED");
        }
        assets.requireSubmitted(number(row, "asset_id"), number(row, "asset_version"), retainedRepairLifecycle(row));
        db.update(
                "UPDATE fams_loss_report SET"
                    + " report_status='VERIFYING',processor_account_id=?,"
                    + "ownership_version=ownership_version+1,version=version+1,updated_at=?"
                    + " WHERE id=?",
                current.id(),
                now(),
                id);
        closeTodos(id);
        todo(id, "VERIFY", "WAREHOUSE_OPERATOR", current.id());
        audit(
                actor,
                current,
                row,
                "LOSS_CLAIMED",
                version + 1,
                Map.of("processorAccountId", Long.toString(current.id())));
        LOG.info("Loss report {} claimed by actor {}.", id, actor.id());
        return remember(current, operation, key, hash, header(id));
    }

    /**
     * Starts the search phase for a claimed report: the assigned processor moves the asset into
     * the SEARCHING exception state and schedules the next review date, which must not lie in the
     * past of the active review timezone. The report moves to SEARCHING and a search todo is filed.
     *
     * @param actor captured actor, must hold the warehouse-operator role
     * @param id loss report id
     * @param version expected report version (If-Match)
     * @param key idempotency key identifying this search attempt
     * @param input search payload (ownership/asset/lock versions, searched-at and review date)
     * @return the updated report view, or the remembered view of an identical earlier request
     * @throws LossFailure with code {@code LOSS_ROLE_REQUIRED}, {@code LOSS_NOT_FOUND},
     *         {@code LOSS_REPORT_CHANGED} on version/status drift,
     *         {@code LOSS_PROCESSOR_OR_ASSET_CHANGED} when the processor or the tracked versions
     *         drifted, {@code LOSS_LOCK_CHANGED} when the business lock moved,
     *         {@code LOSS_TIME_INVALID} for a malformed timestamp, or
     *         {@code LOSS_SEARCH_DATE_INVALID} when the searched-at predates the last-seen instant
     *         or the review date is in the past
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReport search(
            Actor actor, long id, long version, UUID key, LossInput.Search input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String operation = "SEARCH:" + id,
                hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash, LossViews.LossReport.class);
        if (prior != null) {
            return prior;
        }
        var row = header(id);
        guard(row, version, "VERIFYING");
        if (!java.util.Objects.equals(current.id(), row.get("processor_account_id"))
                || input.ownershipVersion() != number(row, "ownership_version")
                || input.assetVersion() != number(row, "asset_version")) {
            throw new LossFailure(409, "LOSS_PROCESSOR_OR_ASSET_CHANGED");
        }
        lock(row, input.lockVersion());
        validTime(input.searchedAt());
        ZoneId reviewZone = activeReviewZone();
        if (input.searchedAt().isBefore(instant(row.get("last_seen_at")))
                || input.nextReviewDate().isBefore(LocalDate.now(clock.withZone(reviewZone)))) {
            throw new LossFailure(400, "LOSS_SEARCH_DATE_INVALID");
        }
        long assetVersion =
                assets.searching(
                        number(row, "asset_id"), input.assetVersion(), id, current.id(), now(),
                        retainedRepairLifecycle(row));
        db.update(
                "UPDATE fams_loss_report SET"
                    + " report_status='SEARCHING',asset_version=?,next_review_date=?,version=version+1,updated_at=?"
                    + " WHERE id=?",
                assetVersion,
                input.nextReviewDate(),
                now(),
                id);
        closeTodos(id);
        todo(id, "SEARCH", "WAREHOUSE_OPERATOR", current.id());
        audit(actor, current, row, "LOSS_SEARCH_STARTED", version + 1,
                reviewAudit(input, input.nextReviewDate(), reviewZone));
        LOG.info("Loss search started for report {} by actor {}.", id, actor.id());
        return remember(current, operation, key, hash, header(id));
    }

    /**
     * Pages the confirmed-lost reports that still require periodic follow-up: the report is in
     * CONFIRMED_LOST status, the asset exception status is LOST and the asset is not scrapped or
     * pending scrap. Visibility scoping matches {@link #list}, with warehouse operators always
     * included; rows are ordered by the next review date so overdue reviews surface first.
     *
     * @param actor captured actor whose visibility scope filters the rows
     * @param page 1-based page number
     * @param size maximum number of rows per page
     * @return page of report views ordered by next review date, with the total count
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public LossViews.Page<LossViews.LossReport> followUps(Actor actor, int page, int size) {
        var current = actors.revalidateLocked(actor);
        String where =
                " FROM fams_loss_report r JOIN fams_asset a ON a.id=r.asset_id"
                        + " WHERE r.report_status='CONFIRMED_LOST' AND a.exception_status='LOST'"
                        + " AND a.lifecycle_status NOT IN ('SCRAPPED','PENDING_SCRAP') AND (? OR r.creator_account_id=?"
                        + " OR r.responsible_employee_id=? OR r.shared_responsible_employee_id=?"
                        + " OR (? AND r.using_organization_id=?) OR ?)";
        Object[] scope = {
            current.has(Role.ASSET_ADMIN),
            current.id(),
            current.employeeId(),
            current.employeeId(),
            current.has(Role.DEPARTMENT_ASSET_MANAGER),
            current.organizationId(),
            current.has(Role.WAREHOUSE_OPERATOR)
        };
        var args = new java.util.ArrayList<Object>(java.util.Arrays.asList(scope));
        args.add(size);
        args.add(((long) page - 1) * size);
        LOG.debug("Loss follow-up page requested by actor {} page {} size {}.", actor.id(), page, size);
        return new LossViews.Page<>(
                db
                        .queryForList(
                                "SELECT r.*"
                                        + where
                                        + " ORDER BY r.next_review_date,r.id LIMIT ? OFFSET ?",
                                args.toArray())
                        .stream()
                        .map(row -> LossViews.lossReport(json, row))
                        .toList(),
                java.util.Objects.requireNonNull(
                        db.queryForObject("SELECT COUNT(*)" + where, Long.class, scope)),
                page,
                size);
    }

    /**
     * Confirms the asset as lost after the search phase. The assigned processor must supply five
     * substantive evidence fields (each at least 10 characters), the asset moves to the LOST
     * exception state, the business lock is released, the confirmation snapshot is frozen, a
     * follow-up todo is filed 30 days out and loan liability is refreshed.
     *
     * @param actor captured actor, must hold the warehouse-operator role
     * @param id loss report id
     * @param version expected report version (If-Match)
     * @param key idempotency key identifying this confirmation attempt
     * @param input confirmation payload (ownership/asset/lock versions, evidence fields, dates)
     * @return the updated report view, or the remembered view of an identical earlier confirmation
     * @throws LossFailure with code {@code LOSS_ROLE_REQUIRED}, {@code LOSS_NOT_FOUND},
     *         {@code LOSS_REPORT_CHANGED} on version/status drift,
     *         {@code LOSS_PROCESSOR_OR_ASSET_CHANGED} when the processor or tracked versions
     *         drifted, {@code LOSS_CONFIRMATION_EVIDENCE_REQUIRED} when any evidence field is
     *         shorter than 10 characters, {@code LOSS_LOCK_CHANGED} when the business lock moved,
     *         {@code LOSS_TIME_INVALID} for a malformed timestamp, or
     *         {@code LOSS_CONFIRMATION_DATE_INVALID} when the search/confirmation timestamps are
     *         out of order relative to the recorded timeline
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReport confirmLost(
            Actor actor, long id, long version, UUID key, LossInput.Confirmation input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String operation = "CONFIRM_LOST:" + id;
        String hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash, LossViews.LossReport.class);
        if (prior != null) {
            return prior;
        }
        var row = header(id);
        guard(row, version, "SEARCHING");
        processor(current, row, input.ownershipVersion());
        if (input.assetVersion() != number(row, "asset_version")) {
            throw new LossFailure(409, "LOSS_PROCESSOR_OR_ASSET_CHANGED");
        }
        if (List.of(
                        input.custodianVerification(),
                        input.searchedScope(),
                        input.relatedClues(),
                        input.inabilityReason(),
                        input.responsibilityEvidence())
                .stream()
                .anyMatch(value -> value.strip().length() < 10)) {
            throw new LossFailure(400, "LOSS_CONFIRMATION_EVIDENCE_REQUIRED");
        }
        lock(row, input.lockVersion());
        validTime(input.searchedAt());
        validTime(input.confirmedAt());
        if (input.searchedAt().isBefore(instant(row.get("last_seen_at")))
                || input.confirmedAt().isBefore(input.searchedAt())) {
            throw new LossFailure(400, "LOSS_CONFIRMATION_DATE_INVALID");
        }
        ZoneId reviewZone = activeReviewZone();
        LocalDate reviewDate = LocalDate.now(clock.withZone(reviewZone)).plusDays(30);
        long next =
                assets.confirmedLost(
                        number(row, "asset_id"), input.assetVersion(), id, current.id(), now(),
                        retainedRepairLifecycle(row));
        db.update(
                "UPDATE fams_loss_report SET report_status='CONFIRMED_LOST',asset_version=?,"
                        + "confirmed_lost_at=?,confirmation_snapshot_json=?,next_review_date=?,"
                        + "version=version+1,updated_at=? WHERE id=?",
                next,
                input.confirmedAt(),
                json.writeValueAsString(input),
                reviewDate,
                now(),
                id);
        release(row, "LOSS_CONFIRMED");
        closeTodos(id);
        todo(id, "LOST_FOLLOW_UP", "WAREHOUSE_OPERATOR", current.id());
        loans.refreshLiability(number(row, "asset_id"));
        audit(actor, current, row, "LOSS_CONFIRMED", version + 1, reviewAudit(input, reviewDate, reviewZone));
        LOG.info("Loss confirmed for report {} by actor {}.", id, actor.id());
        return remember(current, operation, key, hash, header(id));
    }

    /**
     * Records one periodic follow-up review on a confirmed-lost report. The processor must supply
     * findings of at least 10 characters and a reason of at least 3; the reviewed-at timestamp may
     * not precede the confirmation or the previous follow-up, and the next review date (defaulting
     * to 30 days out) may not lie in the past of the active review timezone. The asset must still
     * be in the LOST exception state.
     *
     * @param actor captured actor, must hold the warehouse-operator role
     * @param id loss report id
     * @param version expected report version (If-Match)
     * @param key idempotency key identifying this follow-up attempt
     * @param input follow-up payload (ownership version, findings, reason and dates)
     * @return the updated report view, or the remembered view of an identical earlier follow-up
     * @throws LossFailure with code {@code LOSS_ROLE_REQUIRED}, {@code LOSS_NOT_FOUND},
     *         {@code LOSS_REPORT_CHANGED} on version/status drift,
     *         {@code LOSS_PROCESSOR_OR_ASSET_CHANGED} when the processor or ownership version
     *         drifted, {@code LOSS_TIME_INVALID} for a malformed timestamp,
     *         {@code LOSS_FOLLOW_UP_EVIDENCE_REQUIRED} when findings or reason are too short, or
     *         {@code LOSS_REVIEW_DATE_INVALID} when the review timeline is out of order
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReport followUp(
            Actor actor, long id, long version, UUID key, LossInput.FollowUp input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String operation = "FOLLOW_UP:" + id;
        String hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash, LossViews.LossReport.class);
        if (prior != null) {
            return prior;
        }
        var row = header(id);
        guard(row, version, "CONFIRMED_LOST");
        processor(current, row, input.ownershipVersion());
        validTime(input.reviewedAt());
        if (input.findings().strip().length() < 10 || input.reason().strip().length() < 3) {
            throw new LossFailure(400, "LOSS_FOLLOW_UP_EVIDENCE_REQUIRED");
        }
        ZoneId reviewZone = activeReviewZone();
        LocalDate today = LocalDate.now(clock.withZone(reviewZone));
        LocalDate next = input.nextReviewDate() == null ? today.plusDays(30) : input.nextReviewDate();
        if (input.reviewedAt().isBefore(instant(row.get("confirmed_lost_at")))
                || row.get("last_follow_up_at") != null
                        && input.reviewedAt().isBefore(instant(row.get("last_follow_up_at")))
                || next.isBefore(today)) {
            throw new LossFailure(400, "LOSS_REVIEW_DATE_INVALID");
        }
        assets.requireLost(number(row, "asset_id"));
        db.update(
                "UPDATE fams_loss_report SET last_follow_up_at=?,next_review_date=?,"
                        + "version=version+1,updated_at=? WHERE id=?",
                input.reviewedAt(),
                next,
                now(),
                id);
        audit(actor, current, row, "LOSS_FOLLOWED_UP", version + 1, reviewAudit(input, next, reviewZone));
        LOG.info("Loss follow-up recorded for report {} by actor {}.", id, actor.id());
        return remember(current, operation, key, hash, header(id));
    }

    private void processor(Current current, Map<String, Object> row, long version) {
        if (!java.util.Objects.equals(current.id(), row.get("processor_account_id"))
                || version != number(row, "ownership_version")) {
            throw new LossFailure(409, "LOSS_PROCESSOR_OR_ASSET_CHANGED");
        }
    }

    /**
     * Recovers the asset while the report is still in the search phase, restoring it to its
     * original lifecycle and custody. The processor must supply valid recovery facts (found-at
     * within the recorded timeline, verified physical identity, substantive evidence, condition
     * INTACT and the original custodian retained); the report moves to RECOVERED, the business
     * lock is released and todos are closed.
     *
     * @param actor captured actor, must hold the warehouse-operator role
     * @param id loss report id
     * @param version expected report version (If-Match)
     * @param key idempotency key identifying this recovery attempt
     * @param input recovery execution payload (ownership/asset/lock versions and recovery facts)
     * @return the updated report view, or the remembered view of an identical earlier recovery
     * @throws LossFailure with code {@code LOSS_ROLE_REQUIRED}, {@code LOSS_NOT_FOUND},
     *         {@code LOSS_REPORT_CHANGED} on version/status drift,
     *         {@code LOSS_PROCESSOR_OR_ASSET_CHANGED} when the processor or tracked versions
     *         drifted, {@code LOSS_LOCK_CHANGED} when the business lock moved,
     *         {@code LOSS_RECOVERY_TIME_INVALID} / {@code LOSS_RECOVERY_EVIDENCE_REQUIRED} when
     *         the recovery facts fail the timeline or evidence checks, or
     *         {@code LOSS_LINKED_RECOVERY_REQUIRED} when the asset was not found intact with the
     *         original custodian and a linked-recovery route must be used instead
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReport recoverSearching(
            Actor actor, long id, long version, UUID key, LossInput.RecoveryExecution input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String operation = "RECOVER_ORIGINAL:" + id,
                hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash, LossViews.LossReport.class);
        if (prior != null) {
            return prior;
        }
        var row = header(id);
        guard(row, version, "SEARCHING");
        processor(current, row, input.ownershipVersion());
        if (input.assetVersion() != number(row, "asset_version")) {
            throw new LossFailure(409, "LOSS_PROCESSOR_OR_ASSET_CHANGED");
        }
        lock(row, input.lockVersion());
        recoveryFacts(row, input.facts());
        long next = restoreOriginal(row, input.assetVersion(), "SEARCHING", current, input.facts());
        db.update(
                "UPDATE fams_loss_report SET"
                    + " report_status='RECOVERED',asset_version=?,recovered_at=?,recovery_snapshot_json=?,"
                    + "version=version+1,updated_at=?"
                    + " WHERE id=?",
                next,
                input.facts().foundAt(),
                json.writeValueAsString(input),
                now(),
                id);
        release(row, "LOSS_RECOVERED");
        closeTodos(id);
        audit(actor, current, row, "LOSS_RECOVERED", version + 1, input);
        LOG.info("Lost asset recovered for report {} by actor {}.", id, actor.id());
        return remember(current, operation, key, hash, header(id));
    }

    /**
     * Recovers a damaged asset during the search phase by restoring its original state and
     * immediately creating a linked repair application, which adopts the still-live loss lock
     * before this transaction commits. The recovery facts must show condition DAMAGED with the
     * original custodian retained; the report moves to RECOVERED and records the repair id.
     *
     * @param actor captured actor, must hold the warehouse-operator role
     * @param id loss report id
     * @param version expected report version (If-Match)
     * @param key idempotency key, also reused for the linked repair creation
     * @param input repair-recovery payload with fault/impact descriptions and the execution facts
     * @return report view carrying the created repair id, replayed identically on retry
     * @throws LossFailure with code {@code LOSS_ROLE_REQUIRED}, {@code LOSS_NOT_FOUND},
     *         {@code LOSS_REPORT_CHANGED} on version/status drift,
     *         {@code LOSS_PROCESSOR_OR_ASSET_CHANGED} when the processor or tracked versions
     *         drifted, {@code LOSS_LOCK_CHANGED} when the business lock moved,
     *         {@code LOSS_RECOVERY_EVIDENCE_REQUIRED} when the facts fail timeline/evidence checks,
     *         or {@code LOSS_LINKED_RECOVERY_REQUIRED} when the condition is not DAMAGED or the
     *         original custodian was not retained
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReport recoverToRepair(
            Actor actor, long id, long version, UUID key, LossInput.RepairRecovery input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String operation = "RECOVER_TO_REPAIR:" + id;
        String hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash, LossViews.LossReport.class);
        if (prior != null) {
            return prior;
        }
        var row = header(id);
        guard(row, version, "SEARCHING");
        var execution = input.recovery();
        processor(current, row, execution.ownershipVersion());
        if (execution.assetVersion() != number(row, "asset_version")) {
            throw new LossFailure(409, "LOSS_PROCESSOR_OR_ASSET_CHANGED");
        }
        lock(row, execution.lockVersion());
        var facts = execution.facts();
        validTime(facts.foundAt());
        if (facts.foundAt().isBefore(instant(row.get("last_seen_at")))
                || !facts.physicalIdentityVerified()
                || facts.evidence().strip().length() < 10) {
            throw new LossFailure(400, "LOSS_RECOVERY_EVIDENCE_REQUIRED");
        }
        if (!"DAMAGED".equals(facts.condition()) || !facts.stillOriginalCustodian()) {
            throw new LossFailure(409, "LOSS_LINKED_RECOVERY_REQUIRED");
        }
        // The loss lock remains live throughout; repair adopts it before this transaction commits.
        long next = restoreOriginal(row, execution.assetVersion(), "SEARCHING", current, facts);
        var repair =
                repairs.create(
                        actor,
                        key,
                        new com.fams.repair.RepairInput.Submission(
                                number(row, "asset_id"),
                                next,
                                input.fault(),
                                input.impact(),
                                facts.foundAt()),
                        new com.fams.repair.LossRepairPort.Source(
                                Long.toString(id),
                                execution.lockVersion(),
                                json.writeValueAsString(input)));
        var snapshot = Map.of("execution", input, "repairId", repair.id());
        db.update(
                "UPDATE fams_loss_report SET"
                    + " report_status='RECOVERED',asset_version=?,recovered_at=?,recovery_snapshot_json=?,"
                    + "version=version+1,updated_at=?"
                    + " WHERE id=?",
                next,
                facts.foundAt(),
                json.writeValueAsString(snapshot),
                now(),
                id);
        closeTodos(id);
        audit(actor, current, row, "LOSS_RECOVERED_TO_REPAIR", version + 1, snapshot);
        var result = LossViews.withRepairId(LossViews.lossReport(json, header(id)), repair.id());
        saveReceipt(current, operation, key, hash, id, result);
        LOG.info("Lost asset recovered to repair for report {} by actor {}.", id, actor.id());
        return result;
    }

    /**
     * Requests admin approval to recover an intact confirmed-lost asset back to its original
     * state. Validates the applicant role and recovery facts, checks asset eligibility and loan
     * preconditions in the asset and loan ports, rejects the request when any business lock is
     * still live, then inserts the recovery request, re-locks the asset for the report and files
     * the admin decision todo.
     *
     * @param actor captured actor, must be an eligible loss applicant
     * @param id loss report id
     * @param version expected report version (If-Match)
     * @param key idempotency key identifying this request attempt
     * @param input recovery request payload (asset version and recovery facts)
     * @return report view including the new recovery request, replayed identically on retry
     * @throws LossFailure with code {@code LOSS_ROLE_REQUIRED} when the caller is not an applicant,
     *         {@code LOSS_NOT_FOUND}, {@code LOSS_NOT_VISIBLE}, {@code LOSS_REPORT_CHANGED} on
     *         version/status drift, {@code LOSS_ALREADY_RECOVERED} when the report recovered
     *         meanwhile, {@code LOSS_RECOVERY_PURPOSE_CHANGED} when the report carries a repair
     *         origin, {@code LOSS_RECOVERY_TIME_INVALID} / {@code LOSS_RECOVERY_EVIDENCE_REQUIRED}
     *         / {@code LOSS_LINKED_RECOVERY_REQUIRED} from the facts checks, or
     *         {@code LOSS_ASSET_LOCKED} while another business lock is held on the asset
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReportWithRecovery requestRecovery(
            Actor actor, long id, long version, UUID key, LossInput.RecoveryRequest input) {
        return requestRecovery(actor, id, version, key, input, false, null);
    }

    /**
     * Requests admin approval to recover a confirmed-lost asset that was found damaged, so the
     * later execution can create a linked repair. Same flow as {@link #requestRecovery} but the
     * facts must show condition DAMAGED with the original custodian retained.
     *
     * @param actor captured actor, must be an eligible loss applicant
     * @param id loss report id
     * @param version expected report version (If-Match)
     * @param key idempotency key identifying this request attempt
     * @param input damaged-recovery request payload (asset version and recovery facts)
     * @return report view including the new recovery request, replayed identically on retry
     * @throws LossFailure with the codes of {@link #requestRecovery}, where the facts checks use
     *         {@code LOSS_LINKED_RECOVERY_REQUIRED} when the condition is not DAMAGED or the
     *         original custodian was not retained
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReportWithRecovery requestRepairRecovery(
            Actor actor, long id, long version, UUID key, LossInput.DamagedRecoveryRequest input) {
        return requestRecovery(actor, id, version, key,
                new LossInput.RecoveryRequest(input.assetVersion(), input.facts()), true, null);
    }

    private LossViews.LossReportWithRecovery requestRecovery(
            Actor actor, long id, long version, UUID key, LossInput.RecoveryRequest input,
            boolean damaged, String destination) {
        boolean returning = "ASSET_RETURN".equals(destination);
        boolean transferring = "TRANSFER".equals(destination);
        boolean factory = "FACTORY_REPAIR".equals(destination);
        boolean internal = "INTERNAL_REPAIR".equals(destination);
        var current = actors.revalidateLocked(actor);
        if (!applicant(current)) {
            throw new LossFailure(403, "LOSS_ROLE_REQUIRED");
        }
        String operation = (internal ? "RECOVERY_INTERNAL_REPAIR_REQUEST:"
                : factory ? "RECOVERY_FACTORY_REQUEST:"
                : transferring ? "RECOVERY_TRANSFER_REQUEST:"
                : returning ? "RECOVERY_RETURN_REQUEST:"
                : damaged ? "RECOVERY_REPAIR_REQUEST:"
                : "RECOVERY_REQUEST:") + id,
                hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash, LossViews.LossReportWithRecovery.class);
        if (prior != null) {
            return prior;
        }
        var row = header(id);
        visible(current, row);
        guard(row, version, "CONFIRMED_LOST");
        if (row.get("recovered_at") != null) {
            throw new LossFailure(409, "LOSS_ALREADY_RECOVERED");
        }
        if (factory != (factoryProcessor(row) != null) || internal != (internalRepairProcessor(row) != null)) {
            throw new LossFailure(409, "LOSS_RECOVERY_PURPOSE_CHANGED");
        }
        if (factory) {
            factoryRecoveryFacts(row, input.facts());
            assets.validateFactoryRecovery(number(row, "asset_id"), input.assetVersion(), "LOST",
                    input.facts().observedAssetCode(), input.facts().observedSerialNumber(),
                    input.facts().locationId(), input.facts().locationVersion());
        } else if (internal) {
            factoryRecoveryFacts(row, input.facts());
            assets.validateInternalRepairRecovery(number(row, "asset_id"), input.assetVersion(), "LOST",
                    input.facts().observedAssetCode(), input.facts().observedSerialNumber(),
                    input.facts().locationId(), input.facts().locationVersion());
        } else {
            if (transferring) {
                transferRecoveryFacts(row, input.facts());
            } else if (returning) {
                returnRecoveryFacts(row, input.facts());
            } else if (damaged) {
                damagedRecoveryFacts(row, input.facts());
            } else {
                recoveryFacts(row, input.facts());
            }
            var asset = transferring
                    ? assets.validateTransferRecovery(number(row, "asset_id"), input.assetVersion(), "LOST",
                            row.get("source_snapshot_json").toString(), input.facts().observedAssetCode(),
                            input.facts().observedSerialNumber(), input.facts().locationId(),
                            input.facts().locationVersion())
                    : returning ? assets.validateReturnRecovery(number(row, "asset_id"), input.assetVersion(), "LOST",
                            row.get("source_snapshot_json").toString(), input.facts().observedAssetCode(),
                            input.facts().observedSerialNumber(), input.facts().locationId(),
                            input.facts().locationVersion())
                    : assets.validateOriginalRecovery(number(row, "asset_id"), input.assetVersion(), "LOST",
                            row.get("source_snapshot_json").toString(), input.facts().observedAssetCode(),
                            input.facts().observedSerialNumber(), input.facts().locationId(),
                            input.facts().locationVersion());
            loans.requireRecovery(
                    number(row, "asset_id"),
                    asset.lifecycleStatus(),
                    asset.responsibleEmployeeId(),
                    asset.usingOrganizationId());
        }
        if (!db.queryForList(
                        "SELECT id FROM fams_asset_business_lock WHERE asset_id=? AND released_at"
                                + " IS NULL FOR UPDATE",
                        row.get("asset_id"))
                .isEmpty()) {
            throw new LossFailure(409, "LOSS_ASSET_LOCKED");
        }
        db.update(
                "INSERT INTO"
                    + " fams_loss_recovery(report_id,asset_id,creator_account_id,asset_version,"
                    + "request_snapshot_json,created_at,updated_at)"
                    + " VALUES (?,?,?,?,?,?,?)",
                id,
                row.get("asset_id"),
                current.id(),
                input.assetVersion(),
                json.writeValueAsString(destination != null
                        ? Map.of("assetVersion", input.assetVersion(), "facts", input.facts(),
                                "destination", destination)
                        : input),
                now(),
                now());
        long recovery =
                java.util.Objects.requireNonNull(
                        db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        db.update(
                "INSERT INTO"
                    + " fams_asset_business_lock(asset_id,business_type,business_id,business_item_id,acquired_at)"
                    + " VALUES (?,'LOST_REPORT',?,?,?)",
                row.get("asset_id"),
                id,
                recovery,
                now());
        advanceRecoveryReport(
                actor,
                current,
                row,
                "LOSS_RECOVERY_REQUESTED",
                Map.of("recoveryId", Long.toString(recovery), "facts", input));
        todo(id, "RECOVERY_DECIDE", "ASSET_ADMIN", null);
        return rememberRecovery(current, operation, key, hash, id, recovery);
    }

    /**
     * Records the asset-admin decision on a pending recovery request. Approval re-checks the asset
     * against the frozen request destination and moves the request to WAITING_WAREHOUSE_CLAIM with
     * a claim todo; rejection releases the asset business lock and marks the request REJECTED. The
     * decision snapshot freezes the actor, reason and original request payload.
     *
     * @param actor captured actor, must hold the asset-admin role
     * @param recovery recovery request id
     * @param version expected recovery request version (If-Match)
     * @param key idempotency key identifying this decision attempt
     * @param input decision payload with result APPROVE or REJECT and a reason
     * @return report view with the updated recovery request, replayed identically on retry
     * @throws LossFailure with code {@code LOSS_ROLE_REQUIRED}, {@code LOSS_DECISION_INVALID} for a
     *         result other than APPROVE/REJECT, {@code LOSS_RECOVERY_NOT_FOUND} for an unknown
     *         request, {@code LOSS_RECOVERY_CHANGED} on request version/status drift,
     *         {@code LOSS_ALREADY_RECOVERED} when the parent report recovered meanwhile, or
     *         {@code LOSS_LOCK_CHANGED} when the recovery business lock moved
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReportWithRecovery decideRecovery(
            Actor actor, long recovery, long version, UUID key, LossInput.Decision input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.ASSET_ADMIN);
        String operation = "RECOVERY_DECIDE:" + recovery,
                hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash, LossViews.LossReportWithRecovery.class);
        if (prior != null) {
            return prior;
        }
        var row = recoveryParent(recovery);
        var request = recoveryHeader(recovery);
        recoveryGuard(request, version, "PENDING_ADMIN_DECISION");
        recoveryLock(row, recovery, null);
        boolean approve = "APPROVE".equals(input.result());
        if (!approve && !"REJECT".equals(input.result())) {
            throw new LossFailure(400, "LOSS_DECISION_INVALID");
        }
        if (approve) {
            requireRequestedRecoveryAsset(row, request);
        }
        db.update(
                "UPDATE fams_loss_recovery SET"
                    + " recovery_status=?,approval_snapshot_json=?,version=version+1,updated_at=?"
                    + " WHERE id=?",
                approve ? "WAITING_WAREHOUSE_CLAIM" : "REJECTED",
                json.writeValueAsString(
                        Map.of(
                                "actorAccountId",
                                Long.toString(current.id()),
                                "reason",
                                input.reason().strip(),
                                "request",
                                json.readTree(request.get("request_snapshot_json").toString()),
                                "recordedAt",
                                now().toString())),
                now(),
                recovery);
        closeRecoveryTodos(number(row, "id"));
        if (approve) {
            todo(number(row, "id"), "RECOVERY_CLAIM", "WAREHOUSE_OPERATOR", retainedRepairProcessor(row));
        } else {
            release(row, "LOSS_RECOVERY_REJECTED");
        }
        advanceRecoveryReport(
                actor,
                current,
                row,
                "LOSS_RECOVERY_DECIDED",
                Map.of("recoveryId", Long.toString(recovery), "decision", input));
        LOG.info("Loss recovery {} decision recorded by actor {}.", recovery, actor.id());
        return rememberRecovery(current, operation, key, hash, number(row, "id"), recovery);
    }

    /**
     * Cancels a recovery request that is still awaiting the admin decision. Only the request
     * creator or an asset admin may cancel; the asset business lock is released, recovery todos
     * are closed and the request is marked CANCELLED.
     *
     * @param actor captured actor, must be the request creator or hold the asset-admin role
     * @param recovery recovery request id
     * @param version expected recovery request version (If-Match)
     * @param key idempotency key identifying this cancellation attempt
     * @param input payload carrying the cancellation reason
     * @return report view with the updated recovery request, replayed identically on retry
     * @throws LossFailure with code {@code LOSS_CREATOR_REQUIRED} when the caller is neither the
     *         creator nor an asset admin, {@code LOSS_NOT_VISIBLE},
     *         {@code LOSS_RECOVERY_NOT_FOUND}, {@code LOSS_RECOVERY_CHANGED} on version/status
     *         drift, {@code LOSS_ALREADY_RECOVERED}, or {@code LOSS_LOCK_CHANGED} when the
     *         recovery business lock moved
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReportWithRecovery cancelRecovery(
            Actor actor, long recovery, long version, UUID key, LossInput.Reason input) {
        var current = actors.revalidateLocked(actor);
        String operation = "RECOVERY_CANCEL:" + recovery,
                hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash, LossViews.LossReportWithRecovery.class);
        if (prior != null) {
            return prior;
        }
        var row = recoveryParent(recovery);
        var request = recoveryHeader(recovery);
        visible(current, row);
        if (current.id() != number(request, "creator_account_id")
                && !current.has(Role.ASSET_ADMIN)) {
            throw new LossFailure(403, "LOSS_CREATOR_REQUIRED");
        }
        recoveryGuard(request, version, "PENDING_ADMIN_DECISION");
        recoveryLock(row, recovery, null);
        db.update(
                "UPDATE fams_loss_recovery SET"
                        + " recovery_status='CANCELLED',version=version+1,updated_at=? WHERE id=?",
                now(),
                recovery);
        release(row, "LOSS_RECOVERY_CANCELLED");
        closeRecoveryTodos(number(row, "id"));
        advanceRecoveryReport(
                actor,
                current,
                row,
                "LOSS_RECOVERY_CANCELLED",
                Map.of("recoveryId", Long.toString(recovery), "reason", input.reason()));
        LOG.info("Loss recovery {} cancelled by actor {}.", recovery, actor.id());
        return rememberRecovery(current, operation, key, hash, number(row, "id"), recovery);
    }

    /**
     * Claims an approved recovery request for warehouse execution. Reports originating from a
     * factory round or an internal repair may only be claimed by that source's processor. The
     * request moves to VERIFYING, the caller becomes the processor and the ownership version
     * advances; the asset state is re-checked against the frozen request destination.
     *
     * @param actor captured actor, must hold the warehouse-operator role
     * @param recovery recovery request id
     * @param version expected recovery request version (If-Match)
     * @param key idempotency key identifying this claim attempt
     * @return report view with the updated recovery request, replayed identically on retry
     * @throws LossFailure with code {@code LOSS_ROLE_REQUIRED}, {@code LOSS_RECOVERY_NOT_FOUND},
     *         {@code LOSS_RECOVERY_CHANGED} on version/status drift, {@code LOSS_ALREADY_RECOVERED},
     *         {@code LOSS_LOCK_CHANGED} when the recovery business lock moved,
     *         {@code LOSS_FACTORY_PROCESSOR_REQUIRED} or
     *         {@code LOSS_INTERNAL_REPAIR_PROCESSOR_REQUIRED} when a different processor claims a
     *         repair-sourced report
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReportWithRecovery claimRecovery(Actor actor, long recovery, long version, UUID key) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String operation = "RECOVERY_CLAIM:" + recovery,
                hash = LossPayloads.fingerprint(json, operation, version, null);
        var prior = replay(current, operation, key, hash, LossViews.LossReportWithRecovery.class);
        if (prior != null) {
            return prior;
        }
        var row = recoveryParent(recovery);
        var request = recoveryHeader(recovery);
        recoveryGuard(request, version, "WAITING_WAREHOUSE_CLAIM");
        recoveryLock(row, recovery, null);
        Long factoryProcessor = factoryProcessor(row);
        if (factoryProcessor != null && factoryProcessor != current.id()) {
            throw new LossFailure(403, "LOSS_FACTORY_PROCESSOR_REQUIRED");
        }
        Long internal = internalRepairProcessor(row);
        if (internal != null && internal != current.id()) {
            throw new LossFailure(403, "LOSS_INTERNAL_REPAIR_PROCESSOR_REQUIRED");
        }
        requireRequestedRecoveryAsset(row, request);
        db.update(
                "UPDATE fams_loss_recovery SET"
                    + " recovery_status='VERIFYING',processor_account_id=?,"
                    + "ownership_version=ownership_version+1,version=version+1,updated_at=?"
                    + " WHERE id=?",
                current.id(),
                now(),
                recovery);
        closeRecoveryTodos(number(row, "id"));
        todo(number(row, "id"), "RECOVERY_VERIFY", "WAREHOUSE_OPERATOR", current.id());
        advanceRecoveryReport(
                actor,
                current,
                row,
                "LOSS_RECOVERY_CLAIMED",
                Map.of(
                        "recoveryId",
                        Long.toString(recovery),
                        "processorAccountId",
                        Long.toString(current.id())));
        LOG.info("Loss recovery {} claimed by actor {}.", recovery, actor.id());
        return rememberRecovery(current, operation, key, hash, number(row, "id"), recovery);
    }

    /**
     * Executes a claimed recovery request whose frozen facts showed the asset INTACT: restores the
     * asset to its original lifecycle and custody from the LOST exception state, completes the
     * recovery request, stamps the parent report as recovered, releases the business lock and
     * closes todos.
     *
     * @param actor captured actor, must hold the warehouse-operator role
     * @param recovery recovery request id
     * @param version expected recovery request version (If-Match)
     * @param key idempotency key identifying this execution attempt
     * @param input recovery execution payload (ownership/asset/lock versions and recovery facts)
     * @return report view with the completed recovery request, replayed identically on retry
     * @throws LossFailure with code {@code LOSS_ROLE_REQUIRED}, {@code LOSS_RECOVERY_NOT_FOUND},
     *         {@code LOSS_RECOVERY_CHANGED} on version/status drift,
     *         {@code LOSS_RECOVERY_PURPOSE_CHANGED} when the frozen request was not an intact
     *         original recovery, {@code LOSS_PROCESSOR_OR_ASSET_CHANGED} when the processor or
     *         tracked versions drifted, {@code LOSS_LOCK_CHANGED} when the recovery lock moved, or
     *         the recovery facts codes {@code LOSS_RECOVERY_TIME_INVALID} /
     *         {@code LOSS_RECOVERY_EVIDENCE_REQUIRED} / {@code LOSS_LINKED_RECOVERY_REQUIRED}
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReportWithRecovery executeRecovery(
            Actor actor, long recovery, long version, UUID key, LossInput.RecoveryExecution input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String operation = "RECOVERY_EXECUTE:" + recovery,
                hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash, LossViews.LossReportWithRecovery.class);
        if (prior != null) {
            return prior;
        }
        var row = recoveryParent(recovery);
        var request = recoveryHeader(recovery);
        recoveryGuard(request, version, "VERIFYING");
        requireRecoveryCondition(request, "INTACT");
        processor(current, request, input.ownershipVersion());
        if (input.assetVersion() != number(request, "asset_version")) {
            throw new LossFailure(409, "LOSS_PROCESSOR_OR_ASSET_CHANGED");
        }
        recoveryLock(row, recovery, input.lockVersion());
        recoveryFacts(row, input.facts());
        long next = restoreOriginal(row, input.assetVersion(), "LOST", current, input.facts());
        db.update(
                "UPDATE fams_loss_recovery SET"
                    + " recovery_status='COMPLETED',execution_snapshot_json=?,version=version+1,updated_at=?"
                    + " WHERE id=?",
                json.writeValueAsString(input),
                now(),
                recovery);
        db.update(
                "UPDATE fams_loss_report SET"
                        + " asset_version=?,recovered_at=?,recovery_snapshot_json=? WHERE id=?",
                next,
                input.facts().foundAt(),
                json.writeValueAsString(input),
                row.get("id"));
        release(row, "LOSS_RECOVERY_COMPLETED");
        closeTodos(number(row, "id"));
        advanceRecoveryReport(
                actor,
                current,
                row,
                "LOSS_RECOVERED_AFTER_CONFIRMATION",
                Map.of("recoveryId", Long.toString(recovery), "execution", input));
        LOG.info("Loss recovery {} executed by actor {}.", recovery, actor.id());
        return rememberRecovery(current, operation, key, hash, number(row, "id"), recovery);
    }

    /**
     * Executes a claimed recovery request whose frozen facts showed the asset DAMAGED: restores the
     * asset from the LOST exception state and creates the linked repair application through the
     * repair port in the same transaction, then completes the request and stamps the parent report
     * as recovered with the repair id in its snapshot.
     *
     * @param actor captured actor, must hold the warehouse-operator role
     * @param recovery recovery request id
     * @param version expected recovery request version (If-Match)
     * @param key idempotency key, also reused for the linked repair creation
     * @param input closed-repair recovery payload with fault/impact and the execution facts
     * @return report view with the completed recovery request, replayed identically on retry
     * @throws LossFailure with code {@code LOSS_ROLE_REQUIRED}, {@code LOSS_RECOVERY_NOT_FOUND},
     *         {@code LOSS_RECOVERY_CHANGED} on version/status drift,
     *         {@code LOSS_RECOVERY_PURPOSE_CHANGED} when the frozen request was not a damaged
     *         repair recovery, {@code LOSS_PROCESSOR_OR_ASSET_CHANGED} when the processor or
     *         tracked versions drifted, {@code LOSS_LOCK_CHANGED} when the recovery lock moved, or
     *         the damaged facts codes {@code LOSS_RECOVERY_TIME_INVALID} /
     *         {@code LOSS_RECOVERY_EVIDENCE_REQUIRED} / {@code LOSS_LINKED_RECOVERY_REQUIRED}
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReportWithRecovery executeRecoveryToRepair(
            Actor actor, long recovery, long version, UUID key, LossInput.ClosedRepairRecovery input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String operation = "RECOVERY_EXECUTE_TO_REPAIR:" + recovery;
        String hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash, LossViews.LossReportWithRecovery.class);
        if (prior != null) {
            return prior;
        }
        var row = recoveryParent(recovery);
        var request = recoveryHeader(recovery);
        recoveryGuard(request, version, "VERIFYING");
        requireRecoveryCondition(request, "DAMAGED");
        var execution = input.recovery();
        processor(current, request, execution.ownershipVersion());
        if (execution.assetVersion() != number(request, "asset_version")) {
            throw new LossFailure(409, "LOSS_PROCESSOR_OR_ASSET_CHANGED");
        }
        recoveryLock(row, recovery, execution.lockVersion());
        damagedRecoveryFacts(row, execution.facts());
        long next = restoreOriginal(row, execution.assetVersion(), "LOST", current, execution.facts());
        var repair = repairs.createAfterConfirmation(actor, key,
                new com.fams.repair.RepairInput.Submission(number(row, "asset_id"), next,
                        input.fault(), input.impact(), execution.facts().foundAt()),
                new com.fams.repair.LossRepairPort.Source(Long.toString(number(row, "id")),
                        execution.lockVersion(), json.writeValueAsString(input)), recovery);
        var snapshot = Map.of("execution", input, "repairId", repair.id(),
                "recoveryId", Long.toString(recovery));
        db.update("UPDATE fams_loss_recovery SET recovery_status='COMPLETED',"
                        + "execution_snapshot_json=?,version=version+1,updated_at=? WHERE id=?",
                json.writeValueAsString(snapshot), now(), recovery);
        db.update("UPDATE fams_loss_report SET asset_version=?,recovered_at=?,"
                        + "recovery_snapshot_json=? WHERE id=?", next, execution.facts().foundAt(),
                json.writeValueAsString(snapshot), row.get("id"));
        closeTodos(number(row, "id"));
        advanceRecoveryReport(actor, current, row, "LOSS_RECOVERED_AFTER_CONFIRMATION_TO_REPAIR",
                snapshot);
        LOG.info("Loss recovery {} executed to repair by actor {}.", recovery, actor.id());
        return rememberRecovery(current, operation, key, hash, number(row, "id"), recovery);
    }

    /**
     * Pages the locations selectable as the recovery destination when requesting a recovery. Only
     * eligible loss applicants may read the list; the rows come from the asset domain port.
     *
     * @param actor captured actor, must be an eligible loss applicant
     * @param page 1-based page number
     * @param size maximum number of rows per page
     * @return page of recovery location choices
     * @throws LossFailure with code {@code LOSS_ROLE_REQUIRED} if the caller is not an applicant
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public AssetLossPort.RecoveryLocationPage recoveryLocations(Actor actor, int page, int size) {
        if (!applicant(actors.revalidateLocked(actor))) { throw new LossFailure(403, "LOSS_ROLE_REQUIRED"); }
        LOG.debug("Loss recovery locations requested page {} size {}.", page, size);
        return assets.recoveryLocations(page, size);
    }

    /**
     * Requests admin approval to recover a confirmed-lost asset into a new asset return. Same flow
     * as {@link #requestRecovery} but the frozen destination is {@code ASSET_RETURN} and the facts
     * must show condition INTACT without the original custodian being retained.
     *
     * @param actor captured actor, must be an eligible loss applicant
     * @param id loss report id
     * @param version expected report version (If-Match)
     * @param key idempotency key identifying this request attempt
     * @param input return-recovery request payload (asset version and recovery facts)
     * @return report view including the new recovery request, replayed identically on retry
     * @throws LossFailure with the codes of {@link #requestRecovery}, where the facts checks use
     *         {@code LOSS_RETURN_RECOVERY_REQUIRED} when the condition is not INTACT or the
     *         original custodian was retained
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReportWithRecovery requestReturnRecovery(Actor actor, long id, long version,
            UUID key, LossInput.ReturnRecoveryRequest input) {
        return requestRecovery(actor, id, version, key,
                new LossInput.RecoveryRequest(input.assetVersion(), input.facts()), false, "ASSET_RETURN");
    }

    /**
     * Requests admin approval to recover a confirmed-lost asset into a transfer. Same flow as
     * {@link #requestRecovery} but the frozen destination is {@code TRANSFER} and the facts must
     * show condition INTACT with either a changed custodian or a location different from the one
     * frozen in the report source snapshot.
     *
     * @param actor captured actor, must be an eligible loss applicant
     * @param id loss report id
     * @param version expected report version (If-Match)
     * @param key idempotency key identifying this request attempt
     * @param input transfer-recovery request payload (asset version and recovery facts)
     * @return report view including the new recovery request, replayed identically on retry
     * @throws LossFailure with the codes of {@link #requestRecovery}, where the facts checks use
     *         {@code LOSS_TRANSFER_RECOVERY_REQUIRED} when the condition is not INTACT or the asset
     *         was found with the original custodian at the original location
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReportWithRecovery requestTransferRecovery(Actor actor, long id, long version,
            UUID key, LossInput.ReturnRecoveryRequest input) {
        return requestRecovery(actor, id, version, key,
                new LossInput.RecoveryRequest(input.assetVersion(), input.facts()), false, "TRANSFER");
    }

    private void requireRequestedRecoveryAsset(Map<String, Object> row, Map<String, Object> request) {
        var frozen = json.readTree(request.get("request_snapshot_json").toString());
        String destination = frozen.path("destination").asText();
        if ("FACTORY_REPAIR".equals(destination)) {
            assets.requireFactoryRecovery(number(row, "asset_id"), number(request, "asset_version"));
        } else if ("INTERNAL_REPAIR".equals(destination)) {
            assets.requireInternalRepairRecovery(number(row, "asset_id"), number(request, "asset_version"));
        } else if ("TRANSFER".equals(destination)) {
            assets.requireTransferRecovery(number(row, "asset_id"), number(request, "asset_version"),
                    "LOST", row.get("source_snapshot_json").toString());
        } else if ("ASSET_RETURN".equals(destination)) {
            assets.requireReturnRecovery(number(row, "asset_id"), number(request, "asset_version"),
                    "LOST", row.get("source_snapshot_json").toString());
        } else {
            assets.requireOriginalRecovery(number(row, "asset_id"), number(request, "asset_version"),
                    "LOST", row.get("source_snapshot_json").toString());
        }
    }

    /**
     * Recovers a searching-phase report by handing the found asset into a new asset return. The
     * processor's execution facts must show condition INTACT without the original custodian being
     * retained; the asset port validates and restores the return-eligible state, the loan port
     * re-checks recovery preconditions, and a linked return is created in the same transaction.
     * The report moves to RECOVERED.
     *
     * @param actor captured actor, must hold the warehouse-operator role
     * @param id loss report id
     * @param version expected report version (If-Match)
     * @param key idempotency key, also reused for the linked return creation
     * @param input explicit recovery execution payload (ownership/asset/lock versions and facts)
     * @return the updated report view, replayed identically on retry
     * @throws LossFailure with code {@code LOSS_ROLE_REQUIRED}, {@code LOSS_NOT_FOUND},
     *         {@code LOSS_REPORT_CHANGED} on version/status drift,
     *         {@code LOSS_PROCESSOR_OR_ASSET_CHANGED} when the processor or tracked versions
     *         drifted, {@code LOSS_LOCK_CHANGED} when the business lock moved,
     *         {@code LOSS_RETURN_RECOVERY_REQUIRED} when the facts do not describe a return-bound
     *         recovery, or the timeline/evidence codes {@code LOSS_RECOVERY_TIME_INVALID} /
     *         {@code LOSS_RECOVERY_EVIDENCE_REQUIRED}
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReport recoverToReturn(Actor actor, long id, long version, UUID key,
            LossInput.ExplicitRecoveryExecution input) {
        return recoverToReturn(actor, id, version, key, input, false, LossViews.LossReport.class);
    }

    /**
     * Executes a claimed recovery request with the frozen destination {@code ASSET_RETURN}: same
     * return-handoff flow as {@link #recoverToReturn} but driven by the approved recovery request,
     * which must be in VERIFYING status. The request is completed and the parent report stays in
     * CONFIRMED_LOST while being stamped as recovered.
     *
     * @param actor captured actor, must hold the warehouse-operator role
     * @param recovery recovery request id
     * @param version expected recovery request version (If-Match)
     * @param key idempotency key, also reused for the linked return creation
     * @param input explicit recovery execution payload (ownership/asset/lock versions and facts)
     * @return report view with the completed recovery request, replayed identically on retry
     * @throws LossFailure with the codes of {@link #recoverToReturn}, plus
     *         {@code LOSS_RECOVERY_NOT_FOUND}, {@code LOSS_RECOVERY_CHANGED} on request
     *         version/status drift and {@code LOSS_RECOVERY_PURPOSE_CHANGED} when the frozen
     *         request destination is not ASSET_RETURN
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReportWithRecovery executeRecoveryToReturn(Actor actor, long recovery, long version,
            UUID key, LossInput.ExplicitRecoveryExecution input) {
        return recoverToReturn(actor, recovery, version, key, input, true,
                LossViews.LossReportWithRecovery.class);
    }

    private <T> T recoverToReturn(Actor actor, long id, long version, UUID key,
            LossInput.ExplicitRecoveryExecution input, boolean closed, Class<T> type) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String operation = (closed ? "RECOVERY_EXECUTE_TO_RETURN:" : "RECOVER_TO_RETURN:") + id;
        String hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash, type);
        if (prior != null) { return prior; }
        var row = closed ? recoveryParent(id) : header(id);
        var request = closed ? recoveryHeader(id) : row;
        if (closed) {
            recoveryGuard(request, version, "VERIFYING");
            var frozen = json.readTree(request.get("request_snapshot_json").toString());
            if (!"ASSET_RETURN".equals(frozen.path("destination").asText())) {
                throw new LossFailure(409, "LOSS_RECOVERY_PURPOSE_CHANGED");
            }
        } else {
            guard(row, version, "SEARCHING");
        }
        processor(current, request, input.ownershipVersion());
        if (input.assetVersion() != number(request, "asset_version")) {
            throw new LossFailure(409, "LOSS_PROCESSOR_OR_ASSET_CHANGED");
        }
        if (closed) { recoveryLock(row, id, input.lockVersion()); }
        else { lock(row, input.lockVersion()); }
        returnRecoveryFacts(row, input.facts());
        var asset = assets.requireReturnRecovery(number(row, "asset_id"), input.assetVersion(),
                closed ? "LOST" : "SEARCHING", row.get("source_snapshot_json").toString());
        loans.requireRecovery(number(row, "asset_id"), asset.lifecycleStatus(),
                asset.responsibleEmployeeId(), asset.usingOrganizationId());
        long next = assets.recoverForReturn(number(row, "asset_id"), input.assetVersion(),
                closed ? "LOST" : "SEARCHING", row.get("source_snapshot_json").toString(),
                input.facts().observedAssetCode(), input.facts().observedSerialNumber(),
                input.facts().locationId(), input.facts().locationVersion(), number(row, "id"), current.id(), now());
        var linked = returns.create(actor, key, number(row, "asset_id"), next, input.facts().evidence(),
                new com.fams.loan.LossReturnPort.Source(Long.toString(number(row, "id")),
                        Long.toString(closed ? id : number(row, "id")),
                        input.lockVersion(), json.writeValueAsString(input)));
        var snapshot = Map.of("execution", input, "returnId", linked.id(),
                "destination", "ASSET_RETURN");
        if (closed) {
            db.update("UPDATE fams_loss_recovery SET recovery_status='COMPLETED',execution_snapshot_json=?,"
                    + "version=version+1,updated_at=? WHERE id=?", json.writeValueAsString(snapshot), now(), id);
        }
        db.update("UPDATE fams_loss_report SET asset_version=?,recovered_at=?,recovery_snapshot_json=?,"
                + "report_status=?,version=version+1,updated_at=? WHERE id=?", next, input.facts().foundAt(),
                json.writeValueAsString(snapshot), closed ? "CONFIRMED_LOST" : "RECOVERED", now(), row.get("id"));
        closeTodos(number(row, "id"));
        audit(actor, current, row, "LOSS_RECOVERED_TO_RETURN", number(row, "version") + 1, snapshot);
        loans.refreshLiability(number(row, "asset_id"));
        return type.cast(closed
                ? rememberRecovery(current, operation, key, hash, number(row, "id"), id)
                : remember(current, operation, key, hash, header(number(row, "id"))));
    }

    /**
     * Recovers a searching-phase report by handing the found asset into a new transfer. The
     * processor's execution facts must show condition INTACT with either a changed custodian or a
     * location different from the frozen source snapshot; the asset port restores the
     * transfer-eligible state and a linked transfer is created in the same transaction. The report
     * moves to RECOVERED.
     *
     * @param actor captured actor, must hold the warehouse-operator role
     * @param id loss report id
     * @param version expected report version (If-Match)
     * @param key idempotency key identifying this execution attempt
     * @param input transfer recovery execution payload (target, reason and the recovery execution)
     * @return the updated report view, replayed identically on retry
     * @throws LossFailure with code {@code LOSS_ROLE_REQUIRED}, {@code LOSS_NOT_FOUND},
     *         {@code LOSS_REPORT_CHANGED} on version/status drift,
     *         {@code LOSS_PROCESSOR_OR_ASSET_CHANGED} when the processor or tracked versions
     *         drifted, {@code LOSS_LOCK_CHANGED} when the business lock moved,
     *         {@code LOSS_TRANSFER_RECOVERY_REQUIRED} when the facts do not describe a
     *         transfer-bound recovery, or the timeline/evidence codes
     *         {@code LOSS_RECOVERY_TIME_INVALID} / {@code LOSS_RECOVERY_EVIDENCE_REQUIRED}
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReport recoverToTransfer(Actor actor, long id, long version, UUID key,
            LossInput.TransferRecoveryExecution input) {
        return recoverToTransfer(actor, id, version, key, input, false, LossViews.LossReport.class);
    }

    /**
     * Executes a claimed recovery request with the frozen destination {@code TRANSFER}: same
     * transfer-handoff flow as {@link #recoverToTransfer} but driven by the approved recovery
     * request, which must be in VERIFYING status. The request is completed and the parent report
     * stays in CONFIRMED_LOST while being stamped as recovered.
     *
     * @param actor captured actor, must hold the warehouse-operator role
     * @param id recovery request id
     * @param version expected recovery request version (If-Match)
     * @param key idempotency key identifying this execution attempt
     * @param input transfer recovery execution payload (target, reason and the recovery execution)
     * @return report view with the completed recovery request, replayed identically on retry
     * @throws LossFailure with the codes of {@link #recoverToTransfer}, plus
     *         {@code LOSS_RECOVERY_NOT_FOUND}, {@code LOSS_RECOVERY_CHANGED} on request
     *         version/status drift and {@code LOSS_RECOVERY_PURPOSE_CHANGED} when the frozen
     *         request destination is not TRANSFER
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReportWithRecovery executeRecoveryToTransfer(Actor actor, long id, long version, UUID key,
            LossInput.TransferRecoveryExecution input) {
        return recoverToTransfer(actor, id, version, key, input, true, LossViews.LossReportWithRecovery.class);
    }

    private <T> T recoverToTransfer(Actor actor, long id, long version, UUID key,
            LossInput.TransferRecoveryExecution input, boolean closed, Class<T> type) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String operation = (closed ? "RECOVERY_EXECUTE_TO_TRANSFER:" : "RECOVER_TO_TRANSFER:") + id;
        String hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash, type);
        if (prior != null) {
            return prior;
        }
        var row = closed ? recoveryParent(id) : header(id);
        var request = closed ? recoveryHeader(id) : row;
        if (closed) {
            recoveryGuard(request, version, "VERIFYING");
            var frozen = json.readTree(request.get("request_snapshot_json").toString());
            if (!"TRANSFER".equals(frozen.path("destination").asText())) {
                throw new LossFailure(409, "LOSS_RECOVERY_PURPOSE_CHANGED");
            }
        } else {
            guard(row, version, "SEARCHING");
        }
        var recovery = input.recovery();
        processor(current, request, recovery.ownershipVersion());
        if (recovery.assetVersion() != number(request, "asset_version")) {
            throw new LossFailure(409, "LOSS_PROCESSOR_OR_ASSET_CHANGED");
        }
        if (closed) {
            recoveryLock(row, id, recovery.lockVersion());
        } else {
            lock(row, recovery.lockVersion());
        }
        var facts = recovery.facts();
        transferRecoveryFacts(row, facts);
        long report = number(row, "id");
        long assetId = number(row, "asset_id");
        long next = assets.recoverForTransfer(assetId, recovery.assetVersion(), closed ? "LOST" : "SEARCHING",
                row.get("source_snapshot_json").toString(), facts.observedAssetCode(),
                facts.observedSerialNumber(), facts.locationId(), facts.locationVersion(), report, current.id(), now());
        var linked = transfers.create(actor, assetId, next, input.target(), input.reason(),
                new LossTransferPort.Source(report, id, recovery.lockVersion()));
        var snapshot = Map.of("execution", input, "transferId", linked.id(), "destination", "TRANSFER");
        if (closed) {
            db.update("UPDATE fams_loss_recovery SET recovery_status='COMPLETED',execution_snapshot_json=?,"
                    + "version=version+1,updated_at=? WHERE id=?", json.writeValueAsString(snapshot), now(), id);
        }
        db.update("UPDATE fams_loss_report SET asset_version=?,recovered_at=?,recovery_snapshot_json=?,"
                + "report_status=?,version=version+1,updated_at=? WHERE id=?", next, facts.foundAt(),
                json.writeValueAsString(snapshot), closed ? "CONFIRMED_LOST" : "RECOVERED", now(), report);
        closeTodos(report);
        audit(actor, current, row, "LOSS_RECOVERED_TO_TRANSFER", number(row, "version") + 1, snapshot);
        return type.cast(closed
                ? rememberRecovery(current, operation, key, hash, report, id)
                : remember(current, operation, key, hash, header(report)));
    }

    /**
     * Requests admin approval to recover a confirmed-lost asset back into its originating factory
     * repair round. Same flow as {@link #requestRecovery} but the report must carry a factory
     * source and the frozen destination is {@code FACTORY_REPAIR}; the asset port validates the
     * factory-recovery state instead of loan preconditions.
     *
     * @param actor captured actor, must be an eligible loss applicant
     * @param id loss report id
     * @param version expected report version (If-Match)
     * @param key idempotency key identifying this request attempt
     * @param input factory-recovery request payload (asset version and recovery facts)
     * @return report view including the new recovery request, replayed identically on retry
     * @throws LossFailure with the codes of {@link #requestRecovery}, where
     *         {@code LOSS_RECOVERY_PURPOSE_CHANGED} also covers reports without a factory origin
     *         and the facts checks follow the factory recovery rules
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReportWithRecovery requestFactoryRecovery(Actor actor, long id, long version, UUID key,
            LossInput.ReturnRecoveryRequest input) {
        return requestRecovery(actor, id, version, key,
                new LossInput.RecoveryRequest(input.assetVersion(), input.facts()), false, "FACTORY_REPAIR");
    }

    /**
     * Requests admin approval to recover a confirmed-lost asset back into its originating internal
     * repair order. Same flow as {@link #requestRecovery} but the report must carry an internal
     * repair source and the frozen destination is {@code INTERNAL_REPAIR}; the asset port validates
     * the internal-repair recovery state instead of loan preconditions.
     *
     * @param actor captured actor, must be an eligible loss applicant
     * @param id loss report id
     * @param version expected report version (If-Match)
     * @param key idempotency key identifying this request attempt
     * @param input internal-repair recovery request payload (asset version and recovery facts)
     * @return report view including the new recovery request, replayed identically on retry
     * @throws LossFailure with the codes of {@link #requestRecovery}, where
     *         {@code LOSS_RECOVERY_PURPOSE_CHANGED} also covers reports without an internal repair
     *         origin and the facts checks follow the factory recovery rules
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReportWithRecovery requestInternalRepairRecovery(Actor actor, long id, long version, UUID key,
            LossInput.ReturnRecoveryRequest input) {
        return requestRecovery(actor, id, version, key,
                new LossInput.RecoveryRequest(input.assetVersion(), input.facts()), false, "INTERNAL_REPAIR");
    }

    /**
     * Recovers a searching-phase report by returning the found asset to its originating factory
     * repair round. Only the round's recorded processor may execute; the asset port restores the
     * factory-repair state and the factory repair service resumes the round in the same
     * transaction. The report moves to RECOVERED.
     *
     * @param actor captured actor, must hold the warehouse-operator role and be the round processor
     * @param id loss report id
     * @param version expected report version (If-Match)
     * @param key idempotency key identifying this execution attempt
     * @param input explicit recovery execution payload (ownership/asset/lock versions and facts)
     * @return the updated report view, replayed identically on retry
     * @throws LossFailure with code {@code LOSS_ROLE_REQUIRED}, {@code LOSS_NOT_FOUND},
     *         {@code LOSS_FACTORY_PROCESSOR_REQUIRED} when a different processor executes,
     *         {@code LOSS_REPORT_CHANGED} on version/status drift,
     *         {@code LOSS_PROCESSOR_OR_ASSET_CHANGED} when tracked versions drifted,
     *         {@code LOSS_LOCK_CHANGED} when the business lock moved, or the factory facts codes
     *         {@code LOSS_RECOVERY_TIME_INVALID} / {@code LOSS_RECOVERY_EVIDENCE_REQUIRED}
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReport recoverToFactory(Actor actor, long id, long version, UUID key,
            LossInput.ExplicitRecoveryExecution input) {
        return recoverToRetainedRepair(actor, id, version, key, input, false, false,
                LossViews.LossReport.class);
    }

    /**
     * Executes a claimed recovery request with the frozen destination {@code FACTORY_REPAIR}: same
     * factory-round handoff as {@link #recoverToFactory} but driven by the approved recovery
     * request, which must be in VERIFYING status. The request is completed and the parent report
     * stays in CONFIRMED_LOST while being stamped as recovered.
     *
     * @param actor captured actor, must hold the warehouse-operator role and be the round processor
     * @param id recovery request id
     * @param version expected recovery request version (If-Match)
     * @param key idempotency key identifying this execution attempt
     * @param input explicit recovery execution payload (ownership/asset/lock versions and facts)
     * @return report view with the completed recovery request, replayed identically on retry
     * @throws LossFailure with the codes of {@link #recoverToFactory}, plus
     *         {@code LOSS_RECOVERY_NOT_FOUND}, {@code LOSS_RECOVERY_CHANGED} on request
     *         version/status drift, {@code LOSS_ALREADY_RECOVERED} and
     *         {@code LOSS_RECOVERY_PURPOSE_CHANGED} when the frozen destination is not
     *         FACTORY_REPAIR
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReportWithRecovery executeRecoveryToFactory(Actor actor, long id, long version, UUID key,
            LossInput.ExplicitRecoveryExecution input) {
        return recoverToRetainedRepair(actor, id, version, key, input, true, false,
                LossViews.LossReportWithRecovery.class);
    }

    /**
     * Recovers a searching-phase report by returning the found asset to its originating internal
     * repair order. Only the order's recorded processor may execute; the asset port restores the
     * under-repair state and the internal repair service resumes the order in the same transaction.
     * The report moves to RECOVERED.
     *
     * @param actor captured actor, must hold the warehouse-operator role and be the repair
     *         processor
     * @param id loss report id
     * @param version expected report version (If-Match)
     * @param key idempotency key identifying this execution attempt
     * @param input explicit recovery execution payload (ownership/asset/lock versions and facts)
     * @return the updated report view, replayed identically on retry
     * @throws LossFailure with code {@code LOSS_ROLE_REQUIRED}, {@code LOSS_NOT_FOUND},
     *         {@code LOSS_INTERNAL_REPAIR_PROCESSOR_REQUIRED} when a different processor executes,
     *         {@code LOSS_REPORT_CHANGED} on version/status drift,
     *         {@code LOSS_PROCESSOR_OR_ASSET_CHANGED} when tracked versions drifted,
     *         {@code LOSS_LOCK_CHANGED} when the business lock moved, or the factory facts codes
     *         {@code LOSS_RECOVERY_TIME_INVALID} / {@code LOSS_RECOVERY_EVIDENCE_REQUIRED}
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReport recoverToInternalRepair(Actor actor, long id, long version, UUID key,
            LossInput.ExplicitRecoveryExecution input) {
        return recoverToRetainedRepair(actor, id, version, key, input, false, true,
                LossViews.LossReport.class);
    }

    /**
     * Executes a claimed recovery request with the frozen destination {@code INTERNAL_REPAIR}:
     * same internal-repair handoff as {@link #recoverToInternalRepair} but driven by the approved
     * recovery request, which must be in VERIFYING status. The request is completed and the parent
     * report stays in CONFIRMED_LOST while being stamped as recovered.
     *
     * @param actor captured actor, must hold the warehouse-operator role and be the repair
     *         processor
     * @param id recovery request id
     * @param version expected recovery request version (If-Match)
     * @param key idempotency key identifying this execution attempt
     * @param input explicit recovery execution payload (ownership/asset/lock versions and facts)
     * @return report view with the completed recovery request, replayed identically on retry
     * @throws LossFailure with the codes of {@link #recoverToInternalRepair}, plus
     *         {@code LOSS_RECOVERY_NOT_FOUND}, {@code LOSS_RECOVERY_CHANGED} on request
     *         version/status drift, {@code LOSS_ALREADY_RECOVERED} and
     *         {@code LOSS_RECOVERY_PURPOSE_CHANGED} when the frozen destination is not
     *         INTERNAL_REPAIR
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public LossViews.LossReportWithRecovery executeRecoveryToInternalRepair(Actor actor, long id, long version,
            UUID key, LossInput.ExplicitRecoveryExecution input) {
        return recoverToRetainedRepair(actor, id, version, key, input, true, true,
                LossViews.LossReportWithRecovery.class);
    }

    private <T> T recoverToRetainedRepair(Actor actor, long id, long version, UUID key,
            LossInput.ExplicitRecoveryExecution input, boolean closed, boolean internal, Class<T> type) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String prefix = internal ? "INTERNAL_REPAIR" : "FACTORY";
        String operation = (closed ? "RECOVERY_EXECUTE_TO_" : "RECOVER_TO_") + prefix + ":" + id;
        String destination = internal ? "INTERNAL_REPAIR" : "FACTORY_REPAIR";
        String hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash, type);
        if (prior != null) {
            return prior;
        }
        var row = closed ? recoveryParent(id) : header(id);
        var request = closed ? recoveryHeader(id) : row;
        Long owner = internal ? internalRepairProcessor(row) : factoryProcessor(row);
        if (owner == null || owner != current.id()) {
            throw new LossFailure(403, "LOSS_" + prefix + "_PROCESSOR_REQUIRED");
        }
        if (closed) {
            recoveryGuard(request, version, "VERIFYING");
            var frozen = json.readTree(request.get("request_snapshot_json").toString());
            if (!destination.equals(frozen.path("destination").asText())) {
                throw new LossFailure(409, "LOSS_RECOVERY_PURPOSE_CHANGED");
            }
            recoveryLock(row, id, input.lockVersion());
        } else {
            guard(row, version, "SEARCHING");
            lock(row, input.lockVersion());
        }
        processor(current, request, input.ownershipVersion());
        if (input.assetVersion() != number(request, "asset_version")) {
            throw new LossFailure(409, "LOSS_PROCESSOR_OR_ASSET_CHANGED");
        }
        var facts = input.facts();
        factoryRecoveryFacts(row, facts);
        long report = number(row, "id");
        long assetId = number(row, "asset_id");
        String exception = closed ? "LOST" : "SEARCHING";
        var source = json.readTree(row.get("source_snapshot_json").toString())
                .path(internal ? "internalRepairSource" : "factorySource");
        String repairId = source.path("repairId").asText();
        long next;
        if (internal) {
            next = assets.recoverForInternalRepair(assetId, input.assetVersion(), exception,
                    facts.observedAssetCode(), facts.observedSerialNumber(), facts.locationId(),
                    facts.locationVersion(), report, current.id(), now());
            internalRepairs.resumeFromLoss(actor, current, Long.parseLong(repairId), assetId, owner, report, id, next);
        } else {
            next = assets.recoverForFactory(assetId, input.assetVersion(), exception, facts.observedAssetCode(),
                    facts.observedSerialNumber(), facts.locationId(), facts.locationVersion(), report, current.id(),
                    now());
            factoryRepairs.resumeAfterLoss(actor, Long.parseLong(repairId),
                    Long.parseLong(source.path("roundId").asText()),
                    assetId, report, id, next);
        }
        var snapshot = Map.of("execution", input, "repairId", repairId, "destination", destination);
        String snapshotJson = json.writeValueAsString(snapshot);
        if (closed) {
            db.update("UPDATE fams_loss_recovery SET recovery_status='COMPLETED',execution_snapshot_json=?,"
                    + "version=version+1,updated_at=? WHERE id=?", snapshotJson, now(), id);
        }
        db.update("UPDATE fams_loss_report SET asset_version=?,recovered_at=?,recovery_snapshot_json=?,"
                + "report_status=?,version=version+1,updated_at=? WHERE id=?", next, facts.foundAt(),
                snapshotJson, closed ? "CONFIRMED_LOST" : "RECOVERED", now(), report);
        closeTodos(report);
        loans.refreshLiability(assetId);
        audit(actor, current, row, "LOSS_RECOVERED_TO_" + prefix, number(row, "version") + 1, snapshot);
        return type.cast(closed
                ? rememberRecovery(current, operation, key, hash, report, id)
                : remember(current, operation, key, hash, header(report)));
    }

    private void factoryRecoveryFacts(Map<String, Object> row, LossInput.RecoveryFacts facts) {
        validTime(facts.foundAt());
        if (facts.foundAt().isBefore(instant(row.get("last_seen_at")))
                || row.get("confirmed_lost_at") != null
                        && facts.foundAt().isBefore(instant(row.get("confirmed_lost_at")))) {
            throw new LossFailure(400, "LOSS_RECOVERY_TIME_INVALID");
        }
        if (!facts.physicalIdentityVerified() || facts.evidence().strip().length() < 10
                || !List.of("INTACT", "DAMAGED").contains(facts.condition())) {
            throw new LossFailure(400, "LOSS_RECOVERY_EVIDENCE_REQUIRED");
        }
    }

    private void transferRecoveryFacts(Map<String, Object> row, LossInput.RecoveryFacts facts) {
        validTime(facts.foundAt());
        if (facts.foundAt().isBefore(instant(row.get("last_seen_at")))
                || row.get("confirmed_lost_at") != null
                        && facts.foundAt().isBefore(instant(row.get("confirmed_lost_at")))) {
            throw new LossFailure(400, "LOSS_RECOVERY_TIME_INVALID");
        }
        if (!facts.physicalIdentityVerified() || facts.evidence().strip().length() < 10) {
            throw new LossFailure(400, "LOSS_RECOVERY_EVIDENCE_REQUIRED");
        }
        if (!"INTACT".equals(facts.condition())
                || facts.stillOriginalCustodian()
                        && java.util.Objects.equals(Long.toString(facts.locationId()),
                                json.readTree(row.get("source_snapshot_json").toString())
                                        .path("locationId").asText())) {
            throw new LossFailure(409, "LOSS_TRANSFER_RECOVERY_REQUIRED");
        }
    }

    private void returnRecoveryFacts(Map<String, Object> row, LossInput.RecoveryFacts facts) {
        validTime(facts.foundAt());
        if (facts.foundAt().isBefore(instant(row.get("last_seen_at")))
                || row.get("confirmed_lost_at") != null
                        && facts.foundAt().isBefore(instant(row.get("confirmed_lost_at")))) {
            throw new LossFailure(400, "LOSS_RECOVERY_TIME_INVALID");
        }
        if (!facts.physicalIdentityVerified() || facts.evidence().strip().length() < 10) {
            throw new LossFailure(400, "LOSS_RECOVERY_EVIDENCE_REQUIRED");
        }
        if (!"INTACT".equals(facts.condition()) || facts.stillOriginalCustodian()) {
            throw new LossFailure(409, "LOSS_RETURN_RECOVERY_REQUIRED");
        }
    }

    private void requireRecoveryCondition(Map<String, Object> request, String condition) {
        var frozen = json.readTree(request.get("request_snapshot_json").toString());
        if (frozen.has("destination") || !condition.equals(frozen.path("facts").path("condition").asText())) {
            throw new LossFailure(409, "LOSS_RECOVERY_PURPOSE_CHANGED");
        }
    }

    private void damagedRecoveryFacts(Map<String, Object> row, LossInput.RecoveryFacts facts) {
        validTime(facts.foundAt());
        if (facts.foundAt().isBefore(instant(row.get("last_seen_at")))
                || row.get("confirmed_lost_at") != null
                        && facts.foundAt().isBefore(instant(row.get("confirmed_lost_at")))) {
            throw new LossFailure(400, "LOSS_RECOVERY_TIME_INVALID");
        }
        if (!facts.physicalIdentityVerified() || facts.evidence().strip().length() < 10) {
            throw new LossFailure(400, "LOSS_RECOVERY_EVIDENCE_REQUIRED");
        }
        if (!"DAMAGED".equals(facts.condition()) || !facts.stillOriginalCustodian()) {
            throw new LossFailure(409, "LOSS_LINKED_RECOVERY_REQUIRED");
        }
    }

    private boolean applicant(Current current) {
        return current.has(Role.EMPLOYEE)
                || current.has(Role.DEPARTMENT_ASSET_MANAGER)
                || current.has(Role.ASSET_ADMIN)
                || current.has(Role.WAREHOUSE_OPERATOR);
    }

    private void recoveryFacts(Map<String, Object> row, LossInput.RecoveryFacts facts) {
        validTime(facts.foundAt());
        if (facts.foundAt().isBefore(instant(row.get("last_seen_at")))
                || row.get("confirmed_lost_at") != null
                        && facts.foundAt().isBefore(instant(row.get("confirmed_lost_at")))) {
            throw new LossFailure(400, "LOSS_RECOVERY_TIME_INVALID");
        }
        if (!facts.physicalIdentityVerified() || facts.evidence().strip().length() < 10) {
            throw new LossFailure(400, "LOSS_RECOVERY_EVIDENCE_REQUIRED");
        }
        if (!"INTACT".equals(facts.condition()) || !facts.stillOriginalCustodian()) {
            throw new LossFailure(409, "LOSS_LINKED_RECOVERY_REQUIRED");
        }
    }

    private long restoreOriginal(
            Map<String, Object> row,
            long version,
            String exception,
            Current actor,
            LossInput.RecoveryFacts facts) {
        var asset =
                assets.requireOriginalRecovery(
                        number(row, "asset_id"),
                        version,
                        exception,
                        row.get("source_snapshot_json").toString());
        loans.requireRecovery(
                number(row, "asset_id"),
                asset.lifecycleStatus(),
                asset.responsibleEmployeeId(),
                asset.usingOrganizationId());
        long next =
                assets.recoverOriginal(
                        number(row, "asset_id"),
                        version,
                        exception,
                        row.get("source_snapshot_json").toString(),
                        facts.observedAssetCode(),
                        facts.observedSerialNumber(),
                        facts.locationId(),
                        facts.locationVersion(),
                        number(row, "id"),
                        actor.id(),
                        now());
        loans.refreshLiability(number(row, "asset_id"));
        return next;
    }

    private Map<String, Object> recoveryParent(long recovery) {
        var refs = db.queryForList("SELECT report_id FROM fams_loss_recovery WHERE id=?", recovery);
        if (refs.isEmpty()) {
            throw new LossFailure(404, "LOSS_RECOVERY_NOT_FOUND");
        }
        var row = header(number(refs.getFirst(), "report_id"));
        guard(row, number(row, "version"), "CONFIRMED_LOST");
        if (row.get("recovered_at") != null) {
            throw new LossFailure(409, "LOSS_ALREADY_RECOVERED");
        }
        return row;
    }

    private Map<String, Object> recoveryHeader(long id) {
        var rows = db.queryForList("SELECT * FROM fams_loss_recovery WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new LossFailure(404, "LOSS_RECOVERY_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private void recoveryGuard(Map<String, Object> row, long version, String status) {
        if (number(row, "version") != version || !status.equals(row.get("recovery_status"))) {
            throw new LossFailure(409, "LOSS_RECOVERY_CHANGED");
        }
    }

    private void recoveryLock(Map<String, Object> row, long recovery, Long version) {
        var locks =
                db.queryForList(
                        "SELECT business_item_id,lock_version FROM fams_asset_business_lock WHERE"
                            + " asset_id=? AND business_type='LOST_REPORT' AND business_id=? AND"
                            + " released_at IS NULL FOR UPDATE",
                        row.get("asset_id"),
                        row.get("id"));
        if (locks.size() != 1
                || number(locks.getFirst(), "business_item_id") != recovery
                || version != null && number(locks.getFirst(), "lock_version") != version) {
            throw new LossFailure(409, "LOSS_LOCK_CHANGED");
        }
    }

    private void closeRecoveryTodos(long report) {
        db.update(
                "UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE"
                        + " business_type='LOST_REPORT' AND business_id=? AND todo_type IN"
                        + " ('RECOVERY_DECIDE','RECOVERY_CLAIM','RECOVERY_VERIFY') AND closed_at IS"
                        + " NULL",
                now(),
                report);
    }

    private void advanceRecoveryReport(
            Actor actor, Current current, Map<String, Object> row, String action, Object detail) {
        db.update(
                "UPDATE fams_loss_report SET version=version+1,updated_at=? WHERE id=?",
                now(),
                row.get("id"));
        audit(actor, current, row, action, number(row, "version") + 1, detail);
    }

    private LossViews.LossReportWithRecovery rememberRecovery(
            Current current, String operation, UUID key, String hash, long report, long recovery) {
        var row = header(report);
        var attempt = recoveryHeader(recovery);
        var result = LossViews.lossReportWithRecovery(
                json,
                row,
                Long.toString(recovery),
                ((Number) attempt.get("version")).longValue(),
                (String) attempt.get("recovery_status"));
        saveReceipt(current, operation, key, hash, report, result);
        return result;
    }

    private LossViews.LossRecovery recoveryView(Current current, Map<String, Object> row) {
        boolean pending = "PENDING_ADMIN_DECISION".equals(row.get("recovery_status"));
        return LossViews.lossRecovery(
                json,
                row,
                new LossViews.RecoveryActions(
                        pending && current.has(Role.ASSET_ADMIN),
                        pending
                                && (current.has(Role.ASSET_ADMIN)
                                        || current.id() == number(row, "creator_account_id")),
                        "WAITING_WAREHOUSE_CLAIM".equals(row.get("recovery_status"))
                                && current.has(Role.WAREHOUSE_OPERATOR),
                        "VERIFYING".equals(row.get("recovery_status"))
                                && current.has(Role.WAREHOUSE_OPERATOR)
                                && java.util.Objects.equals(
                                        current.id(), row.get("processor_account_id"))));
    }

    /**
     * Pre-upload authority check for loss photo evidence: only the report creator may upload
     * photos. Runs in its own repeatable-read transaction before the (transaction-less) malware
     * scan so the scan never holds a business transaction open.
     *
     * @param actor captured actor to authorize as the report creator
     * @param id loss report id the photo is destined for
     * @throws LossFailure with code {@code LOSS_NOT_FOUND} if the report does not exist or
     *         {@code LOSS_CREATOR_REQUIRED} if the caller is not the creator
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void photoUploader(Actor actor, long id) {
        var current = actors.revalidateLocked(actor);
        if (current.id() != number(header(id), "creator_account_id")) {
            throw new LossFailure(403, "LOSS_CREATOR_REQUIRED");
        }
    }

    /**
     * Read-access check for loss photo evidence used by grant issuance and content download: the
     * report must exist and be visible to the caller under the same scope rules as
     * {@link #get}. Runs inside the caller's mandatory transaction.
     *
     * @param actor captured actor whose visibility scope is revalidated under lock
     * @param id loss report id owning the photo
     * @throws LossFailure with code {@code LOSS_NOT_FOUND} if the report does not exist or
     *         {@code LOSS_NOT_VISIBLE} if the caller is outside the report's visibility scope
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void photoAccess(Actor actor, long id) {
        visible(actors.revalidateLocked(actor), header(id));
    }

    /**
     * Registers a scanned quarantine attachment as photo evidence on a report awaiting the admin
     * decision. Re-checks creator authority and report state under lock, enforces the malware scan
     * verdict and the image-only format rule, records the attachment in the registry, bumps the
     * report version and writes the LOSS_PHOTO_ADDED audit event. Idempotent via the PHOTO
     * operation receipt keyed by filename, hash, mime and size.
     *
     * @param actor captured actor, must be the report creator
     * @param id loss report id
     * @param version expected report version (If-Match)
     * @param key idempotency key identifying this upload attempt
     * @param filename original filename as supplied by the uploader
     * @param attachment quarantined attachment with its scan verdict and detected format
     * @return receipt with the report id, attachment object id and the new report version
     * @throws LossFailure with code {@code LOSS_CREATOR_REQUIRED}, {@code LOSS_NOT_FOUND},
     *         {@code LOSS_REPORT_CHANGED} when the report left PENDING_ADMIN_DECISION or the
     *         version drifted, {@code LOSS_LOCK_CHANGED} when the business lock moved,
     *         {@code LOSS_PHOTO_SCAN_UNAVAILABLE} when the scanner was down,
     *         {@code LOSS_PHOTO_REJECTED} when the scan was not clean,
     *         {@code LOSS_PHOTO_IMAGE_REQUIRED} for non-image formats, or
     *         {@code LOSS_PHOTOS_UNAVAILABLE} when the attachment registry is not configured
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LossViews.PhotoReceipt registerPhoto(
            Actor actor,
            long id,
            long version,
            UUID key,
            String filename,
            AttachmentIntake.QuarantinedAttachment attachment) {
        var current = actors.revalidateLocked(actor);
        var row = header(id);
        if (current.id() != number(row, "creator_account_id")) {
            throw new LossFailure(403, "LOSS_CREATOR_REQUIRED");
        }
        String operation = "PHOTO:" + id,
                hash =
                        LossPayloads.fingerprint(
                                json,
                                operation,
                                version,
                                Map.of(
                                        "filename",
                                        filename,
                                        "sha256",
                                        attachment.file().sha256(),
                                        "mime",
                                        attachment.format().mime(),
                                        "size",
                                        attachment.file().size()));
        var prior = replay(current, operation, key, hash, LossViews.PhotoReceipt.class);
        if (prior != null) {
            return prior;
        }
        guard(row, version, "PENDING_ADMIN_DECISION");
        lock(row, null);
        if (attachment.scanResult() == MalwareScanner.Result.UNAVAILABLE) {
            throw new LossFailure(503, "LOSS_PHOTO_SCAN_UNAVAILABLE");
        }
        if (attachment.scanResult() != MalwareScanner.Result.CLEAN) {
            throw new LossFailure(422, "LOSS_PHOTO_REJECTED");
        }
        if (!attachment.format().image()) {
            throw new LossFailure(400, "LOSS_PHOTO_IMAGE_REQUIRED");
        }
        var registry = attachments.getIfAvailable();
        if (registry == null) {
            throw new LossFailure(503, "LOSS_PHOTOS_UNAVAILABLE");
        }
        registry.register("LOST_REPORT", id, version, current.id(), filename, attachment);
        db.update(
                "UPDATE fams_loss_report SET version=version+1,updated_at=? WHERE id=?", now(), id);
        var result =
                new LossViews.PhotoReceipt(
                        Long.toString(id),
                        attachment.file().id().toString(),
                        version + 1);
        audit(
                actor,
                current,
                row,
                "LOSS_PHOTO_ADDED",
                version + 1,
                Map.of(
                        "objectId",
                        attachment.file().id().toString(),
                        "sha256",
                        attachment.file().sha256()));
        saveReceipt(current, operation, key, hash, id, result);
        LOG.info("Loss photo registered for report {} by actor {}.", id, actor.id());
        return result;
    }

    private <T> T replay(Current current, String operation, UUID key, String hash, Class<T> type) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_loss_receipt WHERE actor_account_id=? AND operation=?"
                                + " AND request_id=? FOR UPDATE",
                        current.id(),
                        operation,
                        key.toString());
        if (rows.isEmpty()) {
            return null;
        }
        var row = rows.getFirst();
        visible(current, header(number(row, "report_id")));
        if (!hash.equals(row.get("payload_sha256"))) {
            throw new LossFailure(409, "LOSS_IDEMPOTENCY_CONFLICT");
        }
        return json.readValue(row.get("response_json").toString(), type);
    }

    private LossViews.LossReport remember(
            Current current, String operation, UUID key, String hash, Map<String, Object> row) {
        var result = LossViews.lossReport(json, row);
        saveReceipt(current, operation, key, hash, number(row, "id"), result);
        return result;
    }

    private void saveReceipt(
            Current current,
            String operation,
            UUID key,
            String hash,
            long id,
            Object result) {
        db.update(
                "INSERT INTO"
                    + " fams_loss_receipt(actor_account_id,operation,request_id,report_id,payload_sha256,"
                    + "response_json,created_at)"
                    + " VALUES (?,?,?,?,?,?,?)",
                current.id(),
                operation,
                key.toString(),
                id,
                hash,
                json.writeValueAsString(result),
                now());
    }

    private Map<String, Object> header(long id) {
        var rows = db.queryForList("SELECT * FROM fams_loss_report WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new LossFailure(404, "LOSS_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private void visible(Current current, Map<String, Object> row) {
        if (!(current.has(Role.ASSET_ADMIN)
                || current.id() == number(row, "creator_account_id")
                || java.util.Objects.equals(
                        current.employeeId(), row.get("responsible_employee_id"))
                || java.util.Objects.equals(
                        current.employeeId(), row.get("shared_responsible_employee_id"))
                || current.has(Role.DEPARTMENT_ASSET_MANAGER)
                        && java.util.Objects.equals(
                                current.organizationId(), row.get("using_organization_id"))
                || current.has(Role.WAREHOUSE_OPERATOR)
                        && row.get("approval_snapshot_json") != null)) {
            throw new LossFailure(403, "LOSS_NOT_VISIBLE");
        }
    }

    private void require(Current current, Role role) {
        if (!current.has(role)) {
            throw new LossFailure(403, "LOSS_ROLE_REQUIRED");
        }
    }

    private void guard(Map<String, Object> row, long version, String status) {
        if (version != number(row, "version") || !status.equals(row.get("report_status"))) {
            throw new LossFailure(409, "LOSS_REPORT_CHANGED");
        }
    }

    private void lock(Map<String, Object> row, Long version) {
        var rows =
                db.queryForList(
                        "SELECT lock_version FROM fams_asset_business_lock WHERE asset_id=? AND"
                            + " business_type='LOST_REPORT' AND business_id=? AND released_at IS"
                            + " NULL FOR UPDATE",
                        row.get("asset_id"),
                        row.get("id"));
        if (rows.size() != 1
                || version != null
                        && version.longValue() != number(rows.getFirst(), "lock_version")) {
            throw new LossFailure(409, "LOSS_LOCK_CHANGED");
        }
    }

    private void release(Map<String, Object> row, String reason) {
        if (db.update(
                        "UPDATE fams_asset_business_lock SET"
                            + " released_at=?,release_reason=?,lock_version=lock_version+1 WHERE"
                            + " asset_id=? AND business_type='LOST_REPORT' AND business_id=? AND"
                            + " released_at IS NULL",
                        now(),
                        reason,
                        row.get("asset_id"),
                        row.get("id"))
                != 1) {
            throw new LossFailure(409, "LOSS_LOCK_CHANGED");
        }
    }

    private void todo(long id, String kind, String pool, Long owner) {
        db.update(
                "INSERT INTO"
                    + " fams_todo(pool_type,assignee_account_id,business_type,business_id,"
                    + "todo_type,todo_status,opened_at)"
                    + " VALUES (?,?,'LOST_REPORT',?,?,'OPEN',?)",
                pool,
                owner,
                id,
                kind,
                now());
    }

    private void closeTodos(long id) {
        db.update(
                "UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE"
                        + " business_type='LOST_REPORT' AND business_id=? AND closed_at IS NULL",
                now(),
                id);
    }

    private void audit(
            Actor actor,
            Current current,
            Map<String, Object> row,
            String action,
            long after,
            Object detail) {
        String role = current.roles().stream().map(Enum::name).sorted().findFirst().orElseThrow();
        String body = json.writeValueAsString(detail);
        db.update(
                "INSERT INTO"
                    + " fams_loss_event(report_id,action,before_version,after_version,actor_account_id,"
                    + "actor_username,actor_role,detail_json,occurred_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?)",
                row.get("id"),
                action,
                row.get("version"),
                after,
                current.id(),
                current.username(),
                role,
                body,
                now());
        db.update(
                "INSERT INTO"
                    + " fams_audit_event(request_id,actor_account_id,actor_role,action,resource_type,resource_id,"
                    + "before_version,after_version,occurred_at,detail_json)"
                    + " VALUES (?,?,?,?,'LOST_REPORT',?,?,?,?,?)",
                actor.requestId().toString(),
                current.id(),
                role,
                action,
                row.get("id"),
                row.get("version"),
                after,
                now(),
                body);
    }

    /**
     * Frozen wire() projection of the attachment list rows surfaced under the photos key
     * (get() and the approval_snapshot_json freeze): no key matches the id stringification
     * rule, so only createdAt is rewritten (Timestamp/LocalDateTime become ISO instant
     * strings, anything else passes through).
     */
    public record Photo(
            String objectId,
            String filename,
            String contentType,
            long sizeBytes,
            String scanStatus,
            long businessVersion,
            Object createdAt) {
    }

    /**
     * Maps attachment metadata rows into the wire-facing photo projection, normalizing SQL
     * timestamp types into ISO-8601 UTC strings so the JSON shape is stable across drivers.
     *
     * @param rows attachment metadata rows listed for one loss report
     * @return immutable list of photo projections in the order supplied
     */
    public static List<Photo> photoProjection(List<AttachmentEvidencePort.Metadata> rows) {
        return rows.stream()
                .map(
                        row ->
                                new Photo(
                                        row.objectId(),
                                        row.filename(),
                                        row.contentType(),
                                        row.sizeBytes(),
                                        row.scanStatus(),
                                        row.businessVersion(),
                                        photoTime(row.createdAt())))
                .toList();
    }

    private static Object photoTime(Object value) {
        if (value instanceof java.sql.Timestamp time) {
            return time.toInstant().toString();
        }
        if (value instanceof java.time.LocalDateTime time) {
            return time.toInstant(java.time.ZoneOffset.UTC).toString();
        }
        return value;
    }

    private Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp time) {
            return time.toInstant();
        }
        return ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC);
    }

    private void validTime(Instant time) {
        if (!time.equals(time.truncatedTo(ChronoUnit.MILLIS))
                || time.isAfter(clock.instant().plusSeconds(2))) {
            throw new LossFailure(400, "LOSS_TIME_INVALID");
        }
    }

    private ZoneId activeReviewZone() {
        String snapshot =
                db.queryForObject(
                        "SELECT v.snapshot_json FROM fams_business_parameter_state s JOIN"
                                + " fams_business_parameter_version v ON v.id=s.current_version_id"
                                + " WHERE s.singleton_id=1 AND v.status='ACTIVE' FOR SHARE",
                        String.class);
        return ZoneId.of(json.readTree(snapshot).path("timezone").asText());
    }

    private tools.jackson.databind.node.ObjectNode reviewAudit(Object input, LocalDate date, ZoneId zone) {
        tools.jackson.databind.node.ObjectNode details = json.valueToTree(input);
        details.put("reviewDate", date.toString());
        details.put("reviewTimezone", zone.getId());
        return details;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private Long reference(tools.jackson.databind.JsonNode row, String key) {
        return row.path(key).isNull() ? null : row.path(key).asLong();
    }
}
