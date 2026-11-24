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

/**
 * Transaction-side half of the scrap evidence surface. It exists as a separate bean because
 * {@link ScrapAttachmentService#upload} must run outside a transaction — the attachment writer refuses
 * an enclosing one so a rollback cannot orphan stored bytes — while the registration it performs must
 * still be atomic. The class-level {@code @Transactional} gives the simple entry points a transaction
 * of their own, and the two callbacks invoked from inside the writer's transaction declare
 * {@code Propagation.MANDATORY} so a missing transaction fails loudly instead of silently creating one
 * that the writer would not roll back together with the file.
 *
 * <p>Every read of the scrap header uses {@code SELECT ... FOR UPDATE}, which serialises concurrent
 * uploads against each other and against the review flow that also bumps {@code version}. Rows are
 * never read without that lock, because the version comparison and the business-lock existence check
 * in {@link #register} would otherwise race.
 *
 * <p>The kind vocabulary is fixed: {@code request} maps to the {@code SCRAP} attachment business type
 * and {@code disposition} to {@code SCRAP_DISPOSITION}. Any other value is a 400, and the mapping is
 * applied before any authorisation so an invalid kind cannot be probed for a permission difference.
 */
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

    /**
     * Wires the database access, the actor revalidation source, the scrap read service used for
     * visibility, the sensitive-field gate, the shared attachment registry and the serialisation and
     * time sources needed to build receipts.
     *
     * @param db JDBC template used for the locked scrap header, disposition, lock and receipt queries
     * @param actors actor context supplying {@code revalidateLocked} for each mutating entry point
     * @param reads scrap read service whose {@code get} applies the ordinary request visibility rules
     * @param sensitive gate deciding whether the actor holds the {@code SCRAP_ATTACHMENT} field permission
     * @param registry shared attachment registry recording the object under its business type and scope
     * @param json mapper used to read back replayed receipts and to write receipt and event payloads
     * @param clock time source for the truncated millisecond timestamp written to every touched row
     */
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

    /**
     * Maps the client-facing attachment kind onto the shared attachment business type. The mapping is
     * closed: only the two kinds the contract defines are accepted, and the conversion is also used as
     * the validation step, so callers invoke it purely for its rejection behaviour when they do not
     * need the resulting string.
     *
     * @param kind path segment supplied by the client, either {@code request} or {@code disposition}
     * @return {@code SCRAP} for {@code request} and {@code SCRAP_DISPOSITION} for {@code disposition}
     * @throws AssetHandoverPort.Failure 400 {@code SCRAP_ATTACHMENT_KIND_INVALID} for any other value
     */
    public static String type(String kind) {
        return switch (kind) {
            case "request" -> "SCRAP";
            case "disposition" -> "SCRAP_DISPOSITION";
            default -> throw new AssetHandoverPort.Failure("SCRAP_ATTACHMENT_KIND_INVALID", 400);
        };
    }

    /**
     * Asserts that the actor may upload evidence of the given kind for one scrap request, before any
     * bytes are read. The actor is revalidated under the identity lock and the scrap header is taken
     * {@code FOR UPDATE}, so the check and the later registration see the same row state. A
     * {@code request} upload is restricted to the account that created the request; a
     * {@code disposition} upload is restricted to the warehouse operator recorded as the disposition
     * processor.
     *
     * @param actor uploading actor, revalidated here rather than trusted from the controller
     * @param id fams_scrap_request primary key the evidence would be attached to
     * @param kind either {@code request} or {@code disposition}, deciding which precondition applies
     * @throws AssetHandoverPort.Failure 400 for an invalid kind, 403
     *     {@code SCRAP_ATTACHMENT_CREATOR_REQUIRED} when a non-creator uploads request evidence, 403
     *     {@code SCRAP_PROCESSOR_REQUIRED} when a non-processor uploads disposition evidence, or 404
     *     {@code SCRAP_NOT_FOUND} when the request does not exist
     */
    public void uploader(Actor actor, long id, String kind) {
        uploader(actors.revalidateLocked(actor), header(id), kind);
    }

    /**
     * Asserts that the actor may read the evidence of one scrap request. Two independent gates apply:
     * the ordinary scrap visibility rules, enforced by reading the request through
     * {@link ScrapReadService#get}, and the sensitive-field permission, because attachment filenames
     * and sizes disclose more than the request header does. Must be called from inside the ambient
     * transaction so the read it performs joins it.
     *
     * @param actor reading actor whose identity and permissions are checked
     * @param id fams_scrap_request primary key whose evidence is being reached
     * @throws AssetHandoverPort.Failure as raised by the scrap read service when the request does not
     *     exist or is not visible to the actor
     * @throws com.fams.auth.AuthOperationException 403 when the actor lacks the sensitive read permission
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reader(Actor actor, long id) {
        reads.get(actor, id);
        sensitive.requireRead(actor);
    }

    /**
     * Computes the evidence capabilities of one scrap request for one actor. Visibility is established
     * first through the read service, then the actor is revalidated and both the request header and the
     * disposition row are locked so the returned version numbers are the ones a subsequent upload will
     * be compared against.
     *
     * <p>{@code canUploadRequest} requires the actor to be the creator and the request to still be
     * {@code PENDING_ADMIN_DECISION}: once an admin has decided, the submitted evidence is frozen.
     * {@code canUploadDisposition} requires the warehouse operator role, an existing disposition row,
     * that actor to be its processor, and the {@code PENDING_DISPOSITION} status. {@code canRead} is
     * answered without throwing so a client lacking the sensitive permission still receives a usable
     * envelope with every flag false.
     *
     * @param actor reading actor whose identity, role and field permissions decide the flags
     * @param id fams_scrap_request primary key whose capabilities are computed
     * @return capability flags with the current request version and, when a disposition row exists, its
     *     version; the disposition version is null otherwise
     * @throws AssetHandoverPort.Failure as raised by the scrap read service when the request does not
     *     exist or is not visible, or 404 {@code SCRAP_NOT_FOUND} from the locked header read
     */
    public ScrapViews.AttachmentCapabilities capabilities(Actor actor, long id) {
        reads.get(actor, id);
        var current = actors.revalidateLocked(actor);
        var row = header(id);
        var disposition = db.queryForList("SELECT processor_account_id,version,disposition_status"
                + " FROM fams_scrap_disposition WHERE request_id=? FOR UPDATE", id);
        boolean uploadDisposition = current.has(Role.WAREHOUSE_OPERATOR) && !disposition.isEmpty()
                && Objects.equals(disposition.getFirst().get("processor_account_id"), current.id())
                && "PENDING_DISPOSITION".equals(disposition.getFirst().get("disposition_status"));
        return new ScrapViews.AttachmentCapabilities(
                sensitive.mayRead(actor),
                Objects.equals(row.get("creator_account_id"), current.id())
                        && "PENDING_ADMIN_DECISION".equals(row.get("request_status")),
                uploadDisposition,
                ((Number) row.get("version")).longValue(),
                disposition.isEmpty() ? null : ((Number) disposition.getFirst().get("version")).longValue());
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

    /**
     * Commits one quarantined attachment as scrap evidence. This is the callback the attachment writer
     * invokes inside its own transaction, after the bytes are stored and scanned but before the storage
     * is allowed to keep them; a throw here rolls the metadata back and the writer deletes the file.
     *
     * <p>The actor is revalidated and the uploader precondition is re-checked against the locked header,
     * because the caller's first check happened outside this transaction and the row may have moved in
     * between. Idempotency is resolved next: the receipt row is looked up {@code FOR UPDATE} by actor,
     * the fixed operation key {@code ATTACHMENT:<kind>:<id>} and the client key, and a fingerprint of
     * kind, version, filename, sha256, size and MIME distinguishes a genuine replay from a key reused
     * for different content. A matching replay returns the stored receipt without touching any row,
     * which is also the only path where the returned object id differs from the one just stored.
     *
     * <p>Scan results gate registration before any row is written: {@code UNAVAILABLE} is a 503 because
     * the object was never judged, and anything other than {@code CLEAN} is a 422 rejection. The version
     * comparison then differs per kind. Request evidence requires {@code PENDING_ADMIN_DECISION}, the
     * exact request version and an unreleased {@code SCRAP} business lock on the asset, and bumps the
     * request version. Disposition evidence requires the request to be {@code EXECUTED}, a disposition
     * row in {@code PENDING_DISPOSITION} at the exact version, and bumps the disposition version
     * instead. Only a request upload writes a {@code fams_scrap_event} row; both write the audit event
     * and the receipt.
     *
     * @param actor uploading actor, revalidated under the identity lock before the precondition check
     * @param id fams_scrap_request primary key the evidence is attached to
     * @param kind either {@code request} or {@code disposition}, selecting the version target and the
     *     event rows written
     * @param version expected version of the request or disposition row, compared under lock
     * @param key client idempotency key identifying the receipt row for this actor and operation
     * @param filename original filename recorded in the registry and included in the idempotency fingerprint
     * @param attachment quarantined attachment carrying the stored file id, hash, size, format and scan result
     * @return receipt naming the request, the kind, the registered object id and the version after the bump
     * @throws AssetHandoverPort.Failure 400 for an invalid kind, 403 for a non-creator or non-processor,
     *     404 {@code SCRAP_NOT_FOUND}, 409 {@code SCRAP_IDEMPOTENCY_CONFLICT} when the key is reused
     *     with a different fingerprint, 409 {@code SCRAP_REQUEST_CHANGED} or
     *     {@code SCRAP_DISPOSITION_CHANGED} on any precondition or version mismatch, 422
     *     {@code SCRAP_ATTACHMENT_REJECTED} for a non-clean scan, or 503
     *     {@code SCRAP_ATTACHMENT_SCAN_UNAVAILABLE} when the scanner could not judge the object
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ScrapViews.AttachmentReceipt register(Actor actor, long id, String kind, long version, UUID key,
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
                    ScrapViews.AttachmentReceipt.class);
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
        var result = new ScrapViews.AttachmentReceipt(Long.toString(id), kind,
                attachment.file().id().toString(), version + 1);
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
