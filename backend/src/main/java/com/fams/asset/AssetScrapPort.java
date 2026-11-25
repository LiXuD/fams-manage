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
    /** Eligible picker row covering the eligible SQL columns; camelCase keys match the HTTP wire. */
    public record EligibleAsset(String id, String assetCode, String name, long version) {
    }

    /** Eligible page envelope owned by the asset domain; keys match the pre-typing Map.of assembly. */
    public record EligiblePage(List<EligibleAsset> items, long total, int page, int pageSize) {
        /**
         * Freezes the page items of the scrap picker envelope. A null list is deliberately preserved
         * as null rather than turned into an empty one, so the published body keeps the exact shape
         * of the pre-typing {@code Map.of} assembly it replaced.
         *
         * @param items eligible rows to publish; copied when non-null, kept null otherwise
         */
        public EligiblePage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /**
     * Locked scrap-source asset row: the fourteen selected snake_case columns are serialized
     * verbatim into fams_scrap_request.source_snapshot_json. Unlike the loss/repair snapshots
     * the legacy assembly passed raw JDBC values through (numeric ids, numeric version), so the
     * id components are Long/long rather than String (JAVA-01/10a stored-shape rule).
     */
    public record SourceRow(
            @JsonProperty("id") long id,
            @JsonProperty("asset_code") String assetCode,
            @JsonProperty("name") String name,
            @JsonProperty("serial_number") String serialNumber,
            @JsonProperty("lifecycle_status") String lifecycleStatus,
            @JsonProperty("exception_status") String exceptionStatus,
            @JsonProperty("card_stage") String cardStage,
            @JsonProperty("custody_type") String custodyType,
            @JsonProperty("storage_warehouse_id") Long storageWarehouseId,
            @JsonProperty("current_location_id") Long currentLocationId,
            @JsonProperty("using_organization_id") Long usingOrganizationId,
            @JsonProperty("responsible_employee_id") Long responsibleEmployeeId,
            @JsonProperty("department_responsible_employee_id") Long departmentResponsibleEmployeeId,
            @JsonProperty("version") long version) {

        static SourceRow fromRow(Map<String, Object> row) {
            return new SourceRow(
                    ((Number) row.get("id")).longValue(),
                    (String) row.get("asset_code"),
                    (String) row.get("name"),
                    (String) row.get("serial_number"),
                    (String) row.get("lifecycle_status"),
                    (String) row.get("exception_status"),
                    (String) row.get("card_stage"),
                    (String) row.get("custody_type"),
                    numberOrNull(row.get("storage_warehouse_id")),
                    numberOrNull(row.get("current_location_id")),
                    numberOrNull(row.get("using_organization_id")),
                    numberOrNull(row.get("responsible_employee_id")),
                    numberOrNull(row.get("department_responsible_employee_id")),
                    ((Number) row.get("version")).longValue());
        }

        private static Long numberOrNull(Object value) {
            return value == null ? null : ((Number) value).longValue();
        }
    }

    /**
     * Flat stored shape of fams_scrap_request.source_snapshot_json: the fourteen source-row keys
     * plus the conditional evidence keys written by the flow that opened the request
     * (lossEvidence for loss openings, repairEvidence for repair/factory openings,
     * initialSource for the initial-import flows). Absent keys stay absent, matching the
     * pre-typing LinkedHashMap that only carried the extra keys on those flows.
     */
    public record StoredSource(
            @JsonUnwrapped SourceRow row,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @JsonProperty("lossEvidence")
            Object lossEvidence,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @JsonProperty("repairEvidence")
            Object repairEvidence,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @JsonProperty("initialSource")
            Object initialSource) {
    }

    private final JdbcTemplate db;
    private final AssetMapper mapper;
    private final ObjectMapper json;

    /**
     * Wires the port to raw JDBC, the mapper and the JSON codec. The class-level {@code MANDATORY}
     * propagation makes the scrap application own the transaction, so the asset locks taken here are
     * held together with the scrap request and business-lock writes that surround them and can never
     * commit on their own.
     *
     * @param db JDBC handle for the locked fams_asset reads and the lifecycle updates
     * @param mapper timeline-event writer for every scrap movement recorded here
     * @param json codec used to build event details and to replay stored source snapshots
     */
    public AssetScrapPort(JdbcTemplate db, AssetMapper mapper, ObjectMapper json) {
        this.db = db;
        this.mapper = mapper;
        this.json = json;
    }

    /** Locks and snapshots a visible source before the application inserts the request and its lock. */
    public SourceRow standaloneSource(Current actor, long assetId, long version) {
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
        return SourceRow.fromRow(source);
    }

    private static final Map<String, String> ORIGINAL_CUSTODY = Map.of(
            "custody_type", "custodyType", "storage_warehouse_id", "warehouseId",
            "current_location_id", "locationId", "using_organization_id", "usingOrganizationId",
            "responsible_employee_id", "responsibleEmployeeId",
            "department_responsible_employee_id", "departmentResponsibleEmployeeId");

    /**
     * Locks and snapshots a loss-sourced scrap source. The row must already sit at the lifecycle the
     * loss flow recorded and carry the LOST exception, and the six custody fields it holds must still
     * equal the ones the loss report froze under their camelCase names, so a transfer that happened
     * between the loss report and the scrap cannot be silently adopted. An unreleased business lock of
     * any kind also blocks the source. Custody itself is left untouched: a pending scrap only changes
     * the lifecycle later, at freeze time.
     *
     * @param assetId fams_asset primary key to lock
     * @param version optimistic version the caller read; the SQL pins it directly
     * @param lifecycle lifecycle the loss flow left on the row, matched exactly
     * @param original loss report custody snapshot whose camelCase keys are compared field by field
     * @return locked source row whose fourteen snake_case keys are stored verbatim
     * @throws AssetHandoverPort.Failure 409 with code SCRAP_ASSET_OR_LOCK_CHANGED when no row matches
     *     the id, version, lifecycle and LOST exception, when the frozen custody no longer matches, or
     *     when an unreleased business lock exists
     */
    public SourceRow lossSource(long assetId, long version, String lifecycle, JsonNode original) {
        var row = lockedSourceAsset(assetId, version, lifecycle, "LOST");
        if (row == null || !matchesOriginalLossCustody(json.valueToTree(row), original)
                || !db.queryForList("SELECT id FROM fams_asset_business_lock WHERE asset_id=?"
                        + " AND released_at IS NULL FOR UPDATE", assetId).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_OR_LOCK_CHANGED", 409);
        }
        return SourceRow.fromRow(row);
    }

    /**
     * Compares the six custody fields of a stored scrap source against the custody a loss report
     * originally froze. The two sides use different key vocabularies on purpose: the source row keeps
     * the snake_case database column names it was serialized with, while the loss report keeps the
     * camelCase names it was written with, and the fixed mapping between them lives in
     * {@code ORIGINAL_CUSTODY}. Both sides are compared through {@code asText(null)}, so a missing key
     * and an explicit JSON null are treated as equal and only a real value change reports a mismatch.
     *
     * @param frozen source snapshot tree carrying the snake_case custody keys
     * @param original loss report custody tree carrying the camelCase custody keys
     * @return {@code true} only when all six mapped fields are equal, null included
     */
    public boolean matchesOriginalLossCustody(JsonNode frozen, JsonNode original) {
        return ORIGINAL_CUSTODY.entrySet().stream().allMatch(field ->
                Objects.equals(frozen.path(field.getKey()).asText(null), original.path(field.getValue()).asText(null)));
    }

    private Map<String, Object> lockedSourceAsset(long id, long version, String lifecycle, String exception) {
        var rows = db.queryForList(
                "SELECT id,asset_code,name,serial_number,lifecycle_status,exception_status,card_stage,"
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

    /**
     * Freezes a loss-sourced asset into PENDING_SCRAP. The guarded update only fires when the row is
     * still at the expected version and lifecycle, still carries the LOST exception, and an unreleased
     * SCRAP business lock whose business id and item id are both the request id already exists, so the
     * asset is never frozen before the application owns the lock. Custody and all responsibility
     * columns stay exactly as the loss left them; a SCRAP_REQUESTED event records the request id.
     *
     * @param id fams_asset primary key to freeze
     * @param version optimistic version the caller read; the row must still be at it
     * @param requestId scrap request id that must already own the unreleased lock
     * @param lifecycle lifecycle the loss flow left on the row, matched exactly
     * @param actor account id recorded as updater and event actor
     * @param time business timestamp written to the row and the event
     * @return the asset version after the update, that is {@code version} plus one
     * @throws AssetHandoverPort.Failure 409 with code SCRAP_ASSET_CHANGED when the guarded update
     *     matched no row because the version, lifecycle, exception or lock moved
     */
    public long freezeLoss(long id, long version, long requestId, String lifecycle, long actor, Instant time) {
        return freezeLinked(id, version, requestId, lifecycle, "LOST", actor, time);
    }

    /**
     * Locks and snapshots a repair-sourced scrap source. The row must still be at the expected version
     * and carry the repair lifecycle selected by {@code factory} together with a NORMAL exception,
     * which is what distinguishes an in-house repair from a factory one. No eligibility or lock check
     * runs here because the repair flow already owns the asset and its lock; this method only freezes
     * the values the scrap request will store.
     *
     * @param asset fams_asset primary key to lock
     * @param version optimistic version the caller read; the SQL pins it directly
     * @param factory {@code true} to require FACTORY_REPAIR, {@code false} to require UNDER_REPAIR
     * @return locked source row whose fourteen snake_case keys are stored verbatim
     * @throws AssetHandoverPort.Failure 409 with code SCRAP_ASSET_CHANGED when no row matches the id,
     *     version, expected repair lifecycle and NORMAL exception
     */
    public SourceRow repairSource(long asset, long version, boolean factory) {
        var row = lockedSourceAsset(asset, version, factory ? "FACTORY_REPAIR" : "UNDER_REPAIR", "NORMAL");
        if (row == null) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_CHANGED", 409);
        }
        return SourceRow.fromRow(row);
    }

    /**
     * Freezes a repair-sourced asset into PENDING_SCRAP. The guarded update only fires when the row is
     * still at the expected version, still carries the repair lifecycle selected by {@code factory}
     * with a NORMAL exception, and an unreleased SCRAP business lock owned by this request already
     * exists. Custody stays as the repair left it so the return target survives the scrap; a
     * SCRAP_REQUESTED event records the request id.
     *
     * @param id fams_asset primary key to freeze
     * @param version optimistic version the caller read; the row must still be at it
     * @param requestId scrap request id that must already own the unreleased lock
     * @param actor account id recorded as updater and event actor
     * @param time business timestamp written to the row and the event
     * @param factory {@code true} when the source lifecycle is FACTORY_REPAIR, {@code false} for
     *     UNDER_REPAIR
     * @return the asset version after the update, that is {@code version} plus one
     * @throws AssetHandoverPort.Failure 409 with code SCRAP_ASSET_CHANGED when the guarded update
     *     matched no row because the version, lifecycle, exception or lock moved
     */
    public long freezeRepair(long id, long version, long requestId, long actor, Instant time, boolean factory) {
        return freezeLinked(id, version, requestId, factory ? "FACTORY_REPAIR" : "UNDER_REPAIR", "NORMAL", actor, time);
    }

    private long freezeLinked(long id, long version, long requestId, String lifecycle, String exception,
            long actor, Instant time) {
        if (db.update(
                "UPDATE fams_asset a SET lifecycle_status='PENDING_SCRAP',version=version+1,updated_at=?,updated_by=?"
                + " WHERE a.id=? AND a.version=? AND a.lifecycle_status=? AND a.exception_status=?"
                + " AND EXISTS (SELECT 1 FROM fams_asset_business_lock b "
                + "WHERE b.asset_id=a.id AND b.business_type='SCRAP'"
                + " AND b.business_id=? AND b.business_item_id=? AND b.released_at IS NULL)",
                time, actor, id, version, lifecycle, exception, requestId, requestId) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_CHANGED", 409);
        }
        mapper.event(id, "SCRAP_REQUESTED", time,
                json.writeValueAsString(Map.of("scrapRequestId", Long.toString(requestId))), actor);
        return version + 1;
    }

    /**
     * Restores a loss-sourced asset out of PENDING_SCRAP back to the lifecycle its stored source
     * recorded. The frozen custody must still match the source and the exception must still be LOST
     * before the restore is attempted; the actual validity of the custody is then decided by
     * {@link #restoreStandalone}, which refuses rather than restores when the references have gone
     * inactive. A loss write-off that cannot be restored stays frozen for a separately authorized
     * correction.
     *
     * @param id fams_asset primary key to restore
     * @param version optimistic version the caller read; the row must still be at it
     * @param requestId scrap request id embedded in the restore event
     * @param source stored source snapshot supplying the frozen custody and the target lifecycle
     * @param actor account id recorded as updater and event actor
     * @param time business timestamp written to the row and the event
     * @return {@code true} when the lifecycle was restored, {@code false} when the frozen custody no
     *     longer matches or the references are no longer valid
     * @throws AssetHandoverPort.Failure 409 with code SCRAP_ASSET_CHANGED when the guarded restore
     *     update matched no row because the version moved or the card left PENDING_SCRAP
     */
    public boolean restoreLoss(long id, long version, long requestId, JsonNode source, long actor, Instant time) {
        return hasFrozenCustody(id, version, source, "LOST")
                && restoreStandalone(id, version, requestId, source.path("lifecycle_status").asText(), actor, time);
    }

    /**
     * Restores a standalone scrap out of PENDING_SCRAP when the request is cancelled. The source is a
     * NORMAL-exception card, so the frozen custody check uses that exception rather than LOST, and the
     * restore target lifecycle is the one the stored source recorded. As with every restore, an
     * invalid custody reports {@code false} and leaves the card frozen instead of writing a state the
     * asset domain cannot defend.
     *
     * @param asset fams_asset primary key to restore
     * @param version optimistic version the caller read; the row must still be at it
     * @param request scrap request id embedded in the restore event
     * @param source stored source snapshot supplying the frozen custody and the target lifecycle
     * @param actor account id recorded as updater and event actor
     * @param time business timestamp written to the row and the event
     * @return {@code true} when the lifecycle was restored, {@code false} when the frozen custody no
     *     longer matches or the references are no longer valid
     * @throws AssetHandoverPort.Failure 409 with code SCRAP_ASSET_CHANGED when the guarded restore
     *     update matched no row because the version moved or the card left PENDING_SCRAP
     */
    public boolean restoreStandaloneCancellation(long asset, long version, long request, JsonNode source,
            long actor, Instant time) {
        return hasFrozenCustody(asset, version, source, "NORMAL")
                && restoreStandalone(asset, version, request, source.path("lifecycle_status").asText(), actor, time);
    }

    /**
     * Restores a repair-sourced asset out of PENDING_SCRAP back into its repair lifecycle. On top of
     * the frozen custody check this path also proves that every reference the restored custody needs
     * is still active, in one locking query: the current location, the warehouse for a STORAGE card,
     * or the using organization plus an employee that still belongs to it for a PERSONAL or SHARED
     * card. A repair cannot be resumed against a deactivated place or person, so a failed reference
     * check reports {@code false} and leaves the card frozen.
     *
     * @param asset fams_asset primary key to restore
     * @param version optimistic version the caller read; the row must still be at it
     * @param requestId scrap request id embedded in the restore event
     * @param source stored source snapshot supplying the frozen custody keys
     * @param actor account id recorded as updater and event actor
     * @param time business timestamp written to the row and the event
     * @param factory {@code true} to restore FACTORY_REPAIR, {@code false} to restore UNDER_REPAIR
     * @return {@code true} when the repair lifecycle was restored, {@code false} when the frozen
     *     custody no longer matches or a required reference is no longer active
     * @throws AssetHandoverPort.Failure 409 with code SCRAP_ASSET_CHANGED when the guarded restore
     *     update matched no row because the version moved or the card left PENDING_SCRAP
     */
    public boolean restoreRepair(long asset, long version, long requestId, JsonNode source, long actor, Instant time,
            boolean factory) {
        if (!hasFrozenCustody(asset, version, source, "NORMAL")
                || db.queryForList("SELECT a.id FROM fams_asset a JOIN fams_location l ON l.id=a.current_location_id"
                        + " LEFT JOIN fams_warehouse w ON w.id=a.storage_warehouse_id"
                        + " LEFT JOIN fams_organization o ON o.id=a.using_organization_id"
                        + " LEFT JOIN fams_employee e ON e.id=COALESCE(a.responsible_employee_id,"
                        + "a.department_responsible_employee_id)"
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

    /**
     * Asserts that a repair-sourced card is still frozen with the custody its stored source recorded.
     * The row is read under a lock at the expected version with lifecycle PENDING_SCRAP and a NORMAL
     * exception, and all six custody columns must equal the source values. Steps that are about to
     * write the asset call this first so a custody change made behind the scrap's back is refused
     * rather than carried into the write. Nothing is written here.
     *
     * @param asset fams_asset primary key to check
     * @param version optimistic version the caller read; the row must still be at it
     * @param source stored source snapshot carrying the frozen snake_case custody keys
     * @throws AssetHandoverPort.Failure 409 with code SCRAP_ASSET_CHANGED when no row matches the id,
     *     version, PENDING_SCRAP lifecycle and NORMAL exception, or when any frozen custody column no
     *     longer equals the source value
     */
    public void requireFrozenRepair(long asset, long version, JsonNode source) {
        if (!hasFrozenCustody(asset, version, source, "NORMAL")) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_CHANGED", 409);
        }
    }

    /**
     * Executes a factory-replacement scrap, moving the asset from PENDING_SCRAP to SCRAPPED. Both
     * preconditions are re-checked under locks in this call: the request must still own the exact
     * unreleased lock at the pinned lock version, and the frozen repair custody must still match the
     * stored source. Custody and the responsibility columns are deliberately preserved so the
     * replacement flow can still trace what the scrapped card was; the SCRAP_EXECUTED event records
     * the request id and the FACTORY_REPLACEMENT source type.
     *
     * @param asset fams_asset primary key to scrap
     * @param version optimistic version the caller read; the row must still be at it
     * @param request scrap request id that must own the lock and is embedded in the event
     * @param lockVersion pinned version of that unreleased scrap lock
     * @param source stored source snapshot carrying the frozen snake_case custody keys
     * @param actor account id recorded as updater and event actor
     * @param time business timestamp written to the row and the event
     * @return the asset version after the update, that is {@code version} plus one
     * @throws AssetHandoverPort.Failure 409 with code SCRAP_ASSET_OR_LOCK_CHANGED when the pending
     *     state or its lock moved, 409 with code SCRAP_ASSET_CHANGED when the frozen custody drifted
     *     or the guarded update matched no row
     */
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
                json.writeValueAsString(Map.of("scrapRequestId", Long.toString(request),
                        "sourceType", "FACTORY_REPLACEMENT")),
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

    /**
     * Asserts under a lock that the asset is still pending this exact scrap request. One joined query
     * pins all four facts at once: the asset id and version, the PENDING_SCRAP lifecycle, and an
     * unreleased SCRAP business lock whose business id and item id are both the request id and whose
     * lock version equals the pinned one. Every execution path calls this before writing, so a
     * superseded or released lock can never be executed against. Nothing is written here.
     *
     * @param assetId fams_asset primary key that must be pending
     * @param version optimistic version the caller read; the row must still be at it
     * @param requestId scrap request id that must own the unreleased lock
     * @param lockVersion pinned version of that lock, guarding against a re-issued one
     * @throws AssetHandoverPort.Failure 409 with code SCRAP_ASSET_OR_LOCK_CHANGED when no row matches
     *     all four conditions
     */
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

    /**
     * Restores a pending scrap after a correction, putting the asset back into storage custody at an
     * approved BIN. The target location is pinned by version and must be an ACTIVE BIN of an ACTIVE
     * warehouse, and its warehouse is taken from the location row rather than from the caller, so a
     * correction cannot place an asset in a bin of another warehouse. Physical identity is verified
     * against the locked row: the observed code must equal the stored one and the observed serial must
     * equal the stored one whenever the asset has a serial. The write restores IN_STOCK and STORAGE
     * custody, sets both place columns and clears the using organization and both responsibility
     * columns, then records SCRAP_CORRECTED_RESTORED with the corrected warehouse id.
     *
     * @param assetId fams_asset primary key to restore
     * @param version optimistic version the caller read; the row must still be at it
     * @param requestId scrap request id embedded in the restore event
     * @param locationId BIN location the corrected asset is restored into, pinned by version
     * @param locationVersion version of that location the picker read
     * @param observedCode asset code observed physically, compared after stripping whitespace
     * @param observedSerial serial observed physically, compared only when the asset stores one
     * @param actorId account id recorded as updater and event actor
     * @param time business timestamp written to the row and the event
     * @return the asset version after the update, that is {@code version} plus one
     * @throws AssetHandoverPort.Failure 409 with code SCRAP_CORRECTION_TARGET_CHANGED when the pinned
     *     location is missing, inactive, not a BIN or its warehouse is inactive, 409 with code
     *     SCRAP_PHYSICAL_IDENTITY_CHANGED when the observed code or serial differs or the card is no
     *     longer PENDING_SCRAP and NORMAL, and 409 with code SCRAP_ASSET_CHANGED when the guarded
     *     update matched no row
     */
    public long restoreCorrected(long assetId, long version, long requestId, long locationId, long locationVersion,
            String observedCode, String observedSerial, long actorId, Instant time) {
        var warehouses = db.queryForList("SELECT l.warehouse_id FROM fams_location l"
                + " JOIN fams_warehouse w ON w.id=l.warehouse_id WHERE l.id=? AND l.version=?"
                + " AND l.status='ACTIVE' AND l.location_type='BIN' AND w.status='ACTIVE' FOR UPDATE",
                locationId, locationVersion);
        if (warehouses.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_TARGET_CHANGED", 409);
        }
        var asset = db.queryForMap("SELECT asset_code,serial_number,lifecycle_status,exception_status"
                + " FROM fams_asset WHERE id=? FOR UPDATE", assetId);
        if (!Objects.equals(asset.get("asset_code"), observedCode.strip())
                || asset.get("serial_number") != null
                        && !Objects.equals(asset.get("serial_number"),
                                observedSerial == null ? null : observedSerial.strip())
                || !"PENDING_SCRAP".equals(asset.get("lifecycle_status"))
                || !"NORMAL".equals(asset.get("exception_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_PHYSICAL_IDENTITY_CHANGED", 409);
        }
        if (db.update("UPDATE fams_asset SET lifecycle_status='IN_STOCK',custody_type='STORAGE',"
                + "storage_warehouse_id=?,current_location_id=?,using_organization_id=NULL,"
                + "responsible_employee_id=NULL,department_responsible_employee_id=NULL,"
                + "version=version+1,updated_at=?,updated_by=? WHERE id=? AND version=?"
                + " AND lifecycle_status='PENDING_SCRAP'",
                warehouses.getFirst().get("warehouse_id"), locationId, time, actorId, assetId, version) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_CHANGED", 409);
        }
        mapper.event(assetId, "SCRAP_CORRECTED_RESTORED", time,
                json.writeValueAsString(Map.of("scrapRequestId", Long.toString(requestId),
                        "correctedWarehouseId", Long.toString(
                                ((Number) warehouses.getFirst().get("warehouse_id")).longValue()))),
                actorId);
        return version + 1;
    }

    /**
     * Checks under a lock that a location is still an ACTIVE BIN of an ACTIVE warehouse at the pinned
     * version. Unlike {@link #matchesTemporaryLocation} this requires the bin type and an owning
     * warehouse, because it guards restorations that return an asset into formal storage. It reports a
     * mismatch instead of throwing so the caller can answer with its own error code.
     *
     * @param locationId fams_location primary key to verify
     * @param locationVersion version of that location the picker read
     * @return {@code true} only when the pinned BIN and its warehouse are both still active
     */
    public boolean matchesActiveBin(long locationId, long locationVersion) {
        return !db.queryForList("SELECT l.id FROM fams_location l JOIN fams_warehouse w ON w.id=l.warehouse_id"
                + " WHERE l.id=? AND l.version=? AND l.status='ACTIVE' AND l.location_type='BIN'"
                + " AND w.status='ACTIVE' FOR UPDATE", locationId, locationVersion).isEmpty();
    }

    /** Reversal re-accepts an already scrapped asset into an approved bin; original scrap facts stay recorded. */
    public long reverseToStock(long assetId, long version, long requestId, long locationId, long locationVersion,
            String observedCode, String observedSerial, long actorId, Instant time) {
        var warehouses = db.queryForList("SELECT l.warehouse_id FROM fams_location l"
                + " JOIN fams_warehouse w ON w.id=l.warehouse_id WHERE l.id=? AND l.version=?"
                + " AND l.status='ACTIVE' AND l.location_type='BIN' AND w.status='ACTIVE' FOR UPDATE",
                locationId, locationVersion);
        if (warehouses.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_TARGET_CHANGED", 409);
        }
        var asset = db.queryForMap("SELECT asset_code,serial_number,lifecycle_status,exception_status"
                + " FROM fams_asset WHERE id=? FOR UPDATE", assetId);
        if (!Objects.equals(asset.get("asset_code"), observedCode.strip())
                || asset.get("serial_number") != null
                        && !Objects.equals(asset.get("serial_number"),
                                observedSerial == null ? null : observedSerial.strip())
                || !"SCRAPPED".equals(asset.get("lifecycle_status"))
                || !"NORMAL".equals(asset.get("exception_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_PHYSICAL_IDENTITY_CHANGED", 409);
        }
        if (db.update("UPDATE fams_asset SET lifecycle_status='IN_STOCK',custody_type='STORAGE',"
                + "storage_warehouse_id=?,current_location_id=?,using_organization_id=NULL,"
                + "responsible_employee_id=NULL,department_responsible_employee_id=NULL,"
                + "version=version+1,updated_at=?,updated_by=? WHERE id=? AND version=?"
                + " AND lifecycle_status='SCRAPPED'",
                warehouses.getFirst().get("warehouse_id"), locationId, time, actorId, assetId, version) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_CHANGED", 409);
        }
        mapper.event(assetId, "SCRAP_REVERSED_RESTORED", time,
                json.writeValueAsString(Map.of("scrapRequestId", Long.toString(requestId),
                        "reversedWarehouseId", Long.toString(
                                ((Number) warehouses.getFirst().get("warehouse_id")).longValue()))),
                actorId);
        return version + 1;
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
        if (("IN_STOCK".equals(lifecycle) || "FACTORY_REPAIR".equals(lifecycle))
                && "STORAGE".equals(asset.get("custody_type"))) {
            Object warehouse = asset.get("storage_warehouse_id");
            valid = "BIN".equals(location.get("location_type"))
                    && Objects.equals(warehouse, location.get("warehouse_id"))
                    && !db.queryForList("SELECT id FROM fams_warehouse WHERE id=? AND status='ACTIVE' FOR UPDATE",
                            warehouse).isEmpty();
        } else if (List.of("IN_USE", "BORROWED", "FACTORY_REPAIR").contains(lifecycle)) {
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

    /**
     * Executes a standalone scrap, moving the asset from PENDING_SCRAP to SCRAPPED. The pending state
     * and its lock are re-checked first, then the operator's physical observation is compared against
     * the locked row: the code must match exactly after stripping and the serial must match whenever
     * the asset stores one. The location the disposal form named is verified as active at its pinned
     * version, but it is deliberately not required to be a BIN and may have no warehouse, because a
     * scrapped asset leaves formal storage. Custody and the responsibility columns are preserved so the
     * card still records where it was when it was scrapped; the SCRAP_EXECUTED event records only the
     * request id.
     *
     * @param assetId fams_asset primary key to scrap
     * @param version optimistic version the caller read; the row must still be at it
     * @param requestId scrap request id that must own the lock and is embedded in the event
     * @param lockVersion pinned version of that unreleased scrap lock
     * @param observedCode asset code observed physically, compared after stripping whitespace
     * @param observedSerial serial observed physically, compared only when the asset stores one
     * @param locationId disposal location named by the form, pinned by version
     * @param locationVersion version of that location the picker read
     * @param actorId account id recorded as updater and event actor
     * @param time business timestamp written to the row and the event
     * @return the asset version after the update, that is {@code version} plus one
     * @throws AssetHandoverPort.Failure 409 with code SCRAP_ASSET_OR_LOCK_CHANGED when the pending
     *     state or its lock moved, 409 with code SCRAP_PHYSICAL_IDENTITY_CHANGED when the observed
     *     code or serial differs or the exception status is no longer NORMAL, 409 with code
     *     SCRAP_LOCATION_CHANGED when the pinned location is inactive or its warehouse is inactive,
     *     and 409 with code SCRAP_ASSET_CHANGED when the guarded update matched no row
     */
    public long executeStandalone(long assetId, long version, long requestId, long lockVersion,
            String observedCode, String observedSerial, long locationId, long locationVersion,
            long actorId, Instant time) {
        requirePending(assetId, version, requestId, lockVersion);
        var asset = db.queryForMap("SELECT asset_code,serial_number,exception_status"
                + " FROM fams_asset WHERE id=? FOR UPDATE", assetId);
        if (!Objects.equals(asset.get("asset_code"), observedCode.strip())
                || asset.get("serial_number") != null
                        && !Objects.equals(asset.get("serial_number"),
                                observedSerial == null ? null : observedSerial.strip())
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

    /**
     * Executes a loss-sourced scrap, moving the asset from PENDING_SCRAP to SCRAPPED. The pending
     * state and its lock are re-checked first, then the frozen loss custody must still match the
     * stored source and the guarded update must hit a row that is still PENDING_SCRAP with the LOST
     * exception. Custody and the responsibility columns stay frozen, so the loss write-off remains
     * traceable after the card is scrapped; the SCRAP_EXECUTED event records the request id and the
     * LOSS source type.
     *
     * @param asset fams_asset primary key to scrap
     * @param version optimistic version the caller read; the row must still be at it
     * @param requestId scrap request id that must own the lock and is embedded in the event
     * @param lockVersion pinned version of that unreleased scrap lock
     * @param source stored source snapshot carrying the frozen snake_case custody keys
     * @param actor account id recorded as updater and event actor
     * @param time business timestamp written to the row and the event
     * @return the asset version after the update, that is {@code version} plus one
     * @throws AssetHandoverPort.Failure 409 with code SCRAP_ASSET_OR_LOCK_CHANGED when the pending
     *     state or its lock moved, and 409 with code SCRAP_LOSS_ASSET_CHANGED when the frozen custody
     *     drifted or the guarded update matched no row
     */
    public long executeLoss(long asset, long version, long requestId, long lockVersion,
            JsonNode source, long actor, Instant time) {
        requirePending(asset, version, requestId, lockVersion);
        if (!hasFrozenCustody(asset, version, source, "LOST")
                || db.update(
                        "UPDATE fams_asset SET lifecycle_status='SCRAPPED',version=version+1,updated_at=?,updated_by=?"
                        + " WHERE id=? AND version=? AND lifecycle_status='PENDING_SCRAP' AND exception_status='LOST'",
                time, actor, asset, version) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_LOSS_ASSET_CHANGED", 409);
        }
        mapper.event(asset, "SCRAP_EXECUTED", time,
                json.writeValueAsString(Map.of("scrapRequestId", Long.toString(requestId), "sourceType", "LOSS")),
                actor);
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

    /** Only an un-disposed scrapped asset that no later business has locked may be reversed. */
    public void requireReversibleAsset(long id, long version) {
        if (db.queryForList("SELECT id FROM fams_asset WHERE id=? AND version=?"
                        + " AND card_stage='ACTIVE' AND lifecycle_status='SCRAPPED' "
                        + "AND exception_status='NORMAL' FOR UPDATE",
                        id, version).isEmpty()
                || !db.queryForList("SELECT id FROM fams_asset_business_lock WHERE asset_id=?"
                        + " AND released_at IS NULL FOR UPDATE", id).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_OR_LOCK_CHANGED", 409);
        }
    }

    /** A written-off scrap whose physical item came back stays scrapped until a separate re-acceptance approval. */
    public void requireRecoverableAsset(long id, long version) {
        if (db.queryForList("SELECT id FROM fams_asset WHERE id=? AND version=?"
                        + " AND card_stage='ACTIVE' AND lifecycle_status='SCRAPPED'"
                        + " AND exception_status IN ('NORMAL','LOST') FOR UPDATE",
                        id, version).isEmpty()
                || !db.queryForList("SELECT id FROM fams_asset_business_lock WHERE asset_id=?"
                        + " AND released_at IS NULL FOR UPDATE", id).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_OR_LOCK_CHANGED", 409);
        }
    }

    /**
     * Checks under a lock that a temporary holding location is still active at the pinned version. The
     * bin type is not required and a location without a warehouse is accepted, because a recovered
     * scrapped item is parked somewhere provisional before re-acceptance rather than being put back
     * into formal storage; an owning warehouse is only checked when the location actually has one. It
     * reports a mismatch instead of throwing so the caller answers with its own error code.
     *
     * @param locationId fams_location primary key to verify
     * @param locationVersion version of that location the picker read
     * @return {@code true} only when the pinned location is active and any owning warehouse is active
     */
    public boolean matchesTemporaryLocation(long locationId, long locationVersion) {
        return !db.queryForList("SELECT l.id FROM fams_location l LEFT JOIN fams_warehouse w ON w.id=l.warehouse_id"
                + " WHERE l.id=? AND l.version=? AND l.status='ACTIVE'"
                + " AND (l.warehouse_id IS NULL OR w.status='ACTIVE') FOR UPDATE",
                locationId, locationVersion).isEmpty();
    }

    /**
     * Checks under a lock that a temporary holding location is still active, without pinning a version.
     * This is the weaker sibling of {@link #matchesTemporaryLocation}: re-acceptance uses it for the
     * location where the recovered item was already parked, which the operator did not choose in this
     * request and therefore holds no version for. The bin type is not required and a location without a
     * warehouse is accepted; an owning warehouse is only checked when there is one.
     *
     * @param locationId fams_location primary key to verify
     * @return {@code true} only when the location is active and any owning warehouse is active
     */
    public boolean temporaryLocationActive(long locationId) {
        return !db.queryForList("SELECT l.id FROM fams_location l LEFT JOIN fams_warehouse w ON w.id=l.warehouse_id"
                + " WHERE l.id=? AND l.status='ACTIVE'"
                + " AND (l.warehouse_id IS NULL OR w.status='ACTIVE') FOR UPDATE", locationId).isEmpty();
    }

    /** Re-acceptance restores the original card; the loss write-off, closed loan and scrap facts stay recorded. */
    public long reregisterToStock(long assetId, long version, long requestId, long temporaryLocationId,
            long targetLocationId, long targetLocationVersion, String observedCode, String observedSerial,
            long actorId, Instant time) {
        var warehouses = db.queryForList("SELECT l.warehouse_id FROM fams_location l"
                + " JOIN fams_warehouse w ON w.id=l.warehouse_id WHERE l.id=? AND l.version=?"
                + " AND l.status='ACTIVE' AND l.location_type='BIN' AND w.status='ACTIVE' FOR UPDATE",
                targetLocationId, targetLocationVersion);
        if (warehouses.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_TARGET_CHANGED", 409);
        }
        if (!temporaryLocationActive(temporaryLocationId)) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_TEMPORARY_CHANGED", 409);
        }
        var asset = db.queryForMap("SELECT asset_code,serial_number,lifecycle_status,exception_status"
                + " FROM fams_asset WHERE id=? FOR UPDATE", assetId);
        if (!Objects.equals(asset.get("asset_code"), observedCode.strip())
                || asset.get("serial_number") != null
                        && !Objects.equals(asset.get("serial_number"),
                                observedSerial == null ? null : observedSerial.strip())
                || !"SCRAPPED".equals(asset.get("lifecycle_status"))
                || !List.of("NORMAL", "LOST").contains(asset.get("exception_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_PHYSICAL_IDENTITY_CHANGED", 409);
        }
        if (db.update("UPDATE fams_asset SET lifecycle_status='IN_STOCK',custody_type='STORAGE',"
                + "exception_status='NORMAL',storage_warehouse_id=?,current_location_id=?,"
                + "using_organization_id=NULL,responsible_employee_id=NULL,"
                + "department_responsible_employee_id=NULL,version=version+1,updated_at=?,updated_by=?"
                + " WHERE id=? AND version=? AND lifecycle_status='SCRAPPED'",
                warehouses.getFirst().get("warehouse_id"), targetLocationId, time, actorId, assetId, version) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_CHANGED", 409);
        }
        mapper.event(assetId, "SCRAP_RECOVERED_REREGISTERED", time,
                json.writeValueAsString(Map.of("scrapRequestId", Long.toString(requestId),
                        "recoveredWarehouseId", Long.toString(
                                ((Number) warehouses.getFirst().get("warehouse_id")).longValue()),
                        "temporaryLocationId", Long.toString(temporaryLocationId))),
                actorId);
        return version + 1;
    }

    /**
     * Pages the assets the actor may submit for scrap. The predicate is the same one
     * {@link #standaloneSource} enforces, so a picker never lists a card that would later be refused:
     * ACTIVE, NORMAL and only IN_STOCK or IN_USE, because a borrowed asset must come back before it can
     * be scrapped. Visibility is role-gated rather than ownership-gated: a global applicant sees
     * everything, an EMPLOYEE sees only cards where they are the personal responsible employee, and a
     * DEPARTMENT_ASSET_MANAGER sees the cards of their own using organization. Any unreleased business
     * lock excludes the card. Rows are ordered by descending asset id and the total is counted with the
     * identical scope array, so the footer matches the pages.
     *
     * @param actor resolved actor supplying the role, employee and organization predicates
     * @param page 1-based page number; the offset is derived as {@code (page - 1) * size}
     * @param size page length passed straight to the SQL {@code LIMIT}
     * @return eligible page of picker rows plus the total count of matches
     */
    public EligiblePage eligible(Current actor, int page, int size) {
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
                .map(row -> new EligibleAsset(row.get("id").toString(), (String) row.get("asset_code"),
                        (String) row.get("name"), ((Number) row.get("version")).longValue())).toList();
        return new EligiblePage(items, Objects.requireNonNull(
                db.queryForObject("SELECT COUNT(*)" + where, Long.class, scope)), page, size);
    }
}
