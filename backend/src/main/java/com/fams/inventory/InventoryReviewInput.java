package com.fams.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class InventoryReviewInput {
    private InventoryReviewInput() {}

    public record Item(@Positive long itemId, @PositiveOrZero long recheckGeneration,
            @PositiveOrZero long reviewVersion, @PositiveOrZero long assetVersion) {}

    public record NormalBatch(@NotNull @Size(min = 1, max = 50) List<@Valid Item> items,
            @NotBlank @Size(max = 1000) String reason) {
        public NormalBatch {
            items = items == null ? null : List.copyOf(items);
        }
    }
}
