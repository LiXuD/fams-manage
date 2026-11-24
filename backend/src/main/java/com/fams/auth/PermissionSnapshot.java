package com.fams.auth;

import java.util.List;

/**
 * Immutable snapshot of one account's effective permissions: operation permissions, data scopes,
 * field permissions and detailed scope grants, together with the authorization version the
 * snapshot was loaded at.
 */
public record PermissionSnapshot(
        String userId,
        List<String> permissions,
        List<DataScope> dataScopes,
        List<String> fieldPermissions,
        long authorizationVersion,
        List<AuthorizedDataScope> scopeGrants) {

    /**
     * Convenience constructor for snapshots without detailed scope grants.
     *
     * @param userId account id as string
     * @param permissions effective operation permissions
     * @param dataScopes effective data scopes
     * @param fieldPermissions effective field permissions
     * @param authorizationVersion authorization version the snapshot was loaded at
     */
    public PermissionSnapshot(
            String userId,
            List<String> permissions,
            List<DataScope> dataScopes,
            List<String> fieldPermissions,
            long authorizationVersion) {
        this(userId, permissions, dataScopes, fieldPermissions, authorizationVersion, List.of());
    }

    /** Defensively copies every list so a published snapshot cannot be mutated afterwards. */
    public PermissionSnapshot {
        permissions = List.copyOf(permissions);
        dataScopes = List.copyOf(dataScopes);
        fieldPermissions = List.copyOf(fieldPermissions);
        scopeGrants = List.copyOf(scopeGrants);
    }
}
