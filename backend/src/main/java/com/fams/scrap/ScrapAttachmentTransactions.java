package com.fams.scrap;

import com.fams.asset.AssetHandoverPort;
import com.fams.attachment.AttachmentIntake;
import com.fams.attachment.AttachmentRegistry;
import com.fams.attachment.MalwareScanner;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;
import com.fams.auth.ScrapSensitiveAccess;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Profile("fams-product")
@Transactional
public class ScrapAttachmentTransactions {
    private final JdbcTemplate db;
    private final RequisitionActorContext actors;
    private final ScrapReadService reads;
    private final ScrapSensitiveAccess sensitive;
    private final AttachmentRegistry registry;
    private final ObjectMapper json;
    private final Clock clock;

    public ScrapAttachmentTransactions(JdbcTemplate db, RequisitionActorContext actors,
            ScrapReadService reads, ScrapSensitiveAccess sensitive, AttachmentRegistry registry,
            ObjectMapper json, Clock clock) {
        this.db = db;
        this.actors = actors;
        this.reads = reads;
        this.sensitive = sensitive;
        this.registry = registry;
        this.json = json;
        this.clock = clock;
    }

    public static String type(String kind) {
        return switch (kind) {
            case "request" -> "SCRAP";
            case "disposition" -> "SCRAP_DISPOSITION";
            default -> throw new AssetHandoverPort.Failure("SCRAP_ATTACHMENT_KIND_INVALID", 400);
        };
    }

