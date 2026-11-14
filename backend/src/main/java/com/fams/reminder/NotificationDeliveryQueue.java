package com.fams.reminder;

import com.fams.job.DurableJobService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("fams-product")
class NotificationDeliveryQueue {
    static final String KIND = "NOTIFICATION_SMS";
    private static final String ACTIVE_STAGE = """
            AND (
                (r.business_type = 'INBOUND' AND EXISTS (
                    SELECT 1 FROM fams_inbound_receipt x WHERE x.id = r.business_id AND (
                        (r.stage = 'ADMIN_DECISION' AND x.receipt_status = 'PENDING_ADMIN_DECISION'
                            AND x.admin_decision_due_at = r.deadline_at)
                        OR (r.stage = 'WAREHOUSE_CLAIM' AND x.receipt_status = 'WAITING_WAREHOUSE_CLAIM'
                            AND x.warehouse_claim_due_at = r.deadline_at)
                        OR (r.stage = 'FIRST_EXECUTION' AND x.receipt_status = 'WAREHOUSE_PROCESSING'
                            AND x.warehouse_first_execution_due_at = r.deadline_at)
                    )
                ))
                OR (r.business_type = 'REQUISITION_ALLOCATION' AND r.stage = 'HANDOVER_RECEIPT' AND EXISTS (
                    SELECT 1 FROM fams_requisition_allocation x
                    JOIN fams_requisition_handover h ON h.allocation_id = x.id
                    WHERE x.id = r.business_id AND (r.reminder_type = 'DUTY_UNREAD_ESCALATION'
                        OR h.temporary_employee_id = e.id)
                        AND x.allocation_status = 'DELIVERED_WAITING_RECEIPT'
                        AND h.receipt_deadline_at = r.deadline_at
                ))
                OR (r.business_type = 'TRANSFER_ITEM' AND r.stage = 'HANDOVER_RECEIPT' AND EXISTS (
                    SELECT 1 FROM fams_transfer_item x WHERE x.id = r.business_id AND x.item_status = 'DELIVERED'
                        AND x.receipt_deadline_at = r.deadline_at
                        AND (r.reminder_type = 'DUTY_UNREAD_ESCALATION' OR x.receiver_employee_id = e.id)
                ))
                OR (r.business_type = 'INVENTORY_TASK' AND r.stage = 'INVENTORY_COMPLETION' AND EXISTS (
                    SELECT 1 FROM fams_inventory_task t JOIN fams_inventory_item i ON i.task_id = t.id
                    WHERE t.id = r.business_id AND t.task_status = 'IN_PROGRESS' AND t.due_at = r.deadline_at
                        AND i.executor_account_id = a.id
                ))
                OR (r.business_type = 'LOST_REPORT' AND r.stage = 'LOSS_REVIEW' AND EXISTS (
                    SELECT 1 FROM fams_loss_report x
                    WHERE x.id = r.business_id AND x.report_status IN ('SEARCHING','CONFIRMED_LOST')
                        AND x.processor_account_id = a.id
                        AND x.next_review_date = DATE_SUB(r.notice_date, INTERVAL 1 DAY)
                ))
                OR (r.business_type = 'SCRAP' AND r.stage = 'SCRAP_DISPOSITION' AND EXISTS (
                    SELECT 1 FROM fams_scrap_request x JOIN fams_scrap_disposition d ON d.request_id = x.id
                    WHERE x.id = r.business_id AND x.request_status = 'EXECUTED'
                        AND d.disposition_status = 'PENDING_DISPOSITION' AND d.processor_account_id = a.id
                        AND d.due_date = DATE_SUB(r.notice_date, INTERVAL 1 DAY)
                ))
            )
            """;
    private static final String FALLBACK = """
            r.reminder_type = 'SMS_FALLBACK' AND (
                (r.business_type = 'LOAN' AND r.stage = 'LOAN_RETURN' AND EXISTS (
                    SELECT 1 FROM fams_loan l JOIN fams_asset asset ON asset.id = l.asset_id
                    JOIN fams_todo t ON t.business_type = 'LOAN' AND t.business_id = l.id
                        AND t.todo_type = 'OVERDUE' AND t.todo_status = 'OPEN'
                        AND t.pool_type = 'EMPLOYEE' AND t.assignee_account_id = a.id
                    WHERE l.id = r.business_id AND l.borrower_employee_id = e.id AND l.relation_status = 'OPEN'
                        AND asset.lifecycle_status <> 'SCRAPPED' AND asset.exception_status <> 'LOST'
                        AND l.expected_return_date = DATE(r.deadline_at)
                        AND NOT EXISTS (
                            SELECT 1 FROM fams_message_receipt m WHERE m.account_id = a.id
                                AND m.message_kind = 'TODO' AND m.message_id = t.id
                        )
                ) AND NOT EXISTS (
                    SELECT 1 FROM fams_business_reminder prev
                    JOIN fams_notification_delivery d ON d.reminder_id = prev.id AND d.recipient_account_id = a.id
                    JOIN fams_durable_job j ON j.id = d.job_id
                    WHERE prev.business_type = 'LOAN' AND prev.business_id = r.business_id
                        AND prev.stage = 'LOAN_RETURN' AND prev.reminder_type = 'SMS_FALLBACK'
                        AND prev.id <> r.id
                        AND (d.accepted_at > DATE_SUB(?, INTERVAL 3 DAY)
                            OR (prev.id < r.id AND j.job_status IN ('QUEUED','RUNNING','RETRY_WAIT')))
                ))
                OR (r.business_type = 'REPAIR_FACTORY_ROUND' AND r.stage = 'FACTORY_RETURN'
                    AND r.deadline_at <= ? AND EXISTS (
                        SELECT 1 FROM fams_repair_factory_round x JOIN fams_repair_request p ON p.id = x.repair_id
                        WHERE x.id = r.business_id AND x.round_status = 'DISPATCHED'
                            AND p.processor_account_id = a.id
                            AND x.expected_return_date = DATE_SUB(r.notice_date, INTERVAL 1 DAY)
                    ) AND NOT EXISTS (
                        SELECT 1 FROM fams_message_receipt m
                        WHERE m.account_id = a.id AND m.message_kind = 'REMINDER' AND m.message_id = (
                            SELECT MAX(n.id) FROM fams_business_reminder n
                            WHERE n.business_type = r.business_type AND n.business_id = r.business_id
                                AND n.stage = r.stage AND n.deadline_at = r.deadline_at
                                AND n.reminder_type IN ('DUE_TODAY','OVERDUE_EVERY_7_DAYS')
                        )
                    ))
            )
            """;
    private static final String ELIGIBLE = """
            AND ((
                (r.reminder_type = 'DUTY_UNREAD_ESCALATION')
                OR (r.reminder_type = 'OVERDUE' AND r.deadline_at <= ? AND (
                    (r.recipient_pool_type = 'EMPLOYEE' AND r.stage IN ('HANDOVER_RECEIPT','INVENTORY_COMPLETION'))
                    OR (r.recipient_pool_type = 'WAREHOUSE_OPERATOR'
                        AND r.stage IN ('LOSS_REVIEW','SCRAP_DISPOSITION'))
                ))
            ) """ + ACTIVE_STAGE + """
            OR (""" + FALLBACK + """
            )
            ) AND a.status = 'ENABLED' AND NOT a.initial_password_required
            AND e.status = 'ACTIVE' AND o.status = 'ACTIVE'
            AND NOT EXISTS (
                SELECT 1 FROM fams_message_receipt m
                WHERE m.account_id = a.id AND m.message_kind = 'REMINDER' AND m.message_id = r.id
            )
            """;
    private final JdbcTemplate db;
    private final DurableJobService jobs;
    private final Clock clock;

