package com.fams.masterdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JAVA-12b wire equivalence: the three typed masterdata active-reference selector shapes must
 * serialize exactly like the pre-refactor projections. legacyLocationActiveReference,
 * legacyBrandActiveReference and legacyAssetCategoryActiveReference are verbatim copies of the
 * deleted LocationService/BrandService/AssetCategoryService toActiveReference methods and
 * legacyPage is the verbatim Map.of envelope those listActive methods returned. Comparison
 * round-trips through JSON text so integral node widths (int vs long) normalize and the Map.of
 * key order (never stable) is ignored, while key presence, conditional omission and value shapes
 * all count. The final case replays the frozen pre-change JSON through the new records to prove
 * the selector shape stays deserializable and re-serializes identically.
 */
class MasterdataWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void locationActiveReferenceMatchesLegacyProjectionAcrossConditionalKeys() {
        assertWireEquals(legacyLocationActiveReference(location(11L, 21L, 3L)),
                MasterdataViews.locationActiveReference(location(11L, 21L, 3L)));

        // Office selector without warehouse and without parent: neither key exists.
        assertWireEquals(legacyLocationActiveReference(location(null, null, 0L)),
                MasterdataViews.locationActiveReference(location(null, null, 0L)));

        // Warehouse-bound leaf and parent-only office branch, each a single conditional key.
        assertWireEquals(legacyLocationActiveReference(location(11L, null, 4L)),
                MasterdataViews.locationActiveReference(location(11L, null, 4L)));
        assertWireEquals(legacyLocationActiveReference(location(null, 21L, 5L)),
                MasterdataViews.locationActiveReference(location(null, 21L, 5L)));

        MasterdataViews.LocationActiveReference both =
                MasterdataViews.locationActiveReference(location(11L, 21L, 3L));
        assertEquals("11", both.warehouse().id());
        assertEquals(3L, both.warehouse().version());
        assertEquals("21", both.parentLocation().id());
        assertEquals(3L, both.parentLocation().version());
        assertEquals("BIN", both.type());
        assertEquals(7L, both.version());

        MasterdataViews.LocationActiveReference bare =
                MasterdataViews.locationActiveReference(location(null, null, 0L));
        assertFalse(JSON.readTree(JSON.writeValueAsString(bare)).has("warehouse"));
        assertFalse(JSON.readTree(JSON.writeValueAsString(bare)).has("parentLocation"));

