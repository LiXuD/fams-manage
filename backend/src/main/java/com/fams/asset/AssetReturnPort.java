package com.fams.asset;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

/** Only the asset domain writes formal custody after the return domain validates its approval and lock. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class AssetReturnPort {
    /**
     * Validated asset identity at loss-recovery adoption. The former raw row was never read,
     * serialized or embedded by any consumer, so the record only carries the locked identity
     * (JAVA-08 '少列即少契约' narrow-record rule).
     */
    public record Adopted(long id, long version) {
    }

    private final JdbcTemplate db;
    private final AssetMapper mapper;
    private final ObjectMapper json;

    AssetReturnPort(JdbcTemplate db, AssetMapper mapper, ObjectMapper json) {
        this.db = db;
        this.mapper = mapper;
        this.json = json;
    }

    /** Adopt the exact active loss item without a released or available-inventory interval. */
    public Adopted adoptLossRecovery(long asset, long version, long report,
            long lossItem, long lockVersion, long request) {
        var rows = db.queryForList("SELECT * FROM fams_asset WHERE id=? FOR UPDATE", asset);
        if (rows.size() != 1 || ((Number) rows.getFirst().get("version")).longValue() != version
                || !"ACTIVE".equals(rows.getFirst().get("card_stage"))
                || !"NORMAL".equals(rows.getFirst().get("exception_status"))
                || !java.util.List.of("IN_USE", "BORROWED").contains(rows.getFirst().get("lifecycle_status"))) {
            throw new AssetHandoverPort.Failure("ASSET_RETURN_CHANGED", 409);
        }
        if (db.update("UPDATE fams_asset_business_lock SET business_type='ASSET_RETURN',"
                + "business_id=?,business_item_id=?,lock_version=lock_version+1 WHERE asset_id=?"
                + " AND business_type='LOST_REPORT' AND business_id=? AND business_item_id=?"
                + " AND lock_version=? AND released_at IS NULL", request, request, asset,
                report, lossItem, lockVersion) != 1) {
            throw new AssetHandoverPort.Failure("RETURN_LOCK_CHANGED", 409);
        }
        var row = rows.getFirst();
        return new Adopted(((Number) row.get("id")).longValue(),
                ((Number) row.get("version")).longValue());
    }

    /**
     * Applies the physical inspection outcome to the locked asset. A {@code MISSING} result only
     * bumps the version and records the report, leaving custody untouched; {@code NORMAL} or
     * {@code DAMAGED} moves the asset into storage custody, clears the using organization and both
     * responsibility columns, and lands it in {@code IN_STOCK} or {@code UNDER_REPAIR} respectively.
     * One timeline event is written in every branch.
     *
     * @param asset fams_asset primary key to lock and update
     * @param version optimistic version the caller read; a mismatch is a concurrent change
     * @param result inspection outcome: {@code MISSING}, {@code NORMAL} or {@code DAMAGED}
     * @param warehouse inbound warehouse id, required unless the result is {@code MISSING}
     * @param location inbound location id, required unless the result is {@code MISSING}
     * @param actor account id recorded as updater and event actor
     * @param source asset-return request id embedded in the event summary
     * @param time business timestamp written to the row and the timeline event
     * @throws AssetHandoverPort.Failure 404 when the asset row does not exist, 409 when the version,
     *     card stage, lifecycle or exception status no longer match an inspectable return, or 400
     *     when the result is unknown or a received result lacks its warehouse and location
     */
    public void inspect(long asset, long version, String result, Long warehouse, Long location,
            long actor, long source, Instant time) {
        var rows = db.queryForList("SELECT * FROM fams_asset WHERE id=? FOR UPDATE", asset);
        if (rows.isEmpty()) { throw new AssetHandoverPort.Failure("ASSET_NOT_FOUND", 404); }
        var row = rows.getFirst();
        if (((Number) row.get("version")).longValue() != version || !"ACTIVE".equals(row.get("card_stage"))
                || !java.util.List.of("BORROWED", "IN_USE").contains(row.get("lifecycle_status"))
                || !"NORMAL".equals(row.get("exception_status"))) {
            throw new AssetHandoverPort.Failure("ASSET_RETURN_CHANGED", 409);
        }
        Timestamp now = Timestamp.from(time);
        if ("MISSING".equals(result)) {
            db.update("UPDATE fams_asset SET version=version+1,updated_by=?,updated_at=? WHERE id=? AND version=?",
                    actor, now, asset, version);
        } else {
            if (!java.util.List.of("NORMAL", "DAMAGED").contains(result) || warehouse == null || location == null) {
                throw new AssetHandoverPort.Failure("ASSET_RETURN_INVALID", 400);
            }
            db.update("UPDATE fams_asset SET lifecycle_status=?,custody_type='STORAGE',storage_warehouse_id=?,"
                    + "current_location_id=?,using_organization_id=NULL,responsible_employee_id=NULL,"
                    + "department_responsible_employee_id=NULL,version=version+1,updated_by=?,updated_at=?"
                    + " WHERE id=? AND version=?", "DAMAGED".equals(result) ? "UNDER_REPAIR" : "IN_STOCK",
                    warehouse, location, actor, now, asset, version);
        }
        mapper.event(asset, "MISSING".equals(result) ? "RETURN_MISSING_REPORTED"
                : "DAMAGED".equals(result) ? "RETURN_DAMAGE_REPAIR" : "RETURN_ACCEPTED", time,
                json.writeValueAsString(Map.of("returnId", Long.toString(source), "inspectionResult", result)), actor);
    }

    /** The loan owner has verified the pending continuation lock on a legacy missing return. */
    public long prepareLegacyMissingReturn(long asset, long version, long source, long actor, Instant time) {
        if (db.update("UPDATE fams_asset SET exception_status='NORMAL',version=version+1,updated_by=?,updated_at=?"
                + " WHERE id=? AND version=? AND card_stage='ACTIVE' AND exception_status='SEARCHING'"
                + " AND lifecycle_status IN ('BORROWED','IN_USE')", actor, Timestamp.from(time), asset, version) != 1) {
            throw new AssetHandoverPort.Failure("RETURN_ASSET_CHANGED", 409);
        }
        mapper.event(asset, "RETURN_MISSING_APPROVAL_REQUIRED", time,
                json.writeValueAsString(Map.of("returnId", Long.toString(source))), actor);
        return version + 1;
    }

}
