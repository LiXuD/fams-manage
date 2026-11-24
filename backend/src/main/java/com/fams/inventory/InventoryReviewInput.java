package com.fams.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request bodies of the batch normal-review route, where an asset admin accepts several
 * movement-free items in one call. The nested records are the exact Jackson-bound shapes of
 * {@code POST /api/v1/inventory-reviews/tasks/{task}/normal}, and every numeric component is an
 * optimistic-lock value the client must echo back unmodified.
 */
public final class InventoryReviewInput {
    private InventoryReviewInput() {}

    /**
     * Per-item optimistic-lock set of one batch entry. The service compares these four values
     * against the locked fams_inventory_item, its existing fams_inventory_item_review row and the
     * live asset card; a mismatch marks only this entry as CONFLICT in the receipt and leaves the
     * rest of the batch intact.
     *
     * @param itemId fams_inventory_item id, which must belong to the task in the path or the entry
     *     is answered with INVENTORY_ITEM_NOT_FOUND
     * @param recheckGeneration recheck generation the client read on the item, zero for the first
     *     assignment round; a mismatch yields INVENTORY_REVIEW_VERSION_CONFLICT
     * @param reviewVersion fams_inventory_item_review version the client read, which must be zero
     *     because the batch only accepts items that have no review row yet
     * @param assetVersion asset card version the client read, verified through
     *     {@code AssetInventoryReviewPort.matchesVersion}; a stale value yields
     *     INVENTORY_REVIEW_ASSET_CHANGED
     */
    public record Item(@Positive long itemId, @PositiveOrZero long recheckGeneration,
            @PositiveOrZero long reviewVersion, @PositiveOrZero long assetVersion) {}

    /**
     * One batch normal-acceptance request. The task version itself travels in the If-Match-Version
     * header, not here; a stale task version or a task that is not IN_PROGRESS or WAITING_REVIEW
     * rejects the whole batch with INVENTORY_REVIEW_TASK_CHANGED, while repeated item ids fail with
     * INVENTORY_REVIEW_ITEM_DUPLICATE. Items are processed in ascending asset id order so that
     * overlapping reviews in different tasks lock assets in the same sequence.
     *
     * @param items batch entries, 1..50, each carrying its own lock values; entries are sorted by
     *     asset id before being locked
     * @param reason justification shared by every accepted entry, at most 1000 characters, stored
     *     on the review row, the NORMAL review subject and the audit event
     */
    public record NormalBatch(@NotNull @Size(min = 1, max = 50) List<@Valid Item> items,
            @NotBlank @Size(max = 1000) String reason) {
        /**
         * Freezes the batch entry list at construction time. The copy keeps the request immutable,
         * so the duplicate-id check, the asset-id ordering and the per-entry loop all iterate the
         * same entries even if the caller mutates its own list. A null list is preserved as null so
         * bean validation reports it as a missing value.
         *
         * @param items batch entries to retain; copied when non-null, left null otherwise
         */
        public NormalBatch {
            items = items == null ? null : List.copyOf(items);
        }
    }
}
