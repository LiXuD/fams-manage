package com.fams.reminder;

import com.fams.masterdata.BusinessCalendar;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes the four notification escalation thresholds for the first time. A ladder notice that stays unread past
 * its threshold is escalated along the duty chain: the effective duty person, otherwise the backup, otherwise every
 * remaining effective member of the pool. An object still unhandled one further workday after its frozen deadline
 * additionally produces a group notice to every effective administrator, read or not. Both tiers reuse the V65
 * unique key, so a repeated scan within one day cannot notify the same person twice for the same deadline.
 */
@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.READ_COMMITTED)
public class DutyEscalationService {
    private static final int PAGE = 200;
    private static final ZoneId FALLBACK_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String UNREAD = " SELECT 1 FROM fams_message_receipt c WHERE c.account_id=?"
            + " AND c.message_kind='REMINDER' AND c.message_id=?";
    private static final String EFFECTIVE_MEMBER =
            " SELECT a.id FROM fams_system_account a"
            + " JOIN fams_employee e ON e.id=a.employee_id"
            + " JOIN fams_organization o ON o.id=e.organization_id"
            + " JOIN fams_account_role r ON r.account_id=a.id AND r.role_code=?"
            + " WHERE a.status='ENABLED' AND a.initial_password_required=FALSE"
            + " AND e.status='ACTIVE' AND o.status='ACTIVE' ORDER BY a.id";

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;

    DutyEscalationService(JdbcTemplate db, ObjectMapper json, Clock clock) {
        this.db = db;
        this.json = json;
        this.clock = clock;
    }

    public void escalate() {
        JsonNode active = activeSnapshot();
        if (active == null) {
            return;
        }
        var thresholds = active.path("notificationEscalationThresholds");
        var now = clock.instant();
        var today = now.atZone(zone(active)).toLocalDate();
        var stages = List.of(
                new Stage("INBOUND", "ADMIN_DECISION", "ASSET_ADMIN",
                        thresholds.path("administratorUnreadHours").asInt(0), staged("""
                         SELECT r.id notice_id,r.created_at notice_at,x.id business_id,
                                x.admin_decision_due_at deadline_at,v.snapshot_json
                           FROM fams_inbound_receipt x
                           JOIN fams_business_parameter_version v ON v.id=x.parameter_version_id
                           JOIN fams_business_reminder r ON r.business_type='INBOUND' AND r.business_id=x.id
                                AND r.stage='ADMIN_DECISION' AND r.reminder_type='OVERDUE'
                                AND r.deadline_at=x.admin_decision_due_at
                          WHERE x.receipt_status='PENDING_ADMIN_DECISION'""")),
                new Stage("INBOUND", "WAREHOUSE_CLAIM", "WAREHOUSE_OPERATOR",
                        thresholds.path("onDutyWarehouseOperatorUnreadHours").asInt(0), staged("""
                         SELECT r.id notice_id,r.created_at notice_at,x.id business_id,
                                x.warehouse_claim_due_at deadline_at,v.snapshot_json
                           FROM fams_inbound_receipt x
                           JOIN fams_business_parameter_version v ON v.id=x.parameter_version_id
                           JOIN fams_business_reminder r ON r.business_type='INBOUND' AND r.business_id=x.id
                                AND r.stage='WAREHOUSE_CLAIM' AND r.reminder_type='OVERDUE'
                                AND r.deadline_at=x.warehouse_claim_due_at
                          WHERE x.receipt_status='WAITING_WAREHOUSE_CLAIM'""")),
                new Stage("INBOUND", "FIRST_EXECUTION", "WAREHOUSE_OPERATOR",
                        thresholds.path("firstExecutionOverdueNaturalDays").asInt(0), staged("""
                         SELECT r.id notice_id,r.created_at notice_at,x.id business_id,
                                x.warehouse_first_execution_due_at deadline_at,v.snapshot_json
                           FROM fams_inbound_receipt x
                           JOIN fams_business_parameter_version v ON v.id=x.parameter_version_id
                           JOIN fams_business_reminder r ON r.business_type='INBOUND' AND r.business_id=x.id
                                AND r.stage='FIRST_EXECUTION' AND r.reminder_type='OVERDUE'
                                AND r.deadline_at=x.warehouse_first_execution_due_at
                          WHERE x.receipt_status='WAREHOUSE_PROCESSING'""")),
                // The receipt ladder is addressed to the receiving employee, who holds no duty role, so both the
                // unread escalation and the group notice answer to the administrators responsible for the handover.
                new Stage("REQUISITION_ALLOCATION", "HANDOVER_RECEIPT", "ASSET_ADMIN",
                        thresholds.path("receiptDueUnreadHours").asInt(0), staged("""
                         SELECT r.id notice_id,r.created_at notice_at,x.id business_id,
                                h.receipt_deadline_at deadline_at,q.parameter_snapshot_json snapshot_json
                           FROM fams_requisition_allocation x
                           JOIN fams_requisition_handover h ON h.allocation_id=x.id
                           JOIN fams_requisition q ON q.id=x.request_id
                           JOIN fams_business_reminder r ON r.business_type='REQUISITION_ALLOCATION'
                                AND r.business_id=x.id AND r.stage='HANDOVER_RECEIPT'
                                AND r.reminder_type='OVERDUE' AND r.deadline_at=h.receipt_deadline_at
                          WHERE x.allocation_status='DELIVERED_WAITING_RECEIPT'""")),
                new Stage("TRANSFER_ITEM", "HANDOVER_RECEIPT", "ASSET_ADMIN",
                        thresholds.path("receiptDueUnreadHours").asInt(0), staged("""
                         SELECT r.id notice_id,r.created_at notice_at,x.id business_id,
                                x.receipt_deadline_at deadline_at,x.parameter_snapshot_json snapshot_json
                           FROM fams_transfer_item x
                           JOIN fams_business_reminder r ON r.business_type='TRANSFER_ITEM'
                                AND r.business_id=x.id AND r.stage='HANDOVER_RECEIPT'
                                AND r.reminder_type='OVERDUE' AND r.deadline_at=x.receipt_deadline_at
                          WHERE x.item_status='DELIVERED'""")));

        for (var stage : stages) {
            escalateUnread(stage, today, now);
        }
        var administrators = db.queryForList(EFFECTIVE_MEMBER, Long.class, "ASSET_ADMIN");
        for (var stage : stages) {
            escalateGroup(stage, administrators, today);
        }
    }

