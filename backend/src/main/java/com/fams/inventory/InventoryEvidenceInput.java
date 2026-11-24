package com.fams.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request bodies of the evidence-review routes, where an asset admin settles the competing
 * observations of one inventory item. The nested records are the exact Jackson-bound shapes of
 * {@code /api/v1/inventory-reviews/.../evidence}, so their component names and order are wire
 * contract and every optimistic-lock value must be echoed back by the client untouched.
 */
public final class InventoryEvidenceInput {
    private InventoryEvidenceInput() {}

    /**
     * Optimistic pin on one fams_inventory_review_subject row. The reviewer must state both the
     * dimension and the subject version it read; a mismatch makes the service reject that subject
     * with INVENTORY_REVIEW_VERSION_CONFLICT instead of silently overwriting a concurrent decision.
     *
     * @param dimension subject dimension code, at most 32 characters, e.g. FINDING or TIME
     * @param version subject version the client read, zero for a subject never reviewed before
     */
    public record Reference(
            @NotBlank @Size(max = 32) String dimension, @PositiveOrZero long version) {}

    /**
     * Decision on one non-selected (counter) observation of an item under review. The service
     * requires the method to be VERIFY_SELECTED, REJECT_EVIDENCE or SUPPLEMENTAL, requires exactly
     * one pinned version per involved dimension, and records the outcome in
     * fams_inventory_review_subject, fams_inventory_review_decision and, for counter-evidence,
     * fams_inventory_evidence_disposition.
     *
     * @param observationId observation being decided; when it equals the review's chosen
     *     observation the method must be VERIFY_SELECTED
     * @param recheckGeneration recheck generation the observation belongs to, zero for the first
     *     assignment round
     * @param reviewVersion fams_inventory_item_review version the client read, used for the
     *     review-level optimistic lock
     * @param assetVersion asset card version the client read, used for the asset-level lock
     * @param contextDigest 64-char lowercase hex movement digest of the review context the client
     *     rendered; a stale digest fails with INVENTORY_REVIEW_CONTEXT_CHANGED
     * @param method one of VERIFY_SELECTED, REJECT_EVIDENCE or SUPPLEMENTAL, otherwise
     *     INVENTORY_REVIEW_METHOD_INVALID
     * @param subjects pinned dimension versions, at most 13; a repeated dimension fails with
     *     INVENTORY_REVIEW_SUBJECT_DUPLICATE and a missing one with
     *     INVENTORY_REVIEW_ALL_DIMENSIONS_REQUIRED
     * @param reason reviewer justification, 3..1000 characters, stored on every written row
     * @param evidence supporting material reference, 10..2000 characters
     */
    public record Decision(
            @Positive long observationId,
            @PositiveOrZero long recheckGeneration,
            @Positive long reviewVersion,
            @PositiveOrZero long assetVersion,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String contextDigest,
            @NotBlank @Size(max = 32) String method,
            @NotNull @Size(max = 13) List<@NotNull @Valid Reference> subjects,
            @NotBlank @Size(min = 3, max = 1000) String reason,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {
        /**
         * Freezes the pinned subject list at construction time. The copy keeps the decision
         * immutable, so the duplicate check, the dimension coverage comparison and the per-subject
         * version verification all iterate the same rows even if the caller mutates its own list.
         * A null list is preserved as null so bean validation reports it as a missing value.
         *
         * @param subjects pinned dimension versions to retain; copied when non-null
         */
        public Decision {
            subjects = subjects == null ? null : List.copyOf(subjects);
        }
    }

    /**
     * Final acceptance of the observation already marked SELECTED for an item. Before flipping
     * fams_inventory_item_review to ACCEPTED the service verifies that every other observation of
     * the same item and recheck generation carries a stored disposition and that all its review
     * subjects are CLOSED or TRANSFERRED, so this record carries only lock values and the closing
     * justification.
     *
     * @param recheckGeneration recheck generation of the review being closed, zero for the first
     *     assignment round
     * @param reviewVersion fams_inventory_item_review version the client read; the acceptance
     *     writes version+1
     * @param assetVersion asset card version the client read, persisted as
     *     reviewed_asset_version
     * @param contextDigest 64-char lowercase hex movement digest of the selection event the client
     *     rendered; a stale digest fails with INVENTORY_REVIEW_CONTEXT_CHANGED
     * @param reason reviewer justification, 3..1000 characters, stored on the review row and the
     *     emitted selection event
     * @param evidence supporting material reference, 10..2000 characters
     */
    public record Acceptance(
            @PositiveOrZero long recheckGeneration,
            @Positive long reviewVersion,
            @PositiveOrZero long assetVersion,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String contextDigest,
            @NotBlank @Size(min = 3, max = 1000) String reason,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {}
}
