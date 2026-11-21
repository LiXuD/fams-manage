package com.fams.asset;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

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
    /**
     * Custody snapshot projected from a locked asset row. The thirteen camelCase keys are
     * serialized verbatim into fams_repair_request.source_snapshot_json, so every name is frozen
     * with {@link JsonProperty} and explicit nulls stay present (JAVA-01/10a stored-shape rule).
     * Non-version numeric ids pass through stringified, exactly as the pre-typing projection did.
     */
    public record Snapshot(
            @JsonProperty("id") String id,
            @JsonProperty("assetCode") String assetCode,
            @JsonProperty("name") String name,
            @JsonProperty("version") long version,
            @JsonProperty("lifecycle") String lifecycle,
            @JsonProperty("custodyType") String custodyType,
            @JsonProperty("usingOrganizationId") String usingOrganizationId,
            @JsonProperty("owningOrganizationId") String owningOrganizationId,
            @JsonProperty("responsibleEmployeeId") String responsibleEmployeeId,
            @JsonProperty("departmentResponsibleEmployeeId") String departmentResponsibleEmployeeId,
            @JsonProperty("warehouseId") String warehouseId,
            @JsonProperty("locationId") String locationId,
            @JsonProperty("locationName") String locationName) {

        static Snapshot fromRow(Map<String, Object> row) {
            return new Snapshot(
                    stringify(row.get("id")),
                    stringify(row.get("asset_code")),
                    stringify(row.get("name")),
                    ((Number) row.get("version")).longValue(),
                    stringify(row.get("lifecycle_status")),
                    stringify(row.get("custody_type")),
                    stringify(row.get("using_organization_id")),
                    stringify(row.get("owning_organization_id")),
                    stringify(row.get("responsible_employee_id")),
                    stringify(row.get("department_responsible_employee_id")),
                    stringify(row.get("storage_warehouse_id")),
                    stringify(row.get("current_location_id")),
                    stringify(row.get("location_name")));
        }

        /** Mirrors the legacy snapshot(): every Number except the version key became a String. */
        private static String stringify(Object value) {
            return value == null ? null : value.toString();
        }
    }

    /** Eligible page envelope for repair submission pickers; keys mirror the Map.of assembly. */
    public record EligiblePage(List<Snapshot> items, long total, int page, int pageSize) {
        public EligiblePage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /**
     * Storage envelope of the initial-import repair source: the thirteen snapshot keys plus the
     * always-present initialSource provenance object, matching the pre-typing LinkedHashMap that
     * was serialized verbatim into fams_repair_request.source_snapshot_json.
     */
    public record InitialSource(
            @JsonUnwrapped Snapshot snapshot,
            @JsonProperty("initialSource") Object initialSource) {
    }

    /** Warehouse picker row; three snake/identity keys as stringified by the legacy projection. */
    public record WarehouseTarget(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("version") long version) {
    }

    /**
     * Location picker row; warehouse_id is stringified when present and keeps an explicit null,
     * location_type passes through, exactly like the legacy LinkedHashMap(row) projection.
     */
    public record LocationTarget(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("version") long version,
            @JsonProperty("warehouse_id") String warehouseId,
            @JsonProperty("location_type") String locationType) {
    }

    /**
     * Physical catalog page: the whole response body of GET /repairs/physical-catalog/{kind}.
     * Items carry WarehouseTarget rows for kind=warehouse and LocationTarget rows for
     * kind=location, so the per-kind key sets stay untouched.
     */
    public record PhysicalPage(List<?> items, long total, int page, int pageSize) {
        public PhysicalPage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /**
     * Validated return handover target. The eleven camelCase keys are serialized verbatim into
     * fams_repair_return_handover.target_snapshot_json and
     * fams_repair_approved_return_target.target_json and embedded in asset event details, so
     * every name is frozen and the stringified-id/numeric-version value shapes are preserved.
     */
    public record ReturnTarget(
            @JsonProperty("custodyType") String custodyType,
            @JsonProperty("employeeId") String employeeId,
            @JsonProperty("employeeName") String employeeName,
            @JsonProperty("employeeVersion") long employeeVersion,
            @JsonProperty("organizationId") String organizationId,
            @JsonProperty("organizationName") String organizationName,
            @JsonProperty("organizationVersion") long organizationVersion,
            @JsonProperty("locationId") String locationId,
            @JsonProperty("locationName") String locationName,
            @JsonProperty("locationVersion") long locationVersion,
            @JsonProperty("receiverAccountId") String receiverAccountId) {
    }

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

    public EligiblePage eligible(Current actor, int page, int size) {
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
        return new EligiblePage(
                rows.stream().map(Snapshot::fromRow).toList(),
                total,
                page,
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

    /** A loss owner has verified the recovered original; adopt its still-active lock atomically. */
    public String lossRecoverySubmission(
            Current actor, long assetId, long version, long report, long lockVersion) {
        return lossRecoverySubmission(actor, assetId, version, report, report, lockVersion);
    }

    public String lossRecoverySubmission(
            Current actor, long assetId, long version, long report, long recoveryItem,
            long lockVersion) {
        if (!actor.has(Role.WAREHOUSE_OPERATOR)) {
            throw new AssetHandoverPort.Failure("REPAIR_ROLE_REQUIRED", 403);
        }
        var row = locked(assetId, version);
        if (!available(row)
                || db.queryForList(
                                        "SELECT id FROM fams_asset_business_lock WHERE asset_id=?"
                                            + " AND business_type='LOST_REPORT' AND business_id=?"
                                            + " AND business_item_id=? AND lock_version=? AND"
                                            + " released_at IS NULL FOR UPDATE",
                                        assetId,
                                        report,
                                        recoveryItem,
                                        lockVersion)
                                .size()
                        != 1) {
            throw new AssetHandoverPort.Failure("REPAIR_LOSS_LOCK_CHANGED", 409);
        }
        return json.writeValueAsString(snapshot(row));
    }

    public void requireSubmitted(long assetId, long version) {
        if (!available(locked(assetId, version))) {
            throw new AssetHandoverPort.Failure("REPAIR_ASSET_CHANGED", 409);
        }
    }

    /** A completed damaged return is already in repair custody, not a new ordinary report. */
    public String damagedReturnSource(long assetId, long version, long warehouse, long location) {
        var row = locked(assetId, version);
        if (!"ACTIVE".equals(row.get("card_stage"))
                || !"NORMAL".equals(row.get("exception_status"))
                || !"UNDER_REPAIR".equals(row.get("lifecycle_status"))
                || !"STORAGE".equals(row.get("custody_type"))
                || row.get("using_organization_id") != null
                || row.get("responsible_employee_id") != null
                || row.get("department_responsible_employee_id") != null
                || !Objects.equals(warehouse, nullable(row.get("storage_warehouse_id")))
                || !Objects.equals(location, nullable(row.get("current_location_id")))) {
            throw new AssetHandoverPort.Failure("REPAIR_RETURN_SOURCE_CHANGED", 409);
        }
        return json.writeValueAsString(snapshot(row));
    }

    public Snapshot beginInitialRepair(long asset, boolean factory) {
        var row = locked(asset, 0);
        if (!available(row) || !"INITIAL_IMPORT".equals(row.get("source_text"))) {
            throw new AssetHandoverPort.Failure("REPAIR_ASSET_CHANGED", 409);
        }
        var source = Snapshot.fromRow(row);
        if (db.update("UPDATE fams_asset SET lifecycle_status=? WHERE id=? AND version=0",
                factory ? "FACTORY_REPAIR" : "UNDER_REPAIR", asset) != 1) {
            throw new AssetHandoverPort.Failure("REPAIR_ASSET_CHANGED", 409);
        }
        return source;
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

    public PhysicalPage physicalCatalog(String kind, int page, int size) {
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
        boolean location = "location".equals(kind);
        return new PhysicalPage(
                rows.stream()
                        .map(
                                row ->
                                        location
                                                ? new LocationTarget(
                                                        row.get("id") == null
                                                                ? null
                                                                : row.get("id").toString(),
                                                        (String) row.get("name"),
                                                        ((Number) row.get("version")).longValue(),
                                                        row.get("warehouse_id") == null
                                                                ? null
                                                                : row.get("warehouse_id")
                                                                        .toString(),
                                                        (String) row.get("location_type"))
                                                : new WarehouseTarget(
                                                        row.get("id") == null
                                                                ? null
                                                                : row.get("id").toString(),
                                                        (String) row.get("name"),
                                                        ((Number) row.get("version")).longValue()))
                        .toList(),
                Objects.requireNonNull(db.queryForObject("SELECT COUNT(*)" + from, Long.class)),
                page,
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
    public ReturnTarget returnTarget(long assetId, long version, String sourceJson) {
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
        return resolveReturnTarget(
                row.get("custody_type").toString(),
                nullable(row.get("using_organization_id")),
                nullable(
                        row.get(
                                "PERSONAL".equals(row.get("custody_type"))
                                        ? "responsible_employee_id"
                                        : "department_responsible_employee_id")),
                nullable(row.get("current_location_id")));
    }

    private ReturnTarget resolveReturnTarget(
            String custody, Long organization, Long person, Long location) {
        boolean shared = "SHARED".equals(custody);
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
        return new ReturnTarget(
                custody,
                person.toString(),
                (String) people.getFirst().get("name"),
                ((Number) people.getFirst().get("version")).longValue(),
                organization.toString(),
                (String) people.getFirst().get("organization_name"),
                ((Number) people.getFirst().get("organization_version")).longValue(),
                location.toString(),
                (String) place.get("name"),
                placeVersion,
                accounts.getFirst().get("id").toString());
    }

    /** Validate a separately approved target without rewriting frozen original responsibility. */
    public ReturnTarget approvedReturnTarget(
            long assetId, long version, String sourceJson, String targetJson) {
        var source = json.readTree(sourceJson);
        // Identity and frozen source fields must still match even when the old employee is
        // inactive.
        var row = locked(assetId, version);
        if (!"UNDER_REPAIR".equals(row.get("lifecycle_status"))
                || !"ACTIVE".equals(row.get("card_stage"))
                || !"NORMAL".equals(row.get("exception_status"))
                || !Objects.equals(row.get("custody_type"), source.path("custodyType").asText())) {
            throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_CHANGED", 409);
        }
        for (var field :
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
            var value = source.path(field.getValue());
            Long expected = value.isNull() || value.isMissingNode() ? null : value.asLong();
            if (!Objects.equals(expected, nullable(row.get(field.getKey())))) {
                throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_CHANGED", 409);
            }
        }
        var input = json.readTree(targetJson);
        String custody = input.path("custodyType").asText();
        if (!List.of("PERSONAL", "SHARED").contains(custody)
                || !List.of("IN_USE", "BORROWED").contains(source.path("lifecycle").asText())) {
            throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_CHANGED", 409);
        }
        Long person = input.path("employeeId").asLong(),
                org = input.path("organizationId").asLong(),
                location = input.path("locationId").asLong();
        if ("BORROWED".equals(source.path("lifecycle").asText())
                && (!"PERSONAL".equals(custody)
                        || !Objects.equals(person, source.path("responsibleEmployeeId").asLong())
                        || !Objects.equals(org, source.path("usingOrganizationId").asLong()))) {
            throw new AssetHandoverPort.Failure("REPAIR_LOAN_BORROWER_CHANGE_FORBIDDEN", 409);
        }
        var target = resolveReturnTarget(custody, org, person, location);
        if (input.has("receiverAccountId")
                && !input.path("receiverAccountId")
                        .asText()
                        .equals(target.receiverAccountId())) {
            throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_CHANGED", 409);
        }
        if (!input.has("employeeVersion")
                || input.path("employeeVersion").asLong() != target.employeeVersion()
                || !input.has("organizationVersion")
                || input.path("organizationVersion").asLong() != target.organizationVersion()
                || !input.has("locationVersion")
                || input.path("locationVersion").asLong() != target.locationVersion()) {
            throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_CHANGED", 409);
        }
        var place = activeLocation(location, target.locationVersion());
        if ("BIN".equals(place.get("location_type"))) {
            throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_CHANGED", 409);
        }
        return target;
    }

    public long closeApprovedReturn(
            long assetId,
            long version,
            String sourceJson,
            String targetJson,
            String observed,
            long actor,
            long repair,
            Instant time) {
        var target = approvedReturnTarget(assetId, version, sourceJson, targetJson);
        identity.observedCode(assetId, observed);
        boolean shared = "SHARED".equals(target.custodyType());
        db.update(
                "UPDATE fams_asset SET lifecycle_status=?,custody_type=?,using_organization_id=?,"
                    + "responsible_employee_id=?,department_responsible_employee_id=?,storage_warehouse_id=NULL,current_location_id=?,version=version+1,updated_by=?,updated_at=?"
                    + " WHERE id=? AND version=?",
                json.readTree(sourceJson).path("lifecycle").asText(),
                target.custodyType(),
                target.organizationId(),
                shared ? null : target.employeeId(),
                shared ? target.employeeId() : null,
                target.locationId(),
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
                                target,
                                "approvedTarget",
                                true)),
                actor);
        return version + 1;
    }

    /** Approved exception receipt permits an offline account, never inactive responsibility. */
    public ReturnTarget proxyReturnTarget(
            long assetId, long version, String sourceJson, String deliveredTargetJson) {
        var row = locked(assetId, version);
        var source = json.readTree(sourceJson);
        if (!"UNDER_REPAIR".equals(row.get("lifecycle_status"))
                || !"ACTIVE".equals(row.get("card_stage"))
                || !"NORMAL".equals(row.get("exception_status"))
                || !Objects.equals(row.get("custody_type"), source.path("custodyType").asText())
                || !List.of("IN_USE", "BORROWED").contains(source.path("lifecycle").asText())) {
            throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_CHANGED", 409);
        }
        for (var field :
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
            var value = source.path(field.getValue());
            Long expected = value.isNull() || value.isMissingNode() ? null : value.asLong();
            if (!Objects.equals(expected, nullable(row.get(field.getKey())))) {
                throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_CHANGED", 409);
            }
        }
        var frozen = json.readTree(deliveredTargetJson);
        String custody = frozen.path("custodyType").asText();
        long person = frozen.path("employeeId").asLong(),
                org = frozen.path("organizationId").asLong(),
                location = frozen.path("locationId").asLong(),
                account = frozen.path("receiverAccountId").asLong();
        if (!List.of("PERSONAL", "SHARED").contains(custody)
                || "BORROWED".equals(source.path("lifecycle").asText())
                        && (!"PERSONAL".equals(custody)
                                || person != source.path("responsibleEmployeeId").asLong()
                                || org != source.path("usingOrganizationId").asLong())) {
            throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_CHANGED", 409);
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
                || !Objects.equals(org, nullable(people.getFirst().get("organization_id")))
                || ((Number) people.getFirst().get("version")).longValue()
                        != frozen.path("employeeVersion").asLong()
                || ((Number) people.getFirst().get("organization_version")).longValue()
                        != frozen.path("organizationVersion").asLong()) {
            throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_INACTIVE", 409);
        }
        // Login availability is deliberately independent of employee and shared-role eligibility.
        var accounts =
                db.queryForList(
                        "SELECT a.id FROM fams_system_account a WHERE a.id=? AND a.employee_id=?"
                            + " AND (?=FALSE OR EXISTS(SELECT 1 FROM fams_account_role r WHERE"
                            + " r.account_id=a.id AND r.role_code='DEPARTMENT_ASSET_MANAGER')) FOR"
                            + " SHARE",
                        account,
                        person,
                        "SHARED".equals(custody));
        if (accounts.size() != 1) {
            throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_INACTIVE", 409);
        }
        var place = activeLocation(location, frozen.path("locationVersion").asLong());
        if ("BIN".equals(place.get("location_type"))) {
            throw new AssetHandoverPort.Failure("REPAIR_RETURN_TARGET_CHANGED", 409);
        }
        return new ReturnTarget(
                custody,
                Long.toString(person),
                (String) people.getFirst().get("name"),
                ((Number) people.getFirst().get("version")).longValue(),
                Long.toString(org),
                (String) people.getFirst().get("organization_name"),
                ((Number) people.getFirst().get("organization_version")).longValue(),
                Long.toString(location),
                (String) place.get("name"),
                frozen.path("locationVersion").asLong(),
                Long.toString(account));
    }

    public long closeProxyReturn(
            long assetId,
            long version,
            String sourceJson,
            String targetJson,
            String observed,
            long actor,
            long repair,
            long confirmation,
            Instant time) {
        var target = proxyReturnTarget(assetId, version, sourceJson, targetJson);
        identity.observedCode(assetId, observed);
        boolean shared = "SHARED".equals(target.custodyType());
        db.update(
                "UPDATE fams_asset SET lifecycle_status=?,custody_type=?,using_organization_id=?,"
                    + "responsible_employee_id=?,department_responsible_employee_id=?,storage_warehouse_id=NULL,current_location_id=?,version=version+1,updated_by=?,updated_at=?"
                    + " WHERE id=? AND version=?",
                json.readTree(sourceJson).path("lifecycle").asText(),
                target.custodyType(),
                target.organizationId(),
                shared ? null : target.employeeId(),
                shared ? target.employeeId() : null,
                target.locationId(),
                actor,
                time,
                assetId,
                version);
        mapper.event(
                assetId,
                "REPAIR_PROXY_RETURN_RECEIVED",
                time,
                json.writeValueAsString(
                        Map.of(
                                "repairId",
                                Long.toString(repair),
                                "confirmationId",
                                Long.toString(confirmation),
                                "target",
                                target)),
                actor);
        return version + 1;
    }

    public Map<String, String> describeReturnTarget(tools.jackson.databind.JsonNode target) {
        var labels = new LinkedHashMap<String, String>();
        for (String kind : List.of("organization", "employee", "location")) {
            var rows =
                    db.queryForList(
                            "SELECT name FROM fams_" + kind + " WHERE id=?",
                            target.path(kind + "Id").asLong());
            labels.put(
                    kind + "Name",
                    rows.isEmpty() ? "引用已失效" : rows.getFirst().get("name").toString());
        }
        return labels;
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
        if (target.employeeVersion() != employeeVersion
                || target.organizationVersion() != organizationVersion
                || target.locationVersion() != locationVersion) {
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
