package com.fams.requisition;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

final class ItemTargetChangeInput {
    private ItemTargetChangeInput() {}

    record Target(@NotBlank String custodyType,
            @NotNull @Pattern(regexp = "[1-9][0-9]{0,17}") String organizationId, @Min(0) long organizationVersion,
            @NotNull @Pattern(regexp = "[1-9][0-9]{0,17}") String employeeId, @Min(0) long employeeVersion,
            @NotNull @Pattern(regexp = "[1-9][0-9]{0,17}") String locationId, @Min(0) long locationVersion) {}

    record Proposal(@Min(1) long businessId, @Min(1) long itemId,
            @NotNull Map<String, Object> snapshot, @Valid @NotNull Target target,
            @NotBlank @Size(max = 1000) String reason,
            @NotBlank @Size(max = 128) String observedAssetCode,
            @NotBlank @Size(max = 1000) String physicalVerification,
            @AssertTrue boolean physicalChecked) {}

    record Decision(@NotBlank @Pattern(regexp = "APPROVED|REJECTED") String result,
            @NotBlank @Size(max = 1000) String reason) {}
}
