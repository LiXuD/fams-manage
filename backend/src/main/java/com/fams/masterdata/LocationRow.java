package com.fams.masterdata;

import java.time.Instant;

record LocationRow(long id, String code, String name, String locationType,
        Long parentId, Long parentVersion, String parentStatus,
        Long warehouseId, Long warehouseVersion, String warehouseStatus,
        String description, String status, long version) {
}

final class NewLocationRow {
    private Long id;
    private final String code;
    private final String normalizedCode;
    private final String name;
    private final String locationType;
    private final Long parentId;
    private final Long warehouseId;
    private final String description;
    private final long actorId;
    private final Instant now;

    NewLocationRow(String code, String normalizedCode, String name, String locationType,
            Long parentId, Long warehouseId, String description, long actorId, Instant now) {
        this.code = code;
        this.normalizedCode = normalizedCode;
        this.name = name;
        this.locationType = locationType;
        this.parentId = parentId;
        this.warehouseId = warehouseId;
        this.description = description;
        this.actorId = actorId;
        this.now = now;
    }

    Long getId() { return id; }
    void setId(Long id) { this.id = id; }
    String getCode() { return code; }
    String getNormalizedCode() { return normalizedCode; }
    String getName() { return name; }
    String getLocationType() { return locationType; }
    Long getParentId() { return parentId; }
    Long getWarehouseId() { return warehouseId; }
    String getDescription() { return description; }
    long getActorId() { return actorId; }
    Instant getNow() { return now; }
}
