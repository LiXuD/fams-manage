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
