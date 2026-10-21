package com.fams.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record InventoryLateInput(
        @NotBlank @Pattern(regexp = "NOTE|LINK_RECHECK|REPLACE_RECHECK|CONFIRM_RECHECK")
                String method,
        @NotBlank @Size(min = 3, max = 1000) String reason,
        @NotBlank @Size(min = 3, max = 2000) String evidence,
        @Valid InventoryInput.Draft recheck) {}