    /**
     * The unread clock starts when the ladder wrote the overdue notice, because that is the message the duty chain
     * is expected to open. The first-execution threshold is the exception: it counts whole natural days past the due
     * date, matching the product's "overdue one day and unread" wording.
     */
    private void escalateUnread(Stage stage, LocalDate today, Instant now) {
        if (stage.threshold() <= 0) {
            return;
        }
        for (var entry : stage.cases()) {
            var ready = "FIRST_EXECUTION".equals(stage.stage())
                    ? !today.isBefore(entry.dueDate().plusDays(stage.threshold()))
                    : !now.isBefore(entry.noticeAt().plusMillis(stage.threshold() * 3_600_000L));
            if (!ready) {
                continue;
            }
            for (var target : targets(stage.pool())) {
                if (db.queryForList("SELECT" + UNREAD, Long.class, target, entry.noticeId()).isEmpty()) {
                    record(stage.businessType(), entry.businessId(), stage.stage(), stage.pool(),
                            "DUTY_UNREAD_ESCALATION", entry.deadlineAt(), target, today);
                }
            }
        }
    }

    private void escalateGroup(Stage stage, List<Long> administrators, LocalDate today) {
        if (administrators.isEmpty()) {
            return;
        }
        for (var entry : stage.cases()) {
            if (today.isBefore(BusinessCalendar.nthWorkday(entry.snapshot(), entry.zone(), entry.deadlineAt(), 2))
                    || "INBOUND".equals(stage.businessType()) && reassigned(entry)) {
                continue;
            }
            for (var administrator : administrators) {
                record(stage.businessType(), entry.businessId(), stage.stage(), "ASSET_ADMIN",
                        "OVERDUE_UNRESOLVED_WORKDAY", entry.deadlineAt(), administrator, today);
            }
        }
    }

