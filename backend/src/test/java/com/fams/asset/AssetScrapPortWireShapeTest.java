package com.fams.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fams.asset.AssetScrapPort.SourceRow;
import com.fams.asset.AssetScrapPort.StoredSource;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JAVA-10b wire equivalence for AssetScrapPort. Unlike the loss/repair snapshots the three
 * scrap source exits serialized the raw JDBC row map verbatim into
 * fams_scrap_request.source_snapshot_json: fourteen snake_case keys with numeric ids, numeric
 * version and explicit nulls, optionally plus one conditional evidence object assembled by the
 * opening flow (lossEvidence / repairEvidence) and, for initial-import openings, initialSource
 * (a Map or a replayed JsonNode). The frozen oracles copy the legacy raw-row serialization; the
 * valueToTree comparison covers the ORIGINAL_CUSTODY path lookups that lossSource and
 * hasFrozenCustody run against the row tree before and after typing. Comparison round-trips
 * through JSON text so integral node widths normalize and key order is ignored (JAVA-01
 * standard). The eligible envelope stays covered by ScrapWireShapeTest (JAVA-07).
 */
class AssetScrapPortWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void sourceRowKeepsPersistedRawSnakeShapeWithNumericIds() {
        var row = scrapRow(9L, "SN-1", 3L, "LOST", 4L, 5L, 6L, 7L, 8L);
        assertWireEquals(row, SourceRow.fromRow(row));

        // Stored column proof: exactly the fourteen frozen snake_case keys; ids are numeric
        // (the legacy assembly never stringified them) and null custody columns stay explicit.
        var stored = JSON.readTree(JSON.writeValueAsString(SourceRow.fromRow(row)));
        var keys = new java.util.TreeSet<String>();
        keys.addAll(stored.propertyNames());
        assertEquals(java.util.Set.of(
                "id", "asset_code", "name", "serial_number", "lifecycle_status",
                "exception_status", "card_stage", "custody_type", "storage_warehouse_id",
                "current_location_id", "using_organization_id", "responsible_employee_id",
                "department_responsible_employee_id", "version"), keys);
        assertEquals(9L, stored.get("id").asLong());
        assertTrue(stored.get("id").isNumber());
        assertEquals(3L, stored.get("version").asLong());

        var storage = scrapRow(10L, null, 0L, "NORMAL", null, 5L, null, null, 8L);
        assertWireEquals(storage, SourceRow.fromRow(storage));
        var storageTree = JSON.readTree(JSON.writeValueAsString(SourceRow.fromRow(storage)));
        assertTrue(storageTree.get("serial_number").isNull());
        assertTrue(storageTree.get("responsible_employee_id").isNull());
        assertTrue(storageTree.has("using_organization_id"));

