package com.fams.repair;

import com.fams.asset.AssetFactoryRepairPort;
import com.fams.attachment.AttachmentEvidencePort;
import com.fams.attachment.AttachmentIntake;
import com.fams.attachment.AttachmentRegistry;
import com.fams.attachment.MalwareScanner;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Factory-private data never enters the common repair event detail. */
@Service
@Profile("fams-product")
public class RepairFactoryService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final RequisitionActorContext actors;
    private final AssetFactoryRepairPort assets;
    private final RepairFactoryGuard factory;
    private final ObjectProvider<AttachmentEvidencePort> evidence;
    private final ObjectProvider<AttachmentRegistry> attachments;
    private final com.fams.asset.AssetFactoryReplacementPort replacements;
    private final com.fams.inbound.FactoryReplacementInboundPort replacementInbound;

    public RepairFactoryService(
            JdbcTemplate db,
            ObjectMapper json,
            Clock clock,
            RequisitionActorContext actors,
            AssetFactoryRepairPort assets,
            RepairFactoryGuard factory,
            ObjectProvider<AttachmentEvidencePort> evidence,
            ObjectProvider<AttachmentRegistry> attachments,
            com.fams.asset.AssetFactoryReplacementPort replacements,
            com.fams.inbound.FactoryReplacementInboundPort replacementInbound) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actors = actors;
        this.assets = assets;
        this.factory = factory;
        this.evidence = evidence;
        this.attachments = attachments;
        this.replacements = replacements;
        this.replacementInbound = replacementInbound;
    }

    /** Joins the original single administrator decision; it does not perform a second approval. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void approveInitially(Actor actor, long id, long beforeVersion, String reason) {
        approveInitially(actor, id, beforeVersion, reason, null, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void approveInitially(
            Actor actor,
            long id,
            long beforeVersion,
            String reason,
            String costPolicy,
            String costLimit) {
        var policy = RepairFactoryCostPolicy.parse(costPolicy, costLimit);
        var current = reader(actor);
        if (!current.has(Role.ASSET_ADMIN)) {
            throw new RepairFailure(403, "REPAIR_FACTORY_ADMIN_REQUIRED");
        }
        var row = header(id);
        if (number(row, "version") != beforeVersion + 1
                || !"INTERNAL_OR_FACTORY".equals(row.get("allowed_method"))
                || row.get("approval_snapshot_json") == null
                || !List.of("WAITING_WAREHOUSE_CLAIM", "WAREHOUSE_PROCESSING")
                        .contains(row.get("repair_status"))
                || scope(id, "PENDING") != null
                || scope(id, "APPROVED") != null) {
            throw new RepairFailure(409, "REPAIR_FACTORY_INITIAL_SCOPE_NOT_AVAILABLE");
        }
        var frozen = new LinkedHashMap<String, Object>();
        frozen.put("source", "INITIAL_SINGLE_REPAIR_DECISION");
        frozen.put("requestVersion", beforeVersion);
        frozen.put("allowedMethod", policy.method());
        frozen.put("costPolicy", policy.mode());
        frozen.put("costLimit", policy.limit() == null ? null : policy.limit().toPlainString());
        frozen.put("reason", reason.strip());
        frozen.put("repairApproval", json.readTree(row.get("approval_snapshot_json").toString()));
        String body = json.writeValueAsString(frozen);
        db.update(
                "INSERT INTO"
                    + " fams_repair_factory_scope(repair_id,scope_status,allowed_method,cost_policy,cost_limit,request_snapshot_json,request_reason,requested_by,requested_at,decision_snapshot_json,decision_reason,decided_by,decided_at)"
                    + " VALUES (?,'APPROVED',?,?,?,?,?,?,?,?,?,?,?)",
                id,
                policy.method(),
                policy.mode(),
                policy.limit(),
                body,
                reason.strip(),
                current.id(),
                now(),
                body,
                reason.strip(),
                current.id(),
                now());
        long scopeId = lastId();
        var before = new LinkedHashMap<>(row);
        before.put("version", beforeVersion);
        audit(
                actor,
                current,
                before,
                "FACTORY_SCOPE_APPROVED",
                scopeId,
                null,
                beforeVersion + 1,
                frozen,
                Role.ASSET_ADMIN);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, noRollbackFor = RepairConflict.class)
    public RepairViews.FactoryDetail get(Actor actor, long id) {
        var current = reader(actor);
        var row = header(id);
        readable(current, row);
        var pending = scope(id, "PENDING");
        var approved = scope(id, "APPROVED");
        var active = factory.active(id);
        boolean owner = owner(current, row);
        boolean processing = "WAREHOUSE_PROCESSING".equals(row.get("repair_status"));
        Long lockVersion = lockVersion(row);
        var pendingScope = pending == null ? null : RepairViews.factoryScope(json, pending);
        var approvedScope = approved == null ? null : RepairViews.factoryScope(json, approved);
        var activeRound = active == null ? null : roundView(active);
        var guardSummary = factory.summary(id);
        var summary = guardSummary == null ? null : RepairViews.factorySummary(guardSummary);
        var costSummary = costs().summary(id);
        return RepairViews.factoryDetail(
                row,
                lockVersion,
                pendingScope,
                approvedScope,
                activeRound,
                summary,
                evidence.getIfAvailable() != null,
                costSummary,
                new RepairViews.FactoryActions(
                        owner
                                && processing
                                && pending == null
                                && approved == null
                                && active == null
                                && handled(id)
                                && !outstanding(id),
                        current.has(Role.ASSET_ADMIN) && processing && pending != null,
                        processing && pending != null,
                        owner
                                && processing
                                && approved != null
                                && pending == null
                                && active == null
                                && handled(id)
                                && !outstanding(id),
                        owner
                                && processing
                                && active != null
                                && "PENDING_DISPATCH".equals(active.get("round_status")),
                        owner
                                && processing
                                && active != null
                                && "PENDING_DISPATCH".equals(active.get("round_status")),
                        owner
                                && processing
                                && active != null
                                && "DISPATCHED".equals(active.get("round_status"))
                                && !originalDestroyed(id, number(active, "id")),
                        owner && processing && active != null,
                        owner
                                && processing
                                && pending == null
                                && handled(id)
                                && !outstanding(id)));
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, noRollbackFor = RepairConflict.class)
    public RepairViews.Page<RepairViews.FactoryRoundView> rounds(
            Actor actor, long id, int page, int size) {
        var current = reader(actor);
        readable(current, header(id));
        return new RepairViews.Page<>(
                db
                        .queryForList(
                                "SELECT * FROM fams_repair_factory_round WHERE repair_id=?"
                                        + " ORDER BY id DESC LIMIT ? OFFSET ?",
                                id,
                                size,
                                ((long) page - 1) * size)
                        .stream()
                        .map(this::roundView)
                        .toList(),
                count("fams_repair_factory_round", id),
                page,
                size);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, noRollbackFor = RepairConflict.class)
    public RepairViews.FactoryRoundView round(Actor actor, long id, long round) {
        var current = reader(actor);
        var repair = header(id);
        readable(current, repair);
        var selected = round(id, round);
        var base = roundView(selected);
        boolean writable =
                owner(current, repair)
                        && "WAREHOUSE_PROCESSING".equals(repair.get("repair_status"));
        var actions =
                new RepairViews.FactoryRoundActions(
                        writable,
                        writable && selected.get("actual_cost") == null,
                        writable && selected.get("actual_cost") == null);
        var evidenceRows =
                RepairViews.factoryEvidences(
                        db.queryForList(
                                "SELECT"
                                    + " object_id,purpose,content_sha256,uploader_account_id,recorded_at"
                                    + " FROM fams_repair_factory_evidence WHERE repair_id=? AND"
                                    + " round_id=? ORDER BY recorded_at,object_id",
                                id,
                                round));
        var port = evidence.getIfAvailable();
        return base.withRoundDetail(
                actions,
                evidenceRows,
                port == null
                        ? List.of()
                        : fileProjection(port.list("REPAIR_FACTORY", round)));
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, noRollbackFor = RepairConflict.class)
    public RepairViews.Page<RepairViews.FactoryEventRow> history(
            Actor actor, long id, int page, int size) {
        var current = reader(actor);
        readable(current, header(id));
        return new RepairViews.Page<>(
                RepairViews.factoryEvents(
                        json,
                        db.queryForList(
                                "SELECT * FROM fams_repair_factory_event WHERE repair_id=?"
                                        + " ORDER BY id DESC LIMIT ? OFFSET ?",
                                id,
                                size,
                                ((long) page - 1) * size)),
                count("fams_repair_factory_event", id),
                page,
                size);
    }

    @Transactional(
            isolation = Isolation.REPEATABLE_READ,
            rollbackFor = Exception.class,
            noRollbackFor = RepairConflict.class)
    public RepairViews.FactoryReply requestScope(
            Actor actor, long id, long version, UUID key, RepairFactoryInput.ScopeRequest input) {
        var current = warehouse(actor);
        var row = header(id);
        requireOwner(current, row);
        String operation = "FACTORY_SCOPE_REQUEST:" + id;
        String fingerprint = hash(operation, version, input);
        var prior = replay(actor, current, row, operation, key, fingerprint, RepairViews.FactoryReply.class);
        if (prior != null) {
            return prior;
        }
        guard(
                actor,
                current,
                row,
                version,
                input.assetVersion(),
                input.ownershipVersion(),
                input.lockVersion());
        var policy = RepairFactoryCostPolicy.parse(input.costPolicy(), input.costLimit());
        boolean replacement = input.costPolicy() != null;
        if (scope(id, "PENDING") != null
                || !replacement && (scope(id, "APPROVED") != null || factory.active(id) != null)
                || !handled(id)
                || outstanding(id)) {
            throw new RepairFailure(409, "REPAIR_FACTORY_SCOPE_NOT_AVAILABLE");
        }
        var basis = basis(row, input.lockVersion());
        basis.put("costBasis", costs().basis(id));
        db.update(
                "INSERT INTO"
                    + " fams_repair_factory_scope(repair_id,request_snapshot_json,request_reason,requested_by,requested_at,allowed_method,cost_policy,cost_limit)"
                    + " VALUES (?,?,?,?,?,?,?,?)",
                id,
                json.writeValueAsString(basis),
                input.reason().strip(),
                current.id(),
                now(),
                policy.method(),
                policy.mode(),
                policy.limit());
        long scope = lastId();
        todo(id, "FACTORY_SCOPE_APPROVAL", "ASSET_ADMIN", null);
        advance(
                actor,
                current,
                row,
                "FACTORY_SCOPE_REQUESTED",
                scope,
                null,
                input,
                Role.WAREHOUSE_OPERATOR);
        return remember(current, operation, key, fingerprint, id, scope, null);
    }

    @Transactional(
            isolation = Isolation.REPEATABLE_READ,
            rollbackFor = Exception.class,
            noRollbackFor = RepairConflict.class)
    public RepairViews.FactoryReply decideScope(
            Actor actor, long id, long version, UUID key, RepairFactoryInput.ScopeDecision input) {
        var current = reader(actor);
        if (!current.has(Role.ASSET_ADMIN)) {
            throw new RepairFailure(403, "REPAIR_FACTORY_ADMIN_REQUIRED");
        }
        var row = header(id);
        String operation = "FACTORY_SCOPE_DECIDE:" + id;
        String fingerprint = hash(operation, version, input);
        var prior = replay(actor, current, row, operation, key, fingerprint, RepairViews.FactoryReply.class);
        if (prior != null) {
            return prior;
        }
        return endScope(actor, current, row, version, key, operation, fingerprint, input, false);
    }

    @Transactional(
            isolation = Isolation.REPEATABLE_READ,
            rollbackFor = Exception.class,
            noRollbackFor = RepairConflict.class)
    public RepairViews.FactoryReply withdrawScope(
            Actor actor, long id, long version, UUID key, RepairFactoryInput.ScopeDecision input) {
        var current = reader(actor);
        var row = header(id);
        readable(current, row);
        if (!"WITHDRAW".equals(input.result())) {
            throw new RepairFailure(400, "REPAIR_FACTORY_SCOPE_RESULT_INVALID");
        }
        String operation = "FACTORY_SCOPE_WITHDRAW:" + id;
        String fingerprint = hash(operation, version, input);
        var prior = replay(actor, current, row, operation, key, fingerprint, RepairViews.FactoryReply.class);
        if (prior != null) {
            return prior;
        }
        return endScope(actor, current, row, version, key, operation, fingerprint, input, true);
    }

    private RepairViews.FactoryReply endScope(
            Actor actor,
            Current current,
            Map<String, Object> row,
            long version,
            UUID key,
            String operation,
            String fingerprint,
            RepairFactoryInput.ScopeDecision input,
            boolean withdrawing) {
        processing(actor, current, row, version);
        var pending = scope(number(row, "id"), "PENDING");
        if (pending == null
                || number(pending, "id") != input.scopeId()
                || number(pending, "version") != input.scopeVersion()) {
            conflict(actor, current, row, "REPAIR_FACTORY_SCOPE_CHANGED");
        }
        if (!withdrawing && !List.of("APPROVE", "REJECT").contains(input.result())) {
            throw new RepairFailure(400, "REPAIR_FACTORY_SCOPE_RESULT_INVALID");
        }
        var original = json.readTree(pending.get("request_snapshot_json").toString());
        if ("APPROVE".equals(input.result())
                && (number(row, "asset_version") != original.path("assetVersion").asLong()
                        || number(row, "ownership_version")
                                != original.path("ownershipVersion").asLong()
                        || number(row, "processor_account_id")
                                != original.path("processorAccountId").asLong())) {
            conflict(actor, current, row, "REPAIR_FACTORY_SCOPE_BASIS_CHANGED");
        }
        if ("APPROVE".equals(input.result())) {
            var policy = costs().policy(pending);
            if (!policy.covers(costs().total(number(row, "id")))) {
                throw new RepairFailure(409, "REPAIR_FACTORY_COST_SCOPE_REQUIRED");
            }
            if (original.has("costBasis")
                    && !original.get("costBasis")
                            .equals(
                                    json.readTree(
                                            json.writeValueAsString(
                                                    costs().basis(number(row, "id")))))) {
                throw new RepairFailure(409, "REPAIR_FACTORY_SCOPE_BASIS_CHANGED");
            }
        }
        lock(row, original.path("lockVersion").asLong());
        var frozen = new LinkedHashMap<String, Object>();
        frozen.put("request", original);
        frozen.put("currentBasis", basis(row, original.path("lockVersion").asLong()));
        frozen.put("decision", input);
        frozen.put(
                "technicalOpinions",
                wire(
                        db.queryForList(
                                "SELECT"
                                    + " id,judgement,method,opinion_result,occurred_at,attachment_ids_json,actor_account_id"
                                    + " FROM fams_repair_opinion WHERE repair_id=? ORDER BY id",
                                row.get("id"))));
        String status =
                withdrawing
                        ? "WITHDRAWN"
                        : "APPROVE".equals(input.result()) ? "APPROVED" : "REJECTED";
        db.update(
                "UPDATE fams_repair_factory_scope SET"
                    + " scope_status=?,decision_snapshot_json=?,decision_reason=?,decided_by=?,decided_at=?,version=version+1"
                    + " WHERE id=? AND version=?",
                status,
                json.writeValueAsString(frozen),
                input.reason().strip(),
                current.id(),
                now(),
                input.scopeId(),
                input.scopeVersion());
        closeTodo(number(row, "id"), "FACTORY_SCOPE_APPROVAL");
        syncCostTodo(row);
        Role role = current.has(Role.ASSET_ADMIN) ? Role.ASSET_ADMIN : Role.WAREHOUSE_OPERATOR;
        advance(
                actor,
                current,
                row,
                "FACTORY_SCOPE_" + status,
                input.scopeId(),
                null,
                frozen,
                role);
        return remember(
                current, operation, key, fingerprint, number(row, "id"), input.scopeId(), null);
    }

    @Transactional(
            isolation = Isolation.REPEATABLE_READ,
            rollbackFor = Exception.class,
            noRollbackFor = RepairConflict.class)
    public RepairViews.FactoryReply createRound(
            Actor actor, long id, long version, UUID key, RepairFactoryInput.RoundCreation input) {
        var current = warehouse(actor);
        var row = header(id);
        requireOwner(current, row);
        String operation = "FACTORY_ROUND_CREATE:" + id;
        String fingerprint = hash(operation, version, input);
        var prior =
                replay(
                        actor,
                        current,
                        row,
                        operation,
                        key,
                        fingerprint,
                        RepairViews.FactoryReply.class);
        if (prior != null) {
            return prior;
        }
        guard(
                actor,
                current,
                row,
                version,
                input.assetVersion(),
                input.ownershipVersion(),
                input.lockVersion());
        var approved = scope(id, "APPROVED");
        if (approved == null
                || number(approved, "id") != input.scopeId()
                || number(approved, "version") != input.scopeVersion()
                || scope(id, "PENDING") != null
                || factory.active(id) != null
                || !handled(id)
                || outstanding(id)) {
            throw new RepairFailure(409, "REPAIR_FACTORY_ROUND_NOT_AVAILABLE");
        }
        LocalDate today = LocalDate.ofInstant(now(), ZoneId.of(row.get("timezone_id").toString()));
        if (input.expectedReturnDate().isBefore(today)
                || input.expectedReturnDate().getYear() > 9999) {
            throw new RepairFailure(400, "REPAIR_FACTORY_EXPECTED_DATE_INVALID");
        }
        var identity =
                assets.snapshot(number(row, "asset_id"), input.assetVersion(), "UNDER_REPAIR");
        int next =
                Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COALESCE(MAX(round_number),0)+1 FROM"
                                        + " fams_repair_factory_round WHERE repair_id=?",
                                Integer.class,
                                id));
        db.update(
                "INSERT INTO fams_repair_factory_round(repair_id,asset_id,scope_id,round_number,"
                    + "provider_name,service_contact,service_phone,expected_return_date,fault,original_identity_json,created_by,created_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                id,
                row.get("asset_id"),
                input.scopeId(),
                next,
                input.providerName().strip(),
                input.serviceContact().strip(),
                input.servicePhone().strip(),
                input.expectedReturnDate(),
                input.fault().strip(),
                json.writeValueAsString(identity),
                current.id(),
                now());
        long round = lastId();
        todo(id, "FACTORY_DISPATCH", "WAREHOUSE_OPERATOR", current.id());
        advance(
                actor,
                current,
                row,
                "FACTORY_ROUND_CREATED",
                input.scopeId(),
                round,
                input,
                Role.WAREHOUSE_OPERATOR);
        return remember(current, operation, key, fingerprint, id, input.scopeId(), round);
    }

    @Transactional(
            isolation = Isolation.REPEATABLE_READ,
            rollbackFor = Exception.class,
            noRollbackFor = RepairConflict.class)
    public RepairViews.FactoryReply cancelRound(
            Actor actor,
            long id,
            long roundId,
            long version,
            UUID key,
            RepairFactoryInput.RoundCancellation input) {
        var current = warehouse(actor);
        var row = header(id);
        requireOwner(current, row);
        String operation = "FACTORY_CANCEL:" + id + ":" + roundId;
        String fingerprint = hash(operation, version, input);
        var prior =
                replay(
                        actor,
                        current,
                        row,
                        operation,
                        key,
                        fingerprint,
                        RepairViews.FactoryReply.class);
        if (prior != null) {
            return prior;
        }
        guard(
                actor,
                current,
                row,
                version,
                input.assetVersion(),
                input.ownershipVersion(),
                input.lockVersion());
        var round = round(id, roundId);
        roundGuard(actor, current, row, round, input.roundVersion(), "PENDING_DISPATCH");
        assets.snapshot(number(row, "asset_id"), input.assetVersion(), "UNDER_REPAIR");
        db.update(
                "UPDATE fams_repair_factory_round SET round_status='CANCELLED',cancel_reason=?,"
                        + "version=version+1 WHERE id=?",
                input.reason().strip(),
                roundId);
        closeTodo(id, "FACTORY_DISPATCH");
        advance(
                actor,
                current,
                row,
                "FACTORY_ROUND_CANCELLED",
                number(round, "scope_id"),
                roundId,
                input,
                Role.WAREHOUSE_OPERATOR);
        return remember(
                current, operation, key, fingerprint, id, number(round, "scope_id"), roundId);
    }

    @Transactional(
            isolation = Isolation.REPEATABLE_READ,
            rollbackFor = Exception.class,
            noRollbackFor = RepairConflict.class)
    public RepairViews.FactoryReply dispatch(
            Actor actor,
            long id,
            long roundId,
            long version,
            UUID key,
            RepairFactoryInput.Dispatch input) {
        var current = warehouse(actor);
        var row = header(id);
        requireOwner(current, row);
        String operation = "FACTORY_DISPATCH:" + id + ":" + roundId;
        String fingerprint = hash(operation, version, input);
        var prior =
                replay(
                        actor,
                        current,
                        row,
                        operation,
                        key,
                        fingerprint,
                        RepairViews.FactoryReply.class);
        if (prior != null) {
            return prior;
        }
        guard(
                actor,
                current,
                row,
                version,
                input.assetVersion(),
                input.ownershipVersion(),
                input.lockVersion());
        var round = round(id, roundId);
        roundGuard(actor, current, row, round, input.roundVersion(), "PENDING_DISPATCH");
        if (scope(id, "PENDING") != null) {
            throw new RepairFailure(409, "REPAIR_FACTORY_SCOPE_PENDING");
        }
        var dispatchScope = costs().dispatchScope(id, round);
        physicalTime(input.occurredAt(), instant(round.get("created_at")));
        LocalDate due = LocalDate.parse(round.get("expected_return_date").toString());
        if (due.isBefore(
                LocalDate.ofInstant(
                        input.occurredAt(), ZoneId.of(row.get("timezone_id").toString())))) {
            throw new RepairFailure(400, "REPAIR_FACTORY_EXPECTED_DATE_INVALID");
        }
        selected(current, id, roundId, input.evidenceIds(), "FACTORY_ACK");
        long next =
                assets.move(
                        number(row, "asset_id"),
                        input.assetVersion(),
                        round.get("original_identity_json").toString(),
                        false,
                        input.observedCode(),
                        input.observedSerial(),
                        null,
                        null,
                        current.id(),
                        id,
                        roundId,
                        input.occurredAt(),
                        now());
        db.update(
                "UPDATE fams_repair_factory_round SET"
                    + " round_status='DISPATCHED',dispatched_at=?,dispatched_by=?,dispatch_evidence=?,dispatch_scope_id=?,version=version+1"
                    + " WHERE id=?",
                input.occurredAt(),
                current.id(),
                input.evidence().strip(),
                dispatchScope.get("id"),
                roundId);
        row.put("asset_version", next);
        closeTodo(id, "FACTORY_DISPATCH");
        todo(id, "FACTORY_RETURN", "WAREHOUSE_OPERATOR", current.id());
        advance(
                actor,
                current,
                row,
                "FACTORY_ROUND_DISPATCHED",
                number(round, "scope_id"),
                roundId,
                input,
                Role.WAREHOUSE_OPERATOR);
        return remember(
                current, operation, key, fingerprint, id, number(round, "scope_id"), roundId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Long dispatchedLossScrapVersion(long repair, long round, long asset) {
        var rows = db.queryForList("SELECT version FROM fams_repair_factory_round WHERE repair_id=? AND id=?"
                + " AND asset_id=? AND round_status='DISPATCHED' AND received_at IS NULL FOR UPDATE",
                Long.class, repair, round, asset);
        return rows.isEmpty() || originalDestroyed(repair, round) ? null : rows.getFirst();
    }

    public record ScrapEvidence(long roundId, long roundVersion, long replacementId, String method,
            String provider, Instant dispatchedAt, Instant observedAt, long recordedBy, String evidenceIdsJson) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public ScrapEvidence scrapEvidence(Actor actor, long repairId, long roundId, long replacementId,
            long repairVersion, long roundVersion, long assetVersion, long ownershipVersion, long lockVersion) {
        var current = warehouse(actor);
        var row = header(repairId);
        requireOwner(current, row);
        guard(actor, current, row, repairVersion, assetVersion, ownershipVersion, lockVersion);
        var round = round(repairId, roundId);
        roundGuard(actor, current, row, round, roundVersion, "DISPATCHED");
        assets.requireOriginal(assets.snapshot(number(row, "asset_id"), assetVersion, "FACTORY_REPAIR"),
                round.get("original_identity_json").toString());
        var proof = replacementScrapEvidence(repairId, roundId, replacementId, number(row, "asset_id"));
        if (proof == null) {
            throw new RepairFailure(409, "REPAIR_FACTORY_SCRAP_EVIDENCE_CHANGED");
        }
        requireScrapEvidenceObjects(current, repairId, proof);
        return proof;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ScrapEvidence replacementScrapEvidence(long repairId, long roundId, long replacementId, long assetId) {
        var rows = db.queryForList("SELECT r.version,r.provider_name,r.dispatched_at,p.original_disposition,p.observed_at,"
                + " p.recorded_by,p.evidence_ids_json FROM fams_repair_factory_round r"
                + " JOIN fams_repair_factory_replacement p ON p.repair_id=r.repair_id AND p.round_id=r.id"
                + " WHERE r.repair_id=? AND r.id=? AND r.asset_id=? AND r.round_status='DISPATCHED'"
                + " AND r.received_at IS NULL AND p.id=? AND p.original_asset_id=r.asset_id"
                + " AND p.original_disposition IN ('FACTORY_RETAINED','FACTORY_DESTROYED') FOR UPDATE",
                repairId, roundId, assetId, replacementId);
        if (rows.isEmpty()) {
            return null;
        }
        var row = rows.getFirst();
        return new ScrapEvidence(roundId, number(row, "version"), replacementId,
                row.get("original_disposition").toString(), row.get("provider_name").toString(),
                instant(row.get("dispatched_at")), instant(row.get("observed_at")), number(row, "recorded_by"),
                row.get("evidence_ids_json").toString());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireScrapEvidenceObjects(Current current, long repairId, ScrapEvidence proof) {
        var ids = new java.util.ArrayList<UUID>();
        json.readTree(proof.evidenceIdsJson()).forEach(node -> ids.add(UUID.fromString(node.asText())));
        selected(current, repairId, proof.roundId(), ids, "REPLACEMENT");
    }

    public record LossSource(long repairId, long roundId, long assetId, long assetVersion,
            long processorAccountId, Instant dispatchedAt) {}

    /** Locks the dispatched source until the caller finishes creating the report. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LossSource lossSource(Actor actor, long repairId, long roundId, long repairVersion,
            long roundVersion, long assetVersion, long ownershipVersion, long lockVersion) {
        var current = warehouse(actor);
        var row = header(repairId);
        requireOwner(current, row);
        guard(actor, current, row, repairVersion, assetVersion, ownershipVersion, lockVersion);
        var round = round(repairId, roundId);
        roundGuard(actor, current, row, round, roundVersion, "DISPATCHED");
        requireOriginalNotDestroyed(repairId, roundId);
        long assetId = number(row, "asset_id");
        assets.requireOriginal(assets.snapshot(assetId, assetVersion, "FACTORY_REPAIR"),
                round.get("original_identity_json").toString());
        return new LossSource(repairId, roundId, assetId, assetVersion, current.id(),
                instant(round.get("dispatched_at")));
    }

    /** Adopts the existing repair lock without changing the round's physical receipt facts. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void handoffToLoss(Actor actor, LossSource source, long reportId, long lockVersion) {
        var current = warehouse(actor);
        var row = header(source.repairId());
        requireOwner(current, row);
        var round = round(source.repairId(), source.roundId());
        if (source.processorAccountId() != current.id()
                || source.assetId() != number(row, "asset_id")
                || source.assetVersion() != number(row, "asset_version")
                || !"DISPATCHED".equals(round.get("round_status"))
                || source.assetId() != number(round, "asset_id")) {
            throw new RepairFailure(409, "REPAIR_FACTORY_LOSS_SOURCE_CHANGED");
        }
        if (db.update("UPDATE fams_asset_business_lock SET business_type='LOST_REPORT',"
                + "business_id=?,business_item_id=?,lock_version=lock_version+1"
                + " WHERE asset_id=? AND business_type='REPAIR' AND business_id=? AND business_item_id=?"
                + " AND lock_version=? AND released_at IS NULL", reportId, reportId, source.assetId(),
                source.repairId(), source.repairId(), lockVersion) != 1) {
            throw new RepairFailure(409, "REPAIR_FACTORY_LOCK_CHANGED");
        }
        closeTodo(source.repairId(), "FACTORY_RETURN");
        advance(actor, current, row, "FACTORY_LOSS_HANDED_OFF", number(round, "scope_id"),
                source.roundId(), Map.of("reportId", Long.toString(reportId)), Role.WAREHOUSE_OPERATOR);
    }

    /** Restores a rejected or withdrawn initial report to the original repair queue. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void restoreFromLoss(Actor actor, long repairId, long roundId, long assetId, long reportId) {
        var current = reader(actor);
        var row = header(repairId);
        restoreLossLock(actor, current, row, roundId, assetId, reportId, reportId,
                number(row, "asset_version"), "FACTORY_LOSS_RETURNED");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void resumeAfterLoss(Actor actor, long repairId, long roundId, long assetId,
            long reportId, long lossItemId, long assetVersion) {
        var current = warehouse(actor);
        var row = header(repairId);
        requireOwner(current, row);
        restoreLossLock(actor, current, row, roundId, assetId, reportId, lossItemId,
                assetVersion, "FACTORY_LOSS_RECOVERED");
    }

    private void restoreLossLock(Actor actor, Current current, Map<String, Object> row, long roundId,
            long assetId, long reportId, long lossItemId, long assetVersion, String action) {
        long repairId = number(row, "id");
        var round = round(repairId, roundId);
        if (!"WAREHOUSE_PROCESSING".equals(row.get("repair_status"))
                || number(row, "asset_id") != assetId
                || number(round, "asset_id") != assetId
                || !"DISPATCHED".equals(round.get("round_status"))) {
            throw new RepairFailure(409, "REPAIR_FACTORY_LOSS_SOURCE_CHANGED");
        }
        assets.requireOriginal(assets.snapshot(assetId, assetVersion, "FACTORY_REPAIR"),
                round.get("original_identity_json").toString());
        if (db.update("UPDATE fams_asset_business_lock SET business_type='REPAIR',"
                + "business_id=?,business_item_id=?,lock_version=lock_version+1"
                + " WHERE asset_id=? AND business_type='LOST_REPORT' AND business_id=? AND business_item_id=?"
                + " AND released_at IS NULL", repairId, repairId, assetId, reportId, lossItemId) != 1) {
            throw new RepairFailure(409, "REPAIR_FACTORY_LOCK_CHANGED");
        }
        row.put("asset_version", assetVersion);
        todo(repairId, "FACTORY_RETURN", "WAREHOUSE_OPERATOR", (Long) row.get("processor_account_id"));
        Role role = current.has(Role.ASSET_ADMIN) ? Role.ASSET_ADMIN : Role.WAREHOUSE_OPERATOR;
        advance(actor, current, row, action, number(round, "scope_id"), roundId,
                Map.of("reportId", Long.toString(reportId)), role);
    }

    @Transactional(
            isolation = Isolation.REPEATABLE_READ,
            rollbackFor = Exception.class,
            noRollbackFor = RepairConflict.class)
    public RepairViews.FactoryReply receive(
            Actor actor,
            long id,
            long roundId,
            long version,
            UUID key,
            RepairFactoryInput.OriginalReceipt input) {
        var current = warehouse(actor);
        var row = header(id);
        requireOwner(current, row);
        String operation = "FACTORY_RECEIVE:" + id + ":" + roundId;
        String fingerprint = hash(operation, version, input);
        var prior =
                replay(
                        actor,
                        current,
                        row,
                        operation,
                        key,
                        fingerprint,
                        RepairViews.FactoryReply.class);
        if (prior != null) {
            return prior;
        }
        guard(
                actor,
                current,
                row,
                version,
                input.assetVersion(),
                input.ownershipVersion(),
                input.lockVersion());
        var round = round(id, roundId);
        roundGuard(actor, current, row, round, input.roundVersion(), "DISPATCHED");
        requireOriginalNotDestroyed(id, roundId);
        if (!input.originalPhysicalIdentityChecked()
                || !List.of("RESTORED", "STILL_FAULTY").contains(input.conditionResult())) {
            throw new RepairFailure(400, "REPAIR_FACTORY_ORIGINAL_RECEIPT_REQUIRED");
        }
        BigDecimal actualCost =
                input.actualCost() == null
                        ? null
                        : RepairFactoryCostPolicy.amount(input.actualCost());
        BigDecimal recordedCost = (BigDecimal) round.get("actual_cost");
        if (actualCost == null && !Boolean.TRUE.equals(input.costUnassessed())
                || actualCost != null && Boolean.TRUE.equals(input.costUnassessed())) {
            throw new RepairFailure(400, "REPAIR_FACTORY_COST_ASSESSMENT_REQUIRED");
        }
        if (recordedCost != null && (actualCost == null || recordedCost.compareTo(actualCost) != 0)
                || actualCost != null && actualCost.signum() > 0 && recordedCost == null) {
            throw new RepairFailure(409, "REPAIR_FACTORY_COST_SCOPE_REQUIRED");
        }
        physicalTime(input.occurredAt(), instant(round.get("dispatched_at")));
        selected(current, id, roundId, input.evidenceIds(), "RECEIPT");
        long next =
                assets.move(
                        number(row, "asset_id"),
                        input.assetVersion(),
                        round.get("original_identity_json").toString(),
                        true,
                        input.observedCode(),
                        input.observedSerial(),
                        input.actualLocationId(),
                        input.locationVersion(),
                        current.id(),
                        id,
                        roundId,
                        input.occurredAt(),
                        now());
        db.update(
                "UPDATE fams_repair_factory_round SET"
                    + " round_status='RECEIVED',received_at=?,received_by=?,"
                    + "receive_location_id=?,receive_location_version=?,condition_result=?,treatment_result=?,actual_cost=?,version=version+1"
                    + " WHERE id=?",
                input.occurredAt(),
                current.id(),
                input.actualLocationId(),
                input.locationVersion(),
                input.conditionResult(),
                input.treatmentResult().strip(),
                actualCost,
                roundId);
        syncCostTodo(row);
        row.put("asset_version", next);
        closeTodo(id, "FACTORY_RETURN");
        advance(
                actor,
                current,
                row,
                "FACTORY_ROUND_RECEIVED",
                number(round, "scope_id"),
                roundId,
                input,
                Role.WAREHOUSE_OPERATOR);
        return remember(
                current, operation, key, fingerprint, id, number(round, "scope_id"), roundId);
    }

    private boolean originalDestroyed(long repairId, long roundId) {
        return !db.queryForList("SELECT id FROM fams_repair_factory_replacement"
                + " WHERE repair_id=? AND round_id=? AND original_disposition='FACTORY_DESTROYED' FOR UPDATE",
                repairId, roundId).isEmpty()
                || !db.queryForList("""
                        SELECT d.id FROM fams_initial_factory_disposition d
                        JOIN fams_repair_factory_round r ON r.id=d.round_id
                        WHERE r.repair_id=? AND r.id=? FOR UPDATE
                        """, repairId, roundId).isEmpty();
    }

    private void requireOriginalNotDestroyed(long repairId, long roundId) {
        if (originalDestroyed(repairId, roundId)) {
            throw new RepairFailure(409, "REPAIR_FACTORY_ORIGINAL_ALREADY_DESTROYED");
        }
    }

    private boolean replacementPhase(long id, Map<String, Object> round) {
        if (!List.of("DISPATCHED", "RECEIVED").contains(round.get("round_status"))
                || originalDestroyed(id, number(round, "id"))
                || !db.queryForList("SELECT id FROM fams_repair_factory_replacement"
                        + " WHERE round_id=?", round.get("id")).isEmpty()) {
            return false;
        }
        var latest = db.queryForList("SELECT id FROM fams_repair_factory_round WHERE repair_id=?"
                + " ORDER BY id DESC LIMIT 1 FOR UPDATE", id);
        return !latest.isEmpty() && number(latest.getFirst(), "id") == number(round, "id");
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, noRollbackFor = RepairConflict.class)
    public RepairViews.FactoryReplacementsPage replacements(Actor actor, long id, long roundId) {
        var current = reader(actor);
        var row = header(id);
        readable(current, row);
        var round = round(id, roundId);
        var items = db.queryForList("SELECT * FROM fams_repair_factory_replacement"
                + " WHERE repair_id=? AND round_id=?", id, roundId);
        boolean processing = owner(current, row) && "WAREHOUSE_PROCESSING".equals(row.get("repair_status"));
        return new RepairViews.FactoryReplacementsPage(
                items.stream().map(item -> replacementView(actor, item)).toList(),
                processing && replacementPhase(id, round),
                processing && "DISPATCHED".equals(round.get("round_status"))
                        && lockVersion(row) != null
                        && items.stream().anyMatch(item -> List.of("FACTORY_RETAINED", "FACTORY_DESTROYED")
                                .contains(item.get("original_disposition"))));
    }

    private RepairViews.FactoryReplacementRow replacementView(
            Actor actor, Map<String, Object> item) {
        var inbound = replacementInbound.context(actor, item.get("source_id").toString());
        return RepairViews.factoryReplacement(json, item, inbound);
    }

    @Transactional(
            isolation = Isolation.REPEATABLE_READ,
            rollbackFor = Exception.class,
            noRollbackFor = RepairConflict.class)
    public RepairViews.FactoryReply recordReplacement(Actor actor, long id, long roundId, long version,
            UUID key, RepairFactoryInput.ReplacementFact input) {
        var current = warehouse(actor);
        var row = header(id);
        requireOwner(current, row);
        String operation = "FACTORY_REPLACEMENT:" + id + ":" + roundId;
        String fingerprint = hash(operation, version, input);
        var prior =
                replay(
                        actor,
                        current,
                        row,
                        operation,
                        key,
                        fingerprint,
                        RepairViews.FactoryReply.class);
        if (prior != null) { return prior; }
        if (!Boolean.TRUE.equals(input.replacementPhysicalIdentityChecked())) {
            throw new RepairFailure(400, "REPAIR_FACTORY_REPLACEMENT_IDENTITY_REQUIRED");
        }
        guard(actor, current, row, version, input.assetVersion(), input.ownershipVersion(), input.lockVersion());
        var round = round(id, roundId);
        if (number(round, "version") != input.roundVersion() || !replacementPhase(id, round)) {
            throw new RepairFailure(409, "REPAIR_FACTORY_REPLACEMENT_PHASE_INVALID");
        }
        if (!List.of("FACTORY_RETAINED", "FACTORY_DESTROYED", "CONTINUES_REPAIR")
                    .contains(input.originalDisposition())
                || "RECEIVED".equals(round.get("round_status"))
                    && !"CONTINUES_REPAIR".equals(input.originalDisposition())) {
            throw new RepairFailure(400, "REPAIR_FACTORY_REPLACEMENT_DISPOSITION_INVALID");
        }
        physicalTime(input.observedAt(), instant(round.get("dispatched_at")));
        if (input.observedAt().isAfter(now())) {
            throw new RepairFailure(400, "REPAIR_FACTORY_REPLACEMENT_TIME_INVALID");
        }
        selected(current, id, roundId, input.evidenceIds(), "REPLACEMENT");
        UUID source = UUID.randomUUID();
        var created = replacements.create(actor, source, number(row, "asset_id"), input.assetVersion(),
                id, roundId, input.newAsset());
        long asset = Long.parseLong(created.id());
        var inbound = replacementInbound.create(actor, source, number(row, "asset_id"), asset,
                created.version(), id, roundId, input.businessDate(),
                input.warehouseId(), input.warehouseVersion(), input.locationId(), input.locationVersion());
        db.update("INSERT INTO fams_repair_factory_replacement(source_id,repair_id,round_id,"
                        + "original_asset_id,replacement_asset_id,inbound_receipt_id,original_disposition,"
                        + "observed_at,evidence_ids_json,snapshot_json,recorded_by,recorded_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", source.toString(), id, roundId,
                row.get("asset_id"), asset, inbound.id(), input.originalDisposition(),
                input.observedAt(), json.writeValueAsString(input.evidenceIds()),
                json.writeValueAsString(input), current.id(), now());
        long replacement = Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        db.update("UPDATE fams_repair_factory_round SET version=version+1 WHERE id=?", roundId);
        advance(actor, current, row, "FACTORY_REPLACEMENT_RECORDED", number(round, "scope_id"), roundId,
                Map.of("replacementId", Long.toString(replacement),
                        "originalDisposition", input.originalDisposition()), Role.WAREHOUSE_OPERATOR);
        var result =
                reply(
                        header(id),
                        number(round, "scope_id"),
                        roundId,
                        null,
                        Long.toString(replacement),
                        source.toString(),
                        created.id(),
                        inbound.id(),
                        null);
        save(current, operation, key, fingerprint, id, result);
        return result;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, noRollbackFor = RepairConflict.class)
    public RepairViews.FactoryPartsPage parts(
            Actor actor, long id, long roundId, int page, int size) {
        var current = reader(actor);
        var row = header(id);
        readable(current, row);
        var round = round(id, roundId);
        var items =
                db.queryForList(
                        "SELECT * FROM fams_repair_factory_parts_change WHERE repair_id=? AND"
                                + " round_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
                        id,
                        roundId,
                        size,
                        (page - 1L) * size);
        var views =
                items.stream().map(item -> RepairViews.factoryPart(json, item, roundId)).toList();
        return new RepairViews.FactoryPartsPage(
                page,
                size,
                Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM fams_repair_factory_parts_change WHERE"
                                    + " repair_id=? AND round_id=?",
                                Long.class,
                                id,
                                roundId),
                        "PARTS_COUNT_MISSING"),
                views,
                owner(current, row)
                        && "WAREHOUSE_PROCESSING".equals(row.get("repair_status"))
                        && partsPhase(id, round));
    }

    private boolean partsPhase(long id, Map<String, Object> round) {
        if (!"RECEIVED".equals(round.get("round_status"))
                || factory.active(id) != null
                || !db.queryForList(
                                "SELECT id FROM fams_repair_return_handover WHERE repair_id=? AND"
                                        + " handover_status IN ('WAITING_RECEIPT','REFUSED') FOR"
                                        + " UPDATE",
                                id)
                        .isEmpty()
                || db.queryForList(
                                "SELECT repair_id FROM fams_repair_physical_handling"
                                        + " WHERE repair_id=? FOR UPDATE",
                                id)
                        .isEmpty()) {
            return false;
        }
        var latest =
                db.queryForList(
                        "SELECT id FROM fams_repair_factory_round WHERE repair_id=? AND"
                                + " round_status='RECEIVED' ORDER BY id DESC LIMIT 1 FOR UPDATE",
                        id);
        return !latest.isEmpty() && number(round, "id") == number(latest.getFirst(), "id");
    }

    @Transactional(
            isolation = Isolation.REPEATABLE_READ,
            rollbackFor = Exception.class,
            noRollbackFor = RepairConflict.class)
    public RepairViews.FactoryReply recordParts(
            Actor actor,
            long id,
            long roundId,
            long version,
            UUID key,
            RepairFactoryInput.PartsFact input) {
        var current = warehouse(actor);
        var row = header(id);
        requireOwner(current, row);
        String operation = "FACTORY_PARTS:" + id + ":" + roundId;
        String fingerprint = hash(operation, version, input);
        var prior =
                replay(
                        actor,
                        current,
                        row,
                        operation,
                        key,
                        fingerprint,
                        RepairViews.FactoryReply.class);
        if (prior != null) {
            return prior;
        }
        guard(
                actor,
                current,
                row,
                version,
                input.assetVersion(),
                input.ownershipVersion(),
                input.lockVersion());
        var round = round(id, roundId);
        roundGuard(actor, current, row, round, input.roundVersion(), "RECEIVED");
        if (!partsPhase(id, round)) {
            throw new RepairFailure(409, "REPAIR_FACTORY_PARTS_PHASE_INVALID");
        }
        if (!Boolean.TRUE.equals(input.originalPhysicalIdentityChecked())) {
            throw new RepairFailure(400, "REPAIR_FACTORY_ORIGINAL_RECEIPT_REQUIRED");
        }
        String before = input.beforeInfo().strip();
        String after = input.afterInfo().strip();
        String name = input.partName().strip();
        if (before.equals(after)) {
            throw new RepairFailure(400, "REPAIR_FACTORY_PARTS_CHANGE_REQUIRED");
        }
        Instant earliest = instant(round.get("received_at"));
        var recollected =
                db.queryForList(
                        "SELECT r.occurred_at FROM fams_repair_return_recollection r JOIN"
                                + " fams_repair_return_handover h ON h.id=r.handover_id WHERE"
                                + " h.repair_id=? ORDER BY r.occurred_at DESC LIMIT 1 FOR UPDATE",
                        id);
        if (!recollected.isEmpty()
                && instant(recollected.getFirst().get("occurred_at")).isAfter(earliest)) {
            earliest = instant(recollected.getFirst().get("occurred_at"));
        }
        physicalTime(input.observedAt(), earliest);
        if (input.observedAt().isAfter(now())) {
            throw new RepairFailure(400, "REPAIR_FACTORY_PARTS_TIME_INVALID");
        }
        selected(current, id, roundId, input.evidenceIds(), "PARTS_CHANGE");
        Instant recordedAt = now();
        db.update(
                "INSERT INTO fams_repair_factory_parts_change(repair_id,round_id,asset_id,"
                        + "part_name,before_info,after_info,observed_at,evidence_ids_json,"
                        + "snapshot_json,recorded_by,recorded_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id,
                roundId,
                row.get("asset_id"),
                name,
                before,
                after,
                input.observedAt(),
                json.writeValueAsString(input.evidenceIds()),
                json.writeValueAsString(input),
                current.id(),
                recordedAt);
        long change =
                Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        long next =
                assets.recordPartsObservation(
                        number(row, "asset_id"),
                        input.assetVersion(),
                        round.get("original_identity_json").toString(),
                        input.observedCode(),
                        input.observedSerial(),
                        current.id(),
                        id,
                        roundId,
                        change,
                        input.observedAt(),
                        recordedAt);
        row.put("asset_version", next);
        db.update("UPDATE fams_repair_factory_round SET version=version+1 WHERE id=?", roundId);
        advance(
                actor,
                current,
                row,
                "FACTORY_PARTS_OBSERVED",
                number(round, "scope_id"),
                roundId,
                Map.of("input", input, "partsChangeId", Long.toString(change)),
                Role.WAREHOUSE_OPERATOR);
        var result =
                reply(
                        header(id),
                        number(round, "scope_id"),
                        roundId,
                        Long.toString(change),
                        null,
                        null,
                        null,
                        null,
                        null);
        save(current, operation, key, fingerprint, id, result);
        return result;
    }

    @Transactional(
            isolation = Isolation.REPEATABLE_READ,
            rollbackFor = Exception.class,
            noRollbackFor = RepairConflict.class)
    public RepairViews.FactoryReply recordQuote(
            Actor actor,
            long id,
            long roundId,
            long version,
            UUID key,
            RepairFactoryInput.MoneyFact input) {
        return recordMoney(actor, id, roundId, version, key, input, false);
    }

    @Transactional(
            isolation = Isolation.REPEATABLE_READ,
            rollbackFor = Exception.class,
            noRollbackFor = RepairConflict.class)
    public RepairViews.FactoryReply recordCost(
            Actor actor,
            long id,
            long roundId,
            long version,
            UUID key,
            RepairFactoryInput.MoneyFact input) {
        return recordMoney(actor, id, roundId, version, key, input, true);
    }

    private RepairViews.FactoryReply recordMoney(
            Actor actor,
            long id,
            long roundId,
            long version,
            UUID key,
            RepairFactoryInput.MoneyFact input,
            boolean actual) {
        var current = warehouse(actor);
        var row = header(id);
        requireOwner(current, row);
        String operation = (actual ? "FACTORY_COST:" : "FACTORY_QUOTE:") + id + ":" + roundId;
        String fingerprint = hash(operation, version, input);
        var prior =
                replay(
                        actor,
                        current,
                        row,
                        operation,
                        key,
                        fingerprint,
                        RepairViews.FactoryReply.class);
        if (prior != null) {
            return prior;
        }
        if (!Boolean.TRUE.equals(input.finalAmountChecked())) {
            throw new RepairFailure(400, "REPAIR_FACTORY_COST_ASSESSMENT_REQUIRED");
        }
        guard(
                actor,
                current,
                row,
                version,
                input.assetVersion(),
                input.ownershipVersion(),
                input.lockVersion());
        var round = round(id, roundId);
        if (number(round, "version") != input.roundVersion()) {
            conflict(actor, current, row, "REPAIR_FACTORY_ROUND_CHANGED");
        }
        if (round.get("actual_cost") != null) {
            throw new RepairFailure(409, "REPAIR_FACTORY_FINAL_COST_ALREADY_RECORDED");
        }
        var amount = RepairFactoryCostPolicy.amount(input.amount());
        if (!actual || amount.signum() > 0) {
            if (input.evidenceIds().isEmpty()) {
                throw new RepairFailure(400, "REPAIR_FACTORY_COST_ORIGINAL_REQUIRED");
            }
            selected(current, id, roundId, input.evidenceIds(), actual ? "COST_RECEIPT" : "QUOTE");
        } else if (!input.evidenceIds().isEmpty()) {
            selected(current, id, roundId, input.evidenceIds(), "COST_RECEIPT");
        }
        var frozen = new LinkedHashMap<String, Object>();
        frozen.put("input", input);
        frozen.put("authorization", wire(costs().approved(id)));
        frozen.put("previousCostBasis", costs().basis(id));
        if (actual) {
            db.update(
                    "INSERT INTO"
                        + " fams_repair_factory_cost(round_id,amount,evidence_ids_json,snapshot_json,recorded_by,recorded_at)"
                        + " VALUES (?,?,?,?,?,?)",
                    roundId,
                    amount,
                    json.writeValueAsString(input.evidenceIds()),
                    json.writeValueAsString(frozen),
                    current.id(),
                    now());
            db.update(
                    "UPDATE fams_repair_factory_round SET actual_cost=?,version=version+1 WHERE"
                            + " id=?",
                    amount,
                    roundId);
        } else {
            db.update(
                    "INSERT INTO"
                        + " fams_repair_factory_quote(round_id,amount,evidence_ids_json,snapshot_json,created_by,created_at)"
                        + " VALUES (?,?,?,?,?,?)",
                    roundId,
                    amount,
                    json.writeValueAsString(input.evidenceIds()),
                    json.writeValueAsString(frozen),
                    current.id(),
                    now());
            db.update("UPDATE fams_repair_factory_round SET version=version+1 WHERE id=?", roundId);
        }
        syncCostTodo(row);
        advance(
                actor,
                current,
                row,
                actual ? "FACTORY_COST_RECORDED" : "FACTORY_QUOTE_RECORDED",
                number(round, "scope_id"),
                roundId,
                frozen,
                Role.WAREHOUSE_OPERATOR);
        return remember(
                current, operation, key, fingerprint, id, number(round, "scope_id"), roundId);
    }

    private RepairFactoryCosts costs() {
        return new RepairFactoryCosts(db);
    }

    private void syncCostTodo(Map<String, Object> row) {
        long id = number(row, "id");
        closeTodo(id, "FACTORY_COST_FOLLOWUP");
        if (costs().blocked(id)) {
            todo(
                    id,
                    "FACTORY_COST_FOLLOWUP",
                    "WAREHOUSE_OPERATOR",
                    number(row, "processor_account_id"));
        }
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, noRollbackFor = RepairConflict.class)
    public Current evidenceAccess(Actor actor, long id, long roundId, UUID object) {
        var current = reader(actor);
        readable(current, header(id));
        round(id, roundId);
        if (db.queryForList(
                        "SELECT object_id FROM fams_repair_factory_evidence WHERE repair_id=?"
                                + " AND round_id=? AND object_id=?",
                        id,
                        roundId,
                        object.toString())
                .isEmpty()) {
            throw new RepairFailure(404, "REPAIR_FACTORY_EVIDENCE_NOT_FOUND");
        }
        return current;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, noRollbackFor = RepairConflict.class)
    public void uploader(Actor actor, long id, long roundId) {
        var current = warehouse(actor);
        var row = header(id);
        requireOwner(current, row);
        round(id, roundId);
        // Phase checks follow permanent replay inside registration, after bytes are fingerprinted.
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RepairConflict.class)
    public RepairViews.FactoryReply registerEvidence(
            Actor actor,
            long id,
            long roundId,
            long version,
            long roundVersion,
            UUID key,
            long assetVersion,
            long ownershipVersion,
            long lockVersion,
            String purpose,
            String filename,
            AttachmentIntake.QuarantinedAttachment attachment) {
        var current = warehouse(actor);
        var row = header(id);
        requireOwner(current, row);
        String operation = "FACTORY_EVIDENCE:" + id + ":" + roundId;
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("roundVersion", roundVersion);
        metadata.put("assetVersion", assetVersion);
        metadata.put("ownershipVersion", ownershipVersion);
        metadata.put("lockVersion", lockVersion);
        metadata.put("purpose", purpose);
        metadata.put("filename", filename);
        metadata.put("sha256", attachment.file().sha256());
        metadata.put("mime", attachment.format().mime());
        metadata.put("size", attachment.file().size());
        String fingerprint = hash(operation, version, metadata);
        var prior =
                replay(
                        actor,
                        current,
                        row,
                        operation,
                        key,
                        fingerprint,
                        RepairViews.FactoryReply.class);
        if (prior != null) {
            return prior;
        }
        guard(actor, current, row, version, assetVersion, ownershipVersion, lockVersion);
        var round = round(id, roundId);
        if (number(round, "version") != roundVersion) {
            conflict(actor, current, row, "REPAIR_FACTORY_ROUND_CHANGED");
        }
        boolean financial = "QUOTE".equals(purpose) || "COST_RECEIPT".equals(purpose);
        boolean parts = "PARTS_CHANGE".equals(purpose);
        boolean replacement = "REPLACEMENT".equals(purpose);
        if (replacement && !replacementPhase(id, round)) {
            throw new RepairFailure(409, "REPAIR_FACTORY_REPLACEMENT_PHASE_INVALID");
        }
        if (parts && !partsPhase(id, round)) {
            throw new RepairFailure(409, "REPAIR_FACTORY_PARTS_PHASE_INVALID");
        }
        if (!financial
                && !parts
                && !replacement
                && (!List.of("PENDING_DISPATCH", "DISPATCHED").contains(round.get("round_status"))
                        || !List.of("FAULT", "FACTORY_ACK", "RECEIPT").contains(purpose)
                        || "RECEIPT".equals(purpose)
                                && !"DISPATCHED".equals(round.get("round_status")))) {
            throw new RepairFailure(409, "REPAIR_FACTORY_EVIDENCE_PHASE_INVALID");
        }
        if (attachment.scanResult() == MalwareScanner.Result.UNAVAILABLE) {
            throw new RepairFailure(503, "REPAIR_FACTORY_SCAN_UNAVAILABLE");
        }
        if (attachment.scanResult() != MalwareScanner.Result.CLEAN) {
            throw new RepairFailure(422, "REPAIR_FACTORY_EVIDENCE_REJECTED");
        }
        var registry = attachments.getIfAvailable();
        if (registry == null) {
            throw new RepairFailure(503, "REPAIR_FACTORY_EVIDENCE_UNAVAILABLE");
        }
        registry.register(
                "REPAIR_FACTORY", roundId, roundVersion, current.id(), filename, attachment);
        db.update(
                "INSERT INTO fams_repair_factory_evidence(object_id,repair_id,round_id,purpose,"
                        + "content_sha256,uploader_account_id,recorded_at) VALUES (?,?,?,?,?,?,?)",
                attachment.file().id().toString(),
                id,
                roundId,
                purpose,
                attachment.file().sha256(),
                current.id(),
                now());
        db.update("UPDATE fams_repair_factory_round SET version=version+1 WHERE id=?", roundId);
        advance(
                actor,
                current,
                row,
                "FACTORY_EVIDENCE_ADDED",
                number(round, "scope_id"),
                roundId,
                metadata,
                Role.WAREHOUSE_OPERATOR);
        var result =
                reply(
                        header(id),
                        number(round, "scope_id"),
                        roundId,
                        null,
                        null,
                        null,
                        null,
                        null,
                        attachment.file().id().toString());
        save(current, operation, key, fingerprint, id, result);
        return result;
    }

    private Current reader(Actor actor) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(Role.ASSET_ADMIN) && !current.has(Role.WAREHOUSE_OPERATOR)) {
            throw new RepairFailure(403, "REPAIR_FACTORY_PRIVATE_ACCESS_REQUIRED");
        }
        return current;
    }

    private Current warehouse(Actor actor) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(Role.WAREHOUSE_OPERATOR)) {
            throw new RepairFailure(403, "REPAIR_FACTORY_WAREHOUSE_REQUIRED");
        }
        return current;
    }

    private boolean owner(Current actor, Map<String, Object> row) {
        return actor.has(Role.WAREHOUSE_OPERATOR)
                && row.get("processor_account_id") != null
                && actor.id() == number(row, "processor_account_id");
    }

    private void requireOwner(Current current, Map<String, Object> row) {
        if (!owner(current, row)) {
            throw new RepairFailure(403, "REPAIR_FACTORY_NOT_PROCESSOR");
        }
    }

    private void readable(Current current, Map<String, Object> row) {
        if (!current.has(Role.ASSET_ADMIN) && !owner(current, row)) {
            throw new RepairFailure(403, "REPAIR_FACTORY_PRIVATE_ACCESS_REQUIRED");
        }
    }

    private Map<String, Object> header(long id) {
        var rows = db.queryForList("SELECT * FROM fams_repair_request WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new RepairFailure(404, "REPAIR_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private Map<String, Object> round(long repair, long id) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_repair_factory_round WHERE repair_id=? AND id=? FOR"
                                + " UPDATE",
                        repair,
                        id);
        if (rows.isEmpty()) {
            throw new RepairFailure(404, "REPAIR_FACTORY_ROUND_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private Map<String, Object> scope(long repair, String status) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_repair_factory_scope WHERE repair_id=? AND"
                                + " scope_status=? ORDER BY id DESC LIMIT 1 FOR UPDATE",
                        repair,
                        status);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void processing(Actor actor, Current current, Map<String, Object> row, long version) {
        if (number(row, "version") != version
                || !"WAREHOUSE_PROCESSING".equals(row.get("repair_status"))
                || row.get("approval_snapshot_json") == null
                || row.get("accepted_at") == null) {
            conflict(actor, current, row, "REPAIR_FACTORY_VERSION_CONFLICT");
        }
    }

    private void guard(
            Actor actor,
            Current current,
            Map<String, Object> row,
            long version,
            long assetVersion,
            long ownershipVersion,
            long lockVersion) {
        processing(actor, current, row, version);
        if (number(row, "asset_version") != assetVersion
                || number(row, "ownership_version") != ownershipVersion) {
            conflict(actor, current, row, "REPAIR_FACTORY_OWNERSHIP_OR_ASSET_CHANGED");
        }
        lock(row, lockVersion);
    }

    private void lock(Map<String, Object> row, long expected) {
        var locks =
                db.queryForList(
                        "SELECT lock_version FROM fams_asset_business_lock WHERE asset_id=? AND"
                            + " business_type='REPAIR' AND business_id=? AND business_item_id=? AND"
                            + " released_at IS NULL FOR UPDATE",
                        row.get("asset_id"),
                        row.get("id"),
                        row.get("id"));
        if (locks.size() != 1 || number(locks.getFirst(), "lock_version") != expected) {
            throw new RepairFailure(409, "REPAIR_FACTORY_LOCK_CHANGED");
        }
    }

    private void roundGuard(
            Actor actor,
            Current current,
            Map<String, Object> row,
            Map<String, Object> round,
            long version,
            String status) {
        if (number(round, "version") != version
                || !status.equals(round.get("round_status"))
                || number(round, "asset_id") != number(row, "asset_id")) {
            conflict(actor, current, row, "REPAIR_FACTORY_ROUND_CHANGED");
        }
    }

    private Map<String, Object> basis(Map<String, Object> row, long lockVersion) {
        var result = new LinkedHashMap<String, Object>();
        result.put("repairId", row.get("id").toString());
        result.put("requestVersion", row.get("version"));
        result.put("assetVersion", row.get("asset_version"));
        result.put("ownershipVersion", row.get("ownership_version"));
        result.put("processorAccountId", row.get("processor_account_id"));
        result.put("lockVersion", lockVersion);
        result.put(
                "asset",
                assets.snapshot(
                        number(row, "asset_id"),
                        number(row, "asset_version"),
                        factory.dispatchedIdentity(row) ? "FACTORY_REPAIR" : "UNDER_REPAIR"));
        return result;
    }

    private boolean handled(long id) {
        return !db.queryForList(
                        "SELECT repair_id FROM fams_repair_physical_handling WHERE repair_id=?", id)
                .isEmpty();
    }

    private boolean outstanding(long id) {
        return !db.queryForList(
                        "SELECT id FROM fams_repair_return_handover WHERE repair_id=?"
                                + " AND handover_status IN ('WAITING_RECEIPT','REFUSED')",
                        id)
                .isEmpty();
    }

    private void selected(
            Current current, long repair, long round, List<UUID> objects, String purpose) {
        var port = evidence.getIfAvailable();
        if (port == null) {
            throw new RepairFailure(503, "REPAIR_FACTORY_EVIDENCE_UNAVAILABLE");
        }
        if (objects.isEmpty() || objects.stream().distinct().count() != objects.size()) {
            throw new RepairFailure(400, "REPAIR_FACTORY_EVIDENCE_REQUIRED");
        }
        for (var object : objects) {
            var rows =
                    db.queryForList(
                            "SELECT uploader_account_id FROM fams_repair_factory_evidence WHERE"
                                    + " repair_id=? AND round_id=? AND object_id=? AND purpose=?",
                            repair,
                            round,
                            object.toString(),
                            purpose);
            if (rows.size() != 1
                    || !port.clean(
                            "REPAIR_FACTORY",
                            round,
                            number(rows.getFirst(), "uploader_account_id"),
                            object)) {
                throw new RepairFailure(409, "REPAIR_FACTORY_EVIDENCE_CHANGED");
            }
        }
    }

    private void physicalTime(Instant actual, Instant earliest) {
        if (!actual.equals(actual.truncatedTo(ChronoUnit.MILLIS))
                || actual.isBefore(earliest)
                || actual.isAfter(clock.instant().plusSeconds(2))) {
            throw new RepairFailure(400, "REPAIR_FACTORY_PHYSICAL_TIME_INVALID");
        }
    }

    private <T> T replay(
            Actor actor,
            Current current,
            Map<String, Object> row,
            String operation,
            UUID key,
            String fingerprint,
            Class<T> type) {
        var receipts =
                db.queryForList(
                        "SELECT repair_id,payload_sha256,response_json FROM fams_repair_receipt"
                                + " WHERE actor_account_id=? AND operation=? AND request_id=? FOR"
                                + " UPDATE",
                        current.id(),
                        operation,
                        key.toString());
        if (receipts.isEmpty()) {
            return null;
        }
        var receipt = receipts.getFirst();
        if (number(receipt, "repair_id") != number(row, "id")
                || !fingerprint.equals(receipt.get("payload_sha256"))) {
            conflict(actor, current, row, "REPAIR_FACTORY_IDEMPOTENCY_CONFLICT");
        }
        return json.readValue(receipt.get("response_json").toString(), type);
    }

    private RepairViews.FactoryReply remember(
            Current current,
            String operation,
            UUID key,
            String fingerprint,
            long id,
            Long scope,
            Long round) {
        var result = reply(header(id), scope, round);
        save(current, operation, key, fingerprint, id, result);
        return result;
    }

    private void save(
            Current current,
            String operation,
            UUID key,
            String fingerprint,
            long id,
            Object result) {
        db.update(
                "INSERT INTO fams_repair_receipt(actor_account_id,operation,request_id,repair_id,"
                        + "payload_sha256,response_json,created_at) VALUES (?,?,?,?,?,?,?)",
                current.id(),
                operation,
                key.toString(),
                id,
                fingerprint,
                json.writeValueAsString(result),
                now());
    }

    private void advance(
            Actor actor,
            Current current,
            Map<String, Object> row,
            String action,
            Long scope,
            Long round,
            Object detail,
            Role role) {
        long version = number(row, "version");
        if (db.update(
                        "UPDATE fams_repair_request SET"
                                + " version=version+1,asset_version=?,updated_at=? WHERE id=? AND"
                                + " version=?",
                        row.get("asset_version"),
                        now(),
                        row.get("id"),
                        version)
                != 1) {
            throw new RepairFailure(409, "REPAIR_FACTORY_VERSION_CONFLICT");
        }
        audit(actor, current, row, action, scope, round, version + 1, detail, role);
    }

    private void audit(
            Actor actor,
            Current current,
            Map<String, Object> row,
            String action,
            Long scope,
            Long round,
            long after,
            Object detail,
            Role role) {
        long before = number(row, "version");
        String privateBody = json.writeValueAsString(detail);
        db.update(
                "INSERT INTO"
                    + " fams_repair_factory_event(repair_id,scope_id,round_id,action,before_version,"
                    + "after_version,actor_account_id,actor_username,actor_role,detail_json,recorded_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                row.get("id"),
                scope,
                round,
                action,
                before,
                after,
                current.id(),
                current.username(),
                role.name(),
                privateBody,
                now());
        var publicDetail = new LinkedHashMap<String, Object>();
        publicDetail.put("scopeId", scope == null ? null : scope.toString());
        publicDetail.put("roundId", round == null ? null : round.toString());
        db.update(
                "INSERT INTO"
                    + " fams_repair_event(repair_id,action,before_version,after_version,actor_account_id,actor_username,actor_role,detail_json,occurred_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?)",
                row.get("id"),
                "FACTORY_COST_RECORDED".equals(action) || "FACTORY_QUOTE_RECORDED".equals(action)
                        ? "REPAIR_FACTORY_FOLLOWUP_RECORDED"
                        : "REPAIR_" + action,
                before,
                after,
                current.id(),
                current.username(),
                role.name(),
                json.writeValueAsString(publicDetail),
                now());
        db.update(
                "INSERT INTO"
                    + " fams_audit_event(request_id,actor_account_id,actor_role,action,resource_type,resource_id,before_version,after_version,occurred_at,detail_json)"
                    + " VALUES (?,?,?,?,'REPAIR_FACTORY',?,?,?,?,?)",
                actor.requestId().toString(),
                current.id(),
                role.name(),
                action,
                row.get("id"),
                before,
                after,
                now(),
                privateBody);
    }

    private void conflict(Actor actor, Current current, Map<String, Object> row, String code) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("version", row.get("version").toString());
        fields.put("result", row.get("repair_status").toString());
        fields.put(
                "readOnlyUrl",
                (actor.miniToken() == null ? "/repairs/" : "/api/v1/mini/repairs/")
                        + row.get("id"));
        var latest =
                db.queryForList(
                        "SELECT actor_username,occurred_at FROM fams_repair_event WHERE repair_id=?"
                                + " AND action NOT IN"
                                + " ('REPAIR_FACTORY_CONFLICT_REJECTED','REPAIR_CONFLICT_REJECTED')"
                                + " ORDER BY id DESC LIMIT 1 FOR SHARE",
                        row.get("id"));
        if (!latest.isEmpty()) {
            fields.put("processedBy", latest.getFirst().get("actor_username").toString());
            fields.put("processedAt", instant(latest.getFirst().get("occurred_at")).toString());
        }
        audit(
                actor,
                current,
                row,
                "FACTORY_CONFLICT_REJECTED",
                null,
                null,
                number(row, "version"),
                Map.of("code", code, "fields", fields),
                current.has(Role.ASSET_ADMIN) ? Role.ASSET_ADMIN : Role.WAREHOUSE_OPERATOR);
        throw new RepairConflict(code, fields);
    }

    private RepairViews.FactoryReply reply(Map<String, Object> row, Long scope, Long round) {
        return reply(row, scope, round, null, null, null, null, null, null);
    }

    private RepairViews.FactoryReply reply(
            Map<String, Object> row,
            Long scope,
            Long round,
            String partsChangeId,
            String replacementId,
            String sourceId,
            String replacementAssetId,
            String inboundReceiptId,
            String objectId) {
        Long lockVersion = lockVersion(row);
        var scopeSegment =
                scope == null
                        ? null
                        : scopeView(
                                db.queryForMap(
                                        "SELECT * FROM fams_repair_factory_scope WHERE id=? AND"
                                                + " repair_id=?",
                                        scope,
                                        row.get("id")));
        var roundSegment = round == null ? null : roundView(round(number(row, "id"), round));
        return RepairViews.factoryReply(
                row,
                lockVersion,
                scopeSegment,
                roundSegment,
                partsChangeId,
                replacementId,
                sourceId,
                replacementAssetId,
                inboundReceiptId,
                objectId);
    }

    /** The frozen reply() lock chip: raw BIGINT lock_version of the single open repair lock. */
    private Long lockVersion(Map<String, Object> row) {
        var locks =
                db.queryForList(
                        "SELECT lock_version FROM fams_asset_business_lock WHERE asset_id=? AND"
                            + " business_type='REPAIR' AND business_id=? AND released_at IS NULL",
                        row.get("asset_id"),
                        row.get("id"));
        return locks.isEmpty() ? null : number(locks.getFirst(), "lock_version");
    }

    private RepairViews.FactoryScopeView scopeView(Map<String, Object> row) {
        return RepairViews.factoryScope(json, row);
    }

    private RepairViews.FactoryRoundView roundView(Map<String, Object> row) {
        return RepairViews.factoryRound(
                json,
                row,
                db.queryForList(
                        "SELECT * FROM fams_repair_factory_quote WHERE round_id=? ORDER BY" + " id",
                        row.get("id")),
                db.queryForList(
                                "SELECT * FROM fams_repair_factory_cost WHERE round_id=?",
                                row.get("id"))
                        .stream()
                        .findFirst()
                        .orElse(null));
    }

    /**
     * Frozen wire() projection of the attachment list rows surfaced under the files key:
     * no key ends in _json and sizeBytes is a BIGINT, so only createdAt is rewritten
     * (Timestamp/LocalDateTime/Instant become ISO instant strings via instant(), anything
     * else passes through).
     */
    public record EvidenceFile(
            String objectId,
            String filename,
            String contentType,
            long sizeBytes,
            String scanStatus,
            long businessVersion,
            Object createdAt) {
    }

    public static List<EvidenceFile> fileProjection(List<AttachmentEvidencePort.Metadata> rows) {
        return rows.stream()
                .map(
                        row ->
                                new EvidenceFile(
                                        row.objectId(),
                                        row.filename(),
                                        row.contentType(),
                                        row.sizeBytes(),
                                        row.scanStatus(),
                                        row.businessVersion(),
                                        fileTime(row.createdAt())))
                .toList();
    }

    private static Object fileTime(Object value) {
        if (value instanceof java.sql.Timestamp time) {
            return time.toInstant().toString();
        }
        if (value instanceof java.time.LocalDateTime time) {
            return time.toInstant(java.time.ZoneOffset.UTC).toString();
        }
        if (value instanceof Instant time) {
            return time.toString();
        }
        return value;
    }

    private Object wire(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach(
                    (k, v) ->
                            result.put(
                                    k.toString(),
                                    k.toString().endsWith("_json") && v instanceof String text
                                            ? json.readTree(text)
                                            : wire(v)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::wire).toList();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Timestamp
                || value instanceof java.time.LocalDateTime
                || value instanceof Instant) {
            return instant(value).toString();
        }
        return value instanceof BigDecimal decimal ? decimal.toPlainString() : value;
    }

    private void todo(long id, String type, String pool, Long owner) {
        db.update(
                "INSERT INTO"
                    + " fams_todo(pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,opened_at)"
                    + " VALUES (?,?,'REPAIR',?,?,'OPEN',?)",
                pool,
                owner,
                id,
                type,
                now());
    }

    private void closeTodo(long id, String type) {
        db.update(
                "UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE business_type='REPAIR'"
                        + " AND business_id=? AND todo_type=? AND closed_at IS NULL",
                now(),
                id,
                type);
    }

    private long count(String table, long id) {
        if (!List.of("fams_repair_factory_round", "fams_repair_factory_event").contains(table)) {
            throw new IllegalArgumentException("Unsupported factory count");
        }
        return Objects.requireNonNull(
                db.queryForObject(
                        "SELECT COUNT(*) FROM " + table + " WHERE repair_id=?", Long.class, id));
    }

    private long lastId() {
        return Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
    }

    private String hash(String operation, long version, Object body) {
        return RepairFactoryPayloads.fingerprint(json, operation, version, body);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp time) {
            return time.toInstant();
        }
        if (value instanceof java.time.LocalDateTime time) {
            return time.toInstant(java.time.ZoneOffset.UTC);
        }
        return value instanceof Instant time ? time : Instant.parse(value.toString());
    }

    private long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }
}
