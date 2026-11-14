package com.fams.business;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Publishes an approved opening repair fact without synthesizing a historical employee application. */
public interface InitialRepairPort {
    record Factory(int roundNumber, String provider, String contact, String phone, String destination, Instant dispatchedAt,
            String dispatchEvidence, LocalDate due, String costPolicy, BigDecimal costLimit) {}
    record Facts(String stage, String fault, String impact, Instant occurredAt, Instant acceptedAt, Long physicalLocation,
            String evidence, Factory factory) {}
    record Opening(long assetId, String serialNumber, Facts facts,
            UUID batch, int row, long operatorId, String operatorUsername, long administratorId, String timezone, Instant now) {}

    void open(Opening opening);
    void assertReversible(long assetId, UUID batch);
    void cancel(long assetId, UUID batch, long actorId, Instant now);
}
