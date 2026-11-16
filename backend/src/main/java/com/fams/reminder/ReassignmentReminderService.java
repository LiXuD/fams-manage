package com.fams.reminder;

import com.fams.masterdata.BusinessCalendar;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Profile("fams-product")
class ReassignmentReminderService {
    // The original acquisition survives later edits. A first handover or an explicit transfer resolves the original timeout.
    private static final String FIRST_EXECUTION = """
            SELECT 'REQUISITION' business_type, request.id business_id, ownership.id claim_id,
                   ownership.acquired_at, request.parameter_snapshot_json
            FROM fams_requisition request JOIN fams_requisition_ownership ownership
              ON ownership.request_id=request.id AND ownership.active_guard=1 AND ownership.ownership_version=0
            WHERE request.request_status IN ('WAREHOUSE_PROCESSING','WAITING_HANDOVER')
              AND NOT EXISTS (
                  SELECT 1 FROM fams_requisition_allocation item
                  JOIN fams_requisition_handover handover ON handover.allocation_id=item.id
                  WHERE item.request_id=request.id
              )
            UNION ALL
            SELECT 'TRANSFER', request.id, todo.id, todo.opened_at,
                   (SELECT item.parameter_snapshot_json FROM fams_transfer_item item
                    WHERE item.request_id=request.id ORDER BY item.id LIMIT 1)
            FROM fams_transfer request JOIN fams_todo todo
              ON todo.business_type='TRANSFER' AND todo.business_id=request.id AND todo.todo_type='EXECUTION'
                AND todo.active_guard=1 AND todo.assignee_account_id=request.processor_account_id
            WHERE request.request_status IN ('PROCESSING','WAITING_HANDOVER') AND request.ownership_version=0
              AND NOT EXISTS (
                  SELECT 1 FROM fams_transfer_event event WHERE event.request_id=request.id
                    AND event.event_type IN ('TRANSFER_DELIVER','TRANSFER_VERIFY')
              )
            UNION ALL
            SELECT 'ASSET_RETURN', returned.id, todo.id, todo.opened_at, returned.parameter_snapshot_json
            FROM fams_asset_return returned JOIN fams_todo todo
              ON todo.business_type='ASSET_RETURN' AND todo.business_id=returned.id AND todo.todo_type='INSPECTION'
                AND todo.pool_type='WAREHOUSE_OPERATOR' AND todo.active_guard=1
                AND todo.assignee_account_id=returned.processor_account_id
            WHERE returned.return_status='WAREHOUSE_PROCESSING' AND returned.ownership_version=0
              AND returned.inspection_result IS NULL
              AND EXISTS (
                  SELECT 1 FROM fams_asset_business_lock held
                  WHERE held.business_type='ASSET_RETURN' AND held.business_id=returned.id
                    AND held.business_item_id=returned.id AND held.asset_id=returned.asset_id
                    AND held.released_at IS NULL
              )
            """;

    // Inbound ownership version starts at zero; the first acceptance or a controlled transfer advances it.
    private static final String SOURCE = "WITH first_execution AS (" + FIRST_EXECUTION + "), " + """
            owned_work AS (
                SELECT t.business_type, t.business_id,
                    COALESCE(t.assignee_account_id, o.processor_account_id) AS owner_id,
                    CASE WHEN o.ownership_version = 0 THEN r.warehouse_first_execution_due_at END AS first_due,
                    (r.id IS NOT NULL AND o.unavailable_ownership_version = o.ownership_version OR EXISTS (
                        SELECT 1
                        FROM fams_transfer x JOIN fams_audit_event declaration
                          ON declaration.resource_type='TRANSFER' AND declaration.resource_id=x.id
                            AND declaration.action='TRANSFER_PROCESSOR_UNAVAILABLE'
                            AND declaration.actor_account_id=x.processor_account_id
                        WHERE t.business_type='TRANSFER' AND t.todo_type='EXECUTION' AND x.id=t.business_id
                            AND x.request_status IN ('PROCESSING','WAITING_HANDOVER')
                            AND x.processor_account_id=t.assignee_account_id
                            AND JSON_UNQUOTE(JSON_EXTRACT(declaration.detail_json,'$.todoId'))=CAST(t.id AS CHAR)
                            AND JSON_EXTRACT(declaration.detail_json,'$.ownershipVersion')=x.ownership_version
                    )) AS declared_unavailable,
                    EXISTS (
                        SELECT 1
                        FROM fams_repair_request repair
                        JOIN fams_repair_factory_round round ON round.repair_id = repair.id
                            AND round.round_status = 'DISPATCHED' AND round.dispatched_at <= t.opened_at
                        JOIN fams_business_reminder notice ON notice.business_type = 'REPAIR_FACTORY_ROUND'
                            AND notice.business_id = round.id AND notice.stage = 'FACTORY_RETURN'
                            AND notice.reminder_type IN ('OVERDUE', 'OVERDUE_EVERY_7_DAYS')
                        WHERE t.business_type = 'REPAIR' AND t.todo_type = 'FACTORY_RETURN'
                            AND repair.id = t.business_id AND repair.repair_status = 'WAREHOUSE_PROCESSING'
                            AND repair.processor_account_id = t.assignee_account_id
                            AND NOT EXISTS (
                                SELECT 1 FROM fams_repair_event event WHERE event.repair_id = repair.id
                                    AND event.action = 'REPAIR_CONTROLLED_REASSIGNMENT'
                                    AND event.occurred_at >= notice.deadline_at
                            )
                    ) AS factory_overdue
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
                   OR t.first_due <= ? OR t.declared_unavailable OR t.factory_overdue
                UNION
                SELECT 'REPAIR', repair.id
                FROM fams_repair_request repair JOIN fams_audit_event declaration
                  ON declaration.resource_type='REPAIR' AND declaration.resource_id=repair.id
                    AND declaration.action='REPAIR_PROCESSOR_UNAVAILABLE'
                    AND declaration.actor_account_id=repair.processor_account_id
                    AND JSON_EXTRACT(declaration.detail_json,'$.ownershipVersion')=repair.ownership_version
                WHERE repair.repair_status='WAREHOUSE_PROCESSING'
                UNION
                SELECT 'REQUISITION', requisition.id
                FROM fams_requisition requisition JOIN fams_requisition_ownership ownership
                  ON ownership.request_id=requisition.id AND ownership.archived_at IS NULL
                JOIN fams_audit_event declaration
                  ON declaration.resource_type='REQUISITION' AND declaration.resource_id=requisition.id
                    AND declaration.action='REQUISITION_PROCESSOR_UNAVAILABLE'
                    AND declaration.actor_account_id=ownership.processor_account_id
                    AND JSON_EXTRACT(declaration.detail_json,'$.ownershipVersion')=ownership.ownership_version
                WHERE requisition.request_status IN ('WAREHOUSE_PROCESSING','WAITING_HANDOVER')
                UNION
                SELECT 'ASSET_RETURN', returned.id
                FROM fams_asset_return returned JOIN fams_audit_event declaration
                  ON declaration.resource_type='ASSET_RETURN' AND declaration.resource_id=returned.id
                    AND declaration.action='RETURN_PROCESSOR_UNAVAILABLE'
                    AND declaration.actor_account_id=returned.processor_account_id
                    AND JSON_EXTRACT(declaration.detail_json,'$.ownershipVersion')=returned.ownership_version
                WHERE returned.return_status='WAREHOUSE_PROCESSING'
                UNION
                SELECT pending_claim.business_type, pending_claim.business_id
                FROM first_execution pending_claim JOIN JSON_TABLE(?, '$[*]' COLUMNS(
                    business_type VARCHAR(32) PATH '$.businessType',
                    business_id BIGINT PATH '$.businessId',
                    claim_id BIGINT PATH '$.claimId'
                )) expired ON expired.business_type=pending_claim.business_type
                    AND expired.business_id=pending_claim.business_id AND expired.claim_id=pending_claim.claim_id
            )
            """;

