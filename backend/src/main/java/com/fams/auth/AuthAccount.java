package com.fams.auth;

import java.time.Instant;
import java.util.Set;

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

    public AuthAccount {
        roles = Set.copyOf(roles);
    }
}
