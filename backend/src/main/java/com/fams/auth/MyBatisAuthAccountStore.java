package com.fams.auth;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Profile("fams-product")
@Repository
class MyBatisAuthAccountStore implements AuthAccountStore {

    private final AuthAccountMapper mapper;

    MyBatisAuthAccountStore(AuthAccountMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<AuthAccount> findByNormalizedUsername(String normalizedUsername) {
        return Optional.ofNullable(mapper.findByNormalizedUsername(normalizedUsername)).map(this::toAccount);
    }

    @Override
    public Optional<AuthAccount> findById(long accountId) {
        return Optional.ofNullable(mapper.findById(accountId)).map(this::toAccount);
    }

    @Override
    public AuthAccount recordFailedLogin(long accountId, Instant lockedUntil, Instant updatedAt) {
        requireOneRow(mapper.recordFailedLogin(accountId, lockedUntil, updatedAt));
        return findRequired(accountId);
    }

    @Override
    public AuthAccount clearFailedLogins(long accountId, Instant updatedAt) {
        requireOneRow(mapper.clearFailedLogins(accountId, updatedAt));
        return findRequired(accountId);
    }

    @Override
    public AuthAccount changeInitialPassword(
            long accountId,
            long expectedAuthEpoch,
            String passwordHash,
            Instant updatedAt) {
        requireOneRow(mapper.changeInitialPassword(accountId, expectedAuthEpoch, passwordHash, updatedAt));
        return findRequired(accountId);
    }

    @Override
    public PermissionSnapshot loadPermissions(long accountId) {
        AuthAccount account = findRequired(accountId);
        return AuthPolicy.snapshot(
                Long.toString(accountId),
                account.roles(),
                account.authorizationVersion(),
                account.authEpoch());
    }

    @Override
    public Optional<AuthIdempotencyRecord> findIdempotency(
            long accountId,
            String operationId,
            String idempotencyKey) {
        return Optional.ofNullable(mapper.findIdempotency(accountId, operationId, idempotencyKey));
    }

    @Override
    public void appendIdempotency(AuthIdempotencyRecord record) {
        requireOneRow(mapper.insertIdempotency(record));
    }

    @Override
    public void appendSecurityEvent(SecurityEvent event) {
        mapper.insertSecurityEvent(
                event.accountId(),
                event.eventType().name(),
                event.requestId().toString(),
                event.occurredAt(),
                "{\"reason\":\"" + event.detail() + "\"}");
    }

    private AuthAccount findRequired(long accountId) {
        return findById(accountId).orElseThrow(AuthOperationException::sessionInvalid);
    }

    private AuthAccount toAccount(AuthAccountRow row) {
        List<Role> roleRows = mapper.findRoles(row.id());
        Set<Role> roles = roleRows.isEmpty()
                ? EnumSet.noneOf(Role.class)
                : EnumSet.copyOf(roleRows);
        return new AuthAccount(
                row.id(),
                row.username(),
                row.passwordHash(),
                row.status(),
                row.initialPasswordRequired(),
                row.failedAttempts(),
                row.lockedUntil(),
                row.authEpoch(),
                row.authorizationVersion(),
                roles);
    }

    private void requireOneRow(int affectedRows) {
        if (affectedRows != 1) {
            throw new IllegalStateException("Authentication state changed concurrently.");
        }
    }
}
