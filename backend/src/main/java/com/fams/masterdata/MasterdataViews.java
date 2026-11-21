package com.fams.masterdata;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Typed wire shapes of the masterdata active-reference selectors, mirroring
 * contracts/fams-core-v1.yaml while following the actual pre-JAVA-12b projections.
 * Each record reproduces verbatim the LinkedHashMap assembly the corresponding service used to
 * return: identifiers stringify, version columns stay int64 numbers, and a key is emitted only
 * when the frozen {@code put} guard held (conditional keys carry NON_NULL, so the globally
 * configured {@code Include.ALWAYS} mapper keeps them omitted rather than writing an explicit
 * null). The page keys are the four Map.of keys of the old listActive assemblies.
 */
public final class MasterdataViews {

    private MasterdataViews() {
    }

    /** page/pageSize/total/items envelope shared by the three selector endpoints. */
    public record Page<T>(List<T> items, long total, int page, int pageSize) {
        public Page {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /**
     * Immutable mirror of the generated CatalogReference (String id, Long version): embedding the
     * mutable contract class in a record trips SpotBugs EI_EXPOSE_REP, while this shape keeps the
     * identical {@code {id, version}} wire under the global Include.ALWAYS mapper.
     */
    public record ActiveReference(String id, Long version) {
    }

    /**
     * Frozen LocationService.toActiveReference projection. {@code type} is the raw
     * fams_location.location_type column; warehouse and parentLocation appear only when the row
     * carries the corresponding id, in that declaration order.
     */
    public record LocationActiveReference(
            String id,
            String code,
            String name,
            long version,
            String type,
            @JsonInclude(JsonInclude.Include.NON_NULL) ActiveReference warehouse,
            @JsonInclude(JsonInclude.Include.NON_NULL) ActiveReference parentLocation) {
    }

    public static LocationActiveReference locationActiveReference(LocationRow row) {
        ActiveReference warehouse = row.warehouseId() == null ? null
                : new ActiveReference(Long.toString(row.warehouseId()), row.warehouseVersion());
        ActiveReference parentLocation = row.parentId() == null ? null
                : new ActiveReference(Long.toString(row.parentId()), row.parentVersion());
        return new LocationActiveReference(Long.toString(row.id()), row.code(), row.name(),
                row.version(), row.locationType(), warehouse, parentLocation);
    }

    /**
     * Frozen BrandService.toActiveReference projection. {@code defaultManufacturerId} is emitted
     * only for an active default manufacturer, keeping disabled history out of new selectors
     * without rewriting the brand row.
     */
    public record BrandActiveReference(
            String id,
            String code,
            String name,
            long version,
            @JsonInclude(JsonInclude.Include.NON_NULL) String defaultManufacturerId) {
    }

    public static BrandActiveReference brandActiveReference(BrandRow row) {
        String defaultManufacturerId = row.defaultManufacturerId() != null
                && "ACTIVE".equals(row.defaultManufacturerStatus())
                        ? Long.toString(row.defaultManufacturerId())
                        : null;
        return new BrandActiveReference(Long.toString(row.id()), row.code(), row.name(),
                row.version(), defaultManufacturerId);
    }

    /**
     * Frozen AssetCategoryService.toActiveReference projection. suggestedUnit and numberingPrefix
     * are conditional keys that mirror the two non-null guards of the old assembly.
     */
    public record AssetCategoryActiveReference(
            String id,
            String code,
            String name,
            long version,
            @JsonInclude(JsonInclude.Include.NON_NULL) String suggestedUnit,
            @JsonInclude(JsonInclude.Include.NON_NULL) String numberingPrefix) {
    }

    public static AssetCategoryActiveReference assetCategoryActiveReference(AssetCategoryRow row) {
        return new AssetCategoryActiveReference(Long.toString(row.id()), row.code(), row.name(),
                row.version(), row.suggestedUnit(), row.numberingPrefix());
    }
}
