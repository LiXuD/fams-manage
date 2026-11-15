package com.fams.report;

import com.fams.auth.AssetActorContext;
import com.fams.assetimport.AssetImportWorkbook;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("fams-product")
class AssetMovementReportService {
    private static final int EXPORT_LIMIT = 10_000;
    private final JdbcTemplate db;
    private final Clock clock;

    AssetMovementReportService(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    Page list(Filter filter, Cursor before, int pageSize) {
        var rows = query(filter, before, pageSize + 1);
        if (rows.size() <= pageSize) {
            return new Page(rows, null);
        }
        var last = rows.get(pageSize - 1);
        return new Page(List.copyOf(rows.subList(0, pageSize)),
                new Cursor(last.occurredAt(), last.factId()));
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
                VALUES (?,?,?,'OPERATIONS_REPORT_EXPORTED','ASSET_MOVEMENT_REPORT',0,?,
                    JSON_OBJECT('kind',?,'from',?,'toExclusive',?,'assetId',?,'rows',?))
                """, actor.requestId(), actor.id(), actor.role(), Timestamp.from(clock.instant()),
                filter.kind().name(), filter.from().toString(), filter.toExclusive().toString(),
                filter.assetId(), rows.size());
        return "\uFEFFkind,factId,businessId,assetId,assetCode,occurredAt,outcome\r\n"
                + rows.stream().map(row -> String.join(",", filter.kind().name(), row.factId(),
                        row.businessId(), row.assetId(), csv(row.assetCode()), row.occurredAt().toString(),
                        row.outcome())).collect(Collectors.joining("\r\n"));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<TrendMonth> trend() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        YearMonth first = YearMonth.from(today).minusMonths(5);
        LocalDate end = today.plusDays(1);
        List<Kind> kinds = List.of(Kind.INBOUND, Kind.REQUISITION, Kind.TRANSFER, Kind.REPAIR, Kind.SCRAP);
        Map<Kind, Map<YearMonth, Long>> counts = kinds.stream().collect(Collectors.toMap(kind -> kind,
                kind -> db.query("""
                        SELECT EXTRACT(YEAR_MONTH FROM f.occurred_at) AS period, COUNT(*) AS total
                        FROM (
                        """ + kind.sql + """
                        ) f JOIN fams_asset a ON a.id=f.asset_id
                        WHERE f.occurred_at>=? AND f.occurred_at<?
                        GROUP BY EXTRACT(YEAR_MONTH FROM f.occurred_at)
                        """, (row, index) -> {
                            int period = row.getInt("period");
                            return Map.entry(YearMonth.of(period / 100, period % 100), row.getLong("total"));
                        }, utcStart(first.atDay(1)), utcStart(end))
                        .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))));
        return IntStream.range(0, 6).mapToObj(first::plusMonths)
                .map(month -> new TrendMonth(month.atDay(1), month.equals(YearMonth.from(today))
                        ? end : month.plusMonths(1).atDay(1),
                        kinds.stream().map(kind -> new TrendCount(kind,
                                counts.get(kind).getOrDefault(month, 0L))).toList()))
                .toList();
    }

    private static Timestamp utcStart(LocalDate date) {
        return Timestamp.from(date.atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    private List<Row> query(Filter filter, Cursor before, int limit) {
        Timestamp cursorTime = before == null ? null : Timestamp.from(before.occurredAt());
        Long cursorId = before == null ? null : Long.valueOf(before.factId());
        return db.query("""
                SELECT f.fact_id, f.business_id, f.asset_id, a.asset_code, f.occurred_at, f.outcome
                FROM (
                """ + filter.kind().sql + """
                ) f JOIN fams_asset a ON a.id = f.asset_id
                WHERE f.occurred_at >= ? AND f.occurred_at < ?
                    AND (? IS NULL OR f.asset_id = ?)
                    AND (? IS NULL OR f.occurred_at < ? OR (f.occurred_at = ? AND f.fact_id < ?))
                ORDER BY f.occurred_at DESC, f.fact_id DESC LIMIT ?
                """, (row, index) -> new Row(row.getString("fact_id"), row.getString("business_id"),
                row.getString("asset_id"), row.getString("asset_code"),
                row.getTimestamp("occurred_at").toInstant(), row.getString("outcome")),
                Timestamp.from(filter.from().atStartOfDay().toInstant(ZoneOffset.UTC)),
                Timestamp.from(filter.toExclusive().atStartOfDay().toInstant(ZoneOffset.UTC)),
                filter.assetId(), filter.assetId(), cursorTime, cursorTime, cursorTime, cursorId, limit);
    }

    private static String csv(String value) {
        return "\"" + AssetImportWorkbook.safeExportCell(value).replace("\"", "\"\"") + "\"";
    }

    private static final String HANDOVER = """
            SELECT e.id AS fact_id, r.id AS business_id, a.asset_id, e.occurred_at, e.event_type AS outcome
            FROM fams_requisition_handover_event e
            JOIN fams_requisition_allocation a ON a.id = e.allocation_id
            JOIN fams_requisition r ON r.id = a.request_id
            WHERE e.event_type IN ('RECEIVED','PROXY_RECEIVED') AND r.request_type =
            """;

    private static final String RETURN = """
            SELECT id AS fact_id, id AS business_id, asset_id, completed_at AS occurred_at,
                inspection_result AS outcome
            FROM fams_asset_return
            WHERE inspection_result IN ('NORMAL','DAMAGED','MISSING') AND loan_id IS
            """;

    enum Kind {
        INBOUND("""
                SELECT a.id AS fact_id, i.receipt_id AS business_id, a.asset_id,
                    a.accepted_at AS occurred_at, 'ACCEPTED' AS outcome
                FROM fams_inbound_acceptance a JOIN fams_inbound_receipt_item i ON i.id = a.receipt_item_id
                """),
        REQUISITION(HANDOVER + " 'REQUISITION'"),
        BORROW(HANDOVER + " 'BORROW'"),
        REQUISITION_RETURN(RETURN + " NULL"),
        BORROW_RETURN(RETURN + " NOT NULL"),
        TRANSFER("""
                SELECT id AS fact_id, request_id AS business_id, asset_id, completed_at AS occurred_at,
                    'COMPLETED' AS outcome
                FROM fams_transfer_item WHERE item_status = 'COMPLETED'
                """),
        REPAIR("""
                SELECT e.id AS fact_id,r.id AS business_id,r.asset_id,e.occurred_at,'SUBMITTED' AS outcome
                FROM fams_repair_event e JOIN fams_repair_request r ON r.id=e.repair_id
                WHERE e.action IN ('REPAIR_SUBMITTED','REPAIR_RETURN_SOURCE_SUBMITTED')
                """),
        SCRAP("""
                SELECT e.id AS fact_id,r.id AS business_id,r.asset_id,e.occurred_at,'EXECUTED' AS outcome
                FROM fams_audit_event e JOIN fams_scrap_request r ON r.id=e.resource_id
                WHERE e.resource_type='SCRAP' AND e.action='SCRAP_EXECUTED'
                """);

        private final String sql;

        Kind(String sql) {
            this.sql = sql;
        }
    }

    record Filter(Kind kind, LocalDate from, LocalDate toExclusive, Long assetId) {
        boolean valid() {
            return from.getYear() >= 1000 && toExclusive.getYear() <= 9999
                    && from.isBefore(toExclusive) && ChronoUnit.DAYS.between(from, toExclusive) <= 366;
        }
    }

    record TrendMonth(LocalDate from, LocalDate toExclusive, List<TrendCount> counts) {}
    record TrendCount(Kind kind, long count) {}

    record Cursor(Instant occurredAt, String factId) {}
    record Page(List<Row> items, Cursor nextBefore) {}
    record Row(String factId, String businessId, String assetId, String assetCode, Instant occurredAt, String outcome) {}

    static final class ExportLimit extends RuntimeException {}
}