    /**
     * A controlled reassignment rewrites the warehouse processor together with its audit, which is the second of
     * the three recognised facts; the receipt stages have no reassignment path for the receiving employee, so the
     * check only applies to inbound.
     */
    private boolean reassigned(Case entry) {
        return !db.queryForList(" SELECT 1 FROM fams_audit_event ae WHERE ae.resource_type='INBOUND'"
                + " AND ae.resource_id=? AND ae.action='INBOUND_CONTROLLED_REASSIGNMENT'"
                + " AND ae.occurred_at>?", entry.businessId(), Timestamp.from(entry.noticeAt())).isEmpty();
    }

    private void record(String businessType, long businessId, String stage, String pool, String type,
            Instant deadlineAt, long recipient, LocalDate today) {
        db.update("INSERT INTO fams_business_reminder (business_type,business_id,stage,deadline_at,notice_date,"
                + "reminder_business_date,reminder_type,recipient_pool_type,recipient_account_id,created_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id", businessType, businessId, stage,
                Timestamp.from(deadlineAt), today, today, type, pool, recipient, Timestamp.from(clock.instant()));
    }

    /**
     * Without a configured duty row the whole pool is accountable, because the product does not force two duty
     * contacts to exist; an ineffective duty person falls to the backup, and both failing falls to the remaining
     * effective members.
     */
    private List<Long> targets(String pool) {
        var effective = db.queryForList(EFFECTIVE_MEMBER, Long.class, pool);
        var duty = db.queryForList(" SELECT primary_account_id,backup_account_id FROM fams_duty_assignment"
                + " WHERE role_code=? AND duty_status='ACTIVE'", Map.class, pool);
        if (duty.isEmpty()) {
            return effective;
        }
        var primary = number(duty.getFirst().get("primary_account_id"));
        if (effective.contains(primary)) {
            return List.of(primary);
        }
        var backup = duty.getFirst().get("backup_account_id");
        var backupId = backup == null ? 0L : number(backup);
        return backupId != 0 && effective.contains(backupId) ? List.of(backupId) : effective;
    }

    private List<Case> staged(String select) {
        var cases = new ArrayList<Case>();
        long after = 0;
        while (true) {
            var rows = db.queryForList(select + " AND x.id>? ORDER BY x.id LIMIT " + PAGE, after);
            if (rows.isEmpty()) {
                return List.copyOf(cases);
            }
            for (var row : rows) {
                cases.add(new Case(number(row.get("notice_id")), number(row.get("business_id")),
                        instant(row.get("deadline_at")), instant(row.get("notice_at")),
                        json.readTree(String.valueOf(row.get("snapshot_json")))));
            }
            after = number(rows.getLast().get("business_id"));
        }
    }

    private JsonNode activeSnapshot() {
        var rows = db.queryForList(" SELECT snapshot_json FROM fams_business_parameter_version"
                + " WHERE status='ACTIVE' ORDER BY version DESC LIMIT 1", String.class);
        return rows.isEmpty() ? null : json.readTree(rows.getFirst());
    }

    private static ZoneId zone(JsonNode snapshot) {
        var timezone = snapshot.path("timezone").asText("");
        return timezone.isBlank() ? FALLBACK_ZONE : ZoneId.of(timezone);
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        // MySQL DATETIME columns are read back as UTC wall-clock by the driver's LocalDateTime mapping.
        return value == null ? null : ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private record Stage(String businessType, String stage, String pool, int threshold, List<Case> cases) { }

    private record Case(long noticeId, long businessId, Instant deadlineAt, Instant noticeAt, JsonNode snapshot) {
        private LocalDate dueDate() {
            return deadlineAt.atZone(zone()).toLocalDate();
        }

        private ZoneId zone() {
            var timezone = snapshot.path("timezone").asText("");
            return timezone.isBlank() ? FALLBACK_ZONE : ZoneId.of(timezone);
        }
    }
}
