package com.fams.auth;

import java.time.Instant;

final class NewAuthorizationRow {

    private Long id;
    private final long accountId;
    private final Role role;
    private final long generation;
    private final String status;
    private final Long predecessorId;
    private final long actorAccountId;
    private final String reason;
    private final Instant now;

    NewAuthorizationRow(
            long accountId,
            Role role,
            long generation,
            String status,
            Long predecessorId,
            long actorAccountId,
            String reason,
            Instant now) {
        this.accountId = accountId;
        this.role = role;
        this.generation = generation;
        this.status = status;
        this.predecessorId = predecessorId;
        this.actorAccountId = actorAccountId;
        this.reason = reason;
        this.now = now;
    }

    Long getId() {
        return id;
    }

    void setId(Long id) {
        this.id = id;
    }

    long getAccountId() {
        return accountId;
    }

    Role getRole() {
        return role;
    }

    long getGeneration() {
        return generation;
    }

    String getStatus() {
        return status;
    }

    Long getPredecessorId() {
        return predecessorId;
    }

    long getActorAccountId() {
        return actorAccountId;
    }

    String getReason() {
        return reason;
    }

    Instant getNow() {
        return now;
    }
}
