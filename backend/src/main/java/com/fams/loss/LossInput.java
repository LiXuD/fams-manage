package com.fams.loss;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Request payload contracts for the loss-report endpoints. Each record carries the Bean Validation
 * constraints enforced at binding time; deeper business rules (timestamp ordering, evidence
 * length against the recorded timeline) are re-checked in {@link LossService}. The wrapper class is
 * never instantiated.
 */
public final class LossInput {
    private LossInput() {}

    /**
     * Submission payload for reporting an ordinary asset lost: the asset reference under optimistic
     * versioning plus the last-seen facts (timestamp, location and circumstances narrative).
     */
    public record Submission(
            @Positive long assetId,
            @PositiveOrZero long assetVersion,
            @NotNull Instant lastSeenAt,
            @NotBlank @Size(max = 500) String lastLocation,
            @NotBlank @Size(min = 10, max = 2000) String circumstances) {}

    /**
     * Submission payload for reporting an asset lost from a factory repair round. Carries the
     * optimistic versions of the round, asset, custody and business lock so the handoff can be
     * rejected when any of them moved since the caller loaded the round.
     */
    public record FactorySubmission(
            @NotNull @PositiveOrZero Long roundVersion,
            @NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long ownershipVersion,
            @NotNull @PositiveOrZero Long lockVersion,
            @NotNull Instant lastSeenAt,
            @NotBlank @Size(max = 500) String lastLocation,
            @NotBlank @Size(min = 10, max = 2000) String circumstances) {}

    /**
     * Submission payload for reporting an asset lost from an internal repair order, mirroring
     * {@link FactorySubmission} without the round version because internal repairs are single-round.
     */
    public record InternalRepairSubmission(
            @NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long ownershipVersion,
            @NotNull @PositiveOrZero Long lockVersion,
            @NotNull Instant lastSeenAt,
            @NotBlank @Size(max = 500) String lastLocation,
            @NotBlank @Size(min = 10, max = 2000) String circumstances) {}

    /**
     * Admin decision payload shared by report and recovery approvals: the result string is
     * validated against APPROVE/REJECT in the service, and the reason must be substantive.
     */
    public record Decision(
            @NotBlank String result, @NotBlank @Size(min = 3, max = 1000) String reason) {}

    /**
     * Free-text reason payload used by report and recovery cancellations.
     */
    public record Reason(@NotBlank @Size(min = 3, max = 1000) String reason) {}

    /**
     * Payload starting the search phase on a claimed report: tracked versions for concurrency, the
     * custodian verification and searched scope narratives, the search timestamp and the scheduled
     * next review date.
     */
    public record Search(
            @PositiveOrZero long assetVersion,
            @PositiveOrZero long ownershipVersion,
            @PositiveOrZero long lockVersion,
            @NotBlank @Size(min = 10, max = 2000) String custodianVerification,
            @NotBlank @Size(min = 10, max = 2000) String searchedScope,
            @NotNull Instant searchedAt,
            @NotNull LocalDate nextReviewDate) {}

    /**
     * Payload confirming an asset as lost after the search phase. All five evidence fields
     * (custodian verification, searched scope, related clues, inability reason and responsibility
     * evidence) must carry at least 10 characters, and the searched/confirmed timestamps order the
     * confirmation relative to the search.
     */
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

    /**
     * Payload recording one periodic follow-up review on a confirmed-lost report. The next review
     * date is optional; when absent the service schedules it 30 days out in the active review
     * timezone.
     */
    public record FollowUp(
            @PositiveOrZero long ownershipVersion,
            @NotNull Instant reviewedAt,
            @NotBlank @Size(min = 10, max = 2000) String findings,
            @NotBlank @Size(min = 3, max = 1000) String reason,
            LocalDate nextReviewDate) {}

    /**
     * Facts describing a physical recovery: when and where the asset was found, the observed
     * identifiers used to verify physical identity, its condition (INTACT or DAMAGED), whether the
     * original custodian still holds it, and the supporting evidence narrative. These facts steer
     * which recovery route (original, repair, return, transfer) the service accepts.
     */
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

    /**
     * Payload requesting approval to recover a confirmed-lost asset to its original state, pairing
     * the expected asset version with the validated recovery facts.
     */
    public record RecoveryRequest(
            @PositiveOrZero long assetVersion,
            @NotNull @jakarta.validation.Valid RecoveryFacts facts) {}

    /**
     * Payload requesting approval to recover a confirmed-lost asset into a return or transfer
     * destination; the boxed asset version is mandatory because these routes always carry an
     * explicit optimistic version.
     */
    public record ReturnRecoveryRequest(
            @NotNull @PositiveOrZero Long assetVersion,
            @NotNull @jakarta.validation.Valid RecoveryFacts facts) {}

    /**
     * Payload requesting approval to recover a confirmed-lost asset that was found damaged, so the
     * later execution can create a linked repair application.
     */
    public record DamagedRecoveryRequest(
            @NotNull @PositiveOrZero Long assetVersion,
            @NotNull @jakarta.validation.Valid RecoveryFacts facts) {}

    /**
     * Execution payload for recovery routes driven by an approved recovery request or an explicit
     * destination (return/transfer/factory/internal repair): all tracked versions plus the
     * validated recovery facts.
     */
    public record ExplicitRecoveryExecution(
            @NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long ownershipVersion,
            @NotNull @PositiveOrZero Long lockVersion,
            @NotNull @jakarta.validation.Valid RecoveryFacts facts) {}

    /**
     * Execution payload handing a recovered asset into a new transfer: the explicit recovery
     * execution, the transfer target chosen by the operator and the transfer reason.
     */
    public record TransferRecoveryExecution(
            @NotNull @jakarta.validation.Valid ExplicitRecoveryExecution recovery,
            @NotNull com.fams.asset.AssetTransferPort.Target target,
            @NotBlank @Size(max = 1000) String reason) {}

    /**
     * Execution payload completing a damaged approved recovery into a linked repair: the explicit
     * recovery execution plus the fault description and impact statement for the new repair order.
     */
    public record ClosedRepairRecovery(
            @NotNull @jakarta.validation.Valid ExplicitRecoveryExecution recovery,
            @NotBlank @Size(min = 10, max = 2000) String fault,
            @NotBlank String impact) {}

    /**
     * Execution payload recovering a damaged asset during the search phase directly into a linked
     * repair, mirroring {@link ClosedRepairRecovery} but based on the report-scoped
     * {@link RecoveryExecution} without an approved recovery request.
     */
    public record RepairRecovery(
            @NotNull @jakarta.validation.Valid RecoveryExecution recovery,
            @NotBlank @Size(min = 10, max = 2000) String fault,
            @NotBlank String impact) {}

    /**
     * Report-scoped recovery execution payload for the search phase: tracked versions plus the
     * validated recovery facts, without the boxed nullability of the explicit variant.
     */
    public record RecoveryExecution(
            @PositiveOrZero long assetVersion,
            @PositiveOrZero long ownershipVersion,
            @PositiveOrZero long lockVersion,
            @NotNull @jakarta.validation.Valid RecoveryFacts facts) {}
}
