package com.fams.repair;

import com.fams.asset.AssetRepairPort;
import com.fams.attachment.AttachmentEvidencePort;
import com.fams.attachment.AttachmentIntake;
import com.fams.attachment.AttachmentRegistry;
import com.fams.attachment.MalwareScanner;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;
import com.fams.loan.LoanRepairPort;
import com.fams.loan.LoanReturnRepairPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Short repair transactions never wait for the next person or a scanner. */
@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.REPEATABLE_READ, noRollbackFor = RepairConflict.class)
public class RepairService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final RequisitionActorContext actors;
    private final AssetRepairPort assets;
    private final LoanRepairPort loans;
    private final LoanReturnRepairPort returned;
    private final ObjectProvider<AttachmentEvidencePort> evidence;
    private final ObjectProvider<AttachmentRegistry> attachments;
    private final RepairFactoryGuard factory;
    private final RepairFactoryService factoryOperations;

    public RepairService(
            JdbcTemplate db,
            ObjectMapper json,
            Clock clock,
            RequisitionActorContext actors,
            AssetRepairPort assets,
            LoanRepairPort loans,
            LoanReturnRepairPort returned,
            ObjectProvider<AttachmentEvidencePort> evidence,
            ObjectProvider<AttachmentRegistry> attachments,
            RepairFactoryGuard factory,
            RepairFactoryService factoryOperations) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actors = actors;
        this.assets = assets;
        this.loans = loans;
        this.returned = returned;
        this.evidence = evidence;
        this.attachments = attachments;
        this.factory = factory;
        this.factoryOperations = factoryOperations;
    }

    public Map<String, Object> eligible(Actor actor, int page, int size) {
        return assets.eligible(actors.revalidateLocked(actor), page, size);
    }

    public Map<String, Object> returnSources(Actor actor, int page, int size) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.ASSET_ADMIN);
        return returned.list(page, size);
    }

    public Map<String, Object> returnSource(Actor actor, long followup) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.ASSET_ADMIN);
        var source = returned.pending(followup);
        var result = new LinkedHashMap<>(returnSourceWire(source));
        result.put(
                "asset",
                json.readTree(
                        assets.damagedReturnSource(
                                source.assetId(), -1, source.warehouseId(), source.locationId())));
        return result;
    }

    public Map<String, Object> submitReturned(
            Actor actor,
            long followup,
            long version,
            UUID key,
            RepairInput.ReturnSourceSubmission input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.ASSET_ADMIN);
        String operation = "RETURN_SOURCE:" + followup,
                fingerprint = hash(operation, version, input);
        var prior = replay(actor, current, operation, key, fingerprint);
        if (prior != null) {
            return prior;
        }
        if (!List.of("LOW", "MEDIUM", "HIGH").contains(input.impact())) {
            throw new RepairFailure(400, "REPAIR_IMPACT_INVALID");
        }
        var source = returned.pending(followup);
        if (source.version() != version || source.lockVersion() != input.lockVersion()) {
            throw new RepairFailure(409, "REPAIR_RETURN_SOURCE_CHANGED");
        }
        String asset =
                assets.damagedReturnSource(
                        source.assetId(),
                        input.assetVersion(),
                        source.warehouseId(),
                        source.locationId());
        if (loans.open(source.assetId()) != null) {
            throw new RepairFailure(409, "REPAIR_LOAN_CHANGED");
        }
        String zone = json.readTree(source.parametersJson()).path("timezone").asText();
        try {
            ZoneId.of(zone);
        } catch (java.time.DateTimeException invalid) {
            throw new RepairFailure(409, "REPAIR_PARAMETERS_REQUIRED");
        }
        db.update(
                "INSERT INTO fams_repair_request(asset_id,creator_account_id,asset_version,"
                    + "source_snapshot_json,timezone_id,fault,impact,occurred_at,processor_account_id,created_at,updated_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                source.assetId(),
                current.id(),
                input.assetVersion(),
                asset,
                zone,
                input.fault().strip(),
                input.impact(),
                source.inspectedAt(),
                source.processorAccountId(),
                now(),
                now());
        long id =
                java.util.Objects.requireNonNull(
                        db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        db.update(
                "INSERT INTO"
                    + " fams_repair_return_origin(repair_id,followup_id,source_context_json,created_at)"
                    + " VALUES (?,?,?,?)",
                id,
                followup,
                json.writeValueAsString(returnSourceWire(source)),
                now());
        returned.adopt(source, id);
        todo(id, "APPROVAL", "ASSET_ADMIN", null);
        audit(
                actor,
                current,
                header(id),
                "REPAIR_RETURN_SOURCE_SUBMITTED",
                0,
                0,
                Map.of(
                        "source",
                        returnSourceWire(source),
                        "fault",
                        input.fault(),
                        "impact",
                        input.impact()),
                Role.ASSET_ADMIN.name());
        return remember(current, operation, key, fingerprint, header(id));
    }

    private Map<String, Object> returnSourceWire(LoanReturnRepairPort.Context source) {
        var result = new LinkedHashMap<String, Object>();
        result.put("followupId", Long.toString(source.id()));
        result.put("version", source.version());
        result.put("returnId", Long.toString(source.returnId()));
        result.put("assetId", Long.toString(source.assetId()));
        result.put("closedLoanId", source.loanId() == null ? null : source.loanId().toString());
        result.put("processorAccountId", Long.toString(source.processorAccountId()));
        result.put("warehouseId", Long.toString(source.warehouseId()));
        result.put("locationId", Long.toString(source.locationId()));
        result.put("inspectedAt", source.inspectedAt().toString());
        result.put("inspectionReason", source.reason());
        result.put("lockVersion", source.lockVersion());
        return result;
    }

    private Map<String, Object> returnOrigin(long repair) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_repair_return_origin WHERE repair_id=?", repair);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void requireRepairSource(Map<String, Object> row) {
        var origin = returnOrigin(number(row, "id"));
        if (origin == null) {
            assets.requireSubmitted(number(row, "asset_id"), number(row, "asset_version"));
        } else {
            var source = json.readTree(origin.get("source_context_json").toString());
            assets.damagedReturnSource(
                    number(row, "asset_id"),
                    number(row, "asset_version"),
                    source.path("warehouseId").asLong(),
                    source.path("locationId").asLong());
        }
    }

    private void releaseOrRestoreReturn(Map<String, Object> row, String status) {
        var origin = returnOrigin(number(row, "id"));
        if (origin == null) {
            release(row, "REPAIR_" + status);
        } else {
            returned.progress(number(origin, "followup_id"), number(row, "id"), status, null);
        }
    }

    public Map<String, Object> list(Actor actor, int page, int size) {
        var current = actors.revalidateLocked(actor);
        String where =
                " FROM fams_repair_request WHERE (? OR creator_account_id=? OR"
                    + " responsible_employee_id=? OR shared_responsible_employee_id=? OR (? AND"
                    + " using_organization_id=?) OR (? AND approval_snapshot_json IS NOT NULL) OR"
                    + " EXISTS(SELECT 1 FROM fams_repair_approved_return_target t WHERE"
                    + " t.repair_id=fams_repair_request.id AND t.receiver_account_id=? AND"
                    + " t.employee_id=? AND t.organization_id=? AND (t.custody_type='PERSONAL' OR"
                    + " ?) AND t.id=(SELECT MAX(t2.id) FROM fams_repair_approved_return_target t2"
                    + " WHERE t2.repair_id=t.repair_id)))";
        Object[] scope = {
            current.has(Role.ASSET_ADMIN),
            current.id(),
            current.employeeId(),
            current.employeeId(),
            current.has(Role.DEPARTMENT_ASSET_MANAGER),
            current.organizationId(),
            current.has(Role.WAREHOUSE_OPERATOR),
            current.id(),
            current.employeeId(),
            current.organizationId(),
            current.has(Role.DEPARTMENT_ASSET_MANAGER)
        };
        var args = new java.util.ArrayList<Object>(java.util.Arrays.asList(scope));
        args.add(size);
        args.add(((long) page - 1) * size);
        var rows =
                db.queryForList(
                        "SELECT *" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", args.toArray());
        long total =
                java.util.Objects.requireNonNull(
                        db.queryForObject("SELECT COUNT(*)" + where, Long.class, scope));
        return Map.of(
                "items",
                rows.stream().map(this::view).toList(),
                "total",
                total,
                "page",
                page,
                "pageSize",
                size);
    }

    public Map<String, Object> get(Actor actor, long id) {
        var current = actors.revalidateLocked(actor);
        var row = header(id);
        visible(current, row);
        var result = new LinkedHashMap<>(view(row));
        result.put(
                "inventoryEvidence",
                inventoryEvidenceAllowed(current, row)
                        ? db
                                .queryForList(
                                        "SELECT source_context_json FROM"
                                            + " fams_repair_inventory_evidence WHERE repair_id=?"
                                            + " ORDER BY observation_id",
                                        id)
                                .stream()
                                .map(
                                        source -> {
                                            var facts =
                                                    json.readTree(
                                                            source.get("source_context_json")
                                                                    .toString());
                                            return Map.of(
                                                    "taskId",
                                                    facts.path("taskId").asText(),
                                                    "observationId",
                                                    facts.path("observationId").asText(),
                                                    "clientEventId",
                                                    facts.path("clientEventId").asText(),
                                                    "photoIds",
                                                    facts.path("photoIds"));
                                        })
                                .toList()
                        : List.of());
        result.put(
                "decisions",
                wire(
                        db.queryForList(
                                "SELECT * FROM fams_repair_decision WHERE repair_id=? ORDER BY id"
                                        + " DESC LIMIT 100",
                                id)));
        result.put(
                "history",
                wire(
                        db.queryForList(
                                "SELECT * FROM fams_repair_event WHERE repair_id=? ORDER BY id DESC"
                                        + " LIMIT 100",
                                id)));
        result.put(
                "historyTotal",
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_event WHERE repair_id=?",
                        Long.class,
                        id));
        var locks =
                db.queryForList(
                        "SELECT lock_version FROM fams_asset_business_lock WHERE asset_id=? AND"
                            + " business_type='REPAIR' AND business_id=? AND released_at IS NULL",
                        row.get("asset_id"),
                        id);
        result.put("lockVersion", locks.isEmpty() ? null : locks.getFirst().get("lock_version"));
        var port = evidence.getIfAvailable();
        result.put("photosAvailable", port != null);
        result.put("photos", port == null ? List.of() : wire(port.list("REPAIR", id)));
        var returnOrigin = returnOrigin(id);
        result.put(
                "returnOrigin",
                returnOrigin == null
                        ? null
                        : json.readTree(returnOrigin.get("source_context_json").toString()));
        boolean inheritedReturn = returnOrigin != null;
        boolean away = hasPhysicalHandling(id);
        result.put(
                "physicalHandling",
                wire(
                        db.queryForList(
                                "SELECT * FROM fams_repair_physical_handling WHERE repair_id=?",
                                id)));
        result.put(
                "inbound",
                wire(db.queryForList("SELECT * FROM fams_repair_inbound WHERE repair_id=?", id)));
        var currentLoan = loans.open(number(row, "asset_id"));
        result.put("loanVersion", currentLoan == null ? null : currentLoan.version());
        result.put(
                "loanExpectedReturnDate",
                currentLoan == null ? null : currentLoan.expectedReturnDate());
        var returns =
                db.queryForList(
                        "SELECT * FROM fams_repair_return_handover"
                                + " WHERE repair_id=? ORDER BY id DESC LIMIT 100",
                        id);
        result.put("returnHandovers", wire(returns));
        result.put(
                "approvedReturnTargets",
                wire(
                        db.queryForList(
                                "SELECT * FROM fams_repair_approved_return_target WHERE repair_id=?"
                                        + " ORDER BY id DESC LIMIT 100",
                                id)));
        result.put(
                "returnRecollections",
                wire(
                        db.queryForList(
                                "SELECT r.* FROM fams_repair_return_recollection r JOIN"
                                    + " fams_repair_return_handover h ON h.id=r.handover_id WHERE"
                                    + " h.repair_id=? ORDER BY h.id DESC LIMIT 100",
                                id)));
        var outstanding = outstandingReturn(id);
        boolean pending = "PENDING_ADMIN_DECISION".equals(row.get("repair_status"));
        boolean processing =
                "WAREHOUSE_PROCESSING".equals(row.get("repair_status"))
                        && current.has(Role.WAREHOUSE_OPERATOR)
                        && java.util.Objects.equals(
                                current.id(), nullable(row.get("processor_account_id")));
        var actions =
                new LinkedHashMap<>(
                        Map.of(
                                "approve", current.has(Role.ASSET_ADMIN) && pending,
                                "claim",
                                        current.has(Role.WAREHOUSE_OPERATOR)
                                                && "WAITING_WAREHOUSE_CLAIM"
                                                        .equals(row.get("repair_status")),
                                "acceptInPlace",
                                        processing
                                                && !inheritedReturn
                                                && !away
                                                && row.get("accepted_at") == null,
                                "completeInPlace",
                                        processing
                                                && !inheritedReturn
                                                && !away
                                                && row.get("accepted_at") != null,
                                "acceptAway", processing && !away,
                                "closeInbound",
                                        processing
                                                && row.get("accepted_at") != null
                                                && outstanding == null,
                                "addFaultPhoto",
                                        port != null
                                                && pending
                                                && current.id()
                                                        == number(row, "creator_account_id"),
                                "cancel",
                                        pending
                                                && (current.has(Role.ASSET_ADMIN)
                                                        || current.id()
                                                                == number(
                                                                        row,
                                                                        "creator_account_id"))));
        boolean receiver = outstanding != null && isReturnReceiver(current, outstanding);
        boolean returnable =
                List.of("IN_USE", "BORROWED")
                        .contains(
                                json.readTree(row.get("source_snapshot_json").toString())
                                        .path("lifecycle")
                                        .asText());
        actions.put("deliverReturn", processing && away && outstanding == null && returnable);
        actions.put("changeReturnTarget", processing && away && outstanding == null && returnable);
        actions.put(
                "receiveReturn",
                receiver
                        && "WAREHOUSE_PROCESSING".equals(row.get("repair_status"))
                        && "WAITING_RECEIPT".equals(outstanding.get("handover_status")));
        actions.put("refuseReturn", actions.get("receiveReturn"));
        actions.put("recollectReturn", processing && outstanding != null);
        actions.put(
                "reassign",
                current.has(Role.ASSET_ADMIN)
                        && "WAREHOUSE_PROCESSING".equals(row.get("repair_status"))
                        && row.get("processor_account_id") != null);
        actions.put(
                "proxyReturn",
                processing
                        && outstanding != null
                        && "WAITING_RECEIPT".equals(outstanding.get("handover_status")));
        if (current.has(Role.ASSET_ADMIN)
                || current.has(Role.WAREHOUSE_OPERATOR)
                        && java.util.Objects.equals(
                                current.id(), nullable(row.get("processor_account_id")))) {
            actions.put("manageFactory", true);
        }
        var factorySummary = factory.summary(id);
        if (factorySummary != null) {
            result.put("factorySummary", wire(factorySummary));
            if (Boolean.TRUE.equals(factorySummary.get("followupOpen"))) {
                for (var action :
                        List.of(
                                "acceptInPlace",
                                "completeInPlace",
                                "acceptAway",
                                "closeInbound",
                                "deliverReturn",
                                "receiveReturn",
                                "refuseReturn",
                                "recollectReturn",
                                "changeReturnTarget",
                                "proxyReturn")) {
                    actions.put(action, false);
                }
            }
        }
        result.put("actions", actions);
        return result;
    }

    public void requireInventoryAdministrator(Actor actor) {
        require(actors.revalidateLocked(actor), Role.ASSET_ADMIN);
    }

    public void requireInventoryEvidenceReader(Actor actor, long id) {
        var current = actors.revalidateLocked(actor);
        if (!inventoryEvidenceAllowed(current, header(id))) {
            throw new RepairFailure(403, "INVENTORY_REPAIR_EVIDENCE_FORBIDDEN");
        }
    }

    private boolean inventoryEvidenceAllowed(Current current, Map<String, Object> row) {
        return current.has(Role.ASSET_ADMIN)
                || current.has(Role.WAREHOUSE_OPERATOR)
                        && row.get("approval_snapshot_json") != null
                        && java.util.Objects.equals(
                                current.id(), nullable(row.get("processor_account_id")));
    }

    public Map<String, Object> submit(Actor actor, UUID key, RepairInput.Submission input) {
        var current = actors.revalidateLocked(actor);
        String fingerprint = hash("SUBMIT", 0, input);
        var prior = replay(actor, current, "SUBMIT", key, fingerprint);
        if (prior != null) {
            return prior;
        }
        if (!List.of("LOW", "MEDIUM", "HIGH").contains(input.impact())
                || !input.occurredAt().equals(input.occurredAt().truncatedTo(ChronoUnit.MILLIS))
                || input.occurredAt().isAfter(clock.instant().plusSeconds(2))) {
            throw new RepairFailure(400, "REPAIR_FAULT_INVALID");
        }
        String source = assets.submission(current, input.assetId(), input.assetVersion());
        var snapshot = json.readTree(source);
        var loan = loans.open(input.assetId());
        boolean borrowed = "BORROWED".equals(snapshot.path("lifecycle").asText());
        if (borrowed != (loan != null)) {
            throw new RepairFailure(409, "REPAIR_LOAN_CHANGED");
        }
        if (loan != null
                && (!Long.toString(loan.borrowerEmployeeId())
                                .equals(snapshot.path("responsibleEmployeeId").asText())
                        || !Long.toString(loan.organizationId())
                                .equals(snapshot.path("usingOrganizationId").asText()))) {
            throw new RepairFailure(409, "REPAIR_LOAN_CHANGED");
        }
        var parameters =
                db.queryForList(
                        "SELECT v.snapshot_json FROM fams_business_parameter_state s JOIN"
                                + " fams_business_parameter_version v ON v.id=s.current_version_id"
                                + " WHERE s.singleton_id=1 AND v.status='ACTIVE' FOR SHARE");
        if (parameters.isEmpty()) {
            throw new RepairFailure(409, "REPAIR_PARAMETERS_REQUIRED");
        }
        String zone =
                json.readTree(parameters.getFirst().get("snapshot_json").toString())
                        .path("timezone")
                        .asText();
        try {
            ZoneId.of(zone);
        } catch (java.time.DateTimeException invalid) {
            throw new RepairFailure(409, "REPAIR_PARAMETERS_REQUIRED");
        }
        var now = now();
        db.update(
                "INSERT INTO"
                    + " fams_repair_request(asset_id,creator_account_id,using_organization_id,responsible_employee_id,shared_responsible_employee_id,asset_version,source_snapshot_json,loan_snapshot_json,timezone_id,fault,impact,occurred_at,created_at,updated_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                input.assetId(),
                current.id(),
                reference(snapshot, "usingOrganizationId"),
                reference(snapshot, "responsibleEmployeeId"),
                reference(snapshot, "departmentResponsibleEmployeeId"),
                input.assetVersion(),
                source,
                loan == null ? null : json.writeValueAsString(loan),
                zone,
                input.fault().strip(),
                input.impact(),
                input.occurredAt(),
                now,
                now);
        long id =
                java.util.Objects.requireNonNull(
                        db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        db.update(
                "INSERT INTO"
                    + " fams_asset_business_lock(asset_id,business_type,business_id,business_item_id,acquired_at)"
                    + " VALUES (?,'REPAIR',?,?,?)",
                input.assetId(),
                id,
                id,
                now);
        todo(id, "APPROVAL", "ASSET_ADMIN", null);
        audit(actor, current, header(id), "REPAIR_SUBMITTED", 0, 0, input, role(current));
        return remember(current, "SUBMIT", key, fingerprint, header(id));
    }

    public Map<String, Object> decide(
            Actor actor, long id, long version, UUID key, RepairInput.Decision input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.ASSET_ADMIN);
        var row = header(id);
        String operation = "DECIDE:" + id, fingerprint = hash(operation, version, input);
        var prior = replay(actor, current, operation, key, fingerprint);
        if (prior != null) {
            return prior;
        }
        guard(actor, current, row, version, "PENDING_ADMIN_DECISION");
        lock(row, null);
        boolean approve = "APPROVE".equals(input.result());
        if (!List.of("APPROVE", "REJECT").contains(input.result())
                || approve && !noCostHandling(input.allowedMethod())
                || !approve && input.allowedMethod() != null) {
            throw new RepairFailure(400, "REPAIR_APPROVAL_SCOPE_INVALID");
        }
        if (approve) {
            requireRepairSource(row);
        }
        boolean initialFactory = approve && "INTERNAL_OR_FACTORY".equals(input.allowedMethod());
        var publicDecision =
                initialFactory
                        ? new RepairInput.Decision(
                                input.result(), input.allowedMethod(), "已批准内部或必要返厂的无费用范围，具体依据按权限保存")
                        : input;
        var origin = returnOrigin(id);
        var basis = new LinkedHashMap<String, Object>();
        basis.put(
                "returnOrigin",
                origin == null
                        ? null
                        : json.readTree(origin.get("source_context_json").toString()));
        basis.put("source", json.readTree(row.get("source_snapshot_json").toString()));
        basis.put(
                "loan",
                row.get("loan_snapshot_json") == null
                        ? null
                        : json.readTree(row.get("loan_snapshot_json").toString()));
        basis.put("fault", row.get("fault"));
        basis.put("impact", row.get("impact"));
        basis.put("occurredAt", wire(row.get("occurred_at")));
        basis.put(
                "photos",
                wire(
                        db.queryForList(
                                "SELECT object_id,content_sha256,purpose FROM fams_repair_photo"
                                        + " WHERE repair_id=? ORDER BY object_id",
                                id)));
        basis.put(
                "technicalOpinions",
                wire(
                        db.queryForList(
                                "SELECT"
                                    + " id,invitation_id,judgement,method,opinion_result,occurred_at,attachment_ids_json,actor_account_id,actor_username,recorded_at"
                                    + " FROM fams_repair_opinion WHERE repair_id=? ORDER BY id",
                                id)));
        basis.put("allowedMethod", input.allowedMethod());
        basis.put("requestVersion", version);
        String frozen = json.writeValueAsString(basis);
        db.update(
                "INSERT INTO"
                    + " fams_repair_decision(repair_id,before_version,after_version,result,allowed_method,snapshot_json,reason,actor_account_id,recorded_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?)",
                id,
                version,
                version + 1,
                input.result(),
                input.allowedMethod(),
                frozen,
                publicDecision.reason().strip(),
                current.id(),
                now());
        db.update(
                "UPDATE fams_repair_request SET"
                    + " repair_status=?,allowed_method=?,approval_snapshot_json=?,version=version+1,updated_at=?"
                    + " WHERE id=?",
                approve
                        ? origin == null ? "WAITING_WAREHOUSE_CLAIM" : "WAREHOUSE_PROCESSING"
                        : "REJECTED",
                input.allowedMethod(),
                approve ? frozen : null,
                now(),
                id);
        if (initialFactory) {
            factoryOperations.approveInitially(actor, id, version, input.reason());
        }
        closeTodos(id);
        if (approve) {
            if (origin == null) {
                todo(id, "CLAIM", "WAREHOUSE_OPERATOR", null);
            } else {
                db.update(
                        "UPDATE fams_repair_request SET"
                                + " ownership_version=ownership_version+1,claimed_at=? WHERE id=?",
                        now(),
                        id);
                todo(id, "PROCESS", "WAREHOUSE_OPERATOR", number(row, "processor_account_id"));
                returned.progress(number(origin, "followup_id"), id, "WAREHOUSE_PROCESSING", null);
            }
        } else {
            releaseOrRestoreReturn(row, "REJECTED");
            closeCollaborations(id);
        }
        audit(
                actor,
                current,
                row,
                "REPAIR_DECIDED",
                version,
                version + 1,
                publicDecision,
                Role.ASSET_ADMIN.name());
        return remember(current, operation, key, fingerprint, header(id));
    }

    public Map<String, Object> cancel(
            Actor actor, long id, long version, UUID key, RepairInput.Reason input) {
        var current = actors.revalidateLocked(actor);
        var row = header(id);
        visible(current, row);
        if (number(row, "creator_account_id") != current.id() && !current.has(Role.ASSET_ADMIN)) {
            throw new RepairFailure(403, "REPAIR_CREATOR_REQUIRED");
        }
        String operation = "CANCEL:" + id, fingerprint = hash(operation, version, input);
        var prior = replay(actor, current, operation, key, fingerprint);
        if (prior != null) {
            return prior;
        }
        guard(actor, current, row, version, "PENDING_ADMIN_DECISION");
        lock(row, null);
        releaseOrRestoreReturn(row, "CANCELLED");
        closeCollaborations(id);
        closeTodos(id);
        db.update(
                "UPDATE fams_repair_request SET"
                        + " repair_status='CANCELLED',version=version+1,updated_at=? WHERE id=?",
                now(),
                id);
        audit(actor, current, row, "REPAIR_CANCELLED", version, version + 1, input, role(current));
        return remember(current, operation, key, fingerprint, header(id));
    }

    public Map<String, Object> claim(Actor actor, long id, long version, UUID key) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        var row = header(id);
        visible(current, row);
        String operation = "CLAIM:" + id, fingerprint = hash(operation, version, Map.of());
        var prior = replay(actor, current, operation, key, fingerprint);
        if (prior != null) {
            return prior;
        }
        guard(actor, current, row, version, "WAITING_WAREHOUSE_CLAIM");
        lock(row, null);
        db.update(
                "UPDATE fams_repair_request SET"
                    + " repair_status='WAREHOUSE_PROCESSING',processor_account_id=?,claimed_at=?,ownership_version=ownership_version+1,version=version+1,updated_at=?"
                    + " WHERE id=?",
                current.id(),
                now(),
                now(),
                id);
        closeTodos(id);
        todo(id, "PROCESS", "WAREHOUSE_OPERATOR", current.id());
        audit(
                actor,
                current,
                row,
                "REPAIR_CLAIMED",
                version,
                version + 1,
                Map.of("processorAccountId", Long.toString(current.id())),
                Role.WAREHOUSE_OPERATOR.name());
        return remember(current, operation, key, fingerprint, header(id));
    }

    public Map<String, Object> acceptInPlace(
            Actor actor, long id, long version, UUID key, RepairInput.InPlace input) {
        return inPlace(actor, id, version, key, input, false);
    }

    public Map<String, Object> completeInPlace(
            Actor actor, long id, long version, UUID key, RepairInput.InPlace input) {
        return inPlace(actor, id, version, key, input, true);
    }

    private Map<String, Object> inPlace(
            Actor actor,
            long id,
            long version,
            UUID key,
            RepairInput.InPlace input,
            boolean complete) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        var row = header(id);
        visible(current, row);
        owner(actor, current, row);
        String operation = (complete ? "COMPLETE:" : "ACCEPT:") + id,
                fingerprint = hash(operation, version, input);
        var prior = replay(actor, current, operation, key, fingerprint);
        if (prior != null) {
            return prior;
        }
        guard(actor, current, row, version, "WAREHOUSE_PROCESSING");
        if (number(row, "ownership_version") != input.ownershipVersion()) {
            reject(actor, current, row, "REPAIR_OWNERSHIP_CHANGED");
        }
        lock(row, input.lockVersion());
        factory.requireInternal(id);
        if (returnOrigin(id) != null
                || !input.neverLeftCustodian()
                || hasPhysicalHandling(id)
                || input.assetVersion() != number(row, "asset_version")
                || !noCostHandling(row.get("allowed_method"))
                || complete != (row.get("accepted_at") != null)) {
            throw new RepairFailure(409, "REPAIR_IN_PLACE_NOT_ALLOWED");
        }
        var loan =
                row.get("loan_snapshot_json") == null
                        ? null
                        : json.readValue(
                                row.get("loan_snapshot_json").toString(),
                                LoanRepairPort.Context.class);
        loans.requireUnchanged(
                number(row, "asset_id"),
                loan,
                LocalDate.ofInstant(clock.instant(), ZoneId.of(row.get("timezone_id").toString())));
        long next =
                complete
                        ? assets.closeInPlace(
                                number(row, "asset_id"),
                                input.assetVersion(),
                                row.get("source_snapshot_json").toString(),
                                input.observedCode(),
                                input.actualLocationId(),
                                current.id(),
                                id,
                                now())
                        : assets.accept(
                                number(row, "asset_id"),
                                input.assetVersion(),
                                input.observedCode(),
                                input.actualLocationId(),
                                current.id(),
                                id,
                                now());
        if (complete) {
            db.update(
                    "UPDATE fams_repair_request SET"
                        + " repair_status='COMPLETED',completed_at=?,asset_version=?,version=version+1,updated_at=?"
                        + " WHERE id=?",
                    now(),
                    next,
                    now(),
                    id);
            release(row, "REPAIR_COMPLETED_IN_PLACE");
            closeCollaborations(id);
            closeTodos(id);
        } else {
            db.update(
                    "UPDATE fams_repair_request SET"
                            + " accepted_at=?,asset_version=?,version=version+1,updated_at=? WHERE"
                            + " id=?",
                    now(),
                    next,
                    now(),
                    id);
        }
        audit(
                actor,
                current,
                row,
                complete ? "REPAIR_COMPLETED_IN_PLACE" : "REPAIR_ACCEPTED_IN_PLACE",
                version,
                version + 1,
                input,
                Role.WAREHOUSE_OPERATOR.name());
        return remember(current, operation, key, fingerprint, header(id));
    }

    public Map<String, Object> physicalCatalog(Actor actor, String kind, int page, int size) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        return assets.physicalCatalog(kind, page, size);
    }

    public Map<String, Object> acceptAway(
            Actor actor, long id, long version, UUID key, RepairInput.AwayAcceptance input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        var row = header(id);
        visible(current, row);
        owner(actor, current, row);
        String operation = "ACCEPT_AWAY:" + id, fingerprint = hash(operation, version, input);
        var prior = replay(actor, current, operation, key, fingerprint);
        if (prior != null) {
            return prior;
        }
        physicalGuard(
                actor,
                current,
                row,
                version,
                input.assetVersion(),
                input.ownershipVersion(),
                input.lockVersion());
        if (hasPhysicalHandling(id)) {
            reject(actor, current, row, "REPAIR_PHYSICAL_ALREADY_RECORDED");
        }
        physicalTime(
                input.occurredAt(),
                returnOrigin(id) != null
                        ? row.get("claimed_at")
                        : row.get("accepted_at") == null
                                ? row.get("created_at")
                                : row.get("accepted_at"));
        loans.requireIdentity(number(row, "asset_id"), originalLoan(row), input.loanVersion());
        if (returnOrigin(id) != null) {
            requireRepairSource(row);
        }
        long next =
                assets.acceptAway(
                        number(row, "asset_id"),
                        input.assetVersion(),
                        input.observedCode(),
                        input.actualLocationId(),
                        input.locationVersion(),
                        current.id(),
                        id,
                        now());
        db.update(
                "INSERT INTO"
                    + " fams_repair_physical_handling(repair_id,handler_employee_id,actual_location_id,location_version,before_asset_version,occurred_at,evidence,actor_account_id,recorded_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?)",
                id,
                current.employeeId(),
                input.actualLocationId(),
                input.locationVersion(),
                input.assetVersion(),
                input.occurredAt(),
                input.evidence().strip(),
                current.id(),
                now());
        db.update(
                "UPDATE fams_repair_request SET"
                    + " accepted_at=COALESCE(accepted_at,?),asset_version=?,version=version+1,updated_at=?"
                    + " WHERE id=?",
                now(),
                next,
                now(),
                id);
        audit(
                actor,
                current,
                row,
                "REPAIR_ACCEPTED_AWAY",
                version,
                version + 1,
                input,
                Role.WAREHOUSE_OPERATOR.name());
        return remember(current, operation, key, fingerprint, header(id));
    }

    public Map<String, Object> closeInbound(
            Actor actor, long id, long version, UUID key, RepairInput.Inbound input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        var row = header(id);
        visible(current, row);
        owner(actor, current, row);
        String operation = "INBOUND:" + id, fingerprint = hash(operation, version, input);
        var prior = replay(actor, current, operation, key, fingerprint);
        if (prior != null) {
            return prior;
        }
        physicalGuard(
                actor,
                current,
                row,
                version,
                input.assetVersion(),
                input.ownershipVersion(),
                input.lockVersion());
        if (row.get("accepted_at") == null) {
            throw new RepairFailure(409, "REPAIR_ACCEPTANCE_REQUIRED");
        }
        physicalTime(input.occurredAt(), latestPhysicalTime(row));
        var originalLoan = originalLoan(row);
        loans.requireIdentity(number(row, "asset_id"), originalLoan, input.loanVersion());
        long next =
                assets.closeToStorage(
                        number(row, "asset_id"),
                        input.assetVersion(),
                        input.observedCode(),
                        input.warehouseId(),
                        input.warehouseVersion(),
                        input.locationId(),
                        input.locationVersion(),
                        current.id(),
                        id,
                        now());
        loans.closeForRepairInbound(
                number(row, "asset_id"),
                originalLoan,
                input.loanVersion(),
                id,
                current.id(),
                input.occurredAt(),
                now());
        db.update(
                "INSERT INTO"
                    + " fams_repair_inbound(repair_id,warehouse_id,location_id,warehouse_version,location_version,before_asset_version,occurred_at,evidence,actor_account_id,recorded_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                id,
                input.warehouseId(),
                input.locationId(),
                input.warehouseVersion(),
                input.locationVersion(),
                input.assetVersion(),
                input.occurredAt(),
                input.evidence().strip(),
                current.id(),
                now());
        db.update(
                "UPDATE fams_repair_request SET"
                    + " repair_status='COMPLETED',completed_at=?,asset_version=?,version=version+1,updated_at=?"
                    + " WHERE id=?",
                now(),
                next,
                now(),
                id);
        var origin = returnOrigin(id);
        if (origin != null) {
            returned.progress(number(origin, "followup_id"), id, "COMPLETED", null);
        }
        release(row, "REPAIR_COMPLETED_INBOUND");
        closeCollaborations(id);
        closeTodos(id);
        audit(
                actor,
                current,
                row,
                "REPAIR_COMPLETED_INBOUND",
                version,
                version + 1,
                input,
                Role.WAREHOUSE_OPERATOR.name());
        return remember(current, operation, key, fingerprint, header(id));
    }

    private void physicalGuard(
            Actor actor,
            Current current,
            Map<String, Object> row,
            long version,
            long assetVersion,
            long ownershipVersion,
            long lockVersion) {
        guard(actor, current, row, version, "WAREHOUSE_PROCESSING");
        if (number(row, "ownership_version") != ownershipVersion
                || number(row, "asset_version") != assetVersion) {
            reject(actor, current, row, "REPAIR_OWNERSHIP_OR_ASSET_CHANGED");
        }
        lock(row, lockVersion);
        factory.requireInternal(number(row, "id"));
        if (outstandingReturn(number(row, "id")) != null) {
            throw new RepairFailure(409, "REPAIR_RETURN_RECOLLECTION_REQUIRED");
        }
        if (!noCostHandling(row.get("allowed_method"))) {
            throw new RepairFailure(409, "REPAIR_APPROVAL_SCOPE_INVALID");
        }
    }

    private static boolean noCostHandling(Object method) {
        return "INTERNAL_NO_COST".equals(method) || "INTERNAL_OR_FACTORY".equals(method);
    }

    private LoanRepairPort.Context originalLoan(Map<String, Object> row) {
        return row.get("loan_snapshot_json") == null
                ? null
                : json.readValue(
                        row.get("loan_snapshot_json").toString(), LoanRepairPort.Context.class);
    }

    private void physicalTime(Instant actual, Object earliest) {
        Instant start = Instant.parse(wire(earliest).toString());
        if (!actual.equals(actual.truncatedTo(ChronoUnit.MILLIS))
                || actual.isBefore(start)
                || actual.isAfter(clock.instant().plusSeconds(2))) {
            throw new RepairFailure(400, "REPAIR_PHYSICAL_TIME_INVALID");
        }
    }

    private boolean hasPhysicalHandling(long id) {
        return !db.queryForList(
                        "SELECT repair_id FROM fams_repair_physical_handling WHERE repair_id=?", id)
                .isEmpty();
    }

    public Map<String, Object> returnTarget(Actor actor, long id) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        var row = header(id);
        visible(current, row);
        owner(actor, current, row);
        if (!"WAREHOUSE_PROCESSING".equals(row.get("repair_status")) || !hasPhysicalHandling(id)) {
            throw new RepairFailure(409, "REPAIR_PHYSICAL_ACCEPTANCE_REQUIRED");
        }
        return effectiveReturnTarget(row);
    }

    public Map<String, Object> deliverReturn(
            Actor actor, long id, long version, UUID key, RepairInput.ReturnDelivery input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        var row = header(id);
        visible(current, row);
        owner(actor, current, row);
        String operation = "RETURN_DELIVER:" + id, fingerprint = hash(operation, version, input);
        var prior = replay(actor, current, operation, key, fingerprint);
        if (prior != null) {
            return prior;
        }
        physicalGuard(
                actor,
                current,
                row,
                version,
                input.assetVersion(),
                input.ownershipVersion(),
                input.lockVersion());
        if (!hasPhysicalHandling(id)) {
            throw new RepairFailure(409, "REPAIR_PHYSICAL_ACCEPTANCE_REQUIRED");
        }
        physicalTime(input.occurredAt(), latestPhysicalTime(row));
        var approved = approvedTarget(row);
        if (approved != null) {
            physicalTime(input.occurredAt(), approved.get("approved_at"));
        }
        loans.requireContinuable(
                number(row, "asset_id"),
                originalLoan(row),
                input.loanVersion(),
                LocalDate.ofInstant(clock.instant(), ZoneId.of(row.get("timezone_id").toString())));
        var target = effectiveReturnTarget(row);
        if (number(target, "employeeVersion") != input.employeeVersion()
                || number(target, "organizationVersion") != input.organizationVersion()
                || number(target, "locationVersion") != input.locationVersion()) {
            throw new RepairFailure(409, "REPAIR_RETURN_TARGET_CHANGED");
        }
        assets.checkRepairPhysical(
                number(row, "asset_id"),
                input.assetVersion(),
                input.observedCode(),
                Long.parseLong(target.get("locationId").toString()),
                input.locationVersion());
        int round =
                java.util.Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COALESCE(MAX(round_number),0)+1 FROM"
                                        + " fams_repair_return_handover WHERE repair_id=?",
                                Integer.class,
                                id));
        db.update(
                "INSERT INTO"
                    + " fams_repair_return_handover(repair_id,round_number,target_snapshot_json,"
                    + "before_asset_version,delivered_at,delivery_evidence,delivered_by,recorded_at)"
                    + " VALUES (?,?,?,?,?,?,?,?)",
                id,
                round,
                json.writeValueAsString(target),
                input.assetVersion(),
                input.occurredAt(),
                input.evidence().strip(),
                current.id(),
                now());
        closeTodos(id);
        todo(
                id,
                "RETURN_RECEIPT",
                "RECEIVER",
                Long.parseLong(target.get("receiverAccountId").toString()));
        advanceReturn(actor, current, row, version, "REPAIR_RETURN_DELIVERED", input);
        return remember(current, operation, key, fingerprint, header(id));
    }

    public Map<String, Object> receiveReturn(
            Actor actor,
            long id,
            long version,
            UUID key,
            RepairInput.ReturnReceipt input,
            boolean refuse) {
        var current = actors.revalidateLocked(actor);
        var row = header(id);
        visible(current, row);
        var handover = returnHandover(id, input.handoverId());
        if (!isReturnReceiver(current, handover)) {
            throw new RepairFailure(403, "REPAIR_RETURN_RECEIVER_REQUIRED");
        }
        String operation = (refuse ? "RETURN_REFUSE:" : "RETURN_RECEIVE:") + id,
                fingerprint = hash(operation, version, input);
        var prior = replay(actor, current, operation, key, fingerprint);
        if (prior != null) {
            return prior;
        }
        guard(actor, current, row, version, "WAREHOUSE_PROCESSING");
        lock(row, input.lockVersion());
        if (!"WAITING_RECEIPT".equals(handover.get("handover_status"))
                || !input.physicalChecked()
                || input.assetVersion() != number(row, "asset_version")) {
            throw new RepairFailure(409, "REPAIR_RETURN_RECEIPT_CHANGED");
        }
        physicalTime(input.occurredAt(), handover.get("delivered_at"));
        long next = input.assetVersion();
        if (!refuse) {
            loans.requireContinuable(
                    number(row, "asset_id"),
                    originalLoan(row),
                    input.loanVersion(),
                    LocalDate.ofInstant(
                            clock.instant(), ZoneId.of(row.get("timezone_id").toString())));
            var target = json.readTree(handover.get("target_snapshot_json").toString());
            if (approvedTarget(row) != null) {
                next =
                        assets.closeApprovedReturn(
                                number(row, "asset_id"),
                                input.assetVersion(),
                                row.get("source_snapshot_json").toString(),
                                handover.get("target_snapshot_json").toString(),
                                input.observedCode(),
                                current.id(),
                                id,
                                now());
            } else {
                next =
                        assets.closeReturned(
                                number(row, "asset_id"),
                                input.assetVersion(),
                                row.get("source_snapshot_json").toString(),
                                input.observedCode(),
                                target.path("employeeVersion").asLong(),
                                target.path("organizationVersion").asLong(),
                                target.path("locationVersion").asLong(),
                                current.id(),
                                id,
                                now());
            }
        } else {
            assets.checkRepairIdentity(
                    number(row, "asset_id"), input.assetVersion(), input.observedCode());
        }
        db.update(
                "UPDATE fams_repair_return_handover SET handover_status=?,receipt_at=?,"
                        + "receipt_evidence=?,received_by=? WHERE id=?",
                refuse ? "REFUSED" : "RECEIVED",
                input.occurredAt(),
                input.evidence().strip(),
                current.id(),
                input.handoverId());
        closeTodos(id);
        if (refuse) {
            todo(id, "RETURN_EXCEPTION", "WAREHOUSE_OPERATOR", number(row, "processor_account_id"));
            advanceReturn(actor, current, row, version, "REPAIR_RETURN_REFUSED", input);
        } else {
            db.update(
                    "UPDATE fams_repair_request SET repair_status='COMPLETED',completed_at=?,"
                            + "asset_version=?,version=version+1,updated_at=? WHERE id=?",
                    now(),
                    next,
                    now(),
                    id);
            release(row, "REPAIR_RETURN_RECEIVED");
            closeCollaborations(id);
            audit(
                    actor,
                    current,
                    row,
                    "REPAIR_RETURN_RECEIVED",
                    version,
                    version + 1,
                    input,
                    role(current));
        }
        return remember(current, operation, key, fingerprint, header(id));
    }

    public Map<String, Object> recollectReturn(
            Actor actor, long id, long version, UUID key, RepairInput.ReturnRecollection input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        var row = header(id);
        visible(current, row);
        owner(actor, current, row);
        String operation = "RETURN_RECOLLECT:" + id, fingerprint = hash(operation, version, input);
        var prior = replay(actor, current, operation, key, fingerprint);
        if (prior != null) {
            return prior;
        }
        guard(actor, current, row, version, "WAREHOUSE_PROCESSING");
        lock(row, input.lockVersion());
        var handover = returnHandover(id, input.handoverId());
        if (input.assetVersion() != number(row, "asset_version")
                || input.ownershipVersion() != number(row, "ownership_version")
                || !List.of("WAITING_RECEIPT", "REFUSED")
                        .contains(handover.get("handover_status"))) {
            throw new RepairFailure(409, "REPAIR_RETURN_RECEIPT_CHANGED");
        }
        physicalTime(
                input.occurredAt(),
                handover.get("receipt_at") == null
                        ? handover.get("delivered_at")
                        : handover.get("receipt_at"));
        assets.checkRepairPhysical(
                number(row, "asset_id"),
                input.assetVersion(),
                input.observedCode(),
                input.actualLocationId(),
                input.locationVersion());
        db.update(
                "INSERT INTO fams_repair_return_recollection(handover_id,handler_employee_id,"
                    + "actual_location_id,location_version,occurred_at,evidence,actor_account_id,recorded_at)"
                    + " VALUES (?,?,?,?,?,?,?,?)",
                input.handoverId(),
                current.employeeId(),
                input.actualLocationId(),
                input.locationVersion(),
                input.occurredAt(),
                input.evidence().strip(),
                current.id(),
                now());
        db.update(
                "UPDATE fams_repair_return_handover SET handover_status='RECOLLECTED' WHERE id=?",
                input.handoverId());
        closeTodos(id);
        todo(id, "PROCESS", "WAREHOUSE_OPERATOR", current.id());
        advanceReturn(actor, current, row, version, "REPAIR_RETURN_RECOLLECTED", input);
        return remember(current, operation, key, fingerprint, header(id));
    }

    private Map<String, Object> outstandingReturn(long id) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_repair_return_handover WHERE repair_id=? AND"
                                + " handover_status IN ('WAITING_RECEIPT','REFUSED') FOR UPDATE",
                        id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> returnHandover(long repair, long id) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_repair_return_handover WHERE repair_id=? AND id=?"
                                + " FOR UPDATE",
                        repair,
                        id);
        if (rows.isEmpty()) {
            throw new RepairFailure(404, "REPAIR_RETURN_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private boolean isReturnReceiver(Current current, Map<String, Object> handover) {
        var target = json.readTree(handover.get("target_snapshot_json").toString());
        return current.id() == target.path("receiverAccountId").asLong()
                && current.employeeId() == target.path("employeeId").asLong()
                && current.organizationId() == target.path("organizationId").asLong()
                && (!"SHARED".equals(target.path("custodyType").asText())
                        || current.has(Role.DEPARTMENT_ASSET_MANAGER));
    }

    private Object latestPhysicalTime(Map<String, Object> row) {
        return factory.latestPhysicalTime(row);
    }

    private void advanceReturn(
            Actor actor,
            Current current,
            Map<String, Object> row,
            long version,
            String action,
            Object input) {
        db.update(
                "UPDATE fams_repair_request SET version=version+1,updated_at=? WHERE id=?",
                now(),
                row.get("id"));
        audit(actor, current, row, action, version, version + 1, input, role(current));
    }

    private Map<String, Object> approvedTarget(Map<String, Object> row) {
        var targets =
                db.queryForList(
                        "SELECT * FROM fams_repair_approved_return_target WHERE repair_id=? ORDER"
                                + " BY id DESC LIMIT 1",
                        row.get("id"));
        return targets.isEmpty() ? null : targets.getFirst();
    }

    private Map<String, Object> effectiveReturnTarget(Map<String, Object> row) {
        var approved = approvedTarget(row);
        return approved == null
                ? assets.returnTarget(
                        number(row, "asset_id"),
                        number(row, "asset_version"),
                        row.get("source_snapshot_json").toString())
                : assets.approvedReturnTarget(
                        number(row, "asset_id"),
                        number(row, "asset_version"),
                        row.get("source_snapshot_json").toString(),
                        approved.get("target_json").toString());
    }

    private boolean approvedReceiver(Current current, Map<String, Object> row) {
        var target = approvedTarget(row);
        return target != null
                && current.id() == number(target, "receiver_account_id")
                && java.util.Objects.equals(
                        current.employeeId(), nullable(target.get("employee_id")))
                && java.util.Objects.equals(
                        current.organizationId(), nullable(target.get("organization_id")))
                && (!"SHARED".equals(target.get("custody_type"))
                        || current.has(Role.DEPARTMENT_ASSET_MANAGER));
    }

    public void lockTargetChange(long id) {
        header(id);
        factory.requireInternal(id);
    }

    public void requireTargetChangeProcessor(long account, long id) {
        var row = header(id);
        if (!java.util.Objects.equals(account, nullable(row.get("processor_account_id")))) {
            throw new RepairFailure(403, "REPAIR_NOT_PROCESSOR");
        }
    }

    public Map<String, Object> targetChangeContext(Actor actor, long id, long item) {
        var current = actors.revalidateLocked(actor);
        var row = header(id);
        if (!current.has(Role.ASSET_ADMIN)) {
            require(current, Role.WAREHOUSE_OPERATOR);
            owner(actor, current, row);
        }
        if (item != number(row, "asset_id")
                || !"WAREHOUSE_PROCESSING".equals(row.get("repair_status"))
                || !hasPhysicalHandling(id)) {
            throw new RepairFailure(409, "REPAIR_PHYSICAL_ACCEPTANCE_REQUIRED");
        }
        if (outstandingReturn(id) != null) {
            throw new RepairFailure(409, "REPAIR_RETURN_RECOLLECTION_REQUIRED");
        }
        lock(row, null);
        var loan = loans.open(item);
        loans.requireIdentity(item, originalLoan(row), loan == null ? null : loan.version());
        var source = json.readTree(row.get("source_snapshot_json").toString());
        if (!List.of("IN_USE", "BORROWED").contains(source.path("lifecycle").asText())) {
            throw new RepairFailure(409, "REPAIR_RETURN_TARGET_CHANGED");
        }
        assets.checkRepairIdentity(
                item, number(row, "asset_version"), source.path("assetCode").asText());
        var approved = approvedTarget(row);
        var target = new LinkedHashMap<String, Object>();
        if (approved != null) {
            target.putAll(
                    json.readValue(
                            approved.get("target_json").toString(),
                            new tools.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        } else {
            target.put("custodyType", source.path("custodyType").asText());
            target.put("organizationId", source.path("usingOrganizationId").asText());
            target.put(
                    "employeeId",
                    source.path(
                                    "PERSONAL".equals(source.path("custodyType").asText())
                                            ? "responsibleEmployeeId"
                                            : "departmentResponsibleEmployeeId")
                            .asText());
            target.put("locationId", source.path("locationId").asText());
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("businessType", "REPAIR");
        result.put("businessId", Long.toString(id));
        result.put("itemId", Long.toString(item));
        result.put("version", row.get("version"));
        result.put("ownershipVersion", row.get("ownership_version"));
        result.put("processorAccountId", row.get("processor_account_id").toString());
        result.put("assetVersion", row.get("asset_version"));
        result.put("assetCode", source.path("assetCode").asText());
        result.put("target", target);
        result.put("approvedTargetId", approved == null ? null : approved.get("id").toString());
        result.put("loanVersion", loan == null ? null : loan.version());
        result.put("lastPhysicalTime", wire(latestPhysicalTime(row)));
        result.put(
                "lockVersion",
                db.queryForObject(
                        "SELECT lock_version FROM fams_asset_business_lock WHERE asset_id=? AND"
                            + " business_type='REPAIR' AND business_id=? AND released_at IS NULL",
                        Long.class,
                        item,
                        id));
        return result;
    }

    public void validateChangedReturnTarget(
            long id, long item, tools.jackson.databind.JsonNode target) {
        var row = header(id);
        if (item != number(row, "asset_id")) {
            throw new RepairFailure(409, "REPAIR_RETURN_TARGET_CHANGED");
        }
        var valid =
                assets.approvedReturnTarget(
                        item,
                        number(row, "asset_version"),
                        row.get("source_snapshot_json").toString(),
                        json.writeValueAsString(target));
        var approved = approvedTarget(row);
        var old =
                approved == null
                        ? json.readTree(row.get("source_snapshot_json").toString())
                        : json.readTree(approved.get("target_json").toString());
        boolean original = approved == null;
        String oldCustody = old.path("custodyType").asText();
        String oldPerson =
                old.path(
                                original
                                        ? "PERSONAL".equals(oldCustody)
                                                ? "responsibleEmployeeId"
                                                : "departmentResponsibleEmployeeId"
                                        : "employeeId")
                        .asText();
        if (oldCustody.equals(valid.get("custodyType"))
                && oldPerson.equals(valid.get("employeeId"))
                && old.path(original ? "usingOrganizationId" : "organizationId")
                        .asText()
                        .equals(valid.get("organizationId"))
                && old.path("locationId").asText().equals(valid.get("locationId"))) {
            throw new RepairFailure(400, "TARGET_CHANGE_UNCHANGED");
        }
    }

    public void applyChangedReturnTarget(
            Actor actor,
            long id,
            long item,
            tools.jackson.databind.JsonNode target,
            String observedCode,
            long proposal) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.ASSET_ADMIN);
        var row = header(id);
        targetChangeContext(actor, id, item);
        validateChangedReturnTarget(id, item, target);
        assets.checkRepairIdentity(item, number(row, "asset_version"), observedCode);
        var valid =
                assets.approvedReturnTarget(
                        item,
                        number(row, "asset_version"),
                        row.get("source_snapshot_json").toString(),
                        json.writeValueAsString(target));
        db.update(
                "INSERT INTO fams_repair_approved_return_target(repair_id,proposal_id,target_json,"
                    + "receiver_account_id,employee_id,organization_id,custody_type,approved_by,approved_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?)",
                id,
                proposal,
                json.writeValueAsString(valid),
                valid.get("receiverAccountId"),
                valid.get("employeeId"),
                valid.get("organizationId"),
                valid.get("custodyType"),
                current.id(),
                now());
        advanceReturn(
                actor,
                current,
                row,
                number(row, "version"),
                "REPAIR_RETURN_TARGET_APPROVED",
                Map.of("proposalId", Long.toString(proposal), "target", valid));
    }

    public Map<String, Object> reassignment(Actor actor, long id) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.ASSET_ADMIN);
        var row = reassignable(id);
        var snapshot = reassignmentSnapshot(row);
        var operators =
                db.queryForList(
                        "SELECT a.id,e.name FROM fams_system_account a JOIN fams_employee e ON"
                            + " e.id=a.employee_id JOIN fams_organization o ON"
                            + " o.id=e.organization_id JOIN fams_account_role r ON"
                            + " r.account_id=a.id WHERE a.status='ENABLED' AND"
                            + " a.initial_password_required=FALSE AND e.status='ACTIVE' AND"
                            + " o.status='ACTIVE' AND r.role_code='WAREHOUSE_OPERATOR' AND a.id<>?"
                            + " ORDER BY a.id",
                        row.get("processor_account_id"));
        return Map.of("snapshot", snapshot, "operators", wire(operators));
    }

    public Map<String, Object> reassign(
            Actor actor,
            long id,
            long version,
            UUID key,
            RepairReassignmentController.Input input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.ASSET_ADMIN);
        String operation = "REASSIGN:" + id;
        String fingerprint =
                hash(operation, version, canonicalReassignment(json.valueToTree(input)));
        var prior = replay(actor, current, operation, key, fingerprint);
        if (prior != null) {
            return prior;
        }
        // Target account locks precede the header, matching execution's actor-first order.
        var targets =
                db.queryForList(
                        "SELECT id,employee_id,status,initial_password_required FROM"
                                + " fams_system_account WHERE id=? FOR UPDATE",
                        input.processorAccountId());
        if (targets.isEmpty()
                || !"ENABLED".equals(targets.getFirst().get("status"))
                || Boolean.TRUE.equals(targets.getFirst().get("initial_password_required"))) {
            throw new RepairFailure(409, "REPAIR_PROCESSOR_INVALID");
        }
        var eligible =
                db.queryForList(
                        "SELECT e.id FROM fams_employee e JOIN fams_organization o ON"
                            + " o.id=e.organization_id JOIN fams_account_role r ON r.account_id=?"
                            + " WHERE e.id=? AND e.status='ACTIVE' AND o.status='ACTIVE' AND"
                            + " r.role_code='WAREHOUSE_OPERATOR' FOR UPDATE",
                        input.processorAccountId(),
                        targets.getFirst().get("employee_id"));
        if (eligible.isEmpty()) {
            throw new RepairFailure(409, "REPAIR_PROCESSOR_INVALID");
        }
        var row = reassignable(id);
        guard(actor, current, row, version, "WAREHOUSE_PROCESSING");
        if (input.processorAccountId() == number(row, "processor_account_id")) {
            throw new RepairFailure(409, "REPAIR_OWNERSHIP_CHANGED");
        }
        if (!input.unsynchronizedResultsChecked()) {
            throw new RepairFailure(400, "REPAIR_REASSIGNMENT_CHECK_REQUIRED");
        }
        var snapshot = reassignmentSnapshot(row);
        if (!json.readTree(json.writeValueAsString(snapshot)).equals(input.snapshot())) {
            throw new RepairFailure(409, "REPAIR_REASSIGNMENT_SNAPSHOT_CHANGED");
        }
        var detail = new LinkedHashMap<String, Object>();
        detail.put("previousProcessorAccountId", row.get("processor_account_id").toString());
        detail.put("processorAccountId", Long.toString(input.processorAccountId()));
        detail.put("snapshot", snapshot);
        detail.put("reason", input.reason());
        detail.put("evidence", input.evidence());
        detail.put("unsynchronizedResultsChecked", true);
        db.update(
                "UPDATE fams_repair_request SET"
                    + " processor_account_id=?,ownership_version=ownership_version+1,version=version+1,updated_at=?"
                    + " WHERE id=?",
                input.processorAccountId(),
                now(),
                id);
        db.update(
                "UPDATE fams_todo SET assignee_account_id=? WHERE business_type='REPAIR' AND"
                        + " business_id=? AND pool_type='WAREHOUSE_OPERATOR' AND closed_at IS NULL",
                input.processorAccountId(),
                id);
        var origin = returnOrigin(id);
        if (origin != null) {
            returned.progress(
                    number(origin, "followup_id"),
                    id,
                    "WAREHOUSE_PROCESSING",
                    input.processorAccountId());
        }
        audit(
                actor,
                current,
                row,
                "REPAIR_CONTROLLED_REASSIGNMENT",
                version,
                version + 1,
                detail,
                Role.ASSET_ADMIN.name());
        var result = get(actor, id);
        saveReceipt(current, operation, key, fingerprint, id, result);
        return result;
    }

    private tools.jackson.databind.JsonNode canonicalReassignment(
            tools.jackson.databind.JsonNode value) {
        if (value.isObject()) {
            var sorted = json.createObjectNode();
            value.properties().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(
                            entry ->
                                    sorted.set(
                                            entry.getKey(),
                                            canonicalReassignment(entry.getValue())));
            return sorted;
        }
        if (value.isArray()) {
            var ordered = json.createArrayNode();
            value.forEach(item -> ordered.add(canonicalReassignment(item)));
            return ordered;
        }
        return value;
    }

    private Map<String, Object> reassignable(long id) {
        var row = header(id);
        if (!"WAREHOUSE_PROCESSING".equals(row.get("repair_status"))
                || row.get("processor_account_id") == null
                || row.get("approval_snapshot_json") == null) {
            throw new RepairFailure(409, "REPAIR_NOT_PROCESSING");
        }
        return row;
    }

    private Map<String, Object> reassignmentSnapshot(Map<String, Object> row) {
        long id = number(row, "id"), assetId = number(row, "asset_id");
        lock(row, null);
        var loan = loans.open(assetId);
        loans.requireIdentity(assetId, originalLoan(row), loan == null ? null : loan.version());
        if (row.get("accepted_at") == null) {
            requireRepairSource(row);
        } else if (!factory.dispatchedIdentity(row)) {
            assets.checkRepairIdentity(
                    assetId,
                    number(row, "asset_version"),
                    json.readTree(row.get("source_snapshot_json").toString())
                            .path("assetCode")
                            .asText());
        }
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("id", Long.toString(id));
        snapshot.put("version", row.get("version"));
        snapshot.put("ownershipVersion", row.get("ownership_version"));
        snapshot.put("processorAccountId", row.get("processor_account_id").toString());
        snapshot.put("assetVersion", row.get("asset_version"));
        snapshot.put(
                "lockVersion",
                db.queryForObject(
                        "SELECT lock_version FROM fams_asset_business_lock WHERE asset_id=? AND"
                            + " business_type='REPAIR' AND business_id=? AND released_at IS NULL",
                        Long.class,
                        assetId,
                        id));
        snapshot.put("acceptedAt", wire(row.get("accepted_at")));
        snapshot.put(
                "physicalHandling",
                wire(
                        db.queryForList(
                                "SELECT * FROM fams_repair_physical_handling WHERE repair_id=?",
                                id)));
        snapshot.put("outstandingReturn", wire(outstandingReturn(id)));
        snapshot.put("lastPhysicalTime", wire(latestPhysicalTime(row)));
        var approved = approvedTarget(row);
        snapshot.put("approvedTargetId", approved == null ? null : approved.get("id").toString());
        snapshot.put("loan", loanWire(loan));
        var factorySummary = factory.summary(id);
        if (factorySummary != null) {
            snapshot.put("factorySummary", wire(factorySummary));
        }
        return snapshot;
    }

    private Map<String, Object> loanWire(LoanRepairPort.Context loan) {
        return loan == null
                ? null
                : Map.of(
                        "id",
                        Long.toString(loan.id()),
                        "version",
                        loan.version(),
                        "borrowerEmployeeId",
                        Long.toString(loan.borrowerEmployeeId()),
                        "organizationId",
                        Long.toString(loan.organizationId()),
                        "expectedReturnDate",
                        loan.expectedReturnDate().toString());
    }

    public boolean proxyProcessor(long account, long id, boolean locked) {
        return !db.queryForList(
                        "SELECT id FROM fams_repair_request WHERE id=? AND processor_account_id=?"
                                + (locked ? " FOR UPDATE" : ""),
                        id,
                        account)
                .isEmpty();
    }

    public Map<String, Object> proxySnapshot(Actor actor, long id, long item, boolean executing) {
        var current = actors.revalidateLocked(actor);
        var row = header(id);
        if (executing) {
            require(current, Role.WAREHOUSE_OPERATOR);
            owner(actor, current, row);
        } else {
            require(current, Role.ASSET_ADMIN);
        }
        var handover = outstandingReturn(id);
        if (item != number(row, "asset_id")
                || !"WAREHOUSE_PROCESSING".equals(row.get("repair_status"))
                || !hasPhysicalHandling(id)
                || handover == null
                || !"WAITING_RECEIPT".equals(handover.get("handover_status"))) {
            throw new RepairFailure(409, "REPAIR_PROXY_RECEIPT_NOT_PENDING");
        }
        lock(row, null);
        var loan = loans.open(item);
        loans.requireContinuable(
                item,
                originalLoan(row),
                loan == null ? null : loan.version(),
                LocalDate.ofInstant(clock.instant(), ZoneId.of(row.get("timezone_id").toString())));
        var target =
                assets.proxyReturnTarget(
                        item,
                        number(row, "asset_version"),
                        row.get("source_snapshot_json").toString(),
                        handover.get("target_snapshot_json").toString());
        if (number(handover, "before_asset_version") != number(row, "asset_version")) {
            throw new RepairFailure(409, "REPAIR_RETURN_RECEIPT_CHANGED");
        }
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("businessType", "REPAIR");
        snapshot.put("businessId", Long.toString(id));
        snapshot.put("itemId", Long.toString(item));
        snapshot.put("assetId", Long.toString(item));
        snapshot.put("version", row.get("version"));
        snapshot.put("ownershipVersion", row.get("ownership_version"));
        snapshot.put("processorAccountId", row.get("processor_account_id").toString());
        snapshot.put("assetVersion", row.get("asset_version"));
        snapshot.put(
                "assetCode",
                json.readTree(row.get("source_snapshot_json").toString())
                        .path("assetCode")
                        .asText());
        snapshot.put("handoverId", handover.get("id").toString());
        snapshot.put("deliveredAt", wire(handover.get("delivered_at")));
        snapshot.put("targetEmployeeId", target.get("employeeId"));
        snapshot.put("target", target);
        snapshot.put("loan", loanWire(loan));
        var approved = approvedTarget(row);
        snapshot.put("approvedTargetId", approved == null ? null : approved.get("id").toString());
        snapshot.put(
                "lockVersion",
                db.queryForObject(
                        "SELECT lock_version FROM fams_asset_business_lock WHERE asset_id=? AND"
                            + " business_type='REPAIR' AND business_id=? AND released_at IS NULL",
                        Long.class,
                        item,
                        id));
        return snapshot;
    }

    public Map<String, Object> proxyReceipt(
            Actor actor,
            long id,
            long item,
            Map<String, Object> expected,
            String observed,
            long confirmation,
            com.fams.proxy.ProxyReceiptPort.ExecutionEvidence proof) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        var row = header(id);
        owner(actor, current, row);
        var actual = proxySnapshot(actor, id, item, true);
        if (!json.readTree(json.writeValueAsString(actual))
                .equals(json.readTree(json.writeValueAsString(expected)))) {
            throw new RepairFailure(409, "PROXY_RECEIPT_SCOPE_CHANGED");
        }
        var handover = returnHandover(id, Long.parseLong(actual.get("handoverId").toString()));
        physicalTime(proof.actualReceivedAt(), handover.get("delivered_at"));
        if (confirmation < 1
                || proof.actualSigner() == null
                || proof.actualSigner().isBlank()
                || !proof.delegatedReceipt()
                        && !java.util.Objects.equals(
                                proof.actualSignerEmployeeId(),
                                Long.valueOf(actual.get("targetEmployeeId").toString()))
                || !json.readTree(proof.evidenceJson()).isArray()
                || json.readTree(proof.evidenceJson()).isEmpty()) {
            throw new RepairFailure(400, "REPAIR_PROXY_EVIDENCE_REQUIRED");
        }
        long next =
                assets.closeProxyReturn(
                        item,
                        number(row, "asset_version"),
                        row.get("source_snapshot_json").toString(),
                        handover.get("target_snapshot_json").toString(),
                        observed,
                        current.id(),
                        id,
                        confirmation,
                        now());
        db.update(
                "UPDATE fams_repair_return_handover SET handover_status='RECEIVED',receipt_at=?,"
                        + "receipt_evidence=?,received_by=? WHERE id=?",
                proof.actualReceivedAt(),
                "经管理员批准的异常代确认 #" + confirmation + "；实际签收人：" + proof.actualSigner(),
                current.id(),
                handover.get("id"));
        db.update(
                "INSERT INTO"
                    + " fams_repair_proxy_return_evidence(handover_id,repair_id,confirmation_id,"
                    + "actual_signer,actual_signer_employee_id,delegated_receipt,actual_received_at,approved_by,executed_by,metadata_json,evidence_json,recorded_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                handover.get("id"),
                id,
                confirmation,
                proof.actualSigner(),
                proof.delegatedReceipt() ? null : proof.actualSignerEmployeeId(),
                proof.delegatedReceipt(),
                proof.actualReceivedAt(),
                proof.approvedBy(),
                current.id(),
                proof.metadataJson(),
                proof.evidenceJson(),
                now());
        closeTodos(id);
        db.update(
                "UPDATE fams_repair_request SET"
                    + " repair_status='COMPLETED',completed_at=?,asset_version=?,version=version+1,updated_at=?"
                    + " WHERE id=?",
                now(),
                next,
                now(),
                id);
        release(row, "REPAIR_PROXY_RETURN_RECEIVED");
        closeCollaborations(id);
        audit(
                actor,
                current,
                row,
                "REPAIR_PROXY_RETURN_RECEIVED",
                number(row, "version"),
                number(row, "version") + 1,
                Map.of(
                        "confirmationId",
                        Long.toString(confirmation),
                        "handoverId",
                        handover.get("id").toString(),
                        "actualSigner",
                        proof.actualSigner(),
                        "actualReceivedAt",
                        proof.actualReceivedAt().toString(),
                        "delegatedReceipt",
                        proof.delegatedReceipt()),
                Role.WAREHOUSE_OPERATOR.name());
        return Map.of(
                "businessId",
                Long.toString(id),
                "repairId",
                Long.toString(id),
                "itemId",
                Long.toString(item),
                "assetCode",
                actual.get("assetCode"),
                "targetEmployeeId",
                actual.get("targetEmployeeId"),
                "status",
                "COMPLETED",
                "version",
                number(row, "version") + 1);
    }

    public void photoUploader(Actor actor, long id) {
        var current = actors.revalidateLocked(actor);
        var row = header(id);
        visible(current, row);
        if (number(row, "creator_account_id") != current.id()) {
            throw new RepairFailure(403, "REPAIR_CREATOR_REQUIRED");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Current photoAccess(Actor actor, long id) {
        var current = actors.revalidateLocked(actor);
        visible(current, header(id));
        return current;
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RepairConflict.class)
    public Map<String, Object> registerPhoto(
            Actor actor,
            long id,
            long version,
            UUID key,
            String filename,
            AttachmentIntake.QuarantinedAttachment attachment) {
        var current = actors.revalidateLocked(actor);
        var row = header(id);
        visible(current, row);
        if (number(row, "creator_account_id") != current.id()) {
            throw new RepairFailure(403, "REPAIR_CREATOR_REQUIRED");
        }
        String operation = "PHOTO:" + id,
                fingerprint =
                        hash(
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
        var prior = replay(actor, current, operation, key, fingerprint);
        if (prior != null) {
            return prior;
        }
        guard(actor, current, row, version, "PENDING_ADMIN_DECISION");
        if (attachment.scanResult() == MalwareScanner.Result.UNAVAILABLE) {
            throw new RepairFailure(503, "REPAIR_PHOTO_SCAN_UNAVAILABLE");
        }
        if (attachment.scanResult() != MalwareScanner.Result.CLEAN) {
            throw new RepairFailure(422, "REPAIR_PHOTO_REJECTED");
        }
        if (!attachment.format().image()) {
            throw new RepairFailure(400, "REPAIR_PHOTO_IMAGE_REQUIRED");
        }
        var registry = attachments.getIfAvailable();
        if (registry == null) {
            throw new RepairFailure(503, "REPAIR_PHOTOS_UNAVAILABLE");
        }
        registry.register("REPAIR", id, version, current.id(), filename, attachment);
        db.update(
                "INSERT INTO"
                    + " fams_repair_photo(object_id,repair_id,purpose,content_sha256,uploader_account_id,recorded_at)"
                    + " VALUES (?,?,'FAULT',?,?,?)",
                attachment.file().id().toString(),
                id,
                attachment.file().sha256(),
                current.id(),
                now());
        db.update(
                "UPDATE fams_repair_request SET version=version+1,updated_at=? WHERE id=?",
                now(),
                id);
        audit(
                actor,
                current,
                row,
                "REPAIR_FAULT_PHOTO_ADDED",
                version,
                version + 1,
                Map.of(
                        "objectId",
                        attachment.file().id().toString(),
                        "sha256",
                        attachment.file().sha256()),
                role(current));
        var result =
                Map.<String, Object>of(
                        "repairId",
                        Long.toString(id),
                        "objectId",
                        attachment.file().id().toString(),
                        "version",
                        version + 1);
        saveReceipt(current, operation, key, fingerprint, id, result);
        return result;
    }

    private Map<String, Object> replay(
            Actor actor, Current current, String operation, UUID key, String fingerprint) {
        var rows =
                db.queryForList(
                        "SELECT repair_id,payload_sha256,response_json FROM fams_repair_receipt"
                                + " WHERE actor_account_id=? AND operation=? AND request_id=? FOR"
                                + " UPDATE",
                        current.id(),
                        operation,
                        key.toString());
        if (rows.isEmpty()) {
            return null;
        }
        var prior = rows.getFirst();
        var row = header(number(prior, "repair_id"));
        visible(current, row);
        if (!fingerprint.equals(prior.get("payload_sha256"))) {
            reject(actor, current, row, "REPAIR_IDEMPOTENCY_CONFLICT");
        }
        return json.readValue(
                prior.get("response_json").toString(),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> remember(
            Current current,
            String operation,
            UUID key,
            String fingerprint,
            Map<String, Object> row) {
        var result = view(row);
        saveReceipt(current, operation, key, fingerprint, number(row, "id"), result);
        return result;
    }

    private void saveReceipt(
            Current current,
            String operation,
            UUID key,
            String fingerprint,
            long id,
            Map<String, Object> result) {
        db.update(
                "INSERT INTO"
                    + " fams_repair_receipt(actor_account_id,operation,request_id,repair_id,payload_sha256,response_json,created_at)"
                    + " VALUES (?,?,?,?,?,?,?)",
                current.id(),
                operation,
                key.toString(),
                id,
                fingerprint,
                json.writeValueAsString(result),
                now());
    }

    private Map<String, Object> header(long id) {
        var rows = db.queryForList("SELECT * FROM fams_repair_request WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new RepairFailure(404, "REPAIR_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private void visible(Current current, Map<String, Object> row) {
        if (!(approvedReceiver(current, row)
                || current.has(Role.ASSET_ADMIN)
                || current.id() == number(row, "creator_account_id")
                || java.util.Objects.equals(
                        current.employeeId(), nullable(row.get("responsible_employee_id")))
                || java.util.Objects.equals(
                        current.employeeId(), nullable(row.get("shared_responsible_employee_id")))
                || current.has(Role.DEPARTMENT_ASSET_MANAGER)
                        && java.util.Objects.equals(
                                current.organizationId(),
                                nullable(row.get("using_organization_id")))
                || current.has(Role.WAREHOUSE_OPERATOR)
                        && row.get("approval_snapshot_json") != null)) {
            throw new RepairFailure(403, "REPAIR_NOT_VISIBLE");
        }
    }

    private void require(Current current, Role role) {
        if (!current.has(role)) {
            throw new RepairFailure(403, "REPAIR_" + role.name() + "_REQUIRED");
        }
    }

    private void owner(Actor actor, Current current, Map<String, Object> row) {
        if (!java.util.Objects.equals(current.id(), nullable(row.get("processor_account_id")))) {
            reject(actor, current, row, "REPAIR_NOT_PROCESSOR");
        }
    }

    private void guard(
            Actor actor, Current current, Map<String, Object> row, long version, String status) {
        if (number(row, "version") != version || !status.equals(row.get("repair_status"))) {
            reject(actor, current, row, "REPAIR_VERSION_CONFLICT");
        }
    }

    private void lock(Map<String, Object> row, Long version) {
        var rows =
                db.queryForList(
                        "SELECT lock_version FROM fams_asset_business_lock WHERE asset_id=? AND"
                            + " business_type='REPAIR' AND business_id=? AND released_at IS NULL"
                            + " FOR UPDATE",
                        row.get("asset_id"),
                        row.get("id"));
        if (rows.size() != 1
                || version != null
                        && ((Number) rows.getFirst().get("lock_version")).longValue() != version) {
            throw new RepairFailure(409, "REPAIR_LOCK_CHANGED");
        }
    }

    private void release(Map<String, Object> row, String reason) {
        if (db.update(
                        "UPDATE fams_asset_business_lock SET"
                            + " released_at=?,release_reason=?,lock_version=lock_version+1 WHERE"
                            + " asset_id=? AND business_type='REPAIR' AND business_id=? AND"
                            + " released_at IS NULL",
                        now(),
                        reason,
                        row.get("asset_id"),
                        row.get("id"))
                != 1) {
            throw new RepairFailure(409, "REPAIR_LOCK_CHANGED");
        }
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

    private void closeCollaborations(long id) {
        db.update(
                "UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE closed_at IS NULL AND"
                        + " ((business_type='REPAIR_TECH' AND business_id IN (SELECT id FROM"
                        + " fams_repair_invitation WHERE repair_id=?)) OR"
                        + " (business_type='REPAIR_OPINION' AND business_id IN (SELECT id FROM"
                        + " fams_repair_opinion WHERE repair_id=?)))",
                now(),
                id,
                id);
        db.update(
                "UPDATE fams_repair_invitation SET"
                    + " invitation_status='CLOSED',ended_at=?,end_reason='REPAIR_TERMINAL',version=version+1"
                    + " WHERE repair_id=? AND invitation_status='ACTIVE'",
                now(),
                id);
    }

    private void closeTodos(long id) {
        db.update(
                "UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE business_type='REPAIR'"
                        + " AND business_id=? AND closed_at IS NULL",
                now(),
                id);
    }

    private void reject(Actor actor, Current current, Map<String, Object> row, String code) {
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
                            + " AND action<>'REPAIR_CONFLICT_REJECTED' ORDER BY id DESC LIMIT 1 FOR"
                            + " SHARE",
                        row.get("id"));
        if (!latest.isEmpty()) {
            fields.put("processedBy", latest.getFirst().get("actor_username").toString());
            fields.put("processedAt", wire(latest.getFirst().get("occurred_at")).toString());
        }
        audit(
                actor,
                current,
                row,
                "REPAIR_CONFLICT_REJECTED",
                number(row, "version"),
                number(row, "version"),
                Map.of("code", code, "fields", fields),
                role(current));
        throw new RepairConflict(code, fields);
    }

    private void audit(
            Actor actor,
            Current current,
            Map<String, Object> row,
            String action,
            long before,
            long after,
            Object detail,
            String role) {
        String body = json.writeValueAsString(detail);
        var time = now();
        db.update(
                "INSERT INTO"
                    + " fams_repair_event(repair_id,action,before_version,after_version,actor_account_id,actor_username,actor_role,detail_json,occurred_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?)",
                row.get("id"),
                action,
                before,
                after,
                current.id(),
                current.username(),
                role,
                body,
                time);
        db.update(
                "INSERT INTO"
                    + " fams_audit_event(request_id,actor_account_id,actor_role,action,resource_type,resource_id,before_version,after_version,occurred_at,detail_json)"
                    + " VALUES (?,?,?,?,'REPAIR',?,?,?,?,?)",
                actor.requestId().toString(),
                current.id(),
                role,
                action,
                row.get("id"),
                before,
                after,
                time,
                body);
    }

    private Map<String, Object> view(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        for (var field :
                Map.ofEntries(
                                Map.entry("id", "id"),
                                Map.entry("asset_id", "assetId"),
                                Map.entry("creator_account_id", "creatorAccountId"),
                                Map.entry("version", "version"),
                                Map.entry("asset_version", "assetVersion"),
                                Map.entry("repair_status", "status"),
                                Map.entry("fault", "fault"),
                                Map.entry("impact", "impact"),
                                Map.entry("occurred_at", "occurredAt"),
                                Map.entry("allowed_method", "allowedMethod"),
                                Map.entry("processor_account_id", "processorAccountId"),
                                Map.entry("ownership_version", "ownershipVersion"),
                                Map.entry("claimed_at", "claimedAt"),
                                Map.entry("accepted_at", "acceptedAt"),
                                Map.entry("completed_at", "completedAt"))
                        .entrySet()) {
            Object value = row.get(field.getKey());
            result.put(
                    field.getValue(),
                    field.getKey().equals("id") || field.getKey().endsWith("_id")
                            ? value == null ? null : value.toString()
                            : wire(value));
        }
        result.put(
                "source", json.readValue(row.get("source_snapshot_json").toString(), Object.class));
        return result;
    }

    private Object wire(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach(
                    (k, v) ->
                            result.put(
                                    k.toString(),
                                    k.toString().equals("id") || k.toString().endsWith("_id")
                                            ? v == null ? null : v.toString()
                                            : k.toString().endsWith("_json")
                                                            && v instanceof String text
                                                    ? json.readValue(text, Object.class)
                                                    : wire(v)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::wire).toList();
        }
        if (value instanceof java.sql.Timestamp time) {
            return time.toInstant().toString();
        }
        if (value instanceof java.time.LocalDateTime time) {
            return time.toInstant(java.time.ZoneOffset.UTC).toString();
        }
        return value;
    }

    private String hash(String operation, long version, Object body) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (operation
                                                            + "\n"
                                                            + version
                                                            + "\n"
                                                            + json.writeValueAsString(body))
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private String role(Current current) {
        return current.roles().stream().map(Enum::name).sorted().findFirst().orElseThrow();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private Long nullable(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private Long reference(tools.jackson.databind.JsonNode row, String key) {
        var value = row.path(key);
        return value.isNull() || value.isMissingNode() ? null : value.asLong();
    }
}
