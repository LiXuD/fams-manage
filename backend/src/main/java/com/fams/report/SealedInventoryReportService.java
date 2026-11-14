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
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("fams-product")
class SealedInventoryReportService {
    private static final int EXPORT_LIMIT = 10_000;
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

    record Task(String taskId, String name, Instant sealedAt, long expected, long accepted,
                long notObserved, long differenceEvidence, long conflictEvidence) {}
    record Page(List<Task> items, String nextBefore) {}
    static final class ExportLimit extends RuntimeException {}
}
