package com.fams.auth;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("fams-product")
@Component
public final class MasterDataActorContext {

    private final HttpServletRequest request;

    MasterDataActorContext(HttpServletRequest request) {
        this.request = request;
    }

    public long requireSystemAdministrator() {
        AuthAccount account = AuthRequestContext.account(request);
        if (!account.roles().contains(Role.SYSTEM_ADMIN)) {
            throw AuthOperationException.forbidden();
        }
        return account.id();
    }

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
