package com.fams.asset;

import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Owns the asset-side state changes for scrap, preserving custody while the request is pending. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class AssetScrapPort {
    private final JdbcTemplate db;
    private final AssetMapper mapper;
    private final ObjectMapper json;

    public AssetScrapPort(JdbcTemplate db, AssetMapper mapper, ObjectMapper json) {
        this.db = db;
        this.mapper = mapper;
        this.json = json;
    }

    /** Locks and snapshots a visible source before the application inserts the request and its lock. */
    public Map<String, Object> standaloneSource(Current actor, long assetId, long version) {
        var rows = db.queryForList(
                "SELECT id,asset_code,name,serial_number,lifecycle_status,exception_status,card_stage,"
                        + "custody_type,storage_warehouse_id,current_location_id,using_organization_id,"
                        + "responsible_employee_id,department_responsible_employee_id,version"
                        + " FROM fams_asset WHERE id=? FOR UPDATE",
                assetId);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("ASSET_NOT_FOUND", 404);
        }
        var source = rows.getFirst();
        if (!(actor.globalApplicant()
                || actor.has(Role.EMPLOYEE)
                        && Objects.equals(actor.employeeId(), source.get("responsible_employee_id"))
                || actor.has(Role.DEPARTMENT_ASSET_MANAGER)
                        && Objects.equals(actor.organizationId(), source.get("using_organization_id")))) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_NOT_ELIGIBLE", 403);
        }
        if (((Number) source.get("version")).longValue() != version) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_CHANGED", 409);
        }
        if (!"ACTIVE".equals(source.get("card_stage"))
                || !"NORMAL".equals(source.get("exception_status"))
                || !List.of("IN_STOCK", "IN_USE").contains(source.get("lifecycle_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_SOURCE_NOT_ELIGIBLE", 409);
        }
        if (!db.queryForList(
                        "SELECT id FROM fams_asset_business_lock WHERE asset_id=?"
                                + " AND released_at IS NULL FOR UPDATE",
                        assetId)
                .isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_LOCKED", 409);
        }
        return source;
    }

    /** Freezes the asset only once the application owns the exact active scrap lock. */
    public long freezeStandalone(long assetId, long version, long requestId, long actorId, Instant time) {
        if (db.update(
                        "UPDATE fams_asset a SET lifecycle_status='PENDING_SCRAP',"
                                + "version=version+1,updated_at=?,updated_by=?"
                                + " WHERE a.id=? AND a.version=? AND a.card_stage='ACTIVE'"
                                + " AND a.exception_status='NORMAL' AND a.lifecycle_status IN ('IN_STOCK','IN_USE')"
                                + " AND EXISTS (SELECT 1 FROM fams_asset_business_lock b WHERE b.asset_id=a.id"
                                + " AND b.business_type='SCRAP' AND b.business_id=? AND b.business_item_id=?"
                                + " AND b.released_at IS NULL)",
                        time, actorId, assetId, version, requestId, requestId) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_CHANGED", 409);
        }
        mapper.event(assetId, "SCRAP_REQUESTED", time,
                json.writeValueAsString(Map.of("scrapRequestId", Long.toString(requestId))), actorId);
        return version + 1;
    }

    public void requirePending(long assetId, long version, long requestId, long lockVersion) {
        if (db.queryForList(
                "SELECT a.id FROM fams_asset a JOIN fams_asset_business_lock b ON b.asset_id=a.id"
                        + " WHERE a.id=? AND a.version=? AND a.lifecycle_status='PENDING_SCRAP'"
                        + " AND b.business_type='SCRAP' AND b.business_id=? AND b.business_item_id=?"
                        + " AND b.lock_version=? AND b.released_at IS NULL FOR UPDATE",
                assetId, version, requestId, requestId, lockVersion).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_OR_LOCK_CHANGED", 409);
        }
    }

    /** Invalid custody stays frozen for a separately authorized restoration. */
    public boolean restoreStandalone(long assetId, long version, long requestId, String lifecycle,
            long actorId, Instant time) {
        var asset = db.queryForMap("SELECT custody_type,storage_warehouse_id,current_location_id,"
                + "using_organization_id,responsible_employee_id,department_responsible_employee_id"
                + " FROM fams_asset WHERE id=? FOR UPDATE", assetId);
        var locations = db.queryForList("SELECT warehouse_id,location_type FROM fams_location"
                + " WHERE id=? AND status='ACTIVE' FOR UPDATE", asset.get("current_location_id"));
        if (locations.isEmpty()) {
            return false;
        }
        var location = locations.getFirst();
        boolean valid = false;
        if ("IN_STOCK".equals(lifecycle) && "STORAGE".equals(asset.get("custody_type"))) {
            Object warehouse = asset.get("storage_warehouse_id");
            valid = "BIN".equals(location.get("location_type"))
                    && Objects.equals(warehouse, location.get("warehouse_id"))
                    && !db.queryForList("SELECT id FROM fams_warehouse WHERE id=? AND status='ACTIVE' FOR UPDATE",
                            warehouse).isEmpty();
        } else if ("IN_USE".equals(lifecycle)) {
            Object organization = asset.get("using_organization_id");
            Object employee = switch (String.valueOf(asset.get("custody_type"))) {
                case "PERSONAL" -> asset.get("responsible_employee_id");
                case "SHARED" -> asset.get("department_responsible_employee_id");
                default -> null;
            };
            valid = !db.queryForList("SELECT id FROM fams_organization WHERE id=? AND status='ACTIVE' FOR UPDATE",
                            organization).isEmpty()
                    && !db.queryForList("SELECT id FROM fams_employee WHERE id=? AND organization_id=?"
                                    + " AND status='ACTIVE' FOR UPDATE", employee, organization).isEmpty();
        }
        if (!valid) {
            return false;
        }
        if (db.update("UPDATE fams_asset SET lifecycle_status=?,version=version+1,updated_at=?,updated_by=?"
                + " WHERE id=? AND version=? AND lifecycle_status='PENDING_SCRAP'",
                lifecycle, time, actorId, assetId, version) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_CHANGED", 409);
        }
        mapper.event(assetId, "SCRAP_SOURCE_RESTORED", time,
                json.writeValueAsString(Map.of("scrapRequestId", Long.toString(requestId))), actorId);
        return true;
    }
}