    NotificationDeliveryQueue(JdbcTemplate db, DurableJobService jobs, Clock clock) {
        this.db = db;
        this.jobs = jobs;
        this.clock = clock;
    }

    @Transactional
    public void enqueue(String template) {
        var now = clock.instant();
        var dayAgo = Timestamp.from(now.minusSeconds(86400));
        List<Recipient> due = db.query("""
                SELECT r.id, r.recipient_account_id
                FROM fams_business_reminder r
                JOIN fams_system_account a ON a.id = r.recipient_account_id
                JOIN fams_employee e ON e.id = a.employee_id
                JOIN fams_organization o ON o.id = e.organization_id
                JOIN fams_account_role ar ON ar.account_id = a.id AND ar.role_code = r.recipient_pool_type
                WHERE (r.reminder_type <> 'DUTY_UNREAD_ESCALATION' OR r.created_at >= ?)
                """ + ELIGIBLE + """
                  AND (r.reminder_type <> 'SMS_FALLBACK' OR NOT EXISTS (
                      SELECT 1 FROM fams_business_reminder newer
                      WHERE newer.business_type = r.business_type AND newer.business_id = r.business_id
                          AND newer.stage = r.stage AND newer.reminder_type = r.reminder_type
                          AND newer.recipient_slot = r.recipient_slot AND newer.id > r.id
                  ))
                  AND NOT EXISTS (
                      SELECT 1 FROM fams_notification_delivery d
                      WHERE d.reminder_id = r.id AND d.recipient_account_id = a.id AND d.channel = 'SMS'
                  )
                ORDER BY r.id LIMIT 100
                """, (row, index) -> new Recipient(row.getLong(1), row.getLong(2)),
                dayAgo, dayAgo, Timestamp.from(now), dayAgo);
        for (var recipient : due) {
            String reference = Long.toString(recipient.reminderId());
            String key = "reminder-" + reference;
            var job = jobs.submit(KIND, reference, recipient.accountId(), key, hash(key));
            db.update("""
                    INSERT INTO fams_notification_delivery
                        (reminder_id,recipient_account_id,channel,template_code,job_id,requested_at)
                    VALUES (?,?,'SMS',?,?,?)
                    ON DUPLICATE KEY UPDATE reminder_id = fams_notification_delivery.reminder_id
                    """, recipient.reminderId(), recipient.accountId(), template, job.id().toString(),
                    Timestamp.from(clock.instant()));
        }
    }

    String eligibleTemplate(DurableJobService.Claim claim) {
        var now = clock.instant();
        var dayAgo = Timestamp.from(now.minusSeconds(86400));
        return db.query("""
                SELECT d.template_code FROM fams_notification_delivery d
                JOIN fams_business_reminder r ON r.id = d.reminder_id
                JOIN fams_system_account a ON a.id = d.recipient_account_id
                JOIN fams_employee e ON e.id = a.employee_id
                JOIN fams_organization o ON o.id = e.organization_id
                JOIN fams_account_role ar ON ar.account_id = a.id AND ar.role_code = r.recipient_pool_type
                WHERE d.job_id = ?
                """ + ELIGIBLE, (row, index) -> row.getString(1), claim.id().toString(), dayAgo, Timestamp.from(now), dayAgo)
                .stream().findFirst().orElse(null);
    }

    void accepted(DurableJobService.Claim claim) {
        db.update("UPDATE fams_notification_delivery SET accepted_at = ? WHERE job_id = ?",
                Timestamp.from(clock.instant()), claim.id().toString());
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record Recipient(long reminderId, long accountId) {}
}
