package com.fams.masterdata;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Read-only inventory criteria may reference disabled records; never a new-asset selector. */
@Component
@Profile("fams-product")
public final class InventoryReferencePort {
    public record Choice(String id, String name, String status) {}

    private final JdbcTemplate db;

    public InventoryReferencePort(JdbcTemplate db) {
        this.db = db;
    }

    public List<Choice> choices(String kind, int page, int size) {
        String table =
                switch (kind) {
                    case "organization" -> "fams_organization";
                    case "category" -> "fams_asset_category";
                    case "warehouse" -> "fams_warehouse";
                    case "location" -> "fams_location";
                    case "employee" -> "fams_employee";
                    default -> throw new IllegalArgumentException("Unknown inventory criterion");
                };
        return db.query(
                "SELECT CAST(id AS CHAR) AS id,name,status FROM "
                        + table
                        + " ORDER BY id LIMIT ? OFFSET ?",
                (row, index) ->
                        new Choice(
                                row.getString("id"),
                                row.getString("name"),
                                row.getString("status")),
                size,
                (page - 1) * size);
    }
}
