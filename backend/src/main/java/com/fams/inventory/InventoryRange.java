package com.fams.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Queries only the asset owner's published v1 read contract. Values are always bound. */
final class InventoryRange {
    static final String SOURCE = "fams_asset_inventory_source_v1";

    record Predicate(String sql, List<Object> args) {}

    private InventoryRange() {}

    static Predicate predicate(InventoryInput.Range range) {
        if (range == null) {
            throw new InventoryFailure(400, "INVENTORY_RANGE_INVALID");
        }
        StringBuilder sql = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        add(sql, args, "using_organization_id", range.usingOrganizationId());
        add(sql, args, "owning_organization_id", range.owningOrganizationId());
        add(sql, args, "category_id", range.categoryId());
        add(sql, args, "storage_warehouse_id", range.warehouseId());
        add(sql, args, "current_location_id", range.locationId());
        add(sql, args, "responsible_employee_id", range.responsibleEmployeeId());
        if (range.lifecycle() != null) {
            if (!Set.of(
                            "IN_STOCK",
                            "IN_USE",
                            "BORROWED",
                            "UNDER_REPAIR",
                            "FACTORY_REPAIR",
                            "PENDING_SCRAP",
                            "SCRAPPED")
                    .contains(range.lifecycle())) {
                throw new InventoryFailure(400, "INVENTORY_RANGE_INVALID");
            }
            sql.append(" AND lifecycle_status=?");
            args.add(range.lifecycle());
        }
        if (range.exception() != null) {
            if (!Set.of("NORMAL", "SEARCHING", "LOST").contains(range.exception())) {
                throw new InventoryFailure(400, "INVENTORY_RANGE_INVALID");
            }
            sql.append(" AND exception_status=?");
            args.add(range.exception());
        }
        return new Predicate(sql.toString(), List.copyOf(args));
    }

    private static void add(StringBuilder sql, List<Object> args, String column, Long value) {
        if (value == null) {
            return;
        }
        if (value < 1) {
            throw new InventoryFailure(400, "INVENTORY_RANGE_INVALID");
        }
        sql.append(" AND ").append(column).append("=?");
        args.add(value);
    }
}
