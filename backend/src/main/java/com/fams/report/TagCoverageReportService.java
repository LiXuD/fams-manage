package com.fams.report;

import com.fams.assetimport.AssetImportWorkbook;
import com.fams.auth.AssetActorContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("fams-product")
class TagCoverageReportService {
    private static final int EXPORT_LIMIT = 10000;
    private static final String EVIDENCE = """
            SELECT a.id, t.tag_version, p.confirmed_at, g.recognized_at, f.task_id
            FROM fams_asset a
            LEFT JOIN fams_asset_tag t ON t.asset_id=a.id
            LEFT JOIN fams_asset_tag_placement p ON p.asset_id=t.asset_id
                AND p.tag_version=t.tag_version AND p.tag_issued_at=t.issued_at
            LEFT JOIN fams_inventory_sealed_item f ON f.asset_id=a.id AND f.task_id=(
                SELECT x.task_id FROM fams_inventory_sealed_item x
                JOIN fams_inventory_sealed_report s ON s.task_id=x.task_id
                WHERE x.asset_id=a.id ORDER BY s.created_at DESC,s.task_id DESC LIMIT 1
            )
            LEFT JOIN fams_inventory_tag_recognition g ON g.item_id=f.item_id
                AND g.recheck_generation=f.recheck_generation
                AND g.tag_version=t.tag_version AND g.tag_issued_at=t.issued_at
            WHERE a.card_stage='ACTIVE' AND a.lifecycle_status NOT IN ('WAITING_INBOUND','SCRAPPED')
            """;
    private final JdbcTemplate db;
    private final Clock clock;

    TagCoverageReportService(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Summary summary() {
        return db.queryForObject("""
                SELECT COUNT(*) total,COALESCE(SUM(confirmed_at IS NOT NULL OR recognized_at IS NOT NULL),0) covered,
                    COALESCE(SUM(confirmed_at IS NOT NULL),0) placement,
                    COALESCE(SUM(recognized_at IS NOT NULL),0) inventory,
                    COALESCE(SUM(tag_version IS NULL),0) without_tag
                FROM (""" + EVIDENCE + ") e", (row, index) -> new Summary(clock.instant(),
                row.getLong("total"), row.getLong("covered"), row.getLong("placement"),
                row.getLong("inventory"), row.getLong("without_tag")));
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public String export(AssetActorContext.Actor actor) {
        var rows = db.query(EVIDENCE + " ORDER BY a.id LIMIT ?", (row, index) -> new Evidence(
                row.getString("id"), row.getString("tag_version"), row.getString("task_id"),
                row.getTimestamp("confirmed_at"), row.getTimestamp("recognized_at")), EXPORT_LIMIT + 1);
        if (rows.size() > EXPORT_LIMIT) {
            throw new ExportLimit();
        }
        var csv = new StringBuilder("assetId,tagVersion,latestInventoryTaskId,placementAt,recognitionAt,status\r\n");
        long covered = 0;
        for (var row : rows) {
            boolean verified = row.placement() != null || row.recognition() != null;
            if (verified) {
                covered++;
            }
            csv.append(csv(row.assetId())).append(',').append(csv(row.tagVersion())).append(',')
                    .append(csv(row.taskId())).append(',').append(csv(utc(row.placement()))).append(',')
                    .append(csv(utc(row.recognition()))).append(',')
                    .append(csv(verified ? "COVERED" : row.tagVersion() == null ? "NO_TAG" : "EVIDENCE_MISSING"))
                    .append("\r\n");
        }
        // CHECKSTYLE.OFF: LineLength - frozen SQL/CSV literal; re-wrapping would change the string value
        db.update("""
                INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,action,resource_type,
                    resource_id,occurred_at,detail_json)
                VALUES (?,?,?,'OPERATIONS_REPORT_EXPORTED','ASSET_TAG_COVERAGE',0,?,JSON_OBJECT('rows',?,'covered',?))
                """, actor.requestId(), actor.id(), actor.role(), Timestamp.from(clock.instant()), rows.size(), covered);
        // CHECKSTYLE.ON: LineLength
        return csv.toString();
    }

    private static String csv(String value) {
        return value == null ? "" : "\"" + AssetImportWorkbook.safeExportCell(value).replace("\"", "\"\"") + "\"";
    }

    private static String utc(Timestamp value) {
        return value == null ? "" : value.toInstant().toString();
    }

    record Summary(Instant evaluatedAt, long total, long covered, long placement, long inventory, long withoutTag) {}
    private record Evidence(String assetId, String tagVersion, String taskId, Timestamp placement, Timestamp recognition) {}
    static final class ExportLimit extends RuntimeException {}
}
