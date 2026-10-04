package com.fams.masterdata;

import java.time.Instant;

record BrandRow(
        long id,
        String code,
        String name,
        String description,
        Long defaultManufacturerId,
        Long defaultManufacturerVersion,
        String defaultManufacturerStatus,
        String status,
        long version) {
}

final class NewBrandRow {

    private Long id;
    private final String code;
    private final String normalizedCode;
    private final String name;
    private final String description;
    private final Long defaultManufacturerId;
    private final long actorId;
    private final Instant now;

    NewBrandRow(
            String code,
            String normalizedCode,
            String name,
            String description,
            Long defaultManufacturerId,
            long actorId,
            Instant now) {
        this.code = code;
        this.normalizedCode = normalizedCode;
        this.name = name;
        this.description = description;
        this.defaultManufacturerId = defaultManufacturerId;
        this.actorId = actorId;
        this.now = now;
    }

    Long getId() { return id; }
    void setId(Long id) { this.id = id; }
    String getCode() { return code; }
    String getNormalizedCode() { return normalizedCode; }
    String getName() { return name; }
    String getDescription() { return description; }
    Long getDefaultManufacturerId() { return defaultManufacturerId; }
    long getActorId() { return actorId; }
    Instant getNow() { return now; }
}
