package com.fams.reminder;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fams.auth.MiniNotificationSmsPort;
import com.fams.masterdata.NotificationChannelConfigPort;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
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
    private final ObjectProvider<WechatSubscriptionPort> wechat;
    private final NotificationChannelConfigPort channels;

    ConfigurationAnomalyService(JdbcTemplate db, Clock clock, ObjectProvider<MiniNotificationSmsPort> sms,
            ObjectProvider<WechatSubscriptionPort> wechat,
            NotificationChannelConfigPort channels) {
        this.db = db;
        this.clock = clock;
        this.sms = sms;
        this.wechat = wechat;
        this.channels = channels;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public void scan() {
        Timestamp now = Timestamp.from(clock.instant());
        var templates = channels.currentTemplates();
        reconcileRole("ASSET_ADMIN", "NO_ASSET_ADMIN", now);
        reconcileRole("WAREHOUSE_OPERATOR", "NO_WAREHOUSE_OPERATOR", now);
        reconcileChannel("SMS_CHANNEL_MISSING", sms.getIfUnique() == null
                || !templates.smsTemplate().matches("[A-Za-z0-9_-]{1,80}"), now);
        reconcileChannel("WECHAT_TEMPLATE_MISSING", wechat.getIfUnique() == null
                || templates.wechatTemplate().isBlank(), now);
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
                INSERT INTO fams_configuration_anomaly_business_todo
                    (anomaly_id, todo_id, todo_type, business_type, business_id, parameter_version_id)
                SELECT x.id, t.id, t.todo_type, t.business_type, t.business_id,
                    COALESCE(r.parameter_version_id, q.parameter_version_id, i.parameter_version_id)
                FROM fams_configuration_anomaly x
                JOIN fams_todo t ON t.todo_status = 'OPEN' AND t.active_guard = 1
                  AND (t.pool_type = ? OR (? = 'WAREHOUSE_OPERATOR' AND t.pool_type = 'WAREHOUSE'
                    AND t.business_type = 'REQUISITION_ALLOCATION' AND t.todo_type = 'HANDOVER_EXCEPTION'))
                LEFT JOIN fams_inbound_receipt r ON t.business_type = 'INBOUND' AND r.id = t.business_id
                LEFT JOIN fams_requisition_allocation a ON t.business_type = 'REQUISITION_ALLOCATION'
                  AND a.id = t.business_id
                LEFT JOIN fams_requisition q ON q.id = CASE WHEN t.business_type = 'REQUISITION'
                  THEN t.business_id ELSE a.request_id END
                LEFT JOIN fams_inventory_observation o ON t.business_type = 'INVENTORY_OBSERVATION'
                  AND o.id = t.business_id
                LEFT JOIN fams_inventory_task i ON i.id = CASE WHEN t.business_type = 'INVENTORY_TASK'
                  THEN t.business_id ELSE o.task_id END
                LEFT JOIN fams_configuration_anomaly_todo e ON e.anomaly_id = x.id AND e.todo_id = t.id
                WHERE x.dedup_key = ? AND x.active_slot = 1 AND e.todo_id IS NULL
                ON DUPLICATE KEY UPDATE todo_id = fams_configuration_anomaly_business_todo.todo_id
                """, role, role, key);
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
                            resolved == null ? null : resolved.toInstant(), row.getString("dedup_key"),
                            relatedTodos(id), relatedBusinessTodos(id));
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

    private List<BusinessTodoReference> relatedBusinessTodos(long anomalyId) {
        return db.query("""
                SELECT e.todo_id, e.todo_type, e.business_type, e.business_id, e.parameter_version_id, t.todo_status
                FROM fams_configuration_anomaly_business_todo e
                LEFT JOIN fams_todo t ON t.id = e.todo_id
                LEFT JOIN fams_configuration_anomaly_todo timed ON timed.anomaly_id = e.anomaly_id
                  AND timed.todo_id = e.todo_id
                WHERE e.anomaly_id = ? AND timed.todo_id IS NULL ORDER BY e.todo_id
                """, (row, index) -> new BusinessTodoReference(row.getString("todo_id"), row.getString("todo_type"),
                        row.getString("business_type"), row.getString("business_id"),
                        row.getString("parameter_version_id"), row.getString("todo_status")), anomalyId);
    }

    record AnomalyPage(int page, int pageSize, long total, List<Anomaly> items) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Anomaly(String id, String type, String status, Instant detectedAt, Instant resolvedAt,
                   String dedupKey, List<TodoReference> relatedTodos,
                   @JsonInclude(JsonInclude.Include.NON_EMPTY) List<BusinessTodoReference> relatedBusinessTodos) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TodoReference(String id, String kind, String parameterVersionId, Instant deadline, String status) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BusinessTodoReference(String id, String kind, String businessType, String businessId,
                                 String parameterVersionId, String status) {}
}
