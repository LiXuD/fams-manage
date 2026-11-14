package com.fams.report;

import com.fams.assetimport.AssetImportWorkbook;
import com.fams.auth.AssetActorContext;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("fams-product")
class InventoryLateReportService {
    private static final int EXPORT_LIMIT = 10_000;
    private final JdbcTemplate db;
    private final Clock clock;

    InventoryLateReportService(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    Page list(Filter filter, Long before, int pageSize) {
        var rows = query(filter, before, pageSize + 1);
        if (rows.size() <= pageSize) {
            return new Page(rows, null);
        }
        return new Page(List.copyOf(rows.subList(0, pageSize)), rows.get(pageSize - 1).observationId());
    }

    @Transactional
    public String export(AssetActorContext.Actor actor, Filter filter) {
        var rows = query(filter, null, EXPORT_LIMIT + 1);
        if (rows.size() > EXPORT_LIMIT) {
            throw new ExportLimit();
        }
        db.update("""
                INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,action,resource_type,
                    resource_id,occurred_at,detail_json)
                VALUES (?,?,?,'OPERATIONS_REPORT_EXPORTED','INVENTORY_LATE_REPORT',0,?,
                    JSON_OBJECT('taskId',?,'result',?,'rows',?))
                """, actor.requestId(), actor.id(), actor.role(), Timestamp.from(clock.instant()),
                filter.taskId(), filter.resultName(), rows.size());
        return "\uFEFFobservationId,taskId,taskName,assetCode,receivedAt,comparison,result,linkedTaskId,linkedTaskStatus\r\n"
                + rows.stream().map(row -> String.join(",", row.observationId(), row.taskId(), csv(row.taskName()),
                        csv(row.assetCode()), row.receivedAt().toString(), row.comparison(), row.result().name(),
                        csv(row.linkedTaskId()), csv(row.linkedTaskStatus()))).collect(Collectors.joining("\r\n"));
    }

    private List<Row> query(Filter filter, Long before, int limit) {
        return db.query("""
                SELECT c.observation_id,c.task_id,t.name AS task_name,o.asset_code,o.received_at,c.comparison_kind,
                    COALESCE(d.result,IF(c.needs_review,'OPEN','INFORMATION')) AS result,
                    d.linked_task_id,recheck.task_status AS linked_task_status
                FROM fams_inventory_late_case c
                JOIN fams_inventory_task t ON t.id=c.task_id
                JOIN fams_inventory_observation o ON o.id=c.observation_id
                LEFT JOIN fams_inventory_late_decision d ON d.observation_id=c.observation_id
                    AND d.decision_version=(SELECT MAX(v.decision_version) FROM fams_inventory_late_decision v
                                            WHERE v.observation_id=c.observation_id)
                LEFT JOIN fams_inventory_task recheck ON recheck.id=d.linked_task_id
                WHERE (? IS NULL OR c.task_id=?)
                    AND (? IS NULL OR COALESCE(d.result,IF(c.needs_review,'OPEN','INFORMATION'))=?)
                    AND (? IS NULL OR c.observation_id<?)
                ORDER BY c.observation_id DESC LIMIT ?
                """, (row, index) -> new Row(row.getString("observation_id"), row.getString("task_id"),
                        row.getString("task_name"), row.getString("asset_code"), row.getTimestamp("received_at").toInstant(),
                        row.getString("comparison_kind"), Result.valueOf(row.getString("result")),
                        row.getString("linked_task_id"), row.getString("linked_task_status")),
                filter.taskId(), filter.taskId(), filter.resultName(), filter.resultName(), before, before, limit);
    }

    private static String csv(String value) {
        return value == null ? "" : "\"" + AssetImportWorkbook.safeExportCell(value).replace("\"", "\"\"") + "\"";
    }

    enum Result { OPEN, INFORMATION, REVIEWED, FOLLOWUP_OPEN }
    record Filter(Long taskId, Result result) {
        String resultName() {
            return result == null ? null : result.name();
        }
    }
    record Page(List<Row> items, String nextBefore) {}
    record Row(String observationId, String taskId, String taskName, String assetCode, Instant receivedAt,
               String comparison, Result result, String linkedTaskId, String linkedTaskStatus) {}
    static final class ExportLimit extends RuntimeException {}
}
