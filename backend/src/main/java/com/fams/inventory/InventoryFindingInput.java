package com.fams.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class InventoryFindingInput {
    private InventoryFindingInput() {}

    public record Recollection(
            @PositiveOrZero long subjectVersion,
            @Positive long counterObservationId,
            @NotBlank @Size(min = 3, max = 1000) String reason,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {}

    public record Decision(
            @PositiveOrZero long subjectVersion,
            @NotBlank String method,
            @Positive Long assetId,
            @Positive Long inboundReceiptId,
            @PositiveOrZero Long ownerVersion,
            @NotBlank @Size(min = 3, max = 1000) String reason,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {}
}
