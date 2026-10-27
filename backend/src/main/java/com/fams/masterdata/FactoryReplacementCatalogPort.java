package com.fams.masterdata;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Read-only active, versioned selectors for an authorized replacement creator. */
@Component
@Profile("fams-product")
public final class FactoryReplacementCatalogPort {
    private final AssetCategoryService categories;
    private final IdentityMasterDataService identities;
    private final WarehouseService warehouses;
    private final LocationService locations;

    public FactoryReplacementCatalogPort(AssetCategoryService categories,
            IdentityMasterDataService identities, WarehouseService warehouses,
            LocationService locations) {
        this.categories = categories;
        this.identities = identities;
        this.warehouses = warehouses;
        this.locations = locations;
    }

    public Object references(String kind, int page, int size) {
        return switch (kind) {
            case "category" -> categories.listActive(page, size);
            case "organization" -> identities.listActiveOrganizations(page, size);
            case "warehouse" -> warehouses.listActive(page, size);
            case "location" -> locations.listActive(page, size);
            default -> throw new IllegalArgumentException("Unknown replacement reference kind");
        };
    }
}
