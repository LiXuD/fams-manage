package com.fams.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Field permission is additional to the scrap owner's current record-level authorization. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class ScrapSensitiveAccess {
    private final RequisitionActorContext identities;
    private final AuthAccountStore accounts;

    public ScrapSensitiveAccess(RequisitionActorContext identities, AuthAccountStore accounts) {
        this.identities = identities;
        this.accounts = accounts;
    }

    public boolean mayRead(RequisitionActorContext.Actor actor) {
        var current = identities.revalidateLocked(actor);
        return accounts.loadPermissions(current.id()).fieldPermissions().contains("SCRAP_ATTACHMENT");
    }

    public void requireRead(RequisitionActorContext.Actor actor) {
        if (!mayRead(actor)) {
            throw AuthOperationException.forbidden();
        }
    }
}
