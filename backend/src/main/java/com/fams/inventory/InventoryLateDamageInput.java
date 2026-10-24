package com.fams.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

record InventoryLateDamageInput(
        @NotBlank @Pattern(regexp = "FORMAL_REPAIR|LINK_REPAIR|REFRESH_REPAIR") String method,
        @PositiveOrZero long assetVersion,
        @NotBlank @Size(min = 3, max = 1000) String reason,
        @NotBlank @Size(min = 10, max = 2000) String evidence,
        @Valid InventoryDifferenceInput.Damage repair) {}
