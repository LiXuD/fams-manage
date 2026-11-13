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
class BusinessExceptionReportService {
    private static final int EXPORT_LIMIT = 10_000;
    private final JdbcTemplate db;
    private final Clock clock;

    BusinessExceptionReportService(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    Page list(Filter filter, Long before, int pageSize) {
        var rows = query(filter, before, pageSize + 1);
        if (rows.size() <= pageSize) {
            return new Page(rows, null);
        }
        return new Page(List.copyOf(rows.subList(0, pageSize)), rows.get(pageSize - 1).recordId());
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
                VALUES (?,?,?,'OPERATIONS_REPORT_EXPORTED','BUSINESS_EXCEPTION_REPORT',0,?,
                    JSON_OBJECT('kind',?,'businessId',?,'status',?,'rows',?))
                """, actor.requestId(), actor.id(), actor.role(), Timestamp.from(clock.instant()),
                filter.kind().name(), filter.businessId(), filter.status(), rows.size());
        return "\uFEFFkind,recordId,businessType,businessId,assetCode,status,registeredAt,processedAt\r\n"
                + rows.stream().map(row -> String.join(",", filter.kind().name(), row.recordId(),
                        csv(row.businessType()), row.businessId(), csv(row.assetCode()), row.status(),
                        row.registeredAt().toString(), row.processedAt() == null ? "" : row.processedAt().toString()))
                .collect(Collectors.joining("\r\n"));
    }

    private List<Row> query(Filter filter, Long before, int limit) {
        return db.query("SELECT * FROM (" + filter.kind().sql + """
                ) f WHERE (? IS NULL OR f.business_id=?)
                    AND (? IS NULL OR f.fact_status=?)
                    AND (? IS NULL OR f.id<?)
                ORDER BY f.id DESC LIMIT ?
                """, (row, index) -> {
                    var processed = row.getTimestamp("processed_at");
                    return new Row(row.getString("id"), row.getString("business_type"), row.getString("business_id"),
                            row.getString("asset_code"), row.getString("fact_status"),
                            row.getTimestamp("registered_at").toInstant(), processed == null ? null : processed.toInstant());
                }, filter.businessId(), filter.businessId(), filter.status(), filter.status(), before, before, limit);
    }

    private static String csv(String value) {
        return value == null ? "" : "\"" + AssetImportWorkbook.safeExportCell(value).replace("\"", "\"\"") + "\"";
    }

    enum Kind {
        PROXY_CONFIRMATION("""
                SELECT id,business_type,business_id,observed_asset_code AS asset_code,
                    confirmation_status AS fact_status,submitted_at AS registered_at,executed_at AS processed_at
                FROM fams_proxy_confirmation
                WHERE submitted_at IS NOT NULL AND business_type IN ('REQUISITION','TRANSFER','REPAIR')
                """),
        SCRAP_REVERSAL("""
                SELECT r.id,'SCRAP' AS business_type,r.request_id AS business_id,a.asset_code,
                    r.reversal_status AS fact_status,r.created_at AS registered_at,
                    CASE WHEN r.reversal_status='COMPLETED' THEN r.updated_at END AS processed_at
                FROM fams_scrap_reversal r JOIN fams_asset a ON a.id=r.asset_id
                """),
        SCRAP_RECOVERY("""
                SELECT r.id,'SCRAP' AS business_type,r.request_id AS business_id,a.asset_code,
                    r.recovery_status AS fact_status,r.created_at AS registered_at,
                    CASE WHEN r.recovery_status='COMPLETED' THEN r.updated_at END AS processed_at
                FROM fams_scrap_recovery r JOIN fams_asset a ON a.id=r.asset_id
                """);

        private final String sql;

        Kind(String sql) {
            this.sql = sql;
        }
    }

    record Filter(Kind kind, Long businessId, String status) {}
    record Page(List<Row> items, String nextBefore) {}
    record Row(String recordId, String businessType, String businessId, String assetCode,
               String status, Instant registeredAt, Instant processedAt) {}
    static final class ExportLimit extends RuntimeException {}
}
