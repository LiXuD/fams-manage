package com.fams.inventory;

import java.time.Duration;
import java.time.Instant;

/**
 * Calibration bounds confidence; client clocks never grant authorization or a new retention window.
 */
final class InventoryObservationTime {
    record Result(String decision, Instant observedAt, Instant persistedAt) {}

    private InventoryObservationTime() {}

    static Result evaluate(
            InventoryObservationInput.Observation input,
            Instant clientAnchor,
            Instant serverAnchor,
            Instant received) {
        if (clientAnchor == null
                || serverAnchor == null
                || input.clockAnomaly()
                || "SCAN".equals(input.source()) && input.rawScannedAt() == null
                || input.rawScannedAt() != null
                        && input.rawScannedAt().isAfter(input.rawObservedAt().plusSeconds(2))
                || Duration.between(clientAnchor, serverAnchor)
                                .abs()
                                .compareTo(Duration.ofMinutes(5))
                        > 0
                || !input.expiresAt().equals(input.firstPersistedAt().plus(Duration.ofHours(72)))) {
            return new Result("TIME_REVIEW", null, null);
        }
        Duration elapsed = Duration.between(clientAnchor, input.firstPersistedAt());
        Instant persisted = serverAnchor.plus(elapsed);
        Instant observed = serverAnchor.plus(Duration.between(clientAnchor, input.rawObservedAt()));
        if (elapsed.isNegative()
                || elapsed.compareTo(Duration.ofHours(72)) > 0
                || persisted.isAfter(received.plusSeconds(2))
                || observed.isAfter(persisted.plusSeconds(2))
                || observed.isBefore(serverAnchor.minusSeconds(2))) {
            return new Result("TIME_REVIEW", observed, persisted);
        }
        if (!received.isBefore(persisted.plus(Duration.ofHours(72)))) {
            return new Result("EXPIRED", observed, persisted);
        }
        return new Result("VALID", observed, persisted);
    }
}
