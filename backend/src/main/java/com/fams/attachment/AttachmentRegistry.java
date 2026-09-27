package com.fams.attachment;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** Internal persistence boundary; business authorization must precede registration. No public route exists here. */
@Service
@Profile("fams-product")
public class AttachmentRegistry {
    private final JdbcTemplate db;
    private final Clock clock;

    public AttachmentRegistry(JdbcTemplate db, Clock clock) { this.db = db; this.clock = clock; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void auditUpload(long actorId, UUID requestId, String businessType, long businessId,
            long version, AttachmentIntake.QuarantinedAttachment attachment) {
        db.update("INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,action,resource_type,"
                + "resource_id,before_version,after_version,occurred_at,detail_json) "
                + "VALUES (?,?,'WAREHOUSE_OPERATOR','ATTACHMENT_UPLOADED',?,?,?,?,?,"
                + "JSON_OBJECT('objectId',?,'contentSha256',?,'sizeBytes',?,'scanStatus',?))",
                requestId.toString(), actorId, businessType, businessId, version, version + 1, Instant.now(clock),
                attachment.file().id().toString(), attachment.file().sha256(), attachment.file().size(),
                attachment.scanResult().name());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void auditCorrection(long actorId, UUID requestId, String businessType, long businessId,
            long version, AttachmentIntake.QuarantinedAttachment attachment, UUID previousObjectId, String reason) {
        db.update("INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,action,resource_type,"
                + "resource_id,before_version,after_version,occurred_at,detail_json) "
                + "VALUES (?,?,'WAREHOUSE_OPERATOR','ATTACHMENT_CORRECTED',?,?,?,?,?,"
                + "JSON_OBJECT('objectId',?,'previousObjectId',?,'reason',?,'contentSha256',?,'scanStatus',?))",
                requestId.toString(), actorId, businessType, businessId, version, version + 1, Instant.now(clock),
                attachment.file().id().toString(), previousObjectId.toString(), reason,
                attachment.file().sha256(), attachment.scanResult().name());
    }

    /** Caller authorizes the current business version; correction never updates the original record. */
    @Transactional
    public void registerCorrection(String businessType, long businessId, long businessVersion, long uploaderId,
            String filename, AttachmentIntake.QuarantinedAttachment attachment, UUID previousObjectId, String reason) {
        if (previousObjectId == null || reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("Correction requires an original attachment and a reason");
        }
        db.update("INSERT INTO fams_attachment_scope (business_type,business_id) VALUES (?,?) "
                + "ON DUPLICATE KEY UPDATE business_id=VALUES(business_id)", businessType, businessId);
        var original = db.queryForList("SELECT object_id FROM fams_attachment WHERE object_id=? "
                + "AND business_type=? AND business_id=? AND deleted_at IS NULL FOR UPDATE",
                previousObjectId.toString(), businessType, businessId);
        if (original.isEmpty()) { throw new IllegalArgumentException("Original attachment does not belong to this business"); }
        if (!db.queryForList("SELECT object_id FROM fams_attachment_revision WHERE previous_object_id=? FOR UPDATE",
                previousObjectId.toString()).isEmpty()) {
            throw new IllegalArgumentException("Correct the latest attachment version");
        }
        var previous = db.queryForList("SELECT revision_number FROM fams_attachment_revision WHERE object_id=? FOR UPDATE",
                previousObjectId.toString());
        int revision = previous.isEmpty() ? 2 : ((Number) previous.getFirst().get("revision_number")).intValue() + 1;
        register(businessType, businessId, businessVersion, uploaderId, filename, attachment);
        db.update("INSERT INTO fams_attachment_revision (object_id,previous_object_id,revision_number,correction_reason) "
                + "VALUES (?,?,?,?)", attachment.file().id().toString(), previousObjectId.toString(), revision, reason.trim());
    }

    @Transactional
    public void register(String businessType, long businessId, long businessVersion, long uploaderId,
            String filename, AttachmentIntake.QuarantinedAttachment attachment) {
        if (businessType == null || businessType.isBlank() || businessType.length() > 40 || businessId <= 0
                || businessVersion < 0 || uploaderId <= 0 || filename == null || filename.isBlank()
                || filename.length() > 255 || filename.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Attachment registration metadata is invalid"); }
        var file = attachment.file();
        long maxFile = attachment.format().image() ? 10L * 1024 * 1024 : 20L * 1024 * 1024;
        if (file.size() <= 0 || file.size() > maxFile || !file.sha256().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Attachment content metadata is invalid"); }
        // The upsert locks even a previously absent scope until this transaction completes.
        db.update("INSERT INTO fams_attachment_scope (business_type,business_id) VALUES (?,?) "
                + "ON DUPLICATE KEY UPDATE business_id=VALUES(business_id)", businessType, businessId);
        var usage = db.queryForMap("SELECT COALESCE(SUM(file_format <> 'PDF'),0) images,"
                + "COALESCE(SUM(file_format = 'PDF'),0) pdfs,COALESCE(SUM(size_bytes),0) bytes "
                + "FROM fams_attachment WHERE business_type=? AND business_id=? AND deleted_at IS NULL FOR UPDATE",
                businessType, businessId);
        long images = ((Number) Objects.requireNonNull(usage.get("images"))).longValue();
        long pdfs = ((Number) Objects.requireNonNull(usage.get("pdfs"))).longValue();
        long bytes = ((Number) Objects.requireNonNull(usage.get("bytes"))).longValue();
        if ((attachment.format().image() && images >= 9) || (!attachment.format().image() && pdfs >= 5)
                || bytes > 50L * 1024 * 1024 - file.size()) {
            throw new IllegalArgumentException("Attachment business quota exceeded"); }
        db.update("INSERT INTO fams_attachment (object_id,business_type,business_id,original_filename,"
                + "content_type,file_format,size_bytes,content_sha256,scan_status,uploader_account_id,"
                + "business_version,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", file.id().toString(),
                businessType, businessId, filename, attachment.format().mime(), attachment.format().name(),
                file.size(), file.sha256(), attachment.scanResult().name(), uploaderId, businessVersion,
                Instant.now(clock));
    }
}
