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
    private final ObjectProvider<AttachmentEvidencePort> evidence;
    private final ObjectProvider<AttachmentRegistry> attachments;

    public RepairService(
            JdbcTemplate db,
            ObjectMapper json,
            Clock clock,
            RequisitionActorContext actors,
            AssetRepairPort assets,
            LoanRepairPort loans,
            ObjectProvider<AttachmentEvidencePort> evidence,
            ObjectProvider<AttachmentRegistry> attachments) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actors = actors;
        this.assets = assets;
        this.loans = loans;
        this.evidence = evidence;
        this.attachments = attachments;
    }

    public Map<String, Object> eligible(Actor actor, int page, int size) {
        return assets.eligible(actors.revalidateLocked(actor), page, size);
    }

    public Map<String, Object> list(Actor actor, int page, int size) {
        var current = actors.revalidateLocked(actor);
        String where =
                " FROM fams_repair_request WHERE (? OR creator_account_id=? OR"
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
        boolean pending = "PENDING_ADMIN_DECISION".equals(row.get("repair_status"));
        boolean processing =
                "WAREHOUSE_PROCESSING".equals(row.get("repair_status"))
                        && current.has(Role.WAREHOUSE_OPERATOR)
                        && java.util.Objects.equals(
                                current.id(), nullable(row.get("processor_account_id")));
        result.put(
                "actions",
                Map.of(
                        "approve", current.has(Role.ASSET_ADMIN) && pending,
                        "claim",
                                current.has(Role.WAREHOUSE_OPERATOR)
                                        && "WAITING_WAREHOUSE_CLAIM"
                                                .equals(row.get("repair_status")),
                        "acceptInPlace", processing && row.get("accepted_at") == null,
                        "completeInPlace", processing && row.get("accepted_at") != null,
                        "addFaultPhoto",
                                port != null
                                        && pending
                                        && current.id() == number(row, "creator_account_id"),
                        "cancel",
                                pending
                                        && (current.has(Role.ASSET_ADMIN)
                                                || current.id()
                                                        == number(row, "creator_account_id"))));
        return result;
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
                || approve && !"INTERNAL_NO_COST".equals(input.allowedMethod())
                || !approve && input.allowedMethod() != null) {
            throw new RepairFailure(400, "REPAIR_APPROVAL_SCOPE_INVALID");
        }
        if (approve) {
            assets.requireSubmitted(number(row, "asset_id"), number(row, "asset_version"));
        }
        var basis = new LinkedHashMap<String, Object>();
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
                input.reason().strip(),
                current.id(),
                now());
        db.update(
                "UPDATE fams_repair_request SET"
                    + " repair_status=?,allowed_method=?,approval_snapshot_json=?,version=version+1,updated_at=?"
                    + " WHERE id=?",
                approve ? "WAITING_WAREHOUSE_CLAIM" : "REJECTED",
                input.allowedMethod(),
                approve ? frozen : null,
                now(),
                id);
        closeTodos(id);
        if (approve) {
            todo(id, "CLAIM", "WAREHOUSE_OPERATOR", null);
        } else {
            release(row, "REPAIR_REJECTED");
        }
        audit(
                actor,
                current,
                row,
                "REPAIR_DECIDED",
                version,
                version + 1,
                input,
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
        release(row, "REPAIR_CANCELLED");
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
        if (!input.neverLeftCustodian()
                || input.assetVersion() != number(row, "asset_version")
                || !"INTERNAL_NO_COST".equals(row.get("allowed_method"))
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
        if (!(current.has(Role.ASSET_ADMIN)
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
