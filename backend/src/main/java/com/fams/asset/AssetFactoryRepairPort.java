package com.fams.asset;

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
    private final JdbcTemplate db;
    private final AssetMapper mapper;
    private final AssetHandoverPort identity;
    private final ObjectMapper json;

    public AssetFactoryRepairPort(
            JdbcTemplate db, AssetMapper mapper, AssetHandoverPort identity, ObjectMapper json) {
        this.db = db;
        this.mapper = mapper;
        this.identity = identity;
        this.json = json;
    }

    public Map<String, Object> snapshot(long asset, long version, String lifecycle) {
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
        var result = new LinkedHashMap<String, Object>();
        result.put("assetId", Long.toString(asset));
        result.put("version", version);
        result.put("assetCode", row.get("asset_code"));
        result.put("name", row.get("name"));
        result.put("serialNumber", row.get("serial_number"));
        result.put("lifecycle", lifecycle);
        return result;
    }

    public void requireOriginal(Map<String, Object> current, String originalJson) {
        var original = json.readTree(originalJson);
        if (!Objects.equals(current.get("assetId"), original.path("assetId").asText())
                || !Objects.equals(current.get("assetCode"), original.path("assetCode").asText())
                || !Objects.equals(
                        current.get("serialNumber"),
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
        String serial = (String) current.get("serialNumber");
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
        String serial = (String) current.get("serialNumber");
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
