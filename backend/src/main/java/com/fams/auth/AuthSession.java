package com.fams.auth;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

record AuthSession(
        long accountId,
        long authEpoch,
        long authorizationVersion,
        String csrfToken,
        Instant idleExpiresAt,
        Instant absoluteExpiresAt,
        boolean requiresInitialPasswordChange) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
