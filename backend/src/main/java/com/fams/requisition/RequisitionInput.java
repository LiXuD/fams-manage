package com.fams.requisition;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/** Public inputs use versioned references; employee clients never select asset codes. */
public final class RequisitionInput {
    private RequisitionInput() {}

    public record Reference(@Min(1) long id, @Min(0) long version) {}

    public record Demand(
            @Valid @NotNull Reference category,
            @NotBlank @Size(max = 1000) String specification,
            @Size(max = 128) String preferredBrand,
            @Size(max = 128) String preferredModel,
            @NotBlank @Size(max = 1000) String purpose,
            @NotNull LocalDate expectedDate,
            @NotBlank String custodyType,
            @Valid @NotNull Reference organization,
            @Valid @NotNull Reference employee,
            @Valid @NotNull Reference location,
            @Min(1) int quantity) {}

    public record Draft(
            @Valid @NotNull @Size(min = 1, max = 50) List<Demand> demands,
            String requestType,
            LocalDate expectedReturnDate) {
        public Draft {
            demands = demands == null ? null : List.copyOf(demands);
            requestType = requestType == null ? "REQUISITION" : requestType;
        }

        public Draft(List<Demand> demands) {
            this(demands, "REQUISITION", null);
        }
    }

    public record Decision(@NotBlank String result, @Size(max = 1000) String comment) {}

    public record Allocation(
            @NotNull @Valid Reference asset,
            Long predecessorAllocationId,
            @Size(max = 500) String replacementReason) {}

    public record Unfulfillable(
            @Min(1) int quantity, @NotBlank String reason, @Size(max = 1000) String description) {}

    public record Reason(@NotBlank @Size(max = 1000) String reason) {}
}
