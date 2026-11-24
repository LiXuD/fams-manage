package com.fams.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Business role used for inbound decisions and execution. */
@Profile("fams-product")
@Component
public final class InboundActorContext {
    private final HttpServletRequest request;

    InboundActorContext(HttpServletRequest request) {
        this.request = request;
    }

    /**
     * Gate for inbound operations restricted to warehouse operators.
     *
     * @return actor projection carrying the warehouse-operator role name
     * @throws AuthOperationException when the account is not a warehouse operator
     */
    public Actor requireWarehouse() { return require(Role.WAREHOUSE_OPERATOR); }
    /**
     * Gate for inbound decisions restricted to asset admins.
     *
     * @return actor projection carrying the asset-admin role name
     * @throws AuthOperationException when the account is not an asset admin
     */
    public Actor requireAdmin() { return require(Role.ASSET_ADMIN); }

    /**
     * Read gate for inbound receipts: warehouse operators take precedence over asset admins when
     * an account holds both roles.
     *
     * @return actor projection carrying the matched role name
     * @throws AuthOperationException when the account holds neither allowed role
     */
    public Actor requireReader() {
        AuthAccount account = AuthRequestContext.account(request);
        if (account.roles().contains(Role.WAREHOUSE_OPERATOR)) {
            return actor(account, Role.WAREHOUSE_OPERATOR);
        }
        if (account.roles().contains(Role.ASSET_ADMIN)) {
            return actor(account, Role.ASSET_ADMIN);
        }
        throw AuthOperationException.forbidden();
    }

    private Actor require(Role role) {
        AuthAccount account = AuthRequestContext.account(request);
        if (!account.roles().contains(role)) { throw AuthOperationException.forbidden(); }
        return actor(account, role);
    }

    private Actor actor(AuthAccount account, Role role) {
        return new Actor(account.id(), account.username(), role.name(),
                AuthRequestContext.requestId(request).toString());
    }

    /** Actor projection handed to the inbound domain: identity, matched role and request id. */
    public record Actor(long id, String username, String role, String requestId) { }
}
