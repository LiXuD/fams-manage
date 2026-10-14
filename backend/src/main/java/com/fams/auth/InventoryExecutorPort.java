package com.fams.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Profile("fams-product")
public final class InventoryExecutorPort {
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

    public List<Map<String, Object>> choices(int page, int size) {
        return db.queryForList(
                "SELECT CAST(a.id AS CHAR) AS id,e.name,CAST(e.organization_id AS CHAR) AS"
                    + " organizationId"
                        + ELIGIBLE
                        + " ORDER BY a.id LIMIT ? OFFSET ?",
                size,
                (page - 1) * size);
    }
}
