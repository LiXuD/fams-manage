package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-21T02:00:00Z");
    private static final UUID REQUEST_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final String INITIAL_PASSWORD = "correct-horse-battery-staple";

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private InMemoryAuthAccountStore store;
    private AuthApplicationService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryAuthAccountStore(account(
                AccountStatus.ENABLED,
                true,
                0,
                null,
                3L,
                7L,
                passwordEncoder.encode(INITIAL_PASSWORD)));
        service = new AuthApplicationService(
                store,
                passwordEncoder,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new AuthPayloadFingerprint("test-idempotency-hmac-key-0123456789abcdef"));
    }

    @Test
    void authenticatesAnEnabledAccountAndClearsEarlierFailures() {
        store.account = account(
                AccountStatus.ENABLED,
                true,
                2,
                null,
                3L,
                7L,
                passwordEncoder.encode(INITIAL_PASSWORD));

        AuthAccount authenticated = service.authenticate("  Alice  ", INITIAL_PASSWORD, REQUEST_ID);

        assertEquals(1L, authenticated.id());
        assertEquals(0, authenticated.failedLoginCount());
        assertEquals(SecurityEventType.LOGIN_SUCCEEDED, store.events.getLast().eventType());
        assertFalse(store.events.getLast().detail().contains(INITIAL_PASSWORD));
    }

    @Test
    void unknownDisabledLockedAndWrongPasswordUseOneExternalFailure() {
        AuthOperationException unknown = assertThrows(
                AuthOperationException.class,
                () -> service.authenticate("unknown", "wrong-password", REQUEST_ID));

        store.account = account(
                AccountStatus.DISABLED,
                true,
                0,
                null,
                3L,
                7L,
                passwordEncoder.encode(INITIAL_PASSWORD));
        AuthOperationException disabled = assertThrows(
                AuthOperationException.class,
                () -> service.authenticate("alice", INITIAL_PASSWORD, REQUEST_ID));

        store.account = account(
                AccountStatus.LOCKED,
                true,
                5,
                NOW.plusSeconds(300),
                3L,
                7L,
                passwordEncoder.encode(INITIAL_PASSWORD));
        AuthOperationException locked = assertThrows(
                AuthOperationException.class,
                () -> service.authenticate("alice", INITIAL_PASSWORD, REQUEST_ID));

        store.account = account(
                AccountStatus.ENABLED,
                true,
                0,
                null,
                3L,
                7L,
                passwordEncoder.encode(INITIAL_PASSWORD));
        AuthOperationException wrong = assertThrows(
                AuthOperationException.class,
                () -> service.authenticate("alice", "wrong-password", REQUEST_ID));

        assertEquals("AUTHENTICATION_FAILED", unknown.code());
        assertEquals(unknown.code(), disabled.code());
        assertEquals(unknown.code(), locked.code());
        assertEquals(unknown.code(), wrong.code());
        assertEquals(401, unknown.status());
        assertEquals("Authentication failed.", unknown.getMessage());
    }

    @Test
    void fifthFailedPasswordTemporarilyLocksTheAccountAndAuditsInternalReasonOnly() {
        store.account = account(
                AccountStatus.ENABLED,
                true,
                4,
                null,
                3L,
                7L,
                passwordEncoder.encode(INITIAL_PASSWORD));

        assertThrows(
                AuthOperationException.class,
                () -> service.authenticate("alice", "wrong-password", REQUEST_ID));

        assertEquals(AccountStatus.LOCKED, store.account.status());
        assertEquals(5, store.account.failedLoginCount());
        assertEquals(NOW.plusSeconds(900), store.account.lockedUntil());
        SecurityEvent event = store.events.getLast();
        assertEquals(SecurityEventType.LOGIN_FAILED, event.eventType());
        assertEquals("WRONG_PASSWORD_LOCKED", event.detail());
        assertFalse(event.detail().contains("wrong-password"));
    }

    @Test
    void initialPasswordChangeRehashesPasswordAndAdvancesAuthenticationEpoch() {
        AuthAccount changed = service.changeInitialPassword(
                1L,
                INITIAL_PASSWORD,
                "new-password-value-2026",
                "password-change-key-0001",
                REQUEST_ID);

        assertFalse(changed.initialPasswordRequired());
        assertEquals(4L, changed.authEpoch());
        assertTrue(passwordEncoder.matches("new-password-value-2026", changed.passwordHash()));
        assertFalse(passwordEncoder.matches(INITIAL_PASSWORD, changed.passwordHash()));
        assertEquals(SecurityEventType.INITIAL_PASSWORD_CHANGED, store.events.getLast().eventType());
    }

    @Test
    void initialPasswordChangeRejectsWrongCurrentPasswordWithoutChangingTheEpoch() {
        AuthOperationException failure = assertThrows(
                AuthOperationException.class,
                () -> service.changeInitialPassword(
                        1L,
                        "wrong-current-password",
                        "new-password-value-2026",
                        "password-change-key-0001",
                        REQUEST_ID));

        assertEquals("AUTH_CURRENT_PASSWORD_INVALID", failure.code());
        assertEquals(3L, store.account.authEpoch());
        assertTrue(store.account.initialPasswordRequired());
    }

    @Test
    void initialPasswordChangeRejectsRepeatAfterTheRequirementIsComplete() {
        store.account = account(
                AccountStatus.ENABLED,
                false,
                0,
                null,
                3L,
                7L,
                passwordEncoder.encode(INITIAL_PASSWORD));

        AuthOperationException failure = assertThrows(
                AuthOperationException.class,
                () -> service.changeInitialPassword(
                        1L,
                        INITIAL_PASSWORD,
                        "new-password-value-2026",
                        "password-change-key-0001",
                        REQUEST_ID));

        assertEquals("AUTH_INITIAL_PASSWORD_CHANGE_NOT_REQUIRED", failure.code());
    }

    @Test
    void replaysTheSameInitialPasswordChangeAndRejectsKeyReuseWithADifferentPayload() {
        AuthAccount first = service.changeInitialPassword(
                1L,
                INITIAL_PASSWORD,
                "new-password-value-2026",
                "password-change-key-0001",
                REQUEST_ID);

        AuthAccount replay = service.changeInitialPassword(
                1L,
                INITIAL_PASSWORD,
                "new-password-value-2026",
                "password-change-key-0001",
                REQUEST_ID);
        AuthOperationException conflict = assertThrows(
                AuthOperationException.class,
                () -> service.changeInitialPassword(
                        1L,
                        INITIAL_PASSWORD,
                        "different-password-2026",
                        "password-change-key-0001",
                        REQUEST_ID));

        assertEquals(first.authEpoch(), replay.authEpoch());
        assertEquals(4L, replay.authEpoch());
        assertEquals("AUTH_IDEMPOTENCY_CONFLICT", conflict.code());
    }

    private static AuthAccount account(
            AccountStatus status,
            boolean initialPasswordRequired,
            int failedLoginCount,
            Instant lockedUntil,
            long authEpoch,
            long authorizationVersion,
            String passwordHash) {
        return new AuthAccount(
                1L,
                "alice",
                passwordHash,
                status,
                initialPasswordRequired,
                failedLoginCount,
                lockedUntil,
                authEpoch,
                authorizationVersion,
                EnumSet.of(Role.SYSTEM_ADMIN));
    }

    private static final class InMemoryAuthAccountStore implements AuthAccountStore {

        private AuthAccount account;
        private final List<SecurityEvent> events = new ArrayList<>();
        private final Map<String, AuthIdempotencyRecord> idempotencyRecords = new HashMap<>();

        private InMemoryAuthAccountStore(AuthAccount account) {
            this.account = account;
        }

        @Override
        public Optional<AuthAccount> findByNormalizedUsername(String normalizedUsername) {
            return account.username().toUpperCase().equals(normalizedUsername)
                    ? Optional.of(account)
                    : Optional.empty();
        }

        @Override
        public Optional<AuthAccount> findById(long accountId) {
            return account.id() == accountId ? Optional.of(account) : Optional.empty();
        }

        @Override
        public AuthAccount recordFailedLogin(long accountId, Instant lockedUntil, Instant updatedAt) {
            int failures = account.failedLoginCount() + 1;
            account = account(
                    lockedUntil == null ? account.status() : AccountStatus.LOCKED,
                    account.initialPasswordRequired(),
                    failures,
                    lockedUntil,
                    account.authEpoch(),
                    account.authorizationVersion(),
                    account.passwordHash());
            return account;
        }

        @Override
        public AuthAccount clearFailedLogins(long accountId, Instant updatedAt) {
            account = account(
                    account.status(),
                    account.initialPasswordRequired(),
                    0,
                    null,
                    account.authEpoch(),
                    account.authorizationVersion(),
                    account.passwordHash());
            return account;
        }

        @Override
        public AuthAccount changeInitialPassword(
                long accountId,
                long expectedAuthEpoch,
                String passwordHash,
                Instant updatedAt) {
            if (account.authEpoch() != expectedAuthEpoch) {
                throw new IllegalStateException("stale authentication epoch");
            }
            account = account(
                    account.status(),
                    false,
                    0,
                    null,
                    account.authEpoch() + 1,
                    account.authorizationVersion(),
                    passwordHash);
            return account;
        }

        @Override
        public PermissionSnapshot loadPermissions(long accountId) {
            return AuthPolicy.snapshot(
                    Long.toString(account.id()),
                    account.roles(),
                    account.authorizationVersion(),
                    account.authEpoch());
        }

        @Override
        public Optional<AuthIdempotencyRecord> findIdempotency(
                long accountId,
                String operationId,
                String idempotencyKey) {
            return Optional.ofNullable(idempotencyRecords.get(operationId + ":" + idempotencyKey));
        }

        @Override
        public void appendIdempotency(AuthIdempotencyRecord record) {
            idempotencyRecords.put(record.operationId() + ":" + record.idempotencyKey(), record);
        }

        @Override
        public void appendSecurityEvent(SecurityEvent event) {
            events.add(event);
        }
    }
}
