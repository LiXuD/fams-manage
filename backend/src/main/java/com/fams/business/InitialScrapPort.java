package com.fams.business;

import java.time.Instant;
import java.util.UUID;

/** Opening scrap is acknowledged as an existing fact awaiting an administrator decision, never a fabricated application. */
public interface InitialScrapPort {
    record Opening(long asset, String beforeLifecycle, String reason, String observedCondition,
            String proposedDisposition, long actorId, UUID batch, int row, Instant time) { }

    void open(Opening opening);

    void assertReversible(long asset, UUID batch);

    void cancel(long asset, UUID batch, Instant time);
}
