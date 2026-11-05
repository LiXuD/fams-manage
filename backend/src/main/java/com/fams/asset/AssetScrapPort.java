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
}
