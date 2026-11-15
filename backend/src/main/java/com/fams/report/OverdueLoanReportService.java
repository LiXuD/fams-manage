package com.fams.report;

import com.fams.assetimport.AssetImportWorkbook;
import com.fams.auth.AssetActorContext;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("fams-product")
class OverdueLoanReportService {
    private static final int EXPORT_LIMIT = 10_000;
    private static final int SCAN_BATCH_SIZE = 250;
    private static final String CANDIDATES = """
            SELECT l.id, l.asset_id, a.asset_code, e.name AS borrower, o.name AS organization,
                l.expected_return_date, JSON_UNQUOTE(JSON_EXTRACT(l.parameter_snapshot_json, '$.timezone')) AS timezone
            FROM fams_loan l
            JOIN fams_asset a ON a.id = l.asset_id
            JOIN fams_employee e ON e.id = l.borrower_employee_id
            JOIN fams_organization o ON o.id = l.using_organization_id
            WHERE l.relation_status = 'OPEN' AND a.lifecycle_status <> 'SCRAPPED' AND a.exception_status <> 'LOST'
                AND l.expected_return_date < ?
                AND (? IS NULL OR l.using_organization_id = ?)
                AND (? IS NULL OR l.borrower_employee_id = ?)
                AND (? IS NULL OR l.id < ?)
            ORDER BY l.id DESC LIMIT ?
            """;

    private final JdbcTemplate db;
    private final Clock clock;

    OverdueLoanReportService(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(Filter filter, Long before, int pageSize) {
        Instant now = clock.instant();
        var rows = query(filter, before, pageSize + 1, now);
        if (rows.size() <= pageSize) {
            return new Page(now, rows, null);
        }
        return new Page(now, List.copyOf(rows.subList(0, pageSize)), rows.get(pageSize - 1).loanId());
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public String export(AssetActorContext.Actor actor, Filter filter) {
        Instant now = clock.instant();
        var rows = query(filter, null, EXPORT_LIMIT + 1, now);
        if (rows.size() > EXPORT_LIMIT) {
            throw new ExportLimit();
        }
        db.update("""
                INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,action,resource_type,
                    resource_id,occurred_at,detail_json)
                VALUES (?,?,?,'OPERATIONS_REPORT_EXPORTED','OVERDUE_LOAN_REPORT',0,?,
                    JSON_OBJECT('organizationId',?,'borrowerId',?,'rows',?))
                """, actor.requestId(), actor.id(), actor.role(), Timestamp.from(now),
                filter.organizationId(), filter.borrowerId(), rows.size());
        return "\uFEFFloanId,assetId,assetCode,borrower,organization,expectedReturnDate,businessDate,overdueDays\r\n"
                + rows.stream().map(row -> String.join(",", row.loanId(), row.assetId(), csv(row.assetCode()),
                        csv(row.borrower()), csv(row.organization()), row.expectedReturnDate().toString(),
                        row.businessDate().toString(), Long.toString(row.overdueDays())))
                .collect(Collectors.joining("\r\n"));
    }

    private List<Row> query(Filter filter, Long before, int limit, Instant now) {
        // No loan's local date can be later than tomorrow in UTC. Apply its exact zone before paging.
        LocalDate latestBusinessDate = now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1);
        var matches = new ArrayList<Row>();
        Long cursor = before;
        while (matches.size() < limit) {
            var candidates = db.query(CANDIDATES, (row, index) -> {
                LocalDate due = row.getObject("expected_return_date", LocalDate.class);
                LocalDate today = now.atZone(ZoneId.of(row.getString("timezone"))).toLocalDate();
                return new Row(row.getString("id"), row.getString("asset_id"), row.getString("asset_code"),
                        row.getString("borrower"), row.getString("organization"), due, today,
                        ChronoUnit.DAYS.between(due, today));
            }, latestBusinessDate, filter.organizationId(), filter.organizationId(),
                    filter.borrowerId(), filter.borrowerId(), cursor, cursor, SCAN_BATCH_SIZE);
            for (var row : candidates) {
                if (row.overdueDays() > 0) {
                    matches.add(row);
                    if (matches.size() == limit) {
                        return matches;
                    }
                }
            }
            if (candidates.size() < SCAN_BATCH_SIZE) {
                return matches;
            }
            cursor = Long.valueOf(candidates.getLast().loanId());
        }
        return matches;
    }

    private static String csv(String value) {
        return "\"" + AssetImportWorkbook.safeExportCell(value).replace("\"", "\"\"") + "\"";
    }

    record Filter(Long organizationId, Long borrowerId) {}
    record Page(Instant calculatedAt, List<Row> items, String nextBefore) {}
    record Row(String loanId, String assetId, String assetCode, String borrower, String organization,
               LocalDate expectedReturnDate, LocalDate businessDate, long overdueDays) {}
    static final class ExportLimit extends RuntimeException {}
}
