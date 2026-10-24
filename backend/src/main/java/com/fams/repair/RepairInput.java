package com.fams.repair;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class RepairInput {
    private RepairInput() {}

    public record Submission(
            @Positive long assetId,
            @PositiveOrZero long assetVersion,
            @NotBlank @Size(min = 3, max = 2000) String fault,
            @NotBlank String impact,
            @NotNull Instant occurredAt) {}

    public record Decision(
            @NotBlank String result,
            String allowedMethod,
            @NotBlank @Size(min = 3, max = 1000) String reason) {}

    public record ReturnSourceSubmission(
            @PositiveOrZero long assetVersion,
            @PositiveOrZero long lockVersion,
            @NotBlank @Size(min = 3, max = 2000) String fault,
            @NotBlank String impact) {}

    public record Reason(@NotBlank @Size(min = 3, max = 1000) String reason) {}

    public record InPlace(
            @PositiveOrZero long assetVersion,
            @PositiveOrZero long ownershipVersion,
            @PositiveOrZero long lockVersion,
            @NotBlank @Size(max = 2048) String observedCode,
            @Positive long actualLocationId,
            boolean neverLeftCustodian,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {}

    public record AwayAcceptance(
            @PositiveOrZero long assetVersion,
            @PositiveOrZero long ownershipVersion,
            @PositiveOrZero long lockVersion,
            @PositiveOrZero Long loanVersion,
            @NotBlank @Size(max = 2048) String observedCode,
            @Positive long actualLocationId,
            @PositiveOrZero long locationVersion,
            @NotNull Instant occurredAt,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {}

    public record Inbound(
            @PositiveOrZero long assetVersion,
            @PositiveOrZero long ownershipVersion,
            @PositiveOrZero long lockVersion,
            @PositiveOrZero Long loanVersion,
            @NotBlank @Size(max = 2048) String observedCode,
            @Positive long warehouseId,
            @PositiveOrZero long warehouseVersion,
            @Positive long locationId,
            @PositiveOrZero long locationVersion,
            @NotNull Instant occurredAt,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {}

    public record ReturnDelivery(
            @PositiveOrZero long assetVersion,
            @PositiveOrZero long ownershipVersion,
            @PositiveOrZero long lockVersion,
            @PositiveOrZero Long loanVersion,
            @PositiveOrZero long employeeVersion,
            @PositiveOrZero long organizationVersion,
            @PositiveOrZero long locationVersion,
            @NotBlank @Size(max = 2048) String observedCode,
            @NotNull Instant occurredAt,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {}

    public record ReturnReceipt(
            @Positive long handoverId,
            @PositiveOrZero long assetVersion,
            @PositiveOrZero long lockVersion,
            @PositiveOrZero Long loanVersion,
            boolean physicalChecked,
            @NotBlank @Size(max = 2048) String observedCode,
            @NotNull Instant occurredAt,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {}

    public record ReturnRecollection(
            @Positive long handoverId,
            @PositiveOrZero long assetVersion,
            @PositiveOrZero long ownershipVersion,
            @PositiveOrZero long lockVersion,
            @NotBlank @Size(max = 2048) String observedCode,
            @Positive long actualLocationId,
            @PositiveOrZero long locationVersion,
            @NotNull Instant occurredAt,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {}
}
