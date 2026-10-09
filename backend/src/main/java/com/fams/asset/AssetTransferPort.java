package com.fams.asset;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Formal custody belongs to the asset domain; approval and transfer locks belong to the caller. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class AssetTransferPort {
    public record Target(
            Long owningOrganizationId,
            String custodyType,
            Long usingOrganizationId,
            Long employeeId,
            Long warehouseId,
            long locationId,
            Map<String, Long> referenceVersions) {
        public Target {
            referenceVersions = referenceVersions == null ? null : Map.copyOf(referenceVersions);
        }
    }

    private final JdbcTemplate db;
    private final AssetMapper mapper;
    private final ObjectMapper json;

    AssetTransferPort(JdbcTemplate db, AssetMapper mapper, ObjectMapper json) {
        this.db = db;
        this.mapper = mapper;
        this.json = json;
    }

    public Map<String, Object> snapshot(long id, long version) {
        var rows =
                db.queryForList(
                        "SELECT id,version,asset_code,name,owning_organization_id,custody_type,"
                            + "using_organization_id,responsible_employee_id,department_responsible_employee_id,"
                            + "storage_warehouse_id,current_location_id,lifecycle_status,exception_status,card_stage,parameter_version_id"
                            + " FROM fams_asset WHERE id=? FOR UPDATE",
                        id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("ASSET_NOT_FOUND", 404);
        }
        var row = rows.getFirst();
        if (((Number) row.get("version")).longValue() != version
                || !"ACTIVE".equals(row.get("card_stage"))
                || !"NORMAL".equals(row.get("exception_status"))
                || !("IN_STOCK".equals(row.get("lifecycle_status"))
                        || "IN_USE".equals(row.get("lifecycle_status")))) {
            throw new AssetHandoverPort.Failure("TRANSFER_SOURCE_CHANGED", 409);
        }
        return row;
    }

    public Map<String, Object> validateTarget(Map<String, Object> source, Target target) {
        boolean stock = "IN_STOCK".equals(source.get("lifecycle_status"));
        if (target.referenceVersions() == null
                || stock != "STORAGE".equals(target.custodyType())
                || !(stock
                        || "PERSONAL".equals(target.custodyType())
                        || "SHARED".equals(target.custodyType()))) {
            throw new AssetHandoverPort.Failure("TRANSFER_TARGET_INVALID", 400);
        }
        if (target.owningOrganizationId() != null) {
            reference(
                    "fams_organization",
                    target.owningOrganizationId(),
                    target,
                    "owningOrganization");
        }
        var location = reference("fams_location", target.locationId(), target, "location");
        if (stock) {
            if (target.warehouseId() == null
                    || target.usingOrganizationId() != null
                    || target.employeeId() != null) {
                throw new AssetHandoverPort.Failure("TRANSFER_TARGET_INVALID", 400);
            }
            reference("fams_warehouse", target.warehouseId(), target, "warehouse");
            if (!"BIN".equals(location.get("location_type"))
                    || !Objects.equals(location.get("warehouse_id"), target.warehouseId())) {
                throw new AssetHandoverPort.Failure("TRANSFER_TARGET_INVALID", 409);
            }
        } else {
            if ("BIN".equals(location.get("location_type"))) {
                throw new AssetHandoverPort.Failure("TRANSFER_TARGET_INVALID", 409);
            }
            if (target.usingOrganizationId() == null
                    || target.employeeId() == null
                    || target.warehouseId() != null) {
                throw new AssetHandoverPort.Failure("TRANSFER_TARGET_INVALID", 400);
            }
            reference(
                    "fams_organization", target.usingOrganizationId(), target, "usingOrganization");
            var employee = reference("fams_employee", target.employeeId(), target, "employee");
            if (!Objects.equals(employee.get("organization_id"), target.usingOrganizationId())) {
                throw new AssetHandoverPort.Failure("TRANSFER_TARGET_INVALID", 409);
            }
            if ("SHARED".equals(target.custodyType())
                    && db.queryForList(
                                    "SELECT a.id FROM fams_system_account a JOIN fams_account_role"
                                        + " r ON r.account_id=a.id WHERE a.employee_id=? AND"
                                        + " a.status='ENABLED' AND"
                                        + " r.role_code='DEPARTMENT_ASSET_MANAGER' FOR UPDATE",
                                    target.employeeId())
                            .isEmpty()) {
                throw new AssetHandoverPort.Failure("TRANSFER_SHARED_RECEIVER_INVALID", 409);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("owning_organization_id", target.owningOrganizationId());
        result.put("custody_type", target.custodyType());
        result.put("using_organization_id", target.usingOrganizationId());
        result.put(
                "responsible_employee_id",
                "PERSONAL".equals(target.custodyType()) ? target.employeeId() : null);
        result.put(
                "department_responsible_employee_id",
                "SHARED".equals(target.custodyType()) ? target.employeeId() : null);
        result.put("storage_warehouse_id", target.warehouseId());
        result.put("current_location_id", target.locationId());
        boolean changed =
                result.entrySet().stream()
                        .anyMatch(
                                entry ->
                                        !Objects.equals(
                                                source.get(entry.getKey()), entry.getValue()));
        if (!changed) {
            throw new AssetHandoverPort.Failure("TRANSFER_NO_CHANGE", 400);
        }
        return result;
    }

    private Map<String, Object> reference(String table, long id, Target target, String name) {
        var rows = db.queryForList("SELECT * FROM " + table + " WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()
                || !"ACTIVE".equals(rows.getFirst().get("status"))
                || !Objects.equals(
                        target.referenceVersions().get(name),
                        ((Number) rows.getFirst().get("version")).longValue())) {
            throw new AssetHandoverPort.Failure("TRANSFER_REFERENCE_CHANGED", 409);
        }
        return rows.getFirst();
    }

    public Map<String, Object> complete(
            long id, long version, Target target, long actor, long request, Instant time) {
        var before = snapshot(id, version);
        var after = validateTarget(before, target);
        int written =
                db.update(
                        "UPDATE fams_asset SET"
                            + " owning_organization_id=?,custody_type=?,using_organization_id=?,"
                            + "responsible_employee_id=?,department_responsible_employee_id=?,storage_warehouse_id=?,current_location_id=?,version=version+1,updated_by=?,updated_at=?"
                            + " WHERE id=? AND version=?",
                        after.get("owning_organization_id"),
                        after.get("custody_type"),
                        after.get("using_organization_id"),
                        after.get("responsible_employee_id"),
                        after.get("department_responsible_employee_id"),
                        after.get("storage_warehouse_id"),
                        after.get("current_location_id"),
                        actor,
                        Timestamp.from(time),
                        id,
                        version);
        if (written != 1) {
            throw new AssetHandoverPort.Failure("TRANSFER_SOURCE_CHANGED", 409);
        }
        mapper.event(
                id,
                "TRANSFER_COMPLETED",
                time,
                json.writeValueAsString(
                        Map.of(
                                "requestId",
                                Long.toString(request),
                                "before",
                                before,
                                "after",
                                after)),
                actor);
        after.put("version", version + 1);
        return after;
    }
}
