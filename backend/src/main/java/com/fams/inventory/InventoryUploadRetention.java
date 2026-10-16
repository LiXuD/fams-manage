package com.fams.inventory;

import com.fams.attachment.AttachmentEvidencePort;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

/**
 * Unsubmitted quarantine scopes are not observations; bounded cleanup never changes a task report.
 */
@Component
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
public class InventoryUploadRetention {
    private final JdbcTemplate db;
    private final AttachmentEvidencePort evidence;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public InventoryUploadRetention(
            JdbcTemplate db,
            AttachmentEvidencePort evidence,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.db = db;
        this.evidence = evidence;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Scheduled(fixedDelayString = "${fams.inventory.upload-cleanup-poll-ms:60000}")
    public void poll() {
        expire(50);
    }

    public void expire(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Inventory upload cleanup batch must be 1..100");
        }
        var cutoff = clock.instant().minus(Duration.ofHours(72));
        var rows =
                db.queryForList(
                        "SELECT id,task_id,client_event_id FROM fams_inventory_upload_scope s WHERE"
                            + " discarded_at IS NULL AND created_at<=? AND NOT EXISTS(SELECT 1 FROM"
                            + " fams_inventory_observation o WHERE o.task_id=s.task_id AND"
                            + " o.client_event_id=s.client_event_id) ORDER BY created_at,id LIMIT"
                            + " ?",
                        cutoff,
                        limit);
        for (var row : rows) {
            transaction.executeWithoutResult(
                    status -> {
                        long task = ((Number) row.get("task_id")).longValue();
                        db.queryForList(
                                "SELECT id FROM fams_inventory_task WHERE id=? FOR UPDATE", task);
                        var scope =
                                db.queryForList(
                                        "SELECT id FROM fams_inventory_upload_scope WHERE id=? AND"
                                            + " discarded_at IS NULL AND created_at<=? FOR UPDATE",
                                        row.get("id"),
                                        cutoff);
                        if (scope.isEmpty()) {
                            return;
                        }
                        var observation =
                                db.queryForList(
                                        "SELECT id FROM fams_inventory_observation WHERE task_id=?"
                                                + " AND client_event_id=? FOR SHARE",
                                        task,
                                        row.get("client_event_id"));
                        if (observation.isEmpty()) {
                            evidence.discard(
                                    "INVENTORY_EVENT",
                                    ((Number) row.get("id")).longValue(),
                                    "UPLOAD_ABANDONED");
                            db.update(
                                    "UPDATE fams_inventory_upload_scope SET discarded_at=? WHERE"
                                            + " id=?",
                                    clock.instant(),
                                    row.get("id"));
                        }
                    });
        }
    }
}
