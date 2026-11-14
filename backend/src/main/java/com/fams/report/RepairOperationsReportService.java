package com.fams.report;

import com.fams.assetimport.AssetImportWorkbook;
import com.fams.auth.AssetActorContext;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("fams-product")
class RepairOperationsReportService {
    private static final int EXPORT_LIMIT = 10_000;
    private final JdbcTemplate db;
    private final Clock clock;

    RepairOperationsReportService(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(Filter filter, Long before, int pageSize) {
        var totals = db.queryForObject("SELECT COUNT(*),COUNT(f.actual_cost),SUM(f.actual_cost)" + facts(filter.kind()),
                (row, index) -> new Totals(row.getLong(1), row.getLong(2), row.getBigDecimal(3)),
                filter.start(), filter.end(), filter.assetId(), filter.assetId());
        var rows = query(filter, before, pageSize + 1);
        if (rows.size() <= pageSize) {
            return new Page(totals, rows, null);
        }
        return new Page(totals, List.copyOf(rows.subList(0, pageSize)), rows.get(pageSize - 1).factId());
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
                VALUES (?,?,?,'OPERATIONS_REPORT_EXPORTED','REPAIR_OPERATIONS_REPORT',0,?,
                    JSON_OBJECT('kind',?,'from',?,'toExclusive',?,'assetId',?,'rows',?))
                """, actor.requestId(), actor.id(), actor.role(), Timestamp.from(clock.instant()),
                filter.kind().name(), filter.from().toString(), filter.toExclusive().toString(),
                filter.assetId(), rows.size());
        return "\uFEFFkind,factId,repairId,assetId,assetCode,occurredAt,status,actualCost\r\n"
                + rows.stream().map(row -> String.join(",", filter.kind().name(), row.factId(),
                        row.repairId(), row.assetId(), csv(row.assetCode()), row.occurredAt().toString(),
                        row.status(), row.actualCost() == null ? "" : row.actualCost().toPlainString()))
                .collect(Collectors.joining("\r\n"));
    }

    private List<Row> query(Filter filter, Long before, int limit) {
        return db.query("""
                SELECT f.fact_id,r.id AS repair_id,r.asset_id,a.asset_code,f.occurred_at,
                    f.fact_status,f.actual_cost
                """ + facts(filter.kind()) + " AND (? IS NULL OR f.fact_id < ?) ORDER BY f.fact_id DESC LIMIT ?",
                (row, index) -> new Row(row.getString("fact_id"), row.getString("repair_id"),
                        row.getString("asset_id"), row.getString("asset_code"),
                        row.getTimestamp("occurred_at").toInstant(), row.getString("fact_status"),
                        row.getBigDecimal("actual_cost")),
                filter.start(), filter.end(), filter.assetId(), filter.assetId(), before, before, limit);
    }

    private static String facts(Kind kind) {
        return " FROM (" + kind.sql + """
                ) f JOIN fams_repair_request r ON r.id=f.repair_id JOIN fams_asset a ON a.id=r.asset_id
                WHERE f.occurred_at>=? AND f.occurred_at<? AND (? IS NULL OR r.asset_id=?)
                """;
    }

    private static String csv(String value) {
        return "\"" + AssetImportWorkbook.safeExportCell(value).replace("\"", "\"\"") + "\"";
    }

    enum Kind {
        INTERNAL("""
                SELECT e.id AS fact_id,e.repair_id,e.occurred_at,e.action AS fact_status,NULL AS actual_cost
                FROM fams_repair_event e WHERE e.action IN ('REPAIR_ACCEPTED_IN_PLACE','REPAIR_ACCEPTED_AWAY')
                """),
        FACTORY("""
                SELECT f.id AS fact_id,f.repair_id,f.dispatched_at AS occurred_at,
                    f.round_status AS fact_status,f.actual_cost
                FROM fams_repair_factory_round f WHERE f.dispatched_at IS NOT NULL
                """);

        private final String sql;

        Kind(String sql) {
            this.sql = sql;
        }
    }

    record Filter(Kind kind, LocalDate from, LocalDate toExclusive, Long assetId) {
        boolean valid() {
            return from.isBefore(toExclusive) && ChronoUnit.DAYS.between(from, toExclusive) <= 366;
        }

        Timestamp start() {
            return Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC));
        }

        Timestamp end() {
            return Timestamp.from(toExclusive.atStartOfDay().toInstant(ZoneOffset.UTC));
        }
    }
    record Totals(long count, long confirmedCostCount, BigDecimal actualCost) {}
    record Page(Totals totals, List<Row> items, String nextBefore) {}
    record Row(String factId, String repairId, String assetId, String assetCode,
               Instant occurredAt, String status, BigDecimal actualCost) {}
    static final class ExportLimit extends RuntimeException {}
}
