package com.fams.auth;

import java.util.List;

public record PermissionSnapshot(
        String userId,
        List<String> permissions,
        List<DataScope> dataScopes,
        List<String> fieldPermissions,
        long authorizationVersion) {

    public PermissionSnapshot {
        permissions = List.copyOf(permissions);
        dataScopes = List.copyOf(dataScopes);
        fieldPermissions = List.copyOf(fieldPermissions);
    }
}
