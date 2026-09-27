package com.fams.attachment;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Soft deletion and its audit commit before a retryable worker removes private bytes. */
@Service
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
public final class AttachmentCleanupService {
    private final JdbcTemplate db;
    private final AttachmentStorage storage;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public AttachmentCleanupService(JdbcTemplate db, AttachmentStorage storage, Clock clock,
            PlatformTransactionManager transactions) {
        this.db = db; this.storage = storage; this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
    }

    public int expireDrafts(int limit) {
        standalone(limit);
        Instant now = clock.instant();
        Instant cutoff = now.minus(Duration.ofDays(30));
        UUID runId = UUID.randomUUID();
        var candidates = db.queryForList("SELECT a.object_id,a.business_id FROM fams_attachment a "
                + "JOIN fams_inbound_receipt r ON r.id=a.business_id "
                + "WHERE a.business_type='INBOUND' AND a.deleted_at IS NULL AND a.created_at<=? "
                + "AND r.receipt_status='DRAFT' AND r.submitted_at IS NULL ORDER BY a.created_at,a.object_id LIMIT ?",
                cutoff, limit);
        int expired = 0;
        for (var candidate : candidates) {
            boolean changed = Boolean.TRUE.equals(transaction.execute(status -> {
                String id = (String) candidate.get("object_id");
                long receiptId = ((Number) candidate.get("business_id")).longValue();
                // Same receipt -> scope -> attachment lock order as upload registration.
                var receipts = db.queryForList("SELECT version FROM fams_inbound_receipt "
                        + "WHERE id=? AND receipt_status='DRAFT' AND submitted_at IS NULL FOR UPDATE", receiptId);
                if (receipts.isEmpty()) { return false; }
                db.queryForList("SELECT business_id FROM fams_attachment_scope "
                        + "WHERE business_type='INBOUND' AND business_id=? FOR UPDATE", receiptId);
                int removed = db.update("UPDATE fams_attachment SET deleted_at=? WHERE object_id=? "
                        + "AND business_type='INBOUND' AND business_id=? AND deleted_at IS NULL AND created_at<=?",
                        now, id, receiptId, cutoff);
                if (removed == 0) { return false; }
                long version = ((Number) receipts.getFirst().get("version")).longValue();
                db.update("UPDATE fams_inbound_receipt SET version=version+1,updated_at=? WHERE id=?", now, receiptId);
                db.update("INSERT INTO fams_attachment_deletion_job "
                        + "(object_id,reason,next_attempt_at,created_at) VALUES (?,'DRAFT_EXPIRED',?,?)", id, now, now);
                db.update("INSERT INTO fams_attachment_cleanup_audit (object_id,run_id,action,executor_kind,"
                        + "before_business_version,after_business_version,expiry_cutoff,occurred_at) "
                        + "VALUES (?,?,'DRAFT_EXPIRED','SCHEDULED_MAINTENANCE',?,?,?,?)",
                        id, runId.toString(), version, version + 1, cutoff, now);
                return true;
            }));
            if (changed) { expired++; }
        }
        return expired;
    }

    public int deletePending(int limit) {
        standalone(limit);
        Instant now = clock.instant();
        var candidates = db.queryForList("SELECT object_id FROM fams_attachment_deletion_job "
                + "WHERE job_status='PENDING' AND next_attempt_at<=? ORDER BY next_attempt_at,object_id LIMIT ?", now, limit);
        int deleted = 0;
        for (var candidate : candidates) {
            boolean done = Boolean.TRUE.equals(transaction.execute(status -> {
                String id = (String) candidate.get("object_id");
                var jobs = db.queryForList("SELECT attempts FROM fams_attachment_deletion_job "
                        + "WHERE object_id=? AND job_status='PENDING' AND next_attempt_at<=? FOR UPDATE", id, now);
                if (jobs.isEmpty()) { return false; }
                var attachment = db.queryForList("SELECT object_id FROM fams_attachment "
                        + "WHERE object_id=? AND deleted_at IS NOT NULL FOR UPDATE", id);
                if (attachment.isEmpty()) { throw new IllegalStateException("Deletion job requires committed soft deletion"); }
                int attempts = ((Number) jobs.getFirst().get("attempts")).intValue();
                try {
                    storage.delete(UUID.fromString(id));
                    db.update("UPDATE fams_attachment_deletion_job SET job_status='DONE',attempts=attempts+1,"
                            + "completed_at=?,last_error_code=NULL WHERE object_id=?", now, id);
                    return true;
                } catch (IOException error) {
                    long delay = Math.min(3600, 30L << Math.min(attempts, 7));
                    db.update("UPDATE fams_attachment_deletion_job SET attempts=attempts+1,next_attempt_at=?,"
                            + "last_error_code='STORAGE_DELETE_FAILED' WHERE object_id=?", now.plusSeconds(delay), id);
                    return false;
                }
            }));
            if (done) { deleted++; }
        }
        return deleted;
    }

    private void standalone(int limit) {
        if (limit < 1 || limit > 1000 || TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalArgumentException("Cleanup requires an independent transaction boundary and batch 1..1000");
        }
    }
}
