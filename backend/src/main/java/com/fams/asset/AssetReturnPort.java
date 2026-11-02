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
    private final JdbcTemplate db;
    private final AssetMapper mapper;
    private final ObjectMapper json;

    AssetReturnPort(JdbcTemplate db, AssetMapper mapper, ObjectMapper json) {
        this.db = db;
        this.mapper = mapper;
        this.json = json;
    }

    /** Adopt the exact active loss item without a released or available-inventory interval. */
    public Map<String, Object> adoptLossRecovery(long asset, long version, long report,
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
        return rows.getFirst();
    }

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
            db.update("UPDATE fams_asset SET exception_status='SEARCHING',version=version+1,updated_by=?,updated_at=?"
                    + " WHERE id=? AND version=?", actor, now, asset, version);
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
}
