package com.fams.reminder;

import com.fams.auth.RequisitionActorContext;
import com.fams.auth.Role;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app message centre over the two authoritative sources: business todos and the reminder ladder. Read
 * state is stored per person, so opening a shared message neither resolves the work item nor marks it read
 * for anybody else, and no external channel is involved.
 */
@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.READ_COMMITTED)
public class MessageService {
    private static final String ESCALATION_TYPES = "('DUTY_UNREAD_ESCALATION','OVERDUE_UNRESOLVED_WORKDAY')";
    /** The group notice for a still-unhandled object belongs to the overdue exception vocabulary; a duty
     * escalation is a personal notice and stays out of it. */
    private static final String OVERDUE_TYPES = "('OVERDUE','OVERDUE_EVERY_7_DAYS','UNRESOLVED_AFTER_1_WORKDAY',"
            + "'OVERDUE_UNRESOLVED_WORKDAY')";
    private static final String TODO_COLUMNS =
            " SELECT 'TODO' message_kind,t.id id,"
            + " IF(o.id IS NULL,t.business_type,'INVENTORY_TASK') business_type,"
            + " COALESCE(o.task_id,t.business_id) business_id,t.todo_type stage,"
            + " '' object_stage,t.pool_type,t.assignee_account_id,t.opened_at moment,"
            + " CAST(NULL AS DATETIME) deadline_at FROM fams_todo t"
            + " LEFT JOIN fams_inventory_observation o ON t.business_type='INVENTORY_OBSERVATION'"
            + " AND o.id=t.business_id WHERE t.todo_status='OPEN' AND";
    private static final String EXCEPTION_TODOS = TODO_COLUMNS
            + " (t.todo_type IN" + OVERDUE_TYPES + " OR t.todo_type IN"
            + " ('REASSIGNMENT_REQUIRED','INVENTORY_CONFLICT','INVENTORY_DIFFERENCE'))";
    private static final String ACTIVE_FACTORY_RETURN = """
            (r.business_type<>'REPAIR_FACTORY_ROUND' OR r.stage<>'FACTORY_RETURN' OR EXISTS (
                SELECT 1 FROM fams_repair_factory_round f JOIN fams_todo t
                    ON t.business_type='REPAIR' AND t.business_id=f.repair_id AND t.todo_type='FACTORY_RETURN'
                WHERE f.id=r.business_id AND f.round_status='DISPATCHED' AND t.todo_status='OPEN'
                    AND t.closed_at IS NULL AND t.opened_at>=f.dispatched_at
            ))
            """;
    private static final String ACTIVE_INVENTORY_COMPLETION = """
            (r.business_type<>'INVENTORY_TASK' OR r.stage<>'INVENTORY_COMPLETION' OR EXISTS (
                SELECT 1 FROM fams_inventory_task t WHERE t.id=r.business_id
                    AND t.task_status='IN_PROGRESS' AND t.due_at=r.deadline_at
                    AND ((r.recipient_account_id IS NOT NULL AND EXISTS (
                        SELECT 1 FROM fams_inventory_item i WHERE i.task_id=t.id
                            AND i.executor_account_id=r.recipient_account_id
                    )) OR (r.recipient_account_id IS NULL AND NOT EXISTS (
                        SELECT 1 FROM fams_inventory_item i WHERE i.task_id=t.id
                            AND i.executor_account_id IS NOT NULL
                    )))
            ))
            """;
    private static final String ACTIVE_HANDOVER_RECEIPT = """
            (r.stage<>'HANDOVER_RECEIPT' OR (
                (r.business_type='REQUISITION_ALLOCATION' AND EXISTS (
                    SELECT 1 FROM fams_requisition_allocation a
                    JOIN fams_requisition_handover h ON h.allocation_id=a.id
                    LEFT JOIN fams_system_account recipient ON recipient.employee_id=h.temporary_employee_id
                        AND recipient.status='ENABLED'
                    WHERE a.id=r.business_id AND a.allocation_status='DELIVERED_WAITING_RECEIPT'
                        AND h.receipt_deadline_at=r.deadline_at
                        AND (r.reminder_type IN
            """ + ESCALATION_TYPES + """
                            OR r.recipient_account_id <=> recipient.id)
                )) OR (r.business_type='TRANSFER_ITEM' AND EXISTS (
                    SELECT 1 FROM fams_transfer_item x
                    LEFT JOIN fams_system_account recipient ON recipient.employee_id=x.receiver_employee_id
                        AND recipient.status='ENABLED'
                    WHERE x.id=r.business_id AND x.item_status='DELIVERED'
                        AND x.receipt_deadline_at=r.deadline_at
                        AND (r.reminder_type IN
            """ + ESCALATION_TYPES + """
                            OR r.recipient_account_id <=> recipient.id)
                ))
            ))
            """;
    private static final String ACTIVE_REMINDER = ACTIVE_FACTORY_RETURN + " AND " + ACTIVE_INVENTORY_COMPLETION
            + " AND (r.business_type<>'INBOUND' OR " + InboundReminderState.CURRENT_STAGE + ")"
            + " AND " + ACTIVE_HANDOVER_RECEIPT;
    private static final String ACTIVE_CUSTODIAN_REMINDER = """
            (r.business_type<>'LOST_REPORT' OR r.stage<>'LOSS_REVIEW' OR EXISTS (
                SELECT 1 FROM fams_loss_report l JOIN fams_asset asset ON asset.id=l.asset_id
                WHERE l.id=r.business_id AND l.report_status IN ('SEARCHING','CONFIRMED_LOST')
                    AND asset.lifecycle_status<>'SCRAPPED' AND l.processor_account_id <=> r.recipient_account_id
                    AND NOT EXISTS (
                        SELECT 1 FROM fams_scrap_request s JOIN fams_scrap_disposition d ON d.request_id=s.id
                        WHERE s.source_type='LOSS' AND s.source_business_id=l.id AND d.disposition_status='LOSS_WRITTEN_OFF'
                    )
                    AND l.next_review_date=CASE r.reminder_type
                        WHEN 'DUE_IN_1_DAY' THEN DATE_ADD(r.notice_date,INTERVAL 1 DAY)
                        WHEN 'OVERDUE' THEN DATE_SUB(r.notice_date,INTERVAL 1 DAY) END
            )) AND (r.business_type<>'SCRAP' OR r.stage<>'SCRAP_DISPOSITION' OR EXISTS (
                SELECT 1 FROM fams_scrap_request s JOIN fams_scrap_disposition d ON d.request_id=s.id
                WHERE s.id=r.business_id AND s.request_status='EXECUTED'
                    AND d.disposition_status='PENDING_DISPOSITION' AND d.processor_account_id=r.recipient_account_id
                    AND (d.due_at IS NULL OR d.due_at=r.deadline_at)
                    AND d.due_date=CASE r.reminder_type
                        WHEN 'DUE_IN_3_DAYS' THEN DATE_ADD(r.notice_date,INTERVAL 3 DAY)
                        WHEN 'DUE_TODAY' THEN r.notice_date
                        WHEN 'OVERDUE' THEN DATE_SUB(r.notice_date,INTERVAL 1 DAY) END
            ))
            """;
    private static final String REMINDER_COLUMNS =
            " SELECT 'REMINDER' message_kind,r.id id,r.business_type,r.business_id,r.reminder_type stage,"
            + " r.stage object_stage,r.recipient_pool_type pool_type,r.recipient_account_id,r.created_at moment,"
            + " r.deadline_at FROM fams_business_reminder r WHERE r.reminder_type<>'SMS_FALLBACK' AND "
            + ACTIVE_REMINDER + " AND " + ACTIVE_CUSTODIAN_REMINDER + " AND "
            + ReminderFollowupService.GROUP_NOTICE_VISIBLE + " AND";
    /**
     * Only the latest occurrence of an object stage is an active message; earlier ones stay as history. Escalation
     * tiers are newest per recipient within their own tier, and inventory completion has a separate ladder
     * for every executor.
     */
    private static final String NEWEST_OCCURRENCE =
            " r.id=(SELECT MAX(x.id) FROM fams_business_reminder x WHERE x.business_type=r.business_type"
            + " AND x.business_id=r.business_id AND x.stage=r.stage AND x.reminder_type<>'SMS_FALLBACK'"
            + " AND (r.stage<>'HANDOVER_RECEIPT' OR x.deadline_at=r.deadline_at AND x.recipient_slot=r.recipient_slot)"
            + " AND ((x.reminder_type IN" + ESCALATION_TYPES
            + " AND x.reminder_type=r.reminder_type AND x.recipient_slot=r.recipient_slot)"
            + " OR (r.reminder_type NOT IN" + ESCALATION_TYPES + " AND x.reminder_type NOT IN" + ESCALATION_TYPES
            + " AND (r.business_type<>'INVENTORY_TASK' OR r.stage<>'INVENTORY_COMPLETION'"
            + " OR x.recipient_slot=r.recipient_slot)"
            + "))) AND";

