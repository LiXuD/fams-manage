package com.fams.masterdata;

import java.time.Instant;

record ManufacturerRow(
        long id,
        String code,
        String name,
        String description,
        String status,
        long version) {
}

final class NewManufacturerRow {

    private Long id;
    private final String code;
    private final String normalizedCode;
    private final String name;
    private final String description;
    private final long actorId;
    private final Instant now;

    NewManufacturerRow(
            String code,
            String normalizedCode,
            String name,
            String description,
            long actorId,
            Instant now) {
        this.code = code;
        this.normalizedCode = normalizedCode;
        this.name = name;
        this.description = description;
        this.actorId = actorId;
        this.now = now;
    }

    Long getId() { return id; }
    void setId(Long id) { this.id = id; }
    String getCode() { return code; }
    String getNormalizedCode() { return normalizedCode; }
    String getName() { return name; }
    String getDescription() { return description; }
    long getActorId() { return actorId; }
    Instant getNow() { return now; }
}
