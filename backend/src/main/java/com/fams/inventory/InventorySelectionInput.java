package com.fams.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** A selection is evidence for further review, never automatic acceptance. */
public record InventorySelectionInput(
        @Positive long observationId,
        @PositiveOrZero long recheckGeneration,
        @PositiveOrZero long reviewVersion,
        @PositiveOrZero long assetVersion,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String contextDigest,
        Instant verifiedObservedAt,
        @NotBlank @Size(min = 3, max = 1000) String reason,
        @NotBlank @Size(min = 10, max = 2000) String evidence) {}