    private final JdbcTemplate db;
    private final RequisitionActorContext actors;
    private final Clock clock;

    MessageService(JdbcTemplate db, RequisitionActorContext actors, Clock clock) {
        this.db = db;
        this.actors = actors;
        this.clock = clock;
    }

    public Map<String, Object> list(RequisitionActorContext.Actor actor, String view, int page, int pageSize) {
        var current = actors.revalidateLocked(actor);
        var exception = "EXCEPTION".equals(view);
        if (exception && !current.has(Role.ASSET_ADMIN) && !current.has(Role.WAREHOUSE_OPERATOR)) {
            throw new ReminderFailure(403, "MESSAGE_SCOPE_FORBIDDEN");
        }
        var sql = exception ? exceptionSql(current) : inboxSql(current);
        var args = exception ? exceptionArgs(current) : inboxArgs(current);
        var paged = new ArrayList<>(args);
        paged.addAll(List.of(pageSize, (long) (page - 1) * pageSize));
        var rows = db.queryForList("""
                SELECT m.*, COALESCE(allocation.request_id, transfer.request_id, factory.repair_id,
                    extension.loan_id, extension.request_id) parent_business_id,
                    CASE WHEN extension.loan_id IS NOT NULL THEN 'LOAN'
                         WHEN extension.request_id IS NOT NULL THEN 'REQUISITION' END parent_business_type
                FROM (
                """ + sql + """
                ) m
                LEFT JOIN fams_requisition_allocation allocation
                    ON m.business_type='REQUISITION_ALLOCATION' AND allocation.id=m.business_id
                LEFT JOIN fams_transfer_item transfer ON m.business_type='TRANSFER_ITEM' AND transfer.id=m.business_id
                LEFT JOIN fams_repair_factory_round factory
                    ON m.business_type='REPAIR_FACTORY_ROUND' AND factory.id=m.business_id
                LEFT JOIN fams_loan_extension extension ON m.business_type='LOAN_EXTENSION' AND extension.id=m.business_id
                """
                + " ORDER BY m.moment DESC,m.id DESC LIMIT ? OFFSET ?", paged.toArray());
        var read = readIds(current.id(), rows);
        var unreadArgs = new ArrayList<>(args);
        unreadArgs.add(current.id());
        return Map.of(
                "items", rows.stream().map(row -> item(row, read)).toList(),
                "total", Objects.requireNonNull(db.queryForObject("SELECT COUNT(*) FROM (" + sql + ") m",
                        Long.class, args.toArray())),
                "unread", Objects.requireNonNull(db.queryForObject("SELECT COUNT(*) FROM (" + sql + ") m WHERE"
                        + " NOT EXISTS (SELECT 1 FROM fams_message_receipt c WHERE c.account_id=?"
                        + " AND c.message_kind=m.message_kind AND c.message_id=m.id)",
                        Long.class, unreadArgs.toArray())),
                "page", page, "pageSize", pageSize, "view", exception ? "EXCEPTION" : "INBOX");
    }

