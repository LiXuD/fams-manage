package com.fams.auth;

import java.time.Instant;

record AuthAccountRow(
        long id,
        String username,
        String passwordHash,
        AccountStatus status,
        boolean initialPasswordRequired,
        int failedAttempts,
        Instant lockedUntil,
        long authEpoch,
        long authorizationVersion) {
}
