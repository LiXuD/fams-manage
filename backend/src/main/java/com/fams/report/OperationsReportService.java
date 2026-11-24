package com.fams.report;

import com.fams.assetimport.AssetImportWorkbook;
import com.fams.auth.AssetActorContext;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("fams-product")
class OperationsReportService {
    private static final String INVENTORY = """
            FROM fams_inventory_sealed_report s
            JOIN fams_inventory_task t ON t.id = s.task_id
            WHERE s.created_at >= ? AND s.created_at < ?
            """;
    private final JdbcTemplate db;
    private final Clock clock;

    OperationsReportService(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Summary summary(LocalDate start, LocalDate end) {
        var from = start(start);
        var until = start(end);
        var inventory = db.query("""
                SELECT COUNT(*) AS tasks, COALESCE(SUM(s.expected_count),0) AS expected,
                    COALESCE(SUM(s.accepted_count),0) AS accepted, COALESCE(SUM(s.not_observed_count),0) AS not_observed
                """ + INVENTORY, (row, index) -> new Inventory(row.getLong("tasks"), row.getLong("expected"),
                row.getLong("accepted"), row.getLong("not_observed")), from, until).getFirst();
        var openings = db.query("""
                SELECT e.action, COUNT(DISTINCT e.batch_id) AS quantity
                FROM fams_asset_import_event e JOIN fams_asset_import_batch b ON b.id = e.batch_id
                WHERE e.occurred_at >= ? AND e.occurred_at < ?
                  AND ((e.action = 'ASSET_IMPORT_PUBLISHED' AND b.batch_status = 'PUBLISHED')
                     OR e.action = 'ASSET_IMPORT_ROLLED_BACK')
                GROUP BY e.action ORDER BY e.action
                """, (row, index) -> new Count(row.getString("action"), row.getLong("quantity")), from, until);
        var assets = db.query("""
                SELECT lifecycle_status, exception_status, COUNT(*) AS quantity FROM fams_asset
                WHERE card_stage = 'ACTIVE' AND lifecycle_status NOT IN ('WAITING_INBOUND','SCRAPPED')
                GROUP BY lifecycle_status, exception_status ORDER BY lifecycle_status, exception_status
                """, (row, index) -> new AssetCount(row.getString("lifecycle_status"),
                row.getString("exception_status"), row.getLong("quantity")));
        return new Summary(start, end, clock.instant().toString(), inventory, openings, assets,
                importErrorQuality(from, until));
    }

    private ImportErrorQuality importErrorQuality(Timestamp from, Timestamp until) {
        return db.queryForObject("""
                SELECT COUNT(*) AS failed_rows, COUNT(CASE
                    WHEN JSON_TYPE(r.errors_json) = 'ARRAY' AND JSON_LENGTH(r.errors_json) > 0
                     AND NOT EXISTS (
                        SELECT 1 FROM JSON_TABLE(r.errors_json, '$[*]' COLUMNS (
                            sheet_name VARCHAR(255) PATH '$.sheet',
                            sheet_row INT PATH '$.sheetRow',
                            field_name VARCHAR(255) PATH '$.column',
                            original_value JSON PATH '$.original',
                            reason VARCHAR(255) PATH '$.reason'
                        )) e
                        WHERE COALESCE(TRIM(e.sheet_name), '') = ''
                           OR COALESCE(e.sheet_row, 0) <> r.sheet_row
                           OR COALESCE(TRIM(e.field_name), '') = ''
                           OR COALESCE(JSON_TYPE(e.original_value), '') <> 'STRING'
                           OR COALESCE(TRIM(e.reason), '') = ''
                     ) THEN 1 END) AS located_rows
                FROM fams_asset_import_batch b JOIN fams_asset_import_row r ON r.batch_id = b.id
                WHERE b.created_at >= ? AND b.created_at < ? AND b.preview_version > 0 AND r.row_status = 'ERROR'
                """, (row, index) -> new ImportErrorQuality(
                row.getLong("located_rows"), row.getLong("failed_rows")), from, until);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public String exportInventory(AssetActorContext.Actor actor, LocalDate start, LocalDate end) {
        var rows = db.query("""
                SELECT s.task_id, s.created_at, s.expected_count, s.accepted_count, s.not_observed_count
                """ + INVENTORY + " ORDER BY s.created_at, s.task_id LIMIT 10001",
                (row, index) -> String.join(",", csv(row.getString("task_id")),
                        csv(row.getTimestamp("created_at").toInstant().toString()),
                        csv(row.getString("expected_count")),
                        csv(row.getString("accepted_count")), csv(row.getString("not_observed_count"))),
                start(start), start(end));
        if (rows.size() > 10000) {
            throw new IllegalArgumentException("REPORT_EXPORT_LIMIT");
        }
        db.update("""
                INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,action,resource_type,
                    resource_id,occurred_at,detail_json)
                VALUES (?,?,?,'OPERATIONS_REPORT_EXPORTED','INVENTORY_REPORT',0,?,
                    JSON_OBJECT('from',?,'toExclusive',?,'rows',?))
                """, actor.requestId(), actor.id(), actor.role(), Timestamp.from(clock.instant()),
                start.toString(), end.toString(), rows.size());
        return "taskId,sealedAt,expected,accepted,notObserved\r\n" + String.join("\r\n", rows);
    }

    private static Timestamp start(LocalDate date) {
        return Timestamp.from(date.atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    private static String csv(String value) {
        return value == null ? "" : "\"" + AssetImportWorkbook.safeExportCell(value).replace("\"", "\"\"") + "\"";
    }

    record Inventory(long sealedTasks, long expected, long accepted, long notObserved) {}
    record Count(String status, long count) {}
    record AssetCount(String lifecycle, String exception, long count) {}
    record ImportErrorQuality(long locatedRows, long failedRows) {}
    record Summary(LocalDate from, LocalDate toExclusive, String calculatedAt, Inventory inventory,
                   List<Count> openingBatches, List<AssetCount> currentAssets, ImportErrorQuality importErrorQuality) {}
}
