package com.fams.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

final class AuthSessionManager {

    static final String SESSION_ATTRIBUTE = "fams.auth.session";
    static final int IDLE_TIMEOUT_SECONDS = 30 * 60;
    private static final Duration ABSOLUTE_TIMEOUT = Duration.ofHours(8);

    private final Clock clock;
    private final Supplier<String> csrfTokenSupplier;

    AuthSessionManager(Clock clock, Supplier<String> csrfTokenSupplier) {
        this.clock = clock;
        this.csrfTokenSupplier = csrfTokenSupplier;
    }

    AuthSession start(HttpServletRequest request, AuthAccount account) {
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setMaxInactiveInterval(IDLE_TIMEOUT_SECONDS);
        Instant now = clock.instant();
        AuthSession authSession = new AuthSession(
                account.id(),
                account.authEpoch(),
                account.authorizationVersion(),
                csrfTokenSupplier.get(),
                now.plusSeconds(IDLE_TIMEOUT_SECONDS),
                now.plus(ABSOLUTE_TIMEOUT),
                account.initialPasswordRequired());
        session.setAttribute(SESSION_ATTRIBUTE, authSession);
        return authSession;
    }

    AuthSession requireCurrent(
            HttpServletRequest request,
            Function<Long, AuthAccount> accountLoader) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw AuthOperationException.sessionInvalid();
        }
        Object value = session.getAttribute(SESSION_ATTRIBUTE);
        if (!(value instanceof AuthSession stored)) {
            invalidate(session);
            throw AuthOperationException.sessionInvalid();
        }
        AuthAccount account;
        try {
            account = accountLoader.apply(stored.accountId());
        } catch (AuthOperationException exception) {
            invalidate(session);
            throw exception;
        }
        Instant now = clock.instant();
        if (!AuthSessionValidator.isCurrent(
                account,
                stored.authEpoch(),
                stored.authorizationVersion(),
                stored.absoluteExpiresAt(),
                now)) {
            invalidate(session);
            throw AuthOperationException.sessionInvalid();
        }
        AuthSession current = new AuthSession(
                stored.accountId(),
                stored.authEpoch(),
                stored.authorizationVersion(),
                stored.csrfToken(),
                now.plusSeconds(IDLE_TIMEOUT_SECONDS),
                stored.absoluteExpiresAt(),
                account.initialPasswordRequired());
        session.setAttribute(SESSION_ATTRIBUTE, current);
        return current;
    }

    void requireCsrf(HttpServletRequest request, String actualToken) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw AuthOperationException.sessionInvalid();
        }
        Object value = session.getAttribute(SESSION_ATTRIBUTE);
        if (!(value instanceof AuthSession authSession)) {
            throw AuthOperationException.sessionInvalid();
        }
        if (!CsrfTokenValidator.matches(authSession.csrfToken(), actualToken)) {
            throw AuthOperationException.csrfInvalid();
        }
    }

    void invalidate(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            invalidate(session);
        }
    }

    private void invalidate(HttpSession session) {
        try {
            session.invalidate();
        } catch (IllegalStateException ignored) {
            // An already invalidated session satisfies the requested outcome.
        }
    }
}