    /** Marking read records only personal read state; the todo, its business lock and its deadline stay untouched. */
    public Map<String, Object> read(RequisitionActorContext.Actor actor, String kind, long id) {
        var current = actors.revalidateLocked(actor);
        String addressable;
        List<Object> args = new ArrayList<>();
        args.add(id);
        if ("TODO".equals(kind)) {
            addressable = addressableTodo(current, args);
        } else if ("REMINDER".equals(kind)) {
            addressable = addressableReminder(current, args);
        } else {
            throw new ReminderFailure(400, "MESSAGE_KIND_INVALID");
        }
        var columns = "TODO".equals(kind) ? "t.id" : "r.id";
        var table = "TODO".equals(kind) ? "fams_todo t" : "fams_business_reminder r";
        if (db.queryForList("SELECT " + columns + " FROM " + table + " WHERE " + columns
                + "=? AND" + addressable + " FOR UPDATE", args.toArray()).isEmpty()) {
            throw new ReminderFailure(404, "MESSAGE_NOT_AVAILABLE");
        }
        db.update("INSERT INTO fams_message_receipt (account_id,message_kind,message_id,read_at)"
                + " VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE message_id=message_id",
                current.id(), kind, id, clock.instant());
        return Map.of("kind", kind, "id", Long.toString(id), "read", true);
    }

