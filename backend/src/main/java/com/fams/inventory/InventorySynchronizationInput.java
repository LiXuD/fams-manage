package com.fams.inventory;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

final class InventorySynchronizationInput {
    private InventorySynchronizationInput() {}

    record Declaration(
            @NotNull UUID deviceId,
            @PositiveOrZero long assignmentVersion,
            @NotBlank @Pattern(regexp = "[0-9]{1,19}") @DecimalMax("9223372036854775807")
                    String observationWatermark,
            @NotBlank @Size(max = 16384) String queueSnapshot,
            @PositiveOrZero int unresolvedCount,
            @AssertTrue boolean pendingEmpty,
            @AssertTrue boolean cleanupEmpty) {}
}
