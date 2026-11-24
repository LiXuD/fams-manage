package com.fams.inventory;

import com.fams.asset.AssetTransferPort;
import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Request bodies of the difference-review routes, where an asset admin turns raw inventory
 * differences into booked corrections, formal transfers or formal repairs. The nested records are
 * the exact Jackson-bound shapes of {@code /api/v1/inventory-reviews/tasks/{task}/differences}, and
 * every version component is an optimistic-lock value the client must echo back unmodified.
 */
public final class InventoryDifferenceInput {
    private InventoryDifferenceInput() {}

    /**
     * Request that materializes the review subjects of a set of observations. The service derives
     * the required dimensions per observation through
     * {@link InventoryDifferenceService#requiredDimensions} and inserts one
     * fams_inventory_review_subject row per missing dimension; EXPIRED and DUPLICATE observations
     * are skipped silently, a repeated id fails with INVENTORY_REVIEW_OBSERVATION_DUPLICATE and an
     * unknown observation with INVENTORY_OBSERVATION_NOT_FOUND.
     *
     * @param observationIds observation ids of the task to prepare, 1..50, processed in ascending
     *     order so that concurrent preparations lock the same subjects in the same sequence
     */
    public record Prepare(
            @NotNull @Size(min = 1, max = 50) List<@NotNull @Positive Long> observationIds) {
        /**
         * Freezes the observation id list at construction time. The copy keeps the preparation
         * request immutable, so the duplicate check, the ascending sort and the per-observation
         * subject creation all iterate the same ids even if the caller mutates its own list. A null
         * list is preserved as null so bean validation reports it as a missing value.
         *
         * @param observationIds ids to retain; copied when non-null, left null otherwise
         */
        public Prepare {
            observationIds = observationIds == null ? null : List.copyOf(observationIds);
        }
    }

    /**
     * Optimistic pin on one fams_inventory_review_subject row. Decisions must name the subject
     * exactly: an unknown subject fails with INVENTORY_REVIEW_SUBJECT_NOT_FOUND, a stale version or
     * an already CLOSED/TRANSFERRED subject raises INVENTORY_REVIEW_VERSION_CONFLICT, and a repeated
     * observation/dimension pair fails with INVENTORY_REVIEW_SUBJECT_DUPLICATE.
     *
     * @param observationId observation the subject belongs to, which must be part of the task
     * @param dimension derived dimension code such as LOCATION, USAGE_MODE, RESPONSIBILITY,
     *     PERIOD_MOVEMENT, UNKNOWN, CONFLICT, OLD_ASSIGNMENT, TIME, FINDING or LATE
     * @param version subject version the client read, zero for a freshly prepared subject
     */
    public record Subject(
            @Positive long observationId,
            @NotBlank String dimension,
            @PositiveOrZero long version) {}

    /**
     * Difference decision over one or more subjects of a single inventory item. All subjects must
     * belong to the same item, otherwise INVENTORY_REVIEW_JOINT_ASSET_REQUIRED, and a subject of an
     * unbooked asset (item_id null) must be settled through the finding route instead. The method
     * selects the legal target payload: BOOK_CORRECT and CONTINUE_SEARCH need none, FORMAL_TRANSFER
     * needs transferTarget, REFRESH_TRANSFER needs a previously stored transfer link, and the three
     * repair methods need {@code repair}.
     *
     * @param subjects pinned subjects to decide together, 1..20, all of one inventory item
     * @param assetVersion asset card version the client read, verified through
     *     {@code AssetInventoryReadPort.matchesVersion}; a stale value raises
     *     INVENTORY_REVIEW_ASSET_CHANGED
     * @param method one of BOOK_CORRECT, CONTINUE_SEARCH, FORMAL_TRANSFER, REFRESH_TRANSFER,
     *     FORMAL_REPAIR, LINK_REPAIR or REFRESH_REPAIR, otherwise
     *     INVENTORY_REVIEW_METHOD_INVALID
     * @param reason reviewer justification, 3..1000 characters, stored on every written subject,
     *     decision row and, for FORMAL_TRANSFER, passed as the transfer request reason
     * @param evidence supporting material reference, 10..2000 characters, also handed to the repair
     *     port as the damage evidence payload
     * @param transferTarget validated custody/location target of the formal transfer, mandatory for
     *     FORMAL_TRANSFER and ignored by every other method
     * @param repair damage verification and repair target of the three repair methods; omitted from
     *     the serialized body when null thanks to {@link JsonInclude}
     */
    public record Decision(
            @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid Subject> subjects,
            @PositiveOrZero long assetVersion,
            @NotBlank String method,
            @NotBlank @Size(min = 3, max = 1000) String reason,
            @NotBlank @Size(min = 10, max = 2000) String evidence,
            @Valid AssetTransferPort.Target transferTarget,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Valid Damage repair) {
        /**
         * Transfer-only convenience form used by callers that cannot decide a repair. It pins
         * {@code repair} to null so the decision body stays byte-identical to the pre-typing payload
         * for the transfer and book-correction methods, which never carried a repair key.
         *
         * @param subjects pinned subjects to decide together, 1..20, all of one inventory item
         * @param assetVersion asset card version the client read
         * @param method decision method, which must not be one of the repair methods
         * @param reason reviewer justification, 3..1000 characters
         * @param evidence supporting material reference, 10..2000 characters
         * @param transferTarget validated custody/location target, mandatory for FORMAL_TRANSFER
         */
        public Decision(
                List<Subject> subjects,
                long assetVersion,
                String method,
                String reason,
                String evidence,
                AssetTransferPort.Target transferTarget) {
            this(subjects, assetVersion, method, reason, evidence, transferTarget, null);
        }

        /**
         * Freezes the pinned subject list at construction time. The copy keeps the decision
         * immutable, so the duplicate check, the per-subject lock loop and the decision rows all
         * observe the same subjects even if the caller mutates its own list. A null list is
         * preserved as null so bean validation reports it as a missing value.
         *
         * @param subjects pinned subjects to retain; copied when non-null, left null otherwise
         */
        public Decision {
            subjects = subjects == null ? null : List.copyOf(subjects);
        }
    }

    /**
     * Damage verification carried by a repair decision. The service rejects the whole decision with
     * INVENTORY_REPAIR_VERIFICATION_REQUIRED unless both flags are true and verifiedDiscoveryAt is
     * present, not in the future and truncated to whole milliseconds. FORMAL_REPAIR additionally
     * requires repairId to be null and a 3..2000 character fault plus an impact of LOW, MEDIUM or
     * HIGH; LINK_REPAIR and REFRESH_REPAIR require repairId and repairVersion instead.
     *
     * @param repairId existing fams_repair_request id to link to; must be null for FORMAL_REPAIR
     * @param repairVersion repair request version the client read, mandatory when linking
     * @param verifiedDiscoveryAt moment the reviewer confirmed the damage was discovered, in whole
     *     milliseconds and never later than the server clock
     * @param fault fault description of a newly created repair, 3..2000 characters
     * @param impact business impact of a newly created repair: LOW, MEDIUM or HIGH
     * @param physicalDamageChecked reviewer attestation that the physical damage was inspected
     * @param discoveryTimeVerified reviewer attestation that the discovery time was verified
     */
    public record Damage(
            @Positive Long repairId,
            @PositiveOrZero Long repairVersion,
            Instant verifiedDiscoveryAt,
            @Size(min = 3, max = 2000) String fault,
            String impact,
            boolean physicalDamageChecked,
            boolean discoveryTimeVerified) {}
}
