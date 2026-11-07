package com.fams.scrap;

import com.fams.asset.AssetHandoverPort;
import com.fams.asset.AssetScrapPort;
import com.fams.attachment.AttachmentEvidencePort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;
import com.fams.loss.LossScrapSourcePort;
import com.fams.loan.LoanRepairPort;
import com.fams.repair.RepairService;
import com.fams.repair.RepairFactoryService.ScrapEvidence;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@Profile("fams-product")
@Transactional
public class ScrapReviewService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final RequisitionActorContext actors;
    private final AssetScrapPort assets;
    private final com.fams.loan.LoanLossPort loans;
    private final LossScrapSourcePort losses;
    private final RepairService repairs;
    private final com.fams.auth.ScrapSensitiveAccess sensitive;
    private final ObjectProvider<AttachmentEvidencePort> evidence;

    public ScrapReviewService(JdbcTemplate db, ObjectMapper json, Clock clock,
            RequisitionActorContext actors, AssetScrapPort assets, com.fams.loan.LoanLossPort loans,
            LossScrapSourcePort losses, RepairService repairs, com.fams.auth.ScrapSensitiveAccess sensitive,
            ObjectProvider<AttachmentEvidencePort> evidence) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actors = actors;
        this.assets = assets;
        this.loans = loans;
        this.losses = losses;
        this.repairs = repairs;
        this.sensitive = sensitive;
        this.evidence = evidence;
    }

    public record Decision(
            @NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long lockVersion,
            @NotBlank String result,
            @NotBlank @Size(max = 1000) String reason,
            @Size(max = 500) String approvedDisposition) {}

    public record Claim(@NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long lockVersion) {}

    public record Receipt(String id, String assetId, long version, long assetVersion,
            long ownershipVersion, String status, String processorAccountId) {}

    public Receipt decide(Actor actor, long id, long version, UUID key, Decision input) {
        var current = current(actor, Role.ASSET_ADMIN);
        String operation = "DECIDE:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        boolean approve = "APPROVE".equals(input.result());
        if (!approve && !"REJECT".equals(input.result())
                || approve && (input.approvedDisposition() == null || input.approvedDisposition().isBlank())) {
            throw new AssetHandoverPort.Failure("SCRAP_DECISION_INVALID", 400);
        }
        var request = guarded(id, version, input.assetVersion(), input.lockVersion(), "PENDING_ADMIN_DECISION");
        if (approve && "LOSS".equals(request.get("source_type")) && !lossWriteOff(input.approvedDisposition())) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_OUTSIDE_APPROVAL", 409);
        }
        // Match the locked request version even if an earlier read established a MySQL snapshot.
        var attachments = db.queryForList("SELECT detail_json FROM fams_scrap_event"
                + " WHERE request_id=? AND action='SCRAP_ATTACHMENT_ADDED' ORDER BY id FOR UPDATE", String.class, id)
                .stream().map(json::readTree).toList();
        if (approve && !attachments.isEmpty()) {
            sensitive.requireRead(actor);
        }
        long asset = number(request, "asset_id");
        var time = now();
        boolean factory = "FACTORY_REPLACEMENT".equals(request.get("source_type"));
        boolean repairSource = factory || "REPAIR".equals(request.get("source_type"));
        if (approve && repairSource) {
            var proof = json.readTree(request.get("source_snapshot_json").toString()).path("repairEvidence");
            if (request.get("processor_account_id") == null || !proof.has("repairVersion")
                    || !repairs.canRestoreFromScrap(number(request, "source_business_id"), proof.path("repairVersion").asLong(-1),
                    asset, input.assetVersion(), number(request, "processor_account_id"), repairBorrowing(proof))) {
                throw new AssetHandoverPort.Failure("SCRAP_SOURCE_OR_OWNERSHIP_CHANGED", 409);
            }
            if (factory) {
                var evidence = factoryEvidence(request, proof);
                if (evidence == null || !evidence.method().equals(input.approvedDisposition().strip())) {
                    throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_OUTSIDE_APPROVAL", 409);
                }
            }
        }
        String status = repairSource ? "PROCESSING" : "WAITING_WAREHOUSE_CLAIM";
        long assetVersion = input.assetVersion();
        if (!approve) {
            var source = json.readTree(request.get("source_snapshot_json").toString());
            boolean restored = switch (request.get("source_type").toString()) {
                case "STANDALONE" -> assets.restoreStandalone(asset, assetVersion, id,
                        source.path("lifecycle_status").asText(), current.id(), time);
                case "LOSS" -> restoreLoss(asset, assetVersion, id, source, current.id(), time);
                case "REPAIR", "FACTORY_REPLACEMENT" ->
                    restoreRepair(actor, current, request, assetVersion, input.lockVersion(), source, time, factory);
                default -> false;
            };
            status = restored ? "REJECTED" : "RESTORE_REQUIRED";
            if (restored) {
                assetVersion++;
            }
            if (restored && !repairSource) {
                db.update("UPDATE fams_asset_business_lock SET released_at=?,release_reason='SCRAP_REJECTED',"
                        + "lock_version=lock_version+1 WHERE asset_id=? AND business_type='SCRAP'"
                        + " AND business_id=? AND business_item_id=? AND released_at IS NULL",
                        time, asset, id, id);
            }
        }
        db.update("UPDATE fams_scrap_request SET request_status=?,decision_snapshot_json=?,asset_version=?,"
                        + "version=version+1,updated_at=? WHERE id=?",
                status, json.writeValueAsString(Map.of("decision", input, "decidedBy", Long.toString(current.id()),
                        "decidedAt", time, "attachmentEvidence", attachments)), assetVersion, time, id);
        closeTodo(id, "APPROVAL", time);
        if (approve && repairSource) {
            todo(id, "EXECUTE", "WAREHOUSE_OPERATOR", number(request, "processor_account_id"), time);
        } else if (approve) {
            todo(id, "CLAIM", "WAREHOUSE_OPERATOR", null, time);
        } else if ("RESTORE_REQUIRED".equals(status)) {
            todo(id, "RESTORE_REQUIRED", "ASSET_ADMIN", null, time);
        }
        String action = approve ? "SCRAP_APPROVED" : "SCRAP_REJECTED";
        audit(actor, current, Role.ASSET_ADMIN, id, version, action, input, time);
        return remember(current, operation, key, hash,
                new Receipt(Long.toString(id), Long.toString(asset), version + 1, assetVersion,
                        number(request, "ownership_version"), status,
                        repairSource && request.get("processor_account_id") != null ? request.get("processor_account_id").toString() : null), time);
    }

    private boolean restoreRepair(Actor actor, Current current, Map<String, Object> request,
            long assetVersion, long lockVersion, tools.jackson.databind.JsonNode source, Instant time, boolean factory) {
        var proof = source.path("repairEvidence");
        if (!proof.has("repairVersion") || request.get("processor_account_id") == null) {
            return false;
        }
        long asset = number(request, "asset_id"), id = number(request, "id"), repair = number(request, "source_business_id");
        long repairVersion = proof.path("repairVersion").asLong(-1), processor = number(request, "processor_account_id");
        if (!repairs.canRestoreFromScrap(repair, repairVersion, asset, assetVersion, processor, repairBorrowing(proof))
                || factory && factoryEvidence(request, proof) == null
                || !assets.restoreRepair(asset, assetVersion, id, source, current.id(), time, factory)) {
            return false;
        }
        repairs.restoreFromScrap(actor, current, repair, repairVersion, asset, id, lockVersion, assetVersion + 1,
                processor, factory);
        return true;
    }

    private LoanRepairPort.Context repairBorrowing(tools.jackson.databind.JsonNode proof) {
        var borrowing = proof.path("borrowing");
        return borrowing.isMissingNode() || borrowing.isNull() ? null : json.readValue(borrowing.toString(), LoanRepairPort.Context.class);
    }

    private ScrapEvidence factoryEvidence(Map<String, Object> request, tools.jackson.databind.JsonNode proof) {
        if (!proof.path("context").has("replacementId") || request.get("source_item_id") == null) {
            return null;
        }
        var expected = json.readValue(proof.path("context").toString(), ScrapEvidence.class);
        return expected.replacementId() == number(request, "source_item_id")
                && repairs.matchesFactoryScrapEvidence(number(request, "source_business_id"),
                        number(request, "asset_id"), expected) ? expected : null;
    }

    static boolean physicalSource(Object source) {
        return "STANDALONE".equals(source) || "REPAIR".equals(source);
    }

    private boolean restoreLoss(long asset, long version, long id, tools.jackson.databind.JsonNode source,
            long actor, Instant time) {
        if ("BORROWED".equals(source.path("lifecycle_status").asText())) {
            var proof = source.path("lossEvidence");
            var loan = loans.openBorrowing(asset, source.path("responsible_employee_id").asLong(),
                    source.path("using_organization_id").asLong()).orElse(null);
            if (loan == null || !Long.toString(loan.id()).equals(proof.path("loanId").asText())
                    || loan.version() != proof.path("loanVersion").asLong(-1)) {
                return false;
            }
        } else if (loans.hasOpenBorrowing(asset)) {
            return false;
        }
        return assets.restoreLoss(asset, version, id, source, actor, time);
    }

    public Receipt claim(Actor actor, long id, long version, UUID key, Claim input) {
        var current = current(actor, Role.WAREHOUSE_OPERATOR);
        String operation = "CLAIM:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var request = guarded(id, version, input.assetVersion(), input.lockVersion(), "WAITING_WAREHOUSE_CLAIM");
        long ownership = number(request, "ownership_version") + 1;
        var time = now();
        db.update("UPDATE fams_scrap_request SET request_status='PROCESSING',processor_account_id=?,"
                        + "ownership_version=?,version=version+1,updated_at=? WHERE id=?",
                current.id(), ownership, time, id);
        closeTodo(id, "CLAIM", time);
        todo(id, "EXECUTE", "WAREHOUSE_OPERATOR", current.id(), time);
        audit(actor, current, Role.WAREHOUSE_OPERATOR, id, version, "SCRAP_CLAIMED", input, time);
        return remember(current, operation, key, hash, new Receipt(Long.toString(id),
                Long.toString(number(request, "asset_id")), version + 1, input.assetVersion(),
                ownership, "PROCESSING", Long.toString(current.id())), time);
    }

    public record Execution(
            @NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long lockVersion,
            @NotNull @PositiveOrZero Long ownershipVersion,
            @NotBlank @Size(max = 64) String observedCode,
            @Size(max = 128) String observedSerial,
            @NotNull @Positive Long locationId,
            @NotNull @PositiveOrZero Long locationVersion,
            @NotNull @Positive Long custodianEmployeeId,
            @NotNull @PositiveOrZero Long custodianVersion,
            @NotNull Instant occurredAt,
            @NotNull Boolean physicalIdentityVerified,
            @NotBlank @Size(min = 10, max = 2000) String evidence,
            @NotBlank @Size(max = 500) String dispositionMethod,
            LocalDate plannedDisposalDate) {}

    public Receipt execute(Actor actor, long id, long version, UUID key, Execution input) {
        var current = current(actor, Role.WAREHOUSE_OPERATOR);
        String operation = "EXECUTE:" + id;
        String hash = fingerprint(version, input);
        var request = db.queryForList("SELECT processor_account_id FROM fams_scrap_request WHERE id=? FOR UPDATE", id);
        if (request.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_NOT_FOUND", 404);
        }
        if (!java.util.Objects.equals(request.getFirst().get("processor_account_id"), current.id())) {
            throw new AssetHandoverPort.Failure("SCRAP_PROCESSOR_REQUIRED", 403);
        }
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var row = guarded(id, version, input.assetVersion(), input.lockVersion(), "PROCESSING");
        if (!physicalSource(row.get("source_type"))
                || number(row, "ownership_version") != input.ownershipVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_SOURCE_OR_OWNERSHIP_CHANGED", 409);
        }
        var approval = json.readTree(row.get("decision_snapshot_json").toString()).path("decision");
        if (!input.dispositionMethod().strip().equals(approval.path("approvedDisposition").asText().strip())) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_OUTSIDE_APPROVAL", 409);
        }
        var time = now();
        Instant created = instant(row.get("updated_at"));
        if (!Boolean.TRUE.equals(input.physicalIdentityVerified()) || input.evidence().strip().length() < 10
                || input.occurredAt().isBefore(created) || input.occurredAt().isAfter(time.plusSeconds(2))
                || !input.occurredAt().equals(input.occurredAt().truncatedTo(ChronoUnit.MILLIS))) {
            throw new AssetHandoverPort.Failure("SCRAP_PHYSICAL_EVIDENCE_INVALID", 400);
        }
        if (db.queryForList("SELECT e.id FROM fams_employee e JOIN fams_organization o ON o.id=e.organization_id"
                + " WHERE e.id=? AND e.version=? AND e.status='ACTIVE' AND o.status='ACTIVE' FOR UPDATE",
                input.custodianEmployeeId(), input.custodianVersion()).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_CUSTODIAN_CHANGED", 409);
        }
        String parameters = db.queryForObject("SELECT v.snapshot_json FROM fams_business_parameter_state s"
                + " JOIN fams_business_parameter_version v ON v.id=s.current_version_id"
                + " WHERE s.singleton_id=1 AND v.status='ACTIVE' FOR SHARE", String.class);
        var settings = json.readTree(parameters);
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(settings.path("timezone").asText())));
        LocalDate due = input.plannedDisposalDate() == null ? today.plusDays(30) : input.plannedDisposalDate();
        if (due.isBefore(today)) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_DATE_INVALID", 400);
        }
        long asset = number(row, "asset_id");
        if ("REPAIR".equals(row.get("source_type"))) {
            var source = json.readTree(row.get("source_snapshot_json").toString());
            var proof = source.path("repairEvidence");
            var borrowing = repairBorrowing(proof);
            assets.requireFrozenRepair(asset, input.assetVersion(), source);
            repairs.closeFromScrap(actor, current, number(row, "source_business_id"), proof.path("repairVersion").asLong(-1),
                    asset, input.assetVersion(), id, borrowing);
            if (borrowing != null) {
                loans.terminateLost(asset, borrowing.borrowerEmployeeId(), borrowing.organizationId(),
                        borrowing.id(), borrowing.version(), time);
            }
        } else {
            loans.requireRecovery(asset, "IN_USE", null, null);
        }
        long assetVersion = assets.executeStandalone(asset, input.assetVersion(), id, input.lockVersion(),
                input.observedCode(), input.observedSerial(), input.locationId(), input.locationVersion(), current.id(), time);
        String execution = json.writeValueAsString(input);
        db.update("UPDATE fams_scrap_request SET request_status='EXECUTED',execution_snapshot_json=?,"
                + "asset_version=?,version=version+1,updated_at=? WHERE id=?",
                execution, assetVersion, time, id);
        db.update("INSERT INTO fams_scrap_disposition(request_id,processor_account_id,disposition_status,"
                + "due_date,custody_snapshot_json,created_at,updated_at)"
                + " VALUES (?,?,'PENDING_DISPOSITION',?,?,?,?)",
                id, current.id(), due, execution, time, time);
        releaseExecutedLock(id, asset, input.lockVersion(), time);
        closeTodo(id, "EXECUTE", time);
        todo(id, "DISPOSITION", "WAREHOUSE_OPERATOR", current.id(), time);
        audit(actor, current, Role.WAREHOUSE_OPERATOR, id, version, "SCRAP_EXECUTED", input, time);
        return remember(current, operation, key, hash, new Receipt(Long.toString(id), Long.toString(asset),
                version + 1, assetVersion, input.ownershipVersion(), "EXECUTED", Long.toString(current.id())), time);
    }

    public record FactoryExecution(
            @NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long lockVersion,
            @NotNull @PositiveOrZero Long ownershipVersion,
            @NotNull Instant occurredAt) {}

    public Receipt executeFactory(Actor actor, long id, long version, UUID key, FactoryExecution input) {
        var current = current(actor, Role.WAREHOUSE_OPERATOR);
        String operation = "EXECUTE_FACTORY:" + id;
        String hash = fingerprint(version, input);
        var rows = db.queryForList("SELECT processor_account_id FROM fams_scrap_request WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_NOT_FOUND", 404);
        }
        if (!Objects.equals(rows.getFirst().get("processor_account_id"), current.id())) {
            throw new AssetHandoverPort.Failure("SCRAP_PROCESSOR_REQUIRED", 403);
        }
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var row = guarded(id, version, input.assetVersion(), input.lockVersion(), "PROCESSING");
        if (!"FACTORY_REPLACEMENT".equals(row.get("source_type"))
                || number(row, "ownership_version") != input.ownershipVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_SOURCE_OR_OWNERSHIP_CHANGED", 409);
        }
        var source = json.readTree(row.get("source_snapshot_json").toString());
        var proof = source.path("repairEvidence");
        long repair = number(row, "source_business_id"), asset = number(row, "asset_id");
        var time = now();
        var borrowing = repairBorrowing(proof);
        repairs.closeFromScrap(actor, current, repair, proof.path("repairVersion").asLong(-1),
                asset, input.assetVersion(), id, borrowing);
        var factory = factoryEvidence(row, proof);
        var approval = json.readTree(row.get("decision_snapshot_json").toString()).path("decision");
        if (factory == null || !factory.method().equals(approval.path("approvedDisposition").asText().strip())) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_OUTSIDE_APPROVAL", 409);
        }
        if (input.occurredAt().isBefore(factory.dispatchedAt()) || input.occurredAt().isAfter(factory.observedAt())
                || !input.occurredAt().equals(input.occurredAt().truncatedTo(ChronoUnit.MILLIS))) {
            throw new AssetHandoverPort.Failure("SCRAP_FACTORY_DISPOSITION_TIME_INVALID", 400);
        }
        repairs.requireFactoryScrapEvidenceObjects(current, repair, factory);
        long assetVersion = assets.executeFactory(asset, input.assetVersion(), id, input.lockVersion(),
                source, current.id(), time);
        if (borrowing != null) {
            loans.terminateLost(asset, borrowing.borrowerEmployeeId(), borrowing.organizationId(),
                    borrowing.id(), borrowing.version(), time);
        }
        var facts = Map.of("occurredAt", input.occurredAt(), "method", factory.method(),
                "recipient", factory.provider(), "evidenceIds", json.readTree(factory.evidenceIdsJson()),
                "recordedBy", factory.recordedBy());
        String completion = json.writeValueAsString(Map.of("facts", facts, "verifiedBy", current.id()));
        String execution = json.writeValueAsString(Map.of("executedAt", time, "method", factory.method()));
        db.update("UPDATE fams_scrap_request SET request_status='EXECUTED',execution_snapshot_json=?,"
                + "asset_version=?,version=version+1,updated_at=? WHERE id=?", execution, assetVersion, time, id);
        db.update("INSERT INTO fams_scrap_disposition(request_id,processor_account_id,disposition_status,"
                + "custody_snapshot_json,completion_snapshot_json,created_at,updated_at)"
                + " VALUES (?,?,'DISPOSED',?,?,?,?)", id, current.id(), source.toString(), completion, time, time);
        releaseExecutedLock(id, asset, input.lockVersion(), time);
        closeTodo(id, "EXECUTE", time);
        audit(actor, current, Role.WAREHOUSE_OPERATOR, id, version, "SCRAP_FACTORY_EXECUTED",
                Map.of("method", factory.method(), "replacementId", Long.toString(factory.replacementId())), time);
        return remember(current, operation, key, hash, new Receipt(Long.toString(id), Long.toString(asset),
                version + 1, assetVersion, input.ownershipVersion(), "EXECUTED", Long.toString(current.id())), time);
    }

    public record LossExecution(
            @NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long lockVersion,
            @NotNull @PositiveOrZero Long ownershipVersion,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {}

    public Receipt executeLoss(Actor actor, long id, long version, UUID key, LossExecution input) {
        var current = current(actor, Role.WAREHOUSE_OPERATOR);
        String operation = "EXECUTE_LOSS:" + id;
        String hash = fingerprint(version, input);
        var requests = db.queryForList("SELECT * FROM fams_scrap_request WHERE id=? FOR UPDATE", id);
        if (requests.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_NOT_FOUND", 404);
        }
        var row = requests.getFirst();
        if (!Objects.equals(row.get("processor_account_id"), current.id())) {
            throw new AssetHandoverPort.Failure("SCRAP_PROCESSOR_REQUIRED", 403);
        }
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        if (!"PROCESSING".equals(row.get("request_status")) || number(row, "version") != version
                || number(row, "asset_version") != input.assetVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_REQUEST_CHANGED", 409);
        }
        if (!"LOSS".equals(row.get("source_type")) || number(row, "ownership_version") != input.ownershipVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_SOURCE_OR_OWNERSHIP_CHANGED", 409);
        }
        var approval = json.readTree(row.get("decision_snapshot_json").toString()).path("decision");
        if (!lossWriteOff(approval.path("approvedDisposition").asText())) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_OUTSIDE_APPROVAL", 409);
        }
        if (input.evidence().strip().length() < 10) {
            throw new AssetHandoverPort.Failure("SCRAP_LOSS_EVIDENCE_REQUIRED", 400);
        }
        long reportId = number(row, "source_business_id"), asset = number(row, "asset_id");
        var source = json.readTree(row.get("source_snapshot_json").toString());
        var proof = source.path("lossEvidence");
        var loss = losses.requireExecutionEvidence(reportId, proof.path("reportVersion").asLong(-1));
        if (loss.assetId() != asset || !loss.lifecycle().equals(source.path("lifecycle_status").asText())
                || !assets.matchesOriginalLossCustody(source, json.readTree(loss.sourceJson()))
                || !json.readTree(loss.confirmationJson()).equals(proof.path("confirmation"))) {
            throw new AssetHandoverPort.Failure("SCRAP_LOSS_SOURCE_CHANGED", 409);
        }
        var time = now();
        long assetVersion = assets.executeLoss(asset, input.assetVersion(), id, input.lockVersion(),
                source, current.id(), time);
        if ("BORROWED".equals(loss.lifecycle())) {
            loans.terminateLost(asset, source.path("responsible_employee_id").asLong(),
                    source.path("using_organization_id").asLong(), proof.path("loanId").asLong(),
                    proof.path("loanVersion").asLong(-1), time);
        } else {
            loans.requireRecovery(asset, loss.lifecycle(), null, null);
        }
        losses.closeFollowUp(reportId, time);
        var facts = Map.of("lossEvidence", proof, "evidence", input.evidence().strip(), "writtenOffAt", time);
        String execution = json.writeValueAsString(facts);
        db.update("UPDATE fams_scrap_request SET request_status='EXECUTED',execution_snapshot_json=?,"
                + "asset_version=?,version=version+1,updated_at=? WHERE id=?",
                execution, assetVersion, time, id);
        db.update("INSERT INTO fams_scrap_disposition(request_id,processor_account_id,disposition_status,"
                + "custody_snapshot_json,completion_snapshot_json,created_at,updated_at)"
                + " VALUES (?,?,'LOSS_WRITTEN_OFF',?,?,?,?)", id, current.id(), source.toString(), execution, time, time);
        releaseExecutedLock(id, asset, input.lockVersion(), time);
        closeTodo(id, "EXECUTE", time);
        audit(actor, current, Role.WAREHOUSE_OPERATOR, id, version, "SCRAP_LOSS_WRITTEN_OFF", facts, time);
        return remember(current, operation, key, hash, new Receipt(Long.toString(id), Long.toString(asset),
                version + 1, assetVersion, input.ownershipVersion(), "EXECUTED", Long.toString(current.id())), time);
    }

    private boolean lossWriteOff(String method) {
        String value = method.strip();
        return "LOSS_WRITTEN_OFF".equals(value) || "遗失核销".equals(value);
    }

    private void releaseExecutedLock(long id, long asset, long lockVersion, Instant time) {
        if (db.update("UPDATE fams_asset_business_lock SET released_at=?,release_reason='SCRAP_EXECUTED',"
                + "lock_version=lock_version+1 WHERE asset_id=? AND business_type='SCRAP' AND business_id=?"
                + " AND business_item_id=? AND lock_version=? AND released_at IS NULL",
                time, asset, id, id, lockVersion) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_OR_LOCK_CHANGED", 409);
        }
    }

    public record DispositionCompletion(
            @NotNull @PositiveOrZero Long requestVersion,
            @NotNull @PositiveOrZero Long ownershipVersion,
            @NotBlank @Size(max = 64) String observedCode,
            @Size(max = 128) String observedSerial,
            @NotNull Instant occurredAt,
            @NotBlank @Size(max = 500) String method,
            @NotBlank @Size(max = 500) String recipient,
            @NotNull @Positive Long operatorEmployeeId,
            @NotNull @PositiveOrZero Long operatorVersion,
            @NotNull @Size(min = 1, max = 14) List<@NotNull UUID> evidenceObjectIds) {
        public DispositionCompletion {
            evidenceObjectIds = evidenceObjectIds == null ? null : List.copyOf(evidenceObjectIds);
        }
    }

    public record DispositionReceipt(String id, long version, String status) {}

    public DispositionReceipt completeDisposition(Actor actor, long id, long version, UUID key,
            DispositionCompletion input) {
        var current = current(actor, Role.WAREHOUSE_OPERATOR);
        // Keep the request/disposition lock order shared with evidence registration and later reversal.
        var requests = db.queryForList("SELECT * FROM fams_scrap_request WHERE id=? FOR UPDATE", id);
        if (requests.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_NOT_FOUND", 404);
        }
        var request = requests.getFirst();
        var dispositions = db.queryForList("SELECT processor_account_id,disposition_status,version"
                + " FROM fams_scrap_disposition WHERE request_id=? FOR UPDATE", id);
        if (dispositions.isEmpty() || !Objects.equals(dispositions.getFirst().get("processor_account_id"), current.id())) {
            throw new AssetHandoverPort.Failure("SCRAP_PROCESSOR_REQUIRED", 403);
        }
        var disposition = dispositions.getFirst();
        String operation = "COMPLETE_DISPOSITION:" + id;
        String hash = fingerprint(version, input);
        var prior = db.queryForList("SELECT payload_sha256,response_json FROM fams_scrap_receipt"
                + " WHERE actor_account_id=? AND operation=? AND request_id=? FOR UPDATE", current.id(), operation, key.toString());
        if (!prior.isEmpty()) {
            if (!hash.equals(prior.getFirst().get("payload_sha256"))) {
                throw new AssetHandoverPort.Failure("SCRAP_IDEMPOTENCY_CONFLICT", 409);
            }
            return json.readValue(prior.getFirst().get("response_json").toString(), DispositionReceipt.class);
        }
        if (!"EXECUTED".equals(request.get("request_status")) || !physicalSource(request.get("source_type"))
                || number(request, "version") != input.requestVersion()
                || number(request, "ownership_version") != input.ownershipVersion()
                || !"PENDING_DISPOSITION".equals(disposition.get("disposition_status"))
                || number(disposition, "version") != version) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_CHANGED", 409);
        }
        var executed = json.readTree(request.get("execution_snapshot_json").toString());
        if (!input.method().strip().equals(executed.path("dispositionMethod").asText().strip())) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_OUTSIDE_APPROVAL", 409);
        }
        var time = now();
        if (input.occurredAt().isBefore(Instant.parse(executed.path("occurredAt").asText()))
                || input.occurredAt().isAfter(time)
                || !input.occurredAt().equals(input.occurredAt().truncatedTo(ChronoUnit.MILLIS))) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_DATE_INVALID", 400);
        }
        assets.requireDispositionAsset(number(request, "asset_id"), number(request, "asset_version"),
                input.observedCode(), input.observedSerial());
        var operators = db.queryForList("SELECT e.name FROM fams_employee e JOIN fams_organization o ON o.id=e.organization_id"
                + " WHERE e.id=? AND e.version=? AND e.status='ACTIVE' AND o.status='ACTIVE' FOR UPDATE",
                input.operatorEmployeeId(), input.operatorVersion());
        if (operators.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_OPERATOR_CHANGED", 409);
        }
        sensitive.requireRead(actor);
        var attachments = evidence.getIfAvailable();
        if (attachments == null) {
            throw new AssetHandoverPort.Failure("SCRAP_ATTACHMENT_STORAGE_UNAVAILABLE", 503);
        }
        if (Set.copyOf(input.evidenceObjectIds()).size() != input.evidenceObjectIds().size()
                || input.evidenceObjectIds().stream().anyMatch(object -> !attachments.clean("SCRAP_DISPOSITION", id, current.id(), object))) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_EVIDENCE_INVALID", 409);
        }
        String detail = json.writeValueAsString(Map.of("facts", input, "operatorName", operators.getFirst().get("name"),
                "recordedBy", Long.toString(current.id()), "recordedAt", time));
        db.update("UPDATE fams_scrap_disposition SET disposition_status='DISPOSED',completion_snapshot_json=?,"
                + "version=version+1,updated_at=? WHERE request_id=?", detail, time, id);
        if (db.update("UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE business_type='SCRAP'"
                + " AND business_id=? AND todo_type='DISPOSITION' AND assignee_account_id=? AND closed_at IS NULL",
                time, id, current.id()) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_TODO_CHANGED", 409);
        }
        db.update("INSERT INTO fams_audit_event(request_id,actor_account_id,actor_role,action,resource_type,"
                + "resource_id,before_version,after_version,occurred_at,detail_json)"
                + " VALUES (?,?,'WAREHOUSE_OPERATOR','SCRAP_DISPOSITION_COMPLETED','SCRAP_DISPOSITION',?,?,?,?,?)",
                actor.requestId().toString(), current.id(), id, version, version + 1, time, detail);
        var receipt = new DispositionReceipt(Long.toString(id), version + 1, "DISPOSED");
        db.update("INSERT INTO fams_scrap_receipt(actor_account_id,operation,request_id,scrap_request_id,"
                + "payload_sha256,response_json,created_at) VALUES (?,?,?,?,?,?,?)",
                current.id(), operation, key.toString(), id, hash, json.writeValueAsString(receipt), time);
        return receipt;
    }

    private Map<String, Object> guarded(long id, long version, long assetVersion, long lockVersion,
            String expectedStatus) {
        var rows = db.queryForList("SELECT * FROM fams_scrap_request WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_NOT_FOUND", 404);
        }
        var row = rows.getFirst();
        if (!expectedStatus.equals(row.get("request_status")) || number(row, "version") != version
                || number(row, "asset_version") != assetVersion) {
            throw new AssetHandoverPort.Failure("SCRAP_REQUEST_CHANGED", 409);
        }
        assets.requirePending(number(row, "asset_id"), assetVersion, id, lockVersion);
        return row;
    }

    private Current current(Actor actor, Role role) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(role)) {
            throw new AssetHandoverPort.Failure("SCRAP_ROLE_REQUIRED", 403);
        }
        return current;
    }

    private Receipt replay(Current current, String operation, UUID key, String hash) {
        var rows = db.queryForList("SELECT payload_sha256,response_json FROM fams_scrap_receipt"
                        + " WHERE actor_account_id=? AND operation=? AND request_id=? FOR UPDATE",
                current.id(), operation, key.toString());
        if (rows.isEmpty()) {
            return null;
        }
        var row = rows.getFirst();
        if (!hash.equals(row.get("payload_sha256"))) {
            throw new AssetHandoverPort.Failure("SCRAP_IDEMPOTENCY_CONFLICT", 409);
        }
        return json.readValue(row.get("response_json").toString(), Receipt.class);
    }

    private Receipt remember(Current current, String operation, UUID key, String hash, Receipt receipt,
            Instant time) {
        db.update("INSERT INTO fams_scrap_receipt(actor_account_id,operation,request_id,scrap_request_id,"
                        + "payload_sha256,response_json,created_at) VALUES (?,?,?,?,?,?,?)",
                current.id(), operation, key.toString(), Long.parseLong(receipt.id()), hash,
                json.writeValueAsString(receipt), time);
        return receipt;
    }

    private void todo(long id, String kind, String pool, Long owner, Instant time) {
        db.update("INSERT INTO fams_todo(pool_type,assignee_account_id,business_type,business_id,"
                        + "todo_type,todo_status,opened_at) VALUES (?,?,'SCRAP',?,?,'OPEN',?)",
                pool, owner, id, kind, time);
    }

    private void closeTodo(long id, String kind, Instant time) {
        db.update("UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE business_type='SCRAP'"
                        + " AND business_id=? AND todo_type=? AND closed_at IS NULL",
                time, id, kind);
    }

    private void audit(Actor actor, Current current, Role role, long id, long version, String action,
            Object input, Instant time) {
        String detail = json.writeValueAsString(input);
        db.update("INSERT INTO fams_scrap_event(request_id,action,before_version,after_version,"
                        + "actor_account_id,actor_username,actor_role,detail_json,occurred_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                id, action, version, version + 1, current.id(), current.username(), role.name(), detail, time);
        db.update("INSERT INTO fams_audit_event(request_id,actor_account_id,actor_role,action,"
                        + "resource_type,resource_id,before_version,after_version,occurred_at,detail_json)"
                        + " VALUES (?,?,?,?,'SCRAP',?,?,?,?,?)",
                actor.requestId().toString(), current.id(), role.name(), action, id, version, version + 1, time, detail);
    }

    private Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    private String fingerprint(long version, Object input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update((version + "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(json.writeValueAsBytes(input)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
