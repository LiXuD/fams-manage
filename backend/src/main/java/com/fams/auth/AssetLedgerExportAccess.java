package com.fams.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class AssetLedgerExportAccess {
    private final RequisitionActorContext identities;
    private final AuthAccountStore accounts;

    public AssetLedgerExportAccess(RequisitionActorContext identities, AuthAccountStore accounts) {
        this.identities = identities;
        this.accounts = accounts;
    }

    public void require(RequisitionActorContext.Actor actor) {
        var current = identities.revalidateLocked(actor);
        if (!current.globalApplicant() || !accounts.loadPermissions(current.id()).fieldPermissions().contains("BULK_EXPORT")) {
            throw AuthOperationException.forbidden();
        }
    }
}
