package com.fams.asset;

/** Whitelisted predicates only; user values are always MyBatis parameters. */
public final class AssetSearchSql {
    private static final String BASE = """
            FROM fams_asset a
            WHERE (a.card_stage NOT IN ('DRAFT', 'CANCELLED') OR a.creator_account_id = #{actorId})
            """;

    private static final String[][] FIELDS = {
        {"assetCode", "a.asset_code LIKE CONCAT('%', #{filters.assetCode}, '%')"},
        {"name", "a.name LIKE CONCAT('%', #{filters.name}, '%')"},
        {"serialNumber", "a.serial_number_normalized = #{filters.serialNumber}"},
        {"assetCategoryId", "a.category_id = #{filters.assetCategoryId}"},
        {"lifecycle", "a.lifecycle_status = #{filters.lifecycle}"},
        {"exception", "a.exception_status = #{filters.exception}"},
        {"usageMode", "a.custody_type = #{filters.usageMode}"},
        {"warehouseId", "a.storage_warehouse_id = #{filters.warehouseId}"},
        {"locationId", "a.current_location_id = #{filters.locationId}"},
        {"organizationId", "a.owning_organization_id = #{filters.organizationId}"},
        {"usingOrganizationId", "a.using_organization_id = #{filters.usingOrganizationId}"},
        {"responsibleEmployeeId", "a.responsible_employee_id = #{filters.responsibleEmployeeId}"},
        {"departmentResponsibleEmployeeId", "a.department_responsible_employee_id = #{filters.departmentResponsibleEmployeeId}"},
        {"inboundDateFrom", "a.inbound_business_date &gt;= #{filters.inboundDateFrom}"},
        {"inboundDateTo", "a.inbound_business_date &lt;= #{filters.inboundDateTo}"},
        {"lastInventoryDateFrom", "a.last_inventory_business_date &gt;= #{filters.lastInventoryDateFrom}"},
        {"lastInventoryDateTo", "a.last_inventory_business_date &lt;= #{filters.lastInventoryDateTo}"}
    };

    private AssetSearchSql() { }

    public static String list() {
        return where("SELECT a.*, " + AssetPhysicalSql.PROJECTION + " ") + " ORDER BY a.id DESC LIMIT #{limit} OFFSET #{offset}</script>";
    }

    public static String count() {
        return where("SELECT COUNT(*) ") + "</script>";
    }

    private static String where(String select) {
        StringBuilder sql = new StringBuilder("<script>").append(select).append(BASE);
        for (String[] field : FIELDS) {
            sql.append("<if test='filters.").append(field[0]).append(" != null'> AND ")
                    .append(field[1]).append("</if>");
        }
        return sql.toString();
    }
}
