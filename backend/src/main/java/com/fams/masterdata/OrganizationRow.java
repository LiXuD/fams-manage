package com.fams.masterdata;

import java.time.Instant;

record OrganizationRow(
        long id,
        String code,
        String name,
        Long parentId,
        Long parentVersion,
        String status,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}

record EmployeeRow(
        long id,
        String employeeNo,
        String name,
        long organizationId,
        long organizationVersion,
        String status,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}

record IdempotencyRow(String payloadSha256, String responseBodyJson) {
}

final class NewOrganizationRow {

    private Long id;
    private final String code;
    private final String normalizedCode;
    private final String name;
    private final Long parentId;
    private final long actorId;
    private final Instant now;

    NewOrganizationRow(
            String code,
            String normalizedCode,
            String name,
            Long parentId,
            long actorId,
            Instant now) {
        this.code = code;
        this.normalizedCode = normalizedCode;
        this.name = name;
        this.parentId = parentId;
        this.actorId = actorId;
        this.now = now;
    }

    Long getId() {
        return id;
    }

    void setId(Long id) {
        this.id = id;
    }

    String getCode() {
        return code;
    }

    String getNormalizedCode() {
        return normalizedCode;
    }

    String getName() {
        return name;
    }

    Long getParentId() {
        return parentId;
    }

    long getActorId() {
        return actorId;
    }

    Instant getNow() {
        return now;
    }
}

final class NewEmployeeRow {

    private Long id;
    private final String employeeNo;
    private final String normalizedEmployeeNo;
    private final String name;
    private final long organizationId;
    private final byte[] phoneCiphertext;
    private final byte[] phoneLookupHash;
    private final long actorId;
    private final Instant now;

    NewEmployeeRow(
            String employeeNo,
            String normalizedEmployeeNo,
            String name,
            long organizationId,
            byte[] phoneCiphertext,
            byte[] phoneLookupHash,
            long actorId,
            Instant now) {
        this.employeeNo = employeeNo;
        this.normalizedEmployeeNo = normalizedEmployeeNo;
        this.name = name;
        this.organizationId = organizationId;
        this.phoneCiphertext = phoneCiphertext == null ? null : phoneCiphertext.clone();
        this.phoneLookupHash = phoneLookupHash == null ? null : phoneLookupHash.clone();
        this.actorId = actorId;
        this.now = now;
    }

    Long getId() {
        return id;
    }

    void setId(Long id) {
        this.id = id;
    }

    String getEmployeeNo() {
        return employeeNo;
    }

    String getNormalizedEmployeeNo() {
        return normalizedEmployeeNo;
    }

    String getName() {
        return name;
    }

    long getOrganizationId() {
        return organizationId;
    }

    byte[] getPhoneCiphertext() {
        return phoneCiphertext == null ? null : phoneCiphertext.clone();
    }

    byte[] getPhoneLookupHash() {
        return phoneLookupHash == null ? null : phoneLookupHash.clone();
    }

    long getActorId() {
        return actorId;
    }

    Instant getNow() {
        return now;
    }
}
