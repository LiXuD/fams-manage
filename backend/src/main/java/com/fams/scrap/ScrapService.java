package com.fams.scrap;

import com.fams.asset.AssetHandoverPort;
import com.fams.asset.AssetScrapPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.loan.LoanLossPort;
import com.fams.loss.LossScrapSourcePort;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Owns scrap documents and their locks. The asset port owns lifecycle transitions. */
@Service
@Profile("fams-product")
@Transactional
public class ScrapService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final RequisitionActorContext actors;
    private final AssetScrapPort assets;
    private final LossScrapSourcePort losses;
    private final LoanLossPort loans;

    public ScrapService(JdbcTemplate db, ObjectMapper json, Clock clock,
            RequisitionActorContext actors, AssetScrapPort assets, LossScrapSourcePort losses, LoanLossPort loans) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actors = actors;
        this.assets = assets;
        this.losses = losses;
        this.loans = loans;
    }

    public record Submission(
            @Positive long assetId,
            @NotNull @PositiveOrZero Long assetVersion,
            @NotBlank @Size(max = 2000) String reason,
            @NotBlank @Size(max = 2000) String observedCondition,
            @NotBlank @Size(max = 500) String proposedDisposition) {}

    public record Receipt(String id, String assetId, long assetVersion, long version, String status) {}

    public record LossSubmission(@NotNull @PositiveOrZero Long assetVersion,
            @NotBlank @Size(max = 2000) String reason,
            @NotBlank @Size(max = 2000) String observedCondition,
            @NotBlank @Size(max = 500) String proposedDisposition) {}

    private record LossIntent(long reportVersion, LossSubmission input) {}

    public Receipt submit(Actor actor, UUID key, Submission input) {
        var current = actors.revalidateLocked(actor);
        String hash = fingerprint(input);
        var prior = replay(current, "SUBMIT", key, hash);
        if (prior != null) {
            return prior;
        }
        var source = assets.standaloneSource(current, input.assetId(), input.assetVersion());
        return create(actor, current, "SUBMIT", key, hash, input, "STANDALONE", null, source, input);
    }

    public Receipt submitFromLoss(Actor actor, long reportId, long version, UUID key, LossSubmission input) {
        var current = actors.revalidateLocked(actor);
        String operation = "SUBMIT_LOSS:" + reportId;
        String hash = fingerprint(new LossIntent(version, input));
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var loss = losses.requireBorrowedLoss(current, reportId, version);
        var source = new LinkedHashMap<>(assets.borrowedLossSource(loss.assetId(), input.assetVersion(),
                loss.borrowerId(), loss.organizationId()));
        var borrowing = loans.requireOpenBorrowing(loss.assetId(), loss.borrowerId(), loss.organizationId());
        var proof = Map.of("reportId", Long.toString(reportId), "reportVersion", version,
                "confirmedAt", loss.confirmedAt(), "confirmation", json.readTree(loss.confirmationJson()),
                "loanId", Long.toString(borrowing.id()), "loanVersion", borrowing.version());
        source.put("lossEvidence", proof);
        var submission = new Submission(loss.assetId(), input.assetVersion(), input.reason(),
                input.observedCondition(), input.proposedDisposition());
        return create(actor, current, operation, key, hash, submission, "LOSS", reportId, source, input);
    }

    private Receipt replay(Current current, String operation, UUID key, String hash) {
        var prior = db.queryForList("SELECT payload_sha256,response_json FROM fams_scrap_receipt WHERE actor_account_id=?"
                + " AND operation=? AND request_id=? FOR UPDATE", current.id(), operation, key.toString());
        if (prior.isEmpty()) {
            return null;
        }
        if (!hash.equals(prior.getFirst().get("payload_sha256"))) {
            throw new AssetHandoverPort.Failure("SCRAP_IDEMPOTENCY_CONFLICT", 409);
        }
        return json.readValue(prior.getFirst().get("response_json").toString(), Receipt.class);
    }

    private Receipt create(Actor actor, Current current, String operation, UUID key, String hash,
            Submission input, String sourceType, Long sourceId, Map<String, Object> source, Object facts) {
        var time = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        db.update(
                "INSERT INTO fams_scrap_request(asset_id,creator_account_id,source_type,source_business_id,"
                        + "source_snapshot_json,reason,observed_condition,proposed_disposition,"
                        + "asset_version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                input.assetId(), current.id(), sourceType, sourceId, json.writeValueAsString(source), input.reason().strip(),
                input.observedCondition().strip(), input.proposedDisposition().strip(), input.assetVersion() + 1,
                time, time);
        long id = Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        db.update(
                "INSERT INTO fams_asset_business_lock(asset_id,business_type,business_id,business_item_id,"
                        + "acquired_at) VALUES (?,'SCRAP',?,?,?)",
                input.assetId(), id, id, time);
        long assetVersion = "LOSS".equals(sourceType)
                ? assets.freezeBorrowedLoss(input.assetId(), input.assetVersion(), id, current.id(), time)
                : assets.freezeStandalone(input.assetId(), input.assetVersion(), id, current.id(), time);
        db.update(
                "INSERT INTO fams_todo(pool_type,business_type,business_id,todo_type,todo_status,opened_at)"
                        + " VALUES ('ASSET_ADMIN','SCRAP',?,'APPROVAL','OPEN',?)",
                id, time);
        String role = current.roles().stream().map(Enum::name).sorted().findFirst().orElseThrow();
        String detail = sourceId == null ? json.writeValueAsString(facts)
                : json.writeValueAsString(Map.of("sourceType", sourceType, "facts", facts, "sourceId", sourceId.toString()));
        db.update(
                "INSERT INTO fams_scrap_event(request_id,action,before_version,after_version,"
                        + "actor_account_id,actor_username,actor_role,detail_json,occurred_at)"
                        + " VALUES (?,'SCRAP_SUBMITTED',0,0,?,?,?,?,?)",
                id, current.id(), current.username(), role, detail, time);
        db.update(
                "INSERT INTO fams_audit_event(request_id,actor_account_id,actor_role,action,"
                        + "resource_type,resource_id,before_version,after_version,occurred_at,detail_json)"
                        + " VALUES (?,?,?,'SCRAP_SUBMITTED','SCRAP',?,0,0,?,?)",
                actor.requestId().toString(), current.id(), role, id, time, detail);
        var result = new Receipt(Long.toString(id), Long.toString(input.assetId()), assetVersion,
                0, "PENDING_ADMIN_DECISION");
        db.update(
                "INSERT INTO fams_scrap_receipt(actor_account_id,operation,request_id,scrap_request_id,"
                        + "payload_sha256,response_json,created_at) VALUES (?,?,?,?,?,?,?)",
                current.id(), operation, key.toString(), id, hash, json.writeValueAsString(result), time);
        return result;
    }

    private String fingerprint(Object input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(input)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
