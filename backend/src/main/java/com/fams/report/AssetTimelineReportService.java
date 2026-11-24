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
class AssetTimelineReportService {
    private static final int EXPORT_LIMIT = 10_000;
    private final JdbcTemplate db;
    private final Clock clock;

    AssetTimelineReportService(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    Page list(Filter filter, Cursor before, int pageSize) {
        var rows = query(filter, before, pageSize + 1);
        if (rows.size() <= pageSize) {
            return new Page(rows, null);
        }
        var last = rows.get(pageSize - 1);
        return new Page(List.copyOf(rows.subList(0, pageSize)), new Cursor(last.occurredAt(), last.eventId()));
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
                VALUES (?,?,?,'OPERATIONS_REPORT_EXPORTED','ASSET_TIMELINE_REPORT',?,?,JSON_OBJECT('eventType',?,'rows',?))
                """, actor.requestId(), actor.id(), actor.role(), filter.assetId(), Timestamp.from(clock.instant()),
                filter.eventType(), rows.size());
        return "\uFEFFeventId,assetId,assetCode,eventType,occurredAt,businessType,businessId,factoryRoundId,summary\r\n"
                + rows.stream().map(row -> String.join(",", row.eventId(), Long.toString(filter.assetId()),
                        csv(row.assetCode()), csv(row.eventType()), row.occurredAt().toString(),
                        csv(row.businessType()), row.businessId(), csv(row.factoryRoundId()), csv(row.summary())))
                .collect(Collectors.joining("\r\n"));
    }

    private List<Row> query(Filter filter, Cursor before, int limit) {
        var time = before == null ? null : Timestamp.from(before.occurredAt());
        var id = before == null ? null : Long.valueOf(before.eventId());
        return db.query("""
                SELECT e.id,e.event_type,e.occurred_at,a.asset_code,
                    CASE WHEN f.id IS NULL THEN e.business_type ELSE 'REPAIR' END AS business_type,
                    COALESCE(f.repair_id,e.business_id) AS business_id, f.id AS factory_round_id,
                    CASE WHEN JSON_TYPE(JSON_EXTRACT(e.summary_json,'$.summary'))='STRING'
                         THEN JSON_UNQUOTE(JSON_EXTRACT(e.summary_json,'$.summary')) ELSE '' END AS summary
                FROM fams_asset_timeline_event e JOIN fams_asset a ON a.id=e.asset_id
                LEFT JOIN fams_repair_factory_round f
                    ON e.event_type IN ('REPAIR_FACTORY_DISPATCHED','REPAIR_FACTORY_ORIGINAL_RECEIVED')
                    AND f.id=JSON_VALUE(e.summary_json,'$.roundId' RETURNING UNSIGNED NULL ON ERROR)
                    AND f.repair_id=JSON_VALUE(e.summary_json,'$.repairId' RETURNING UNSIGNED NULL ON ERROR)
                    AND f.asset_id=e.asset_id
                WHERE e.asset_id=? AND a.card_stage IN ('ACTIVE','PENDING_INBOUND')
                    AND (? IS NULL OR e.event_type=?)
                    AND (? IS NULL OR e.occurred_at<? OR (e.occurred_at=? AND e.id<?))
                ORDER BY e.occurred_at DESC,e.id DESC LIMIT ?
                """, (row, index) -> new Row(row.getString("id"), row.getString("asset_code"),
                        row.getString("event_type"), row.getTimestamp("occurred_at").toInstant(),
                        row.getString("business_type"), row.getString("business_id"),
                        row.getString("factory_round_id"), row.getString("summary")),
                filter.assetId(), filter.eventType(), filter.eventType(), time, time, time, id, limit);
    }

    private static String csv(String value) {
        return "\"" + AssetImportWorkbook.safeExportCell(value).replace("\"", "\"\"") + "\"";
    }

    record Filter(long assetId, String eventType) {}
    record Cursor(Instant occurredAt, String eventId) {}
    record Page(List<Row> items, Cursor nextBefore) {}
    record Row(String eventId, String assetCode, String eventType, Instant occurredAt,
               String businessType, String businessId, String factoryRoundId, String summary) {}
    static final class ExportLimit extends RuntimeException {}
}
