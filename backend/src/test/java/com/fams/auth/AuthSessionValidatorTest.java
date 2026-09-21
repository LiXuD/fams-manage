package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;

class AuthSessionValidatorTest {

    @Test
    void rejectsAStoppedAccountEvenWhenTheSessionEpochMatches() {
        AuthAccount account = account(AccountStatus.DISABLED, 4L, 9L);

        assertFalse(AuthSessionValidator.isCurrent(
                account,
                4L,
                9L,
                Instant.parse("2026-09-21T09:00:00Z"),
                Instant.parse("2026-09-21T01:00:00Z")));
    }

    @Test
    void rejectsAnOldAuthenticationEpoch() {
        AuthAccount account = account(AccountStatus.ENABLED, 5L, 9L);

        assertFalse(AuthSessionValidator.isCurrent(
                account,
                4L,
                9L,
                Instant.parse("2026-09-21T09:00:00Z"),
                Instant.parse("2026-09-21T01:00:00Z")));
    }

    @Test
    void rejectsAnOldAuthorizationVersion() {
        AuthAccount account = account(AccountStatus.ENABLED, 4L, 10L);

        assertFalse(AuthSessionValidator.isCurrent(
                account,
                4L,
                9L,
                Instant.parse("2026-09-21T09:00:00Z"),
                Instant.parse("2026-09-21T01:00:00Z")));
    }

    @Test
    void acceptsAnEnabledAccountWithCurrentVersions() {
        AuthAccount account = account(AccountStatus.ENABLED, 4L, 9L);

        assertTrue(AuthSessionValidator.isCurrent(
                account,
                4L,
                9L,
                Instant.parse("2026-09-21T09:00:00Z"),
                Instant.parse("2026-09-21T01:00:00Z")));
    }

    private static AuthAccount account(AccountStatus status, long authEpoch, long authorizationVersion) {
        return new AuthAccount(
                1L,
                "alice",
                "hash",
                status,
                false,
                0,
                null,
                authEpoch,
                authorizationVersion,
                EnumSet.of(Role.EMPLOYEE));
    }
}
