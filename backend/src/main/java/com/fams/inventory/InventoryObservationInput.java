package com.fams.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class InventoryObservationInput {
    private InventoryObservationInput() {}

    public record Calibration(@NotNull UUID deviceId, @NotNull Instant clientTime) {}

    public record Facts(
            Long locationId,
            @Size(max = 500) String locationHint,
            @Size(max = 24) String custodyType,
            Long responsibleEmployeeId,
            @Size(max = 500) String responsibilityHint,
            @NotBlank String condition,
            @Size(max = 1000) String unknownReason,
            @Size(max = 2000) String description) {}

    public record Observation(
            @NotNull UUID eventId,
            Long itemId,
            @PositiveOrZero long assignmentVersion,
            @PositiveOrZero long recheckGeneration,
            Long assetVersion,
            @NotNull UUID deviceId,
            @NotBlank String source,
            @Size(max = 128) String assetCode,
            @Size(max = 1000) String manualReason,
            @NotNull @Valid Facts actual,
            Instant rawScannedAt,
            @NotNull Instant rawObservedAt,
            @NotNull Instant firstPersistedAt,
            @NotNull Instant expiresAt,
            UUID calibrationId,
            boolean clockAnomaly,
            @NotNull @Size(max = 9) List<UUID> photoIds) {
        public Observation {
            photoIds = photoIds == null ? null : List.copyOf(photoIds);
        }

        public List<UUID> photoIds() {
            return photoIds == null ? null : List.copyOf(photoIds);
        }
    }
}
