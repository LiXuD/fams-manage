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

/** The asset domain owns formal custody writes; the caller owns approval and business locks. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class AssetHandoverPort {
    /**
     * Locked in-stock asset facts shared with requisition handover. Snake_case JSON names are
     * persisted verbatim into {@code formal_custody_snapshot_json} (AssetTransferPort.Snapshot
     * precedent), so every key of the former row projection is kept, explicit nulls included.
     */
    public record Stock(
            @JsonProperty("id") Long id,
            @JsonProperty("version") long version,
            @JsonProperty("asset_code") String assetCode,
            @JsonProperty("name") String name,
            @JsonProperty("model") String model,
            @JsonProperty("custody_type") String custodyType,
            @JsonProperty("storage_warehouse_id") Long storageWarehouseId,
            @JsonProperty("current_location_id") Long currentLocationId) {
        static Stock fromRow(Map<String, Object> row) {
            return new Stock(
                    ((Number) row.get("id")).longValue(),
                    ((Number) row.get("version")).longValue(),
                    (String) row.get("asset_code"),
                    (String) row.get("name"),
                    (String) row.get("model"),
                    (String) row.get("custody_type"),
                    row.get("storage_warehouse_id") == null
                            ? null
                            : ((Number) row.get("storage_warehouse_id")).longValue(),
                    row.get("current_location_id") == null
                            ? null
                            : ((Number) row.get("current_location_id")).longValue());
        }
    }

    /**
     * Cross-domain handover failure carrying both the stable error code and the HTTP status the
     * calling domain must return. Every asset port throws this type rather than its own exception so
     * requisition, transfer, repair, loss and return callers can translate asset-side refusals
     * uniformly; the code is the exception message and is never localized.
     */
    public static final class Failure extends RuntimeException {
        private final int status;

        /**
         * Creates a failure whose message is the error code itself.
         *
         * @param code stable uppercase error code, used verbatim as the exception message and as the
         *     {@code ErrorResponse} code
         * @param status HTTP status the calling domain must answer with, typically 400, 403, 404 or
         *     409
         */
        public Failure(String code, int status) {
            super(code);
            this.status = status;
        }

        /**
         * Exposes the HTTP status paired with the error code.
         *
         * @return status passed to the constructor, for example 409 for a concurrent-change refusal
         */
        public int status() {
            return status;
        }
    }

    private final JdbcTemplate db;
    private final AssetMapper mapper;
    private final AssetSecretCodec codec;
    private final ObjectMapper json;

    AssetHandoverPort(
            JdbcTemplate db, AssetMapper mapper, AssetSecretCodec codec, ObjectMapper json) {
        this.db = db;
        this.mapper = mapper;
        this.codec = codec;
        this.json = json;
    }

    /**
     * Locks one asset and proves it is still handable in-stock stock. The {@code FOR UPDATE} lock is
     * held until the caller's transaction ends, so a handover that validated the source cannot be
     * overtaken by a concurrent write. Only an ACTIVE card that is IN_STOCK, NORMAL and in STORAGE
     * custody may be handed over; anything else is a concurrent change rather than a missing asset.
     *
     * @param id fams_asset primary key to lock
     * @param version optimistic version the caller read when it built the handover
     * @return locked in-stock facts whose snake_case keys are persisted verbatim by the caller
     * @throws Failure 404 when no such asset exists, or 409 with code ASSET_HANDOVER_CHANGED when
     *     the version, card stage, lifecycle, exception status or custody type no longer match
     */
    public Stock stock(long id, long version) {
        var rows = db.queryForList("SELECT * FROM fams_asset WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new Failure("ASSET_NOT_FOUND", 404);
        }
        var row = rows.getFirst();
        if (((Number) row.get("version")).longValue() != version
                || !"ACTIVE".equals(row.get("card_stage"))
                || !"IN_STOCK".equals(row.get("lifecycle_status"))
                || !"NORMAL".equals(row.get("exception_status"))
                || !"STORAGE".equals(row.get("custody_type"))) {
            throw new Failure("ASSET_HANDOVER_CHANGED", 409);
        }
        return Stock.fromRow(row);
    }

    /**
     * Verifies that the operator is physically holding the asset being handed over. The observed
     * string is accepted either as the exact asset code or as a scan of that same asset's current
     * tag token, which is resolved through the secret codec so the raw token is never compared or
     * logged. A tag belonging to a different asset is a physical mismatch, and possession of a tag
     * never grants receipt rights by itself. No lock is taken because the surrounding write already
     * holds one.
     *
     * @param assetId fams_asset primary key the operation targets
     * @param observed asset code typed or scanned by the operator, or a scanned tag token
     * @return the authoritative asset code of {@code assetId}, for the caller to record
     * @throws Failure 404 when the asset does not exist, or 409 with code ASSET_PHYSICAL_MISMATCH
     *     when the observed value is neither the asset code nor a tag token of that same asset
     */
    public String observedCode(long assetId, String observed) {
        var row = db.queryForList("SELECT asset_code FROM fams_asset WHERE id=?", assetId);
        if (row.isEmpty()) {
            throw new Failure("ASSET_NOT_FOUND", 404);
        }
        String code = row.getFirst().get("asset_code").toString();
        if (code.equals(observed)) {
            return code;
        }
        // Scan results may carry the current opaque tag; possession never grants receipt rights.
        Long tagged = mapper.assetIdByTokenHash(codec.hash(observed));
        if (tagged == null || tagged != assetId) {
            throw new Failure("ASSET_PHYSICAL_MISMATCH", 409);
        }
        return code;
    }

    /**
     * Writes formal receipt of a requisition handover. The asset must still be lockable in-stock
     * stock at the expected version; the write then moves it to IN_USE under the requested custody,
     * clears the storage warehouse, routes the receiver employee to the personal or the shared
     * responsibility column and records a REQUISITION_RECEIVED event. Approval and the requisition
     * business lock are the caller's responsibility and are not re-checked here.
     *
     * @param id fams_asset primary key to receive
     * @param expectedVersion optimistic version the caller validated against
     * @param custody target custody type, must be PERSONAL or SHARED
     * @param organization using organization id written to the card
     * @param employee receiver employee id, stored in the column selected by {@code custody}
     * @param location current location id written to the card
     * @param actor account id recorded as updater and event actor
     * @param request requisition request id embedded in the event summary
     * @param time business timestamp written to the row and the event
     * @throws Failure 404 when the asset is missing, or 409 with code ASSET_HANDOVER_CHANGED when the
     *     source is no longer handable in-stock stock or the guarded update matched no row
     * @throws IllegalArgumentException with marker ASSET_CUSTODY_INVALID when {@code custody} is
     *     neither PERSONAL nor SHARED
     */
    public void receive(
            long id,
            long expectedVersion,
            String custody,
            long organization,
            long employee,
            long location,
            long actor,
            long request,
            Instant time) {
        assign(id, expectedVersion, custody, organization, employee, location, actor, request, time,
                "IN_USE", "REQUISITION_RECEIVED");
    }

    /**
     * Writes formal receipt of a borrowing handover. Custody is forced to PERSONAL because a
     * borrowed asset always has a single named borrower, the lifecycle becomes BORROWED and the
     * event recorded is BORROW_RECEIVED. As with {@link #receive}, the loan row and its business
     * lock are the caller's responsibility and are not re-checked here.
     *
     * @param id fams_asset primary key to borrow
     * @param expectedVersion optimistic version the caller validated against
     * @param organization using organization id written to the card
     * @param employee borrower employee id, stored in the personal responsibility column
     * @param location current location id written to the card
     * @param actor account id recorded as updater and event actor
     * @param request borrowing request id embedded in the event summary
     * @param time business timestamp written to the row and the event
     * @throws Failure 404 when the asset is missing, or 409 with code ASSET_HANDOVER_CHANGED when the
     *     source is no longer handable in-stock stock or the guarded update matched no row
     */
    public void borrow(
            long id, long expectedVersion, long organization, long employee, long location,
            long actor, long request, Instant time) {
        assign(id, expectedVersion, "PERSONAL", organization, employee, location, actor, request,
                time, "BORROWED", "BORROW_RECEIVED");
    }

    private void assign(
            long id, long expectedVersion, String custody, long organization, long employee,
            long location, long actor, long request, Instant time, String lifecycle, String event) {
        stock(id, expectedVersion);
        if (!"PERSONAL".equals(custody) && !"SHARED".equals(custody)) {
            throw new IllegalArgumentException("ASSET_CUSTODY_INVALID");
        }
        if (db.update(
                        "UPDATE fams_asset SET lifecycle_status=?,custody_type=?,"
                            + "storage_warehouse_id=NULL,current_location_id=?,using_organization_id=?,"
                            + "responsible_employee_id=?,department_responsible_employee_id=?,"
                            + "version=version+1,updated_by=?,updated_at=?"
                            + " WHERE id=? AND version=?",
                        lifecycle,
                        custody,
                        location,
                        organization,
                        "PERSONAL".equals(custody) ? employee : null,
                        "SHARED".equals(custody) ? employee : null,
                        actor,
                        Timestamp.from(time),
                        id,
                        expectedVersion)
                != 1) {
            throw new Failure("ASSET_HANDOVER_CHANGED", 409);
        }
        mapper.event(
                id,
                event,
                time,
                json.writeValueAsString(
                        Map.of(
                                "requestId",
                                Long.toString(request),
                                "custodyType",
                                custody,
                                "receiverEmployeeId",
                                Long.toString(employee))),
                actor);
    }
}
