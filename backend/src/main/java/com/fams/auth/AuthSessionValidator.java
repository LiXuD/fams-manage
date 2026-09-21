package com.fams.auth;

import java.time.Instant;

final class AuthSessionValidator {

    private AuthSessionValidator() {
    }

    static boolean isCurrent(
            AuthAccount account,
            long sessionAuthEpoch,
            long sessionAuthorizationVersion,
            Instant sessionAbsoluteExpiresAt,
            Instant now) {
        return account.status() == AccountStatus.ENABLED
                && account.authEpoch() == sessionAuthEpoch
                && account.authorizationVersion() == sessionAuthorizationVersion
                && sessionAbsoluteExpiresAt != null
                && now.isBefore(sessionAbsoluteExpiresAt);
    }
}
