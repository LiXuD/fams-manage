package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

class AuthPolicyTest {

    @Test
    void systemAdminDoesNotReceiveBusinessPermissions() {
        PermissionSnapshot snapshot = AuthPolicy.snapshot(
                "42",
                EnumSet.of(Role.SYSTEM_ADMIN),
                7L,
                3L);

        assertTrue(snapshot.permissions().contains("ACCOUNT_ADMIN"));
        assertTrue(snapshot.permissions().contains("ROLE_ADMIN"));
        assertFalse(snapshot.permissions().contains("ASSET_LEDGER_READ"));
        assertFalse(snapshot.permissions().contains("ASSET_ADMIN_DECISION"));
        assertTrue(snapshot.dataScopes().isEmpty());
    }

    @Test
    void assetAdminReceivesEnterpriseLedgerAndDecisionPermissions() {
        PermissionSnapshot snapshot = AuthPolicy.snapshot(
                "42",
                EnumSet.of(Role.ASSET_ADMIN),
                7L,
                3L);

        assertTrue(snapshot.permissions().contains("ASSET_LEDGER_READ"));
        assertTrue(snapshot.permissions().contains("ASSET_ADMIN_DECISION"));
        assertTrue(snapshot.dataScopes().contains(DataScope.ENTERPRISE));
    }

    @Test
    void multipleRolesUnionOnlyExplicitPermissions() {
        PermissionSnapshot snapshot = AuthPolicy.snapshot(
                "42",
                EnumSet.of(Role.EMPLOYEE, Role.WAREHOUSE_OPERATOR),
                7L,
                3L);

        assertTrue(snapshot.permissions().contains("AUTH_SESSION_READ"));
        assertTrue(snapshot.permissions().contains("WAREHOUSE_RECEIVE"));
        assertFalse(snapshot.permissions().contains("ASSET_ADMIN_DECISION"));
        assertFalse(snapshot.permissions().contains("ACCOUNT_ADMIN"));
    }
}
