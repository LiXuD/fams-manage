package com.fams.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Revalidates the original PC or mini identity after scanning, before business locks. */
@Component
@Profile("fams-product")
public final class AttachmentActorContext {
    /**
     * Attachment actor snapshot: account identity, session authority versions, expiry, request id
     * and the mini token when the caller came from the mini program (null for PC sessions).
     */
    public record Actor(long id, long authEpoch, long authorizationVersion, Instant expiresAt,
            UUID requestId, String miniToken) {
        /**
         * Convenience constructor for tests and PC flows without an explicit request id; a random
         * request id is generated and the mini token stays null.
         *
         * @param id account id
         * @param authEpoch session auth epoch used for revalidation
         * @param authorizationVersion authorization version used for revalidation
         * @param expiresAt absolute session expiry
         */
        public Actor(long id, long authEpoch, long authorizationVersion, Instant expiresAt) {
            this(id, authEpoch, authorizationVersion, expiresAt, UUID.randomUUID(), null);
        }
        /**
         * Convenience constructor for PC flows with an explicit request id; the mini token stays
         * null.
         *
         * @param id account id
         * @param authEpoch session auth epoch used for revalidation
         * @param authorizationVersion authorization version used for revalidation
         * @param expiresAt absolute session expiry
         * @param requestId idempotency request id of the current call
         */
        public Actor(long id, long authEpoch, long authorizationVersion, Instant expiresAt, UUID requestId) {
            this(id, authEpoch, authorizationVersion, expiresAt, requestId, null);
        }
    }
    private final HttpServletRequest request;
    private final RequisitionActorContext identities;

    AttachmentActorContext(HttpServletRequest request, RequisitionActorContext identities) {
        this.request = request;
        this.identities = identities;
    }

    /**
     * Entry gate for warehouse attachment uploads: requires the warehouse-operator role and
     * captures the current PC or mini identity for later revalidation.
     *
     * @return actor snapshot carrying identity, authority versions, request id and mini token
     * @throws AuthOperationException when the account is not a warehouse operator
     */
    public Actor requireWarehouse() {
        var account = AuthRequestContext.account(request);
        if (!account.roles().contains(Role.WAREHOUSE_OPERATOR)) { throw AuthOperationException.forbidden(); }
        var source = identities.capture();
        return new Actor(source.id(), source.authEpoch(), source.authorizationVersion(), source.expiresAt(),
                source.requestId(), source.miniToken());
    }

    /**
     * Revalidates the captured actor with row locks held before business locks are taken, and
     * re-checks that the warehouse-operator role is still effective.
     *
     * @param actor actor snapshot captured by {@link #requireWarehouse()}
     * @throws AuthOperationException when the session is no longer valid or the role was lost
     */
    public void revalidateLocked(Actor actor) {
        var current = identities.revalidateLocked(new RequisitionActorContext.Actor(actor.id(), actor.authEpoch(),
                actor.authorizationVersion(), actor.expiresAt(), actor.miniToken(), actor.requestId()));
        if (!current.has(Role.WAREHOUSE_OPERATOR)) { throw AuthOperationException.forbidden(); }
    }
}
