package com.fams.auth;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("fams-product")
@Service
class AuthApplicationService {

    private static final int MAX_FAILED_LOGINS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);
    private static final String INITIAL_PASSWORD_OPERATION = "changeInitialPassword";
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$7EqJtq98hPqEX7fNZaFWoO5hYw5L6p7Vf0RrE9P8DwU1J9Oe4X9Iu";

    private final AuthAccountStore accountStore;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final AuthPayloadFingerprint payloadFingerprint;

    AuthApplicationService(
            AuthAccountStore accountStore,
            PasswordEncoder passwordEncoder,
            Clock clock,
            AuthPayloadFingerprint payloadFingerprint) {
        this.accountStore = accountStore;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.payloadFingerprint = payloadFingerprint;
    }

    @Transactional(noRollbackFor = AuthOperationException.class)
    AuthAccount authenticate(String username, String password, UUID requestId) {
        Instant now = clock.instant();
        String normalizedUsername = normalizeUsername(username);
        Optional<AuthAccount> foundAccount = accountStore.findByNormalizedUsername(normalizedUsername);
        if (foundAccount.isEmpty()) {
            passwordEncoder.matches(password, DUMMY_PASSWORD_HASH);
            appendEvent(null, SecurityEventType.LOGIN_FAILED, requestId, now, "UNKNOWN_ACCOUNT");
            throw AuthOperationException.authenticationFailed();
        }

        AuthAccount account = foundAccount.orElseThrow();
        if (account.status() == AccountStatus.DISABLED) {
            appendEvent(account.id(), SecurityEventType.LOGIN_FAILED, requestId, now, "ACCOUNT_DISABLED");
            throw AuthOperationException.authenticationFailed();
        }
        if (isActivelyLocked(account, now)) {
            appendEvent(account.id(), SecurityEventType.LOGIN_FAILED, requestId, now, "ACCOUNT_LOCKED");
            throw AuthOperationException.authenticationFailed();
        }
        if (!passwordEncoder.matches(password, account.passwordHash())) {
            int failedLoginCount = account.failedLoginCount() + 1;
            Instant lockedUntil = failedLoginCount >= MAX_FAILED_LOGINS ? now.plus(LOCK_DURATION) : null;
            accountStore.recordFailedLogin(account.id(), lockedUntil, now);
            appendEvent(
                    account.id(),
                    SecurityEventType.LOGIN_FAILED,
                    requestId,
                    now,
                    lockedUntil == null ? "WRONG_PASSWORD" : "WRONG_PASSWORD_LOCKED");
            throw AuthOperationException.authenticationFailed();
        }

        AuthAccount authenticated = account;
        if (account.failedLoginCount() > 0 || account.status() == AccountStatus.LOCKED) {
            authenticated = accountStore.clearFailedLogins(account.id(), now);
        }
        appendEvent(account.id(), SecurityEventType.LOGIN_SUCCEEDED, requestId, now, "AUTHENTICATED");
        return authenticated;
    }

    @Transactional(noRollbackFor = AuthOperationException.class)
    AuthAccount changeInitialPassword(
            long accountId,
            String currentPassword,
            String newPassword,
            String idempotencyKey,
            UUID requestId) {
        Instant now = clock.instant();
        String fingerprint = payloadFingerprint.initialPasswordChange(currentPassword, newPassword);
        Optional<AuthIdempotencyRecord> replay = accountStore.findIdempotency(
                accountId,
                INITIAL_PASSWORD_OPERATION,
                idempotencyKey);
        if (replay.isPresent()) {
            AuthIdempotencyRecord recorded = replay.orElseThrow();
            AuthAccount current = requireCurrentAccount(accountId);
            if (!recorded.payloadFingerprint().equals(fingerprint)
                    || recorded.resultAuthEpoch() != current.authEpoch()) {
                throw AuthOperationException.idempotencyConflict();
            }
            return current;
        }
        AuthAccount account = accountStore.findById(accountId)
                .filter(candidate -> candidate.status() == AccountStatus.ENABLED)
                .orElseThrow(AuthOperationException::sessionInvalid);
        if (!account.initialPasswordRequired()) {
            appendEvent(
                    account.id(),
                    SecurityEventType.INITIAL_PASSWORD_CHANGE_REJECTED,
                    requestId,
                    now,
                    "CHANGE_NOT_REQUIRED");
            throw AuthOperationException.initialPasswordChangeNotRequired();
        }
        if (!passwordEncoder.matches(currentPassword, account.passwordHash())) {
            appendEvent(
                    account.id(),
                    SecurityEventType.INITIAL_PASSWORD_CHANGE_REJECTED,
                    requestId,
                    now,
                    "CURRENT_PASSWORD_INVALID");
            throw AuthOperationException.currentPasswordInvalid();
        }
        try {
            PasswordPolicy.validateInitialPassword(newPassword, account.username());
        } catch (AuthValidationException exception) {
            appendEvent(
                    account.id(),
                    SecurityEventType.INITIAL_PASSWORD_CHANGE_REJECTED,
                    requestId,
                    now,
                    "NEW_PASSWORD_POLICY_VIOLATION");
            throw AuthOperationException.passwordPolicyViolation(exception.getMessage());
        }

        AuthAccount changed = accountStore.changeInitialPassword(
                account.id(),
                account.authEpoch(),
                passwordEncoder.encode(newPassword),
                now);
        appendEvent(
                account.id(),
                SecurityEventType.INITIAL_PASSWORD_CHANGED,
                requestId,
                now,
                "AUTH_EPOCH_ADVANCED");
        accountStore.appendIdempotency(new AuthIdempotencyRecord(
                account.id(),
                INITIAL_PASSWORD_OPERATION,
                idempotencyKey,
                fingerprint,
                changed.authEpoch(),
                now,
                now.plus(IDEMPOTENCY_RETENTION)));
        return changed;
    }

    AuthAccount requireCurrentAccount(long accountId) {
        return accountStore.findById(accountId)
                .filter(candidate -> candidate.status() == AccountStatus.ENABLED)
                .orElseThrow(AuthOperationException::sessionInvalid);
    }

    PermissionSnapshot loadPermissions(long accountId) {
        requireCurrentAccount(accountId);
        return accountStore.loadPermissions(accountId);
    }

    @Transactional
    void audit(
            Long accountId,
            SecurityEventType eventType,
            UUID requestId,
            String detail) {
        appendEvent(accountId, eventType, requestId, clock.instant(), detail);
    }

    private boolean isActivelyLocked(AuthAccount account, Instant now) {
        return account.status() == AccountStatus.LOCKED
                && (account.lockedUntil() == null || now.isBefore(account.lockedUntil()));
    }

    private String normalizeUsername(String username) {
        if (username == null) {
            return "";
        }
        return Normalizer.normalize(username.strip(), Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
    }

    private void appendEvent(
            Long accountId,
            SecurityEventType eventType,
            UUID requestId,
            Instant occurredAt,
            String detail) {
        accountStore.appendSecurityEvent(new SecurityEvent(
                accountId,
                eventType,
                requestId,
                occurredAt,
                detail));
    }
}
