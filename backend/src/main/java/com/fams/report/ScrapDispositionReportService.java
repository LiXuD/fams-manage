package com.fams.report;

import com.fams.assetimport.AssetImportWorkbook;
import com.fams.auth.AssetActorContext;
import com.fams.masterdata.AssetMasterDataPort;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("fams-product")
class ScrapDispositionReportService {
    private static final int EXPORT_LIMIT = 10_000;
    private static final String FACTS = """
            FROM fams_scrap_disposition d JOIN fams_scrap_request r ON r.id=d.request_id
            JOIN fams_asset a ON a.id=r.asset_id
            JOIN fams_system_account processor ON processor.id=d.processor_account_id
            JOIN fams_employee employee ON employee.id=processor.employee_id
            WHERE r.request_status='EXECUTED' AND (? IS NULL OR d.processor_account_id=?)
            """;
    private static final String STATE = """
            CASE WHEN d.disposition_status='PENDING_DISPOSITION' AND d.due_date < ?
                 THEN 'OVERDUE' ELSE d.disposition_status END
            """;
    private final JdbcTemplate db;
    private final Clock clock;
    private final AssetMasterDataPort masterData;

    ScrapDispositionReportService(JdbcTemplate db, Clock clock, AssetMasterDataPort masterData) {
        this.db = db;
        this.clock = clock;
        this.masterData = masterData;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(Filter filter, Long before, int pageSize) {
        var date = LocalDate.ofInstant(clock.instant(), masterData.currentZone());
        var counts = db.query("SELECT " + STATE + " AS state, COUNT(*) AS total " + FACTS
                        + " GROUP BY state ORDER BY state",
                (row, index) -> new Count(State.valueOf(row.getString("state")), row.getLong("total")),
                date, filter.processorId(), filter.processorId());
        var rows = query(filter, date, before, pageSize + 1);
        if (rows.size() <= pageSize) {
            return new Page(date, counts, rows, null);
        }
        return new Page(date, counts, List.copyOf(rows.subList(0, pageSize)), rows.get(pageSize - 1).requestId());
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public String export(AssetActorContext.Actor actor, Filter filter) {
        var now = clock.instant();
        var date = LocalDate.ofInstant(now, masterData.currentZone());
        var rows = query(filter, date, null, EXPORT_LIMIT + 1);
        if (rows.size() > EXPORT_LIMIT) {
            throw new ExportLimit();
        }
        db.update("""
                INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,action,resource_type,
                    resource_id,occurred_at,detail_json)
                VALUES (?,?,?,'OPERATIONS_REPORT_EXPORTED','SCRAP_DISPOSITION_REPORT',0,?,
                    JSON_OBJECT('processorId',?,'state',?,'businessDate',?,'rows',?))
                """, actor.requestId(), actor.id(), actor.role(), Timestamp.from(now),
                filter.processorId(), filter.stateName(), date.toString(), rows.size());
        return "\uFEFFrequestId,assetId,assetCode,processor,state,dueDate,dispositionRecordedAt,businessDate\r\n"
                + rows.stream().map(row -> String.join(",", row.requestId(), row.assetId(), csv(row.assetCode()),
                        csv(row.processor()), row.state().name(), row.dueDate() == null ? "" : row.dueDate().toString(),
                        row.dispositionRecordedAt() == null ? "" : row.dispositionRecordedAt().toString(),
                        date.toString()))
                .collect(Collectors.joining("\r\n"));
    }

    private List<Row> query(Filter filter, LocalDate date, Long before, int limit) {
        return db.query("""
                SELECT r.id, r.asset_id, a.asset_code, employee.name AS processor, d.due_date,
                    CASE WHEN d.disposition_status='DISPOSED' THEN d.updated_at END AS disposition_recorded_at,
                """ + STATE + " AS state " + FACTS + " AND (? IS NULL OR (" + STATE + ") = ?)"
                + " AND (? IS NULL OR r.id < ?) ORDER BY r.id DESC LIMIT ?", (row, index) -> {
                    var recorded = row.getTimestamp("disposition_recorded_at");
                    return new Row(row.getString("id"), row.getString("asset_id"), row.getString("asset_code"),
                            row.getString("processor"), State.valueOf(row.getString("state")),
                            row.getObject("due_date", LocalDate.class), recorded == null ? null : recorded.toInstant());
                }, date, filter.processorId(), filter.processorId(), filter.stateName(), date, filter.stateName(),
                before, before, limit);
    }

    private static String csv(String value) {
        return value == null ? "" : "\"" + AssetImportWorkbook.safeExportCell(value).replace("\"", "\"\"") + "\"";
    }

    enum State { PENDING_DISPOSITION, OVERDUE, DISPOSED, LOSS_WRITTEN_OFF, REVERSED }
    record Filter(Long processorId, State state) {
        String stateName() {
            return state == null ? null : state.name();
        }
    }
    record Count(State state, long count) {}
    record Page(LocalDate businessDate, List<Count> counts, List<Row> items, String nextBefore) {}
    record Row(String requestId, String assetId, String assetCode, String processor, State state,
               LocalDate dueDate, Instant dispositionRecordedAt) {}
    static final class ExportLimit extends RuntimeException {}
}
