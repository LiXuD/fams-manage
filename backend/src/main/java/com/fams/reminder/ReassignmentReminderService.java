package com.fams.reminder;

import java.sql.Timestamp;
import java.time.Clock;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("fams-product")
class ReassignmentReminderService {
    // Inbound ownership version starts at zero; the first acceptance or a controlled transfer advances it.
    private static final String SOURCE = """
            WITH owned_work AS (
                SELECT t.business_type, t.business_id,
                    COALESCE(t.assignee_account_id, o.processor_account_id) AS owner_id,
                    CASE WHEN o.ownership_version = 0 THEN r.warehouse_first_execution_due_at END AS first_due
                FROM fams_todo t
                LEFT JOIN fams_inbound_processing_ownership o
                    ON t.business_type = 'INBOUND' AND t.todo_type = 'WAREHOUSE_EXECUTION'
                    AND o.receipt_id = t.business_id AND o.active_guard = 1
                LEFT JOIN fams_inbound_receipt r
                    ON r.id = o.receipt_id AND r.receipt_status = 'WAREHOUSE_PROCESSING'
                WHERE t.pool_type = 'WAREHOUSE_OPERATOR' AND t.active_guard = 1 AND t.todo_status = 'OPEN'
            ), affected_work AS (
                SELECT DISTINCT t.business_type, t.business_id
                FROM owned_work t
                JOIN fams_system_account a ON a.id = t.owner_id
                JOIN fams_employee e ON e.id = a.employee_id
                JOIN fams_organization g ON g.id = e.organization_id
                LEFT JOIN fams_account_role r ON r.account_id = a.id AND r.role_code = 'WAREHOUSE_OPERATOR'
                WHERE a.status = 'DISABLED' OR e.status <> 'ACTIVE' OR g.status <> 'ACTIVE' OR r.account_id IS NULL
                   OR t.first_due <= ?
            )
            """;

    private final JdbcTemplate db;
    private final Clock clock;

    ReassignmentReminderService(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public void scan() {
        Timestamp now = Timestamp.from(clock.instant());
        db.update("""
                INSERT INTO fams_todo (pool_type, business_type, business_id, todo_type, todo_status, opened_at)
                """ + SOURCE + """
                SELECT 'ASSET_ADMIN', work.business_type, work.business_id, 'REASSIGNMENT_REQUIRED', 'OPEN', ?
                FROM affected_work work
                ORDER BY work.business_type, work.business_id
                ON DUPLICATE KEY UPDATE id = fams_todo.id
                """, now, now);
        db.update(SOURCE + """
                UPDATE fams_todo t
                LEFT JOIN affected_work work ON work.business_type = t.business_type AND work.business_id = t.business_id
                SET t.todo_status = 'CLOSED', t.closed_at = ?
                WHERE t.todo_type = 'REASSIGNMENT_REQUIRED' AND t.active_guard = 1 AND work.business_id IS NULL
                """, now, now);
    }
}
