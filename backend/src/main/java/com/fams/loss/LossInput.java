package com.fams.loss;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

public final class LossInput {
    private LossInput() {}

    public record Submission(
            @Positive long assetId,
            @PositiveOrZero long assetVersion,
            @NotNull Instant lastSeenAt,
            @NotBlank @Size(max = 500) String lastLocation,
            @NotBlank @Size(min = 10, max = 2000) String circumstances) {}

    public record Decision(
            @NotBlank String result, @NotBlank @Size(min = 3, max = 1000) String reason) {}

    public record Reason(@NotBlank @Size(min = 3, max = 1000) String reason) {}

    public record Search(
            @PositiveOrZero long assetVersion,
            @PositiveOrZero long ownershipVersion,
            @PositiveOrZero long lockVersion,
            @NotBlank @Size(min = 10, max = 2000) String custodianVerification,
            @NotBlank @Size(min = 10, max = 2000) String searchedScope,
            @NotNull Instant searchedAt,
            @NotNull LocalDate nextReviewDate) {}

    public record Confirmation(
            @PositiveOrZero long assetVersion,
            @PositiveOrZero long ownershipVersion,
            @PositiveOrZero long lockVersion,
            @NotBlank @Size(min = 10, max = 2000) String custodianVerification,
            @NotBlank @Size(min = 10, max = 2000) String searchedScope,
            @NotNull Instant searchedAt,
            @NotBlank @Size(min = 10, max = 2000) String relatedClues,
            @NotBlank @Size(min = 10, max = 2000) String inabilityReason,
            @NotBlank @Size(min = 10, max = 2000) String responsibilityEvidence,
            @NotNull Instant confirmedAt) {}

    public record FollowUp(
            @PositiveOrZero long ownershipVersion,
            @NotNull Instant reviewedAt,
            @NotBlank @Size(min = 10, max = 2000) String findings,
            @NotBlank @Size(min = 3, max = 1000) String reason,
            LocalDate nextReviewDate) {}

    public record RecoveryFacts(
            @NotNull Instant foundAt,
            @NotBlank @Size(max = 64) String observedAssetCode,
            @Size(max = 128) String observedSerialNumber,
            @Positive long locationId,
            @PositiveOrZero long locationVersion,
            boolean physicalIdentityVerified,
            @NotBlank String condition,
            boolean stillOriginalCustodian,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {}

    public record RecoveryRequest(
            @PositiveOrZero long assetVersion,
            @NotNull @jakarta.validation.Valid RecoveryFacts facts) {}

    public record RecoveryExecution(
            @PositiveOrZero long assetVersion,
            @PositiveOrZero long ownershipVersion,
            @PositiveOrZero long lockVersion,
            @NotNull @jakarta.validation.Valid RecoveryFacts facts) {}
}
