package com.fams.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal identity state for synchronization; never exposes credentials or contacts. */
@Component
@Profile("fams-product")
public final class InventorySynchronizationIdentityPort {
    private final JdbcTemplate db;

    public InventorySynchronizationIdentityPort(JdbcTemplate db) {
        this.db = db;
    }

    public Map<Long, State> states(List<Long> accounts) {
        if (accounts.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(accounts.size(), "?"));
        var rows =
                db.queryForList(
                        "SELECT a.id,e.name,a.status AS"
                            + " account_status,a.initial_password_required,e.status AS"
                            + " employee_status,o.status AS organization_status,b.id AS"
                            + " binding_id,b.version AS binding_version,EXISTS(SELECT 1 FROM"
                            + " fams_account_role r WHERE r.account_id=a.id AND r.role_code IN"
                            + " ('EMPLOYEE','DEPARTMENT_ASSET_MANAGER','ASSET_ADMIN','WAREHOUSE_OPERATOR'))"
                            + " AS eligible_role FROM fams_system_account a JOIN fams_employee e ON"
                            + " e.id=a.employee_id JOIN fams_organization o ON"
                            + " o.id=e.organization_id LEFT JOIN fams_mini_binding b ON"
                            + " b.account_id=a.id AND b.revoked_at IS NULL WHERE a.id IN ("
                                + placeholders
                                + ")",
                        accounts.toArray());
        Map<Long, State> result = new LinkedHashMap<>();
        for (var row : rows) {
            boolean enabled =
                    "ENABLED".equals(row.get("account_status"))
                            && "ACTIVE".equals(row.get("employee_status"))
                            && "ACTIVE".equals(row.get("organization_status"))
                            && !Boolean.TRUE.equals(row.get("initial_password_required"))
                            && ((Number) row.get("eligible_role")).intValue() == 1;
            result.put(
                    ((Number) row.get("id")).longValue(),
                    new State(
                            row.get("name").toString(),
                            enabled,
                            row.get("binding_id") == null
                                    ? null
                                    : ((Number) row.get("binding_id")).longValue(),
                            row.get("binding_version") == null
                                    ? null
                                    : ((Number) row.get("binding_version")).longValue()));
        }
        return Map.copyOf(result);
    }

    public record State(String name, boolean enabled, Long bindingId, Long bindingVersion) {}
}
