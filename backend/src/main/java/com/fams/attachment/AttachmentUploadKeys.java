package com.fams.attachment;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Caller holds the uploader account lock throughout replay lookup and registration. */
@Component
@Profile("fams-product")
public final class AttachmentUploadKeys {
    public static final class Replay extends RuntimeException {
        private final AttachmentIntake.QuarantinedAttachment attachment;
        Replay(AttachmentIntake.QuarantinedAttachment attachment) { this.attachment = attachment; }
        public AttachmentIntake.QuarantinedAttachment attachment() { return attachment; }
    }
    private final JdbcTemplate db;
    public AttachmentUploadKeys(JdbcTemplate db) { this.db = db; }

    public boolean exists(long actorId, String key) {
        return !db.queryForList("SELECT object_id FROM fams_attachment_upload_key "
                + "WHERE uploader_account_id=? AND idempotency_key=?", actorId, key).isEmpty();
    }

    public void replay(long actorId, String key, String businessType, long businessId, long version,
            String filename, String mime, AttachmentIntake.QuarantinedAttachment incoming) {
        var rows = db.queryForList("SELECT a.*,k.expected_business_version FROM fams_attachment_upload_key k "
                + "JOIN fams_attachment a ON a.object_id=k.object_id "
                + "WHERE k.uploader_account_id=? AND k.idempotency_key=? FOR UPDATE", actorId, key);
        if (rows.isEmpty()) { return; }
        var row = rows.getFirst();
        if (!businessType.equals(row.get("business_type"))
                || businessId != ((Number) row.get("business_id")).longValue()
                || version != ((Number) row.get("expected_business_version")).longValue()
                || !filename.equals(row.get("original_filename")) || !mime.equals(row.get("content_type"))
                || !incoming.file().sha256().equals(row.get("content_sha256"))
                || incoming.file().size() != ((Number) row.get("size_bytes")).longValue()) {
            throw new AttachmentUploadConflict();
        }
        throw new Replay(new AttachmentIntake.QuarantinedAttachment(
                new AttachmentStorage.StoredFile(UUID.fromString((String) row.get("object_id")),
                    ((Number) row.get("size_bytes")).longValue(), (String) row.get("content_sha256")),
                AttachmentFormat.valueOf((String) row.get("file_format")),
                MalwareScanner.Result.valueOf((String) row.get("scan_status"))));
    }

    public void save(long actorId, String key, long version, UUID objectId) {
        db.update("INSERT INTO fams_attachment_upload_key "
                + "(uploader_account_id,idempotency_key,object_id,expected_business_version) VALUES (?,?,?,?)",
                actorId, key, objectId.toString(), version);
    }
}
