package com.fams.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

final class InventoryReadinessInput {
    private InventoryReadinessInput() {}

    record Unobserved(
            @PositiveOrZero long recheckGeneration,
            @PositiveOrZero long assetVersion,
            @NotBlank String kind,
            @NotBlank @Size(min = 3, max = 1000) String reason,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {}

    record Waiting(@NotBlank @Size(min = 3, max = 1000) String reason) {}
}
