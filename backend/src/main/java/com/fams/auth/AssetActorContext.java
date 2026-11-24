package com.fams.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Current request identity; the QR token never supplies authority. */
@Profile("fams-product")
@Component
public final class AssetActorContext {
    private final HttpServletRequest request;

    AssetActorContext(HttpServletRequest request) {
        this.request = request;
    }

    /**
     * Read gate for the asset module: asset admins and warehouse operators may read, with the
     * warehouse flag and role name reflecting which of the two the account holds. Audit-only
     * accounts are rejected until audit scope is exposed to this module.
     *
     * @return actor projection of the authenticated account
     * @throws AuthOperationException when the account holds neither allowed role
     */
    public Actor requireRead() {
        AuthAccount account = AuthRequestContext.account(request);
        if (account.roles().contains(Role.ASSET_ADMIN)
                || account.roles().contains(Role.WAREHOUSE_OPERATOR)) {
            return new Actor(account.id(), account.username(),
                    account.roles().contains(Role.WAREHOUSE_OPERATOR), false,
                    account.roles().contains(Role.WAREHOUSE_OPERATOR)
                            ? Role.WAREHOUSE_OPERATOR.name() : Role.ASSET_ADMIN.name(),
                    AuthRequestContext.requestId(request).toString());
        }
        if (account.roles().contains(Role.AUDIT_READ_ONLY)) {
            // Audit scope is not yet exposed to this module. Fail closed until it is.
            throw AuthOperationException.forbidden();
        }
        throw AuthOperationException.forbidden();
    }

    /**
     * Gate for write operations restricted to warehouse operators.
     *
     * @return actor projection marked as warehouse operator
     * @throws AuthOperationException when the account is not a warehouse operator
     */
    public Actor requireWarehouseOperator() {
        AuthAccount account = AuthRequestContext.account(request);
        if (!account.roles().contains(Role.WAREHOUSE_OPERATOR)) {
            throw AuthOperationException.forbidden();
        }
        return new Actor(account.id(), account.username(), true, false,
                Role.WAREHOUSE_OPERATOR.name(), AuthRequestContext.requestId(request).toString());
    }

    /**
     * Gate for operations restricted to asset admins.
     *
     * @return actor projection carrying the asset-admin role name
     * @throws AuthOperationException when the account is not an asset admin
     */
    public Actor requireAssetAdmin() {
        return requireRole(Role.ASSET_ADMIN);
    }

    /**
     * Gate for operations restricted to system admins.
     *
     * @return actor projection carrying the system-admin role name
     * @throws AuthOperationException when the account is not a system admin
     */
    public Actor requireSystemAdmin() {
        return requireRole(Role.SYSTEM_ADMIN);
    }

    private Actor requireRole(Role role) {
        AuthAccount account = AuthRequestContext.account(request);
        if (!account.roles().contains(role)) { throw AuthOperationException.forbidden(); }
        return new Actor(account.id(), account.username(), false, false,
                role.name(), AuthRequestContext.requestId(request).toString());
    }

    /**
     * Actor projection handed to the asset domain: account identity, the session authority
     * versions used for revalidation, the role the gate matched and the request id for
     * idempotency.
     */
    public record Actor(long id, String username, boolean warehouseOperator,
            boolean auditOnly, String role, String requestId) { }
}
