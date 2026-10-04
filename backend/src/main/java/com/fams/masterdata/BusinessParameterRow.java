package com.fams.masterdata;

import java.time.Instant;

record BusinessParameterRow(long id, long version, String status, Instant effectiveAt,
        long actorAccountId, String actorUsername, String snapshotJson) {
}

record BusinessParameterState(long currentVersion, Long currentVersionId) {
}

final class NewBusinessParameterRow {
    private Long id;
    private final long version;
    private final Instant effectiveAt;
    private final long actorAccountId;
    private final String actorUsername;
    private final String snapshotJson;

    NewBusinessParameterRow(long version, Instant effectiveAt, long actorAccountId,
            String actorUsername, String snapshotJson) {
        this.version = version;
        this.effectiveAt = effectiveAt;
        this.actorAccountId = actorAccountId;
        this.actorUsername = actorUsername;
        this.snapshotJson = snapshotJson;
    }

    Long getId() { return id; }
    void setId(Long id) { this.id = id; }
    long getVersion() { return version; }
    Instant getEffectiveAt() { return effectiveAt; }
    long getActorAccountId() { return actorAccountId; }
    String getActorUsername() { return actorUsername; }
    String getSnapshotJson() { return snapshotJson; }
}
