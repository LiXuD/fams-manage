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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Asset owns repair lifecycle writes; callers must validate approval, processor and the live lock.
 */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class AssetRepairPort {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AssetMapper mapper;
    private final AssetHandoverPort identity;

    public AssetRepairPort(
            JdbcTemplate db, ObjectMapper json, AssetMapper mapper, AssetHandoverPort identity) {
        this.db = db;
        this.json = json;
        this.mapper = mapper;
        this.identity = identity;
    }

    public Map<String, Object> eligible(Current actor, int page, int size) {
        boolean global = actor.globalApplicant(),
                department = actor.has(Role.DEPARTMENT_ASSET_MANAGER);
        String where =
                " FROM fams_asset a LEFT JOIN fams_location l ON l.id=a.current_location_id WHERE"
                    + " a.card_stage='ACTIVE' AND a.exception_status='NORMAL' AND"
                    + " a.lifecycle_status IN ('IN_STOCK','IN_USE','BORROWED') AND (? OR"
                    + " COALESCE(a.responsible_employee_id,a.department_responsible_employee_id)=?"
                    + " OR (a.custody_type='SHARED' AND a.using_organization_id=?) OR (? AND"
                    + " a.using_organization_id=?)) AND NOT EXISTS(SELECT 1 FROM"
                    + " fams_asset_business_lock b WHERE b.asset_id=a.id AND b.released_at IS"
                    + " NULL)";
        var rows =
                db.queryForList(
                        "SELECT a.*,l.name AS location_name"
                                + where
                                + " ORDER BY a.id DESC LIMIT ? OFFSET ?",
                        global,
                        actor.employeeId(),
                        actor.organizationId(),
                        department,
                        actor.organizationId(),
                        size,
                        ((long) page - 1) * size);
        long total =
                Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*)" + where,
                                Long.class,
                                global,
                                actor.employeeId(),
                                actor.organizationId(),
                                department,
                                actor.organizationId()));
        return Map.of(
                "items",
                rows.stream().map(this::snapshot).toList(),
                "total",
                total,
                "page",
                page,
                "pageSize",
                size);
    }

    public String submission(Current actor, long assetId, long version) {
        var row = locked(assetId, -1);
        if (!(actor.globalApplicant()
                || Objects.equals(actor.employeeId(), nullable(row.get("responsible_employee_id")))
                || Objects.equals(
                        actor.employeeId(), nullable(row.get("department_responsible_employee_id")))
                || ("SHARED".equals(row.get("custody_type"))
                                || actor.has(Role.DEPARTMENT_ASSET_MANAGER))
                        && Objects.equals(
                                actor.organizationId(),
                                nullable(row.get("using_organization_id"))))) {
            throw new AssetHandoverPort.Failure("REPAIR_ASSET_NOT_ELIGIBLE", 403);
        }
        if (!available(row) || ((Number) row.get("version")).longValue() != version) {
            throw new AssetHandoverPort.Failure("REPAIR_ASSET_CHANGED", 409);
        }
        if (!db.queryForList(
                        "SELECT id FROM fams_asset_business_lock WHERE asset_id=? AND released_at"
                                + " IS NULL FOR UPDATE",
                        assetId)
                .isEmpty()) {
            throw new AssetHandoverPort.Failure("REPAIR_ASSET_LOCKED", 409);
        }
        return json.writeValueAsString(snapshot(row));
    }

    public void requireSubmitted(long assetId, long version) {
        if (!available(locked(assetId, version))) {
            throw new AssetHandoverPort.Failure("REPAIR_ASSET_CHANGED", 409);
        }
    }

    public long accept(
            long assetId,
            long version,
            String observed,
            long location,
            long actor,
            long repair,
            Instant time) {
        var row = locked(assetId, version);
        if (!available(row)
                || !Objects.equals(location, nullable(row.get("current_location_id")))) {
            throw new AssetHandoverPort.Failure("REPAIR_ASSET_CHANGED", 409);
        }
        identity.observedCode(assetId, observed);
        db.update(
                "UPDATE fams_asset SET"
                    + " lifecycle_status='UNDER_REPAIR',version=version+1,updated_by=?,updated_at=?"
                    + " WHERE id=? AND version=?",
                actor,
                time,
                assetId,
                version);
        mapper.event(
                assetId,
                "REPAIR_ACCEPTED",
                time,
                json.writeValueAsString(
                        Map.of("repairId", Long.toString(repair), "physicalDeparture", false)),
                actor);
        return version + 1;
    }

    public long closeInPlace(
            long assetId,
            long version,
            String sourceJson,
            String observed,
            long location,
            long actor,
            long repair,
            Instant time) {
        var row = locked(assetId, version);
        var source = json.readTree(sourceJson);
        if (!"UNDER_REPAIR".equals(row.get("lifecycle_status"))
                || !"NORMAL".equals(row.get("exception_status"))
                || !"ACTIVE".equals(row.get("card_stage"))
                || !Objects.equals(location, nullable(row.get("current_location_id")))
                || !Objects.equals(row.get("custody_type"), source.path("custodyType").asText())) {
            throw new AssetHandoverPort.Failure("REPAIR_ASSET_CHANGED", 409);
        }
        for (var entry :
                Map.of(
                                "using_organization_id",
                                "usingOrganizationId",
                                "responsible_employee_id",
                                "responsibleEmployeeId",
                                "department_responsible_employee_id",
                                "departmentResponsibleEmployeeId",
                                "storage_warehouse_id",
                                "warehouseId",
                                "current_location_id",
                                "locationId")
                        .entrySet()) {
            var value = source.path(entry.getValue());
            Long expected = value.isNull() || value.isMissingNode() ? null : value.asLong();
            if (!Objects.equals(expected, nullable(row.get(entry.getKey())))) {
                throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_CHANGED", 409);
            }
        }
        requireActiveTarget(row);
        String lifecycle = source.path("lifecycle").asText();
        if (!List.of("IN_STOCK", "IN_USE", "BORROWED").contains(lifecycle)) {
            throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_CHANGED", 409);
        }
        identity.observedCode(assetId, observed);
        db.update(
                "UPDATE fams_asset SET"
                    + " lifecycle_status=?,version=version+1,updated_by=?,updated_at=? WHERE id=?"
                    + " AND version=?",
                lifecycle,
                actor,
                time,
                assetId,
                version);
        mapper.event(
                assetId,
                "REPAIR_RESTORED_IN_PLACE",
                time,
                json.writeValueAsString(
                        Map.of("repairId", Long.toString(repair), "physicalDeparture", false)),
                actor);
        return version + 1;
    }

    public Map<String, Object> physicalCatalog(String kind, int page, int size) {
        String from =
                switch (kind) {
                    case "warehouse" -> " FROM fams_warehouse WHERE status='ACTIVE'";
                    case "location" -> " FROM fams_location WHERE status='ACTIVE'";
                    default -> throw new AssetHandoverPort.Failure("REPAIR_CATALOG_NOT_FOUND", 404);
                };
        String fields =
                "location".equals(kind)
                        ? "id,name,version,warehouse_id,location_type"
                        : "id,name,version";
        var rows =
                db.queryForList(
                        "SELECT " + fields + from + " ORDER BY id LIMIT ? OFFSET ?",
                        size,
                        ((long) page - 1) * size);
        return Map.of(
                "items",
                rows.stream()
                        .map(
                                row -> {
                                    var result = new LinkedHashMap<String, Object>(row);
                                    for (String field : List.of("id", "warehouse_id")) {
                                        if (result.get(field) != null) {
                                            result.put(field, result.get(field).toString());
                                        }
                                    }
                                    return result;
                                })
                        .toList(),
                "total",
                Objects.requireNonNull(db.queryForObject("SELECT COUNT(*)" + from, Long.class)),
                "page",
                page,
                "pageSize",
                size);
    }

    public long acceptAway(
            long assetId,
            long version,
            String observed,
            long location,
            long locationVersion,
            long actor,
            long repair,
            Instant time) {
        var row = locked(assetId, version);
        if (!(available(row)
                || "UNDER_REPAIR".equals(row.get("lifecycle_status"))
                        && "ACTIVE".equals(row.get("card_stage"))
                        && "NORMAL".equals(row.get("exception_status")))) {
            throw new AssetHandoverPort.Failure("REPAIR_ASSET_CHANGED", 409);
        }
        activeLocation(location, locationVersion);
        identity.observedCode(assetId, observed);
        db.update(
                "UPDATE fams_asset SET"
                    + " lifecycle_status='UNDER_REPAIR',version=version+1,updated_by=?,updated_at=?"
                    + " WHERE id=? AND version=?",
                actor,
                time,
                assetId,
                version);
        mapper.event(
                assetId,
                "REPAIR_ACCEPTED_AWAY",
                time,
                json.writeValueAsString(
                        Map.of(
                                "repairId",
                                Long.toString(repair),
                                "physicalDeparture",
                                true,
                                "actualLocationId",
                                Long.toString(location))),
                actor);
        return version + 1;
    }

    public long closeToStorage(
            long assetId,
            long version,
            String observed,
            long warehouse,
            long warehouseVersion,
            long location,
            long locationVersion,
            long actor,
            long repair,
            Instant time) {
        var row = locked(assetId, version);
        if (!"UNDER_REPAIR".equals(row.get("lifecycle_status"))
                || !"NORMAL".equals(row.get("exception_status"))
                || !"ACTIVE".equals(row.get("card_stage"))) {
            throw new AssetHandoverPort.Failure("REPAIR_ASSET_CHANGED", 409);
        }
        var warehouses =
                db.queryForList(
                        "SELECT id,version,status FROM fams_warehouse WHERE id=? FOR SHARE",
                        warehouse);
        if (warehouses.size() != 1
                || !"ACTIVE".equals(warehouses.getFirst().get("status"))
                || ((Number) warehouses.getFirst().get("version")).longValue()
                        != warehouseVersion) {
            throw new AssetHandoverPort.Failure("REPAIR_INBOUND_TARGET_CHANGED", 409);
        }
        var place = activeLocation(location, locationVersion);
        if (!"BIN".equals(place.get("location_type"))
                || !Objects.equals(warehouse, nullable(place.get("warehouse_id")))) {
            throw new AssetHandoverPort.Failure("REPAIR_INBOUND_TARGET_CHANGED", 409);
        }
        identity.observedCode(assetId, observed);
        db.update(
                "UPDATE fams_asset SET"
                    + " lifecycle_status='IN_STOCK',custody_type='STORAGE',storage_warehouse_id=?,current_location_id=?,using_organization_id=NULL,responsible_employee_id=NULL,department_responsible_employee_id=NULL,version=version+1,updated_by=?,updated_at=?"
                    + " WHERE id=? AND version=?",
                warehouse,
                location,
                actor,
                time,
                assetId,
                version);
        mapper.event(
                assetId,
                "REPAIR_COMPLETED_INBOUND",
                time,
                json.writeValueAsString(
                        Map.of(
                                "repairId",
                                Long.toString(repair),
                                "warehouseId",
                                Long.toString(warehouse),
                                "locationId",
                                Long.toString(location))),
                actor);
        return version + 1;
    }

    /** Frozen responsibility is the target, never proof that physical receipt already happened. */
    public Map<String, Object> returnTarget(long assetId, long version, String sourceJson) {
        var row = locked(assetId, version);
        var source = json.readTree(sourceJson);
        if (!"UNDER_REPAIR".equals(row.get("lifecycle_status"))
                || !"ACTIVE".equals(row.get("card_stage"))
                || !"NORMAL".equals(row.get("exception_status"))
                || !List.of("PERSONAL", "SHARED").contains(source.path("custodyType").asText())
                || !Objects.equals(row.get("custody_type"), source.path("custodyType").asText())) {
            throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_CHANGED", 409);
        }
        for (var field :
                Map.of(
                                "using_organization_id", "usingOrganizationId",
                                "responsible_employee_id", "responsibleEmployeeId",
                                "department_responsible_employee_id",
                                        "departmentResponsibleEmployeeId",
                                "storage_warehouse_id", "warehouseId",
                                "current_location_id", "locationId")
                        .entrySet()) {
            var value = source.path(field.getValue());
            Long expected = value.isNull() || value.isMissingNode() ? null : value.asLong();
            if (!Objects.equals(expected, nullable(row.get(field.getKey())))) {
                throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_CHANGED", 409);
            }
        }
        boolean shared = "SHARED".equals(row.get("custody_type"));
        Long person =
                nullable(
                        row.get(
                                shared
                                        ? "department_responsible_employee_id"
                                        : "responsible_employee_id"));
        Long organization = nullable(row.get("using_organization_id"));
        Long location = nullable(row.get("current_location_id"));
        if (person == null || organization == null || location == null) {
            throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_INACTIVE", 409);
        }
        var people =
                db.queryForList(
                        "SELECT e.name,e.version,e.organization_id,e.status,o.name AS"
                            + " organization_name,o.version AS organization_version,o.status AS"
                            + " organization_status FROM fams_employee e JOIN fams_organization o"
                            + " ON o.id=e.organization_id WHERE e.id=? FOR SHARE",
                        person);
        if (people.size() != 1
                || !"ACTIVE".equals(people.getFirst().get("status"))
                || !"ACTIVE".equals(people.getFirst().get("organization_status"))
                || !Objects.equals(
                        organization, nullable(people.getFirst().get("organization_id")))) {
            throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_INACTIVE", 409);
        }
        var accounts =
                db.queryForList(
                        "SELECT a.id FROM fams_system_account a WHERE a.employee_id=? AND"
                            + " a.status='ENABLED' AND a.initial_password_required=FALSE AND"
                            + " (?=FALSE OR EXISTS(SELECT 1 FROM fams_account_role r WHERE"
                            + " r.account_id=a.id AND r.role_code='DEPARTMENT_ASSET_MANAGER')) FOR"
                            + " SHARE",
                        person,
                        shared);
        if (accounts.size() != 1) {
            throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_INACTIVE", 409);
        }
        var places =
                db.queryForList("SELECT version FROM fams_location WHERE id=? FOR SHARE", location);
        if (places.size() != 1) {
            throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_INACTIVE", 409);
        }
        long placeVersion = ((Number) places.getFirst().get("version")).longValue();
        var place = activeLocation(location, placeVersion);
        var target = new LinkedHashMap<String, Object>();
        target.put("custodyType", row.get("custody_type"));
        target.put("employeeId", person.toString());
        target.put("employeeName", people.getFirst().get("name"));
        target.put("employeeVersion", people.getFirst().get("version"));
        target.put("organizationId", organization.toString());
        target.put("organizationName", people.getFirst().get("organization_name"));
        target.put("organizationVersion", people.getFirst().get("organization_version"));
        target.put("locationId", location.toString());
        target.put("locationName", place.get("name"));
        target.put("locationVersion", placeVersion);
        target.put("receiverAccountId", accounts.getFirst().get("id").toString());
        return target;
    }

    public void checkRepairIdentity(long assetId, long version, String observed) {
        var row = locked(assetId, version);
        if (!"UNDER_REPAIR".equals(row.get("lifecycle_status"))
                || !"ACTIVE".equals(row.get("card_stage"))
                || !"NORMAL".equals(row.get("exception_status"))) {
            throw new AssetHandoverPort.Failure("REPAIR_ASSET_CHANGED", 409);
        }
        identity.observedCode(assetId, observed);
    }

    public void checkRepairPhysical(
            long assetId, long version, String observed, long location, long locationVersion) {
        var row = locked(assetId, version);
        if (!"UNDER_REPAIR".equals(row.get("lifecycle_status"))
                || !"ACTIVE".equals(row.get("card_stage"))
                || !"NORMAL".equals(row.get("exception_status"))) {
            throw new AssetHandoverPort.Failure("REPAIR_ASSET_CHANGED", 409);
        }
        identity.observedCode(assetId, observed);
        activeLocation(location, locationVersion);
    }

    public long closeReturned(
            long assetId,
            long version,
            String sourceJson,
            String observed,
            long employeeVersion,
            long organizationVersion,
            long locationVersion,
            long actor,
            long repair,
            Instant time) {
        var target = returnTarget(assetId, version, sourceJson);
        if (((Number) target.get("employeeVersion")).longValue() != employeeVersion
                || ((Number) target.get("organizationVersion")).longValue() != organizationVersion
                || ((Number) target.get("locationVersion")).longValue() != locationVersion) {
            throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_CHANGED", 409);
        }
        String lifecycle = json.readTree(sourceJson).path("lifecycle").asText();
        if (!List.of("IN_USE", "BORROWED").contains(lifecycle)) {
            throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_CHANGED", 409);
        }
        identity.observedCode(assetId, observed);
        db.update(
                "UPDATE fams_asset SET"
                    + " lifecycle_status=?,version=version+1,updated_by=?,updated_at=? WHERE id=?"
                    + " AND version=?",
                lifecycle,
                actor,
                time,
                assetId,
                version);
        mapper.event(
                assetId,
                "REPAIR_RETURN_RECEIVED",
                time,
                json.writeValueAsString(
                        Map.of(
                                "repairId",
                                Long.toString(repair),
                                "physicalDeparture",
                                true,
                                "target",
                                target)),
                actor);
        return version + 1;
    }

    private Map<String, Object> activeLocation(long location, long version) {
        var places = db.queryForList("SELECT * FROM fams_location WHERE id=? FOR SHARE", location);
        if (places.size() != 1
                || !"ACTIVE".equals(places.getFirst().get("status"))
                || ((Number) places.getFirst().get("version")).longValue() != version) {
            throw new AssetHandoverPort.Failure("REPAIR_INBOUND_TARGET_CHANGED", 409);
        }
        var place = places.getFirst();
        if (place.get("warehouse_id") != null) {
            var warehouses =
                    db.queryForList(
                            "SELECT status FROM fams_warehouse WHERE id=? FOR SHARE",
                            place.get("warehouse_id"));
            if (warehouses.size() != 1 || !"ACTIVE".equals(warehouses.getFirst().get("status"))) {
                throw new AssetHandoverPort.Failure("REPAIR_INBOUND_TARGET_CHANGED", 409);
            }
        }
        return place;
    }

    private void requireActiveTarget(Map<String, Object> row) {
        Long location = nullable(row.get("current_location_id"));
        if (location == null
                || !exists(
                        "SELECT COUNT(*) FROM fams_location WHERE id=? AND status='ACTIVE'",
                        location)) {
            throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_INACTIVE", 409);
        }
        if ("STORAGE".equals(row.get("custody_type"))) {
            Long warehouse = nullable(row.get("storage_warehouse_id"));
            if (warehouse == null
                    || !exists(
                            "SELECT COUNT(*) FROM fams_warehouse w JOIN fams_location l ON"
                                + " l.warehouse_id=w.id AND l.location_type='BIN' WHERE w.id=? AND"
                                + " l.id=? AND w.status='ACTIVE' AND l.status='ACTIVE'",
                            warehouse,
                            location)) {
                throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_INACTIVE", 409);
            }
        } else {
            Long person =
                    nullable(
                            row.get(
                                    "PERSONAL".equals(row.get("custody_type"))
                                            ? "responsible_employee_id"
                                            : "department_responsible_employee_id"));
            Long organization = nullable(row.get("using_organization_id"));
            if (person == null
                    || organization == null
                    || !exists(
                            "SELECT COUNT(*) FROM fams_employee e JOIN fams_organization o ON"
                                + " o.id=e.organization_id WHERE e.id=? AND e.organization_id=? AND"
                                + " e.status='ACTIVE' AND o.status='ACTIVE'",
                            person,
                            organization)) {
                throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_INACTIVE", 409);
            }
            if ("SHARED".equals(row.get("custody_type"))
                    && !exists(
                            "SELECT COUNT(*) FROM fams_system_account a JOIN fams_account_role r ON"
                                + " r.account_id=a.id WHERE a.employee_id=? AND a.status='ENABLED'"
                                + " AND r.role_code='DEPARTMENT_ASSET_MANAGER'",
                            person)) {
                throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_INACTIVE", 409);
            }
        }
    }

    private Map<String, Object> locked(long id, long version) {
        var rows =
                db.queryForList(
                        "SELECT a.*,l.name AS location_name FROM fams_asset a LEFT JOIN"
                                + " fams_location l ON l.id=a.current_location_id WHERE a.id=? FOR"
                                + " UPDATE",
                        id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("ASSET_NOT_FOUND", 404);
        }
        if (version >= 0 && ((Number) rows.getFirst().get("version")).longValue() != version) {
            throw new AssetHandoverPort.Failure("REPAIR_ASSET_CHANGED", 409);
        }
        return rows.getFirst();
    }

    private boolean available(Map<String, Object> row) {
        return "ACTIVE".equals(row.get("card_stage"))
                && "NORMAL".equals(row.get("exception_status"))
                && List.of("IN_STOCK", "IN_USE", "BORROWED").contains(row.get("lifecycle_status"));
    }

    private Map<String, Object> snapshot(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        for (var entry :
                Map.ofEntries(
                                Map.entry("id", "id"),
                                Map.entry("asset_code", "assetCode"),
                                Map.entry("name", "name"),
                                Map.entry("version", "version"),
                                Map.entry("lifecycle_status", "lifecycle"),
                                Map.entry("custody_type", "custodyType"),
                                Map.entry("using_organization_id", "usingOrganizationId"),
                                Map.entry("owning_organization_id", "owningOrganizationId"),
                                Map.entry("responsible_employee_id", "responsibleEmployeeId"),
                                Map.entry(
                                        "department_responsible_employee_id",
                                        "departmentResponsibleEmployeeId"),
                                Map.entry("storage_warehouse_id", "warehouseId"),
                                Map.entry("current_location_id", "locationId"))
                        .entrySet()) {
            Object value = row.get(entry.getKey());
            result.put(
                    entry.getValue(),
                    value instanceof Number && !"version".equals(entry.getKey())
                            ? value.toString()
                            : value);
        }
        result.put("locationName", row.get("location_name"));
        return result;
    }

    private boolean exists(String sql, Object... args) {
        return Objects.requireNonNull(db.queryForObject(sql, Long.class, args)) > 0;
    }

    private Long nullable(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
