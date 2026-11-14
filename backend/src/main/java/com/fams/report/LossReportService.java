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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("fams-product")
class LossReportService {
    private static final int EXPORT_LIMIT = 10_000;
    private static final String FACTS = """
            FROM fams_loss_report l JOIN fams_asset a ON a.id = l.asset_id
            LEFT JOIN fams_organization o ON o.id = l.using_organization_id
            WHERE (l.report_status IN ('SEARCHING','CONFIRMED_LOST','RECOVERED') OR l.recovered_at IS NOT NULL)
              AND (? IS NULL OR l.using_organization_id = ?)
            """;
    private static final String STATE = """
            CASE WHEN l.recovered_at IS NOT NULL THEN 'RECOVERED' ELSE l.report_status END
            """;
    private final JdbcTemplate db;
    private final Clock clock;

    LossReportService(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(Filter filter, Long before, int pageSize) {
        var counts = db.query("SELECT " + STATE + " AS state, COUNT(*) AS total " + FACTS
                        + " GROUP BY state ORDER BY state",
                (row, index) -> new Count(State.valueOf(row.getString("state")), row.getLong("total")),
                filter.organizationId(), filter.organizationId());
        var rows = query(filter, before, pageSize + 1);
        if (rows.size() <= pageSize) {
            return new Page(counts, rows, null);
        }
        return new Page(counts, List.copyOf(rows.subList(0, pageSize)), rows.get(pageSize - 1).reportId());
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public String export(AssetActorContext.Actor actor, Filter filter) {
        var rows = query(filter, null, EXPORT_LIMIT + 1);
        if (rows.size() > EXPORT_LIMIT) {
            throw new ExportLimit();
        }
        db.update("""
                INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,action,resource_type,
                    resource_id,occurred_at,detail_json)
                VALUES (?,?,?,'OPERATIONS_REPORT_EXPORTED','LOSS_REPORT_EXPORT',0,?,
                    JSON_OBJECT('organizationId',?,'state',?,'rows',?))
                """, actor.requestId(), actor.id(), actor.role(), Timestamp.from(clock.instant()),
                filter.organizationId(), filter.stateName(), rows.size());
        return "\uFEFFreportId,assetId,assetCode,organization,state,reportedAt,confirmedLostAt,recoveredAt\r\n"
                + rows.stream().map(row -> String.join(",", row.reportId(), row.assetId(), csv(row.assetCode()),
                        csv(row.organization()), row.state().name(), row.reportedAt().toString(),
                        nullableInstant(row.confirmedLostAt()), nullableInstant(row.recoveredAt())))
                .collect(Collectors.joining("\r\n"));
    }

    private List<Row> query(Filter filter, Long before, int limit) {
        return db.query("""
                SELECT l.id, l.asset_id, a.asset_code, o.name AS organization,
                    l.created_at, l.confirmed_lost_at, l.recovered_at,
                """ + STATE + " AS state " + FACTS + " AND (? IS NULL OR (" + STATE + ") = ?)"
                + " AND (? IS NULL OR l.id < ?) ORDER BY l.id DESC LIMIT ?", (row, index) -> {
                    var confirmed = row.getTimestamp("confirmed_lost_at");
                    var recovered = row.getTimestamp("recovered_at");
                    return new Row(row.getString("id"), row.getString("asset_id"), row.getString("asset_code"),
                            row.getString("organization"), State.valueOf(row.getString("state")),
                            row.getTimestamp("created_at").toInstant(), confirmed == null ? null : confirmed.toInstant(),
                            recovered == null ? null : recovered.toInstant());
                }, filter.organizationId(), filter.organizationId(), filter.stateName(), filter.stateName(),
                before, before, limit);
    }

    private static String csv(String value) {
        return value == null ? "" : "\"" + AssetImportWorkbook.safeExportCell(value).replace("\"", "\"\"") + "\"";
    }

    private static String nullableInstant(Instant value) {
        return value == null ? "" : value.toString();
    }

    enum State { SEARCHING, CONFIRMED_LOST, RECOVERED }
    record Filter(Long organizationId, State state) {
        String stateName() {
            return state == null ? null : state.name();
        }
    }
    record Count(State state, long count) {}
    record Page(List<Count> counts, List<Row> items, String nextBefore) {}
    record Row(String reportId, String assetId, String assetCode, String organization, State state,
               Instant reportedAt, Instant confirmedLostAt, Instant recoveredAt) {}
    static final class ExportLimit extends RuntimeException {}
}
