package com.fams.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

class InventoryObservationTimeTest {
    private static final Instant CLIENT = Instant.parse("2026-10-14T00:00:30Z");
    private static final Instant SERVER = Instant.parse("2026-10-14T00:00:00Z");

    private InventoryObservationInput.Observation event(Instant persisted, boolean anomaly) {
        return new InventoryObservationInput.Observation(
                UUID.randomUUID(),
                1L,
                1,
                0,
                0L,
                UUID.randomUUID(),
                "SCAN",
                "A-1",
                null,
                new InventoryObservationInput.Facts(
                        1L, null, "STORAGE", null, null, "GOOD", null, null),
                persisted,
                persisted,
                persisted,
                persisted.plus(Duration.ofHours(72)),
                UUID.randomUUID(),
                anomaly,
                List.of());
    }

    @Test
    void exactExpiryUsesOriginalCalibratedPersistenceNotUploadOrSealTime() {
        var input = event(CLIENT.plusSeconds(10), false);
        Instant deadline = SERVER.plusSeconds(10).plus(Duration.ofHours(72));
        assertEquals(
                "VALID",
                InventoryObservationTime.evaluate(input, CLIENT, SERVER, deadline.minusMillis(1))
                        .decision());
        assertEquals(
                "EXPIRED",
                InventoryObservationTime.evaluate(input, CLIENT, SERVER, deadline).decision());
        assertEquals(
                "EXPIRED",
                InventoryObservationTime.evaluate(input, CLIENT, SERVER, deadline.plusMillis(1))
                        .decision());
    }

    @Test
    void missingCalibrationRollbackAndFutureClaimsNeverBecomeAutomaticAcceptance() {
        assertEquals(
                "TIME_REVIEW",
                InventoryObservationTime.evaluate(event(CLIENT, false), null, null, SERVER)
                        .decision());
        assertEquals(
                "TIME_REVIEW",
                InventoryObservationTime.evaluate(
                                event(CLIENT.minusSeconds(10), false), CLIENT, SERVER, SERVER)
                        .decision());
        assertEquals(
                "TIME_REVIEW",
                InventoryObservationTime.evaluate(
                                event(CLIENT.plusSeconds(100), false), CLIENT, SERVER, SERVER)
                        .decision());
        assertEquals(
                "TIME_REVIEW",
                InventoryObservationTime.evaluate(event(CLIENT, true), CLIENT, SERVER, SERVER)
                        .decision());
        assertEquals(
                "TIME_REVIEW",
                InventoryObservationTime.evaluate(
                                event(CLIENT, false), CLIENT, SERVER.minusSeconds(3600), SERVER)
                        .decision());
    }
}
