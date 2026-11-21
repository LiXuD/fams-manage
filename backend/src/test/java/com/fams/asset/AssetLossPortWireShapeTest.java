package com.fams.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fams.asset.AssetLossPort.EligiblePage;
import com.fams.asset.AssetLossPort.RecoveryLocation;
import com.fams.asset.AssetLossPort.RecoveryLocationPage;
import com.fams.asset.AssetLossPort.RecoverySource;
import com.fams.asset.AssetLossPort.RecoveryView;
import com.fams.asset.AssetLossPort.Snapshot;
import com.fams.asset.AssetLossPort.StoredSource;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JAVA-10b wire equivalence for AssetLossPort. Each pre-refactor shape is frozen verbatim as a
 * reference oracle: the eleven camelCase snapshot keys serialized into
 * fams_loss_report.source_snapshot_json (also reached verbatim through the String submission
 * path), the same snapshot plus the conditional returnSource/factorySource/internalRepairSource/
 * initialSource handoff keys, the eligible page envelope that is the whole
 * GET /loss-reports/eligible body, the recoveryView object embedded under the untyped recoveryAsset
 * key of the loss detail response, the recovery-locations page body and the narrow recovery facts
 * read from the locked raw row. Comparison round-trips through JSON text so integral node widths
 * normalize and object key order (never stable in the Map.of-based shapes) is ignored, while key
 * presence, explicit nulls and value shapes all count (JAVA-01 standard).
 */
class AssetLossPortWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    // ---- snapshot -> source_snapshot_json stored shape ----

    @Test
    void snapshotKeepsPersistedLossSourceSnapshotShape() {
        var row = assetRow(9L, 3L, "FA-001", "投影仪", "BORROWED", 4L, 5L, 6L, 7L, 8L);
        assertWireEquals(legacySnapshot(row), Snapshot.fromRow(row));

        // Stored column proof: exactly the eleven frozen camelCase keys; unowned custody ids
        // keep their explicit null keys; ids are strings while the version stays numeric.
        var stored = JSON.readTree(JSON.writeValueAsString(Snapshot.fromRow(row)));
        var keys = new java.util.TreeSet<String>();
        keys.addAll(stored.propertyNames());
        assertEquals(java.util.Set.of(
                "id", "assetCode", "name", "version", "lifecycle", "custodyType",
                "usingOrganizationId", "responsibleEmployeeId", "departmentResponsibleEmployeeId",
                "warehouseId", "locationId"), keys);
        assertEquals("9", stored.get("id").asText());
        assertEquals(3L, stored.get("version").asLong());
        assertTrue(stored.get("version").isNumber());
        assertFalse(stored.get("id").isNumber());

        // Shared custody: personal responsible id is null and stays an explicit key.
        var shared = assetRow(10L, 4L, "FA-002", "显示器", "IN_USE", null, 6L, null, 11L, 8L);
        var sharedStored = JSON.readTree(JSON.writeValueAsString(Snapshot.fromRow(shared)));
        assertWireEquals(legacySnapshot(shared), Snapshot.fromRow(shared));
        assertTrue(sharedStored.get("responsibleEmployeeId").isNull());
        assertTrue(sharedStored.has("warehouseId"));
        assertEquals("11", sharedStored.get("departmentResponsibleEmployeeId").asText());

        // JDBC integral-width variant: Integer vs Long ids serialize to the same JSON text.
        var narrow = assetRow(10L, 4L, "FA-002", "显示器", "IN_USE", 7L, 6L, 11L, 12L, 8L);
        narrow.put("using_organization_id", Integer.valueOf(7));
        assertWireEquals(legacySnapshot(narrow), Snapshot.fromRow(narrow));
    }

    @Test
    void storedSourceKeepsConditionalHandoffKeysFlat() {
        var row = assetRow(9L, 3L, "FA-001", "投影仪", "IN_USE", 4L, 5L, 6L, null, 8L);
        var snapshot = Snapshot.fromRow(row);

        // Legacy assemblies: the snapshot map plus exactly one flow-specific handoff key.
        var bare = legacySnapshot(row);
        var returning = legacySnapshot(row);
        returning.put("returnSource", Map.of("returnId", "12", "followupId", "13",
                "processorAccountId", "3"));
        var factory = legacySnapshot(row);
        factory.put("factorySource", Map.of("repairId", "4", "roundId", "5",
                "processorAccountId", "3"));
        var internal = legacySnapshot(row);
        internal.put("internalRepairSource", Map.of("repairId", "4", "processorAccountId", "3",
                "handling", JSON.readTree("{\"method\":\"INTERNAL\"}")));
        var initial = legacySnapshot(row);
        initial.put("initialSource", Map.of("batchId", "b1", "sheetRow", 2,
                "businessDate", LocalDate.parse("2026-09-12"), "operatorAccountId", "3"));

        assertWireEquals(bare, new StoredSource(snapshot, null, null, null, null));
        assertWireEquals(returning, new StoredSource(snapshot, Map.of("returnId", "12",
                "followupId", "13", "processorAccountId", "3"), null, null, null));
        assertWireEquals(factory, new StoredSource(snapshot, null, Map.of("repairId", "4",
                "roundId", "5", "processorAccountId", "3"), null, null));
        assertWireEquals(internal, new StoredSource(snapshot, null, null, Map.of(
                "repairId", "4", "processorAccountId", "3",
                "handling", JSON.readTree("{\"method\":\"INTERNAL\"}")), null));
        assertWireEquals(initial, new StoredSource(snapshot, null, null, null, Map.of(
                "batchId", "b1", "sheetRow", 2,
                "businessDate", LocalDate.parse("2026-09-12"), "operatorAccountId", "3")));

        // Absent handoff keys stay absent; the eleven snapshot keys stay present with nulls.
        var tree = JSON.readTree(JSON.writeValueAsString(new StoredSource(snapshot, null,
                Map.of("repairId", "4", "roundId", "5", "processorAccountId", "3"), null, null)));
        assertEquals(12, tree.size());
        assertFalse(tree.has("returnSource"));
        assertTrue(tree.has("departmentResponsibleEmployeeId"));
    }

    @Test
    void frozenOriginalReplayStillMatchesTheTypedSnapshot() {
        // requireOriginalRecovery/requireLinkedRecovery replay the stored camelCase keys against
        // the freshly locked row through toString/asText equality; prove the record carries the
        // same text values, including the null-versus-missing equivalence on both sides.
        var row = assetRow(9L, 3L, "FA-001", "投影仪", "IN_USE", null, 5L, null, 6L, 8L);
        var storedLegacy = JSON.readTree(JSON.writeValueAsString(legacySnapshot(row)));
        var typed = Snapshot.fromRow(row);
        for (String[] field : new String[][] {
            {"lifecycle", typed.lifecycle()},
            {"custodyType", typed.custodyType()},
            {"usingOrganizationId", typed.usingOrganizationId()},
            {"responsibleEmployeeId", typed.responsibleEmployeeId()},
            {"departmentResponsibleEmployeeId", typed.departmentResponsibleEmployeeId()},
            {"warehouseId", typed.warehouseId()},
            {"locationId", typed.locationId()}}) {
            var expected = storedLegacy.path(field[0]);
            assertTrue(
                    Objects.equals(field[1] == null ? null : field[1].toString(),
                            expected.isNull() || expected.isMissingNode()
                                    ? null : expected.asText()),
                    field[0]);
        }
    }

    // ---- eligible page envelope (response body of GET /loss-reports/eligible) ----

    @Test
    void eligiblePageMatchesLegacyMapOfEnvelope() {
        var row = assetRow(9L, 3L, "FA-001", "投影仪", "IN_STOCK", 4L, 5L, 6L, 7L, 8L);
        var legacy = Map.of("items", List.of(legacySnapshot(row)), "total", 42L, "page", 2, "pageSize", 20);
        assertWireEquals(legacy, new EligiblePage(List.of(Snapshot.fromRow(row)), 42L, 2, 20));
        var empty = Map.of("items", List.of(), "total", 0L, "page", 1, "pageSize", 20);
        assertWireEquals(empty, new EligiblePage(List.of(), 0L, 1, 20));
    }

    // ---- recoveryView -> embedded recoveryAsset wire object ----

    @Test
    void recoveryViewKeepsEmbeddedLossDetailShape() {
        var withPlace = legacyRecoveryView("FA-1", "SN-1", 5L, "LOST", "SEARCHING", "8", 2L,
                "一号仓货架", true, false, false);
        assertWireEquals(withPlace, new RecoveryView("FA-1", "SN-1", 5L, "LOST", "SEARCHING",
                "8", 2L, "一号仓货架", true, false, false));

        // LEFT JOIN miss: locationId/locationVersion/locationName keep explicit null keys.
        var withoutPlace = legacyRecoveryView("FA-2", null, 6L, "IN_USE", "LOST", null, null,
                null, true, true, false);
        assertWireEquals(withoutPlace, new RecoveryView("FA-2", null, 6L, "IN_USE", "LOST",
                null, null, null, true, true, false));
        var tree = JSON.readTree(JSON.writeValueAsString(new RecoveryView("FA-2", null, 6L,
                "IN_USE", "LOST", null, null, null, true, true, false)));
        assertEquals(11, tree.size());
        assertTrue(tree.get("locationVersion").isNull());
        assertTrue(tree.get("factoryScrapEligible").isBoolean());

        // Embedding under the still-untyped loss detail map keeps the recoveryAsset wire object.
        var legacyResult = new LinkedHashMap<String, Object>();
        legacyResult.put("recoveryAsset", withoutPlace);
        var typedResult = new LinkedHashMap<String, Object>();
        typedResult.put("recoveryAsset", new RecoveryView("FA-2", null, 6L, "IN_USE", "LOST",
                null, null, null, true, true, false));
        assertWireEquals(legacyResult, typedResult);
    }

    // ---- recoveryLocations page (response body of GET /loss-reports/recovery-locations) ----

    @Test
    void recoveryLocationsMatchLegacyEnvelope() {
        var rows = List.of(locationRow(3L, "暂存位", 1L), locationRow(4L, "货架", 9L));
        var legacy = Map.of(
                "items",
                rows.stream()
                        .map(r -> Map.of("id", r.get("id").toString(), "name", r.get("name"),
                                "version", r.get("version")))
                        .toList(),
                "total", 7L, "page", 1, "pageSize", 100);
        var typed = new RecoveryLocationPage(
                rows.stream().map(r -> new RecoveryLocation(r.get("id").toString(),
                        (String) r.get("name"), ((Number) r.get("version")).longValue())).toList(),
                7L, 1, 100);
        assertWireEquals(legacy, typed);
    }

    // ---- require/validate recovery -> narrow locked-row facts ----

    @Test
    void recoverySourceCarriesTheFactsConsumersRead() {
        var row = assetRow(9L, 3L, "FA-001", "投影仪", "BORROWED", 4L, 5L, 6L, 7L, 8L);
        var source = RecoverySource.fromRow(row);
        assertEquals("BORROWED", source.lifecycleStatus());
        assertEquals(6L, source.responsibleEmployeeId());
        assertEquals(4L, source.usingOrganizationId());

        // Shared custody: the personal responsible id is null and the consumer passes null on,
        // matching the pre-typing (Long) row.get cast.
        var shared = assetRow(9L, 3L, "FA-001", "投影仪", "IN_USE", 4L, 5L, null, 7L, 8L);
        var sharedSource = RecoverySource.fromRow(shared);
        assertNull(sharedSource.responsibleEmployeeId());
        assertEquals("IN_USE", sharedSource.lifecycleStatus());
    }

    // ---- frozen legacy oracles (verbatim pre-JAVA-10b projections) ----

    /** Verbatim copy of the pre-JAVA-10b private AssetLossPort.snapshot(row) projection. */
    private static Map<String, Object> legacySnapshot(Map<String, Object> row) {
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

    /** Verbatim copy of the pre-JAVA-10b AssetLossPort.recoveryView assembly. */
    private static Map<String, Object> legacyRecoveryView(String assetCode, String serial,
            long version, String lifecycle, String exception, String locationId,
            Long locationVersion, String locationName, boolean eligible, boolean locked,
            boolean factoryScrapEligible) {
        var view = new LinkedHashMap<String, Object>();
        view.put("assetCode", assetCode);
        view.put("serialNumber", serial);
        view.put("version", version);
        view.put("lifecycle", lifecycle);
        view.put("exceptionStatus", exception);
        view.put("locationId", locationId);
        view.put("locationVersion", locationVersion);
        view.put("locationName", locationName);
        view.put("eligible", eligible);
        view.put("locked", locked);
        view.put("factoryScrapEligible", factoryScrapEligible);
        return view;
    }

    /** Simulates a locked fams_asset row as JDBC returns it (BIGINT columns as Long). */
    private static LinkedHashMap<String, Object> assetRow(long id, long version, String code,
            String name, String lifecycle, Long organization, Long location, Long responsible,
            Long shared, Long warehouse) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("version", version);
        row.put("asset_code", code);
        row.put("name", name);
        row.put("lifecycle_status", lifecycle);
        row.put("custody_type", responsible == null ? "SHARED" : "PERSONAL");
        row.put("using_organization_id", organization);
        row.put("responsible_employee_id", responsible);
        row.put("department_responsible_employee_id", shared);
        row.put("storage_warehouse_id", warehouse);
        row.put("current_location_id", location);
        return row;
    }

    private static LinkedHashMap<String, Object> locationRow(long id, String name, long version) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("name", name);
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
