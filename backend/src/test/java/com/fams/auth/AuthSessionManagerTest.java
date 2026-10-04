package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthSessionManagerTest {

    private static final Instant NOW = Instant.parse("2026-09-21T02:00:00Z");

    private AuthSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        sessionManager = new AuthSessionManager(
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "csrf-token-value-1234567890");
    }

    @Test
    void loginInvalidatesAnExistingSessionAndIssuesBoundedSessionMetadata() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("old", "value");
        String oldSessionId = request.getSession().getId();

        AuthSession session = sessionManager.start(request, account(3L, 7L, true));

        assertNotNull(request.getSession(false));
        assertNotEquals(oldSessionId, request.getSession(false).getId());
        assertEquals(1800, request.getSession(false).getMaxInactiveInterval());
        assertEquals(NOW.plusSeconds(1800), session.idleExpiresAt());
        assertEquals(NOW.plusSeconds(8 * 60 * 60), session.absoluteExpiresAt());
        assertEquals("csrf-token-value-1234567890", session.csrfToken());
        assertTrue(session.requiresInitialPasswordChange());
    }

    @Test
    void rejectsAStaleAuthenticationEpochOnTheNextRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionManager.start(request, account(3L, 7L, true));

        AuthOperationException failure = assertThrows(
                AuthOperationException.class,
                () -> sessionManager.requireCurrent(request, ignored -> account(4L, 7L, false)));

        assertEquals("AUTH_SESSION_INVALID", failure.code());
        assertNull(request.getSession(false));
    }

    @Test
    void rejectsAStaleAuthorizationVersionOnTheNextRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionManager.start(request, account(3L, 7L, false));

        AuthOperationException failure = assertThrows(
                AuthOperationException.class,
                () -> sessionManager.requireCurrent(request, ignored -> account(3L, 8L, false)));

        assertEquals("AUTH_SESSION_INVALID", failure.code());
    }

    @Test
    void requiresTheSessionCsrfTokenForWrites() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionManager.start(request, account(3L, 7L, false));

        assertThrows(AuthOperationException.class, () -> sessionManager.requireCsrf(request, null));
        assertThrows(AuthOperationException.class, () -> sessionManager.requireCsrf(request, "wrong-token-value"));
        sessionManager.requireCsrf(request, "csrf-token-value-1234567890");
    }

    @Test
    void passwordChangeCreatesANewSessionBoundToTheAdvancedEpoch() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionManager.start(request, account(3L, 7L, true));
        String oldSessionId = request.getSession(false).getId();

        AuthSession rotated = sessionManager.start(request, account(4L, 7L, false));

        assertNotEquals(oldSessionId, request.getSession(false).getId());
        assertEquals(4L, rotated.authEpoch());
        assertTrue(!rotated.requiresInitialPasswordChange());
    }

    private static AuthAccount account(long authEpoch, long authorizationVersion, boolean initialPasswordRequired) {
        return new AuthAccount(
                1L,
                "alice",
                "hash",
                AccountStatus.ENABLED,
                initialPasswordRequired,
                0,
                null,
                authEpoch,
                authorizationVersion,
                EnumSet.of(Role.SYSTEM_ADMIN));
    }
}
