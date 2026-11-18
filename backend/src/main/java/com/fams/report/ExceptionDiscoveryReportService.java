package com.fams.report;

import com.fams.auth.AssetActorContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Profile("fams-product")
class ExceptionDiscoveryReportService {
    private static final int EXPORT_LIMIT = 10000;
    private static final long TARGET_SECONDS = 300;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;

    ExceptionDiscoveryReportService(JdbcTemplate db, ObjectMapper json, Clock clock) {
        this.db = db;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Summary summary(LocalDate from, LocalDate toExclusive) {
        var window = window(from, toExclusive);
        var totals = new EnumMap<Kind, long[]>(Kind.class);
        for (var kind : Kind.values()) {
            totals.put(kind, new long[Status.values().length]);
        }
        scan(window, row -> totals.get(row.kind())[row.status().ordinal()]++);
        var categories = totals.entrySet().stream().map(entry -> {
            var counts = entry.getValue();
            return new Category(entry.getKey(), counts[Status.ON_TIME.ordinal()], counts[Status.LATE.ordinal()],
                    counts[Status.MISSING.ordinal()], counts[Status.PENDING.ordinal()]);
        }).toList();
        return new Summary(window.evaluatedAt(), categories.stream().mapToLong(Category::total).sum(),
                categories.stream().mapToLong(Category::onTime).sum(), categories);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public String export(AssetActorContext.Actor actor, LocalDate from, LocalDate toExclusive) {
        var window = window(from, toExclusive);
        var rows = new ArrayList<Occurrence>();
        scan(window, row -> {
            if (rows.size() == EXPORT_LIMIT) {
                throw new ExportLimit();
            }
            rows.add(row);
        });
        rows.sort(Comparator.comparing(Occurrence::occurredAt).reversed()
                .thenComparing(Occurrence::kind).thenComparing(Occurrence::id));
        var result = new StringBuilder("kind,id,dueDate,occurredAt,firstSeenAt,status\r\n");
        for (var row : rows) {
            result.append(row.kind()).append(',').append(row.id()).append(',')
                    .append(row.dueDate() == null ? "" : row.dueDate()).append(',').append(row.occurredAt()).append(',')
                    .append(row.firstSeenAt() == null ? "" : row.firstSeenAt()).append(',').append(row.status())
                    .append("\r\n");
        }
        db.update("""
                INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,action,resource_type,
                    resource_id,occurred_at,detail_json)
                VALUES (?,?,?,'OPERATIONS_REPORT_EXPORTED','EXCEPTION_DISCOVERY',0,?,
                    JSON_OBJECT('from',?,'toExclusive',?,'rows',?,'onTime',?))
                """, actor.requestId(), actor.id(), actor.role(), Timestamp.from(window.evaluatedAt()),
                from.toString(), toExclusive.toString(), rows.size(),
                rows.stream().filter(row -> row.status() == Status.ON_TIME).count());
        return result.toString();
    }

    private void scan(Window window, Consumer<Occurrence> receive) {
        inventory(window, receive);
        loans(window, receive);
        factory(window, receive);
    }

    private void inventory(Window window, Consumer<Occurrence> receive) {
        db.query("""
                SELECT o.id,o.outcome,o.received_at,
                    (SELECT MIN(t.opened_at) FROM fams_todo t
                     WHERE t.business_type='INVENTORY_OBSERVATION' AND t.business_id=o.id
                       AND t.todo_type=IF(o.outcome='CONFLICT_REVIEW','INVENTORY_CONFLICT','INVENTORY_DIFFERENCE')
                       AND t.opened_at>=o.received_at) first_seen
                FROM fams_inventory_observation o WHERE o.received_at>=? AND o.received_at<?
                    AND o.outcome IN ('CONFLICT_REVIEW','DIFFERENCE_REVIEW')
                """, row -> {
            var kind = "CONFLICT_REVIEW".equals(row.getString("outcome"))
                    ? Kind.INVENTORY_CONFLICT : Kind.INVENTORY_DIFFERENCE;
            record(window, receive, kind, row.getString("id"), null, instant(row.getTimestamp("received_at")),
                    instant(row.getTimestamp("first_seen")));
        }, Timestamp.from(window.from()), Timestamp.from(window.until()));
    }

    private void loans(Window window, Consumer<Occurrence> receive) {
        db.query("""
                WITH periods AS (
                    SELECT l.id,l.asset_id,l.created_at,l.closed_at period_end,l.expected_return_date due_date,
                        l.parameter_snapshot_json->>'$.timezone' timezone_id
                    FROM fams_loan l WHERE l.relation_status<>'VOID' AND l.expected_return_date<=?
                    UNION ALL
                    SELECT l.id,l.asset_id,l.created_at,e.decided_at,e.original_due_date,
                        l.parameter_snapshot_json->>'$.timezone'
                    FROM fams_loan_extension e JOIN fams_loan l ON l.id=e.loan_id
                    WHERE e.extension_status='APPROVED' AND l.relation_status<>'VOID' AND e.original_due_date<=?
                )
                SELECT p.*,
                    (SELECT MIN(r.created_at) FROM fams_loan_reminder r
                     WHERE r.loan_id=p.id AND r.expected_return_date=p.due_date AND r.reminder_type='OVERDUE'
                       AND (p.period_end IS NULL OR r.created_at<p.period_end)) first_seen,
                    (SELECT JSON_ARRAYAGG(JSON_OBJECT('since',s.confirmed_lost_at,'until',s.recovered_at))
                     FROM fams_loss_report s WHERE s.asset_id=p.asset_id AND s.confirmed_lost_at IS NOT NULL
                         AND (s.recovered_at IS NULL OR s.recovered_at>=p.created_at)) loss_periods
                FROM periods p WHERE p.created_at<? AND (p.period_end IS NULL OR p.period_end>=?)
                """, row -> {
            var due = row.getObject("due_date", LocalDate.class);
            var occurred = later(deadline(due, row.getString("timezone_id")), instant(row.getTimestamp("created_at")));
            occurred = afterLoss(occurred, row.getString("loss_periods"));
            var end = instant(row.getTimestamp("period_end"));
            if (occurred != null && (end == null || occurred.isBefore(end))) {
                record(window, receive, Kind.LOAN_OVERDUE, row.getString("id"), due, occurred,
                        instant(row.getTimestamp("first_seen")));
            }
        }, window.untilDate(), window.untilDate(), Timestamp.from(window.until()), Timestamp.from(window.from()));
    }

    private void factory(Window window, Consumer<Occurrence> receive) {
        db.query("""
                SELECT x.id,x.expected_return_date,x.received_at,p.timezone_id,
                    (SELECT MIN(r.created_at) FROM fams_business_reminder r
                     WHERE r.business_type='REPAIR_FACTORY_ROUND' AND r.business_id=x.id AND r.stage='FACTORY_RETURN'
                       AND r.reminder_type IN ('OVERDUE','OVERDUE_EVERY_7_DAYS')
                       AND r.created_at>=r.deadline_at) first_seen,
                    (SELECT JSON_ARRAYAGG(JSON_OBJECT('since',t.opened_at,'until',t.closed_at))
                     FROM fams_todo t WHERE t.business_type='REPAIR' AND t.business_id=x.repair_id
                       AND t.todo_type='FACTORY_RETURN' AND t.opened_at>=x.dispatched_at
                       AND (x.received_at IS NULL OR t.opened_at<x.received_at)) followups
                FROM fams_repair_factory_round x JOIN fams_repair_request p ON p.id=x.repair_id
                WHERE x.dispatched_at<? AND (x.received_at IS NULL OR x.received_at>=?)
                    AND x.expected_return_date<=?
                """, row -> {
            var due = row.getObject("expected_return_date", LocalDate.class);
            var dueAt = deadline(due, row.getString("timezone_id"));
            var received = instant(row.getTimestamp("received_at"));
            var firstNotice = instant(row.getTimestamp("first_seen"));
            Instant occurred = null;
            Instant seen = null;
            for (var followup : periods(row.getString("followups"))) {
                var start = later(dueAt, followup.since());
                var end = factoryPeriodEnd(received, followup.until());
                if (end != null && !start.isBefore(end)) {
                    continue;
                }
                if (occurred == null) {
                    occurred = start;
                }
                if (firstNotice != null) {
                    var visible = later(start, firstNotice);
                    if (end == null || visible.isBefore(end)) {
                        seen = visible;
                        break;
                    }
                }
            }
            if (occurred != null) {
                record(window, receive, Kind.FACTORY_OVERDUE, row.getString("id"), due, occurred, seen);
            }
        }, Timestamp.from(window.until()), Timestamp.from(window.from()), window.untilDate());
    }

    private Instant afterLoss(Instant occurred, String periods) {
        for (var period : periods(periods)) {
            if (occurred.isBefore(period.since())) {
                break;
            }
            if (period.until() == null) {
                return null;
            }
            occurred = later(occurred, period.until());
        }
        return occurred;
    }

    private List<Period> periods(String periods) {
        if (periods == null) {
            return List.of();
        }
        var windows = new ArrayList<Period>();
        for (var period : json.readTree(periods)) {
            windows.add(new Period(jsonTime(period.path("since").asText()),
                    period.path("until").isNull() ? null : jsonTime(period.path("until").asText())));
        }
        windows.sort(Comparator.comparing(Period::since));
        return windows;
    }

    private static Instant factoryPeriodEnd(Instant receipt, Instant todoEnd) {
        return todoEnd == null || receipt != null && receipt.isBefore(todoEnd) ? receipt : todoEnd;
    }

    private static void record(Window window, Consumer<Occurrence> receive, Kind kind, String id, LocalDate dueDate,
            Instant occurred, Instant seen) {
        if (occurred.isBefore(window.from()) || !occurred.isBefore(window.until())
                || occurred.isAfter(window.evaluatedAt())) {
            return;
        }
        if (seen != null && (seen.isBefore(occurred) || seen.isAfter(window.evaluatedAt()))) {
            seen = null;
        }
        var threshold = occurred.plusSeconds(TARGET_SECONDS);
        Status status;
        if (seen != null) {
            status = seen.isAfter(threshold) ? Status.LATE : Status.ON_TIME;
        } else {
            status = window.evaluatedAt().isBefore(threshold) ? Status.PENDING : Status.MISSING;
        }
        receive.accept(new Occurrence(kind, id, dueDate, occurred, seen, status));
    }

    private Window window(LocalDate from, LocalDate until) {
        return new Window(from.atStartOfDay().toInstant(ZoneOffset.UTC),
                until.atStartOfDay().toInstant(ZoneOffset.UTC), until, clock.instant());
    }

    private static Instant deadline(LocalDate due, String zone) {
        return due.plusDays(1).atStartOfDay(ZoneId.of(zone)).toInstant();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Instant jsonTime(String value) {
        return LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
    }

    private static Instant later(Instant left, Instant right) {
        return left.isBefore(right) ? right : left;
    }

    enum Kind { LOAN_OVERDUE, INVENTORY_CONFLICT, INVENTORY_DIFFERENCE, FACTORY_OVERDUE }
    private enum Status { ON_TIME, LATE, MISSING, PENDING }
    record Summary(Instant evaluatedAt, long total, long onTime, List<Category> categories) {}
    record Category(Kind kind, long onTime, long late, long missing, long pending) {
        long total() {
            return onTime + late + missing + pending;
        }
    }
    private record Window(Instant from, Instant until, LocalDate untilDate, Instant evaluatedAt) {}
    private record Period(Instant since, Instant until) {}
    private record Occurrence(Kind kind, String id, LocalDate dueDate, Instant occurredAt, Instant firstSeenAt,
                              Status status) {}
    static final class ExportLimit extends RuntimeException {}
}
