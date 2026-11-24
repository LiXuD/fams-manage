package com.fams.report;

import com.fams.assetimport.AssetImportWorkbook;
import com.fams.auth.AssetActorContext;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("fams-product")
class SealedInventoryReportService {
    private static final int EXPORT_LIMIT = 10_000;
    private static final String EXECUTOR_SOURCE = """
            CASE WHEN s.task_id IS NOT NULL THEN 'SEALED'
                 WHEN t.task_status IN ('READY','IN_PROGRESS','WAITING_REVIEW') THEN 'CURRENT'
                 WHEN t.published_at IS NOT NULL AND t.task_status IN ('CANCELLED','TERMINATED') THEN 'RETAINED'
                 ELSE 'MISSING' END
            """;
    // CHECKSTYLE.OFF: LineLength - frozen SQL/CSV literal; re-wrapping changes the value
    private static final String PUBLISHED_TRACEABILITY = """
            WITH basis AS (
                SELECT t.id,t.name,t.task_status,t.published_at,COALESCE(s.expected_count,t.expected_count) AS expected,
            """ + EXECUTOR_SOURCE + """
                    AS source
                FROM fams_inventory_task t LEFT JOIN fams_inventory_sealed_report s ON s.task_id=t.id
                WHERE t.published_at>=? AND t.published_at<?
            ), coverage AS (
                SELECT b.*,
                    CASE
                        WHEN b.source='SEALED' THEN (SELECT COUNT(*) FROM fams_inventory_sealed_item i
                            WHERE i.task_id=b.id)
                        WHEN b.source IN ('CURRENT','RETAINED') THEN (SELECT COUNT(*) FROM fams_inventory_item i
                            WHERE i.task_id=b.id)
                        ELSE 0 END AS counted,
                    CASE WHEN b.source='SEALED' THEN EXISTS (
                        SELECT 1 FROM fams_inventory_sealed_item i LEFT JOIN fams_inventory_sealed_evidence e
                            ON e.task_id=i.task_id AND e.observation_id=CAST(
                                NULLIF(i.snapshot_json->>'$.review.observation_id','null') AS UNSIGNED)
                        WHERE i.task_id=b.id AND (NOT JSON_CONTAINS_PATH(i.snapshot_json,'one','$.item.executor_account_id')
                            OR (i.snapshot_json->>'$.review.review_status'='ACCEPTED' AND e.observation_id IS NULL))
                    ) ELSE FALSE END AS incomplete
                FROM basis b
            ), traceability AS (
                SELECT c.*, c.source<>'MISSING' AND c.expected=c.counted AND NOT c.incomplete AS traceable
                FROM coverage c
            )
            """;
    // CHECKSTYLE.ON: LineLength

    private final JdbcTemplate db;
    private final Clock clock;

