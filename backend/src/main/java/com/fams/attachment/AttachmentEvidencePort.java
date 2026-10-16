package com.fams.attachment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Owning-domain callers authorize and lock their aggregate before inspecting or discarding its
 * scope.
 */
@Component
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
@Transactional(propagation = Propagation.MANDATORY)
public class AttachmentEvidencePort {
    private final JdbcTemplate db;
    private final Clock clock;
    private final AttachmentStorage storage;
    private final AttachmentGrantSigner grants;

    public AttachmentEvidencePort(
            JdbcTemplate db,
            Clock clock,
            AttachmentStorage storage,
            @org.springframework.beans.factory.annotation.Value(
                            "${fams.security.idempotency-hmac-key}")
                    String secret) {
        this.db = db;
        this.clock = clock;
        this.storage = storage;
        this.grants = new AttachmentGrantSigner(secret, clock);
    }

    public java.util.List<java.util.Map<String, Object>> list(String type, long scope) {
        return db.queryForList(
                "SELECT object_id objectId,original_filename filename,content_type"
                    + " contentType,size_bytes sizeBytes,scan_status scanStatus,business_version"
                    + " businessVersion,created_at createdAt FROM fams_attachment WHERE"
                    + " business_type=? AND business_id=? AND deleted_at IS NULL ORDER BY"
                    + " created_at,object_id",
                type,
                scope);
    }

    public AttachmentGrantSigner.Grant grant(
            String type, long scope, AttachmentGrantSigner.Identity identity) {
        metadata(type, scope, identity.objectId());
        return grants.issue(identity);
    }

    public AttachmentReadService.Content read(
            String type, long scope, AttachmentGrantSigner.Identity identity, String token)
            throws java.io.IOException {
        grants.verify(token, identity);
        var row = metadata(type, scope, identity.objectId());
        try (var content = storage.open(identity.objectId())) {
            byte[] bytes = content.readNBytes((int) LocalAttachmentStorage.MAX_FILE_BYTES + 1);
            String hash;
            try {
                hash =
                        java.util.HexFormat.of()
                                .formatHex(
                                        java.security.MessageDigest.getInstance("SHA-256")
                                                .digest(bytes));
            } catch (java.security.NoSuchAlgorithmException error) {
                throw new IllegalStateException(error);
            }
            if (bytes.length != ((Number) row.get("size_bytes")).longValue()
                    || !hash.equals(row.get("content_sha256"))) {
                throw new AttachmentAccessFailure(409, "ATTACHMENT_CONTENT_CHANGED");
            }
            return new AttachmentReadService.Content(
                    row.get("original_filename").toString(),
                    row.get("content_type").toString(),
                    bytes);
        }
    }

    private java.util.Map<String, Object> metadata(String type, long scope, UUID object) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_attachment WHERE business_type=? AND business_id=? AND"
                            + " object_id=? AND deleted_at IS NULL FOR UPDATE",
                        type,
                        scope,
                        object.toString());
        if (rows.isEmpty()) {
            throw new AttachmentAccessFailure(404, "ATTACHMENT_NOT_FOUND");
        }
        if (!"CLEAN".equals(rows.getFirst().get("scan_status"))) {
            throw new AttachmentAccessFailure(409, "ATTACHMENT_NOT_CLEAN");
        }
        return rows.getFirst();
    }

    public boolean clean(String type, long scope, long uploader, UUID objectId) {
        Long count =
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_attachment WHERE business_type=? AND"
                                + " business_id=? AND uploader_account_id=? AND object_id=? AND"
                                + " deleted_at IS NULL AND scan_status='CLEAN'",
                        Long.class,
                        type,
                        scope,
                        uploader,
                        objectId.toString());
        return count != null && count == 1;
    }

    public void discard(String type, long scope, String reason) {
        if (!"INVENTORY_EVENT".equals(type)
                || !java.util.List.of("EVENT_EXPIRED", "UPLOAD_ABANDONED").contains(reason)) {
            throw new IllegalArgumentException("Unsupported evidence disposal");
        }
        db.queryForList(
                "SELECT business_id FROM fams_attachment_scope WHERE business_type=? AND"
                        + " business_id=? FOR UPDATE",
                type,
                scope);
        var rows =
                db.queryForList(
                        "SELECT object_id,business_version FROM fams_attachment WHERE"
                                + " business_type=? AND business_id=? AND deleted_at IS NULL FOR"
                                + " UPDATE",
                        type,
                        scope);
        var now = clock.instant();
        String run = UUID.randomUUID().toString();
        for (var row : rows) {
            String object = row.get("object_id").toString();
            long version = ((Number) row.get("business_version")).longValue();
            db.update(
                    "UPDATE fams_attachment SET deleted_at=?,business_version=business_version+1"
                            + " WHERE object_id=?",
                    now,
                    object);
            db.update(
                    "INSERT INTO"
                        + " fams_attachment_deletion_job(object_id,reason,next_attempt_at,created_at)"
                        + " VALUES (?,?,?,?)",
                    object,
                    reason,
                    now,
                    now);
            db.update(
                    "INSERT INTO"
                        + " fams_attachment_cleanup_audit(object_id,run_id,action,executor_kind,before_business_version,after_business_version,expiry_cutoff,occurred_at)"
                        + " VALUES (?,?,?,'DOMAIN_RETENTION',?,?,?,?)",
                    object,
                    run,
                    reason,
                    version,
                    version + 1,
                    now,
                    now);
        }
    }
}
