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
            " SELECT 'TODO' message_kind,t.id id,t.business_type,t.business_id,t.todo_type stage,"
            + " '' object_stage,t.pool_type,t.assignee_account_id,t.opened_at moment,"
            + " CAST(NULL AS DATETIME) deadline_at FROM fams_todo t WHERE t.todo_status='OPEN' AND";
    private static final String REMINDER_COLUMNS =
            " SELECT 'REMINDER' message_kind,r.id id,r.business_type,r.business_id,r.reminder_type stage,"
            + " r.stage object_stage,r.recipient_pool_type pool_type,r.recipient_account_id,r.created_at moment,"
            + " r.deadline_at FROM fams_business_reminder r WHERE r.reminder_type<>'SMS_FALLBACK' AND";
    /**
     * Only the latest occurrence of an object stage is an active message; earlier ones stay as history. Escalation
     * tiers are newest-per-recipient within their own tier instead, so an escalation never displaces the ladder
     * notice that triggered it.
     */
    private static final String NEWEST_OCCURRENCE =
            " r.id=(SELECT MAX(x.id) FROM fams_business_reminder x WHERE x.business_type=r.business_type"
            + " AND x.business_id=r.business_id AND x.stage=r.stage AND x.reminder_type<>'SMS_FALLBACK'"
            + " AND ((x.reminder_type IN" + ESCALATION_TYPES
            + " AND x.reminder_type=r.reminder_type AND x.recipient_slot=r.recipient_slot)"
            + " OR (r.reminder_type NOT IN" + ESCALATION_TYPES + " AND x.reminder_type NOT IN" + ESCALATION_TYPES
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
        var rows = db.queryForList("SELECT m.* FROM (" + sql + ") m"
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
     * Administrators answer for every pool's overdue exception; warehouse operators see their own pool plus
     * whatever is addressed to them personally.
     */
    private String exceptionSql(RequisitionActorContext.Current current) {
        if (current.has(Role.ASSET_ADMIN)) {
            return TODO_COLUMNS + " t.todo_type IN" + OVERDUE_TYPES + " UNION ALL" + REMINDER_COLUMNS
                    + NEWEST_OCCURRENCE + " r.reminder_type IN" + OVERDUE_TYPES;
        }
        return TODO_COLUMNS + " t.todo_type IN" + OVERDUE_TYPES
                + " AND (t.pool_type='WAREHOUSE_OPERATOR' OR t.assignee_account_id=?)" + " UNION ALL"
                + REMINDER_COLUMNS + NEWEST_OCCURRENCE + " r.reminder_type IN" + OVERDUE_TYPES
                + " AND (r.recipient_pool_type='WAREHOUSE_OPERATOR' OR r.recipient_account_id=?)";
    }

    private List<Object> exceptionArgs(RequisitionActorContext.Current current) {
        if (current.has(Role.ASSET_ADMIN)) {
            return List.of();
        }
        return List.of(current.id(), current.id());
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
        if (current.has(Role.ASSET_ADMIN)) {
            return " r.reminder_type<>'SMS_FALLBACK'";
        }
        var pools = pools(current);
        args.add(current.id());
        args.addAll(pools);
        return " r.reminder_type<>'SMS_FALLBACK' AND" + addressed("r", "recipient_account_id", "recipient_pool_type", pools);
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
