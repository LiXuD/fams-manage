package com.fams.auth;

import java.time.Instant;

record AuthorizationAdminRow(
        long id,
        long accountId,
        Role role,
        String status,
        long generation,
        Instant createdAt) {
}
