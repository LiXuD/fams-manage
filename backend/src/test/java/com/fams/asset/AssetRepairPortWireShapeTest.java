package com.fams.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fams.asset.AssetRepairPort.EligiblePage;
import com.fams.asset.AssetRepairPort.InitialSource;
import com.fams.asset.AssetRepairPort.LocationTarget;
import com.fams.asset.AssetRepairPort.PhysicalPage;
import com.fams.asset.AssetRepairPort.ReturnTarget;
import com.fams.asset.AssetRepairPort.Snapshot;
import com.fams.asset.AssetRepairPort.WarehouseTarget;
import com.fams.proxy.ProxyViews;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JAVA-10b wire equivalence for AssetRepairPort. Frozen pre-refactor oracles: the thirteen
 * camelCase snapshot keys serialized into fams_repair_request.source_snapshot_json (plus the
 * always-present initialSource provenance object of the initial-import flow), the eligible page
 * envelope that is the whole GET /repairs/eligible body, the per-kind physical catalog pages
 * (three-key warehouse rows vs five-key location rows, stringified id/warehouse_id, numeric
 * version) of GET /repairs/physical-catalog/{kind}, the eleven camelCase return target keys
 * serialized into fams_repair_return_handover.target_snapshot_json and
 * fams_repair_approved_return_target.target_json, and the RepairSnapshot proxy embedding where
 * the target component switched from raw map to the typed record (Object pass-through keeps the
 * stored-snapshot replay and the JAVA-01 text round-trip comparison intact). Comparison
 * round-trips through JSON text (JAVA-01 standard).
 */
class AssetRepairPortWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    // ---- snapshot -> repair source_snapshot_json stored shape ----

    @Test
    void snapshotKeepsPersistedRepairSourceSnapshotShape() {
        var row = assetRow(9L, 3L, "FA-001", "投影仪", "BORROWED", 4L, 5L, 6L, 7L, 8L, 2L, "A区货架");
        assertWireEquals(legacySnapshot(row), Snapshot.fromRow(row));

        var stored = JSON.readTree(JSON.writeValueAsString(Snapshot.fromRow(row)));
        var keys = new java.util.TreeSet<String>();
        keys.addAll(stored.propertyNames());
        assertEquals(java.util.Set.of(
                "id", "assetCode", "name", "version", "lifecycle", "custodyType",
                "usingOrganizationId", "owningOrganizationId", "responsibleEmployeeId",
                "departmentResponsibleEmployeeId", "warehouseId", "locationId", "locationName"),
                keys);
        assertEquals("9", stored.get("id").asText());
        assertEquals(3L, stored.get("version").asLong());

        // Missing warehouse custody: owning id, warehouse and joined location stay explicit nulls.
        var bare = assetRow(10L, 4L, "FA-002", "显示器", "IN_USE", null, 6L, 11L, null, null,
                null, null);
        assertWireEquals(legacySnapshot(bare), Snapshot.fromRow(bare));
        var bareTree = JSON.readTree(JSON.writeValueAsString(Snapshot.fromRow(bare)));
        assertTrue(bareTree.get("locationName").isNull());
        assertTrue(bareTree.has("owningOrganizationId"));

        // JDBC integral-width variant.
        var wide = assetRow(10L, 4L, "FA-002", "显示器", "IN_USE", 7L, 6L, 11L, 12L, 8L, 2L, "B");
        wide.put("storage_warehouse_id", Integer.valueOf(8));
        assertWireEquals(legacySnapshot(wide), Snapshot.fromRow(wide));
    }

    @Test
    void initialSourceEnvelopeKeepsStoredAndApprovalShapes() {
        var row = assetRow(9L, 3L, "FA-001", "投影仪", "IN_USE", 4L, 5L, 6L, 7L, 8L, 2L, "A区");
        var snapshot = Snapshot.fromRow(row);
        var origin = new LinkedHashMap<String, Object>();
        origin.put("batchId", "b-1");
        origin.put("sheetRow", 3);
        origin.put("operatorAccountId", "11");
        origin.put("administratorAccountId", "12");
        origin.put("businessDate", LocalDate.parse("2026-09-12"));
        origin.put("stage", "ACCEPTED");
        origin.put("evidence", "期初核验");
        var legacy = legacySnapshot(row);
        legacy.put("initialSource", origin);
        assertWireEquals(legacy, new InitialSource(snapshot, origin));

        // Conditional factoryDestination inside the provenance object passes through verbatim.
        origin.put("factoryDestination", Map.of("provider", "厂商A"));
        var withFactory = legacySnapshot(row);
        withFactory.put("initialSource", new LinkedHashMap<>(origin));
        assertWireEquals(withFactory, new InitialSource(snapshot, origin));

        // The approval envelope embeds the whole source document under "source".
        var legacyApproval = Map.of("source", legacy, "allowedMethod", "INTERNAL_OR_FACTORY");
        var typedApproval = Map.of("source", new InitialSource(snapshot, origin),
                "allowedMethod", "INTERNAL_OR_FACTORY");
        assertWireEquals(legacyApproval, typedApproval);
    }

    // ---- eligible page envelope (response body of GET /repairs/eligible) ----

    @Test
    void eligiblePageMatchesLegacyMapOfEnvelope() {
        var row = assetRow(9L, 3L, "FA-001", "投影仪", "IN_STOCK", 4L, 5L, 6L, 7L, 8L, 2L, "A");
        var legacy = Map.of("items", List.of(legacySnapshot(row)), "total", 5L,
                "page", 3, "pageSize", 20);
        assertWireEquals(legacy, new EligiblePage(List.of(Snapshot.fromRow(row)), 5L, 3, 20));
    }

    // ---- physical catalog (response body of GET /repairs/physical-catalog/{kind}) ----

    @Test
    void physicalCatalogKeepsPerKindKeySets() {
        var warehouse = new LinkedHashMap<String, Object>();
        warehouse.put("id", 2L);
        warehouse.put("name", "中心仓");
        warehouse.put("version", 1L);
        var legacyWarehouse = legacyCatalogItem(warehouse);
        assertWireEquals(legacyWarehouse, new WarehouseTarget("2", "中心仓", 1L));
        var warehousePage = Map.of("items", List.of(legacyWarehouse), "total", 1L,
                "page", 1, "pageSize", 20);
        assertWireEquals(warehousePage,
                new PhysicalPage(List.of(new WarehouseTarget("2", "中心仓", 1L)), 1L, 1, 20));

        // Location rows carry warehouse_id and location_type; a null warehouse keeps the key.
        var location = new LinkedHashMap<String, Object>();
        location.put("id", 7L);
        location.put("name", "货架");
        location.put("version", 4L);
        location.put("warehouse_id", 2L);
        location.put("location_type", "BIN");
        assertWireEquals(legacyCatalogItem(location),
                new LocationTarget("7", "货架", 4L, "2", "BIN"));
        var open = new LinkedHashMap<>(location);
        open.put("warehouse_id", null);
        open.put("location_type", "AREA");
        var openTree = JSON.readTree(JSON.writeValueAsString(
                new LocationTarget("7", "货架", 4L, null, "AREA")));
        assertWireEquals(legacyCatalogItem(open), openTree);
        assertTrue(openTree.has("warehouse_id"));
        assertEquals(5, openTree.size());

        // Warehouse rows must not carry the location-only keys.
        var warehouseTree = JSON.readTree(JSON.writeValueAsString(new WarehouseTarget("2", "c", 1L)));
        assertFalse(warehouseTree.has("warehouse_id"));
        assertFalse(warehouseTree.has("location_type"));
        assertEquals(3, warehouseTree.size());
    }

    // ---- return target -> target_snapshot_json / approved target_json stored shape ----

    @Test
    void returnTargetKeepsStoredAndReplayedShape() {
        var legacy = legacyTarget("PERSONAL", 11L, "张三", 3L, 4L, "研发中心", 2L, 8L, "A区", 5L, 9L);
        var typed = target("PERSONAL", 11L, "张三", 3L, 4L, "研发中心", 2L, 8L, "A区", 5L, 9L);
        assertWireEquals(legacy, typed);

        // Stored column proof: exactly the eleven frozen camelCase keys, ids stringified,
        // versions numeric.
        var stored = JSON.readTree(JSON.writeValueAsString(typed));
        assertEquals(11, stored.size());
        assertEquals("11", stored.get("employeeId").asText());
        assertEquals(3L, stored.get("employeeVersion").asLong());
        assertTrue(stored.get("organizationVersion").isNumber());
        assertEquals("9", stored.get("receiverAccountId").asText());

        // Pre-JAVA-10b stored target_snapshot_json (written from the legacy map) still replays
        // field-for-field against the typed record (receiveReturn/closeReturned comparisons and
        // the approvedReturnTarget input checks all read via path().asLong()/asText()).
        String storedJson = JSON.writeValueAsString(legacy);
        var frozen = JSON.readTree(storedJson);
        assertEquals(frozen.path("employeeVersion").asLong(), typed.employeeVersion());
        assertEquals(frozen.path("organizationVersion").asLong(), typed.organizationVersion());
        assertEquals(frozen.path("locationVersion").asLong(), typed.locationVersion());
        assertEquals(frozen.path("receiverAccountId").asText(), typed.receiverAccountId());
        assertEquals(frozen.path("locationId").asText(), typed.locationId());
        assertEquals(frozen.path("custodyType").asText(), typed.custodyType());
    }

    @Test
    void proxyRepairSnapshotEmbedsTheTypedTargetWireNeutrally() {
        var legacyTarget = legacyTarget("SHARED", 12L, "李四", 1L, 4L, "运维部", 6L, 8L, "B区", 2L, 10L);
        var typedTarget = target("SHARED", 12L, "李四", 1L, 4L, "运维部", 6L, 8L, "B区", 2L, 10L);
        var loan = Map.<String, Object>of("id", "8", "version", 2L, "borrowerEmployeeId", "3",
                "organizationId", "1", "expectedReturnDate", "2026-12-01");

        var legacySnapshot = legacyRepairSnapshot(legacyTarget, loan);
        var typedSnapshot = new ProxyViews.RepairSnapshot(
                "REPAIR", "2", "40", "40", 6, 1, "12", 3, "FA-1", "9",
                "2026-11-01T02:00:00Z", "12", typedTarget, loan, "7", 4L);
        assertWireEquals(legacySnapshot, typedSnapshot);

        // Stored snapshot replay (raw-map target) vs live typed target agree under the
        // text round-trip comparison that proxyReceipt keeps (JAVA-01 regression red line).
        var replayed = JSON.readValue(JSON.writeValueAsString(legacySnapshot),
                ProxyViews.ProxySnapshot.class);
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(replayed)),
                JSON.readTree(JSON.writeValueAsString(typedSnapshot)));
    }

    // ---- frozen legacy oracles (verbatim pre-JAVA-10b projections) ----

    /** Verbatim copy of the pre-JAVA-10b private AssetRepairPort.snapshot(row) projection. */
    private static Map<String, Object> legacySnapshot(Map<String, Object> row) {
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

    /** Verbatim copy of the pre-JAVA-10b AssetRepairPort.physicalCatalog item projection. */
    private static Map<String, Object> legacyCatalogItem(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>(row);
        for (String field : List.of("id", "warehouse_id")) {
            if (result.get(field) != null) {
                result.put(field, result.get(field).toString());
            }
        }
        return result;
    }

    /** Verbatim copy of the pre-JAVA-10b AssetRepairPort.resolveReturnTarget assembly. */
    private static Map<String, Object> legacyTarget(String custody, long person, String personName,
            long personVersion, long organization, String organizationName, long organizationVersion,
            long location, String locationName, long locationVersion, long account) {
        var target = new LinkedHashMap<String, Object>();
        target.put("custodyType", custody);
        target.put("employeeId", Long.toString(person));
        target.put("employeeName", personName);
        target.put("employeeVersion", personVersion);
        target.put("organizationId", Long.toString(organization));
        target.put("organizationName", organizationName);
        target.put("organizationVersion", organizationVersion);
        target.put("locationId", Long.toString(location));
        target.put("locationName", locationName);
        target.put("locationVersion", locationVersion);
        target.put("receiverAccountId", Long.toString(account));
        return target;
    }

    private static ReturnTarget target(String custody, long person, String personName,
            long personVersion, long organization, String organizationName, long organizationVersion,
            long location, String locationName, long locationVersion, long account) {
        return new ReturnTarget(custody, Long.toString(person), personName, personVersion,
                Long.toString(organization), organizationName, organizationVersion,
                Long.toString(location), locationName, locationVersion, Long.toString(account));
    }

    /** Verbatim copy of the pre-JAVA-10b RepairService.proxySnapshot legacy assembly. */
    private static Map<String, Object> legacyRepairSnapshot(Map<String, Object> target,
            Map<String, Object> loan) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("businessType", "REPAIR");
        snapshot.put("businessId", "2");
        snapshot.put("itemId", "40");
        snapshot.put("assetId", "40");
        snapshot.put("version", 6L);
        snapshot.put("ownershipVersion", 1L);
        snapshot.put("processorAccountId", "12");
        snapshot.put("assetVersion", 3L);
        snapshot.put("assetCode", "FA-1");
        snapshot.put("handoverId", "9");
        snapshot.put("deliveredAt", "2026-11-01T02:00:00Z");
        snapshot.put("targetEmployeeId", "12");
        snapshot.put("target", target);
        snapshot.put("loan", loan);
        snapshot.put("approvedTargetId", "7");
        snapshot.put("lockVersion", 4L);
        return snapshot;
    }

    /** Simulates a locked repair-eligible fams_asset join row (BIGINT columns as Long). */
    private static LinkedHashMap<String, Object> assetRow(long id, long version, String code,
            String name, String lifecycle, Long organization, Long location, Long responsible,
            Long shared, Long warehouse, Long owningOrganization, String locationName) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("version", version);
        row.put("asset_code", code);
        row.put("name", name);
        row.put("lifecycle_status", lifecycle);
        row.put("custody_type", responsible == null ? "SHARED" : "PERSONAL");
        row.put("using_organization_id", organization);
        row.put("owning_organization_id", owningOrganization);
        row.put("responsible_employee_id", responsible);
        row.put("department_responsible_employee_id", shared);
        row.put("storage_warehouse_id", warehouse);
        row.put("current_location_id", location);
        row.put("location_name", locationName);
        return row;
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }
}
