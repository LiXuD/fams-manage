package com.fams.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Profile("fams-product")
public final class InventoryExecutorPort {

    /**
     * Eligible executor option. Component names mirror the choices() SQL aliases (already
     * camelCase CAST-as-CHAR strings) because the row list is returned verbatim as the
     * /inventory/executors HTTP body; the serialized shape is frozen by SmallPortsWireShapeTest.
     */
    public record ExecutorChoice(String id, String name, String organizationId) {

        /** Projection of one choices() JDBC row (id,name,organizationId). */
        public static ExecutorChoice fromRow(Map<String, Object> row) {
            return new ExecutorChoice(
                    (String) row.get("id"),
                    (String) row.get("name"),
                    (String) row.get("organizationId"));
        }
    }

    private static final String ELIGIBLE =
            " FROM fams_system_account a JOIN fams_employee e ON e.id=a.employee_id JOIN"
                + " fams_organization o ON o.id=e.organization_id WHERE a.status='ENABLED' AND"
                + " a.initial_password_required=FALSE AND e.status='ACTIVE' AND o.status='ACTIVE'"
                + " AND EXISTS (SELECT 1 FROM fams_account_role r WHERE r.account_id=a.id AND"
                + " r.role_code IN"
                + " ('EMPLOYEE','DEPARTMENT_ASSET_MANAGER','ASSET_ADMIN','WAREHOUSE_OPERATOR'))";
    private final JdbcTemplate db;

    public InventoryExecutorPort(JdbcTemplate db) {
        this.db = db;
    }

    public boolean eligible(long accountId) {
        return !db.queryForList("SELECT a.id" + ELIGIBLE + " AND a.id=? FOR SHARE", accountId)
                .isEmpty();
    }

    public List<ExecutorChoice> choices(int page, int size) {
        return db.queryForList(
                        "SELECT CAST(a.id AS CHAR) AS id,e.name,CAST(e.organization_id AS CHAR) AS"
                            + " organizationId"
                            + ELIGIBLE
                            + " ORDER BY a.id LIMIT ? OFFSET ?",
                        size,
                        (page - 1) * size)
                .stream()
                .map(ExecutorChoice::fromRow)
                .toList();
    }
}
