package com.fams.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Request bodies of the inventory-task lifecycle routes. The nested records are the exact
 * Jackson-bound shapes accepted by {@code /api/v1/inventory-tasks}, so their component order and
 * names are wire contract: renaming one silently drops the value instead of failing validation.
 */
public final class InventoryInput {
    private InventoryInput() {}

    /**
     * Asset selection filter of one inventory task. Every component is optional and a null
     * component contributes no predicate, so an all-null range covers the whole published asset
     * source view. {@link InventoryRange#predicate} turns each non-null component into a bound
     * {@code AND column=?} clause against fams_asset_inventory_source_v1, and rejects unknown
     * lifecycle/exception codes with INVENTORY_RANGE_INVALID. The same record is serialized
     * verbatim into fams_inventory_task.range_json, so its shape is frozen by the stored column.
     *
     * @param usingOrganizationId restricts to assets currently used by this organization
     * @param owningOrganizationId restricts to assets owned by this organization
     * @param categoryId restricts to assets of this asset category
     * @param warehouseId restricts to assets whose storage warehouse is this one
     * @param locationId restricts to assets whose current location is this one
     * @param lifecycle restricts to one lifecycle_status code such as IN_STOCK or BORROWED
     * @param exception restricts to one exception_status code: NORMAL, SEARCHING or LOST
     * @param responsibleEmployeeId restricts to assets whose responsible employee is this one
     */
    public record Range(
            Long usingOrganizationId,
            Long owningOrganizationId,
            Long categoryId,
            Long warehouseId,
            Long locationId,
            String lifecycle,
            String exception,
            Long responsibleEmployeeId) {}

    /**
     * Editable definition of a not-yet-published task, shared by the create, copy and revision
     * routes. {@code InventoryService.validateDraft} re-checks what bean validation cannot: the
     * range must compile to a predicate, the stripped name must be non-blank and at most 128
     * characters, dueAt must be strictly after startsAt, and both instants must carry whole
     * milliseconds because fams_inventory_task stores them in second/millisecond-precision
     * columns. Any violation raises INVENTORY_DRAFT_INVALID.
     *
     * @param name human-readable task title, stored stripped
     * @param range asset selection filter captured verbatim into fams_inventory_task.range_json
     * @param startsAt planned window opening, in whole milliseconds
     * @param dueAt planned window closing, strictly later than startsAt and in whole milliseconds
     */
    public record Draft(
            @NotBlank @Size(max = 128) String name,
            @NotNull @Valid Range range,
            @NotNull Instant startsAt,
            @NotNull Instant dueAt) {}

    /**
     * Client attestation that the asset set about to be captured is the one the admin previewed.
     * Publishing snapshots the range into fams_inventory_item and then recomputes the digest over
     * the captured rows; if expectedCount differs from the captured count, or previewDigest
     * differs from the recomputed digest, the transaction rolls back with
     * INVENTORY_PREVIEW_CHANGED so no half-published task survives.
     *
     * @param previewDigest SHA-256 hex digest returned by the preview route the client last saw
     * @param expectedCount number of assets the preview reported, which must still match
     */
    public record Publish(@NotBlank String previewDigest, @Positive long expectedCount) {}

    /**
     * Mandatory written justification for ending a task, used by the delete, cancel and terminate
     * actions and stored in fams_inventory_task.end_reason as well as in the INVENTORY_* audit
     * event detail. The action-specific state preconditions are enforced by the service, not here.
     *
     * @param reason free-text justification, stored stripped, at most 1000 characters
     */
    public record Reason(@NotBlank @Size(max = 1000) String reason) {}

    /**
     * Executor assignment request. The service locks each referenced fams_inventory_item, requires
     * the executor account to be eligible, and writes one fams_inventory_assignment row plus the
     * participant roster entry before bumping the task version.
     *
     * @param itemIds inventory item ids to hand over, 1..500 entries, duplicates rejected with
     *     INVENTORY_DUPLICATE_ITEM
     * @param executorAccountId receiving executor account, must pass the executor eligibility
     *     port or the call fails with INVENTORY_EXECUTOR_UNAVAILABLE
     * @param recheck when true, raises each item's recheck_generation so already-observed or
     *     already-reviewed items can be counted again; required whenever the task is
     *     WAITING_REVIEW or an item already carries results, otherwise INVENTORY_RECHECK_REQUIRED
     * @param reason justification stored on every generated assignment row, at most 1000 chars
     */
    public record Assign(
            @NotNull @Size(min = 1, max = 500) List<@Positive Long> itemIds,
            @Positive long executorAccountId,
            boolean recheck,
            @NotBlank @Size(max = 1000) String reason) {
        /**
         * Replaces the generated accessor so callers always receive an immutable copy instead of
         * the Jackson-supplied list. The compact constructor already copied the field, so this
         * guards against a subclass-free but mutable list escaping through the accessor contract.
         *
         * @return immutable copy of the requested item ids, or null when none were supplied
         */
        public List<Long> itemIds() {
            return itemIds == null ? null : List.copyOf(itemIds);
        }

        /**
         * Freezes the requested item ids at construction time. The copy keeps the assignment
         * request immutable, so the eligibility checks, the per-item lock loop and the audit event
         * detail all observe the same id list even if the caller mutates its own collection. A null
         * list is preserved as null so bean validation can report it as a missing value.
         *
         * @param itemIds inventory item ids to retain; copied when non-null, left null otherwise
         */
        public Assign {
            if (itemIds != null) {
                itemIds = List.copyOf(itemIds);
            }
        }
    }
}
