package com.fams.asset;

import java.util.Map;

/** Fixed table whitelist for asset-list reference versions; identifiers remain bound parameters. */
public final class AssetReferenceBatchSql {
    private AssetReferenceBatchSql() { }

    public static String select(Map<String, Object> parameters) {
        String table = switch ((String) parameters.get("kind")) {
            case "category" -> "fams_asset_category";
            case "organization" -> "fams_organization";
            case "warehouse" -> "fams_warehouse";
            case "location" -> "fams_location";
            case "brand" -> "fams_brand";
            case "manufacturer" -> "fams_manufacturer";
            case "employee" -> "fams_employee";
            default -> throw new IllegalArgumentException("Unknown asset reference kind");
        };
        return "<script>SELECT id,version FROM " + table
                + " WHERE id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>"
                + "#{id}</foreach></script>";
    }
}
