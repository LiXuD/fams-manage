package com.fams.asset;

import java.util.Map;

/** Fixed table whitelist for asset-list reference versions; identifiers remain bound parameters. */
public final class AssetReferenceBatchSql {
    private AssetReferenceBatchSql() { }

    /**
     * Builds the batched {@code SELECT id,version} statement for one reference kind. The kind
     * selects a table from a closed switch, so caller input can only choose among the seven
     * whitelisted tables and never reaches the SQL text; the ids stay {@code #{id}} bound
     * parameters inside the foreach.
     *
     * @param parameters MyBatis provider map carrying {@code kind} (category, organization,
     *     warehouse, location, brand, manufacturer or employee) and {@code ids}
     * @return dynamic {@code <script>} statement returning one {@code id,version} row per id
     * @throws IllegalArgumentException when {@code kind} is not one of the seven known kinds
     */
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
