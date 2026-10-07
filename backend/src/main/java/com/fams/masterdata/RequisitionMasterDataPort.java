package com.fams.masterdata;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Public, non-sensitive catalog for the owning requisition domain. */
@Component
@Profile("fams-product")
public final class RequisitionMasterDataPort {
    private final JdbcTemplate db;

    RequisitionMasterDataPort(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> current(String kind, long id, boolean lock) {
        String table = table(kind);
        String columns =
                "id,name,status,version" + ("employee".equals(kind) ? ",organization_id" : "");
        var rows =
                db.queryForList(
                        "SELECT "
                                + columns
                                + " FROM "
                                + table
                                + " WHERE id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        id);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    public List<Map<String, Object>> choices(
            String kind, Long employeeId, Long organizationId, int page, int size) {
        String columns = "id,name,version" + ("employee".equals(kind) ? ",organization_id" : "");
        String condition = "status='ACTIVE'";
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if ("employee".equals(kind)) {
            condition += " AND (? IS NULL OR id=?) AND (? IS NULL OR organization_id=?)";
            args.add(employeeId);
            args.add(employeeId);
            args.add(organizationId);
            args.add(organizationId);
        } else if ("organization".equals(kind) && organizationId != null) {
            condition += " AND id=?";
            args.add(organizationId);
        }
        args.add(size);
        args.add((page - 1) * size);
        return db.queryForList(
                "SELECT "
                        + columns
                        + " FROM "
                        + table(kind)
                        + " WHERE "
                        + condition
                        + " ORDER BY id LIMIT ? OFFSET ?",
                args.toArray());
    }

    private static String table(String kind) {
        return switch (kind) {
            case "category" -> "fams_asset_category";
            case "organization" -> "fams_organization";
            case "employee" -> "fams_employee";
            case "location" -> "fams_location";
            default -> throw new IllegalArgumentException("Unknown requisition reference kind");
        };
    }
}
