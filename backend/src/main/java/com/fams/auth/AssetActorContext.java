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

    public Actor requireWarehouseOperator() {
        AuthAccount account = AuthRequestContext.account(request);
        if (!account.roles().contains(Role.WAREHOUSE_OPERATOR)) {
            throw AuthOperationException.forbidden();
        }
        return new Actor(account.id(), account.username(), true, false,
                Role.WAREHOUSE_OPERATOR.name(), AuthRequestContext.requestId(request).toString());
    }

    public record Actor(long id, String username, boolean warehouseOperator,
            boolean auditOnly, String role, String requestId) { }
}
