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
    static final String WECHAT_KIND = "NOTIFICATION_WECHAT";
    private static final String GRANT_MATCH = """
            ((g.business_type = 'REQUISITION' AND r.business_type = 'REQUISITION_ALLOCATION' AND EXISTS (
                SELECT 1 FROM fams_requisition_allocation x WHERE x.id = r.business_id AND x.request_id = g.business_id))
            OR (g.business_type = 'REPAIR' AND r.business_type = 'REPAIR_FACTORY_ROUND' AND EXISTS (
                SELECT 1 FROM fams_repair_factory_round x WHERE x.id = r.business_id AND x.repair_id = g.business_id))
            OR (g.business_type = r.business_type AND g.business_id = r.business_id))
            """;
    private static final String GRANT_AVAILABLE = """
            SELECT 1 FROM fams_wechat_subscription_grant g
            WHERE g.account_id = a.id AND g.status = 'AVAILABLE' AND g.template_id = ? AND
            """ + GRANT_MATCH + """
                AND NOT EXISTS (SELECT 1 FROM fams_notification_delivery d2 WHERE d2.grant_id = g.id)
            """;
    private static final String ACTIVE_STAGE = """
            AND (
                (r.business_type = 'INBOUND' AND
            """ + InboundReminderState.CURRENT_STAGE + """
                )
                OR (r.business_type = 'REQUISITION_ALLOCATION' AND r.stage = 'HANDOVER_RECEIPT' AND EXISTS (
                    SELECT 1 FROM fams_requisition_allocation x
                    JOIN fams_requisition_handover h ON h.allocation_id = x.id
                    JOIN fams_requisition_demand d ON d.id = x.demand_id
                    JOIN fams_requisition q ON q.id = x.request_id
                    WHERE x.id = r.business_id AND (r.reminder_type = 'DUTY_UNREAD_ESCALATION'
                        OR COALESCE(x.receipt_target_employee_id,d.target_employee_id,d.target_department_employee_id) = e.id)
                        AND x.allocation_status = 'DELIVERED_WAITING_RECEIPT'
                        AND q.request_status = 'WAITING_HANDOVER'
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
                    SELECT 1 FROM fams_loss_report x JOIN fams_asset asset ON asset.id = x.asset_id
                    WHERE x.id = r.business_id AND x.report_status IN ('SEARCHING','CONFIRMED_LOST')
                        AND asset.lifecycle_status <> 'SCRAPPED'
                        AND NOT EXISTS (
                            SELECT 1 FROM fams_scrap_request s JOIN fams_scrap_disposition d ON d.request_id = s.id
                            WHERE s.source_type = 'LOSS' AND s.source_business_id = x.id
                                AND d.disposition_status = 'LOSS_WRITTEN_OFF'
                        )
                        AND x.processor_account_id = a.id
                        AND x.next_review_date = DATE_SUB(r.notice_date, INTERVAL 1 DAY)
                ))
                OR (r.business_type = 'SCRAP' AND r.stage = 'SCRAP_DISPOSITION' AND EXISTS (
                    SELECT 1 FROM fams_scrap_request x JOIN fams_scrap_disposition d ON d.request_id = x.id
                    WHERE x.id = r.business_id AND x.request_status = 'EXECUTED'
                        AND d.disposition_status = 'PENDING_DISPOSITION' AND d.processor_account_id = a.id
                        AND d.due_date = DATE_SUB(r.notice_date, INTERVAL 1 DAY)
                        AND (d.due_at IS NULL OR d.due_at = r.deadline_at)
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
                        JOIN fams_todo t ON t.business_type='REPAIR' AND t.business_id=x.repair_id
                            AND t.todo_type='FACTORY_RETURN' AND t.todo_status='OPEN'
                            AND t.closed_at IS NULL AND t.opened_at>=x.dispatched_at
                        WHERE x.id = r.business_id AND x.round_status = 'DISPATCHED'
                            AND p.processor_account_id = a.id
                            AND x.expected_return_date = DATE_SUB(r.notice_date, INTERVAL 1 DAY)
                    ) AND NOT EXISTS (
                        SELECT 1 FROM fams_message_receipt m
                        WHERE m.account_id = a.id AND m.message_kind = 'REMINDER' AND m.message_id = (
                            SELECT MAX(n.id) FROM fams_business_reminder n
                            WHERE n.business_type = r.business_type AND n.business_id = r.business_id
                                AND n.stage = r.stage AND n.deadline_at = r.deadline_at
                                AND n.reminder_type IN ('DUE_TODAY','OVERDUE','OVERDUE_EVERY_7_DAYS')
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

    @Transactional
    public void enqueueWechat(String template) {
        var now = clock.instant();
        var dayAgo = Timestamp.from(now.minusSeconds(86400));
        List<WechatRecipient> due = db.query("""
                SELECT r.id, r.recipient_account_id, (
                    SELECT g.id FROM fams_wechat_subscription_grant g
                    WHERE g.account_id = a.id AND g.status = 'AVAILABLE' AND g.template_id = ? AND
                """ + GRANT_MATCH + """
                        AND NOT EXISTS (SELECT 1 FROM fams_notification_delivery d2 WHERE d2.grant_id = g.id)
                    ORDER BY g.id LIMIT 1)
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
                  AND EXISTS (SELECT 1 FROM fams_mini_binding b WHERE b.account_id = a.id AND b.revoked_at IS NULL)
                  AND EXISTS (""" + GRANT_AVAILABLE + """
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM fams_notification_delivery d
                      WHERE d.reminder_id = r.id AND d.recipient_account_id = a.id AND d.channel = 'WECHAT'
                  )
                ORDER BY r.id LIMIT 100
                """, (row, index) -> new WechatRecipient(row.getLong(1), row.getLong(2), row.getLong(3)),
                template, dayAgo, dayAgo, Timestamp.from(now), dayAgo, template);
        for (var recipient : due) {
            String reference = Long.toString(recipient.reminderId());
            String key = "wechat-reminder-" + reference;
            var job = jobs.submit(WECHAT_KIND, reference, recipient.accountId(), key, hash(key));
            db.update("""
                    INSERT INTO fams_notification_delivery
                        (reminder_id,recipient_account_id,channel,template_code,job_id,grant_id,requested_at)
                    VALUES (?,?,'WECHAT',?,?,?,?)
                    ON DUPLICATE KEY UPDATE reminder_id = fams_notification_delivery.reminder_id
                    """, recipient.reminderId(), recipient.accountId(), template, job.id().toString(),
                    recipient.grantId(), Timestamp.from(clock.instant()));
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

    String eligibleWechatTemplate(DurableJobService.Claim claim) {
        var now = clock.instant();
        var dayAgo = Timestamp.from(now.minusSeconds(86400));
        return db.query("""
                SELECT d.template_code FROM fams_notification_delivery d
                JOIN fams_business_reminder r ON r.id = d.reminder_id
                JOIN fams_system_account a ON a.id = d.recipient_account_id
                JOIN fams_employee e ON e.id = a.employee_id
                JOIN fams_organization o ON o.id = e.organization_id
                JOIN fams_account_role ar ON ar.account_id = a.id AND ar.role_code = r.recipient_pool_type
                WHERE d.job_id = ? AND d.channel = 'WECHAT'
                """ + ELIGIBLE + """
                AND EXISTS (SELECT 1 FROM fams_mini_binding b WHERE b.account_id = a.id AND b.revoked_at IS NULL)
                AND EXISTS (
                    SELECT 1 FROM fams_wechat_subscription_grant g WHERE g.id = d.grant_id
                        AND (g.status = 'AVAILABLE' OR (g.status = 'CONSUMED' AND g.delivery_key = d.job_id))
                )
                """, (row, index) -> row.getString(1), claim.id().toString(), dayAgo, Timestamp.from(now), dayAgo)
                .stream().findFirst().orElse(null);
    }

    record WechatContext(Long grantId, long accountId, String businessType, long businessId, Timestamp deadline) {}

    WechatContext wechatContext(DurableJobService.Claim claim) {
        return db.query("""
                SELECT d.grant_id, d.recipient_account_id, r.business_type, r.business_id, r.deadline_at
                FROM fams_notification_delivery d
                JOIN fams_business_reminder r ON r.id = d.reminder_id
                WHERE d.job_id = ?
                """, (row, index) -> new WechatContext(row.getObject(1, Long.class), row.getLong(2),
                row.getString(3), row.getLong(4), row.getTimestamp(5)), claim.id().toString())
                .stream().findFirst().orElse(null);
    }

    Long activeBinding(long accountId) {
        return db.query("SELECT id FROM fams_mini_binding WHERE account_id = ? AND revoked_at IS NULL ORDER BY id LIMIT 1",
                (row, index) -> row.getLong(1), accountId).stream().findFirst().orElse(null);
    }

    boolean consumeGrant(long grantId, String deliveryKey) {
        var rows = db.query("SELECT status, delivery_key FROM fams_wechat_subscription_grant WHERE id = ?",
                (row, index) -> new String[] {row.getString(1), row.getString(2)}, grantId);
        if (rows.isEmpty()) {
            return false;
        }
        if ("CONSUMED".equals(rows.getFirst()[0])) {
            return deliveryKey.equals(rows.getFirst()[1]);
        }
        return db.update("""
                UPDATE fams_wechat_subscription_grant SET status = 'CONSUMED', consumed_at = ?, delivery_key = ?
                WHERE id = ? AND status = 'AVAILABLE'
                """, Timestamp.from(clock.instant()), deliveryKey, grantId) == 1;
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

    private record WechatRecipient(long reminderId, long accountId, long grantId) {}
}
