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
@Transactional(isolation = Isolation.REPEATABLE_READ, noRollbackFor = RepairConflict.class)
public class RepairFactoryService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final RequisitionActorContext actors;
    private final AssetFactoryRepairPort assets;
    private final RepairFactoryGuard factory;
    private final ObjectProvider<AttachmentEvidencePort> evidence;
    private final ObjectProvider<AttachmentRegistry> attachments;

    public RepairFactoryService(
            JdbcTemplate db,
            ObjectMapper json,
            Clock clock,
            RequisitionActorContext actors,
            AssetFactoryRepairPort assets,
            RepairFactoryGuard factory,
            ObjectProvider<AttachmentEvidencePort> evidence,
            ObjectProvider<AttachmentRegistry> attachments) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actors = actors;
        this.assets = assets;
        this.factory = factory;
        this.evidence = evidence;
        this.attachments = attachments;
    }

    public Map<String, Object> get(Actor actor, long id) {
        var current = reader(actor);
        var row = header(id);
        readable(current, row);
        var pending = scope(id, "PENDING");
        var approved = scope(id, "APPROVED");
        var active = factory.active(id);
        boolean owner = owner(current, row);
        boolean processing = "WAREHOUSE_PROCESSING".equals(row.get("repair_status"));
        var result = reply(row, null, null);
        result.put("pendingScope", pending == null ? null : scopeView(pending));
        result.put("approvedScope", approved == null ? null : scopeView(approved));
        result.put("activeRound", active == null ? null : roundView(active));
        result.put("summary", wire(factory.summary(id)));
        result.put("photosAvailable", evidence.getIfAvailable() != null);
        result.put(
                "actions",
                Map.of(
                        "requestScope",
                                owner
                                        && processing
                                        && pending == null
                                        && approved == null
                                        && active == null
                                        && handled(id)
                                        && !outstanding(id),
                        "decideScope",
                                current.has(Role.ASSET_ADMIN) && processing && pending != null,
                        "withdrawScope", processing && pending != null,
                        "createRound",
                                owner
                                        && processing
                                        && approved != null
                                        && pending == null
                                        && active == null
                                        && handled(id)
                                        && !outstanding(id),
                        "dispatch",
                                owner
                                        && processing
                                        && active != null
                                        && "PENDING_DISPATCH".equals(active.get("round_status")),
                        "cancelRound",
                                owner
                                        && processing
                                        && active != null
                                        && "PENDING_DISPATCH".equals(active.get("round_status")),
                        "receive",
                                owner
                                        && processing
                                        && active != null
                                        && "DISPATCHED".equals(active.get("round_status")),
                        "uploadEvidence", owner && processing && active != null));
        return result;
    }

    public Map<String, Object> rounds(Actor actor, long id, int page, int size) {
        var current = reader(actor);
        readable(current, header(id));
        return Map.of(
                "items",
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
                "total",
                count("fams_repair_factory_round", id),
                "page",
                page,
                "pageSize",
                size);
    }

    public Map<String, Object> round(Actor actor, long id, long round) {
        var current = reader(actor);
        readable(current, header(id));
        var result = new LinkedHashMap<>(roundView(round(id, round)));
        var port = evidence.getIfAvailable();
        result.put(
                "evidence",
                wire(
                        db.queryForList(
                                "SELECT"
                                    + " object_id,purpose,content_sha256,uploader_account_id,recorded_at"
                                    + " FROM fams_repair_factory_evidence WHERE repair_id=? AND"
                                    + " round_id=? ORDER BY recorded_at,object_id",
                                id,
                                round)));
        result.put("files", port == null ? List.of() : wire(port.list("REPAIR_FACTORY", round)));
        return result;
    }

    public Map<String, Object> history(Actor actor, long id, int page, int size) {
        var current = reader(actor);
        readable(current, header(id));
        return Map.of(
                "items",
                wire(
                        db.queryForList(
                                "SELECT * FROM fams_repair_factory_event WHERE repair_id=?"
                                        + " ORDER BY id DESC LIMIT ? OFFSET ?",
                                id,
                                size,
                                ((long) page - 1) * size)),
                "total",
                count("fams_repair_factory_event", id),
                "page",
                page,
                "pageSize",
                size);
    }

    public Map<String, Object> requestScope(
            Actor actor, long id, long version, UUID key, RepairFactoryInput.ScopeRequest input) {
        var current = warehouse(actor);
        var row = header(id);
        requireOwner(current, row);
        String operation = "FACTORY_SCOPE_REQUEST:" + id;
        String fingerprint = hash(operation, version, input);
        var prior = replay(actor, current, row, operation, key, fingerprint);
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
        if (scope(id, "PENDING") != null
                || scope(id, "APPROVED") != null
                || factory.active(id) != null
                || !handled(id)
                || outstanding(id)) {
            throw new RepairFailure(409, "REPAIR_FACTORY_SCOPE_NOT_AVAILABLE");
        }
        var basis = basis(row, input.lockVersion());
        db.update(
                "INSERT INTO fams_repair_factory_scope(repair_id,request_snapshot_json,"
                        + "request_reason,requested_by,requested_at) VALUES (?,?,?,?,?)",
                id,
                json.writeValueAsString(basis),
                input.reason().strip(),
                current.id(),
                now());
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

    public Map<String, Object> decideScope(
            Actor actor, long id, long version, UUID key, RepairFactoryInput.ScopeDecision input) {
        var current = reader(actor);
        if (!current.has(Role.ASSET_ADMIN)) {
            throw new RepairFailure(403, "REPAIR_FACTORY_ADMIN_REQUIRED");
        }
        var row = header(id);
        String operation = "FACTORY_SCOPE_DECIDE:" + id;
        String fingerprint = hash(operation, version, input);
        var prior = replay(actor, current, row, operation, key, fingerprint);
        if (prior != null) {
            return prior;
        }
        return endScope(actor, current, row, version, key, operation, fingerprint, input, false);
    }

    public Map<String, Object> withdrawScope(
            Actor actor, long id, long version, UUID key, RepairFactoryInput.ScopeDecision input) {
        var current = reader(actor);
        var row = header(id);
        readable(current, row);
        if (!"WITHDRAW".equals(input.result())) {
            throw new RepairFailure(400, "REPAIR_FACTORY_SCOPE_RESULT_INVALID");
        }
        String operation = "FACTORY_SCOPE_WITHDRAW:" + id;
        String fingerprint = hash(operation, version, input);
        var prior = replay(actor, current, row, operation, key, fingerprint);
        if (prior != null) {
            return prior;
        }
        return endScope(actor, current, row, version, key, operation, fingerprint, input, true);
    }

    private Map<String, Object> endScope(
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

    public Map<String, Object> createRound(
            Actor actor, long id, long version, UUID key, RepairFactoryInput.RoundCreation input) {
        var current = warehouse(actor);
        var row = header(id);
        requireOwner(current, row);
        String operation = "FACTORY_ROUND_CREATE:" + id;
        String fingerprint = hash(operation, version, input);
        var prior = replay(actor, current, row, operation, key, fingerprint);
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

    public Map<String, Object> cancelRound(
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
        var prior = replay(actor, current, row, operation, key, fingerprint);
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

    public Map<String, Object> dispatch(
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
        var prior = replay(actor, current, row, operation, key, fingerprint);
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
                "UPDATE fams_repair_factory_round SET round_status='DISPATCHED',dispatched_at=?,"
                        + "dispatched_by=?,dispatch_evidence=?,version=version+1 WHERE id=?",
                input.occurredAt(),
                current.id(),
                input.evidence().strip(),
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

    public Map<String, Object> receive(
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
        var prior = replay(actor, current, row, operation, key, fingerprint);
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
        if (!input.originalPhysicalIdentityChecked()
                || !List.of("RESTORED", "STILL_FAULTY").contains(input.conditionResult())) {
            throw new RepairFailure(400, "REPAIR_FACTORY_ORIGINAL_RECEIPT_REQUIRED");
        }
        if (new BigDecimal(input.actualCost()).signum() != 0) {
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
                    + "receive_location_id=?,receive_location_version=?,condition_result=?,treatment_result=?,actual_cost=0,version=version+1"
                    + " WHERE id=?",
                input.occurredAt(),
                current.id(),
                input.actualLocationId(),
                input.locationVersion(),
                input.conditionResult(),
                input.treatmentResult().strip(),
                roundId);
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

    public void uploader(Actor actor, long id, long roundId) {
        var current = warehouse(actor);
        var row = header(id);
        requireOwner(current, row);
        round(id, roundId);
        // Phase checks follow permanent replay inside registration, after bytes are fingerprinted.
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RepairConflict.class)
    public Map<String, Object> registerEvidence(
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
        var prior = replay(actor, current, row, operation, key, fingerprint);
        if (prior != null) {
            return prior;
        }
        guard(actor, current, row, version, assetVersion, ownershipVersion, lockVersion);
        var round = round(id, roundId);
        if (number(round, "version") != roundVersion) {
            conflict(actor, current, row, "REPAIR_FACTORY_ROUND_CHANGED");
        }
        if (!List.of("PENDING_DISPATCH", "DISPATCHED").contains(round.get("round_status"))
                || !List.of("FAULT", "FACTORY_ACK", "RECEIPT").contains(purpose)
                || "RECEIPT".equals(purpose) && !"DISPATCHED".equals(round.get("round_status"))) {
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
        var result = reply(header(id), number(round, "scope_id"), roundId);
        result.put("objectId", attachment.file().id().toString());
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
                        number(row, "asset_id"), number(row, "asset_version"), "UNDER_REPAIR"));
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

    private Map<String, Object> replay(
            Actor actor,
            Current current,
            Map<String, Object> row,
            String operation,
            UUID key,
            String fingerprint) {
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
        return json.readValue(
                receipt.get("response_json").toString(),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> remember(
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
            Map<String, Object> result) {
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
                "REPAIR_" + action,
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

    private Map<String, Object> reply(Map<String, Object> row, Long scope, Long round) {
        var result = new LinkedHashMap<String, Object>();
        result.put("repairId", row.get("id").toString());
        result.put("version", row.get("version"));
        result.put("assetVersion", row.get("asset_version"));
        result.put("ownershipVersion", row.get("ownership_version"));
        result.put("status", row.get("repair_status"));
        result.put(
                "processorAccountId",
                row.get("processor_account_id") == null
                        ? null
                        : row.get("processor_account_id").toString());
        var locks =
                db.queryForList(
                        "SELECT lock_version FROM fams_asset_business_lock WHERE asset_id=? AND"
                            + " business_type='REPAIR' AND business_id=? AND released_at IS NULL",
                        row.get("asset_id"),
                        row.get("id"));
        result.put("lockVersion", locks.isEmpty() ? null : locks.getFirst().get("lock_version"));
        if (scope != null) {
            result.put(
                    "scope",
                    scopeView(
                            db.queryForMap(
                                    "SELECT * FROM fams_repair_factory_scope WHERE id=? AND"
                                            + " repair_id=?",
                                    scope,
                                    row.get("id"))));
        }
        if (round != null) {
            result.put("round", roundView(round(number(row, "id"), round)));
        }
        return result;
    }

    private Map<String, Object> scopeView(Map<String, Object> row) {
        var result =
                fields(
                        row,
                        "id",
                        "repair_id",
                        "scope_status",
                        "allowed_method",
                        "request_reason",
                        "requested_by",
                        "requested_at",
                        "decision_reason",
                        "decided_by",
                        "decided_at",
                        "version");
        result.put("requestBasis", json.readTree(row.get("request_snapshot_json").toString()));
        result.put(
                "decisionBasis",
                row.get("decision_snapshot_json") == null
                        ? null
                        : json.readTree(row.get("decision_snapshot_json").toString()));
        return result;
    }

    private Map<String, Object> roundView(Map<String, Object> row) {
        var result =
                fields(
                        row,
                        "id",
                        "repair_id",
                        "asset_id",
                        "scope_id",
                        "round_number",
                        "round_status",
                        "provider_name",
                        "service_contact",
                        "service_phone",
                        "expected_return_date",
                        "fault",
                        "created_by",
                        "created_at",
                        "dispatched_at",
                        "dispatched_by",
                        "dispatch_evidence",
                        "received_at",
                        "received_by",
                        "receive_location_id",
                        "receive_location_version",
                        "condition_result",
                        "treatment_result",
                        "actual_cost",
                        "cancel_reason",
                        "version");
        result.put("originalIdentity", json.readTree(row.get("original_identity_json").toString()));
        return result;
    }

    private Map<String, Object> fields(Map<String, Object> row, String... names) {
        var result = new LinkedHashMap<String, Object>();
        for (var name : names) {
            String[] words = name.split("_");
            var label = new StringBuilder(words[0]);
            for (int i = 1; i < words.length; i++) {
                label.append(Character.toUpperCase(words[i].charAt(0)))
                        .append(words[i].substring(1));
            }
            Object value = row.get(name);
            result.put(
                    label.toString(),
                    value instanceof Number
                                    && (name.equals("id")
                                            || name.endsWith("_id")
                                            || name.endsWith("_by"))
                            ? value.toString()
                            : wire(value));
        }
        return result;
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
