package com.fams.business;

import java.time.Instant;
import java.util.UUID;

/** Opening scrap is acknowledged as an existing fact awaiting an administrator decision, never a fabricated application. */
public interface InitialScrapPort {
    record LossFacts(Instant lastSeenAt, String lastLocation, Instant confirmedAt, String evidence) { }
    record FactoryDisposition(String method, Instant occurredAt, String evidence) { }

    record Opening(long asset, String beforeLifecycle, String reason, String observedCondition,
            String proposedDisposition, long actorId, UUID batch, int row, Instant time) { }

    void open(Opening opening);

    void openRepair(Opening opening);

    void openLoss(Opening opening, LossFacts facts, java.time.LocalDate businessDate);

    void openFactoryDisposition(Opening opening, FactoryDisposition disposition);

    void assertReversible(long asset, UUID batch);

    void cancel(long asset, UUID batch, Instant time);
}
