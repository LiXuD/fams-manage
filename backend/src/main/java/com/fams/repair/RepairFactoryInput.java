package com.fams.repair;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class RepairFactoryInput {
    private RepairFactoryInput() {}

    public record ScopeRequest(
            @PositiveOrZero long assetVersion,
            @PositiveOrZero long ownershipVersion,
            @PositiveOrZero long lockVersion,
            @NotBlank @Size(min = 3, max = 1000) String reason) {}

    public record ScopeDecision(
            @Positive long scopeId,
            @PositiveOrZero long scopeVersion,
            @NotBlank String result,
            @NotBlank @Size(min = 3, max = 1000) String reason) {}

    public record RoundCreation(
            @Positive long scopeId,
            @PositiveOrZero long scopeVersion,
            @PositiveOrZero long assetVersion,
            @PositiveOrZero long ownershipVersion,
            @PositiveOrZero long lockVersion,
            @NotBlank @Size(max = 200) String providerName,
            @NotBlank @Size(max = 100) String serviceContact,
            @NotBlank @Size(min = 3, max = 64) String servicePhone,
            @NotNull LocalDate expectedReturnDate,
            @NotBlank @Size(min = 3, max = 2000) String fault) {}

    public record RoundCancellation(
            @PositiveOrZero long roundVersion,
            @PositiveOrZero long assetVersion,
            @PositiveOrZero long ownershipVersion,
            @PositiveOrZero long lockVersion,
            @NotBlank @Size(min = 3, max = 1000) String reason) {}

    public record Dispatch(
            @PositiveOrZero long roundVersion,
            @PositiveOrZero long assetVersion,
            @PositiveOrZero long ownershipVersion,
            @PositiveOrZero long lockVersion,
            @NotBlank @Size(max = 2048) String observedCode,
            @Size(max = 128) String observedSerial,
            @NotNull Instant occurredAt,
            @NotNull @Size(min = 1, max = 14) List<@NotNull UUID> evidenceIds,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {
        public Dispatch {
            evidenceIds = evidenceIds == null ? null : List.copyOf(evidenceIds);
        }
    }

    public record OriginalReceipt(
            @PositiveOrZero long roundVersion,
            @PositiveOrZero long assetVersion,
            @PositiveOrZero long ownershipVersion,
            @PositiveOrZero long lockVersion,
            @NotBlank @Size(max = 2048) String observedCode,
            @Size(max = 128) String observedSerial,
            boolean originalPhysicalIdentityChecked,
            @Positive long actualLocationId,
            @PositiveOrZero long locationVersion,
            @NotNull Instant occurredAt,
            @NotBlank String conditionResult,
            @NotBlank @Pattern(regexp = "(?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,2})?")
                    String actualCost,
            @NotNull @Size(min = 1, max = 14) List<@NotNull UUID> evidenceIds,
            @NotBlank @Size(min = 10, max = 2000) String treatmentResult) {
        public OriginalReceipt {
            evidenceIds = evidenceIds == null ? null : List.copyOf(evidenceIds);
        }
    }
}
