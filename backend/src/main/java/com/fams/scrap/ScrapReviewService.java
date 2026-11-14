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

    public record Cancellation(@NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long lockVersion,
            @NotBlank @Size(max = 1000) String reason) {}

    public record Receipt(String id, String assetId, long version, long assetVersion,
            long ownershipVersion, String status, String processorAccountId) {}

    static boolean mayCancel(Current current, Map<String, Object> request) {
        if (!"STANDALONE".equals(request.get("source_type"))) {
            return false;
        }
        Object status = request.get("request_status");
        if ("PENDING_ADMIN_DECISION".equals(status)) {
            return current.has(Role.ASSET_ADMIN) || Objects.equals(current.id(), request.get("creator_account_id"));
        }
        return current.has(Role.ASSET_ADMIN)
                && List.of("WAITING_WAREHOUSE_CLAIM", "PROCESSING").contains(status);
    }

    public Receipt cancel(Actor actor, long id, long version, UUID key, Cancellation input) {
        var current = actors.revalidateLocked(actor);
        var rows = db.queryForList("SELECT * FROM fams_scrap_request WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_NOT_FOUND", 404);
        }
        var request = rows.getFirst();
        boolean admin = current.has(Role.ASSET_ADMIN);
        if (!admin && !Objects.equals(current.id(), request.get("creator_account_id"))) {
            throw new AssetHandoverPort.Failure("SCRAP_CANCEL_FORBIDDEN", 403);
        }
        String operation = "CANCEL:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        if (!mayCancel(current, request) || number(request, "version") != version
                || number(request, "asset_version") != input.assetVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_CANCEL_NOT_ALLOWED", 409);
        }
        long asset = number(request, "asset_id");
        var time = now();
        assets.requirePending(asset, input.assetVersion(), id, input.lockVersion());
        var source = json.readTree(request.get("source_snapshot_json").toString());
        boolean restored = !loans.hasOpenBorrowing(asset)
                && assets.restoreStandaloneCancellation(asset, input.assetVersion(), id, source, current.id(), time);
        long assetVersion = restored ? input.assetVersion() + 1 : input.assetVersion();
        String status = restored ? "CANCELLED" : "RESTORE_REQUIRED";
        if (restored) {
            if (db.update("UPDATE fams_asset_business_lock SET released_at=?,release_reason='SCRAP_CANCELLED',"
                    + "lock_version=lock_version+1 WHERE asset_id=? AND business_type='SCRAP' AND business_id=?"
                    + " AND business_item_id=? AND lock_version=? AND released_at IS NULL",
                    time, asset, id, id, input.lockVersion()) != 1) {
                throw new AssetHandoverPort.Failure("SCRAP_ASSET_OR_LOCK_CHANGED", 409);
            }
        } else {
            todo(id, "RESTORE_REQUIRED", "ASSET_ADMIN", null, time);
        }
        db.update("UPDATE fams_scrap_request SET request_status=?,asset_version=?,version=version+1,updated_at=? WHERE id=?",
                status, assetVersion, time, id);
        for (String task : List.of("APPROVAL", "CLAIM", "EXECUTE")) {
            closeTodo(id, task, time);
        }
        Role role = admin ? Role.ASSET_ADMIN : current.roles().stream().sorted().findFirst()
                .orElseThrow(() -> new AssetHandoverPort.Failure("SCRAP_CANCEL_FORBIDDEN", 403));
        audit(actor, current, role, id, version, restored ? "SCRAP_CANCELLED" : "SCRAP_CANCEL_RESTORE_REQUIRED",
                Map.of("reason", input.reason().strip()), time);
        return remember(current, operation, key, hash, new Receipt(Long.toString(id), Long.toString(asset),
                version + 1, assetVersion, number(request, "ownership_version"), status,
                Objects.toString(request.get("processor_account_id"), null)), time);
    }

    public record CorrectionRequest(@NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long lockVersion, @NotNull @Positive Long locationId,
            @NotNull @PositiveOrZero Long locationVersion, @NotBlank @Size(max = 1000) String reason) {}

    public record CorrectionDecision(@NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long lockVersion, @NotBlank String result,
            @NotBlank @Size(max = 1000) String reason) {}

    public record CorrectionClaim(@NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long lockVersion) {}

    public record CorrectionExecution(@NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long lockVersion, @NotNull @PositiveOrZero Long ownershipVersion,
            @NotNull @PositiveOrZero Long locationVersion, @NotBlank @Size(max = 64) String observedCode,
            @Size(max = 128) String observedSerial, @NotNull Instant occurredAt,
            @NotNull Boolean physicalIdentityVerified, @NotBlank @Size(min = 10, max = 2000) String evidence) {}

    public Receipt requestCorrection(Actor actor, long id, long version, UUID key, CorrectionRequest input) {
        var current = current(actor, Role.ASSET_ADMIN);
        var rows = db.queryForList("SELECT * FROM fams_scrap_request WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_NOT_FOUND", 404);
        }
        var request = rows.getFirst();
        String operation = "CORRECTION:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        if (!"STANDALONE".equals(request.get("source_type")) || !"RESTORE_REQUIRED".equals(request.get("request_status"))
                || number(request, "version") != version || number(request, "asset_version") != input.assetVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_NOT_ALLOWED", 409);
        }
        if (!db.queryForList("SELECT id FROM fams_scrap_correction WHERE request_id=? AND active_guard=1"
                + " FOR UPDATE", id).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_OPEN", 409);
        }
        long asset = number(request, "asset_id");
        var time = now();
        assets.requirePending(asset, input.assetVersion(), id, input.lockVersion());
        if (!assets.matchesActiveBin(input.locationId(), input.locationVersion())) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_TARGET_CHANGED", 409);
        }
        db.update("INSERT INTO fams_scrap_correction(request_id,asset_id,creator_account_id,reason,asset_version,"
                + "lock_version,target_location_id,target_location_version,correction_status,request_snapshot_json,"
                + "created_at,updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?,'PENDING_ADMIN_DECISION',?,?,?)",
                id, asset, current.id(), input.reason().strip(), input.assetVersion(), input.lockVersion(),
                input.locationId(), input.locationVersion(), json.writeValueAsString(input), time, time);
        long correction = Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        closeTodo(id, "RESTORE_REQUIRED", time);
        todo(id, "CORRECTION_DECIDE", "ASSET_ADMIN", null, time);
        audit(actor, current, Role.ASSET_ADMIN, id, number(request, "version"), "SCRAP_CORRECTION_REQUESTED",
                Map.of("correctionId", Long.toString(correction), "input", input), time);
        return remember(current, operation, key, hash, new Receipt(Long.toString(id), Long.toString(asset),
                number(request, "version"), input.assetVersion(), number(request, "ownership_version"),
                "PENDING_ADMIN_DECISION", null), time);
    }

    private Map<String, Object> correctionGuarded(long id, long version, CorrectionClaim input) {
        var rows = db.queryForList("SELECT * FROM fams_scrap_correction WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_NOT_FOUND", 404);
        }
        var correction = rows.getFirst();
        if (number(correction, "version") != version || number(correction, "asset_version") != input.assetVersion()
                || number(correction, "lock_version") != input.lockVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_CHANGED", 409);
        }
        var requests = db.queryForList("SELECT request_status FROM fams_scrap_request WHERE id=? FOR UPDATE",
                number(correction, "request_id"));
        if (requests.isEmpty() || !"RESTORE_REQUIRED".equals(requests.getFirst().get("request_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_CHANGED", 409);
        }
        assets.requirePending(number(correction, "asset_id"), input.assetVersion(),
                number(correction, "request_id"), input.lockVersion());
        return correction;
    }

    public Receipt decideCorrection(Actor actor, long id, long version, UUID key, CorrectionDecision input) {
        var current = current(actor, Role.ASSET_ADMIN);
        boolean approve = "APPROVE".equals(input.result());
        if (!approve && !"REJECT".equals(input.result())) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_INPUT_INVALID", 400);
        }
        String operation = "CORRECTION_DECISION:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var correction = correctionGuarded(id, version, new CorrectionClaim(input.assetVersion(), input.lockVersion()));
        if (!"PENDING_ADMIN_DECISION".equals(correction.get("correction_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_CHANGED", 409);
        }
        long request = number(correction, "request_id"), asset = number(correction, "asset_id");
        var time = now();
        if (approve && !assets.matchesActiveBin(number(correction, "target_location_id"),
                number(correction, "target_location_version"))) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_TARGET_CHANGED", 409);
        }
        db.update("UPDATE fams_scrap_correction SET correction_status=?,approval_snapshot_json=?,"
                + "version=version+1,updated_at=? WHERE id=?",
                approve ? "WAITING_WAREHOUSE_CLAIM" : "REJECTED",
                json.writeValueAsString(Map.of("decision", input, "decidedBy", Long.toString(current.id()),
                        "decidedAt", time)), time, id);
        closeTodo(request, "CORRECTION_DECIDE", time);
        if (approve) {
            todo(request, "CORRECTION_CLAIM", "WAREHOUSE_OPERATOR", null, time);
        } else {
            todo(request, "RESTORE_REQUIRED", "ASSET_ADMIN", null, time);
        }
        audit(actor, current, Role.ASSET_ADMIN, request, version,
                approve ? "SCRAP_CORRECTION_APPROVED" : "SCRAP_CORRECTION_REJECTED",
                Map.of("correctionId", Long.toString(id), "input", input), time);
        return remember(current, operation, key, hash, new Receipt(Long.toString(request), Long.toString(asset),
                version + 1, input.assetVersion(), number(correction, "ownership_version"),
                approve ? "WAITING_WAREHOUSE_CLAIM" : "REJECTED", null), time);
    }

    public Receipt claimCorrection(Actor actor, long id, long version, UUID key, CorrectionClaim input) {
        var current = current(actor, Role.WAREHOUSE_OPERATOR);
        String operation = "CORRECTION_CLAIM:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var correction = correctionGuarded(id, version, input);
        if (!"WAITING_WAREHOUSE_CLAIM".equals(correction.get("correction_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_CHANGED", 409);
        }
        long request = number(correction, "request_id"), asset = number(correction, "asset_id");
        var time = now();
        long ownership = number(correction, "ownership_version") + 1;
        db.update("UPDATE fams_scrap_correction SET correction_status='PROCESSING',processor_account_id=?,"
                + "ownership_version=?,version=version+1,updated_at=? WHERE id=?", current.id(), ownership, time, id);
        closeTodo(request, "CORRECTION_CLAIM", time);
        todo(request, "CORRECTION_EXECUTE", "WAREHOUSE_OPERATOR", current.id(), time);
        audit(actor, current, Role.WAREHOUSE_OPERATOR, request, version, "SCRAP_CORRECTION_CLAIMED", input, time);
        return remember(current, operation, key, hash, new Receipt(Long.toString(request), Long.toString(asset),
                version + 1, input.assetVersion(), ownership, "PROCESSING", Long.toString(current.id())), time);
    }

    public Receipt executeCorrection(Actor actor, long id, long version, UUID key, CorrectionExecution input) {
        var current = current(actor, Role.WAREHOUSE_OPERATOR);
        var processor = db.queryForList("SELECT processor_account_id FROM fams_scrap_correction WHERE id=? FOR UPDATE", id);
        if (processor.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_NOT_FOUND", 404);
        }
        if (!Objects.equals(processor.getFirst().get("processor_account_id"), current.id())) {
            throw new AssetHandoverPort.Failure("SCRAP_PROCESSOR_REQUIRED", 403);
        }
        String operation = "CORRECTION_EXECUTE:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var correction = correctionGuarded(id, version, new CorrectionClaim(input.assetVersion(), input.lockVersion()));
        if (!"PROCESSING".equals(correction.get("correction_status"))
                || number(correction, "ownership_version") != input.ownershipVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_CHANGED", 409);
        }
        long request = number(correction, "request_id"), asset = number(correction, "asset_id");
        var time = now();
        Instant created = instant(correction.get("created_at"));
        if (!Boolean.TRUE.equals(input.physicalIdentityVerified()) || input.evidence().strip().length() < 10
                || input.occurredAt().isBefore(created) || input.occurredAt().isAfter(time.plusSeconds(2))
                || !input.occurredAt().equals(input.occurredAt().truncatedTo(ChronoUnit.MILLIS))) {
            throw new AssetHandoverPort.Failure("SCRAP_PHYSICAL_EVIDENCE_INVALID", 400);
        }
        if (number(correction, "target_location_version") != input.locationVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_TARGET_CHANGED", 409);
        }
        long assetVersion = assets.restoreCorrected(asset, input.assetVersion(), request,
                number(correction, "target_location_id"), input.locationVersion(),
                input.observedCode(), input.observedSerial(), current.id(), time);
        if (db.update("UPDATE fams_asset_business_lock SET released_at=?,release_reason='SCRAP_CORRECTED',"
                + "lock_version=lock_version+1 WHERE asset_id=? AND business_type='SCRAP' AND business_id=?"
                + " AND business_item_id=? AND lock_version=? AND released_at IS NULL",
                time, asset, request, request, input.lockVersion()) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_OR_LOCK_CHANGED", 409);
        }
        db.update("UPDATE fams_scrap_request SET request_status='CORRECTED',asset_version=?,"
                + "version=version+1,updated_at=? WHERE id=?", assetVersion, time, request);
        db.update("UPDATE fams_scrap_correction SET correction_status='COMPLETED',execution_snapshot_json=?,"
                + "asset_version=?,version=version+1,updated_at=? WHERE id=?",
                json.writeValueAsString(input), assetVersion, time, id);
        closeTodo(request, "CORRECTION_EXECUTE", time);
        audit(actor, current, Role.WAREHOUSE_OPERATOR, request, version, "SCRAP_CORRECTION_EXECUTED", input, time);
        return remember(current, operation, key, hash, new Receipt(Long.toString(request), Long.toString(asset),
                version + 1, assetVersion, input.ownershipVersion(), "COMPLETED", Long.toString(current.id())), time);
    }

    public record ReversalRequest(@NotNull @PositiveOrZero Long assetVersion, @NotNull @Positive Long locationId,
            @NotNull @PositiveOrZero Long locationVersion, @NotBlank @Size(max = 1000) String reason) {}

    public record ReversalDecision(@NotNull @PositiveOrZero Long assetVersion, @NotBlank String result,
            @NotBlank @Size(max = 1000) String reason) {}

    public record ReversalClaim(@NotNull @PositiveOrZero Long assetVersion) {}

    public record ReversalExecution(@NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long ownershipVersion, @NotNull @PositiveOrZero Long locationVersion,
            @NotBlank @Size(max = 64) String observedCode, @Size(max = 128) String observedSerial,
            @NotNull Instant occurredAt, @NotNull Boolean physicalIdentityVerified,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {}

    private static final List<Role> FOLLOW_UP_INITIATORS = List.of(Role.ASSET_ADMIN, Role.WAREHOUSE_OPERATOR,
            Role.DEPARTMENT_ASSET_MANAGER, Role.EMPLOYEE);

    public Receipt requestReversal(Actor actor, long id, long version, UUID key, ReversalRequest input) {
        var current = actors.revalidateLocked(actor);
        var initiator = FOLLOW_UP_INITIATORS.stream().filter(current::has).findFirst()
                .orElseThrow(() -> new AssetHandoverPort.Failure("SCRAP_REVERSAL_FORBIDDEN", 403));
        var rows = db.queryForList("SELECT * FROM fams_scrap_request WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_NOT_FOUND", 404);
        }
        var request = rows.getFirst();
        if (initiator == Role.EMPLOYEE || initiator == Role.DEPARTMENT_ASSET_MANAGER) {
            if (!Objects.equals(current.id(), request.get("creator_account_id"))) {
                throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_FORBIDDEN", 403);
            }
        }
        String operation = "REVERSAL:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        if (!physicalSource(request.get("source_type")) || !"EXECUTED".equals(request.get("request_status"))
                || number(request, "version") != version || number(request, "asset_version") != input.assetVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_NOT_ALLOWED", 409);
        }
        if (!db.queryForList("SELECT id FROM fams_scrap_reversal WHERE request_id=? AND active_guard=1"
                + " FOR UPDATE", id).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_OPEN", 409);
        }
        var disposition = db.queryForList("SELECT disposition_status FROM fams_scrap_disposition"
                + " WHERE request_id=? FOR UPDATE", id);
        if (disposition.isEmpty() || !"PENDING_DISPOSITION".equals(disposition.getFirst().get("disposition_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_NOT_ALLOWED", 409);
        }
        long asset = number(request, "asset_id");
        var time = now();
        assets.requireReversibleAsset(asset, input.assetVersion());
        if (!assets.matchesActiveBin(input.locationId(), input.locationVersion())) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_TARGET_CHANGED", 409);
        }
        db.update("INSERT INTO fams_scrap_reversal(request_id,asset_id,creator_account_id,reason,asset_version,"
                + "target_location_id,target_location_version,reversal_status,request_snapshot_json,"
                + "created_at,updated_at) VALUES (?, ?, ?, ?, ?, ?, ?,'PENDING_ADMIN_DECISION',?,?,?)",
                id, asset, current.id(), input.reason().strip(), input.assetVersion(),
                input.locationId(), input.locationVersion(), json.writeValueAsString(input), time, time);
        long reversal = Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        closeTodo(id, "DISPOSITION", time);
        todo(id, "REVERSAL_DECIDE", "ASSET_ADMIN", null, time);
        audit(actor, current, initiator, id, number(request, "version"), "SCRAP_REVERSAL_REQUESTED",
                Map.of("reversalId", Long.toString(reversal), "input", input), time);
        return remember(current, operation, key, hash, new Receipt(Long.toString(id), Long.toString(asset),
                number(request, "version"), input.assetVersion(), number(request, "ownership_version"),
                "PENDING_ADMIN_DECISION", null), time);
    }

    private Map<String, Object> reversalGuarded(long id, long version, long assetVersion) {
        var parents = db.queryForList("SELECT request_id FROM fams_scrap_reversal WHERE id=?", id);
        if (parents.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_NOT_FOUND", 404);
        }
        long request = number(parents.getFirst(), "request_id");
        // Lock the parent before the sub-record so disposition completion and reversal serialize identically.
        var scraps = db.queryForList("SELECT source_type FROM fams_scrap_request"
                + " WHERE id=? AND request_status='EXECUTED' FOR UPDATE", request);
        if (scraps.isEmpty() || !physicalSource(scraps.getFirst().get("source_type"))) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_CHANGED", 409);
        }
        var rows = db.queryForList("SELECT * FROM fams_scrap_reversal WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_NOT_FOUND", 404);
        }
        var reversal = rows.getFirst();
        if (number(reversal, "version") != version || number(reversal, "asset_version") != assetVersion) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_CHANGED", 409);
        }
        if (db.queryForList("SELECT request_id FROM fams_scrap_disposition WHERE request_id=?"
                + " AND disposition_status='PENDING_DISPOSITION' FOR UPDATE", request).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_CHANGED", 409);
        }
        assets.requireReversibleAsset(number(reversal, "asset_id"), assetVersion);
        return reversal;
    }

    public Receipt decideReversal(Actor actor, long id, long version, UUID key, ReversalDecision input) {
        var current = current(actor, Role.ASSET_ADMIN);
        boolean approve = "APPROVE".equals(input.result());
        if (!approve && !"REJECT".equals(input.result())) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_INPUT_INVALID", 400);
        }
        String operation = "REVERSAL_DECISION:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var reversal = reversalGuarded(id, version, input.assetVersion());
        if (!"PENDING_ADMIN_DECISION".equals(reversal.get("reversal_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_CHANGED", 409);
        }
        long request = number(reversal, "request_id"), asset = number(reversal, "asset_id");
        var time = now();
        if (approve && !assets.matchesActiveBin(number(reversal, "target_location_id"),
                number(reversal, "target_location_version"))) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_TARGET_CHANGED", 409);
        }
        db.update("UPDATE fams_scrap_reversal SET reversal_status=?,approval_snapshot_json=?,"
                + "version=version+1,updated_at=? WHERE id=?",
                approve ? "WAITING_WAREHOUSE_CLAIM" : "REJECTED",
                json.writeValueAsString(Map.of("decision", input, "decidedBy", Long.toString(current.id()),
                        "decidedAt", time)), time, id);
        closeTodo(request, "REVERSAL_DECIDE", time);
        if (approve) {
            todo(request, "REVERSAL_CLAIM", "WAREHOUSE_OPERATOR", null, time);
        } else {
            var processor = db.queryForObject("SELECT processor_account_id FROM fams_scrap_disposition"
                    + " WHERE request_id=?", Long.class, request);
            todo(request, "DISPOSITION", "WAREHOUSE_OPERATOR", processor, time);
        }
        audit(actor, current, Role.ASSET_ADMIN, request, version,
                approve ? "SCRAP_REVERSAL_APPROVED" : "SCRAP_REVERSAL_REJECTED",
                Map.of("reversalId", Long.toString(id), "input", input), time);
        return remember(current, operation, key, hash, new Receipt(Long.toString(request), Long.toString(asset),
                version + 1, input.assetVersion(), number(reversal, "ownership_version"),
                approve ? "WAITING_WAREHOUSE_CLAIM" : "REJECTED", null), time);
    }

    public Receipt claimReversal(Actor actor, long id, long version, UUID key, ReversalClaim input) {
        var current = current(actor, Role.WAREHOUSE_OPERATOR);
        String operation = "REVERSAL_CLAIM:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var reversal = reversalGuarded(id, version, input.assetVersion());
        if (!"WAITING_WAREHOUSE_CLAIM".equals(reversal.get("reversal_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_CHANGED", 409);
        }
        long request = number(reversal, "request_id"), asset = number(reversal, "asset_id");
        var time = now();
        long ownership = number(reversal, "ownership_version") + 1;
        db.update("UPDATE fams_scrap_reversal SET reversal_status='PROCESSING',processor_account_id=?,"
                + "ownership_version=?,version=version+1,updated_at=? WHERE id=?", current.id(), ownership, time, id);
        closeTodo(request, "REVERSAL_CLAIM", time);
        todo(request, "REVERSAL_EXECUTE", "WAREHOUSE_OPERATOR", current.id(), time);
        audit(actor, current, Role.WAREHOUSE_OPERATOR, request, version, "SCRAP_REVERSAL_CLAIMED", input, time);
        return remember(current, operation, key, hash, new Receipt(Long.toString(request), Long.toString(asset),
                version + 1, input.assetVersion(), ownership, "PROCESSING", Long.toString(current.id())), time);
    }

    public Receipt executeReversal(Actor actor, long id, long version, UUID key, ReversalExecution input) {
        var current = current(actor, Role.WAREHOUSE_OPERATOR);
        String operation = "REVERSAL_EXECUTE:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var reversal = reversalGuarded(id, version, input.assetVersion());
        if (!"PROCESSING".equals(reversal.get("reversal_status"))
                || number(reversal, "ownership_version") != input.ownershipVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_CHANGED", 409);
        }
        if (!Objects.equals(reversal.get("processor_account_id"), current.id())) {
            throw new AssetHandoverPort.Failure("SCRAP_PROCESSOR_REQUIRED", 403);
        }
        long request = number(reversal, "request_id"), asset = number(reversal, "asset_id");
        var time = now();
        Instant created = instant(reversal.get("created_at"));
        if (!Boolean.TRUE.equals(input.physicalIdentityVerified()) || input.evidence().strip().length() < 10
                || input.occurredAt().isBefore(created) || input.occurredAt().isAfter(time.plusSeconds(2))
                || !input.occurredAt().equals(input.occurredAt().truncatedTo(ChronoUnit.MILLIS))) {
            throw new AssetHandoverPort.Failure("SCRAP_PHYSICAL_EVIDENCE_INVALID", 400);
        }
        if (number(reversal, "target_location_version") != input.locationVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_TARGET_CHANGED", 409);
        }
        long assetVersion = assets.reverseToStock(asset, input.assetVersion(), request,
                number(reversal, "target_location_id"), input.locationVersion(),
                input.observedCode(), input.observedSerial(), current.id(), time);
        if (db.update("UPDATE fams_scrap_disposition SET disposition_status='REVERSED',updated_at=?,"
                + "version=version+1 WHERE request_id=? AND disposition_status='PENDING_DISPOSITION'",
                time, request) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_CHANGED", 409);
        }
        db.update("UPDATE fams_scrap_reversal SET reversal_status='COMPLETED',execution_snapshot_json=?,"
                + "asset_version=?,version=version+1,updated_at=? WHERE id=?",
                json.writeValueAsString(input), assetVersion, time, id);
        closeTodo(request, "REVERSAL_EXECUTE", time);
        closeTodo(request, "DISPOSITION", time);
        audit(actor, current, Role.WAREHOUSE_OPERATOR, request, version, "SCRAP_REVERSAL_EXECUTED", input, time);
        return remember(current, operation, key, hash, new Receipt(Long.toString(request), Long.toString(asset),
                version + 1, assetVersion, input.ownershipVersion(), "COMPLETED", Long.toString(current.id())), time);
    }

    public record RecoveryRequest(@NotNull @PositiveOrZero Long assetVersion, @NotNull Instant foundAt,
            @NotNull @Positive Long temporaryLocationId, @NotNull @PositiveOrZero Long temporaryLocationVersion,
            @NotNull @Positive Long temporaryCustodianEmployeeId, @NotNull @Positive Long locationId,
            @NotNull @PositiveOrZero Long locationVersion, @NotBlank @Size(max = 1000) String reason) {}

    public record RecoveryDecision(@NotNull @PositiveOrZero Long assetVersion, @NotBlank String result,
            @NotBlank @Size(max = 1000) String reason) {}

    public record RecoveryClaim(@NotNull @PositiveOrZero Long assetVersion) {}

    public record RecoveryExecution(@NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long ownershipVersion, @NotNull @PositiveOrZero Long locationVersion,
            @NotBlank @Size(max = 64) String observedCode, @Size(max = 128) String observedSerial,
            @NotNull Instant occurredAt, @NotNull Boolean physicalIdentityVerified,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {}

    public Receipt requestRecovery(Actor actor, long id, long version, UUID key, RecoveryRequest input) {
        var current = actors.revalidateLocked(actor);
        var initiator = FOLLOW_UP_INITIATORS.stream().filter(current::has).findFirst()
                .orElseThrow(() -> new AssetHandoverPort.Failure("SCRAP_RECOVERY_FORBIDDEN", 403));
        var rows = db.queryForList("SELECT * FROM fams_scrap_request WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_NOT_FOUND", 404);
        }
        var request = rows.getFirst();
        if ((initiator == Role.EMPLOYEE || initiator == Role.DEPARTMENT_ASSET_MANAGER)
                && !Objects.equals(current.id(), request.get("creator_account_id"))) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_FORBIDDEN", 403);
        }
        String operation = "RECOVERY:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        if (!"EXECUTED".equals(request.get("request_status")) || number(request, "version") != version
                || number(request, "asset_version") != input.assetVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_NOT_ALLOWED", 409);
        }
        if (!db.queryForList("SELECT id FROM fams_scrap_recovery WHERE request_id=? AND active_guard=1"
                + " FOR UPDATE", id).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_OPEN", 409);
        }
        var dispositions = db.queryForList("SELECT disposition_status,completion_snapshot_json"
                + " FROM fams_scrap_disposition WHERE request_id=? FOR UPDATE", id);
        if (dispositions.isEmpty()
                || !"LOSS_WRITTEN_OFF".equals(dispositions.getFirst().get("disposition_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_NOT_ALLOWED", 409);
        }
        long asset = number(request, "asset_id");
        var time = now();
        assets.requireRecoverableAsset(asset, input.assetVersion());
        Instant writtenOff = Instant.parse(json.readTree(dispositions.getFirst()
                .get("completion_snapshot_json").toString()).path("writtenOffAt").asText(null));
        // Both clients submit recovered instants with second precision, so floor the write-off bound.
        if (input.foundAt().isBefore(writtenOff.truncatedTo(ChronoUnit.SECONDS))
                || input.foundAt().isAfter(time.plusSeconds(2))
                || !input.foundAt().equals(input.foundAt().truncatedTo(ChronoUnit.MILLIS))) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_FACTS_INVALID", 400);
        }
        if (!assets.matchesTemporaryLocation(input.temporaryLocationId(), input.temporaryLocationVersion())) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_TEMPORARY_CHANGED", 409);
        }
        if (db.queryForList("SELECT e.id FROM fams_employee e JOIN fams_organization o ON o.id=e.organization_id"
                + " WHERE e.id=? AND e.status='ACTIVE' AND o.status='ACTIVE' FOR UPDATE",
                input.temporaryCustodianEmployeeId()).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_CUSTODIAN_CHANGED", 409);
        }
        if (!assets.matchesActiveBin(input.locationId(), input.locationVersion())) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_TARGET_CHANGED", 409);
        }
        db.update("INSERT INTO fams_scrap_recovery(request_id,asset_id,creator_account_id,reason,found_at,"
                + "temporary_location_id,temporary_location_version,temporary_custodian_employee_id,asset_version,"
                + "target_location_id,target_location_version,recovery_status,request_snapshot_json,"
                + "created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,'PENDING_ADMIN_DECISION',?,?,?)",
                id, asset, current.id(), input.reason().strip(), input.foundAt(), input.temporaryLocationId(),
                input.temporaryLocationVersion(), input.temporaryCustodianEmployeeId(), input.assetVersion(),
                input.locationId(), input.locationVersion(), json.writeValueAsString(input), time, time);
        long recovery = Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        todo(id, "RECOVERY_DECIDE", "ASSET_ADMIN", null, time);
        audit(actor, current, initiator, id, number(request, "version"), "SCRAP_RECOVERY_REQUESTED",
                Map.of("recoveryId", Long.toString(recovery), "input", input), time);
        return remember(current, operation, key, hash, new Receipt(Long.toString(id), Long.toString(asset),
                number(request, "version"), input.assetVersion(), number(request, "ownership_version"),
                "PENDING_ADMIN_DECISION", null), time);
    }

    private Map<String, Object> recoveryGuarded(long id, long version, long assetVersion) {
        var parents = db.queryForList("SELECT request_id FROM fams_scrap_recovery WHERE id=?", id);
        if (parents.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_NOT_FOUND", 404);
        }
        long request = number(parents.getFirst(), "request_id");
        // Lock the parent before the sub-record so write-off follow-ups and recovery serialize identically.
        if (db.queryForList("SELECT id FROM fams_scrap_request WHERE id=? AND request_status='EXECUTED'"
                + " FOR UPDATE", request).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_CHANGED", 409);
        }
        var rows = db.queryForList("SELECT * FROM fams_scrap_recovery WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_NOT_FOUND", 404);
        }
        var recovery = rows.getFirst();
        if (number(recovery, "version") != version || number(recovery, "asset_version") != assetVersion) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_CHANGED", 409);
        }
        if (db.queryForList("SELECT request_id FROM fams_scrap_disposition WHERE request_id=?"
                + " AND disposition_status='LOSS_WRITTEN_OFF' FOR UPDATE", request).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_CHANGED", 409);
        }
        assets.requireRecoverableAsset(number(recovery, "asset_id"), assetVersion);
        return recovery;
    }

    public Receipt decideRecovery(Actor actor, long id, long version, UUID key, RecoveryDecision input) {
        var current = current(actor, Role.ASSET_ADMIN);
        boolean approve = "APPROVE".equals(input.result());
        if (!approve && !"REJECT".equals(input.result())) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_INPUT_INVALID", 400);
        }
        String operation = "RECOVERY_DECISION:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var recovery = recoveryGuarded(id, version, input.assetVersion());
        if (!"PENDING_ADMIN_DECISION".equals(recovery.get("recovery_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_CHANGED", 409);
        }
        long request = number(recovery, "request_id"), asset = number(recovery, "asset_id");
        var time = now();
        if (approve && !assets.matchesActiveBin(number(recovery, "target_location_id"),
                number(recovery, "target_location_version"))) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_TARGET_CHANGED", 409);
        }
        db.update("UPDATE fams_scrap_recovery SET recovery_status=?,approval_snapshot_json=?,"
                + "version=version+1,updated_at=? WHERE id=?",
                approve ? "WAITING_WAREHOUSE_CLAIM" : "REJECTED",
                json.writeValueAsString(Map.of("decision", input, "decidedBy", Long.toString(current.id()),
                        "decidedAt", time)), time, id);
        closeTodo(request, "RECOVERY_DECIDE", time);
        if (approve) {
            todo(request, "RECOVERY_CLAIM", "WAREHOUSE_OPERATOR", null, time);
        }
        audit(actor, current, Role.ASSET_ADMIN, request, version,
                approve ? "SCRAP_RECOVERY_APPROVED" : "SCRAP_RECOVERY_REJECTED",
                Map.of("recoveryId", Long.toString(id), "input", input), time);
        return remember(current, operation, key, hash, new Receipt(Long.toString(request), Long.toString(asset),
                version + 1, input.assetVersion(), number(recovery, "ownership_version"),
                approve ? "WAITING_WAREHOUSE_CLAIM" : "REJECTED", null), time);
    }

    public Receipt claimRecovery(Actor actor, long id, long version, UUID key, RecoveryClaim input) {
        var current = current(actor, Role.WAREHOUSE_OPERATOR);
        String operation = "RECOVERY_CLAIM:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var recovery = recoveryGuarded(id, version, input.assetVersion());
        if (!"WAITING_WAREHOUSE_CLAIM".equals(recovery.get("recovery_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_CHANGED", 409);
        }
        long request = number(recovery, "request_id"), asset = number(recovery, "asset_id");
        var time = now();
        long ownership = number(recovery, "ownership_version") + 1;
        db.update("UPDATE fams_scrap_recovery SET recovery_status='PROCESSING',processor_account_id=?,"
                + "ownership_version=?,version=version+1,updated_at=? WHERE id=?", current.id(), ownership, time, id);
        closeTodo(request, "RECOVERY_CLAIM", time);
        todo(request, "RECOVERY_EXECUTE", "WAREHOUSE_OPERATOR", current.id(), time);
        audit(actor, current, Role.WAREHOUSE_OPERATOR, request, version, "SCRAP_RECOVERY_CLAIMED", input, time);
        return remember(current, operation, key, hash, new Receipt(Long.toString(request), Long.toString(asset),
                version + 1, input.assetVersion(), ownership, "PROCESSING", Long.toString(current.id())), time);
    }

    public Receipt executeRecovery(Actor actor, long id, long version, UUID key, RecoveryExecution input) {
        var current = current(actor, Role.WAREHOUSE_OPERATOR);
        String operation = "RECOVERY_EXECUTE:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var recovery = recoveryGuarded(id, version, input.assetVersion());
        if (!"PROCESSING".equals(recovery.get("recovery_status"))
                || number(recovery, "ownership_version") != input.ownershipVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_CHANGED", 409);
        }
        if (!Objects.equals(recovery.get("processor_account_id"), current.id())) {
            throw new AssetHandoverPort.Failure("SCRAP_PROCESSOR_REQUIRED", 403);
        }
        long request = number(recovery, "request_id"), asset = number(recovery, "asset_id");
        var time = now();
        Instant found = instant(recovery.get("found_at"));
        if (!Boolean.TRUE.equals(input.physicalIdentityVerified()) || input.evidence().strip().length() < 10
                || input.occurredAt().isBefore(found) || input.occurredAt().isAfter(time.plusSeconds(2))
                || !input.occurredAt().equals(input.occurredAt().truncatedTo(ChronoUnit.MILLIS))) {
            throw new AssetHandoverPort.Failure("SCRAP_PHYSICAL_EVIDENCE_INVALID", 400);
        }
        if (number(recovery, "target_location_version") != input.locationVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_TARGET_CHANGED", 409);
        }
        long assetVersion = assets.reregisterToStock(asset, input.assetVersion(), request,
                number(recovery, "temporary_location_id"), number(recovery, "target_location_id"),
                input.locationVersion(), input.observedCode(), input.observedSerial(), current.id(), time);
        db.update("UPDATE fams_scrap_recovery SET recovery_status='COMPLETED',execution_snapshot_json=?,"
                + "asset_version=?,version=version+1,updated_at=? WHERE id=?",
                json.writeValueAsString(input), assetVersion, time, id);
        closeTodo(request, "RECOVERY_EXECUTE", time);
        audit(actor, current, Role.WAREHOUSE_OPERATOR, request, version, "SCRAP_RECOVERY_EXECUTED", input, time);
        return remember(current, operation, key, hash, new Receipt(Long.toString(request), Long.toString(asset),
                version + 1, assetVersion, input.ownershipVersion(), "COMPLETED", Long.toString(current.id())), time);
    }

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
        var source = json.readTree(request.get("source_snapshot_json").toString());
        var factoryLoss = factoryLossRepair(source.path("lossEvidence"));
        boolean factory = "FACTORY_REPLACEMENT".equals(request.get("source_type"));
        boolean repairSource = factory || "REPAIR".equals(request.get("source_type"));
        boolean inheritedProcessor = repairSource || factoryLoss != null;
        if (approve && repairSource) {
            var proof = source.path("repairEvidence");
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
        if (approve && factoryLoss != null && (!Objects.equals(request.get("processor_account_id"), factoryLoss.processorAccountId())
                || !validFactoryLossRepair(asset, factoryLoss))) {
            throw new AssetHandoverPort.Failure("SCRAP_SOURCE_OR_OWNERSHIP_CHANGED", 409);
        }
        String status = inheritedProcessor ? "PROCESSING" : "WAITING_WAREHOUSE_CLAIM";
        long assetVersion = input.assetVersion();
        if (!approve) {
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
        if (approve && inheritedProcessor) {
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
                        inheritedProcessor && request.get("processor_account_id") != null
                                ? request.get("processor_account_id").toString() : null), time);
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
        if ("FACTORY_REPAIR".equals(source.path("lifecycle_status").asText())) {
            var factory = factoryLossRepair(source.path("lossEvidence"));
            if (factory == null || !validFactoryLossRepair(asset, factory)) {
                return false;
            }
        } else if ("BORROWED".equals(source.path("lifecycle_status").asText())) {
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

    private RepairService.FactoryLossScrap factoryLossRepair(tools.jackson.databind.JsonNode proof) {
        var factory = proof.path("factoryRepair");
        return factory.isMissingNode() ? null : json.readValue(factory.toString(), RepairService.FactoryLossScrap.class);
    }

    private boolean validFactoryLossRepair(long asset, RepairService.FactoryLossScrap source) {
        return source.equals(repairs.factoryLossScrap(source.repairId(), source.roundId(), asset, source.processorAccountId()));
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
        } else if (loss.factory() != null) {
            var factory = factoryLossRepair(proof);
            if (factory == null || factory.repairId() != loss.factory().repairId()
                    || factory.roundId() != loss.factory().roundId()
                    || factory.processorAccountId() != loss.factory().processorAccountId()) {
                throw new AssetHandoverPort.Failure("SCRAP_LOSS_SOURCE_CHANGED", 409);
            }
            repairs.closeFromFactoryLossScrap(actor, current, factory, input.assetVersion(), id);
            var borrowing = factory.borrowing();
            if (borrowing != null) {
                loans.terminateLost(asset, borrowing.borrowerEmployeeId(), borrowing.organizationId(),
                        borrowing.id(), borrowing.version(), time);
            }
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
        if (!db.queryForList("SELECT id FROM fams_scrap_reversal WHERE request_id=? AND active_guard=1 FOR UPDATE",
                id).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_REVERSAL_OPEN", 409);
        }
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
        if (db.update("UPDATE fams_scrap_disposition SET disposition_status='DISPOSED',"
                + "completion_snapshot_json=?,version=version+1,updated_at=? WHERE request_id=?"
                + " AND disposition_status='PENDING_DISPOSITION'", detail, time, id) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_CHANGED", 409);
        }
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
