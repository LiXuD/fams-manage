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