    private String inboxSql(RequisitionActorContext.Current current) {
        var pools = pools(current);
        return TODO_COLUMNS + addressed("t", "assignee_account_id", "pool_type", pools)
                + " UNION ALL" + REMINDER_COLUMNS + NEWEST_OCCURRENCE
                + addressed("r", "recipient_account_id", "recipient_pool_type", pools);
    }

    private List<Object> inboxArgs(RequisitionActorContext.Current current) {
        var pools = pools(current);
        var args = new ArrayList<>();
        args.add(current.id());
        args.addAll(pools);
        args.add(current.id());
        args.addAll(pools);
        return List.copyOf(args);
    }

    /**
     * Administrators see every overdue exception. Warehouse operators see their shared pool and personal work.
     */
    private String exceptionSql(RequisitionActorContext.Current current) {
        if (current.has(Role.ASSET_ADMIN)) {
            return EXCEPTION_TODOS + " UNION ALL" + REMINDER_COLUMNS
                    + NEWEST_OCCURRENCE + " r.reminder_type IN" + OVERDUE_TYPES;
        }
        var sharedPool = List.of("WAREHOUSE_OPERATOR");
        return EXCEPTION_TODOS + " AND" + addressed("t", "assignee_account_id", "pool_type", sharedPool) + " UNION ALL"
                + REMINDER_COLUMNS + NEWEST_OCCURRENCE + " r.reminder_type IN" + OVERDUE_TYPES
                + " AND" + addressed("r", "recipient_account_id", "recipient_pool_type", sharedPool);
    }

    private List<Object> exceptionArgs(RequisitionActorContext.Current current) {
        if (current.has(Role.ASSET_ADMIN)) {
            return List.of();
        }
        return List.of(current.id(), "WAREHOUSE_OPERATOR", current.id(), "WAREHOUSE_OPERATOR");
    }

    private String addressableTodo(RequisitionActorContext.Current current, List<Object> args) {
        if (current.has(Role.ASSET_ADMIN)) {
            return " t.todo_status='OPEN'";
        }
        var pools = pools(current);
        args.add(current.id());
        args.addAll(pools);
        return " t.todo_status='OPEN' AND" + addressed("t", "assignee_account_id", "pool_type", pools);
    }

