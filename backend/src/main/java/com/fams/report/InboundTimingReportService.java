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
class InboundTimingReportService {
    private static final int EXPORT_LIMIT = 10_000;
    private final JdbcTemplate db;
    private final Clock clock;

    InboundTimingReportService(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    Page list(Filter filter, Long before, int pageSize) {
        var rows = query(filter, before, pageSize + 1);
        if (rows.size() <= pageSize) {
            return new Page(rows, null);
        }
        return new Page(List.copyOf(rows.subList(0, pageSize)), rows.get(pageSize - 1).factId());
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
                VALUES (?,?,?,'OPERATIONS_REPORT_EXPORTED','INBOUND_TIMING_REPORT',0,?,
                    JSON_OBJECT('kind',?,'receiptId',?,'status',?,'rows',?))
                """, actor.requestId(), actor.id(), actor.role(), Timestamp.from(clock.instant()),
                filter.kind().name(), filter.receiptId(), filter.status(), rows.size());
        return "\uFEFFkind,factId,receiptNo,receiptId,processorAccountId,status,startedAt,finishedAt,elapsedSeconds\r\n"
                + rows.stream().map(row -> String.join(",", filter.kind().name(), row.factId(),
                        csv(row.receiptNo()), row.receiptId(), csv(row.processorAccountId()), row.status(),
                        row.startedAt() == null ? "" : row.startedAt().toString(), row.finishedAt().toString(),
                        row.elapsedSeconds() == null ? "" : row.elapsedSeconds().toString()))
                .collect(Collectors.joining("\r\n"));
    }

    private List<Row> query(Filter filter, Long before, int limit) {
        return db.query("SELECT * FROM (" + filter.kind().sql + """
                ) f WHERE (? IS NULL OR f.receipt_id=?)
                    AND (? IS NULL OR f.stage_result=?)
                    AND (? IS NULL OR f.id<?)
                ORDER BY f.id DESC LIMIT ?
                """, (row, index) -> {
                    var started = row.getTimestamp("started_at");
                    var finished = row.getTimestamp("finished_at");
                    return new Row(row.getString("id"), row.getString("receipt_no"), row.getString("receipt_id"),
                            row.getString("processor_account_id"), row.getString("stage_result"),
                            started == null ? null : started.toInstant(), finished.toInstant(),
                            started == null || finished.before(started) ? null
                                    : java.time.Duration.between(started.toInstant(), finished.toInstant()).toSeconds());
                }, filter.receiptId(), filter.receiptId(), filter.status(), filter.status(), before, before, limit);
    }

    private static String csv(String value) {
        return value == null ? "" : "\"" + AssetImportWorkbook.safeExportCell(value).replace("\"", "\"\"") + "\"";
    }

    enum Kind {
        APPROVAL("""
                SELECT d.id,r.id AS receipt_id,r.receipt_no,NULL AS processor_account_id,
                    d.decision_result AS stage_result,r.submitted_at AS started_at,d.decided_at AS finished_at
                FROM fams_inbound_decision d JOIN fams_inbound_receipt r ON r.id=d.receipt_id
                """),
        CLAIM("""
                SELECT a.id,r.id AS receipt_id,r.receipt_no,CAST(a.actor_account_id AS CHAR) AS processor_account_id,
                    'CLAIMED' AS stage_result,d.decided_at AS started_at,a.occurred_at AS finished_at
                FROM fams_audit_event a JOIN fams_inbound_receipt r ON r.id=a.resource_id
                JOIN fams_inbound_decision d ON d.receipt_id=r.id AND d.decision_result='APPROVED'
                WHERE a.resource_type='INBOUND' AND a.action='INBOUND_WAREHOUSE_CLAIMED'
                """),
        FIRST_EXECUTION("""
                SELECT r.id,r.id AS receipt_id,r.receipt_no,CAST(a.accepted_by_account_id AS CHAR) AS processor_account_id,
                    'ACCEPTED' AS stage_result,o.acquired_at AS started_at,a.accepted_at AS finished_at
                FROM fams_inbound_receipt r
                JOIN fams_inbound_receipt_item i ON i.receipt_id=r.id
                JOIN fams_inbound_acceptance a ON a.receipt_item_id=i.id
                JOIN fams_inbound_processing_ownership o ON o.id=a.ownership_id
                WHERE a.id=(
                    SELECT aa.id FROM fams_inbound_receipt_item ii
                    JOIN fams_inbound_acceptance aa ON aa.receipt_item_id=ii.id
                    WHERE ii.receipt_id=r.id ORDER BY aa.accepted_at,aa.id LIMIT 1
                )
                """),
        REASSIGNMENT("""
                SELECT a.id,r.id AS receipt_id,r.receipt_no,
                    JSON_UNQUOTE(JSON_EXTRACT(a.detail_json,'$.processorAccountId')) AS processor_account_id,
                    'REASSIGNED' AS stage_result,NULL AS started_at,a.occurred_at AS finished_at
                FROM fams_audit_event a JOIN fams_inbound_receipt r ON r.id=a.resource_id
                WHERE a.resource_type='INBOUND' AND a.action='INBOUND_CONTROLLED_REASSIGNMENT'
                """);

        private final String sql;

        Kind(String sql) {
            this.sql = sql;
        }
    }

    record Filter(Kind kind, Long receiptId, String status) {}
    record Page(List<Row> items, String nextBefore) {}
    record Row(String factId, String receiptNo, String receiptId, String processorAccountId,
               String status, Instant startedAt, Instant finishedAt, Long elapsedSeconds) {}
    static final class ExportLimit extends RuntimeException {}
}
