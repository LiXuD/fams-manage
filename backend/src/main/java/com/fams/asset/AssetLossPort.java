package com.fams.asset;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 * Asset owns loss eligibility and exception writes. Responsibility and lifecycle never move here.
 */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class AssetLossPort {
    /**
     * Custody snapshot projected from a locked asset row. The eleven camelCase keys are
     * serialized verbatim into fams_loss_report.source_snapshot_json, so every name is frozen
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
            @JsonProperty("responsibleEmployeeId") String responsibleEmployeeId,
            @JsonProperty("departmentResponsibleEmployeeId") String departmentResponsibleEmployeeId,
            @JsonProperty("warehouseId") String warehouseId,
            @JsonProperty("locationId") String locationId) {

        static Snapshot fromRow(Map<String, Object> row) {
            return new Snapshot(
                    stringify(row.get("id")),
                    stringify(row.get("asset_code")),
                    stringify(row.get("name")),
                    ((Number) row.get("version")).longValue(),
                    stringify(row.get("lifecycle_status")),
                    stringify(row.get("custody_type")),
                    stringify(row.get("using_organization_id")),
                    stringify(row.get("responsible_employee_id")),
                    stringify(row.get("department_responsible_employee_id")),
                    stringify(row.get("storage_warehouse_id")),
                    stringify(row.get("current_location_id")));
        }

        /** Mirrors the legacy snapshot(): every Number except the version key became a String. */
        private static String stringify(Object value) {
            return value == null ? null : value.toString();
        }
    }

    /** Eligible page envelope for loss submission pickers; keys mirror the pre-typing Map.of assembly. */
    public record EligiblePage(List<Snapshot> items, long total, int page, int pageSize) {
        public EligiblePage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /**
     * Flat stored shape of fams_loss_report.source_snapshot_json: the eleven snapshot keys plus
     * the conditional handoff key written by the flow that opened the report. Absent keys stay
     * absent (NON_NULL), matching the pre-typing LinkedHashMap that only carried the extra key
     * on return/factory/internal-repair/initial openings.
     */
    public record StoredSource(
            @JsonUnwrapped Snapshot snapshot,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @JsonProperty("returnSource")
            Map<String, String> returnSource,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @JsonProperty("factorySource")
            Map<String, String> factorySource,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @JsonProperty("internalRepairSource")
            Map<String, Object> internalRepairSource,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @JsonProperty("initialSource")
            Map<String, Object> initialSource) {
        public StoredSource {
            returnSource = returnSource == null ? null : Map.copyOf(returnSource);
            factorySource = factorySource == null ? null : Map.copyOf(factorySource);
            internalRepairSource =
                    internalRepairSource == null ? null : Map.copyOf(internalRepairSource);
            initialSource = initialSource == null ? null : Map.copyOf(initialSource);
        }
    }

    /**
     * Recovery verification view; embedded under the recoveryAsset key of the untyped loss
     * detail response, so the eleven camelCase wire keys are frozen (JAVA-10a embedding rule).
     */
    public record RecoveryView(
            @JsonProperty("assetCode") String assetCode,
            @JsonProperty("serialNumber") String serialNumber,
            @JsonProperty("version") long version,
            @JsonProperty("lifecycle") String lifecycle,
            @JsonProperty("exceptionStatus") String exceptionStatus,
            @JsonProperty("locationId") String locationId,
            @JsonProperty("locationVersion") Long locationVersion,
            @JsonProperty("locationName") String locationName,
            @JsonProperty("eligible") boolean eligible,
            @JsonProperty("locked") boolean locked,
            @JsonProperty("factoryScrapEligible") boolean factoryScrapEligible) {
    }

    /**
     * Locked custody facts a loss recovery flow reads after require/validate: lifecycle plus the
     * two numeric custodian references. Never serialized; the pre-typing raw row's other columns
     * had no consumers (adoptLossRecovery narrow-record precedent).
     */
    public record RecoverySource(
            String lifecycleStatus, Long responsibleEmployeeId, Long usingOrganizationId) {

        static RecoverySource fromRow(Map<String, Object> row) {
            return new RecoverySource(
                    (String) row.get("lifecycle_status"),
                    row.get("responsible_employee_id") == null
                            ? null
                            : ((Number) row.get("responsible_employee_id")).longValue(),
                    row.get("using_organization_id") == null
                            ? null
                            : ((Number) row.get("using_organization_id")).longValue());
        }
    }

    /** Recovery location picker row; id stringified and version numeric exactly as before typing. */
    public record RecoveryLocation(String id, String name, long version) {
    }

    /** Recovery location page envelope; keys mirror the pre-typing Map.of assembly. */
    public record RecoveryLocationPage(
            List<RecoveryLocation> items, long total, int page, int pageSize) {
        public RecoveryLocationPage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AssetMapper mapper;

    public AssetLossPort(JdbcTemplate db, ObjectMapper json, AssetMapper mapper) {
        this.db = db;
        this.json = json;
        this.mapper = mapper;
    }

    public EligiblePage eligible(Current actor, int page, int size) {
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
        return new EligiblePage(
                db
                        .queryForList(
                                "SELECT a.*" + where + " ORDER BY a.id DESC LIMIT ? OFFSET ?",
                                args.toArray())
                        .stream()
                        .map(Snapshot::fromRow)
                        .toList(),
                Objects.requireNonNull(
                        db.queryForObject("SELECT COUNT(*)" + where, Long.class, scope)),
                page,
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

    /** The return owner already holds and validates the source business lock. */
    public Snapshot returnSubmission(long id, long version) {
        return Snapshot.fromRow(locked(id, version, "NORMAL"));
    }

    public Snapshot initialLossSnapshot(long id) {
        var row = locked(id, 0, "NORMAL");
        if (!"INITIAL_IMPORT".equals(row.get("source_text"))) {
            throw new AssetHandoverPort.Failure("LOSS_ASSET_CHANGED", 409);
        }
        return Snapshot.fromRow(row);
    }

    /** Captures custody for a factory loss after the repair owner validates its active lock. */
    public Snapshot factorySubmission(long id, long version) {
        return Snapshot.fromRow(lockFactoryAsset(id, version, "NORMAL"));
    }

    public Snapshot internalRepairSubmission(long id, long version) {
        return Snapshot.fromRow(lockRepairAsset(id, version, "NORMAL", "UNDER_REPAIR"));
    }

    private Map<String, Object> lockFactoryAsset(long id, long version, String exception) {
        return lockRepairAsset(id, version, exception, "FACTORY_REPAIR");
    }

    private Map<String, Object> lockRepairAsset(long id, long version, String exception, String lifecycle) {
        var rows = db.queryForList("SELECT * FROM fams_asset WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("ASSET_NOT_FOUND", 404);
        }
        var row = rows.getFirst();
        if (!"ACTIVE".equals(row.get("card_stage"))
                || !lifecycle.equals(row.get("lifecycle_status"))
                || !exception.equals(row.get("exception_status"))
                || ((Number) row.get("version")).longValue() != version) {
            throw new AssetHandoverPort.Failure("LOSS_ASSET_CHANGED", 409);
        }
        return row;
    }

    public void requireSubmitted(long id, long version, String repairLifecycle) {
        lockTransitionAsset(id, version, "NORMAL", repairLifecycle);
    }

    private void lockTransitionAsset(long id, long version, String exception, String repairLifecycle) {
        if (repairLifecycle != null) {
            lockRepairAsset(id, version, exception, repairLifecycle);
        } else {
            locked(id, version, exception);
        }
    }

    public long searching(long id, long version, long report, long actor, Instant time, String repairLifecycle) {
        lockTransitionAsset(id, version, "NORMAL", repairLifecycle);
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

    public long confirmedLost(long id, long version, long report, long actor, Instant time, String repairLifecycle) {
        lockTransitionAsset(id, version, "SEARCHING", repairLifecycle);
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
                || List.of("SCRAPPED", "PENDING_SCRAP").contains(rows.getFirst().get("lifecycle_status"))) {
            throw new AssetHandoverPort.Failure("LOSS_ASSET_CHANGED", 409);
        }
    }

    public RecoveryView recoveryView(long id) {
        var row =
                db.queryForMap(
                        "SELECT"
                            + " a.asset_code,a.serial_number,a.version,a.lifecycle_status,a.exception_status,a.card_stage,a.current_location_id,l.name"
                            + " location_name,l.version location_version FROM fams_asset a LEFT"
                            + " JOIN fams_location l ON l.id=a.current_location_id WHERE a.id=?",
                        id);
        return new RecoveryView(
                (String) row.get("asset_code"),
                (String) row.get("serial_number"),
                ((Number) row.get("version")).longValue(),
                (String) row.get("lifecycle_status"),
                (String) row.get("exception_status"),
                row.get("current_location_id") == null
                        ? null
                        : row.get("current_location_id").toString(),
                row.get("location_version") == null
                        ? null
                        : ((Number) row.get("location_version")).longValue(),
                (String) row.get("location_name"),
                "ACTIVE".equals(row.get("card_stage"))
                        && List.of("IN_STOCK", "IN_USE", "BORROWED")
                                .contains(row.get("lifecycle_status"))
                        && List.of("SEARCHING", "LOST").contains(row.get("exception_status")),
                !db.queryForList(
                                "SELECT id FROM fams_asset_business_lock WHERE asset_id=? AND"
                                        + " released_at IS NULL",
                                id)
                        .isEmpty(),
                "ACTIVE".equals(row.get("card_stage"))
                        && "FACTORY_REPAIR".equals(row.get("lifecycle_status"))
                        && "LOST".equals(row.get("exception_status")));
    }

    /** Revalidate the unchanged lawful custodian; other destinations must use a linked workflow. */
    public RecoverySource requireOriginalRecovery(
            long id, long version, String exception, String original) {
        return RecoverySource.fromRow(requireOriginalRecoveryRow(id, version, exception, original));
    }

    private Map<String, Object> requireOriginalRecoveryRow(
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

    public RecoverySource validateOriginalRecovery(
            long id,
            long version,
            String exception,
            String original,
            String observedCode,
            String observedSerial,
            long location,
            long locationVersion) {
        return RecoverySource.fromRow(
                validateOriginalRecoveryRow(
                        id, version, exception, original, observedCode, observedSerial,
                        location, locationVersion));
    }

    private Map<String, Object> validateOriginalRecoveryRow(
            long id,
            long version,
            String exception,
            String original,
            String observedCode,
            String observedSerial,
            long location,
            long locationVersion) {
        var row = requireOriginalRecoveryRow(id, version, exception, original);
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

    /** Recovery to a formal return preserves recorded custody, even when its person is inactive. */
    public RecoverySource requireReturnRecovery(
            long id, long version, String exception, String original) {
        return RecoverySource.fromRow(
                requireLinkedRecovery(id, version, exception, original, "RETURN",
                        List.of("IN_USE", "BORROWED")));
    }

    public RecoverySource requireTransferRecovery(long id, long version, String exception, String original) {
        return RecoverySource.fromRow(
                requireLinkedRecovery(id, version, exception, original, "TRANSFER",
                        List.of("IN_USE", "IN_STOCK")));
    }

    private Map<String, Object> requireLinkedRecovery(long id, long version, String exception,
            String original, String destination, List<String> lifecycles) {
        var row = locked(id, version, exception);
        if (!lifecycles.contains(row.get("lifecycle_status"))) {
            throw new AssetHandoverPort.Failure("LOSS_" + destination + "_SOURCE_REQUIRED", 409);
        }
        var frozen = json.readTree(original);
        var current = snapshot(row);
        for (String field : List.of("lifecycle", "custodyType", "usingOrganizationId",
                "responsibleEmployeeId", "departmentResponsibleEmployeeId", "warehouseId", "locationId")) {
            Object value = current.get(field);
            var expected = frozen.path(field);
            if (!Objects.equals(value == null ? null : value.toString(),
                    expected.isNull() || expected.isMissingNode() ? null : expected.asText())) {
                throw new AssetHandoverPort.Failure("LOSS_" + destination + "_SOURCE_CHANGED", 409);
            }
        }
        return row;
    }

    public RecoverySource validateReturnRecovery(long id, long version, String exception,
            String original, String observedCode, String observedSerial, long location, long locationVersion) {
        var row = requireLinkedRecovery(id, version, exception, original, "RETURN",
                List.of("IN_USE", "BORROWED"));
        verifyLinkedRecoveryFacts(row, observedCode, observedSerial, location, locationVersion);
        return RecoverySource.fromRow(row);
    }

    public RecoverySource validateTransferRecovery(long id, long version, String exception,
            String original, String observedCode, String observedSerial, long location, long locationVersion) {
        var row = requireLinkedRecovery(id, version, exception, original, "TRANSFER",
                List.of("IN_USE", "IN_STOCK"));
        verifyLinkedRecoveryFacts(row, observedCode, observedSerial, location, locationVersion);
        return RecoverySource.fromRow(row);
    }

    private void verifyLinkedRecoveryFacts(Map<String, Object> row, String observedCode, String observedSerial,
            long location, long locationVersion) {
        String serial = row.get("serial_number") == null ? "" : row.get("serial_number").toString();
        if (!row.get("asset_code").equals(observedCode.strip())
                || !serial.strip().equalsIgnoreCase(observedSerial == null ? "" : observedSerial.strip())) {
            throw new AssetHandoverPort.Failure("LOSS_PHYSICAL_IDENTITY_MISMATCH", 409);
        }
        var places = db.queryForList("SELECT id FROM fams_location WHERE id=? AND version=?"
                + " AND status='ACTIVE' FOR SHARE", location, locationVersion);
        if (places.size() != 1) {
            throw new AssetHandoverPort.Failure("LOSS_LOCATION_CHANGED", 409);
        }
    }

    public RecoveryLocationPage recoveryLocations(int page, int size) {
        var items = db.queryForList("SELECT id,name,version FROM fams_location WHERE status='ACTIVE'"
                + " ORDER BY id LIMIT ? OFFSET ?", size, ((long) page - 1) * size);
        return new RecoveryLocationPage(
                items.stream().map(r -> new RecoveryLocation(r.get("id").toString(),
                        (String) r.get("name"), ((Number) r.get("version")).longValue())).toList(),
                Objects.requireNonNull(db.queryForObject("SELECT COUNT(*) FROM fams_location WHERE status='ACTIVE'", Long.class)),
                page,
                size);
    }

    public long recoverForReturn(long id, long version, String exception, String original,
            String observedCode, String observedSerial, long location, long locationVersion,
            long report, long actor, Instant time) {
        validateReturnRecovery(id, version, exception, original, observedCode, observedSerial,
                location, locationVersion);
        return clearRecoveryException(id, version, location, report, actor, time, "LOSS_RECOVERED_FOR_RETURN");
    }

    public long recoverForTransfer(long id, long version, String exception, String original,
            String observedCode, String observedSerial, long location, long locationVersion,
            long report, long actor, Instant time) {
        validateTransferRecovery(id, version, exception, original, observedCode, observedSerial,
                location, locationVersion);
        return clearRecoveryException(id, version, location, report, actor, time, "LOSS_RECOVERED_FOR_TRANSFER");
    }

    public void requireFactoryRecovery(long id, long version) {
        lockFactoryAsset(id, version, "LOST");
    }

    public void validateFactoryRecovery(long id, long version, String exception,
            String observedCode, String observedSerial, long location, long locationVersion) {
        var row = lockFactoryAsset(id, version, exception);
        verifyLinkedRecoveryFacts(row, observedCode, observedSerial, location, locationVersion);
    }

    public long recoverForFactory(long id, long version, String exception, String observedCode,
            String observedSerial, long location, long locationVersion, long report, long actor, Instant time) {
        validateFactoryRecovery(id, version, exception, observedCode, observedSerial, location, locationVersion);
        return clearRecoveryException(id, version, location, report, actor, time, "LOSS_RECOVERED_FOR_FACTORY");
    }

    public void requireInternalRepairRecovery(long id, long version) {
        lockRepairAsset(id, version, "LOST", "UNDER_REPAIR");
    }

    public void validateInternalRepairRecovery(long id, long version, String exception,
            String observedCode, String observedSerial, long location, long locationVersion) {
        var row = lockRepairAsset(id, version, exception, "UNDER_REPAIR");
        verifyLinkedRecoveryFacts(row, observedCode, observedSerial, location, locationVersion);
    }

    public long recoverForInternalRepair(long id, long version, String exception, String observedCode,
            String observedSerial, long location, long locationVersion, long report, long actor, Instant time) {
        validateInternalRepairRecovery(id, version, exception, observedCode, observedSerial, location, locationVersion);
        return clearRecoveryException(id, version, location, report, actor, time, "LOSS_RECOVERED_FOR_INTERNAL_REPAIR");
    }

    private long clearRecoveryException(long id, long version, long location, long report,
            long actor, Instant time, String event) {
        if (db.update("UPDATE fams_asset SET exception_status='NORMAL',version=version+1,"
                + "updated_by=?,updated_at=? WHERE id=? AND version=?", actor, time, id, version) != 1) {
            throw new AssetHandoverPort.Failure("LOSS_ASSET_CHANGED", 409);
        }
        mapper.event(id, event, time,
                json.writeValueAsString(Map.of("reportId", Long.toString(report),
                        "observedLocationId", Long.toString(location))), actor);
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
