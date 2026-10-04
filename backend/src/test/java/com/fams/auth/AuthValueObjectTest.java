package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AuthValueObjectTest {

    @Test
    void accountRolesAreDefensivelyCopied() {
        Set<Role> roles = EnumSet.of(Role.SYSTEM_ADMIN);
        AuthAccount account = new AuthAccount(
                1,
                "alice",
                "hash",
                AccountStatus.ENABLED,
                false,
                0,
                Instant.EPOCH,
                1,
                1,
                roles);

        roles.add(Role.ASSET_ADMIN);

        assertEquals(Set.of(Role.SYSTEM_ADMIN), account.roles());
        assertThrows(UnsupportedOperationException.class, () -> account.roles().add(Role.ASSET_ADMIN));
    }

    @Test
    void permissionCollectionsAreDefensivelyCopied() {
        List<String> permissions = new ArrayList<>(List.of("ACCOUNT_ADMIN"));
        List<DataScope> dataScopes = new ArrayList<>(List.of(DataScope.ENTERPRISE));
        List<String> fieldPermissions = new ArrayList<>(List.of("ASSET_FINANCE_VIEW"));
        PermissionSnapshot snapshot = new PermissionSnapshot(
                "1",
                permissions,
                dataScopes,
                fieldPermissions,
                1);

        permissions.add("ASSET_VIEW");
        dataScopes.add(DataScope.SELF);
        fieldPermissions.add("ASSET_FINANCE_EDIT");

        assertEquals(List.of("ACCOUNT_ADMIN"), snapshot.permissions());
        assertEquals(List.of(DataScope.ENTERPRISE), snapshot.dataScopes());
        assertEquals(List.of("ASSET_FINANCE_VIEW"), snapshot.fieldPermissions());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.permissions().add("ASSET_VIEW"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.dataScopes().add(DataScope.SELF));
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.fieldPermissions().add("ASSET_FINANCE_EDIT"));
    }
}
