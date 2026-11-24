package com.fams.auth;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Role gates for master-data maintenance; each gate returns the authenticated account id. */
@Profile("fams-product")
@Component
public final class MasterDataActorContext {

    private final HttpServletRequest request;

    MasterDataActorContext(HttpServletRequest request) {
        this.request = request;
    }

    /**
     * Gate for master-data writes restricted to system administrators.
     *
     * @return the authenticated account id
     * @throws AuthOperationException when the account is not a system admin
     */
    public long requireSystemAdministrator() {
        AuthAccount account = AuthRequestContext.account(request);
        if (!account.roles().contains(Role.SYSTEM_ADMIN)) {
            throw AuthOperationException.forbidden();
        }
        return account.id();
    }

    /**
     * Gate for catalog selection reads, open to system admins, warehouse operators and asset
     * admins.
     *
     * @return the authenticated account id
     * @throws AuthOperationException when the account holds none of the three allowed roles
     */
    public long requireCatalogSelector() {
        AuthAccount account = AuthRequestContext.account(request);
        if (!account.roles().contains(Role.SYSTEM_ADMIN)
                && !account.roles().contains(Role.WAREHOUSE_OPERATOR)
                && !account.roles().contains(Role.ASSET_ADMIN)) {
            throw AuthOperationException.forbidden();
        }
        return account.id();
    }
}
