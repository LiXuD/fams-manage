package com.fams.asset;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Factory movements change lifecycle, preserving the frozen formal custody and return target. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class AssetFactoryRepairPort {
    /**
     * Locked physical identity at factory dispatch. CamelCase JSON names are persisted verbatim
     * into {@code fams_repair_factory_round.original_identity_json} and replayed by
     * requireOriginal, so every key of the former projection is kept, explicit null serials
     * included (AssetTransferPort.Snapshot precedent).
     */
    public record Identity(
            @JsonProperty("assetId") String assetId,
            @JsonProperty("version") long version,
            @JsonProperty("assetCode") String assetCode,
            @JsonProperty("name") String name,
            @JsonProperty("serialNumber") String serialNumber,
            @JsonProperty("lifecycle") String lifecycle) {

        static Identity of(long asset, long version, Map<String, Object> row, String lifecycle) {
            return new Identity(
                    Long.toString(asset),
                    version,
                    (String) row.get("asset_code"),
                    (String) row.get("name"),
                    (String) row.get("serial_number"),
                    lifecycle);
        }
    }

    private final JdbcTemplate db;
    private final AssetMapper mapper;
    private final AssetHandoverPort identity;
    private final ObjectMapper json;

    /**
     * Wires the port to raw JDBC, the mapper, the physical-identity verifier and the JSON codec. The
     * class-level {@code MANDATORY} propagation means the repair service must already have opened
     * the transaction, so the asset locks taken here are held together with the factory-round write
     * instead of committing separately.
     *
     * @param db JDBC handle for the locked fams_asset reads and the factory movement updates
     * @param mapper timeline-event writer for the factory dispatch, receipt and parts events
     * @param identity handover port reused to verify the operator physically holds the asset
     * @param json codec used to replay the frozen original identity and to build event details
     */
    public AssetFactoryRepairPort(
            JdbcTemplate db, AssetMapper mapper, AssetHandoverPort identity, ObjectMapper json) {
        this.db = db;
        this.mapper = mapper;
        this.identity = identity;
        this.json = json;
    }

    /**
     * Locks the asset and freezes its physical identity for a factory movement. The lifecycle
     * argument selects which side of the movement is being recorded and must be the value actually
     * stored on the row, so a dispatch cannot be recorded against an asset that was never received
     * and vice versa. The returned identity is what the caller persists as the round's original
     * identity and replays on every later step of the same round.
     *
     * @param asset fams_asset primary key to lock
     * @param version optimistic version the caller read; it is echoed into the identity unchanged
     * @param lifecycle expected stored lifecycle, either UNDER_REPAIR or FACTORY_REPAIR
     * @return locked physical identity with frozen camelCase keys and the expected lifecycle
     * @throws IllegalArgumentException when {@code lifecycle} is neither UNDER_REPAIR nor
     *     FACTORY_REPAIR
     * @throws AssetHandoverPort.Failure 404 with code REPAIR_FACTORY_ASSET_NOT_FOUND when the asset
     *     does not exist, or 409 with code REPAIR_FACTORY_ASSET_CHANGED when the version, lifecycle,
     *     card stage or exception status no longer match the expected movement source
     */
    public Identity snapshot(long asset, long version, String lifecycle) {
        if (!java.util.List.of("UNDER_REPAIR", "FACTORY_REPAIR").contains(lifecycle)) {
            throw new IllegalArgumentException("Unsupported factory lifecycle");
        }
        var rows = db.queryForList("SELECT * FROM fams_asset WHERE id=? FOR UPDATE", asset);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("REPAIR_FACTORY_ASSET_NOT_FOUND", 404);
        }
        var row = rows.getFirst();
        if (((Number) row.get("version")).longValue() != version
                || !lifecycle.equals(row.get("lifecycle_status"))
                || !"ACTIVE".equals(row.get("card_stage"))
                || !"NORMAL".equals(row.get("exception_status"))) {
            throw new AssetHandoverPort.Failure("REPAIR_FACTORY_ASSET_CHANGED", 409);
        }
        return Identity.of(asset, version, row, lifecycle);
    }

    /**
     * Replays the round's frozen original identity against the freshly locked one. Only asset id,
     * asset code and serial number are compared: those three are what proves the physical item is
     * still the one dispatched, while name and lifecycle are allowed to have moved on. A stored
     * explicit-null serial is compared as null rather than as the empty string, so an asset that
     * never had a serial cannot be matched against a later-entered one.
     *
     * @param current identity just produced by {@link #snapshot(long, long, String)}
     * @param originalJson original_identity_json persisted when the factory round was opened
     * @throws AssetHandoverPort.Failure 409 with code REPAIR_FACTORY_ORIGINAL_CHANGED when the id,
     *     code or serial no longer match the frozen original
     */
    public void requireOriginal(Identity current, String originalJson) {
        var original = json.readTree(originalJson);
        if (!Objects.equals(current.assetId(), original.path("assetId").asText())
                || !Objects.equals(current.assetCode(), original.path("assetCode").asText())
                || !Objects.equals(
                        current.serialNumber(),
                        original.path("serialNumber").isNull()
                                ? null
                                : original.path("serialNumber").asText())) {
            throw new AssetHandoverPort.Failure("REPAIR_FACTORY_ORIGINAL_CHANGED", 409);
        }
    }

    /** Append a non-private trace; technical before/after evidence remains with repair. */
    public long recordPartsObservation(
            long asset,
            long version,
            String originalJson,
            String observedCode,
            String observedSerial,
            long actor,
            long repair,
            long round,
            long change,
            Instant observedAt,
            Instant recordedAt) {
        var current = snapshot(asset, version, "UNDER_REPAIR");
        requireOriginal(current, originalJson);
        identity.observedCode(asset, observedCode);
        String serial = current.serialNumber();
        if (serial != null
                && (observedSerial == null
                        || !serial.strip()
                                .toUpperCase(Locale.ROOT)
                                .equals(observedSerial.strip().toUpperCase(Locale.ROOT)))) {
            throw new AssetHandoverPort.Failure("REPAIR_FACTORY_ORIGINAL_SERIAL_MISMATCH", 409);
        }
        if (db.update(
                        "UPDATE fams_asset SET version=version+1,updated_by=?,updated_at=?"
                                + " WHERE id=? AND version=?",
                        actor,
                        recordedAt,
                        asset,
                        version)
                != 1) {
            throw new AssetHandoverPort.Failure("REPAIR_FACTORY_ASSET_CHANGED", 409);
        }
        mapper.event(
                asset,
                "REPAIR_FACTORY_PARTS_OBSERVED",
                recordedAt,
                json.writeValueAsString(
                        Map.of(
                                "repairId",
                                Long.toString(repair),
                                "roundId",
                                Long.toString(round),
                                "partsChangeId",
                                Long.toString(change),
                                "observedAt",
                                observedAt.toString())),
                actor);
        return version + 1;
    }

    /**
     * Records one factory movement: dispatch away to the factory or receipt of the original back.
     * Only {@code lifecycle_status} is written, so the frozen formal custody and return target stay
     * untouched while the item is physically absent. Both directions re-verify the round's original
     * identity, the operator's physical possession of the asset and, when the asset has a serial,
     * the observed serial against it case-insensitively. Receipt additionally requires a real
     * location pinned by version whose warehouse is still active, because the asset returns to a
     * place that must exist. A trace event is written in both directions with the actual business
     * time kept separate from the recording time.
     *
     * @param asset fams_asset primary key to move
     * @param version optimistic version the caller read; the row must still be at it
     * @param originalJson original_identity_json persisted when the factory round was opened
     * @param receiving {@code true} to receive the original back, {@code false} to dispatch it away
     * @param observedCode asset code or scanned tag token proving physical possession
     * @param observedSerial serial read off the physical item, compared only when one is stored
     * @param actualLocation location the original actually returned to, required when receiving
     * @param locationVersion pinned version of that location, required when receiving
     * @param actor account id recorded as updater and event actor
     * @param repair repair request id embedded in the event detail
     * @param round factory round id embedded in the event detail
     * @param actualTime business time the movement physically happened
     * @param recordedTime time the movement was recorded, stamped on the row and the event
     * @return the asset version after the guarded update, that is {@code version} plus one
     * @throws AssetHandoverPort.Failure 404 or 409 as raised by {@link #snapshot}, 409 with code
     *     REPAIR_FACTORY_ORIGINAL_CHANGED or REPAIR_FACTORY_ORIGINAL_SERIAL_MISMATCH when identity
     *     replay fails, 409 with code ASSET_PHYSICAL_MISMATCH or ASSET_NOT_FOUND from the possession
     *     check, 400 with code REPAIR_FACTORY_LOCATION_REQUIRED when a receipt omits the location or
     *     its version, 409 with code REPAIR_FACTORY_LOCATION_CHANGED when that location or its
     *     warehouse is gone or inactive, and 409 with code REPAIR_FACTORY_ASSET_CHANGED when the
     *     guarded update matched no row
     */
    public long move(
            long asset,
            long version,
            String originalJson,
            boolean receiving,
            String observedCode,
            String observedSerial,
            Long actualLocation,
            Long locationVersion,
            long actor,
            long repair,
            long round,
            Instant actualTime,
            Instant recordedTime) {
        var current = snapshot(asset, version, receiving ? "FACTORY_REPAIR" : "UNDER_REPAIR");
        requireOriginal(current, originalJson);
        identity.observedCode(asset, observedCode);
        String serial = current.serialNumber();
        if (serial != null
                && (observedSerial == null
                        || !serial.strip()
                                .toUpperCase(Locale.ROOT)
                                .equals(observedSerial.strip().toUpperCase(Locale.ROOT)))) {
            throw new AssetHandoverPort.Failure("REPAIR_FACTORY_ORIGINAL_SERIAL_MISMATCH", 409);
        }
        if (receiving) {
            if (actualLocation == null || locationVersion == null) {
                throw new AssetHandoverPort.Failure("REPAIR_FACTORY_LOCATION_REQUIRED", 400);
            }
            var locations =
                    db.queryForList(
                            "SELECT id,warehouse_id FROM fams_location WHERE id=? AND version=? AND"
                                    + " status='ACTIVE' FOR UPDATE",
                            actualLocation,
                            locationVersion);
            if (locations.isEmpty()) {
                throw new AssetHandoverPort.Failure("REPAIR_FACTORY_LOCATION_CHANGED", 409);
            }
            var warehouse = locations.getFirst().get("warehouse_id");
            if (warehouse != null
                    && db.queryForList(
                                    "SELECT id FROM fams_warehouse WHERE id=? AND status='ACTIVE'"
                                            + " FOR SHARE",
                                    warehouse)
                            .isEmpty()) {
                throw new AssetHandoverPort.Failure("REPAIR_FACTORY_LOCATION_CHANGED", 409);
            }
        }
        if (db.update(
                        "UPDATE fams_asset SET lifecycle_status=?,version=version+1,updated_by=?,"
                                + "updated_at=? WHERE id=? AND version=?",
                        receiving ? "UNDER_REPAIR" : "FACTORY_REPAIR",
                        actor,
                        recordedTime,
                        asset,
                        version)
                != 1) {
            throw new AssetHandoverPort.Failure("REPAIR_FACTORY_ASSET_CHANGED", 409);
        }
        var detail = new LinkedHashMap<String, Object>();
        detail.put("repairId", Long.toString(repair));
        detail.put("roundId", Long.toString(round));
        detail.put("actualTime", actualTime.toString());
        detail.put("originalPhysicalIdentityChecked", true);
        detail.put("actualLocationId", actualLocation == null ? null : actualLocation.toString());
        mapper.event(
                asset,
                receiving ? "REPAIR_FACTORY_ORIGINAL_RECEIVED" : "REPAIR_FACTORY_DISPATCHED",
                recordedTime,
                json.writeValueAsString(detail),
                actor);
        return version + 1;
    }
}