        // A joined version that the driver returns as null keeps the ActiveReference object with
        // an explicit null version, exactly as the old conditional put did.
        String legacyVersionless = JSON.writeValueAsString(
                legacyLocationActiveReference(locationWithWarehouseVersion(null)));
        String typedVersionless = JSON.writeValueAsString(
                MasterdataViews.locationActiveReference(locationWithWarehouseVersion(null)));
        assertWireEquals(JSON.readTree(legacyVersionless), JSON.readTree(typedVersionless));
        assertTrue(JSON.readTree(typedVersionless).path("warehouse").has("version"));
        assertTrue(JSON.readTree(typedVersionless).path("warehouse").get("version").isNull());
    }

    @Test
    void brandActiveReferenceOmitsDisabledDefaultManufacturer() {
        assertWireEquals(legacyBrandActiveReference(brand(31L, "ACTIVE")),
                MasterdataViews.brandActiveReference(brand(31L, "ACTIVE")));
        assertWireEquals(legacyBrandActiveReference(brand(31L, "DISABLED")),
                MasterdataViews.brandActiveReference(brand(31L, "DISABLED")));
        assertWireEquals(legacyBrandActiveReference(brand(null, "ACTIVE")),
                MasterdataViews.brandActiveReference(brand(null, "ACTIVE")));
        assertWireEquals(legacyBrandActiveReference(brand(31L, null)),
                MasterdataViews.brandActiveReference(brand(31L, null)));

        assertTrue(JSON.readTree(JSON.writeValueAsString(MasterdataViews.brandActiveReference(brand(31L, "ACTIVE"))))
                .path("defaultManufacturerId").isString());
        assertFalse(JSON.readTree(JSON.writeValueAsString(MasterdataViews.brandActiveReference(brand(31L, "DISABLED"))))
                .has("defaultManufacturerId"));
    }

    @Test
    void assetCategoryActiveReferenceMatchesOptionalSelectorKeys() {
        assertWireEquals(legacyAssetCategoryActiveReference(category("FU", "PC")),
                MasterdataViews.assetCategoryActiveReference(category("FU", "PC")));
        assertWireEquals(legacyAssetCategoryActiveReference(category("FU", null)),
                MasterdataViews.assetCategoryActiveReference(category("FU", null)));
        assertWireEquals(legacyAssetCategoryActiveReference(category(null, "PC")),
                MasterdataViews.assetCategoryActiveReference(category(null, "PC")));
        assertWireEquals(legacyAssetCategoryActiveReference(category(null, null)),
                MasterdataViews.assetCategoryActiveReference(category(null, null)));

        String omitted = JSON.writeValueAsString(
                MasterdataViews.assetCategoryActiveReference(category(null, null)));
        assertFalse(JSON.readTree(omitted).has("suggestedUnit"));
        assertFalse(JSON.readTree(omitted).has("numberingPrefix"));
    }

    @Test
    void selectorPagesMatchLegacyMapEnvelope() {
        List<MasterdataViews.LocationActiveReference> locations = List.of(
                MasterdataViews.locationActiveReference(location(11L, 21L, 3L)),
                MasterdataViews.locationActiveReference(location(null, null, 0L)));
        List<Map<String, Object>> legacyLocations = List.of(
                legacyLocationActiveReference(location(11L, 21L, 3L)),
                legacyLocationActiveReference(location(null, null, 0L)));
        assertWireEquals(legacyPage(1, 50, 2L, legacyLocations),
                new MasterdataViews.Page<>(locations, 2L, 1, 50));

        // Empty selector page keeps the four envelope keys with an empty items array.
        assertWireEquals(legacyPage(3, 200, 0L, List.of()),
                new MasterdataViews.Page<>(List.of(), 0L, 3, 200));

        List<MasterdataViews.BrandActiveReference> brands = List.of(
                MasterdataViews.brandActiveReference(brand(31L, "ACTIVE")),
                MasterdataViews.brandActiveReference(brand(32L, "DISABLED")));
        assertWireEquals(legacyPage(1, 50, 9007199254740993L, List.of(
                        legacyBrandActiveReference(brand(31L, "ACTIVE")),
                        legacyBrandActiveReference(brand(32L, "DISABLED")))),
                new MasterdataViews.Page<>(brands, 9007199254740993L, 1, 50));

        List<MasterdataViews.AssetCategoryActiveReference> categories = List.of(
                MasterdataViews.assetCategoryActiveReference(category("FU", "PC")));
        assertWireEquals(legacyPage(2, 20, 1L, List.of(
                        legacyAssetCategoryActiveReference(category("FU", "PC")))),
                new MasterdataViews.Page<>(categories, 1L, 2, 20));

        MasterdataViews.Page<MasterdataViews.AssetCategoryActiveReference> page =
                new MasterdataViews.Page<>(categories, 1L, 2, 20);
        assertEquals(1, page.items().size());
        assertEquals(1L, page.total());
        assertEquals(2, page.page());
        assertEquals(20, page.pageSize());
    }

    @Test
    void compactConstructorsCopyItemListDefensively() {
        java.util.ArrayList<MasterdataViews.BrandActiveReference> mutable = new java.util.ArrayList<>();
        mutable.add(MasterdataViews.brandActiveReference(brand(41L, "ACTIVE")));
        MasterdataViews.Page<MasterdataViews.BrandActiveReference> page =
                new MasterdataViews.Page<>(mutable, 1L, 1, 50);
        mutable.add(MasterdataViews.brandActiveReference(brand(42L, "ACTIVE")));
        assertEquals(1, page.items().size());
    }

    @Test
    void legacySelectorJsonStillDeserializesIntoTypedRecordsAndReRoundTrips() {
        Map<String, Object> legacyEnvelope = legacyPage(1, 50, 2L, List.of(
                legacyLocationActiveReference(location(11L, 21L, 3L)),
                legacyLocationActiveReference(location(null, null, 0L))));
        String stored = JSON.writeValueAsString(legacyEnvelope);

        MasterdataViews.Page<MasterdataViews.LocationActiveReference> parsed = JSON.readValue(stored,
                new TypeReference<MasterdataViews.Page<MasterdataViews.LocationActiveReference>>() {
                });
        JsonNode reRead = JSON.readTree(JSON.writeValueAsString(parsed));
        assertEquals(JSON.readTree(stored), reRead);
        assertEquals("11", parsed.items().get(0).warehouse().id());
        assertFalse(JSON.readTree(JSON.writeValueAsString(parsed.items().get(1))).has("warehouse"));

        MasterdataViews.BrandActiveReference brand = JSON.readValue(
                JSON.writeValueAsString(legacyBrandActiveReference(brand(31L, "DISABLED"))),
                MasterdataViews.BrandActiveReference.class);
        assertWireEquals(JSON.readTree(JSON.writeValueAsString(legacyBrandActiveReference(brand(31L, "DISABLED")))),
                JSON.readTree(JSON.writeValueAsString(brand)));
        assertFalse(JSON.readTree(JSON.writeValueAsString(brand)).has("defaultManufacturerId"));

        MasterdataViews.AssetCategoryActiveReference category = JSON.readValue(
                JSON.writeValueAsString(legacyAssetCategoryActiveReference(category("FU", null))),
                MasterdataViews.AssetCategoryActiveReference.class);
        assertWireEquals(JSON.readTree(JSON.writeValueAsString(legacyAssetCategoryActiveReference(category("FU", null)))),
                JSON.readTree(JSON.writeValueAsString(category)));
        assertFalse(JSON.readTree(JSON.writeValueAsString(category)).has("numberingPrefix"));
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        // Round-trip through JSON text: integral node widths (int vs long) serialize to the same
        // bytes, and the Map.of envelope key order was never stable in the pre-refactor responses.
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }

    private static LocationRow location(Long warehouseId, Long parentId, long linkedVersion) {
        return new LocationRow(101L, "BIN-A", "Bin A", "BIN", parentId,
                parentId == null ? null : linkedVersion, "ACTIVE", warehouseId,
                warehouseId == null ? null : linkedVersion, "ACTIVE", "North", "ACTIVE", 7L);
    }

    private static LocationRow locationWithWarehouseVersion(Long warehouseVersion) {
        return new LocationRow(101L, "BIN-A", "Bin A", "BIN", null, null, null, 11L,
                warehouseVersion, "ACTIVE", null, "ACTIVE", 7L);
    }

    private static BrandRow brand(Long defaultManufacturerId, String defaultManufacturerStatus) {
        return new BrandRow(202L, "BRAND-X", "Brand X", "desc", defaultManufacturerId,
                defaultManufacturerId == null ? null : 4L, defaultManufacturerStatus, "ACTIVE", 2L);
    }

    private static AssetCategoryRow category(String suggestedUnit, String numberingPrefix) {
        return new AssetCategoryRow(303L, "CAT-X", "Category X", 1L, 5L, "ACTIVE", "desc",
                suggestedUnit, numberingPrefix, "[]", "ACTIVE", 6L);
    }

    /** Frozen pre-JAVA-12b LocationService.toActiveReference projection; reference oracle only. */
    private static Map<String, Object> legacyLocationActiveReference(LocationRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", Long.toString(row.id()));
        result.put("code", row.code());
        result.put("name", row.name());
        result.put("version", row.version());
        result.put("type", row.locationType());
        if (row.warehouseId() != null) {
            result.put("warehouse", new com.fams.product.contract.model.CatalogReference(
                    Long.toString(row.warehouseId()), row.warehouseVersion()));
        }
        if (row.parentId() != null) {
            result.put("parentLocation", new com.fams.product.contract.model.CatalogReference(
                    Long.toString(row.parentId()), row.parentVersion()));
        }
        return result;
    }

    /** Frozen pre-JAVA-12b BrandService.toActiveReference projection; reference oracle only. */
    private static Map<String, Object> legacyBrandActiveReference(BrandRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", Long.toString(row.id()));
        result.put("code", row.code());
        result.put("name", row.name());
        result.put("version", row.version());
        if (row.defaultManufacturerId() != null
                && "ACTIVE".equals(row.defaultManufacturerStatus())) {
            result.put("defaultManufacturerId", Long.toString(row.defaultManufacturerId()));
        }
        return result;
    }

    /** Frozen pre-JAVA-12b AssetCategoryService.toActiveReference projection; reference oracle. */
    private static Map<String, Object> legacyAssetCategoryActiveReference(AssetCategoryRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", Long.toString(row.id()));
        result.put("code", row.code());
        result.put("name", row.name());
        result.put("version", row.version());
        if (row.suggestedUnit() != null) {
            result.put("suggestedUnit", row.suggestedUnit());
        }
        if (row.numberingPrefix() != null) {
            result.put("numberingPrefix", row.numberingPrefix());
        }
        return result;
    }

    /** Frozen pre-JAVA-12b listActive envelope; the four Map.of keys of the old response. */
    private static Map<String, Object> legacyPage(int page, int pageSize, long total,
            List<Map<String, Object>> items) {
        return Map.of("page", page, "pageSize", pageSize, "total", total, "items", items);
    }
}
