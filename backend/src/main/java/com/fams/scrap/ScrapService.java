package com.fams.scrap;

import com.fams.asset.AssetHandoverPort;
import com.fams.asset.AssetScrapPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;

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

    public ScrapService(JdbcTemplate db, ObjectMapper json, Clock clock,
            RequisitionActorContext actors, AssetScrapPort assets) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actors = actors;
        this.assets = assets;
    }

    public record Submission(
            @Positive long assetId,
            @NotNull @PositiveOrZero Long assetVersion,
            @NotBlank @Size(max = 2000) String reason,
            @NotBlank @Size(max = 2000) String observedCondition,
            @NotBlank @Size(max = 500) String proposedDisposition) {}

    public record Receipt(String id, String assetId, long assetVersion, long version, String status) {}

    public Receipt submit(Actor actor, UUID key, Submission input) {
        var current = actors.revalidateLocked(actor);
        String hash = fingerprint(input);
        var prior = db.queryForList(
                "SELECT payload_sha256,response_json FROM fams_scrap_receipt WHERE actor_account_id=?"
                        + " AND operation='SUBMIT' AND request_id=? FOR UPDATE",
                current.id(), key.toString());
        if (!prior.isEmpty()) {
            var receipt = prior.getFirst();
            if (!hash.equals(receipt.get("payload_sha256"))) {
                throw new AssetHandoverPort.Failure("SCRAP_IDEMPOTENCY_CONFLICT", 409);
            }
            return json.readValue(receipt.get("response_json").toString(), Receipt.class);
        }

        var source = assets.standaloneSource(current, input.assetId(), input.assetVersion());
        var time = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        db.update(
                "INSERT INTO fams_scrap_request(asset_id,creator_account_id,source_type,"
                        + "source_snapshot_json,reason,observed_condition,proposed_disposition,"
                        + "asset_version,created_at,updated_at) VALUES (?,?,'STANDALONE',?,?,?,?,?,?,?)",
                input.assetId(), current.id(), json.writeValueAsString(source), input.reason().strip(),
                input.observedCondition().strip(), input.proposedDisposition().strip(), input.assetVersion() + 1,
                time, time);
        long id = Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        db.update(
                "INSERT INTO fams_asset_business_lock(asset_id,business_type,business_id,business_item_id,"
                        + "acquired_at) VALUES (?,'SCRAP',?,?,?)",
                input.assetId(), id, id, time);
        long assetVersion = assets.freezeStandalone(input.assetId(), input.assetVersion(), id, current.id(), time);
        db.update(
                "INSERT INTO fams_todo(pool_type,business_type,business_id,todo_type,todo_status,opened_at)"
                        + " VALUES ('ASSET_ADMIN','SCRAP',?,'APPROVAL','OPEN',?)",
                id, time);
        String role = current.roles().stream().map(Enum::name).sorted().findFirst().orElseThrow();
        String detail = json.writeValueAsString(input);
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
                        + "payload_sha256,response_json,created_at) VALUES (?,'SUBMIT',?,?,?,?,?)",
                current.id(), key.toString(), id, hash, json.writeValueAsString(result), time);
        return result;
    }

    private String fingerprint(Submission input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(input)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
