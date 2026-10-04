package com.fams.auth;

import java.time.Instant;

record SystemAccountAdminRow(
        long id,
        String username,
        long employeeId,
        AccountStatus status,
        long version,
        boolean initialPasswordRequired,
        int failedLoginCount,
        Instant createdAt,
        Instant updatedAt) {
}
