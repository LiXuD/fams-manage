package com.fams.scrap;

import com.fams.asset.AssetHandoverPort;
import com.fams.asset.AssetScrapPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

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
import java.util.Map;
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

    public ScrapReviewService(JdbcTemplate db, ObjectMapper json, Clock clock,
            RequisitionActorContext actors, AssetScrapPort assets, com.fams.loan.LoanLossPort loans) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actors = actors;
        this.assets = assets;
        this.loans = loans;
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
        long asset = number(request, "asset_id");
        var time = now();
        String status = "WAITING_WAREHOUSE_CLAIM";
        long assetVersion = input.assetVersion();
        if (!approve) {
            String previous = json.readTree(request.get("source_snapshot_json").toString())
                    .path("lifecycle_status").asText();
            boolean restored = assets.restoreStandalone(asset, assetVersion, id, previous, current.id(), time);
            status = restored ? "REJECTED" : "RESTORE_REQUIRED";
            if (restored) {
                assetVersion++;
                db.update("UPDATE fams_asset_business_lock SET released_at=?,release_reason='SCRAP_REJECTED',"
                        + "lock_version=lock_version+1 WHERE asset_id=? AND business_type='SCRAP'"
                        + " AND business_id=? AND business_item_id=? AND released_at IS NULL",
                        time, asset, id, id);
            }
        }
        db.update("UPDATE fams_scrap_request SET request_status=?,decision_snapshot_json=?,asset_version=?,"
                        + "version=version+1,updated_at=? WHERE id=?",
                status, json.writeValueAsString(Map.of("decision", input, "decidedBy", Long.toString(current.id()),
                        "decidedAt", time)), assetVersion, time, id);
        closeTodo(id, "APPROVAL", time);
        if (approve) {
            todo(id, "CLAIM", "WAREHOUSE_OPERATOR", null, time);
        } else if ("RESTORE_REQUIRED".equals(status)) {
            todo(id, "RESTORE_REQUIRED", "ASSET_ADMIN", null, time);
        }
        String action = approve ? "SCRAP_APPROVED" : "SCRAP_REJECTED";
        audit(actor, current, Role.ASSET_ADMIN, id, version, action, input, time);
        return remember(current, operation, key, hash,
                new Receipt(Long.toString(id), Long.toString(asset), version + 1, assetVersion, 0, status, null), time);
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
        if (!"STANDALONE".equals(row.get("source_type"))
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
        loans.requireRecovery(asset, "IN_USE", null, null);
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
        if (db.update("UPDATE fams_asset_business_lock SET released_at=?,release_reason='SCRAP_EXECUTED',"
                + "lock_version=lock_version+1 WHERE asset_id=? AND business_type='SCRAP' AND business_id=?"
                + " AND business_item_id=? AND lock_version=? AND released_at IS NULL",
                time, asset, id, id, input.lockVersion()) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_OR_LOCK_CHANGED", 409);
        }
        closeTodo(id, "EXECUTE", time);
        todo(id, "DISPOSITION", "WAREHOUSE_OPERATOR", current.id(), time);
        audit(actor, current, Role.WAREHOUSE_OPERATOR, id, version, "SCRAP_EXECUTED", input, time);
        return remember(current, operation, key, hash, new Receipt(Long.toString(id), Long.toString(asset),
                version + 1, assetVersion, input.ownershipVersion(), "EXECUTED", Long.toString(current.id())), time);
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
