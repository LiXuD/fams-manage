package com.fams.auth;

import java.time.Instant;
import java.util.Optional;

interface AuthAccountStore {

    Optional<AuthAccount> findByNormalizedUsername(String normalizedUsername);

    Optional<AuthAccount> findById(long accountId);

    AuthAccount recordFailedLogin(long accountId, Instant lockedUntil, Instant updatedAt);

    AuthAccount clearFailedLogins(long accountId, Instant updatedAt);

    AuthAccount changeInitialPassword(
            long accountId,
            long expectedAuthEpoch,
            String passwordHash,
            Instant updatedAt);

    PermissionSnapshot loadPermissions(long accountId);

    Optional<AuthIdempotencyRecord> findIdempotency(
            long accountId,
            String operationId,
            String idempotencyKey);

    void appendIdempotency(AuthIdempotencyRecord record);

    void appendSecurityEvent(SecurityEvent event);
}
