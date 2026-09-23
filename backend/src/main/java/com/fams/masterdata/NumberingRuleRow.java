package com.fams.masterdata;

import java.time.Instant;

record NumberingRuleRow(long id, String code, String target, String mode,
        String fixedPrefix, String datePart, int sequenceLength, String status, long version) {
}

final class NewNumberingRuleRow {
    private Long id;
    private final String code;
    private final String normalizedCode;
    private final String target;
    private final String mode;
    private final String fixedPrefix;
    private final String datePart;
    private final int sequenceLength;
    private final long actorId;
    private final Instant now;

    NewNumberingRuleRow(String code, String normalizedCode, String target, String mode,
            String fixedPrefix, String datePart, int sequenceLength, long actorId, Instant now) {
        this.code = code;
        this.normalizedCode = normalizedCode;
        this.target = target;
        this.mode = mode;
        this.fixedPrefix = fixedPrefix;
        this.datePart = datePart;
        this.sequenceLength = sequenceLength;
        this.actorId = actorId;
        this.now = now;
    }

    Long getId() { return id; }
    void setId(Long id) { this.id = id; }
    String getCode() { return code; }
    String getNormalizedCode() { return normalizedCode; }
    String getTarget() { return target; }
    String getMode() { return mode; }
    String getFixedPrefix() { return fixedPrefix; }
    String getDatePart() { return datePart; }
    int getSequenceLength() { return sequenceLength; }
    long getActorId() { return actorId; }
    Instant getNow() { return now; }
}
