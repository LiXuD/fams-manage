package com.fams.attachment;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fams.auth.AttachmentReaderContext;
import com.fams.inbound.InboundAttachmentPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
public class AttachmentReadService {
    public record Content(String filename, String mime, byte[] bytes) { }
    private final JdbcTemplate db;
    private final AttachmentReaderContext actors;
    private final InboundAttachmentPort business;
    private final AttachmentStorage storage;
    private final AttachmentGrantSigner grants;
    public AttachmentReadService(JdbcTemplate db, AttachmentReaderContext actors, InboundAttachmentPort business,
            AttachmentStorage storage, Clock clock, @Value("${fams.security.idempotency-hmac-key}") String secret) {
        this.db = db;
        this.actors = actors;
        this.business = business;
        this.storage = storage;
        this.grants = new AttachmentGrantSigner(secret, clock);
    }

    @Transactional
    public List<Map<String, Object>> list(long receiptId) {
        var actor = actors.requireReaderLocked();
        business.requireReaderLocked(actor.id(), actor.roles(), receiptId);
        return db.queryForList("SELECT a.object_id objectId,a.original_filename filename,a.content_type contentType,"
                + "a.size_bytes sizeBytes,a.scan_status scanStatus,a.business_version businessVersion,a.created_at createdAt,"
                + "COALESCE(r.revision_number,1) revisionNumber,r.previous_object_id previousObjectId,"
                + "r.correction_reason correctionReason,n.object_id nextObjectId FROM fams_attachment a "
                + "LEFT JOIN fams_attachment_revision r ON r.object_id=a.object_id "
                + "LEFT JOIN fams_attachment_revision n ON n.previous_object_id=a.object_id "
                + "WHERE a.business_type='INBOUND' AND a.business_id=? AND a.deleted_at IS NULL "
                + "ORDER BY a.created_at,a.object_id FOR UPDATE", receiptId);
    }

    @Transactional
    public Map<String, Object> grant(UUID objectId) {
        var actor = actors.requireReaderLocked();
        metadata(actor, objectId);
        var grant = grants.issue(identity(actor, objectId));
        return Map.of("url", "/api/v1/attachments/" + objectId + "/content?grant=" + grant.token(),
                "expiresAtEpochSecond", grant.expiresAtEpochSecond());
    }

    @Transactional(rollbackFor = IOException.class)
    public Content read(UUID objectId, String token) throws IOException {
        var actor = actors.requireReaderLocked();
        grants.verify(token, identity(actor, objectId));
        var row = metadata(actor, objectId);
        try (var stream = storage.open(objectId)) {
            byte[] bytes = stream.readNBytes((int) LocalAttachmentStorage.MAX_FILE_BYTES + 1);
            if (bytes.length != ((Number) row.get("size_bytes")).longValue()
                    || !hash(bytes).equals(row.get("content_sha256"))) {
                throw new AttachmentAccessFailure(409, "ATTACHMENT_CONTENT_CHANGED");
            }
            return new Content((String) row.get("original_filename"), (String) row.get("content_type"), bytes);
        }
    }

    private Map<String, Object> metadata(AttachmentReaderContext.Reader actor, UUID objectId) {
        var rows = db.queryForList("SELECT business_type,business_id FROM fams_attachment WHERE object_id=?",
                objectId.toString());
        if (rows.isEmpty() || !"INBOUND".equals(rows.getFirst().get("business_type"))) { throw missing(); }
        business.requireReaderLocked(actor.id(), actor.roles(), ((Number) rows.getFirst().get("business_id")).longValue());
        var current = db.queryForList("SELECT * FROM fams_attachment WHERE object_id=? AND deleted_at IS NULL FOR UPDATE",
                objectId.toString());
        if (current.isEmpty()) { throw missing(); }
        var row = current.getFirst();
        if (!"CLEAN".equals(row.get("scan_status"))) {
            throw new AttachmentAccessFailure(409, "ATTACHMENT_NOT_CLEAN");
        }
        return row;
    }
    private AttachmentGrantSigner.Identity identity(AttachmentReaderContext.Reader actor, UUID id) {
        return new AttachmentGrantSigner.Identity(id, actor.id(), actor.authEpoch(), actor.authorizationVersion());
    }
    private AttachmentAccessFailure missing() { return new AttachmentAccessFailure(404, "ATTACHMENT_NOT_FOUND"); }
    private String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