    public void uploader(Actor actor, long id, String kind) {
        uploader(actors.revalidateLocked(actor), header(id), kind);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reader(Actor actor, long id) {
        reads.get(actor, id);
        sensitive.requireRead(actor);
    }

    public Map<String, Object> capabilities(Actor actor, long id) {
        reads.get(actor, id);
        var current = actors.revalidateLocked(actor);
        var row = header(id);
        var disposition = db.queryForList("SELECT processor_account_id,version,disposition_status"
                + " FROM fams_scrap_disposition WHERE request_id=? FOR UPDATE", id);
        boolean uploadDisposition = current.has(Role.WAREHOUSE_OPERATOR) && !disposition.isEmpty()
                && Objects.equals(disposition.getFirst().get("processor_account_id"), current.id())
                && "PENDING_DISPOSITION".equals(disposition.getFirst().get("disposition_status"));
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("canRead", sensitive.mayRead(actor));
        result.put("canUploadRequest", Objects.equals(row.get("creator_account_id"), current.id())
                && "PENDING_ADMIN_DECISION".equals(row.get("request_status")));
        result.put("canUploadDisposition", uploadDisposition);
        result.put("requestVersion", row.get("version"));
        result.put("dispositionVersion", disposition.isEmpty() ? null : disposition.getFirst().get("version"));
        return result;
    }

    private void uploader(Current actor, Map<String, Object> row, String kind) {
        type(kind);
        if ("request".equals(kind)) {
            if (!Objects.equals(row.get("creator_account_id"), actor.id())) {
                throw new AssetHandoverPort.Failure("SCRAP_ATTACHMENT_CREATOR_REQUIRED", 403);
            }
        } else {
            var disposition = db.queryForList("SELECT processor_account_id FROM fams_scrap_disposition"
                    + " WHERE request_id=? FOR UPDATE", row.get("id"));
            if (!actor.has(Role.WAREHOUSE_OPERATOR) || disposition.isEmpty()
                    || !Objects.equals(disposition.getFirst().get("processor_account_id"), actor.id())) {
                throw new AssetHandoverPort.Failure("SCRAP_PROCESSOR_REQUIRED", 403);
            }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> register(Actor actor, long id, String kind, long version, UUID key,
            String filename, AttachmentIntake.QuarantinedAttachment attachment) {
        var current = actors.revalidateLocked(actor);
        var row = header(id);
        uploader(current, row, kind);
        String operation = "ATTACHMENT:" + kind + ":" + id;
        String hash = fingerprint(kind, version, filename, attachment);
        var prior = db.queryForList("SELECT payload_sha256,response_json FROM fams_scrap_receipt"
                + " WHERE actor_account_id=? AND operation=? AND request_id=? FOR UPDATE",
                current.id(), operation, key.toString());
        if (!prior.isEmpty()) {
            if (!hash.equals(prior.getFirst().get("payload_sha256"))) {
                throw new AssetHandoverPort.Failure("SCRAP_IDEMPOTENCY_CONFLICT", 409);
            }
            return json.readValue(prior.getFirst().get("response_json").toString(),
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        }
        if (attachment.scanResult() == MalwareScanner.Result.UNAVAILABLE) {
            throw new AssetHandoverPort.Failure("SCRAP_ATTACHMENT_SCAN_UNAVAILABLE", 503);
        }
        if (attachment.scanResult() != MalwareScanner.Result.CLEAN) {
            throw new AssetHandoverPort.Failure("SCRAP_ATTACHMENT_REJECTED", 422);
        }
        var time = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if ("request".equals(kind)) {
            if (!"PENDING_ADMIN_DECISION".equals(row.get("request_status"))
                    || ((Number) row.get("version")).longValue() != version
                    || db.queryForList("SELECT id FROM fams_asset_business_lock WHERE business_type='SCRAP'"
                            + " AND asset_id=? AND business_id=? AND business_item_id=? AND released_at IS NULL FOR UPDATE",
                            row.get("asset_id"), id, id).isEmpty()) {
                throw new AssetHandoverPort.Failure("SCRAP_REQUEST_CHANGED", 409);
            }
            db.update("UPDATE fams_scrap_request SET version=version+1,updated_at=? WHERE id=?", time, id);
        } else {
            var disposition = db.queryForList("SELECT version FROM fams_scrap_disposition"
                    + " WHERE request_id=? AND disposition_status='PENDING_DISPOSITION' FOR UPDATE", id);
            if (!"EXECUTED".equals(row.get("request_status")) || disposition.isEmpty()
                    || ((Number) disposition.getFirst().get("version")).longValue() != version) {
                throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_CHANGED", 409);
            }
            db.update("UPDATE fams_scrap_disposition SET version=version+1,updated_at=? WHERE request_id=?", time, id);
        }
        registry.register(type(kind), id, version, current.id(), filename, attachment);
        var result = Map.<String, Object>of("id", Long.toString(id), "kind", kind,
                "objectId", attachment.file().id().toString(), "version", version + 1);
        String action = "request".equals(kind) ? "SCRAP_ATTACHMENT_ADDED" : "SCRAP_DISPOSITION_ATTACHMENT_ADDED";
        String detail = json.writeValueAsString(Map.of("objectId", attachment.file().id().toString(),
                "sha256", attachment.file().sha256(), "kind", kind));
        if ("request".equals(kind)) {
            db.update("INSERT INTO fams_scrap_event(request_id,action,before_version,after_version,"
                    + "actor_account_id,actor_username,actor_role,detail_json,occurred_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    id, action, version, version + 1, current.id(), current.username(), role(current), detail, time);
        }
        db.update("INSERT INTO fams_audit_event(request_id,actor_account_id,actor_role,action,resource_type,"
                + "resource_id,before_version,after_version,occurred_at,detail_json) VALUES (?,?,?,?,?,?,?,?,?,?)",
                actor.requestId().toString(), current.id(), role(current), action, type(kind), id, version, version + 1, time, detail);
        db.update("INSERT INTO fams_scrap_receipt(actor_account_id,operation,request_id,scrap_request_id,"
                + "payload_sha256,response_json,created_at) VALUES (?,?,?,?,?,?,?)",
                current.id(), operation, key.toString(), id, hash, json.writeValueAsString(result), time);
        return result;
    }

    private String role(Current actor) {
        return actor.roles().stream().map(Enum::name).sorted().findFirst().orElseThrow();
    }

    private Map<String, Object> header(long id) {
        var rows = db.queryForList("SELECT * FROM fams_scrap_request WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_NOT_FOUND", 404);
        }
        return rows.getFirst();
    }

    private record Payload(String kind, long version, String filename, String sha256, long size, String mime) {}

    private String fingerprint(String kind, long version, String filename,
            AttachmentIntake.QuarantinedAttachment attachment) {
        try {
            String value = json.writeValueAsString(new Payload(kind, version, filename,
                    attachment.file().sha256(), attachment.file().size(), attachment.format().mime()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
