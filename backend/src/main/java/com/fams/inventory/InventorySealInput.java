package com.fams.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record InventorySealInput(
        @NotBlank @Size(min = 3, max = 1000) String reason,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String synchronizationDigest,
        @Size(max = 2000) String missingSynchronizationReason) {}
