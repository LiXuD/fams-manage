package com.fams.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request bodies reported by the handheld inventory client: one clock calibration per device and
 * one observation per scanned asset. Every timestamp component is a client-clock value that the
 * server re-derives through {@link InventoryObservationTime}, so none of them is ever trusted as an
 * authority; the nested records are the exact Jackson-bound shapes of
 * {@code /api/v1/inventory-observations} and are stored verbatim in
 * fams_inventory_observation.facts_json.
 */
public final class InventoryObservationInput {
    private InventoryObservationInput() {}

    /**
     * One device clock sample. The service rejects a clientTime outside 2000-01-01..2100-01-01 or
     * carrying sub-millisecond precision with INVENTORY_TIME_INVALID, then stores the pair together
     * with the actor and its device binding in fams_inventory_clock_calibration and returns the
     * generated calibration id, which later observations must quote to be time-calibrated at all.
     *
     * @param deviceId stable client device identifier, also checked against the observation's own
     *     deviceId and the photo upload scope
     * @param clientTime device clock reading taken at the moment of the sample
     */
    public record Calibration(@NotNull UUID deviceId, @NotNull Instant clientTime) {}

    /**
     * Physically observed facts of one asset, free of any bookkeeping reference. This record is the
     * unit that is hashed into fams_inventory_round_result.fact_sha256 to decide whether a repeat
     * submission of the same item is a DUPLICATE or a CONFLICT_REVIEW, and it is diffed against the
     * published item snapshot to derive the difference dimensions LOCATION, USAGE_MODE,
     * RESPONSIBILITY, CONDITION, UNKNOWN and PERIOD_MOVEMENT.
     *
     * @param locationId observed location id, or null when the executor could not determine one;
     *     null forces a non-blank unknownReason
     * @param locationHint free-text description of where the asset was found, at most 500 chars
     * @param custodyType one of STORAGE, PERSONAL or SHARED; anything else fails with
     *     INVENTORY_CUSTODY_INVALID, and a value other than STORAGE requires a responsible employee
     *     or an unknownReason
     * @param responsibleEmployeeId observed responsible employee; must appear in at least one item
     *     snapshot of the task, otherwise INVENTORY_PERSON_OUTSIDE_TASK
     * @param responsibilityHint free-text hint about who actually holds the asset; a non-blank hint
     *     alone raises the RESPONSIBILITY difference dimension
     * @param condition one of GOOD, DAMAGED or UNKNOWN; DAMAGED requires at least one photo
     * @param unknownReason justification for every field the executor could not verify, at most
     *     1000 characters; required whenever a fact is missing or the condition is UNKNOWN
     * @param description free-text description of the unbooked asset, at most 2000 characters and
     *     mandatory when no itemId is supplied (a finding)
     */
    public record Facts(
            Long locationId,
            @Size(max = 500) String locationHint,
            @Size(max = 24) String custodyType,
            Long responsibleEmployeeId,
            @Size(max = 500) String responsibilityHint,
            @NotBlank String condition,
            @Size(max = 1000) String unknownReason,
            @Size(max = 2000) String description) {}

    /**
     * One submitted inventory observation. The eventId is the idempotency key shared by
     * fams_inventory_observation.client_event_id and fams_inventory_upload_scope.client_event_id:
     * resubmitting the same eventId with the same actor, device binding and payload hash replays the
     * stored receipt, while any identity or payload mismatch fails with INVENTORY_EVENT_CONFLICT.
     * The service derives the outcome (ACCEPTED, DUPLICATE, CONFLICT_REVIEW, DIFFERENCE_REVIEW,
     * FINDING_REVIEW, OLD_ASSIGNMENT_REVIEW, TIME_REVIEW, LATE_REVIEW or EXPIRED) rather than
     * trusting anything in this record.
     *
     * @param eventId client-generated event id used for idempotent replay and to bind the photo
     *     upload scope of this observation
     * @param itemId inventory item the observation refers to, or null for an unbooked asset, which
     *     routes the observation to FINDING_REVIEW and makes a photo and a description mandatory
     * @param assignmentVersion assignment version echoed from the identification lookup; a mismatch
     *     against the locked item downgrades the outcome to OLD_ASSIGNMENT_REVIEW
     * @param recheckGeneration recheck generation echoed from the identification lookup, zero for
     *     the first assignment round
     * @param assetVersion asset card version echoed from the identification lookup, or null when
     *     the item carries none; a mismatch downgrades the outcome to OLD_ASSIGNMENT_REVIEW
     * @param deviceId device that produced the observation; must equal the device recorded on the
     *     quoted clock calibration and on the upload scope, otherwise INVENTORY_CALIBRATION_IDENTITY
     *     or INVENTORY_PHOTO_NOT_AUTHORIZED
     * @param source one of SCAN, CODE or MANUAL; anything else fails with INVENTORY_FACT_INVALID
     * @param assetCode scanned or typed asset code, at most 128 characters; it is not persisted when
     *     the observation is EXPIRED
     * @param manualReason mandatory non-blank justification whenever source is not SCAN, otherwise
     *     INVENTORY_MANUAL_REASON_REQUIRED
     * @param actual observed physical facts, always present and validated as a whole
     * @param rawScannedAt device clock reading of the tag scan; mandatory for source SCAN and, when
     *     present, must not postdate rawObservedAt by more than two seconds
     * @param rawObservedAt device clock reading of the physical sighting, always required
     * @param firstPersistedAt device clock reading when the observation was first stored locally;
     *     expiresAt must equal it plus exactly 72 hours or the observation is sent to TIME_REVIEW
     * @param expiresAt local retention deadline; once the server receive time reaches it the
     *     observation is recorded as EXPIRED and its upload scope is discarded
     * @param calibrationId previously issued clock calibration to anchor the client timestamps, or
     *     null to submit uncalibrated, which forces TIME_REVIEW
     * @param clockAnomaly client-reported clock fault; true forces TIME_REVIEW regardless of how
     *     plausible the timestamps look
     * @param photoIds attachment ids of up to nine uploaded evidence photos, which must be
     *     distinct and must all belong to this observation's upload scope
     */
    public record Observation(
            @NotNull UUID eventId,
            Long itemId,
            @PositiveOrZero long assignmentVersion,
            @PositiveOrZero long recheckGeneration,
            Long assetVersion,
            @NotNull UUID deviceId,
            @NotBlank String source,
            @Size(max = 128) String assetCode,
            @Size(max = 1000) String manualReason,
            @NotNull @Valid Facts actual,
            Instant rawScannedAt,
            @NotNull Instant rawObservedAt,
            @NotNull Instant firstPersistedAt,
            @NotNull Instant expiresAt,
            UUID calibrationId,
            boolean clockAnomaly,
            @NotNull @Size(max = 9) List<UUID> photoIds) {
        /**
         * Freezes the evidence photo list at construction time. The copy keeps the submitted
         * observation immutable, so the duplicate check, the per-photo attachment authorization and
         * the serialized facts_json all see the same ids even if the caller mutates its own list. A
         * null list is preserved as null so bean validation reports it as a missing value.
         *
         * @param photoIds attachment ids to retain; copied when non-null, left null otherwise
         */
        public Observation {
            photoIds = photoIds == null ? null : List.copyOf(photoIds);
        }

        /**
         * Replaces the generated accessor so callers always receive an immutable copy instead of
         * the Jackson-supplied list, keeping the photo ids of a stored observation read-only.
         *
         * @return immutable copy of the submitted photo ids, or null when none were supplied
         */
        public List<UUID> photoIds() {
            return photoIds == null ? null : List.copyOf(photoIds);
        }
    }
}
