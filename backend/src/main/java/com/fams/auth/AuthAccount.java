package com.fams.auth;

import java.time.Instant;
import java.util.Set;

/**
 * Authenticated account snapshot loaded from the account store. {@code passwordHash} is the
 * stored password digest, never a plaintext credential; {@code authEpoch} and
 * {@code authorizationVersion} are the authority counters session validation compares against,
 * so bumping either invalidates existing sessions.
 */
public record AuthAccount(
        long id,
        String username,
        String passwordHash,
        AccountStatus status,
        boolean initialPasswordRequired,
        int failedLoginCount,
        Instant lockedUntil,
        long authEpoch,
        long authorizationVersion,
        Set<Role> roles) {

    /** Defensively copies the role set so an account snapshot cannot be mutated afterwards. */
    public AuthAccount {
        roles = Set.copyOf(roles);
    }
}
