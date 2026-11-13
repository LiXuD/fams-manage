package com.fams.reminder;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fams.auth.MiniNotificationSmsPort;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("fams-product")
class ConfigurationAnomalyService {
    private final JdbcTemplate db;
    private final Clock clock;
    private final ObjectProvider<MiniNotificationSmsPort> sms;
    private final String smsTemplate;
    private final String wechatTemplate;

    ConfigurationAnomalyService(JdbcTemplate db, Clock clock, ObjectProvider<MiniNotificationSmsPort> sms,
            @Value("${fams.notification.sms-template:}") String smsTemplate,
            @Value("${fams.notification.wechat-template:}") String wechatTemplate) {
        this.db = db;
        this.clock = clock;
        this.sms = sms;
        this.smsTemplate = smsTemplate;
        this.wechatTemplate = wechatTemplate;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public void scan() {
        Timestamp now = Timestamp.from(clock.instant());
        reconcileRole("ASSET_ADMIN", "NO_ASSET_ADMIN", now);
        reconcileRole("WAREHOUSE_OPERATOR", "NO_WAREHOUSE_OPERATOR", now);
        reconcileChannel("SMS_CHANNEL_MISSING", sms.getIfUnique() == null
                || !smsTemplate.matches("[A-Za-z0-9_-]{1,80}"), now);
        reconcileChannel("WECHAT_TEMPLATE_MISSING", wechatTemplate.isBlank(), now);
    }

    private void reconcileChannel(String type, boolean missing, Timestamp now) {
        String key = type + ":default";
        if (missing) {
            db.update("""
                    INSERT INTO fams_configuration_anomaly (anomaly_type, dedup_key, first_seen_at, last_seen_at)
                    VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE last_seen_at = GREATEST(last_seen_at, ?)
                    """, type, key, now, now, now);
        } else {
            db.update("""
                    UPDATE fams_configuration_anomaly SET resolved_at = GREATEST(last_seen_at, ?)
                    WHERE dedup_key = ? AND active_slot = 1
                    """, now, key);
        }
    }

    private void reconcileRole(String role, String type, Timestamp now) {
        String key = type + ":enterprise";
        db.update("""
                INSERT INTO fams_configuration_anomaly
                    (anomaly_type, dedup_key, first_seen_at, last_seen_at)
                SELECT ?, ?, ?, ? FROM DUAL WHERE NOT EXISTS (
                    SELECT 1 FROM fams_system_account a
                    JOIN fams_employee e ON e.id = a.employee_id
                    JOIN fams_organization o ON o.id = e.organization_id
                    JOIN fams_account_role r ON r.account_id = a.id AND r.role_code = ?
                    WHERE a.status = 'ENABLED' AND NOT a.initial_password_required
                      AND e.status = 'ACTIVE' AND o.status = 'ACTIVE'
                )
                ON DUPLICATE KEY UPDATE last_seen_at = GREATEST(last_seen_at, ?)
                """, type, key, now, now, role, now);
        db.update("""
                INSERT INTO fams_configuration_anomaly_todo
                    (anomaly_id, todo_id, todo_type, parameter_version_id, deadline)
                SELECT x.id, t.id, t.todo_type, r.parameter_version_id,
                    CASE t.todo_type
                        WHEN 'ADMIN_DECISION' THEN r.admin_decision_due_at
                        WHEN 'WAREHOUSE_CLAIM' THEN r.warehouse_claim_due_at
                        WHEN 'WAREHOUSE_EXECUTION' THEN r.warehouse_first_execution_due_at
                    END AS deadline
                FROM fams_configuration_anomaly x
                JOIN fams_todo t ON t.pool_type = ? AND t.active_guard = 1 AND t.business_type = 'INBOUND'
                JOIN fams_inbound_receipt r ON r.id = t.business_id
                WHERE x.dedup_key = ? AND x.active_slot = 1
                HAVING deadline IS NOT NULL
                ON DUPLICATE KEY UPDATE deadline = fams_configuration_anomaly_todo.deadline
                """, role, key);
        db.update("""
                UPDATE fams_configuration_anomaly x SET x.resolved_at = GREATEST(x.last_seen_at, ?)
                WHERE x.dedup_key = ? AND x.active_slot = 1 AND EXISTS (
                    SELECT 1 FROM fams_system_account a
                    JOIN fams_employee e ON e.id = a.employee_id
                    JOIN fams_organization o ON o.id = e.organization_id
                    JOIN fams_account_role r ON r.account_id = a.id AND r.role_code = ?
                    WHERE a.status = 'ENABLED' AND NOT a.initial_password_required
                      AND e.status = 'ACTIVE' AND o.status = 'ACTIVE'
                )
                """, now, key, role);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AnomalyPage list(int page, int pageSize) {
        Long total = db.queryForObject("SELECT COUNT(*) FROM fams_configuration_anomaly", Long.class);
        List<Anomaly> items = read(" ORDER BY id DESC LIMIT ? OFFSET ?", pageSize, (page - 1) * pageSize);
        return new AnomalyPage(page, pageSize, total == null ? 0 : total, items);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Anomaly detail(long id) {
        return read(" WHERE id = ?", id).stream().findFirst().orElse(null);
    }

    private List<Anomaly> read(String suffix, Object... args) {
        return db.query("""
                SELECT id, anomaly_type, first_seen_at, resolved_at, dedup_key
                FROM fams_configuration_anomaly
                """ + suffix, (row, index) -> {
                    long id = row.getLong("id");
                    Timestamp resolved = row.getTimestamp("resolved_at");
                    return new Anomaly(Long.toString(id), row.getString("anomaly_type"),
                            resolved == null ? "OPEN" : "RESOLVED", row.getTimestamp("first_seen_at").toInstant(),
                            resolved == null ? null : resolved.toInstant(), row.getString("dedup_key"), relatedTodos(id));
                }, args);
    }

    private List<TodoReference> relatedTodos(long anomalyId) {
        return db.query("""
                SELECT e.todo_id, e.todo_type, e.parameter_version_id, e.deadline, t.todo_status
                FROM fams_configuration_anomaly_todo e
                LEFT JOIN fams_todo t ON t.id = e.todo_id
                WHERE e.anomaly_id = ? ORDER BY e.todo_id
                """, (row, index) -> new TodoReference(row.getString("todo_id"), row.getString("todo_type"),
                        row.getString("parameter_version_id"), row.getTimestamp("deadline").toInstant(),
                        row.getString("todo_status")), anomalyId);
    }

    record AnomalyPage(int page, int pageSize, long total, List<Anomaly> items) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Anomaly(String id, String type, String status, Instant detectedAt, Instant resolvedAt,
                   String dedupKey, List<TodoReference> relatedTodos) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TodoReference(String id, String kind, String parameterVersionId, Instant deadline, String status) {}
}
