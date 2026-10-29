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

    public long confirmedLost(long id, long version, long report, long actor, Instant time) {
        locked(id, version, "SEARCHING");
        if (db.update(
                        "UPDATE fams_asset SET exception_status='LOST',version=version+1,"
                                + "updated_by=?,updated_at=? WHERE id=? AND version=?",
                        actor,
                        time,
                        id,
                        version)
                != 1) {
            throw new AssetHandoverPort.Failure("LOSS_ASSET_CHANGED", 409);
        }
        mapper.event(
                id,
                "LOSS_CONFIRMED",
                time,
                json.writeValueAsString(Map.of("reportId", Long.toString(report))),
                actor);
        return version + 1;
    }

    /**
     * Follow-up observes an outstanding loss; it never writes custody or overrides another owner.
     */
    public void requireLost(long id) {
        var rows =
                db.queryForList(
                        "SELECT card_stage,lifecycle_status,exception_status"
                                + " FROM fams_asset WHERE id=? FOR UPDATE",
                        id);
        if (rows.size() != 1
                || !"ACTIVE".equals(rows.getFirst().get("card_stage"))
                || !"LOST".equals(rows.getFirst().get("exception_status"))
                || "SCRAPPED".equals(rows.getFirst().get("lifecycle_status"))) {
            throw new AssetHandoverPort.Failure("LOSS_ASSET_CHANGED", 409);
        }
    }

    public Map<String, Object> recoveryView(long id) {
        var row =
                db.queryForMap(
                        "SELECT"
                            + " a.asset_code,a.serial_number,a.version,a.lifecycle_status,a.exception_status,a.card_stage,a.current_location_id,l.name"
                            + " location_name,l.version location_version FROM fams_asset a LEFT"
                            + " JOIN fams_location l ON l.id=a.current_location_id WHERE a.id=?",
                        id);
        var view = new LinkedHashMap<String, Object>();
        view.put("assetCode", row.get("asset_code"));
        view.put("serialNumber", row.get("serial_number"));
        view.put("version", row.get("version"));
        view.put(
                "locationId",
                row.get("current_location_id") == null
                        ? null
                        : row.get("current_location_id").toString());
        view.put("locationVersion", row.get("location_version"));
        view.put("locationName", row.get("location_name"));
        view.put(
                "eligible",
                "ACTIVE".equals(row.get("card_stage"))
                        && List.of("IN_STOCK", "IN_USE", "BORROWED")
                                .contains(row.get("lifecycle_status"))
                        && List.of("SEARCHING", "LOST").contains(row.get("exception_status")));
        view.put(
                "locked",
                !db.queryForList(
                                "SELECT id FROM fams_asset_business_lock WHERE asset_id=? AND"
                                        + " released_at IS NULL",
                                id)
                        .isEmpty());
        return view;
    }

    /** Revalidate the unchanged lawful custodian; other destinations must use a linked workflow. */
    public Map<String, Object> requireOriginalRecovery(
            long id, long version, String exception, String original) {
        var row = locked(id, version, exception);
        var frozen = json.readTree(original);
        for (String[] field :
                new String[][] {
                    {"lifecycle_status", "lifecycle"},
                    {"custody_type", "custodyType"},
                    {"using_organization_id", "usingOrganizationId"},
                    {"responsible_employee_id", "responsibleEmployeeId"},
                    {"department_responsible_employee_id", "departmentResponsibleEmployeeId"},
                    {"storage_warehouse_id", "warehouseId"},
                    {"current_location_id", "locationId"}
                }) {
            Object value = row.get(field[0]);
            var expected = frozen.path(field[1]);
            if (!Objects.equals(
                    value == null ? null : value.toString(),
                    expected.isNull() || expected.isMissingNode() ? null : expected.asText())) {
                throw new AssetHandoverPort.Failure("LOSS_LINKED_RECOVERY_REQUIRED", 409);
            }
        }
        Long person =
                (Long)
                        ("SHARED".equals(row.get("custody_type"))
                                ? row.get("department_responsible_employee_id")
                                : row.get("responsible_employee_id"));
        if (!"IN_STOCK".equals(row.get("lifecycle_status"))
                && (person == null
                        || db.queryForList(
                                                "SELECT e.id FROM fams_employee e JOIN"
                                                    + " fams_organization o ON"
                                                    + " o.id=e.organization_id WHERE e.id=? AND"
                                                    + " e.status='ACTIVE' AND o.status='ACTIVE' AND"
                                                    + " e.organization_id=? FOR SHARE",
                                                person,
                                                row.get("using_organization_id"))
                                        .size()
                                != 1)) {
            throw new AssetHandoverPort.Failure("LOSS_CUSTODIAN_CHANGED", 409);
        }
        if ("IN_STOCK".equals(row.get("lifecycle_status"))
                && db.queryForList(
                                        "SELECT id FROM fams_warehouse WHERE id=? AND"
                                            + " status='ACTIVE' FOR SHARE",
                                        row.get("storage_warehouse_id"))
                                .size()
                        != 1) {
            throw new AssetHandoverPort.Failure("LOSS_CUSTODIAN_CHANGED", 409);
        }
        return row;
    }

    public Map<String, Object> validateOriginalRecovery(
            long id,
            long version,
            String exception,
            String original,
            String observedCode,
            String observedSerial,
            long location,
            long locationVersion) {
        var row = requireOriginalRecovery(id, version, exception, original);
        String serial = row.get("serial_number") == null ? "" : row.get("serial_number").toString();
        if (!row.get("asset_code").equals(observedCode.strip())
                || !serial.strip()
                        .equalsIgnoreCase(observedSerial == null ? "" : observedSerial.strip())) {
            throw new AssetHandoverPort.Failure("LOSS_PHYSICAL_IDENTITY_MISMATCH", 409);
        }
        var places = db.queryForList("SELECT * FROM fams_location WHERE id=? FOR SHARE", location);
        if (!Objects.equals(row.get("current_location_id"), location)
                || places.size() != 1
                || !"ACTIVE".equals(places.getFirst().get("status"))
                || ((Number) places.getFirst().get("version")).longValue() != locationVersion
                || "IN_STOCK".equals(row.get("lifecycle_status"))
                        && !Objects.equals(
                                row.get("storage_warehouse_id"),
                                places.getFirst().get("warehouse_id"))) {
            throw new AssetHandoverPort.Failure("LOSS_LOCATION_CHANGED", 409);
        }
        return row;
    }

    public long recoverOriginal(
            long id,
            long version,
            String exception,
            String original,
            String observedCode,
            String observedSerial,
            long location,
            long locationVersion,
            long report,
            long actor,
            Instant recordedAt) {
        validateOriginalRecovery(
                id,
                version,
                exception,
                original,
                observedCode,
                observedSerial,
                location,
                locationVersion);
        if (db.update(
                        "UPDATE fams_asset SET"
                            + " exception_status='NORMAL',version=version+1,updated_by=?,updated_at=?"
                            + " WHERE id=? AND version=?",
                        actor,
                        recordedAt,
                        id,
                        version)
                != 1) {
            throw new AssetHandoverPort.Failure("LOSS_ASSET_CHANGED", 409);
        }
        mapper.event(
                id,
                "LOSS_RECOVERED",
                recordedAt,
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