        // JDBC integral-width variant: Integer vs Long serialize to the same JSON bytes.
        var wide = scrapRow(11L, "SN-2", 2L, "UNDER_REPAIR", 4L, 5L, 6L, 7L, 8L);
        wide.put("storage_warehouse_id", Integer.valueOf(8));
        assertWireEquals(wide, SourceRow.fromRow(wide));
    }

    @Test
    void storedSourceKeepsConditionalEvidenceKeysFlat() {
        var row = scrapRow(9L, "SN-1", 3L, "LOST", 4L, 5L, 6L, 7L, 8L);
        var source = SourceRow.fromRow(row);
        var proof = Map.of("reportId", "12", "reportVersion", 0L);
        var initial = Map.of("batchId", "b-1", "sheetRow", "2");

        // Standalone openings stored the bare raw row.
        assertWireEquals(row, new StoredSource(source, null, null, null));
        // LOSS openings appended lossEvidence only; REPAIR openings, repairEvidence only.
        var withLoss = new LinkedHashMap<String, Object>(row);
        withLoss.put("lossEvidence", proof);
        assertWireEquals(withLoss, new StoredSource(source, proof, null, null));
        var withRepair = new LinkedHashMap<String, Object>(row);
        withRepair.put("repairEvidence", proof);
        assertWireEquals(withRepair, new StoredSource(source, null, proof, null));
        // Initial flows additionally carried initialSource, either as a fresh Map or as a
        // JsonNode replayed from the loss custody snapshot.
        var withInitialMap = new LinkedHashMap<String, Object>(row);
        withInitialMap.put("repairEvidence", proof);
        withInitialMap.put("initialSource", initial);
        assertWireEquals(withInitialMap, new StoredSource(source, null, proof, initial));
        var withInitialNode = new LinkedHashMap<String, Object>(row);
        withInitialNode.put("lossEvidence", proof);
        withInitialNode.put("initialSource", JSON.readTree("{\"batchId\":\"b-1\"}"));
        assertWireEquals(withInitialNode, new StoredSource(source, proof, null,
                JSON.readTree("{\"batchId\":\"b-1\"}")));

        // Absent evidence keys stay absent; the fourteen row keys never drop.
        var tree = JSON.readTree(JSON.writeValueAsString(new StoredSource(source, null, null, null)));
        assertEquals(14, tree.size());
        assertFalse(tree.has("lossEvidence"));
        assertFalse(tree.has("initialSource"));
    }

    @Test
    void custodyComparisonSeesTheSameTreeFromRowAndRecord() {
        // lossSource/hasFrozenCustody compare the ORIGINAL_CUSTODY snake_case columns of the
        // locked row tree against the stored snapshot or the raw row map; valueToTree of the
        // typed record must produce the identical tree.
        var row = scrapRow(9L, "SN-1", 3L, "PENDING_SCRAP", 4L, 5L, 6L, 7L, 8L);
        assertEquals(
                JSON.valueToTree(row),
                JSON.valueToTree(SourceRow.fromRow(row)));
        var frozen = JSON.readTree(JSON.writeValueAsString(row));
        for (String field : java.util.List.of("custody_type", "storage_warehouse_id",
                "current_location_id", "using_organization_id", "responsible_employee_id",
                "department_responsible_employee_id")) {
            assertEquals(
                    java.util.Objects.toString(row.get(field), null),
                    frozen.path(field).asText(null),
                    field);
        }
    }

    @Test
    void preTypingStoredRowsStillReplayAgainstTheTypedRecord() {
        // Existing source_snapshot_json documents were written from the raw row map; the
        // restore/execute flows replay them through readTree snake_case paths.
        var row = scrapRow(10L, null, 0L, "NORMAL", null, 5L, null, 7L, 8L);
        String stored = JSON.writeValueAsString(row);
        var frozen = JSON.readTree(stored);
        var typed = SourceRow.fromRow(row);
        assertEquals(frozen.path("lifecycle_status").asText(), typed.lifecycleStatus());
        assertEquals(frozen.path("card_stage").asText(), typed.cardStage());
        // The legacy comparison semantics: asLong()/asText(null) on the stored tree equal the
        // record components, including the explicit-null shared-responsible column.
        assertEquals(frozen.path("department_responsible_employee_id").asLong(),
                typed.departmentResponsibleEmployeeId());
        assertEquals(frozen.path("responsible_employee_id").asText(null),
                java.util.Objects.toString(typed.responsibleEmployeeId(), null));
        assertEquals(frozen.path("storage_warehouse_id").asText(null),
                java.util.Objects.toString(typed.storageWarehouseId(), null));
        assertEquals(frozen, JSON.readTree(JSON.writeValueAsString(typed)));
    }

    /** Simulates the fourteen-column locked fams_asset row exactly as JDBC returned it. */
    private static LinkedHashMap<String, Object> scrapRow(long id, String serial, long version,
            String lifecycle, Long organization, Long location, Long responsible, Long shared,
            Long warehouse) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("asset_code", "FA-" + id);
        row.put("name", "投影仪");
        row.put("serial_number", serial);
        row.put("lifecycle_status", lifecycle);
        row.put("exception_status", lifecycle.equals("LOST") ? "LOST" : "NORMAL");
        row.put("card_stage", "ACTIVE");
        row.put("custody_type", responsible == null ? "STORAGE" : "PERSONAL");
        row.put("storage_warehouse_id", warehouse);
        row.put("current_location_id", location);
        row.put("using_organization_id", organization);
        row.put("responsible_employee_id", responsible);
        row.put("department_responsible_employee_id", shared);
        row.put("version", version);
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
