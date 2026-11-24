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

    /**
     * Wires the gate to the shared identity context and permission store.
     *
     * @param identities locked identity revalidation source
     * @param accounts permission store used for the field-permission check
     */
    public ScrapSensitiveAccess(RequisitionActorContext identities, AuthAccountStore accounts) {
        this.identities = identities;
        this.accounts = accounts;
    }

    /**
     * Revalidates the actor with locks held and checks the {@code SCRAP_ATTACHMENT} field
     * permission on the fresh permission snapshot.
     *
     * @param actor actor captured earlier in the same transaction
     * @return true when the revalidated identity holds the sensitive-scrap field permission
     * @throws AuthOperationException when the locked revalidation itself fails
     */
    public boolean mayRead(RequisitionActorContext.Actor actor) {
        var current = identities.revalidateLocked(actor);
        return accounts.loadPermissions(current.id()).fieldPermissions().contains("SCRAP_ATTACHMENT");
    }

    /**
     * Hard variant of {@link #mayRead}: fails the request when the field permission is missing.
     *
     * @param actor actor captured earlier in the same transaction
     * @throws AuthOperationException when revalidation fails or the identity lacks the
     *         {@code SCRAP_ATTACHMENT} field permission
     */
    public void requireRead(RequisitionActorContext.Actor actor) {
        if (!mayRead(actor)) {
            throw AuthOperationException.forbidden();
        }
    }
}
