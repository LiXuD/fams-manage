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
 * Asset owns loss eligibility and exception writes. Responsibility and lifecycle never move here.
 */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class AssetLossPort {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AssetMapper mapper;

    public AssetLossPort(JdbcTemplate db, ObjectMapper json, AssetMapper mapper) {
        this.db = db;
        this.json = json;
        this.mapper = mapper;
    }

    public Map<String, Object> eligible(Current actor, int page, int size) {
        String where =
                " FROM fams_asset a WHERE a.card_stage='ACTIVE' AND a.exception_status='NORMAL' AND"
                    + " a.lifecycle_status IN ('IN_STOCK','IN_USE','BORROWED') AND (? OR"
                    + " a.responsible_employee_id=? OR a.department_responsible_employee_id=? OR (?"
                    + " AND a.using_organization_id=?)) AND NOT EXISTS(SELECT 1 FROM"
                    + " fams_asset_business_lock b WHERE b.asset_id=a.id AND b.released_at IS"
                    + " NULL)";
        Object[] scope = {
            actor.globalApplicant(),
            actor.employeeId(),
            actor.employeeId(),
            actor.has(Role.DEPARTMENT_ASSET_MANAGER),
            actor.organizationId()
        };
        var args = new java.util.ArrayList<Object>(java.util.Arrays.asList(scope));
        args.add(size);
        args.add(((long) page - 1) * size);
        return Map.of(
                "items",
                db
                        .queryForList(
                                "SELECT a.*" + where + " ORDER BY a.id DESC LIMIT ? OFFSET ?",
                                args.toArray())
                        .stream()
                        .map(this::snapshot)
                        .toList(),
                "total",
                Objects.requireNonNull(
                        db.queryForObject("SELECT COUNT(*)" + where, Long.class, scope)),
                "page",
                page,
                "pageSize",
                size);
    }

    public String submission(Current actor, long id, long version) {
        var row = locked(id, version, "NORMAL");
        if (!(actor.globalApplicant()
                || Objects.equals(actor.employeeId(), row.get("responsible_employee_id"))
                || Objects.equals(actor.employeeId(), row.get("department_responsible_employee_id"))
                || actor.has(Role.DEPARTMENT_ASSET_MANAGER)
                        && Objects.equals(
                                actor.organizationId(), row.get("using_organization_id")))) {
            throw new AssetHandoverPort.Failure("LOSS_ASSET_NOT_ELIGIBLE", 403);
        }
        if (!db.queryForList(
                        "SELECT id FROM fams_asset_business_lock WHERE asset_id=? AND released_at"
                            + " IS NULL FOR UPDATE",
                        id)
                .isEmpty()) {
            throw new AssetHandoverPort.Failure("LOSS_ASSET_LOCKED", 409);
        }
        return json.writeValueAsString(snapshot(row));
    }

    public void requireSubmitted(long id, long version) {
        locked(id, version, "NORMAL");
    }

    public long searching(long id, long version, long report, long actor, Instant time) {
        locked(id, version, "NORMAL");
        if (db.update(
                        "UPDATE fams_asset SET"
                            + " exception_status='SEARCHING',version=version+1,updated_by=?,updated_at=?"
                            + " WHERE id=? AND version=?",
                        actor,
                        time,
                        id,
                        version)
                != 1) {
            throw new AssetHandoverPort.Failure("LOSS_ASSET_CHANGED", 409);
        }
        mapper.event(
                id,
                "LOSS_SEARCH_STARTED",
                time,
                json.writeValueAsString(Map.of("reportId", Long.toString(report))),
                actor);
        return version + 1;
    }

    private Map<String, Object> locked(long id, long version, String exception) {
        var rows = db.queryForList("SELECT * FROM fams_asset WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("ASSET_NOT_FOUND", 404);
        }
        var row = rows.getFirst();
        if (!"ACTIVE".equals(row.get("card_stage"))
                || !exception.equals(row.get("exception_status"))
                || !List.of("IN_STOCK", "IN_USE", "BORROWED").contains(row.get("lifecycle_status"))
                || ((Number) row.get("version")).longValue() != version) {
            throw new AssetHandoverPort.Failure("LOSS_ASSET_CHANGED", 409);
        }
        return row;
    }

    private Map<String, Object> snapshot(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        for (var field :
                Map.ofEntries(
                                Map.entry("id", "id"),
                                Map.entry("asset_code", "assetCode"),
                                Map.entry("name", "name"),
                                Map.entry("version", "version"),
                                Map.entry("lifecycle_status", "lifecycle"),
                                Map.entry("custody_type", "custodyType"),
                                Map.entry("using_organization_id", "usingOrganizationId"),
                                Map.entry("responsible_employee_id", "responsibleEmployeeId"),
                                Map.entry(
                                        "department_responsible_employee_id",
                                        "departmentResponsibleEmployeeId"),
                                Map.entry("storage_warehouse_id", "warehouseId"),
                                Map.entry("current_location_id", "locationId"))
                        .entrySet()) {
            var value = row.get(field.getKey());
            result.put(
                    field.getValue(),
                    value instanceof Number && !"version".equals(field.getKey())
                            ? value.toString()
                            : value);
        }
        return result;
    }
}
