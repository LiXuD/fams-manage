package com.fams.attachment;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal persistence boundary; business authorization must precede registration. No public route exists here. */
@Service
@Profile("fams-product")
public class AttachmentRegistry {
    private final JdbcTemplate db;
    private final Clock clock;

    public AttachmentRegistry(JdbcTemplate db, Clock clock) { this.db = db; this.clock = clock; }

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
