package com.fams.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Access gate for asset ledger bulk export. Runs inside the caller's transaction
 * ({@code Propagation.MANDATORY}) so the identity revalidation locks are held together with the
 * export itself.
 */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class AssetLedgerExportAccess {
    private final RequisitionActorContext identities;
    private final AuthAccountStore accounts;

    /**
     * Wires the gate to the shared identity context and permission store.
     *
     * @param identities actor capture and locked revalidation source
     * @param accounts permission store used for the field-permission check
     */
    public AssetLedgerExportAccess(RequisitionActorContext identities, AuthAccountStore accounts) {
        this.identities = identities;
        this.accounts = accounts;
    }

    /**
     * Requires that the actor is still valid with row locks held, has global applicant scope and
     * holds the {@code BULK_EXPORT} field permission.
     *
     * @param actor actor captured earlier in the same transaction
     * @throws AuthOperationException when revalidation fails or either export condition is unmet
     */
    public void require(RequisitionActorContext.Actor actor) {
        var current = identities.revalidateLocked(actor);
        if (!current.globalApplicant() || !accounts.loadPermissions(current.id()).fieldPermissions().contains("BULK_EXPORT")) {
            throw AuthOperationException.forbidden();
        }
    }
}
