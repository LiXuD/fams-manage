package com.fams.auth;

import java.time.Clock;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Revalidates permissions after potentially slow scanning, before business locks. */
@Component
@Profile("fams-product")
public final class AttachmentActorContext {
    public record Actor(long id, long authEpoch, long authorizationVersion, Instant expiresAt) { }
    private final HttpServletRequest request;
    private final AuthAccountStore accounts;
    private final JdbcTemplate db;
    private final Clock clock;

    AttachmentActorContext(HttpServletRequest request, AuthAccountStore accounts, JdbcTemplate db, Clock clock) {
        this.request = request; this.accounts = accounts; this.db = db; this.clock = clock;
    }

    public Actor requireWarehouse() {
        var account = AuthRequestContext.account(request);
        if (!account.roles().contains(Role.WAREHOUSE_OPERATOR)) { throw AuthOperationException.forbidden(); }
        var session = AuthRequestContext.session(request);
        return new Actor(account.id(), session.authEpoch(), session.authorizationVersion(), session.absoluteExpiresAt());
    }

    public void revalidateLocked(Actor actor) {
        // Match inbound mutations' account-before-receipt lock order.
        if (db.queryForList("SELECT id FROM fams_system_account WHERE id=? FOR UPDATE", actor.id()).isEmpty()) {
            throw AuthOperationException.sessionInvalid();
        }
        var current = accounts.findById(actor.id()).orElseThrow(AuthOperationException::sessionInvalid);
        if (!AuthSessionValidator.isCurrent(current, actor.authEpoch(), actor.authorizationVersion(),
                actor.expiresAt(), clock.instant())) { throw AuthOperationException.sessionInvalid(); }
        if (!current.roles().contains(Role.WAREHOUSE_OPERATOR)) { throw AuthOperationException.forbidden(); }
    }
}
