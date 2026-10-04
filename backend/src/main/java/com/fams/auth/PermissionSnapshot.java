package com.fams.auth;

import java.util.List;

public record PermissionSnapshot(
        String userId,
        List<String> permissions,
        List<DataScope> dataScopes,
        List<String> fieldPermissions,
        long authorizationVersion,
        List<AuthorizedDataScope> scopeGrants) {

    public PermissionSnapshot(
            String userId,
            List<String> permissions,
            List<DataScope> dataScopes,
            List<String> fieldPermissions,
            long authorizationVersion) {
        this(userId, permissions, dataScopes, fieldPermissions, authorizationVersion, List.of());
    }

    public PermissionSnapshot {
        permissions = List.copyOf(permissions);
        dataScopes = List.copyOf(dataScopes);
        fieldPermissions = List.copyOf(fieldPermissions);
        scopeGrants = List.copyOf(scopeGrants);
    }
}
