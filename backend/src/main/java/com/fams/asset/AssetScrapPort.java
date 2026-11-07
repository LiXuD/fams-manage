package com.fams.asset;

import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
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

    private static final Map<String, String> ORIGINAL_CUSTODY = Map.of(
            "custody_type", "custodyType", "storage_warehouse_id", "warehouseId",
            "current_location_id", "locationId", "using_organization_id", "usingOrganizationId",
            "responsible_employee_id", "responsibleEmployeeId",
            "department_responsible_employee_id", "departmentResponsibleEmployeeId");

    public Map<String, Object> lossSource(long assetId, long version, String lifecycle, JsonNode original) {
        var row = lockedSourceAsset(assetId, version, lifecycle, "LOST");
        if (row == null || !matchesOriginalLossCustody(json.valueToTree(row), original)
                || !db.queryForList("SELECT id FROM fams_asset_business_lock WHERE asset_id=?"
                        + " AND released_at IS NULL FOR UPDATE", assetId).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_OR_LOCK_CHANGED", 409);
        }
        return row;
    }

    public boolean matchesOriginalLossCustody(JsonNode frozen, JsonNode original) {
        return ORIGINAL_CUSTODY.entrySet().stream().allMatch(field ->
                Objects.equals(frozen.path(field.getKey()).asText(null), original.path(field.getValue()).asText(null)));
    }

    private Map<String, Object> lockedSourceAsset(long id, long version, String lifecycle, String exception) {
        var rows = db.queryForList("SELECT id,asset_code,name,serial_number,lifecycle_status,exception_status,card_stage,"
                + "custody_type,storage_warehouse_id,current_location_id,using_organization_id,responsible_employee_id,"
                + "department_responsible_employee_id,version FROM fams_asset WHERE id=? AND version=?"
                + " AND card_stage='ACTIVE' AND lifecycle_status=? AND exception_status=? FOR UPDATE",
                id, version, lifecycle, exception);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean hasFrozenCustody(long id, long version, JsonNode source, String exception) {
        var row = lockedSourceAsset(id, version, "PENDING_SCRAP", exception);
        return row != null && ORIGINAL_CUSTODY.keySet().stream().allMatch(field ->
                Objects.equals(Objects.toString(row.get(field), null), source.path(field).asText(null)));
    }

    public long freezeLoss(long id, long version, long requestId, String lifecycle, long actor, Instant time) {
        return freezeLinked(id, version, requestId, lifecycle, "LOST", actor, time);
    }

    public Map<String, Object> repairSource(long asset, long version, boolean factory) {
        var row = lockedSourceAsset(asset, version, factory ? "FACTORY_REPAIR" : "UNDER_REPAIR", "NORMAL");
        if (row == null) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_CHANGED", 409);
        }
        return row;
    }

    public long freezeRepair(long id, long version, long requestId, long actor, Instant time, boolean factory) {
        return freezeLinked(id, version, requestId, factory ? "FACTORY_REPAIR" : "UNDER_REPAIR", "NORMAL", actor, time);
    }

    private long freezeLinked(long id, long version, long requestId, String lifecycle, String exception, long actor, Instant time) {
        if (db.update("UPDATE fams_asset a SET lifecycle_status='PENDING_SCRAP',version=version+1,updated_at=?,updated_by=?"
                + " WHERE a.id=? AND a.version=? AND a.lifecycle_status=? AND a.exception_status=?"
                + " AND EXISTS (SELECT 1 FROM fams_asset_business_lock b WHERE b.asset_id=a.id AND b.business_type='SCRAP'"
                + " AND b.business_id=? AND b.business_item_id=? AND b.released_at IS NULL)",
                time, actor, id, version, lifecycle, exception, requestId, requestId) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_CHANGED", 409);
        }
        mapper.event(id, "SCRAP_REQUESTED", time,
                json.writeValueAsString(Map.of("scrapRequestId", Long.toString(requestId))), actor);
        return version + 1;
    }

    public boolean restoreLoss(long id, long version, long requestId, JsonNode source, long actor, Instant time) {
        return hasFrozenCustody(id, version, source, "LOST")
                && restoreStandalone(id, version, requestId, source.path("lifecycle_status").asText(), actor, time);
    }

    public boolean restoreRepair(long asset, long version, long requestId, JsonNode source, long actor, Instant time,
            boolean factory) {
        if (!hasFrozenCustody(asset, version, source, "NORMAL")
                || db.queryForList("SELECT a.id FROM fams_asset a JOIN fams_location l ON l.id=a.current_location_id"
                        + " LEFT JOIN fams_warehouse w ON w.id=a.storage_warehouse_id"
                        + " LEFT JOIN fams_organization o ON o.id=a.using_organization_id"
                        + " LEFT JOIN fams_employee e ON e.id=COALESCE(a.responsible_employee_id,a.department_responsible_employee_id)"
                        + " WHERE a.id=? AND l.status='ACTIVE' AND ((a.custody_type='STORAGE' AND w.status='ACTIVE')"
                        + " OR (a.custody_type IN ('PERSONAL','SHARED') AND o.status='ACTIVE' AND e.status='ACTIVE'"
                        + " AND e.organization_id=a.using_organization_id)) FOR UPDATE", asset).isEmpty()) {
            return false;
        }
        if (db.update("UPDATE fams_asset SET lifecycle_status=?,version=version+1,updated_at=?,updated_by=?"
                + " WHERE id=? AND version=? AND lifecycle_status='PENDING_SCRAP'",
                factory ? "FACTORY_REPAIR" : "UNDER_REPAIR", time, actor, asset, version) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_CHANGED", 409);
        }
        mapper.event(asset, "SCRAP_SOURCE_RESTORED", time,
                json.writeValueAsString(Map.of("scrapRequestId", Long.toString(requestId))), actor);
        return true;
    }

    public void requireFrozenRepair(long asset, long version, JsonNode source) {
        if (!hasFrozenCustody(asset, version, source, "NORMAL")) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_CHANGED", 409);
        }
    }

    public long executeFactory(long asset, long version, long request, long lockVersion, JsonNode source,
            long actor, Instant time) {
        requirePending(asset, version, request, lockVersion);
        requireFrozenRepair(asset, version, source);
        if (db.update("UPDATE fams_asset SET lifecycle_status='SCRAPPED',version=version+1,updated_at=?,updated_by=?"
                + " WHERE id=? AND version=? AND lifecycle_status='PENDING_SCRAP' AND exception_status='NORMAL'",
                time, actor, asset, version) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_CHANGED", 409);
        }
        mapper.event(asset, "SCRAP_EXECUTED", time,
                json.writeValueAsString(Map.of("scrapRequestId", Long.toString(request), "sourceType", "FACTORY_REPLACEMENT")),
                actor);
        return version + 1;
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
        } else if ("IN_USE".equals(lifecycle) || "BORROWED".equals(lifecycle)) {
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

    public long executeStandalone(long assetId, long version, long requestId, long lockVersion,
            String observedCode, String observedSerial, long locationId, long locationVersion,
            long actorId, Instant time) {
        requirePending(assetId, version, requestId, lockVersion);
        var asset = db.queryForMap("SELECT asset_code,serial_number,exception_status"
                + " FROM fams_asset WHERE id=? FOR UPDATE", assetId);
        if (!Objects.equals(asset.get("asset_code"), observedCode.strip())
                || asset.get("serial_number") != null
                        && !Objects.equals(asset.get("serial_number"), observedSerial == null ? null : observedSerial.strip())
                || !"NORMAL".equals(asset.get("exception_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_PHYSICAL_IDENTITY_CHANGED", 409);
        }
        if (db.queryForList("SELECT l.id FROM fams_location l LEFT JOIN fams_warehouse w ON w.id=l.warehouse_id"
                + " WHERE l.id=? AND l.version=? AND l.status='ACTIVE'"
                + " AND (l.warehouse_id IS NULL OR w.status='ACTIVE') FOR UPDATE",
                locationId, locationVersion).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_LOCATION_CHANGED", 409);
        }
        if (db.update("UPDATE fams_asset SET lifecycle_status='SCRAPPED',version=version+1,"
                + "updated_at=?,updated_by=? WHERE id=? AND version=? AND lifecycle_status='PENDING_SCRAP'",
                time, actorId, assetId, version) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_CHANGED", 409);
        }
        mapper.event(assetId, "SCRAP_EXECUTED", time,
                json.writeValueAsString(Map.of("scrapRequestId", Long.toString(requestId))), actorId);
        return version + 1;
    }

    public long executeLoss(long asset, long version, long requestId, long lockVersion,
            JsonNode source, long actor, Instant time) {
        requirePending(asset, version, requestId, lockVersion);
        if (!hasFrozenCustody(asset, version, source, "LOST") || db.update("UPDATE fams_asset SET lifecycle_status='SCRAPPED',version=version+1,updated_at=?,updated_by=?"
                        + " WHERE id=? AND version=? AND lifecycle_status='PENDING_SCRAP' AND exception_status='LOST'",
                time, actor, asset, version) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_LOSS_ASSET_CHANGED", 409);
        }
        mapper.event(asset, "SCRAP_EXECUTED", time,
                json.writeValueAsString(Map.of("scrapRequestId", Long.toString(requestId), "sourceType", "LOSS")), actor);
        return version + 1;
    }

    /** Serialize disposition with any later workflow that needs this asset. */
    public void requireDispositionAsset(long id, long version, String observedCode, String observedSerial) {
        var rows = db.queryForList("SELECT asset_code,serial_number FROM fams_asset WHERE id=? AND version=?"
                + " AND card_stage='ACTIVE' AND lifecycle_status='SCRAPPED' AND exception_status='NORMAL' FOR UPDATE",
                id, version);
        if (rows.isEmpty() || !db.queryForList("SELECT id FROM fams_asset_business_lock WHERE asset_id=?"
                + " AND released_at IS NULL FOR UPDATE", id).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_OR_LOCK_CHANGED", 409);
        }
        var asset = rows.getFirst();
        if (!Objects.equals(asset.get("asset_code"), observedCode.strip())
                || asset.get("serial_number") != null && !Objects.equals(asset.get("serial_number"),
                        observedSerial == null ? null : observedSerial.strip())) {
            throw new AssetHandoverPort.Failure("SCRAP_PHYSICAL_IDENTITY_CHANGED", 409);
        }
    }

    public Map<String, Object> eligible(Current actor, int page, int size) {
        String where = " FROM fams_asset a WHERE a.card_stage='ACTIVE' AND a.exception_status='NORMAL'"
                + " AND a.lifecycle_status IN ('IN_STOCK','IN_USE') AND (? OR (? AND a.responsible_employee_id=?)"
                + " OR (? AND a.using_organization_id=?)) AND NOT EXISTS (SELECT 1 FROM fams_asset_business_lock b"
                + " WHERE b.asset_id=a.id AND b.released_at IS NULL)";
        Object[] scope = {actor.globalApplicant(), actor.has(Role.EMPLOYEE), actor.employeeId(),
                actor.has(Role.DEPARTMENT_ASSET_MANAGER), actor.organizationId()};
        var args = new java.util.ArrayList<Object>(java.util.Arrays.asList(scope));
        args.add(size);
        args.add(((long) page - 1) * size);
        var items = db.queryForList("SELECT a.id,a.asset_code,a.name,a.version" + where
                + " ORDER BY a.id DESC LIMIT ? OFFSET ?", args.toArray()).stream()
                .map(row -> Map.of("id", row.get("id").toString(), "assetCode", row.get("asset_code"),
                        "name", row.get("name"), "version", row.get("version"))).toList();
        return Map.of("items", items, "total", Objects.requireNonNull(
                db.queryForObject("SELECT COUNT(*)" + where, Long.class, scope)),
                "page", page, "pageSize", size);
    }
}
