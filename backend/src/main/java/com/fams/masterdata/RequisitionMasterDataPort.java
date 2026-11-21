package com.fams.masterdata;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Public, non-sensitive catalog for the owning requisition domain. */
@Component
@Profile("fams-product")
public final class RequisitionMasterDataPort {

    /**
     * Locked reference row. Snake_case JSON names are persisted verbatim into requisition demand
     * target_snapshot_json (JAVA-01 storage-shape precedent). organization_id exists only on
     * employee rows, so NON_NULL keeps the key absent for every other kind, matching the frozen
     * JDBC row map serialization proven by SmallPortsWireShapeTest.
     */
    public record MasterReference(
            @JsonProperty("id") long id,
            @JsonProperty("name") String name,
            @JsonProperty("status") String status,
            @JsonProperty("version") long version,
            @JsonProperty("organization_id")
                    @JsonInclude(JsonInclude.Include.NON_NULL)
                    Long organizationId) {

        /** Projection of one current() JDBC row (id,name,status,version[,organization_id]). */
        public static MasterReference fromRow(Map<String, Object> row) {
            return new MasterReference(
                    ((Number) row.get("id")).longValue(),
                    (String) row.get("name"),
                    (String) row.get("status"),
                    ((Number) row.get("version")).longValue(),
                    row.get("organization_id") == null
                            ? null
                            : ((Number) row.get("organization_id")).longValue());
        }
    }

    /**
     * Catalog option in the frozen requisition clean() wire form (JAVA-09): stringified id and
     * organizationId, numeric version; organizationId appears only on employee kind rows, where
     * fams_employee.organization_id is NOT NULL (V1__create_authentication).
     */
    public record CatalogChoice(
            String id,
            String name,
            long version,
            @JsonInclude(JsonInclude.Include.NON_NULL) String organizationId) {

        /** Projection of one choices() JDBC row (id,name,version[,organization_id]). */
        public static CatalogChoice fromRow(Map<String, Object> row) {
            return new CatalogChoice(
                    row.get("id").toString(),
                    (String) row.get("name"),
                    ((Number) row.get("version")).longValue(),
                    row.get("organization_id") == null
                            ? null
                            : row.get("organization_id").toString());
        }
    }

    private final JdbcTemplate db;

    RequisitionMasterDataPort(JdbcTemplate db) {
        this.db = db;
    }

    /** The locked reference row, or null when the reference does not exist. */
    public MasterReference current(String kind, long id, boolean lock) {
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
        return rows.isEmpty() ? null : MasterReference.fromRow(rows.getFirst());
    }

    public List<CatalogChoice> choices(
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
                        args.toArray())
                .stream()
                .map(CatalogChoice::fromRow)
                .toList();
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
