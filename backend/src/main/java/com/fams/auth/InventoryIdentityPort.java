package com.fams.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Exposes current binding identity without leaking opaque credentials to the inventory domain. */
@Component
@Profile("fams-product")
public final class InventoryIdentityPort {
    private final MiniSessionService sessions;

    InventoryIdentityPort(MiniSessionService sessions) {
        this.sessions = sessions;
    }

    /**
     * Resolves the mini binding id behind the actor's mini token, so the inventory domain can
     * scope data by binding without seeing the token itself.
     *
     * @param actor authenticated actor of the current request
     * @return the binding id, or null when the actor authenticated without a mini token
     * @throws AuthOperationException when the token has no current session or the session belongs
     *         to a different account than the actor
     */
    public Long bindingId(RequisitionActorContext.Actor actor) {
        if (actor.miniToken() == null) {
            return null;
        }
        var current = sessions.requireCurrent(actor.miniToken());
        if (current.account().id() != actor.id()) {
            throw AuthOperationException.sessionInvalid();
        }
        return Long.valueOf(current.bindingId());
    }
}
