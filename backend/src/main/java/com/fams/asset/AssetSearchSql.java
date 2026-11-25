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
        {"departmentResponsibleEmployeeId",
                "a.department_responsible_employee_id = #{filters.departmentResponsibleEmployeeId}"},
        {"inboundDateFrom", "a.inbound_business_date &gt;= #{filters.inboundDateFrom}"},
        {"inboundDateTo", "a.inbound_business_date &lt;= #{filters.inboundDateTo}"},
        {"lastInventoryDateFrom", "a.last_inventory_business_date &gt;= #{filters.lastInventoryDateFrom}"},
        {"lastInventoryDateTo", "a.last_inventory_business_date &lt;= #{filters.lastInventoryDateTo}"},
        // CHECKSTYLE.OFF: LineLength - frozen SQL/CSV literal; re-wrapping changes the value
        {"inventoryBefore", """
            a.card_stage='ACTIVE' AND a.lifecycle_status NOT IN ('WAITING_INBOUND','SCRAPPED') AND
            <choose>
                <when test="filters.longUninventoried == false">
                    a.last_inventory_business_date &gt;= #{filters.inventoryBefore}
                </when>
                <otherwise>
                    (a.last_inventory_business_date IS NULL OR a.last_inventory_business_date &lt; #{filters.inventoryBefore})
                </otherwise>
            </choose>
            """},
        // CHECKSTYLE.ON: LineLength
        {"currentProcessorId", AssetLedgerWorkflowSql.PROCESSOR},
        {"businessReference", AssetLedgerWorkflowSql.REFERENCE},
        {"borrowOverdue", """
            EXISTS (
                SELECT 1 FROM fams_loan l
                WHERE l.open_asset_id=a.id AND a.lifecycle_status&lt;&gt;'SCRAPPED' AND a.exception_status&lt;&gt;'LOST'
                  AND l.expected_return_date &lt; #{filters.borrowOverdue.latest}
                  AND (l.expected_return_date &lt; #{filters.borrowOverdue.earliest}
                    OR l.expected_return_date &lt; CAST(JSON_UNQUOTE(JSON_EXTRACT(#{filters.borrowOverdue.datesByZone},
                       CONCAT('$.',JSON_QUOTE(l.parameter_snapshot_json->>'$.timezone')))) AS DATE))
            ) = #{filters.borrowOverdue.expected}
            """}
    };

    private AssetSearchSql() { }

    /**
     * Ledger list statement: the whitelisted predicates plus the shared physical projection,
     * ordered by descending id and closed with the caller-supplied {@code limit}/{@code offset}
     * parameters so paging never interpolates user values.
     *
     * @return dynamic {@code <script>} statement yielding one fams_asset row per match
     */
    public static String list() {
        return where("SELECT a.*, " + AssetPhysicalSql.PROJECTION + " ")
                + " ORDER BY a.id DESC LIMIT #{limit} OFFSET #{offset}</script>";
    }

    /**
     * Ledger total for the same predicate set as {@link #list()}, so the page footer counts exactly
     * the rows the list would return without paging.
     *
     * @return dynamic {@code <script>} statement yielding a single {@code COUNT(*)} value
     */
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
