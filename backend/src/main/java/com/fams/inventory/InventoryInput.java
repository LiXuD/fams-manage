package com.fams.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class InventoryInput {
    private InventoryInput() {}

    public record Range(
            Long usingOrganizationId,
            Long owningOrganizationId,
            Long categoryId,
            Long warehouseId,
            Long locationId,
            String lifecycle,
            String exception,
            Long responsibleEmployeeId) {}

    public record Draft(
            @NotBlank @Size(max = 128) String name,
            @NotNull @Valid Range range,
            @NotNull Instant startsAt,
            @NotNull Instant dueAt) {}

    public record Publish(@NotBlank String previewDigest, @Positive long expectedCount) {}

    public record Reason(@NotBlank @Size(max = 1000) String reason) {}

    public record Assign(
            @NotNull @Size(min = 1, max = 500) List<@Positive Long> itemIds,
            @Positive long executorAccountId,
            boolean recheck,
            @NotBlank @Size(max = 1000) String reason) {
        public List<Long> itemIds() {
            return itemIds == null ? null : List.copyOf(itemIds);
        }

        public Assign {
            if (itemIds != null) {
                itemIds = List.copyOf(itemIds);
            }
        }
    }
}
