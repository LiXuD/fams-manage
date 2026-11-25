package com.fams.asset;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Formal custody belongs to the asset domain; approval and transfer locks belong to the caller. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class AssetTransferPort {
    /**
     * Requested transfer target supplied by the calling domain. Unlike the snapshot and custody
     * records this one carries no JSON names because it is never persisted; it is pure validated
     * input. {@code referenceVersions} pins the optimistic version the operator saw for each
     * referenced master-data row under the fixed keys {@code owningOrganization}, {@code location},
     * {@code warehouse}, {@code usingOrganization} and {@code employee}, and a null map is rejected
     * outright so a target can never be validated without version pinning.
     *
     * @param owningOrganizationId optional new owning organization; null leaves ownership untouched
     * @param custodyType target custody, STORAGE when the source is in stock and PERSONAL or SHARED
     *     otherwise
     * @param usingOrganizationId using organization, required for PERSONAL and SHARED, forbidden for
     *     STORAGE
     * @param employeeId receiver employee, required for PERSONAL and SHARED, forbidden for STORAGE
     * @param warehouseId storage warehouse, required for STORAGE and forbidden otherwise
     * @param locationId target location; a BIN for storage custody, a non-BIN otherwise
     * @param referenceVersions pinned master-data versions keyed by the five fixed reference names
     */
    public record Target(
            Long owningOrganizationId,
            String custodyType,
            Long usingOrganizationId,
            Long employeeId,
            Long warehouseId,
            long locationId,
            Map<String, Long> referenceVersions) {
        /**
         * Freezes the pinned reference versions of the requested target. The copy makes the target
         * immutable, so validation and the later write cannot observe different versions if the
         * caller mutates its own map. A null map is preserved as null and rejected by
         * {@link #validateTarget(Snapshot, Target)} rather than silently treated as unpinned.
         *
         * @param referenceVersions pinned versions to retain; copied when non-null
         */
        public Target {
            referenceVersions = referenceVersions == null ? null : Map.copyOf(referenceVersions);
        }
    }

    /**
     * Locked asset facts shared with callers. Snake_case JSON names are persisted verbatim
     * into transfer source snapshots and asset event details.
     */
    public record Snapshot(
            @JsonProperty("id") Long id,
            @JsonProperty("version") long version,
            @JsonProperty("asset_code") String assetCode,
            @JsonProperty("name") String name,
            @JsonProperty("owning_organization_id") Long owningOrganizationId,
            @JsonProperty("custody_type") String custodyType,
            @JsonProperty("using_organization_id") Long usingOrganizationId,
            @JsonProperty("responsible_employee_id") Long responsibleEmployeeId,
            @JsonProperty("department_responsible_employee_id") Long departmentResponsibleEmployeeId,
            @JsonProperty("storage_warehouse_id") Long storageWarehouseId,
            @JsonProperty("current_location_id") Long currentLocationId,
            @JsonProperty("lifecycle_status") String lifecycleStatus,
            @JsonProperty("exception_status") String exceptionStatus,
            @JsonProperty("card_stage") String cardStage,
            @JsonProperty("parameter_version_id") Long parameterVersionId) {
    }

    /** Validated custody change; snake_case names match the persisted after-snapshot shape. */
    public record Custody(
            @JsonProperty("owning_organization_id") Long owningOrganizationId,
            @JsonProperty("custody_type") String custodyType,
            @JsonProperty("using_organization_id") Long usingOrganizationId,
            @JsonProperty("responsible_employee_id") Long responsibleEmployeeId,
            @JsonProperty("department_responsible_employee_id") Long departmentResponsibleEmployeeId,
            @JsonProperty("storage_warehouse_id") Long storageWarehouseId,
            @JsonProperty("current_location_id") long currentLocationId) {
    }

    /** Custody plus the written asset version, as stored in transfer after-snapshots. */
    public record After(
            @JsonProperty("owning_organization_id") Long owningOrganizationId,
            @JsonProperty("custody_type") String custodyType,
            @JsonProperty("using_organization_id") Long usingOrganizationId,
            @JsonProperty("responsible_employee_id") Long responsibleEmployeeId,
            @JsonProperty("department_responsible_employee_id") Long departmentResponsibleEmployeeId,
            @JsonProperty("storage_warehouse_id") Long storageWarehouseId,
            @JsonProperty("current_location_id") long currentLocationId,
            @JsonProperty("version") long version) {
        /**
         * Widens a validated custody change into the stored after-snapshot by appending the asset
         * version the write produced. The seven custody components are copied in order and keep
         * their frozen snake_case JSON names, so the resulting record serializes into the transfer
         * after-snapshot exactly like a row read back from the database.
         *
         * @param custody validated custody change to widen
         * @param version asset version after the guarded update, conventionally the source version
         *     plus one
         * @return after-snapshot record carrying the seven custody keys plus {@code version}
         */
        public static After of(Custody custody, long version) {
            return new After(
                    custody.owningOrganizationId(),
                    custody.custodyType(),
                    custody.usingOrganizationId(),
                    custody.responsibleEmployeeId(),
                    custody.departmentResponsibleEmployeeId(),
                    custody.storageWarehouseId(),
                    custody.currentLocationId(),
                    version);
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

    /**
     * Locks a transferable asset and projects the fifteen frozen snapshot columns. The
     * {@code FOR UPDATE} lock is held for the caller's whole transaction, so the before-snapshot
     * embedded in the transfer event cannot drift from the row that is finally written. Only an
     * ACTIVE, NORMAL card that is IN_STOCK or IN_USE may be transferred; a scrapped, borrowed,
     * under-repair or exceptional card is reported as a changed source.
     *
     * @param id fams_asset primary key to lock and project
     * @param version optimistic version the caller read when it built the transfer request
     * @return before-snapshot whose snake_case keys are persisted verbatim into transfer snapshots
     *     and event details
     * @throws AssetHandoverPort.Failure 404 with code ASSET_NOT_FOUND when no such asset exists, or
     *     409 with code TRANSFER_SOURCE_CHANGED when the version, card stage, exception status or
     *     lifecycle no longer allow a transfer
     */
    public Snapshot snapshot(long id, long version) {
        var rows =
                db.queryForList(
                        "SELECT id,version,asset_code,name,owning_organization_id,custody_type,"
                            + "using_organization_id,responsible_employee_id,department_responsible_employee_id,"
                            + "storage_warehouse_id,current_location_id,lifecycle_status,exception_status,card_stage,"
                            + "parameter_version_id"
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
        return new Snapshot(
                ((Number) row.get("id")).longValue(),
                ((Number) row.get("version")).longValue(),
                (String) row.get("asset_code"),
                (String) row.get("name"),
                nullableId(row.get("owning_organization_id")),
                (String) row.get("custody_type"),
                nullableId(row.get("using_organization_id")),
                nullableId(row.get("responsible_employee_id")),
                nullableId(row.get("department_responsible_employee_id")),
                nullableId(row.get("storage_warehouse_id")),
                ((Number) row.get("current_location_id")).longValue(),
                (String) row.get("lifecycle_status"),
                (String) row.get("exception_status"),
                (String) row.get("card_stage"),
                ((Number) row.get("parameter_version_id")).longValue());
    }

    private static Long nullableId(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    /**
     * Validates a requested target against a locked source and returns the custody values to write.
     * Every referenced master-data row is locked {@code FOR UPDATE} and compared against the pinned
     * version, so an organization, warehouse, location or employee edited behind the operator's back
     * is refused rather than silently accepted. Storage custody demands a BIN location inside the
     * requested warehouse and forbids any using organization or employee; personal and shared custody
     * demand a non-BIN location, a using organization and an employee that belongs to it, and shared
     * custody additionally demands an enabled account holding the department asset manager role. The
     * receiver employee is routed to the personal or the shared responsibility column by custody.
     * Finally the target must actually differ from the source, because a no-op transfer would still
     * burn an asset version and emit a misleading event.
     *
     * @param source locked before-snapshot produced by {@link #snapshot(long, long)}
     * @param target requested target with pinned reference versions
     * @return validated custody change in the persisted after-snapshot shape
     * @throws AssetHandoverPort.Failure 400 with code TRANSFER_TARGET_INVALID when reference versions
     *     are missing, the custody type contradicts the source lifecycle, or the required and
     *     forbidden fields for that custody are wrong; 400 with code TRANSFER_NO_CHANGE when the
     *     target equals the source; 409 with code TRANSFER_REFERENCE_CHANGED when a referenced row
     *     is missing, inactive or no longer at its pinned version; 409 with code
     *     TRANSFER_TARGET_INVALID when the location type contradicts the custody or the employee
     *     belongs to another organization; and 409 with code TRANSFER_SHARED_RECEIVER_INVALID when a
     *     shared receiver has no enabled department asset manager account
     */
    public Custody validateTarget(Snapshot source, Target target) {
        boolean stock = "IN_STOCK".equals(source.lifecycleStatus());
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
        var result = new Custody(
                target.owningOrganizationId(),
                target.custodyType(),
                target.usingOrganizationId(),
                "PERSONAL".equals(target.custodyType()) ? target.employeeId() : null,
                "SHARED".equals(target.custodyType()) ? target.employeeId() : null,
                target.warehouseId(),
                target.locationId());
        boolean changed =
                !Objects.equals(source.owningOrganizationId(), result.owningOrganizationId())
                        || !Objects.equals(source.custodyType(), result.custodyType())
                        || !Objects.equals(source.usingOrganizationId(), result.usingOrganizationId())
                        || !Objects.equals(source.responsibleEmployeeId(), result.responsibleEmployeeId())
                        || !Objects.equals(
                                source.departmentResponsibleEmployeeId(), result.departmentResponsibleEmployeeId())
                        || !Objects.equals(source.storageWarehouseId(), result.storageWarehouseId())
                        || !Objects.equals(source.currentLocationId(), result.currentLocationId());
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

    /**
     * Completes a transfer by locking the source, validating the target and writing formal custody
     * in one caller-owned transaction. The guarded update bumps the asset version and stamps the
     * actor and time, and a TRANSFER_COMPLETED event is written whose summary embeds both the
     * before-snapshot and the validated after-snapshot, so the movement history alone is enough to
     * reconstruct what changed. Approval and the transfer business lock remain the caller's
     * responsibility and are not re-checked here.
     *
     * @param id fams_asset primary key to transfer
     * @param version optimistic version the caller read when it built the transfer request
     * @param target requested target with pinned reference versions
     * @param actor account id recorded as updater and event actor
     * @param request transfer request id embedded in the event summary
     * @param time business timestamp written to the row and the event
     * @return after-snapshot carrying the seven custody keys plus the newly written asset version
     * @throws AssetHandoverPort.Failure with any code raised by {@link #snapshot(long, long)} or
     *     {@link #validateTarget(Snapshot, Target)}, plus 409 with code TRANSFER_SOURCE_CHANGED when
     *     the guarded update matched no row because the version moved under the lock
     */
    public After complete(
            long id, long version, Target target, long actor, long request, Instant time) {
        var before = snapshot(id, version);
        var after = validateTarget(before, target);
        int written =
                db.update(
                        "UPDATE fams_asset SET"
                            + " owning_organization_id=?,custody_type=?,using_organization_id=?,"
                            + "responsible_employee_id=?,department_responsible_employee_id=?,storage_warehouse_id=?,"
                            + "current_location_id=?,version=version+1,updated_by=?,updated_at=?"
                            + " WHERE id=? AND version=?",
                        after.owningOrganizationId(),
                        after.custodyType(),
                        after.usingOrganizationId(),
                        after.responsibleEmployeeId(),
                        after.departmentResponsibleEmployeeId(),
                        after.storageWarehouseId(),
                        after.currentLocationId(),
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
        return After.of(after, version + 1);
    }
}