    private final JdbcTemplate db;
    private final Clock clock;
    private final ObjectMapper json;

    ReassignmentReminderService(JdbcTemplate db, Clock clock, ObjectMapper json) {
        this.db = db;
        this.clock = clock;
        this.json = json;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public void scan() {
        Timestamp now = Timestamp.from(clock.instant());
        String expired = expiredClaims(now.toInstant());
        db.update("""
                INSERT INTO fams_todo (pool_type, business_type, business_id, todo_type, todo_status, opened_at)
                """ + SOURCE + """
                SELECT 'ASSET_ADMIN', work.business_type, work.business_id, 'REASSIGNMENT_REQUIRED', 'OPEN', ?
                FROM affected_work work
                ORDER BY work.business_type, work.business_id
                ON DUPLICATE KEY UPDATE id = fams_todo.id
                """, now, expired, now);
        db.update(SOURCE + """
                UPDATE fams_todo t
                LEFT JOIN affected_work work ON work.business_type = t.business_type AND work.business_id = t.business_id
                SET t.todo_status = 'CLOSED', t.closed_at = ?
                WHERE t.todo_type = 'REASSIGNMENT_REQUIRED' AND t.active_guard = 1 AND work.business_id IS NULL
                """, now, expired, now);
    }

    private String expiredClaims(Instant now) {
        var candidates = db.queryForList(FIRST_EXECUTION);
        var expired = candidates.stream().filter(row -> firstExecutionExpired(row, now))
                .map(row -> Map.of("businessType", row.get("business_type"), "businessId", row.get("business_id"),
                        "claimId", row.get("claim_id"))).toList();
        return json.writeValueAsString(expired);
    }

    private boolean firstExecutionExpired(Map<String, Object> row, Instant now) {
        if (row.get("parameter_snapshot_json") == null) { return false; }
        var snapshot = json.readTree(row.get("parameter_snapshot_json").toString());
        int workdays = snapshot.path("warehouseFirstExecutionDeadlineWorkdays").asInt(2);
        var days = snapshot.path("workWeek");
        if (days.isMissingNode() && snapshot instanceof tools.jackson.databind.node.ObjectNode object) {
            object.set("workWeek", json.valueToTree(List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY")));
            days = object.path("workWeek");
        }
        if (workdays < 1 || workdays > 30 || !days.isArray()
                || days.valueStream().noneMatch(day -> java.util.Arrays.stream(java.time.DayOfWeek.values())
                    .anyMatch(weekday -> weekday.name().equals(day.asText())))) {
            return false;
        }
        var acquired = row.get("acquired_at");
        Instant origin = acquired instanceof Timestamp timestamp
                ? timestamp.toInstant() : ((LocalDateTime) acquired).toInstant(ZoneOffset.UTC);
        var zone = ZoneId.of(snapshot.path("timezone").asText("Asia/Shanghai"));
        return !now.isBefore(BusinessCalendar.workdayDeadline(snapshot, zone, origin, workdays));
    }
}