    private String addressableReminder(RequisitionActorContext.Current current, List<Object> args) {
        String available = " r.reminder_type<>'SMS_FALLBACK' AND " + ACTIVE_REMINDER + " AND "
                + ACTIVE_CUSTODIAN_REMINDER + " AND " + ReminderFollowupService.GROUP_NOTICE_VISIBLE;
        if (current.has(Role.ASSET_ADMIN)) {
            return available;
        }
        var pools = pools(current);
        args.add(current.id());
        args.addAll(pools);
        return available + " AND" + addressed("r", "recipient_account_id", "recipient_pool_type", pools);
    }

    private static String addressed(String alias, String personColumn, String poolColumn, List<String> pools) {
        return " (" + alias + "." + personColumn + "=?"
                + " OR (" + alias + "." + personColumn + " IS NULL AND " + alias + "." + poolColumn + " IN ("
                + markers(pools.size()) + ")))";
    }

    private Set<String> readIds(long account, List<Map<String, Object>> rows) {
        var read = new HashSet<String>();
        collect(read, account, "TODO", ids(rows, "TODO"));
        collect(read, account, "REMINDER", ids(rows, "REMINDER"));
        return read;
    }

    private static List<Long> ids(List<Map<String, Object>> rows, String kind) {
        var ids = new ArrayList<Long>();
        for (var row : rows) {
            if (kind.equals(row.get("message_kind"))) {
                ids.add(((Number) row.get("id")).longValue());
            }
        }
        return List.copyOf(ids);
    }

    private void collect(Set<String> read, long account, String kind, List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        var args = new ArrayList<>();
        args.add(account);
        args.add(kind);
        args.addAll(ids);
        db.queryForList("SELECT message_id FROM fams_message_receipt WHERE account_id=? AND message_kind=?"
                + " AND message_id IN (" + markers(ids.size()) + ")", Long.class, args.toArray())
                .forEach(id -> read.add(kind + ":" + id));
    }

    private static Map<String, Object> item(Map<String, Object> row, Set<String> read) {
        var kind = String.valueOf(row.get("message_kind"));
        return Map.ofEntries(Map.entry("kind", kind), Map.entry("id", row.get("id").toString()),
                Map.entry("businessType", row.get("business_type")),
                Map.entry("businessId", row.get("business_id").toString()),
                Map.entry("parentBusinessId", Objects.toString(row.get("parent_business_id"), "")),
                Map.entry("parentBusinessType", Objects.toString(row.get("parent_business_type"), "")),
                Map.entry("stage", row.get("stage")), Map.entry("objectStage", row.get("object_stage")),
                Map.entry("poolType", row.get("pool_type")),
                Map.entry("assigneeAccountId", row.get("assignee_account_id") == null
                        ? "" : row.get("assignee_account_id").toString()),
                Map.entry("openedAt", instant(row.get("moment")).toString()),
                Map.entry("deadlineAt", row.get("deadline_at") == null ? ""
                        : instant(row.get("deadline_at")).toString()),
                Map.entry("read", read.contains(kind + ":" + row.get("id"))));
    }

    private static Instant instant(Object value) {
        return value instanceof LocalDateTime local
                ? local.toInstant(ZoneOffset.UTC) : ((Timestamp) value).toInstant();
    }

    private static String markers(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static List<String> pools(RequisitionActorContext.Current current) {
        var pools = new ArrayList<String>();
        // TECH_ASSISTANT is deliberately absent: that pool is reached through the repair invitation pages, whose
        // sessions revalidate under participant rules rather than the ordinary business-identity rules.
        for (var role : List.of(Role.ASSET_ADMIN, Role.WAREHOUSE_OPERATOR, Role.EMPLOYEE)) {
            if (current.has(role)) {
                pools.add(role.name());
            }
        }
        return pools.isEmpty() ? List.of("_NONE_") : List.copyOf(pools);
    }
}
