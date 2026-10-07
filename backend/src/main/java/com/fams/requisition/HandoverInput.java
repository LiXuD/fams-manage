package com.fams.requisition;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

final class HandoverInput {
    private HandoverInput() {}

    record Delivery(
            @Min(0) long allocationVersion,
            @Min(0) long lockVersion,
            @Min(0) long assetVersion,
            @NotBlank @Size(max = 128) String observedAssetCode,
            @NotBlank @Size(max = 1000) String destination,
            @NotBlank @Size(max = 1000) String evidence) {}

    record WarehouseAction(
            @Min(0) long allocationVersion,
            @Min(0) long lockVersion,
            @Min(0) long assetVersion,
            @NotBlank @Size(max = 500) String reason,
            @Size(max = 128) String observedAssetCode,
            @Size(max = 1000) String inspectionEvidence,
            boolean physicalReturned) {}

    record Receipt(
            @NotBlank @Size(min = 43, max = 43) String grant,
            @NotBlank @Size(max = 128) String observedAssetCode,
            @AssertTrue boolean physicalChecked,
            @Size(max = 1000) String reason) {}
}
