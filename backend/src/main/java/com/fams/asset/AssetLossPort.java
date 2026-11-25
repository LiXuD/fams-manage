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
        /**
         * Freezes the page items of the loss picker envelope. A null list is deliberately preserved as
         * null rather than turned into an empty one, so the published body keeps the exact shape of the
         * pre-typing {@code Map.of} assembly it replaced.
         *
         * @param items eligible snapshots to publish; copied when non-null, kept null otherwise
         */
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
        /**
         * Freezes the four conditional handoff maps of the stored loss source. Each map is copied so a
         * later mutation by the opening flow cannot change what was serialized into
         * {@code source_snapshot_json}, and each is preserved as null when absent because the NON_NULL
         * inclusion is what keeps an ordinary loss opening from carrying empty handoff keys.
         *
         * @param returnSource return-flow handoff labels, or null when the report was not opened by a
         *     return
         * @param factorySource factory-flow handoff labels, or null when not opened by a factory repair
         * @param internalRepairSource internal-repair handoff facts, or null when not opened by one
         * @param initialSource initial-import provenance facts, or null when not opened by an import
         */
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
        /**
         * Freezes the page items of the recovery location envelope. A null list is preserved as null so
         * the published body keeps the shape of the pre-typing {@code Map.of} assembly.
         *
         * @param items active location rows to publish; copied when non-null, kept null otherwise
         */
        public RecoveryLocationPage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AssetMapper mapper;

    /**
     * Wires the port to raw JDBC, the JSON codec and the mapper. The class-level {@code MANDATORY}
     * propagation makes the loss application own the transaction, so the asset locks taken here are
     * held together with the loss report and business-lock writes that surround them.
     *
     * @param db JDBC handle for the locked fams_asset reads and the exception-status updates
     * @param json codec used to serialize frozen snapshots and to replay stored source JSON
     * @param mapper timeline-event writer for every loss movement recorded here
     */
    public AssetLossPort(JdbcTemplate db, ObjectMapper json, AssetMapper mapper) {
        this.db = db;
        this.json = json;
        this.mapper = mapper;
    }

    /**
     * Pages the assets the actor may report as lost. The predicate matches what {@link #submission}
     * enforces, so a picker never lists a card that would later be refused: ACTIVE, NORMAL and in a
     * handable lifecycle, with no unreleased business lock. Visibility is ownership-based rather than
     * role-based for the two responsibility columns: a global applicant sees everything, anyone whose
     * employee id is the personal or the department responsible employee sees that card, and a
     * department asset manager additionally sees the cards of their own using organization. Rows are
     * ordered by descending asset id and the total is counted with the identical scope array.
     *
     * @param actor resolved actor supplying the employee, organization and role predicates
     * @param page 1-based page number; the offset is derived as {@code (page - 1) * size}
     * @param size page length passed straight to the SQL {@code LIMIT}
     * @return eligible page of snapshots plus the total count of matches
     */
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

    /**
     * Freezes the loss source snapshot for a standalone report. The row is locked at the expected
     * version with a NORMAL exception and a handable lifecycle before eligibility is judged, and the
     * eligibility rule is the same one {@link #eligible} applied, because listing and submitting are
     * separate requests. Any unreleased business lock of any kind blocks the report, which is what
     * keeps a loss from being opened on an asset another flow already holds. Nothing is written; the
     * returned JSON becomes the loss report's source snapshot and the exception move happens later.
     *
     * @param actor resolved actor whose eligibility is checked against the locked row
     * @param id fams_asset primary key to report as lost
     * @param version optimistic version the actor's picker read
     * @return the eleven-key snapshot JSON to store as the loss source
     * @throws AssetHandoverPort.Failure 404 with code ASSET_NOT_FOUND when the asset does not exist,
     *     409 with code LOSS_ASSET_CHANGED when the version moved or the card left a reportable state,
     *     403 with code LOSS_ASSET_NOT_ELIGIBLE when the actor may not report this asset, and 409 with
     *     code LOSS_ASSET_LOCKED when an unreleased business lock exists
     */
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

    /**
     * Captures the custody snapshot for a loss asserted by an initial-import row. The card must still
     * be at version 0, which is the invariant the import rollback relies on, and must carry source text
     * INITIAL_IMPORT, so a loss can only be opened this way on a card nobody has touched since
     * publication. Eligibility is not re-checked here because the import domain owns the approval.
     *
     * @param id fams_asset primary key of the initial-import card
     * @return snapshot of the untouched published card
     * @throws AssetHandoverPort.Failure 404 with code ASSET_NOT_FOUND when the asset does not exist,
     *     and 409 with code LOSS_ASSET_CHANGED when the card is no longer at version 0, is no longer
     *     ACTIVE, NORMAL and handable, or is not an initial import
     */
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

    /**
     * Captures custody for a loss reported against an asset that is under internal repair. The repair
     * owner has already validated its lock; this method only proves the card is still ACTIVE, NORMAL
     * and UNDER_REPAIR at the expected version before freezing the values the loss report will store.
     *
     * @param id fams_asset primary key to snapshot
     * @param version optimistic version the repair flow read
     * @return snapshot of the locked under-repair card
     * @throws AssetHandoverPort.Failure 404 with code ASSET_NOT_FOUND when the asset does not exist,
     *     and 409 with code LOSS_ASSET_CHANGED when the card is no longer ACTIVE, NORMAL and
     *     UNDER_REPAIR at that version
     */
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

    /**
     * Confirms at insertion time that the asset is still in the state the loss report describes. The
     * repair lifecycle argument lets one method serve both a plain loss, which requires a handable
     * lifecycle, and a loss opened on an asset that is under repair, which requires that exact repair
     * lifecycle instead. Nothing is written; the caller inserts the report and its lock afterwards.
     *
     * @param id fams_asset primary key to re-check
     * @param version optimistic version recorded on the pending loss report
     * @param repairLifecycle repair lifecycle to require, or {@code null} to require a handable
     *     IN_STOCK, IN_USE or BORROWED lifecycle instead
     * @throws AssetHandoverPort.Failure 404 with code ASSET_NOT_FOUND when the asset does not exist,
     *     and 409 with code LOSS_ASSET_CHANGED when the version moved or the card left the required
     *     state
     */
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

    /**
     * Opens the search phase of a loss report by moving the exception status from NORMAL to SEARCHING.
     * Only the exception status is written: the lifecycle and every custody and responsibility column
     * stay untouched, because a lost asset still has a lawful custodian until it is written off. The
     * required current exception is NORMAL, so a report cannot restart a search on an asset that is
     * already searching or already lost. A LOSS_SEARCH_STARTED event records the report id.
     *
     * @param id fams_asset primary key whose exception status moves
     * @param version optimistic version the caller read; the row must still be at it
     * @param report loss report id embedded in the event summary
     * @param actor account id recorded as updater and event actor
     * @param time business timestamp written to the row and the event
     * @param repairLifecycle repair lifecycle to require, or {@code null} to require a handable
     *     IN_STOCK, IN_USE or BORROWED lifecycle instead
     * @return the asset version after the update, that is {@code version} plus one
     * @throws AssetHandoverPort.Failure 404 with code ASSET_NOT_FOUND when the asset does not exist,
     *     and 409 with code LOSS_ASSET_CHANGED when the version moved, the card is not ACTIVE and
     *     NORMAL in the required lifecycle, or the guarded update matched no row
     */
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

    /**
     * Writes the loss off by moving the exception status from SEARCHING to LOST. The search phase is a
     * hard precondition, so a report cannot skip straight to a write-off, and again only the exception
     * status changes: custody and responsibility stay frozen, which is what later lets a recovery or a
     * scrap prove it is dealing with the same custodian. A LOSS_CONFIRMED event records the report id.
     *
     * @param id fams_asset primary key whose exception status moves
     * @param version optimistic version the caller read; the row must still be at it
     * @param report loss report id embedded in the event summary
     * @param actor account id recorded as updater and event actor
     * @param time business timestamp written to the row and the event
     * @param repairLifecycle repair lifecycle to require, or {@code null} to require a handable
     *     IN_STOCK, IN_USE or BORROWED lifecycle instead
     * @return the asset version after the update, that is {@code version} plus one
     * @throws AssetHandoverPort.Failure 404 with code ASSET_NOT_FOUND when the asset does not exist,
     *     and 409 with code LOSS_ASSET_CHANGED when the version moved, the card is not ACTIVE and
     *     SEARCHING in the required lifecycle, or the guarded update matched no row
     */
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

    /**
     * Projects the recovery verification view of one asset for the loss detail response. No lock is
     * taken because this is a display read: the three computed flags only tell the UI which recovery
     * buttons may be offered, and every recovery write re-verifies the same facts under a lock.
     * {@code eligible} means the card is ACTIVE, in a handable lifecycle and either searching or
     * already lost; {@code locked} means an unreleased business lock exists, which is reported as a
     * positive fact rather than hidden; {@code factoryScrapEligible} means the card is ACTIVE, in
     * FACTORY_REPAIR and LOST, the only combination that may be scrapped at the factory.
     *
     * @param id fams_asset primary key to project, joined with its current location when present
     * @return recovery view whose eleven frozen camelCase keys are embedded under the recoveryAsset
     *     key of the untyped loss detail response
     * @throws org.springframework.dao.EmptyResultDataAccessException when no such asset exists,
     *     because the projection reads exactly one row and does not translate the miss itself
     */
    public RecoveryView recoveryView(long id) {
        var row =
                db.queryForMap(
                        "SELECT"
                            + " a.asset_code,a.serial_number,a.version,a.lifecycle_status,a.exception_status,"
                            + "a.card_stage,a.current_location_id,l.name"
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

    /**
     * Validates a recovery back to the original custodian, adding the physical and place checks on top
     * of {@link #requireOriginalRecovery}. The observed asset code must equal the stored one after
     * stripping and the observed serial must equal the stored one case-insensitively, with a missing
     * stored serial compared against the empty string. The observed location must be the asset's own
     * current location, must exist and be ACTIVE at the pinned version, and for an IN_STOCK card it
     * must belong to the stored warehouse. Nothing is written here; the caller writes afterwards.
     *
     * @param id fams_asset primary key to recover
     * @param version optimistic version the caller read; the row must still be at it
     * @param exception exception status the row must currently carry, SEARCHING or LOST
     * @param original frozen source snapshot JSON whose seven custody fields must still match
     * @param observedCode asset code observed physically, compared after stripping whitespace
     * @param observedSerial serial observed physically, or {@code null} when the item carries none
     * @param location location the operator observed the item at, pinned by version
     * @param locationVersion version of that location the picker read
     * @return locked custody facts the recovery flow needs after validation
     * @throws AssetHandoverPort.Failure with any code raised by
     *     {@link #requireOriginalRecovery(long, long, String, String)}, plus 409 with code
     *     LOSS_PHYSICAL_IDENTITY_MISMATCH when the observed code or serial differs, and 409 with code
     *     LOSS_LOCATION_CHANGED when the observed location is not the stored one, is inactive, is no
     *     longer at the pinned version, or belongs to another warehouse for an in-stock card
     */
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

    /**
     * Recovers a lost asset back to its original custodian by clearing the exception status to NORMAL.
     * The full original-recovery validation runs first, so neither the frozen custody nor the physical
     * identity nor the observed place can have drifted. Only {@code exception_status} is written: the
     * lifecycle and the responsibility columns were never changed by the loss, so clearing the
     * exception is enough to restore the pre-loss state. A LOSS_RECOVERED event records the report id.
     *
     * @param id fams_asset primary key to recover
     * @param version optimistic version the caller read; the row must still be at it
     * @param exception exception status the row must currently carry, SEARCHING or LOST
     * @param original frozen source snapshot JSON whose seven custody fields must still match
     * @param observedCode asset code observed physically, compared after stripping whitespace
     * @param observedSerial serial observed physically, or {@code null} when the item carries none
     * @param location location the operator observed the item at, pinned by version
     * @param locationVersion version of that location the picker read
     * @param report loss report id embedded in the event summary
     * @param actor account id recorded as updater and event actor
     * @param recordedAt business timestamp written to the row and the event
     * @return the asset version after the update, that is {@code version} plus one
     * @throws AssetHandoverPort.Failure with any code raised by
     *     {@link #validateOriginalRecovery(long, long, String, String, String, String, long, long)},
     *     plus 409 with code LOSS_ASSET_CHANGED when the guarded update matched no row
     */
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

    /**
     * Revalidates the frozen source of a loss that is being recovered through a transfer. The card must
     * be IN_USE or IN_STOCK, the lifecycles a transfer can lawfully take over, and all seven snapshot
     * custody fields must still equal the frozen ones. Unlike the original-recovery path the custodian
     * references are deliberately not required to be active: the transfer workflow that follows owns
     * the new responsibility and only needs the recorded old one to be unchanged. Nothing is written.
     *
     * @param id fams_asset primary key to validate
     * @param version optimistic version the caller read; the row must still be at it
     * @param exception exception status the row must currently carry, SEARCHING or LOST
     * @param original frozen source snapshot JSON whose seven custody fields must still match
     * @return locked custody facts the transfer flow reads afterwards
     * @throws AssetHandoverPort.Failure 404 with code ASSET_NOT_FOUND when the asset does not exist,
     *     409 with code LOSS_ASSET_CHANGED when the version moved or the card left a handable state,
     *     409 with code LOSS_TRANSFER_SOURCE_REQUIRED when the lifecycle is neither IN_USE nor
     *     IN_STOCK, and 409 with code LOSS_TRANSFER_SOURCE_CHANGED when a frozen custody field drifted
     */
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

    /**
     * Validates a recovery that hands the asset to a formal return. On top of the frozen-source check
     * this verifies the operator's physical observation and the observed place. The observed location
     * is only required to exist and be ACTIVE at the pinned version, not to equal the stored current
     * location, because the return workflow may lawfully receive the item somewhere else; that is also
     * why the recorded custody is preserved rather than rewritten here. Nothing is written.
     *
     * @param id fams_asset primary key to validate
     * @param version optimistic version the caller read; the row must still be at it
     * @param exception exception status the row must currently carry, SEARCHING or LOST
     * @param original frozen source snapshot JSON whose seven custody fields must still match
     * @param observedCode asset code observed physically, compared after stripping whitespace
     * @param observedSerial serial observed physically, or {@code null} when the item carries none
     * @param location location the operator observed the item at, pinned by version
     * @param locationVersion version of that location the picker read
     * @return locked custody facts the return flow reads afterwards
     * @throws AssetHandoverPort.Failure with any code raised by
     *     {@link #requireReturnRecovery(long, long, String, String)}, plus 409 with code
     *     LOSS_PHYSICAL_IDENTITY_MISMATCH when the observed code or serial differs, and 409 with code
     *     LOSS_LOCATION_CHANGED when the observed location is missing, inactive or no longer at the
     *     pinned version
     */
    public RecoverySource validateReturnRecovery(long id, long version, String exception,
            String original, String observedCode, String observedSerial, long location, long locationVersion) {
        var row = requireLinkedRecovery(id, version, exception, original, "RETURN",
                List.of("IN_USE", "BORROWED"));
        verifyLinkedRecoveryFacts(row, observedCode, observedSerial, location, locationVersion);
        return RecoverySource.fromRow(row);
    }

    /**
     * Validates a recovery that hands the asset to a transfer. Identical in shape to
     * {@link #validateReturnRecovery} but the frozen lifecycle must be IN_USE or IN_STOCK, the two
     * states a transfer can take over. The physical observation and the pinned observed location are
     * verified, the recorded custody is left alone and nothing is written.
     *
     * @param id fams_asset primary key to validate
     * @param version optimistic version the caller read; the row must still be at it
     * @param exception exception status the row must currently carry, SEARCHING or LOST
     * @param original frozen source snapshot JSON whose seven custody fields must still match
     * @param observedCode asset code observed physically, compared after stripping whitespace
     * @param observedSerial serial observed physically, or {@code null} when the item carries none
     * @param location location the operator observed the item at, pinned by version
     * @param locationVersion version of that location the picker read
     * @return locked custody facts the transfer flow reads afterwards
     * @throws AssetHandoverPort.Failure with any code raised by
     *     {@link #requireTransferRecovery(long, long, String, String)}, plus 409 with code
     *     LOSS_PHYSICAL_IDENTITY_MISMATCH when the observed code or serial differs, and 409 with code
     *     LOSS_LOCATION_CHANGED when the observed location is missing, inactive or no longer at the
     *     pinned version
     */
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

    /**
     * Pages every ACTIVE location for the recovery destination picker. Any active location may be
     * chosen, including rooms and locations without a warehouse, because a recovered item is handed to
     * a linked workflow rather than being put back into storage by this port. Rows are ordered by
     * ascending location id and the total is counted with the same status filter, so the footer matches
     * the pages. No lock is taken; the chosen location is re-verified under a lock when the recovery is
     * written.
     *
     * @param page 1-based page number; the offset is derived as {@code (page - 1) * size}
     * @param size page length passed straight to the SQL {@code LIMIT}
     * @return page of active location rows with a stringified id and a numeric version
     */
    public RecoveryLocationPage recoveryLocations(int page, int size) {
        var items = db.queryForList("SELECT id,name,version FROM fams_location WHERE status='ACTIVE'"
                + " ORDER BY id LIMIT ? OFFSET ?", size, ((long) page - 1) * size);
        return new RecoveryLocationPage(
                items.stream().map(r -> new RecoveryLocation(r.get("id").toString(),
                        (String) r.get("name"), ((Number) r.get("version")).longValue())).toList(),
                Objects.requireNonNull(
                        db.queryForObject("SELECT COUNT(*) FROM fams_location WHERE status='ACTIVE'", Long.class)),
                page,
                size);
    }

    /**
     * Clears the loss exception so a formal return can take the asset over. The full return-recovery
     * validation runs first, then only {@code exception_status} becomes NORMAL: the lifecycle and the
     * recorded custody stay exactly as they were, so the return flow inherits the same custodian the
     * loss report froze. A LOSS_RECOVERED_FOR_RETURN event records the report id and the location the
     * operator actually observed the item at, which is not necessarily the stored one.
     *
     * @param id fams_asset primary key to recover
     * @param version optimistic version the caller read; the row must still be at it
     * @param exception exception status the row must currently carry, SEARCHING or LOST
     * @param original frozen source snapshot JSON whose seven custody fields must still match
     * @param observedCode asset code observed physically, compared after stripping whitespace
     * @param observedSerial serial observed physically, or {@code null} when the item carries none
     * @param location location the operator observed the item at, pinned by version
     * @param locationVersion version of that location the picker read
     * @param report loss report id embedded in the event summary
     * @param actor account id recorded as updater and event actor
     * @param time business timestamp written to the row and the event
     * @return the asset version after the update, that is {@code version} plus one
     * @throws AssetHandoverPort.Failure with any code raised by
     *     {@link #validateReturnRecovery(long, long, String, String, String, String, long, long)},
     *     plus 409 with code LOSS_ASSET_CHANGED when the guarded update matched no row
     */
    public long recoverForReturn(long id, long version, String exception, String original,
            String observedCode, String observedSerial, long location, long locationVersion,
            long report, long actor, Instant time) {
        validateReturnRecovery(id, version, exception, original, observedCode, observedSerial,
                location, locationVersion);
        return clearRecoveryException(id, version, location, report, actor, time, "LOSS_RECOVERED_FOR_RETURN");
    }

    /**
     * Clears the loss exception so a transfer can take the asset over. The full transfer-recovery
     * validation runs first, then only {@code exception_status} becomes NORMAL; the lifecycle and the
     * recorded custody are left for the transfer to rewrite under its own rules. A
     * LOSS_RECOVERED_FOR_TRANSFER event records the report id and the observed location id.
     *
     * @param id fams_asset primary key to recover
     * @param version optimistic version the caller read; the row must still be at it
     * @param exception exception status the row must currently carry, SEARCHING or LOST
     * @param original frozen source snapshot JSON whose seven custody fields must still match
     * @param observedCode asset code observed physically, compared after stripping whitespace
     * @param observedSerial serial observed physically, or {@code null} when the item carries none
     * @param location location the operator observed the item at, pinned by version
     * @param locationVersion version of that location the picker read
     * @param report loss report id embedded in the event summary
     * @param actor account id recorded as updater and event actor
     * @param time business timestamp written to the row and the event
     * @return the asset version after the update, that is {@code version} plus one
     * @throws AssetHandoverPort.Failure with any code raised by
     *     {@link #validateTransferRecovery(long, long, String, String, String, String, long, long)},
     *     plus 409 with code LOSS_ASSET_CHANGED when the guarded update matched no row
     */
    public long recoverForTransfer(long id, long version, String exception, String original,
            String observedCode, String observedSerial, long location, long locationVersion,
            long report, long actor, Instant time) {
        validateTransferRecovery(id, version, exception, original, observedCode, observedSerial,
                location, locationVersion);
        return clearRecoveryException(id, version, location, report, actor, time, "LOSS_RECOVERED_FOR_TRANSFER");
    }

    /**
     * Asserts under a lock that the asset is still a lost item sitting in factory repair. The LOST
     * exception is pinned here rather than passed in, because this is the pre-check a factory scrap
     * flow runs before it asks the operator for a physical observation; the later
     * {@link #validateFactoryRecovery} takes the exception the flow actually observed. Nothing is
     * written.
     *
     * @param id fams_asset primary key to check
     * @param version optimistic version the caller read; the row must still be at it
     * @throws AssetHandoverPort.Failure 404 with code ASSET_NOT_FOUND when the asset does not exist,
     *     and 409 with code LOSS_ASSET_CHANGED when the version moved or the card is not ACTIVE,
     *     FACTORY_REPAIR and LOST
     */
    public void requireFactoryRecovery(long id, long version) {
        lockFactoryAsset(id, version, "LOST");
    }

    /**
     * Validates the physical facts of a recovery on a factory-repair asset. The card must still be in
     * FACTORY_REPAIR at the expected version and the exception the flow names, the observed code and
     * serial must match the locked row, and the observed location must exist and be ACTIVE at the
     * pinned version. The stored custody is not compared against a frozen snapshot here, because a
     * factory asset has no personal custodian to preserve. Nothing is written.
     *
     * @param id fams_asset primary key to validate
     * @param version optimistic version the caller read; the row must still be at it
     * @param exception exception status the row must currently carry
     * @param observedCode asset code observed physically, compared after stripping whitespace
     * @param observedSerial serial observed physically, or {@code null} when the item carries none
     * @param location location the operator observed the item at, pinned by version
     * @param locationVersion version of that location the picker read
     * @throws AssetHandoverPort.Failure 404 with code ASSET_NOT_FOUND when the asset does not exist,
     *     409 with code LOSS_ASSET_CHANGED when the version moved or the card is not ACTIVE and
     *     FACTORY_REPAIR at that exception, 409 with code LOSS_PHYSICAL_IDENTITY_MISMATCH when the
     *     observed code or serial differs, and 409 with code LOSS_LOCATION_CHANGED when the observed
     *     location is missing, inactive or no longer at the pinned version
     */
    public void validateFactoryRecovery(long id, long version, String exception,
            String observedCode, String observedSerial, long location, long locationVersion) {
        var row = lockFactoryAsset(id, version, exception);
        verifyLinkedRecoveryFacts(row, observedCode, observedSerial, location, locationVersion);
    }

    /**
     * Clears the loss exception on a factory-repair asset so the factory flow can continue with the
     * recovered original. The factory-recovery validation runs first, then only
     * {@code exception_status} becomes NORMAL; the FACTORY_REPAIR lifecycle and all custody columns
     * stay untouched, so the repair round keeps its source. A LOSS_RECOVERED_FOR_FACTORY event records
     * the report id and the observed location id.
     *
     * @param id fams_asset primary key to recover
     * @param version optimistic version the caller read; the row must still be at it
     * @param exception exception status the row must currently carry
     * @param observedCode asset code observed physically, compared after stripping whitespace
     * @param observedSerial serial observed physically, or {@code null} when the item carries none
     * @param location location the operator observed the item at, pinned by version
     * @param locationVersion version of that location the picker read
     * @param report loss report id embedded in the event summary
     * @param actor account id recorded as updater and event actor
     * @param time business timestamp written to the row and the event
     * @return the asset version after the update, that is {@code version} plus one
     * @throws AssetHandoverPort.Failure with any code raised by
     *     {@link #validateFactoryRecovery(long, long, String, String, String, long, long)}, plus 409
     *     with code LOSS_ASSET_CHANGED when the guarded update matched no row
     */
    public long recoverForFactory(long id, long version, String exception, String observedCode,
            String observedSerial, long location, long locationVersion, long report, long actor, Instant time) {
        validateFactoryRecovery(id, version, exception, observedCode, observedSerial, location, locationVersion);
        return clearRecoveryException(id, version, location, report, actor, time, "LOSS_RECOVERED_FOR_FACTORY");
    }

    /**
     * Asserts under a lock that the asset is still a lost item under internal repair. The LOST
     * exception is pinned here for the same reason as in {@link #requireFactoryRecovery}: this is the
     * pre-check before the operator is asked for a physical observation. Nothing is written.
     *
     * @param id fams_asset primary key to check
     * @param version optimistic version the caller read; the row must still be at it
     * @throws AssetHandoverPort.Failure 404 with code ASSET_NOT_FOUND when the asset does not exist,
     *     and 409 with code LOSS_ASSET_CHANGED when the version moved or the card is not ACTIVE,
     *     UNDER_REPAIR and LOST
     */
    public void requireInternalRepairRecovery(long id, long version) {
        lockRepairAsset(id, version, "LOST", "UNDER_REPAIR");
    }

    /**
     * Validates the physical facts of a recovery on an internally repaired asset. The card must still be
     * UNDER_REPAIR at the expected version and the exception the flow names, and the observed code,
     * serial and pinned location are checked exactly as for the factory path. Nothing is written.
     *
     * @param id fams_asset primary key to validate
     * @param version optimistic version the caller read; the row must still be at it
     * @param exception exception status the row must currently carry
     * @param observedCode asset code observed physically, compared after stripping whitespace
     * @param observedSerial serial observed physically, or {@code null} when the item carries none
     * @param location location the operator observed the item at, pinned by version
     * @param locationVersion version of that location the picker read
     * @throws AssetHandoverPort.Failure 404 with code ASSET_NOT_FOUND when the asset does not exist,
     *     409 with code LOSS_ASSET_CHANGED when the version moved or the card is not ACTIVE and
     *     UNDER_REPAIR at that exception, 409 with code LOSS_PHYSICAL_IDENTITY_MISMATCH when the
     *     observed code or serial differs, and 409 with code LOSS_LOCATION_CHANGED when the observed
     *     location is missing, inactive or no longer at the pinned version
     */
    public void validateInternalRepairRecovery(long id, long version, String exception,
            String observedCode, String observedSerial, long location, long locationVersion) {
        var row = lockRepairAsset(id, version, exception, "UNDER_REPAIR");
        verifyLinkedRecoveryFacts(row, observedCode, observedSerial, location, locationVersion);
    }

    /**
     * Clears the loss exception on an internally repaired asset so the repair flow can continue with
     * the recovered original. The internal-repair validation runs first, then only
     * {@code exception_status} becomes NORMAL; the UNDER_REPAIR lifecycle and all custody columns stay
     * untouched so the repair keeps its source. A LOSS_RECOVERED_FOR_INTERNAL_REPAIR event records the
     * report id and the observed location id.
     *
     * @param id fams_asset primary key to recover
     * @param version optimistic version the caller read; the row must still be at it
     * @param exception exception status the row must currently carry
     * @param observedCode asset code observed physically, compared after stripping whitespace
     * @param observedSerial serial observed physically, or {@code null} when the item carries none
     * @param location location the operator observed the item at, pinned by version
     * @param locationVersion version of that location the picker read
     * @param report loss report id embedded in the event summary
     * @param actor account id recorded as updater and event actor
     * @param time business timestamp written to the row and the event
     * @return the asset version after the update, that is {@code version} plus one
     * @throws AssetHandoverPort.Failure with any code raised by
     *     {@link #validateInternalRepairRecovery(long, long, String, String, String, long, long)},
     *     plus 409 with code LOSS_ASSET_CHANGED when the guarded update matched no row
     */
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
