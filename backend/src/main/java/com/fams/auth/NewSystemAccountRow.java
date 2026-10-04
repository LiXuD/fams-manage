package com.fams.auth;

import java.time.Instant;

final class NewSystemAccountRow {

    private Long id;
    private final long employeeId;
    private final String username;
    private final String normalizedUsername;
    private final String passwordHash;
    private final long actorAccountId;
    private final Instant now;

    NewSystemAccountRow(
            long employeeId,
            String username,
            String normalizedUsername,
            String passwordHash,
            long actorAccountId,
            Instant now) {
        this.employeeId = employeeId;
        this.username = username;
        this.normalizedUsername = normalizedUsername;
        this.passwordHash = passwordHash;
        this.actorAccountId = actorAccountId;
        this.now = now;
    }

    Long getId() {
        return id;
    }

    void setId(Long id) {
        this.id = id;
    }

    long getEmployeeId() {
        return employeeId;
    }

    String getUsername() {
        return username;
    }

    String getNormalizedUsername() {
        return normalizedUsername;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    long getActorAccountId() {
        return actorAccountId;
    }

    Instant getNow() {
        return now;
    }
}
