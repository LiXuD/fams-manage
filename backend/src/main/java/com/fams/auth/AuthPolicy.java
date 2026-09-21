package com.fams.auth;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class AuthPolicy {

    private AuthPolicy() {
    }

    static PermissionSnapshot snapshot(
            String userId,
            Collection<Role> roles,
            long authorizationVersion,
            long ignoredAccountEpoch) {
        Set<Role> effectiveRoles = roles.isEmpty()
                ? EnumSet.noneOf(Role.class)
                : EnumSet.copyOf(roles);
        LinkedHashSet<String> permissions = new LinkedHashSet<>();
        LinkedHashSet<DataScope> dataScopes = new LinkedHashSet<>();
        for (Role role : effectiveRoles) {
            permissions.addAll(permissionsFor(role));
            dataScopes.addAll(scopesFor(role));
        }
        return new PermissionSnapshot(
                userId,
                List.copyOf(permissions),
                List.copyOf(dataScopes),
                List.of(),
                authorizationVersion);
    }

    private static Collection<String> permissionsFor(Role role) {
        List<String> permissions = new ArrayList<>();
        permissions.add("AUTH_SESSION_READ");
        switch (role) {
            case SYSTEM_ADMIN -> permissions.addAll(List.of(
                    "ACCOUNT_ADMIN", "ROLE_ADMIN", "MASTER_DATA_ADMIN"));
            case ASSET_ADMIN -> permissions.addAll(List.of(
                    "ASSET_LEDGER_READ", "ASSET_ADMIN_DECISION"));
            case WAREHOUSE_OPERATOR -> permissions.addAll(List.of(
                    "ASSET_LEDGER_READ", "WAREHOUSE_RECEIVE"));
            case AUDIT_READ_ONLY -> permissions.add("AUDIT_READ");
            case EMPLOYEE, DEPARTMENT_ASSET_MANAGER, TECH_ASSISTANT -> {
                // The fixed V1 baseline gives these roles session-only access here.
            }
        }
        return permissions;
    }

    private static Collection<DataScope> scopesFor(Role role) {
        return switch (role) {
            case ASSET_ADMIN -> List.of(DataScope.ENTERPRISE);
            case WAREHOUSE_OPERATOR -> List.of(DataScope.SPECIFIED_WAREHOUSES);
            case EMPLOYEE -> List.of(DataScope.SELF);
            case DEPARTMENT_ASSET_MANAGER -> List.of(DataScope.OWN_ORGANIZATION);
            default -> List.of();
        };
    }
}
