package com.fams.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JAVA-10a wire equivalence for the five light asset ports. Each pre-refactor shape is frozen
 * verbatim as a reference oracle: the eight-key snake_case stock projection serialized into
 * fams_requisition_handover.formal_custody_snapshot_json, the six-key camelCase factory identity
 * serialized into fams_repair_factory_round.original_identity_json and replayed by
 * requireOriginal, the camelCase trace envelope that is the whole
 * GET /api/v1/assets/{id}/factory-replacements body, and the field-by-field consumer embeddings
 * (asset_code into the proxy/target-change snapshots, id/version of a created replacement).
 * Comparison round-trips through JSON text so integral node widths normalize and object key
 * order (never stable in the Map.of-based shapes) is ignored, while key presence, explicit nulls
 * and value shapes all count (JAVA-01 standard). AssetReturnPort.adoptLossRecovery returned a
 * raw row that no consumer ever read, so its narrow Adopted record is checked for identity
 * fidelity only; AssetInitialPublicationPort keeps no Map in its public signatures.
 */
class AssetLightPortsWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    // ---- AssetHandoverPort.stock -> formal_custody_snapshot_json stored shape ----

    @Test
    void stockKeepsPersistedFormalCustodySnapshotShape() {
        // A null model keeps the explicit key in the legacy map and in the typed record alike.
        var row = assetRow(9L, 3L, "FA-001", "笔记本电脑", null, 7L, 8L);
        var legacy = legacyStockProjection(row);
        var typed = AssetHandoverPort.Stock.fromRow(row);
        assertWireEquals(legacy, typed);

        // Stored column proof: exactly the eight frozen snake_case keys, explicit null retained.
        var stored = JSON.readTree(JSON.writeValueAsString(typed));
        for (String key : List.of("id", "version", "asset_code", "name", "model", "custody_type",
                "storage_warehouse_id", "current_location_id")) {
            assertEquals(legacy.containsKey(key), stored.has(key), key);
        }
        assertEquals(8, stored.size());
        assertEquals(3L, stored.get("version").asLong());
        assertEquals("STORAGE", stored.get("custody_type").asText());

        // JDBC integral-width variant: Integer vs Long serialize to the same JSON bytes.
        var wideRow = assetRow(9L, 3L, "FA-001", "笔记本电脑", "M1", 7L, 8L);
        wideRow.put("version", Integer.valueOf(3));
        assertWireEquals(legacyStockProjection(wideRow), AssetHandoverPort.Stock.fromRow(wideRow));
    }

    @Test
    void stockConsumersReadAssetCodeOnly() {
        var row = assetRow(9L, 3L, "FA-001", "笔记本电脑", "M1", 7L, 8L);
        var legacy = legacyStockProjection(row);
        var typed = AssetHandoverPort.Stock.fromRow(row);
        // ProxyViews.RequisitionSnapshot and TargetChangeViews.RequisitionContext embed the
        // same value under the same key before and after typing.
        assertWireEquals(
                Map.of("assetCode", legacy.get("asset_code")),
                Map.of("assetCode", typed.assetCode()));
    }

    // ---- AssetReturnPort.adoptLossRecovery -> discarded row becomes narrow identity ----

    @Test
    void adoptedCarriesTheLockedRowIdentity() {
        var row = assetRow(9L, 3L, "FA-001", "笔记本电脑", "M1", 7L, 8L);
        var adopted = new AssetReturnPort.Adopted(
                ((Number) row.get("id")).longValue(),
                ((Number) row.get("version")).longValue());
        assertEquals(9L, adopted.id());
        assertEquals(3L, adopted.version());
        // The caller AssetReturnService.proposeLossRecovery ignores the return value entirely;
        // no stored or embedded wire exists for this port method (grep-verified zero readers).
    }

    // ---- AssetFactoryReplacementPort.create -> field-by-field consumer embedding ----

    @Test
    void replacedKeepsConsumerEmbeddedValues() {
        var legacy = Map.of("id", "41", "version", Long.valueOf(0), "assetCode", "FA-041");
        var typed = new AssetFactoryReplacementPort.Replaced("41", 0L);
        // RepairFactoryService reads id()/version() and embeds the id string into the still
        // untyped repair HTTP map and its saved idempotency result.
        assertEquals(Long.parseLong(legacy.get("id").toString()), Long.parseLong(typed.id()));
        assertEquals(((Number) legacy.get("version")).longValue(), typed.version());
        assertWireEquals(
                Map.of("replacementAssetId", legacy.get("id")),
                Map.of("replacementAssetId", typed.id()));
    }

    // ---- AssetFactoryReplacementPort.trace -> whole HTTP body of factory-replacements ----

    @Test
    void traceMatchesFrozenHttpEnvelopeAndItems() {
        var origin = originRow("6f9f1a2c-0000-4000-8000-000000000001", 41L, 9L, 5L, 6L,
                "FA-009", "FA-041", null, "SN-NEW");
        var typed = AssetFactoryReplacementPort.TraceItem.fromRow(origin);
        assertWireEquals(legacyTraceItem(origin), typed);

        // Null original_serial keeps the explicit key; the {"items": [...]} envelope is unchanged.
        assertWireEquals(
                Map.of("items", List.of(legacyTraceItem(origin))),
                new AssetFactoryReplacementPort.Trace(List.of(typed)));

        var second = originRow("6f9f1a2c-0000-4000-8000-000000000002", 42L, 9L, 5L, 7L,
                "FA-009", "FA-042", "SN-OLD", "SN-NEW2");
        assertWireEquals(
                Map.of("items", List.of(legacyTraceItem(origin), legacyTraceItem(second))),
                new AssetFactoryReplacementPort.Trace(
                        List.of(typed, AssetFactoryReplacementPort.TraceItem.fromRow(second))));
    }

    // ---- AssetFactoryRepairPort.snapshot -> original_identity_json stored shape ----

    @Test
    void factoryIdentityKeepsPersistedOriginalIdentityShape() {
        var row = assetRow(9L, 3L, "FA-001", "笔记本电脑", "M1", 7L, 8L);
        row.put("serial_number", null);
        var legacy = legacyIdentityProjection(9L, 3L, row, "UNDER_REPAIR");
        var typed = AssetFactoryRepairPort.Identity.of(9L, 3L, row, "UNDER_REPAIR");
        assertWireEquals(legacy, typed);

        // Storage-side key-set proof: explicit null serialNumber key is retained.
        var stored = JSON.readTree(JSON.writeValueAsString(typed));
        for (String key : List.of("assetId", "version", "assetCode", "name", "serialNumber",
                "lifecycle")) {
            assertEquals(legacy.containsKey(key), stored.has(key), key);
        }
        assertEquals(6, stored.size());
        assertEquals("9", stored.get("assetId").asText());
        assertEquals(3L, stored.get("version").asLong());

        var withSerial = assetRow(9L, 3L, "FA-001", "笔记本电脑", "M1", 7L, 8L);
        withSerial.put("serial_number", "SN-1");
        assertWireEquals(
                legacyIdentityProjection(9L, 3L, withSerial, "FACTORY_REPAIR"),
                AssetFactoryRepairPort.Identity.of(9L, 3L, withSerial, "FACTORY_REPAIR"));
    }

    @Test
    void requireOriginalReplaysStoredPreRefactorIdentityJson() {
        var row = assetRow(9L, 3L, "FA-001", "笔记本电脑", "M1", 7L, 8L);
        row.put("serial_number", "SN-1");
        // Pre-refactor rows: the legacy projection string is replayed against the typed record
        // (round.original_identity_json written before this batch stays readable).
        var legacyStored =
                JSON.writeValueAsString(legacyIdentityProjection(9L, 3L, row, "UNDER_REPAIR"));
        var guard = new AssetFactoryRepairPort(null, null, null, JSON);
        var current = AssetFactoryRepairPort.Identity.of(9L, 3L, row, "UNDER_REPAIR");
        assertDoesNotThrow(() -> guard.requireOriginal(current, legacyStored));

        // A null serial in the stored json matches a null serial component, and drift still 409s.
        var nullSerialRow = new LinkedHashMap<>(row);
        nullSerialRow.put("serial_number", null);
        var nullSerialStored =
                JSON.writeValueAsString(
                        legacyIdentityProjection(9L, 3L, nullSerialRow, "UNDER_REPAIR"));
        assertDoesNotThrow(() -> guard.requireOriginal(
                AssetFactoryRepairPort.Identity.of(9L, 3L, nullSerialRow, "UNDER_REPAIR"),
                nullSerialStored));
        var changed = new LinkedHashMap<>(row);
        changed.put("serial_number", "SN-2");
        assertThrows(AssetHandoverPort.Failure.class, () -> guard.requireOriginal(
                AssetFactoryRepairPort.Identity.of(9L, 3L, changed, "UNDER_REPAIR"),
                legacyStored));
    }

    // ---- frozen pre-JAVA-10a oracles (verbatim copies) ----

    /** Frozen AssetHandoverPort.stock projection (pre-JAVA-10a); reference oracle only. */
    private static Map<String, Object> legacyStockProjection(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key :
                new String[] {
                    "id",
                    "version",
                    "asset_code",
                    "name",
                    "model",
                    "custody_type",
                    "storage_warehouse_id",
                    "current_location_id"
                }) {
            result.put(key, row.get(key));
        }
        return result;
    }

    /** Frozen AssetFactoryRepairPort.snapshot projection (pre-JAVA-10a); oracle only. */
    private static Map<String, Object> legacyIdentityProjection(
            long asset, long version, Map<String, Object> row, String lifecycle) {
        var result = new LinkedHashMap<String, Object>();
        result.put("assetId", Long.toString(asset));
        result.put("version", version);
        result.put("assetCode", row.get("asset_code"));
        result.put("name", row.get("name"));
        result.put("serialNumber", row.get("serial_number"));
        result.put("lifecycle", lifecycle);
        return result;
    }

    /** Frozen AssetFactoryReplacementPort.trace item projection (pre-JAVA-10a); oracle only. */
    private static Map<String, Object> legacyTraceItem(Map<String, Object> row) {
        var item = new LinkedHashMap<String, Object>();
        item.put("sourceId", row.get("source_id"));
        item.put("originalAssetId", row.get("original_asset_id").toString());
        item.put("replacementAssetId", row.get("asset_id").toString());
        item.put("repairId", row.get("repair_id").toString());
        item.put("roundId", row.get("round_id").toString());
        item.put("originalAssetCode", row.get("original_code"));
        item.put("replacementAssetCode", row.get("replacement_code"));
        item.put("originalSerialNumber", row.get("original_serial"));
        item.put("replacementSerialNumber", row.get("replacement_serial"));
        return item;
    }

    /** Simulates a locked fams_asset row as JDBC returns it (BIGINT as Long). */
    private static LinkedHashMap<String, Object> assetRow(
            long id, long version, String code, String name, String model,
            long warehouseId, long locationId) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("version", version);
        row.put("asset_code", code);
        row.put("name", name);
        row.put("model", model);
        row.put("custody_type", "STORAGE");
        row.put("storage_warehouse_id", warehouseId);
        row.put("current_location_id", locationId);
        row.put("serial_number", "SN-1");
        return row;
    }

    /** Simulates a fams_asset_factory_replacement_origin join row (CHAR(36) as String). */
    private static LinkedHashMap<String, Object> originRow(String sourceId, long asset, long original,
            long repair, long round, String originalCode, String replacementCode,
            String originalSerial, String replacementSerial) {
        var row = new LinkedHashMap<String, Object>();
        row.put("source_id", sourceId);
        row.put("asset_id", asset);
        row.put("original_asset_id", original);
        row.put("repair_id", repair);
        row.put("round_id", round);
        row.put("original_code", originalCode);
        row.put("replacement_code", replacementCode);
        row.put("original_serial", originalSerial);
        row.put("replacement_serial", replacementSerial);
        return row;
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        // Round-trip through JSON text: integral node widths (int vs long) serialize to the
        // same bytes, and object key order was never stable in the Map.of-based responses.
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }
}