    SealedInventoryReportService(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    Page list(LocalDate from, LocalDate until, Long before, int pageSize) {
        var rows = query(from, until, before, pageSize + 1);
        if (rows.size() <= pageSize) {
            return new Page(rows, null);
        }
        return new Page(List.copyOf(rows.subList(0, pageSize)), rows.get(pageSize - 1).taskId());
    }

    @Transactional
    public String export(AssetActorContext.Actor actor, LocalDate from, LocalDate until) {
        var rows = query(from, until, null, EXPORT_LIMIT + 1);
        if (rows.size() > EXPORT_LIMIT) {
            throw new ExportLimit();
        }
        db.update("""
                INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,action,resource_type,
                    resource_id,occurred_at,detail_json)
                VALUES (?,?,?,'OPERATIONS_REPORT_EXPORTED','SEALED_INVENTORY_DETAIL',0,?,
                    JSON_OBJECT('from',?,'toExclusive',?,'rows',?))
                """, actor.requestId(), actor.id(), actor.role(), Timestamp.from(clock.instant()),
                from.toString(), until.toString(), rows.size());
        return "\uFEFFtaskId,taskName,sealedAt,expected,accepted,notObserved,differenceEvidence,conflictEvidence\r\n"
                + rows.stream().map(row -> String.join(",", row.taskId(),
                        "\"" + AssetImportWorkbook.safeExportCell(row.name()).replace("\"", "\"\"") + "\"",
                        row.sealedAt().toString(), Long.toString(row.expected()), Long.toString(row.accepted()),
                        Long.toString(row.notObserved()), Long.toString(row.differenceEvidence()),
                        Long.toString(row.conflictEvidence()))).collect(Collectors.joining("\r\n"));
    }

    private List<Task> query(LocalDate from, LocalDate until, Long before, int limit) {
        return db.query("""
                SELECT s.task_id,t.name,s.created_at,s.expected_count,s.accepted_count,s.not_observed_count,
                    (SELECT COUNT(*) FROM fams_inventory_sealed_evidence e
                        WHERE e.task_id=s.task_id
                        AND JSON_SEARCH(e.snapshot_json,'one','CONFLICT',NULL,'$.subjects[*].dimension') IS NOT NULL)
                        AS conflict_count,
                    (SELECT COUNT(*) FROM fams_inventory_sealed_evidence e WHERE e.task_id=s.task_id
                        AND JSON_LENGTH(e.snapshot_json,'$.subjects')>0) AS difference_count
                FROM (
                    SELECT task_id,created_at,expected_count,accepted_count,not_observed_count
                    FROM fams_inventory_sealed_report
                    WHERE created_at>=? AND created_at<? AND (? IS NULL OR task_id<?)
                    ORDER BY task_id DESC LIMIT ?
                ) s JOIN fams_inventory_task t ON t.id=s.task_id ORDER BY s.task_id DESC
                """, (row, index) -> new Task(row.getString("task_id"), row.getString("name"),
                        row.getTimestamp("created_at").toInstant(), row.getLong("expected_count"),
                        row.getLong("accepted_count"), row.getLong("not_observed_count"),
                        row.getLong("difference_count"), row.getLong("conflict_count")),
                Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC)),
                Timestamp.from(until.atStartOfDay().toInstant(ZoneOffset.UTC)), before, before, limit);
    }

    ProgressPage progress(Long before, int pageSize) {
        var rows = db.query("""
                SELECT t.id,t.name,t.task_status,t.due_at,t.expected_count,
                    (SELECT COUNT(*) FROM fams_inventory_item i JOIN fams_inventory_item_review r
                        ON r.item_id=i.id AND r.recheck_generation=i.recheck_generation
                        WHERE i.task_id=t.id AND r.task_id=t.id AND r.review_status='ACCEPTED') AS accepted
                FROM fams_inventory_task t
                WHERE t.task_status IN ('READY','IN_PROGRESS','WAITING_REVIEW') AND (? IS NULL OR t.id<?)
                ORDER BY t.id DESC LIMIT ?
                """, (row, index) -> new Progress(row.getString("id"), row.getString("name"),
                        row.getString("task_status"), row.getTimestamp("due_at").toInstant(),
                        row.getLong("expected_count"), row.getLong("accepted")), before, before, pageSize + 1);
        if (rows.size() <= pageSize) {
            return new ProgressPage(rows, null);
        }
        return new ProgressPage(List.copyOf(rows.subList(0, pageSize)), rows.get(pageSize - 1).taskId());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExecutorBreakdown executors(long taskId) {
        var tasks = db.query("""
                SELECT COALESCE(s.expected_count,t.expected_count) AS expected,
                """ + EXECUTOR_SOURCE + """
                    AS source
                FROM fams_inventory_task t LEFT JOIN fams_inventory_sealed_report s ON s.task_id=t.id
                WHERE t.id=?
                """, (row, index) -> new ExecutorBasis(ExecutorSource.valueOf(row.getString("source")),
                        row.getLong("expected")), taskId);
        if (tasks.isEmpty() || tasks.getFirst().source() == ExecutorSource.MISSING) {
            return null;
        }
        var task = tasks.getFirst();
        if (task.source() == ExecutorSource.SEALED) {
            // CHECKSTYLE.OFF: LineLength - frozen SQL/CSV literal; re-wrapping changes the value
            var rows = executorRows("""
                    SELECT NULLIF(CAST(NULLIF(i.snapshot_json->>'$.item.executor_account_id','null') AS UNSIGNED),0)
                            AS executor_id,
                        i.snapshot_json->>'$.review.review_status' AS review_status,
                        EXISTS (SELECT 1 FROM JSON_TABLE(e.snapshot_json,'$.subjects[*]' COLUMNS (
                            dimension VARCHAR(32) PATH '$.dimension'
                        )) subject WHERE subject.dimension <> 'NORMAL') AS has_difference
                    FROM fams_inventory_sealed_item i
                    LEFT JOIN fams_inventory_sealed_evidence e ON e.task_id=i.task_id
                        AND e.observation_id=CAST(NULLIF(i.snapshot_json->>'$.review.observation_id','null') AS UNSIGNED)
                    WHERE i.task_id=?
                    """, taskId);
            // CHECKSTYLE.ON: LineLength
            return executorBreakdown(taskId, task.source(), task.expected(), rows);
        }
        var rows = executorRows("""
                SELECT i.executor_account_id AS executor_id,r.review_status,
                    EXISTS (SELECT 1 FROM fams_inventory_review_subject d
                        WHERE d.observation_id=r.observation_id AND d.task_id=i.task_id
                          AND d.item_id=i.id AND d.recheck_generation=i.recheck_generation
                          AND d.dimension <> 'NORMAL') AS has_difference
                FROM fams_inventory_item i
                LEFT JOIN fams_inventory_item_review r
                    ON r.item_id=i.id AND r.task_id=i.task_id AND r.recheck_generation=i.recheck_generation
                WHERE i.task_id=?
                """, taskId);
        return executorBreakdown(taskId, task.source(), task.expected(), rows);
    }

    private List<Executor> executorRows(String items, long taskId) {
        return db.query("WITH items AS (" + items + ") " + """
                SELECT executor_id,COUNT(*) AS expected,
                    COUNT(CASE WHEN review_status='ACCEPTED' THEN 1 END) AS accepted,
                    COUNT(CASE WHEN review_status='ACCEPTED' AND has_difference THEN 1 END) AS differences
                FROM items GROUP BY executor_id ORDER BY executor_id IS NULL,executor_id
                """, (row, index) -> {
                    long expected = row.getLong("expected"), accepted = row.getLong("accepted");
                    return new Executor(row.getString("executor_id"), expected, accepted,
                            row.getLong("differences"), expected - accepted);
                }, taskId);
    }

    private static ExecutorBreakdown executorBreakdown(long taskId, ExecutorSource source, long expected,
            List<Executor> rows) {
        long counted = rows.stream().mapToLong(Executor::expected).sum();
        return new ExecutorBreakdown(Long.toString(taskId), source, expected, counted, rows);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public String exportExecutors(AssetActorContext.Actor actor, long taskId) {
        var breakdown = executors(taskId);
        if (breakdown == null) {
            return null;
        }
        db.update("""
                INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,action,resource_type,
                    resource_id,occurred_at,detail_json)
                VALUES (?,?,?,'OPERATIONS_REPORT_EXPORTED',?,?,?,
                    JSON_OBJECT('rows',?,'expected',?,'counted',?))
                """, actor.requestId(), actor.id(), actor.role(),
                breakdown.source().name() + "_INVENTORY_EXECUTORS",
                taskId, Timestamp.from(clock.instant()), breakdown.items().size(), breakdown.expected(),
                breakdown.counted());
        // The comma-safe fields are only database IDs and integer counts.
        return "taskId,executorAccountId,expected,accepted,differences,notObserved\r\n"
                + breakdown.items().stream().map(row -> String.join(",", breakdown.taskId(),
                        row.executorAccountId() == null ? "" : row.executorAccountId(),
                        Long.toString(row.expected()), Long.toString(row.accepted()),
                        Long.toString(row.differences()), Long.toString(row.notObserved())))
                        .collect(Collectors.joining("\r\n"));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TraceabilityPage traceability(LocalDate from, LocalDate until, Long before, int pageSize) {
        var counts = db.queryForObject(PUBLISHED_TRACEABILITY + """
                SELECT COUNT(*) AS published,COUNT(CASE WHEN traceable THEN 1 END) AS traceable FROM traceability
                """, (row, index) -> new TraceabilityCounts(row.getLong("published"), row.getLong("traceable")),
                utc(from), utc(until));
        var rows = traceabilityRows(from, until, before, pageSize + 1);
        if (rows.size() <= pageSize) {
            return new TraceabilityPage(counts, rows, null);
        }
        return new TraceabilityPage(counts, List.copyOf(rows.subList(0, pageSize)), rows.get(pageSize - 1).taskId());
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public String exportTraceability(AssetActorContext.Actor actor, LocalDate from, LocalDate until) {
        var rows = traceabilityRows(from, until, null, EXPORT_LIMIT + 1);
        if (rows.size() > EXPORT_LIMIT) {
            throw new ExportLimit();
        }
        db.update("""
                INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,action,resource_type,
                    resource_id,occurred_at,detail_json)
                VALUES (?,?,?,'OPERATIONS_REPORT_EXPORTED','PUBLISHED_INVENTORY_TRACEABILITY',0,?,
                    JSON_OBJECT('from',?,'toExclusive',?,'rows',?,'traceable',?))
                """, actor.requestId(), actor.id(), actor.role(), Timestamp.from(clock.instant()),
                from.toString(), until.toString(), rows.size(),
                rows.stream().filter(TraceabilityTask::traceable).count());
        return "taskId,taskName,status,publishedAt,source,expected,counted,traceable\r\n"
                + rows.stream().map(row -> String.join(",", row.taskId(),
                        "\"" + AssetImportWorkbook.safeExportCell(row.name()).replace("\"", "\"\"") + "\"",
                        row.status(), row.publishedAt().toString(), row.source().name(),
                        Long.toString(row.expected()), Long.toString(row.counted()), Boolean.toString(row.traceable())))
                        .collect(Collectors.joining("\r\n"));
    }

    private List<TraceabilityTask> traceabilityRows(LocalDate from, LocalDate until, Long before, int limit) {
        return db.query(PUBLISHED_TRACEABILITY + """
                SELECT id,name,task_status,published_at,source,expected,counted,traceable
                FROM traceability WHERE (? IS NULL OR id<?) ORDER BY id DESC LIMIT ?
                """, (row, index) -> new TraceabilityTask(row.getString("id"), row.getString("name"),
                        row.getString("task_status"), row.getTimestamp("published_at").toInstant(),
                        ExecutorSource.valueOf(row.getString("source")), row.getLong("expected"),
                        row.getLong("counted"), row.getBoolean("traceable")),
                        utc(from), utc(until), before, before, limit);
    }

    private static Timestamp utc(LocalDate date) {
        return Timestamp.from(date.atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    private record ExecutorBasis(ExecutorSource source, long expected) {}
    record TraceabilityCounts(long published, long traceable) {}
    record TraceabilityTask(String taskId, String name, String status, Instant publishedAt, ExecutorSource source,
                            long expected, long counted, boolean traceable) {}
    record TraceabilityPage(TraceabilityCounts counts, List<TraceabilityTask> items, String nextBefore) {}
    record Executor(String executorAccountId, long expected, long accepted, long differences, long notObserved) {}
    enum ExecutorSource { CURRENT, SEALED, RETAINED, MISSING }
    record ExecutorBreakdown(String taskId, ExecutorSource source, long expected, long counted, List<Executor> items) {}
    record Progress(String taskId, String name, String status, Instant dueAt, long expected, long accepted) {}
    record ProgressPage(List<Progress> items, String nextBefore) {}

    record Task(String taskId, String name, Instant sealedAt, long expected, long accepted,
                long notObserved, long differenceEvidence, long conflictEvidence) {}
    record Page(List<Task> items, String nextBefore) {}
    static final class ExportLimit extends RuntimeException {}
}
