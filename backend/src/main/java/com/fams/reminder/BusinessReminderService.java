package com.fams.reminder;

import com.fams.masterdata.BusinessCalendar;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * In-app notice ladder for stages that have no owner-specific business todo. One row per object, stage,
 * frozen deadline, tier, occurrence date and recipient, so a repeated or restarted scan never notifies twice.
 */
@Service
@Profile("fams-product")
public class BusinessReminderService {
    private static final int PAGE = 200;
    private static final ZoneId FALLBACK_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long DAY_SECONDS = 86_400L;

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;

    BusinessReminderService(JdbcTemplate db, ObjectMapper json, Clock clock) {
        this.db = db;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void scan() {
        var zone = activeZone();
        var today = clock.instant().atZone(zone).toLocalDate();
        inbound(today);
        receipts(today);
        transfers(today);
        inventory(today, zone);
        factoryReturns(today, zone);
        lossReviews(today, zone);
        dispositions(today, zone);
    }

    private void inbound(LocalDate today) {
        each("""
             SELECT x.id,x.receipt_status,x.timezone_snapshot,x.submitted_at,x.admin_decision_due_at,
                    x.warehouse_claim_due_at,x.warehouse_first_execution_due_at,v.snapshot_json
               FROM fams_inbound_receipt x JOIN fams_business_parameter_version v ON v.id=x.parameter_version_id
              WHERE x.receipt_status IN ('PENDING_ADMIN_DECISION','WAITING_WAREHOUSE_CLAIM','WAREHOUSE_PROCESSING')""",
                row -> {
                    var status = String.valueOf(row.get("receipt_status"));
                    var zone = ZoneId.of(String.valueOf(row.get("timezone_snapshot")));
                    var field = switch (status) {
                        case "PENDING_ADMIN_DECISION" -> "admin_decision_due_at";
                        case "WAITING_WAREHOUSE_CLAIM" -> "warehouse_claim_due_at";
                        default -> "warehouse_first_execution_due_at";
                    };
                    var deadline = instant(row.get(field));
                    var due = deadline.atZone(zone).toLocalDate();
                    switch (status) {
                        // One workday without an administrator decision is already worth a notice, because
                        // the decision deadline itself may still be several workdays away.
                        case "PENDING_ADMIN_DECISION" -> record("INBOUND", number(row, "id"), "ADMIN_DECISION",
                                due, deadline, zone, "ASSET_ADMIN", null, today, List.of(
                                        new Tier("UNRESOLVED_AFTER_1_WORKDAY", BusinessCalendar.nthWorkday(
                                                json.readTree(String.valueOf(row.get("snapshot_json"))), zone,
                                                instant(row.get("submitted_at")), 1)),
                                        new Tier("DUE_TODAY", due), new Tier("OVERDUE", due.plusDays(1))));
                        case "WAITING_WAREHOUSE_CLAIM" -> record("INBOUND", number(row, "id"), "WAREHOUSE_CLAIM",
                                due, deadline, zone, "WAREHOUSE_OPERATOR", null, today, List.of(
                                        new Tier("DUE_IN_1_DAY", due.minusDays(1)),
                                        new Tier("OVERDUE", due.plusDays(1))));
                        default -> record("INBOUND", number(row, "id"), "FIRST_EXECUTION", due, deadline, zone,
                                "WAREHOUSE_OPERATOR", null, today, List.of(new Tier("OVERDUE", due.plusDays(1))));
                    }
                });
    }

    private void receipts(LocalDate today) {
        each("""
             SELECT x.id,h.receipt_deadline_at,h.temporary_employee_id AS employee_id,
                    q.parameter_snapshot_json AS snapshot_json
               FROM fams_requisition_allocation x
               JOIN fams_requisition_handover h ON h.allocation_id=x.id
               JOIN fams_requisition q ON q.id=x.request_id
              WHERE x.allocation_status='DELIVERED_WAITING_RECEIPT'""",
                row -> receipt(row, "REQUISITION_ALLOCATION", today));
    }

    private void transfers(LocalDate today) {
        each("""
             SELECT x.id,x.receipt_deadline_at,x.receiver_employee_id AS employee_id,x.parameter_snapshot_json
               FROM fams_transfer_item x WHERE x.item_status='DELIVERED'""",
                row -> receipt(row, "TRANSFER_ITEM", today));
    }

    private void receipt(Map<String, Object> row, String businessType, LocalDate today) {
        var deadline = instant(row.get("receipt_deadline_at"));
        var zone = zone(row.get("snapshot_json"));
        var due = deadline.atZone(zone).toLocalDate();
        Long recipient = accountFor(row.get("employee_id"));
        record(businessType, number(row, "id"), "HANDOVER_RECEIPT", due, deadline, zone,
                recipient == null ? "ASSET_ADMIN" : "EMPLOYEE", recipient, today, List.of(
                        new Tier("DUE_IN_24_HOURS", Instant.ofEpochSecond(deadline.getEpochSecond() - DAY_SECONDS)
                                .atZone(zone).toLocalDate()),
                        new Tier("DUE_TODAY", due), new Tier("OVERDUE", due.plusDays(1))));
    }

    private void inventory(LocalDate today, ZoneId fallback) {
        each("SELECT x.id,x.due_at,x.timezone_id FROM fams_inventory_task x WHERE x.task_status='IN_PROGRESS'",
                row -> {
                    var zone = row.get("timezone_id") == null ? fallback : ZoneId.of(String.valueOf(row.get("timezone_id")));
                    var deadline = instant(row.get("due_at"));
                    var due = deadline.atZone(zone).toLocalDate();
                    var tiers = List.of(new Tier("DUE_IN_3_DAYS", due.minusDays(3)),
                            new Tier("DUE_IN_1_DAY", due.minusDays(1)), new Tier("DUE_TODAY", due),
                            new Tier("OVERDUE", due.plusDays(1)));
                    var executors = db.queryForList("SELECT DISTINCT executor_account_id FROM fams_inventory_item"
                            + " WHERE task_id=? AND executor_account_id IS NOT NULL", Long.class, row.get("id"));
                    if (executors.isEmpty()) {
                        record("INVENTORY_TASK", number(row, "id"), "INVENTORY_COMPLETION", due, deadline, zone,
                                "ASSET_ADMIN", null, today, tiers);
                    } else {
                        for (long executor : executors) {
                            record("INVENTORY_TASK", number(row, "id"), "INVENTORY_COMPLETION", due, deadline, zone,
                                    "EMPLOYEE", executor, today, tiers);
                        }
                    }
                });
    }

    private void factoryReturns(LocalDate today, ZoneId zone) {
        each("SELECT x.id,x.expected_return_date FROM fams_repair_factory_round x WHERE x.round_status='DISPATCHED'",
                row -> {
                    var due = LocalDate.parse(String.valueOf(row.get("expected_return_date")));
                    record("REPAIR_FACTORY_ROUND", number(row, "id"), "FACTORY_RETURN", due, endOfDay(due, zone), zone,
                            "WAREHOUSE_OPERATOR", null, today, List.of(
                                    new Tier("DUE_IN_3_DAYS", due.minusDays(3)), new Tier("DUE_TODAY", due),
                                    // Deriving the occurrence from the due date keeps a restarted scan on the same
                                    // weekly bucket instead of emitting a fresh notice every run.
                                    new Tier("OVERDUE_EVERY_7_DAYS", due.plusDays(7L * Math.max(1,
                                            (today.toEpochDay() - due.toEpochDay()) / 7)))));
                });
    }

    private void lossReviews(LocalDate today, ZoneId zone) {
        each("""
             SELECT x.id,x.next_review_date,x.processor_account_id FROM fams_loss_report x
              WHERE x.report_status IN ('SEARCHING','CONFIRMED_LOST') AND x.next_review_date IS NOT NULL""",
                row -> {
                    var due = LocalDate.parse(String.valueOf(row.get("next_review_date")));
                    Long processor = row.get("processor_account_id") == null
                            ? null : ((Number) row.get("processor_account_id")).longValue();
                    record("LOST_REPORT", number(row, "id"), "LOSS_REVIEW", due, endOfDay(due, zone), zone,
                            processor == null ? "ASSET_ADMIN" : "WAREHOUSE_OPERATOR", processor, today,
                            List.of(new Tier("DUE_IN_1_DAY", due.minusDays(1)), new Tier("OVERDUE", due.plusDays(1))));
                });
    }

    private void dispositions(LocalDate today, ZoneId zone) {
        each("""
             SELECT x.id,d.due_date,d.processor_account_id FROM fams_scrap_disposition d
               JOIN fams_scrap_request x ON x.id=d.request_id
              WHERE d.disposition_status='PENDING_DISPOSITION' AND x.request_status='EXECUTED'""",
                row -> {
                    var due = LocalDate.parse(String.valueOf(row.get("due_date")));
                    record("SCRAP", number(row, "id"), "SCRAP_DISPOSITION", due, endOfDay(due, zone), zone,
                            "WAREHOUSE_OPERATOR", ((Number) row.get("processor_account_id")).longValue(), today,
                            List.of(new Tier("DUE_IN_3_DAYS", due.minusDays(3)), new Tier("DUE_TODAY", due),
                                    new Tier("OVERDUE", due.plusDays(1))));
                });
    }

    private void record(String businessType, long businessId, String stage, LocalDate due, Instant deadlineAt,
            ZoneId zone, String pool, Long recipient, LocalDate today, List<Tier> tiers) {
        var created = Timestamp.from(clock.instant());
        for (var tier : tiers) {
            if (today.isBefore(tier.noticeDate())) {
                continue;
            }
            db.update("INSERT INTO fams_business_reminder (business_type,business_id,stage,deadline_at,notice_date,"
                    + "reminder_business_date,reminder_type,recipient_pool_type,recipient_account_id,created_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id", businessType, businessId, stage,
                    Timestamp.from(deadlineAt), tier.noticeDate(), today, tier.type(), pool, recipient, created);
        }
    }

    private void each(String sql, Consumer<Map<String, Object>> row) {
        long after = 0;
        while (true) {
            var rows = db.queryForList(sql + " AND x.id>? ORDER BY x.id LIMIT " + PAGE, after);
            if (rows.isEmpty()) {
                return;
            }
            rows.forEach(row);
            after = number(rows.getLast(), "id");
        }
    }

    private ZoneId activeZone() {
        var rows = db.queryForList("SELECT snapshot_json FROM fams_business_parameter_version WHERE status='ACTIVE'"
                + " ORDER BY version DESC LIMIT 1", String.class);
        return rows.isEmpty() ? FALLBACK_ZONE : zone(rows.getFirst());
    }

    private ZoneId zone(Object snapshotJson) {
        if (snapshotJson == null) {
            return FALLBACK_ZONE;
        }
        JsonNode snapshot = json.readTree(String.valueOf(snapshotJson));
        var timezone = snapshot.path("timezone").asText("");
        return timezone.isBlank() ? FALLBACK_ZONE : ZoneId.of(timezone);
    }

    private Instant endOfDay(LocalDate date, ZoneId zone) {
        return date.plusDays(1).atStartOfDay(zone).toInstant();
    }

    private Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        // MySQL DATETIME columns are read back as UTC wall-clock by the driver's LocalDateTime mapping.
        return value == null ? null
                : ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
    }

    private Long accountFor(Object employeeId) {
        if (employeeId == null) {
            return null;
        }
        var accounts = db.queryForList("SELECT id FROM fams_system_account WHERE employee_id=?"
                + " AND status='ENABLED'", Long.class, employeeId);
        return accounts.isEmpty() ? null : accounts.getFirst();
    }

    private static long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    private record Tier(String type, LocalDate noticeDate) { }
}
