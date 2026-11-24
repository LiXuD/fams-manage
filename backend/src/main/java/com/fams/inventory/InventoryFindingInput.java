package com.fams.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Request bodies of the finding-review routes, where an asset admin settles an observation that
 * matched no booked asset (the FINDING dimension) or whose recorded time is implausible (the TIME
 * dimension). The nested records are the exact Jackson-bound shapes of
 * {@code /api/v1/inventory-reviews/tasks/{task}/findings/{observation}}, so their component names
 * and order are wire contract.
 */
public final class InventoryFindingInput {
    private InventoryFindingInput() {}

    /**
     * Time-dimension decision that closes a TIME_REVIEW subject by pointing at a later, credible
     * FINDING_REVIEW observation of the same unbooked asset. The service rejects the observation
     * itself as counter-evidence, requires the counter observation to be fully calibrated, and
     * requires its calibrated time to sit inside the published window and within the two-second
     * observed/persisted and persisted/received tolerances; anything else raises
     * INVENTORY_FINDING_RECOLLECTION_INVALID or INVENTORY_REVIEW_RECOLLECTION_REQUIRED. The written
     * subject always ends up CLOSED with method RECOLLECTED.
     *
     * @param subjectVersion fams_inventory_review_subject version the client read for the TIME
     *     dimension; a mismatch or an already CLOSED/TRANSFERRED subject raises
     *     INVENTORY_REVIEW_VERSION_CONFLICT
     * @param counterObservationId id of the later FINDING_REVIEW observation that supersedes the
     *     implausible timestamp, which must differ from the observation being decided
     * @param reason reviewer justification, 3..1000 characters, stored on the subject and the
     *     emitted decision row
     * @param evidence supporting material reference, 10..2000 characters
     */
    public record Recollection(
            @PositiveOrZero long subjectVersion,
            @Positive long counterObservationId,
            @NotBlank @Size(min = 3, max = 1000) String reason,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {}

    /**
     * Finding-dimension decision. The method selects which of the three target shapes is legal:
     * NON_ENTERPRISE requires all three targets to be null and closes the subject immediately,
     * DUPLICATE_ASSET requires an asset plus its owner version and no inbound receipt, and
     * LINK_INBOUND/REFRESH_INBOUND require all three. A wrong combination raises
     * INVENTORY_FINDING_TARGET_INVALID; REFRESH_INBOUND additionally requires an existing
     * FORMAL_INBOUND link on the same asset and receipt, otherwise
     * INVENTORY_FINDING_LINK_REQUIRED.
     *
     * @param subjectVersion fams_inventory_review_subject version the client read for the FINDING
     *     dimension; the decision writes version+1
     * @param method one of NON_ENTERPRISE, DUPLICATE_ASSET, LINK_INBOUND or REFRESH_INBOUND,
     *     otherwise INVENTORY_REVIEW_METHOD_INVALID
     * @param assetId target asset card for the duplicate or formal-inbound link; must be null for
     *     NON_ENTERPRISE
     * @param inboundReceiptId formal inbound receipt the finding is linked to; required only by
     *     LINK_INBOUND and REFRESH_INBOUND
     * @param ownerVersion owner version the client read for the asset or receipt, verified through
     *     the inbound context port and rejected as INVENTORY_FINDING_OWNER_CHANGED when stale
     * @param reason reviewer justification, 3..1000 characters, stored stripped on the subject,
     *     the decision row and the audit event
     * @param evidence supporting material reference, 10..2000 characters, stored stripped
     */
    public record Decision(
            @PositiveOrZero long subjectVersion,
            @NotBlank String method,
            @Positive Long assetId,
            @Positive Long inboundReceiptId,
            @PositiveOrZero Long ownerVersion,
            @NotBlank @Size(min = 3, max = 1000) String reason,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {}
}
