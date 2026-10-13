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
    public record Actor(long id, long authEpoch, long authorizationVersion, Instant expiresAt,
            UUID requestId, String miniToken) {
        public Actor(long id, long authEpoch, long authorizationVersion, Instant expiresAt) {
            this(id, authEpoch, authorizationVersion, expiresAt, UUID.randomUUID(), null);
        }
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

    public Actor requireWarehouse() {
        var account = AuthRequestContext.account(request);
        if (!account.roles().contains(Role.WAREHOUSE_OPERATOR)) { throw AuthOperationException.forbidden(); }
        var source = identities.capture();
        return new Actor(source.id(), source.authEpoch(), source.authorizationVersion(), source.expiresAt(),
                source.requestId(), source.miniToken());
    }

    public void revalidateLocked(Actor actor) {
        var current = identities.revalidateLocked(new RequisitionActorContext.Actor(actor.id(), actor.authEpoch(),
                actor.authorizationVersion(), actor.expiresAt(), actor.miniToken(), actor.requestId()));
        if (!current.has(Role.WAREHOUSE_OPERATOR)) { throw AuthOperationException.forbidden(); }
    }
}
