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
}
