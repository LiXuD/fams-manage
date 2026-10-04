package com.fams.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("fams-product")
public final class AttachmentReaderContext {
    public record Reader(long id, Set<Role> roles, long authEpoch, long authorizationVersion, Instant expiresAt) {
        public Reader { roles = Set.copyOf(roles); }
    }
    private final HttpServletRequest request;
    private final AuthAccountStore accounts;
    private final JdbcTemplate db;
    private final Clock clock;
    AttachmentReaderContext(HttpServletRequest request, AuthAccountStore accounts, JdbcTemplate db, Clock clock) {
        this.request = request;
        this.accounts = accounts;
        this.db = db;
        this.clock = clock;
    }
    public Reader requireReaderLocked() {
        var account = AuthRequestContext.account(request);
        var session = AuthRequestContext.session(request);
        Role role = account.roles().contains(Role.WAREHOUSE_OPERATOR) ? Role.WAREHOUSE_OPERATOR
                : account.roles().contains(Role.ASSET_ADMIN) ? Role.ASSET_ADMIN : null;
        if (role == null) { throw AuthOperationException.forbidden(); }
        if (db.queryForList("SELECT id FROM fams_system_account WHERE id=? FOR UPDATE", account.id()).isEmpty()) {
            throw AuthOperationException.sessionInvalid();
        }
        var current = accounts.findById(account.id()).orElseThrow(AuthOperationException::sessionInvalid);
        if (!AuthSessionValidator.isCurrent(current, session.authEpoch(), session.authorizationVersion(),
                session.absoluteExpiresAt(), clock.instant())) { throw AuthOperationException.sessionInvalid(); }
        if (!current.roles().contains(role)) { throw AuthOperationException.forbidden(); }
        return new Reader(current.id(), current.roles(), current.authEpoch(), current.authorizationVersion(),
                session.absoluteExpiresAt());
    }
}
