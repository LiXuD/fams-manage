package com.fams.auth;

import java.time.Clock;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** SYSTEM_ADMIN reads by role; AUDIT_READ_ONLY additionally needs SYSTEM_OPERATION_LOG. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class OperationLogAccess {

    private final HttpServletRequest request;
    private final AuthAccountStore accounts;
    private final JdbcTemplate db;
    private final Clock clock;

    OperationLogAccess(HttpServletRequest request, AuthAccountStore accounts,
            JdbcTemplate db, Clock authClock) {
        this.request = request;
        this.accounts = accounts;
        this.db = db;
        this.clock = authClock;
    }

    /**
     * Revalidates the captured session against the account row locked FOR UPDATE, rejecting
     * stale authority versions and accounts that still owe an initial password change, then
     * applies the role rules of this gate.
     *
     * @return the revalidated account id
     * @throws AuthOperationException with a session-invalid failure when the account vanished,
     *         the session is no longer current or the initial password is still required, and
     *         with a forbidden failure when the account is neither a system admin nor an audit
     *         reader holding the {@code SYSTEM_OPERATION_LOG} field permission
     */
    public long require() {
        AuthAccount captured = AuthRequestContext.account(request);
        AuthSession session = AuthRequestContext.session(request);
        if (db.queryForList("SELECT id FROM fams_system_account WHERE id=? FOR UPDATE",
                captured.id()).isEmpty()) {
            throw AuthOperationException.sessionInvalid();
        }
        AuthAccount current = accounts.findById(captured.id())
                .orElseThrow(AuthOperationException::sessionInvalid);
        if (!AuthSessionValidator.isCurrent(current, session.authEpoch(),
                session.authorizationVersion(), session.absoluteExpiresAt(), clock.instant())
                || current.initialPasswordRequired()) {
            throw AuthOperationException.sessionInvalid();
        }
        if (current.roles().contains(Role.SYSTEM_ADMIN)) {
            return current.id();
        }
        if (!current.roles().contains(Role.AUDIT_READ_ONLY)) {
            throw AuthOperationException.forbidden();
        }
        if (!accounts.loadPermissions(current.id()).fieldPermissions()
                .contains("SYSTEM_OPERATION_LOG")) {
            throw AuthOperationException.forbidden();
        }
        return current.id();
    }
}
