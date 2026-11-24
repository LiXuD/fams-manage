package com.fams.inbound;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fams.attachment.AttachmentIntake;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Typed wire shapes of the inbound domain HTTP exits, mirroring the frozen pre-JAVA-15 row
 * projections of {@code InboundService.receipt}/{@code item}/{@code acceptance} and the
 * {@code InboundReassignmentService} snapshots. Identifier columns stringify through the same
 * {@code Long.toString}/{@code toString} calls the pre-batch maps performed; DATETIME values
 * surface as ISO instants through the pre-batch instant() semantics (Timestamp/Instant/
 * LocalDateTime driver shapes), DATE values as yyyy-MM-dd strings; conditional keys (the legacy
 * putTime/putOptional guarded puts) are frozen with {@code @JsonInclude(NON_NULL)} because the
 * global mapper (JsonConfiguration) has inclusion ALWAYS and otherwise emits them as explicit
 * nulls. Key sets, value shapes and id stringification replicate the previous Map assemblies
 * verbatim; the pre-batch HashMap/Map.of key order was never stable and stays non-contractual.
 */
public final class InboundViews {
    private InboundViews() {
    }

    /** Responses persisted in fams_idempotency_record expose the receipt resource version. */
    public sealed interface Reply permits Receipt, AcceptResult {
        /**
         * Returns the receipt resource version an idempotent replay must compare against.
         *
         * @return receipt version carried by this reply
         */
        long version();
    }

    /** Generic list page envelope: page items plus total count and the requested page parameters. */
    public record Page<T>(List<T> items, long total, int page, int pageSize) {
        /**
         * Defensively copies the item list so the published page cannot be mutated afterwards;
         * an explicit null list is preserved to keep the wire shape unchanged.
         */
        public Page {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** Account identity projection: stringified account id with the username snapshot. */
    public record Identity(String id, String username) {
    }

    /** Versioned entity reference: stringified id plus the optimistic version. */
    public record Ref(String id, long version) {
    }

    /** Frozen inbound parameter snapshot: the parameter version with the business date, review
     *  timezone and the three deadline workday budgets copied onto the receipt. */
    public record Snapshot(
            String parameterVersionId,
            String businessDate,
            String timezone,
            long adminDecisionDeadlineWorkdays,
            long warehouseClaimDeadlineWorkdays,
            long warehouseFirstExecutionDeadlineWorkdays) {
    }

    /** Asset code and name snapshot frozen onto the receipt item at creation time. */
    public record AssetSnapshot(String assetCode, String assetName) {
    }

    /** Warehouse and location snapshot pair, both projected from a {code,name} map. */
    public record TargetSnapshot(String code, String name) {
    }

    /** businessLock: conditional key, only present while the item's lock row exists. */
    public record BusinessLock(
            String assetId,
            String businessType,
            String businessId,
            String businessItemId,
            long lockVersion,
            String acquiredAt) {
    }

    /** Storage custody projection: custody type with versioned warehouse and location refs. */
    public record StorageCustody(String custodyType, Ref warehouse, Ref location) {
    }

    /** notes is a legacy putOptional guarded key (explicit presence only when the row has text). */
    public record Acceptance(
            String id,
            String receiptItemId,
            String assetId,
            Identity acceptedBy,
            String acceptedAt,
            String businessDate,
            long receiptVersionBefore,
            long assetVersionBefore,
            long assetVersionAfter,
            long lockVersion,
            String observedAssetCode,
            StorageCustody storageCustody,
            @JsonInclude(JsonInclude.Include.NON_NULL) String notes) {
    }

    /**
     * Receipt line projection: execution status, asset and target references with their frozen
     * snapshots, plus the conditional businessLock and acceptance keys (NON_NULL-guarded legacy
     * puts).
     */
    public record Item(
            String id,
            String executionStatus,
            Ref asset,
            AssetSnapshot assetSnapshot,
            Ref targetWarehouse,
            Ref targetLocation,
            TargetSnapshot warehouseSnapshot,
            TargetSnapshot locationSnapshot,
            @JsonInclude(JsonInclude.Include.NON_NULL) BusinessLock businessLock,
            @JsonInclude(JsonInclude.Include.NON_NULL) Acceptance acceptance) {
    }

    /** One approved line of a decision scope: the receipt item with its approved asset, warehouse
     *  and location references and versions. */
    public record ScopeItem(String receiptItemId, Ref asset, Ref targetWarehouse, Ref targetLocation) {
    }

    /** Decision scope: the receipt version the decision was made against plus its approved lines. */
    public record Scope(long receiptVersion, List<ScopeItem> items) {
        /**
         * Defensively copies the scope item list so the published scope cannot be mutated
         * afterwards; an explicit null list is preserved to keep the wire shape unchanged.
         */
        public Scope {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** comment is a legacy putOptional guarded key inside the decision object. */
    public record Decision(
            String id,
            String result,
            long decidedReceiptVersion,
            Identity decidedBy,
            String decidedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String comment,
            Scope scope) {
    }

    /** archivedAt is a legacy putTime guarded key inside the currentProcessor object. */
    public record CurrentProcessor(
            Identity processor,
            String claimedAt,
            long ownershipVersion,
            @JsonInclude(JsonInclude.Include.NON_NULL) String archivedAt) {
    }

    /**
     * factoryReplacement origin projection. sourceId stays the raw JDBC value: the pre-batch map
     * put it through untouched (no toString), so the driver shape must not be normalised here.
     */
    public record FactoryReplacement(
            Object sourceId,
            String originalAssetId,
            String replacementAssetId,
            String repairId,
            String roundId) {
    }

    /**
     * get/create/update/submit/cancel/decision/claim/reassign body. Every putTime/putOptional
     * guarded column of the pre-batch HashMap is a NON_NULL component; the remaining components
     * were always put and stay explicitly present, nulls included.
     */
    public record Receipt(
            String id,
            String receiptNumber,
            String status,
            String businessDate,
            Identity createdBy,
            String createdAt,
            long version,
            String parameterVersionId,
            Snapshot snapshot,
            List<Item> items,
            @JsonInclude(JsonInclude.Include.NON_NULL) FactoryReplacement factoryReplacement,
            @JsonInclude(JsonInclude.Include.NON_NULL) String submittedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String adminDecisionDueAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String warehouseClaimDueAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String warehouseFirstExecutionDueAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String cancelledAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String completedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String notes,
            @JsonInclude(JsonInclude.Include.NON_NULL) String cancellationReason,
            @JsonInclude(JsonInclude.Include.NON_NULL) Decision decision,
            @JsonInclude(JsonInclude.Include.NON_NULL) CurrentProcessor currentProcessor)
            implements Reply {
        /**
         * Defensively copies the item list so the published receipt cannot be mutated afterwards;
         * an explicit null list is preserved to keep the wire shape unchanged.
         */
        public Receipt {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** Asset state projected after accept: lifecycle/exception/cardStage plus current custody. */
    public record AcceptedAsset(
            String id,
            String assetCode,
            String lifecycle,
            String exception,
            String cardStage,
            long version,
            StorageCustody currentCustody,
            String inboundBusinessDate) {
    }

    /** accept exit body: the three always-present receipt/item/asset keys. */
    public record AcceptResult(Receipt receipt, Item item, AcceptedAsset asset) implements Reply {
        @Override
        public long version() {
            return receipt.version();
        }
    }

    /**
     * Reassignment inspect line: receipt item identity and status with the asset, warehouse and
     * location versions an executor must match when reassigning.
     */
    public record ReassignmentItem(
            String id,
            long version,
            String status,
            long assetVersion,
            long lockVersion,
            String assetCode,
            String warehouseId,
            String locationId) {
    }

    /** Candidate reassignment target operator: account id and display name. */
    public record Operator(String id, String name) {
    }

    /**
     * Reassignment inspect exit: unavailableReason is a conditional key — the pre-batch
     * LinkedHashMap only put it when the current ownership had declared unavailability
     * (global inclusion is ALWAYS, so NON_NULL freezes it, JAVA-14c precedent).
     */
    public record ReassignmentSnapshot(
            String id,
            long version,
            long ownershipVersion,
            String processorAccountId,
            List<ReassignmentItem> items,
            List<Operator> operators,
            @JsonInclude(JsonInclude.Include.NON_NULL) String unavailableReason) {
        /**
         * Defensively copies the item and operator lists so the published snapshot cannot be
         * mutated afterwards; explicit null lists are preserved to keep the wire shape unchanged.
         */
        public ReassignmentSnapshot {
            items = items == null ? null : List.copyOf(items);
            operators = operators == null ? null : List.copyOf(operators);
        }
    }

    /** declareUnavailable exit and stored receipt: the two pre-batch Map.of keys. */
    public record UnavailabilityResult(String receiptId, boolean recorded) {
    }

    /** upload/correct 201 receipt: six keys exactly as the pre-batch Map.of assembly. */
    public record AttachmentReceipt(
            String objectId,
            String filename,
            String contentType,
            long sizeBytes,
            String scanStatus,
            long businessVersion) {
    }

    /** Envelope of the attachment list exit (pre-batch {@code Map.of("items", ...)}). */
    public record AttachmentListPage(List<AttachmentEvidence> items) {
        /**
         * Defensively copies the evidence list so the published page cannot be mutated
         * afterwards; an explicit null list is preserved to keep the wire shape unchanged.
         */
        public AttachmentListPage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /**
     * Alias row of {@code AttachmentReadService.list} for INBOUND. createdAt keeps the raw JDBC
     * value (Timestamp or LocalDateTime across drivers), mirroring the ProxyConfirmationViews
     * AttachmentEvidence precedent; the three revision keys are nullable and stay explicitly
     * present on the wire, as the queryForList map always carried them.
     */
    public record AttachmentEvidence(
            String objectId,
            String filename,
            String contentType,
            long sizeBytes,
            String scanStatus,
            long businessVersion,
            Object createdAt,
            long revisionNumber,
            String previousObjectId,
            String correctionReason,
            String nextObjectId) {
    }

    /** Download grant: the AttachmentReadService.grant map's two keys. */
    public record DownloadGrant(String url, long expiresAtEpochSecond) {
    }

    static Identity identity(long accountId, String username) {
        return new Identity(Long.toString(accountId), username);
    }

    static Ref ref(long id, long version) {
        return new Ref(Long.toString(id), version);
    }

    static StorageCustody storage(long warehouseId, long warehouseVersion,
            long locationId, long locationVersion) {
        return new StorageCustody("STORAGE", ref(warehouseId, warehouseVersion),
                ref(locationId, locationVersion));
    }

    /** Receipt document assembly; the caller loads every row in the frozen pre-batch read order. */
    static Receipt receipt(Map<String, Object> row, List<Item> items, Map<String, Object> origin,
            Map<String, Object> decision, List<Map<String, Object>> scopeRows,
            Map<String, Object> owner) {
        String parameterVersionId = Long.toString(num(row, "parameter_version_id"));
        String businessDate = date(row, "business_date");
        return new Receipt(
                Long.toString(num(row, "id")),
                text(row, "receipt_no"),
                text(row, "receipt_status"),
                businessDate,
                identity(num(row, "creator_account_id"), text(row, "creator_username_snapshot")),
                time(row, "created_at"),
                num(row, "version"),
                parameterVersionId,
                new Snapshot(parameterVersionId, businessDate, text(row, "timezone_snapshot"),
                        num(row, "admin_decision_deadline_workdays"),
                        num(row, "warehouse_claim_deadline_workdays"),
                        num(row, "warehouse_first_execution_deadline_workdays")),
                items,
                origin == null ? null : new FactoryReplacement(origin.get("source_id"),
                        origin.get("original_asset_id").toString(),
                        origin.get("asset_id").toString(),
                        origin.get("repair_id").toString(),
                        origin.get("round_id").toString()),
                time(row, "submitted_at"),
                time(row, "admin_decision_due_at"),
                time(row, "warehouse_claim_due_at"),
                time(row, "warehouse_first_execution_due_at"),
                time(row, "cancelled_at"),
                time(row, "completed_at"),
                row.get("notes") == null ? null : text(row, "notes"),
                row.get("cancellation_reason") == null ? null : text(row, "cancellation_reason"),
                decision == null ? null : decision(decision, scopeRows),
                owner == null ? null : new CurrentProcessor(
                        identity(num(owner, "processor_account_id"),
                                text(owner, "processor_username_snapshot")),
                        time(owner, "acquired_at"),
                        num(owner, "ownership_version"),
                        time(owner, "archived_at")));
    }

    /** Item row with its optional lock/acceptance rows; both were guarded conditional puts. */
    static Item item(Map<String, Object> row, Map<String, Object> lock,
            Map<String, Object> acceptance) {
        return new Item(
                Long.toString(num(row, "id")),
                text(row, "execution_status"),
                ref(num(row, "asset_id"), num(row, "asset_version_snapshot")),
                new AssetSnapshot(text(row, "asset_code_snapshot"), text(row, "asset_name_snapshot")),
                ref(num(row, "target_warehouse_id"), num(row, "warehouse_version_snapshot")),
                ref(num(row, "target_location_id"), num(row, "location_version_snapshot")),
                new TargetSnapshot(text(row, "warehouse_code_snapshot"),
                        text(row, "warehouse_name_snapshot")),
                new TargetSnapshot(text(row, "location_code_snapshot"),
                        text(row, "location_name_snapshot")),
                lock == null ? null : new BusinessLock(
                        Long.toString(num(lock, "asset_id")),
                        "INBOUND",
                        Long.toString(num(lock, "business_id")),
                        Long.toString(num(lock, "business_item_id")),
                        num(lock, "lock_version"),
                        time(lock, "acquired_at")),
                acceptance == null ? null : acceptance(acceptance));
    }

    static Acceptance acceptance(Map<String, Object> row) {
        return new Acceptance(
                Long.toString(num(row, "id")),
                Long.toString(num(row, "receipt_item_id")),
                Long.toString(num(row, "asset_id")),
                identity(num(row, "accepted_by_account_id"),
                        text(row, "accepted_by_username_snapshot")),
                time(row, "accepted_at"),
                date(row, "business_date"),
                num(row, "receipt_version_before"),
                num(row, "asset_version_before"),
                num(row, "asset_version_after"),
                num(row, "lock_version"),
                text(row, "observed_asset_code"),
                storage(num(row, "storage_warehouse_id"), num(row, "warehouse_version_at_acceptance"),
                        num(row, "storage_location_id"), num(row, "location_version_at_acceptance")),
                row.get("notes") == null ? null : text(row, "notes"));
    }

    static ScopeItem scope(Map<String, Object> row) {
        return new ScopeItem(
                Long.toString(num(row, "receipt_item_id")),
                ref(num(row, "approved_asset_id"), num(row, "approved_asset_version")),
                ref(num(row, "approved_warehouse_id"), num(row, "approved_warehouse_version")),
                ref(num(row, "approved_location_id"), num(row, "approved_location_version")));
    }

    static Decision decision(Map<String, Object> row, List<Map<String, Object>> scopeRows) {
        return new Decision(
                Long.toString(num(row, "id")),
                text(row, "decision_result"),
                num(row, "receipt_version_before"),
                identity(num(row, "decided_by_account_id"), text(row, "decided_by_username_snapshot")),
                time(row, "decided_at"),
                row.get("comment_text") == null ? null : text(row, "comment_text"),
                new Scope(num(row, "receipt_version_before"),
                        scopeRows.stream().map(InboundViews::scope).toList()));
    }

    static AcceptedAsset acceptedAsset(Map<String, Object> asset, Map<String, Object> warehouse,
            Map<String, Object> location, long assetId) {
        return new AcceptedAsset(
                Long.toString(assetId),
                text(asset, "asset_code"),
                text(asset, "lifecycle_status"),
                text(asset, "exception_status"),
                text(asset, "card_stage"),
                num(asset, "version"),
                storage(num(asset, "storage_warehouse_id"), num(warehouse, "version"),
                        num(asset, "current_location_id"), num(location, "version")),
                date(asset, "inbound_business_date"));
    }

    /** One frozen row of InboundReassignmentService.checks (already camel-keyed by the query). */
    static ReassignmentItem reassignmentItem(Map<String, Object> check) {
        return new ReassignmentItem(
                (String) check.get("id"),
                num(check, "version"),
                (String) check.get("status"),
                num(check, "assetVersion"),
                num(check, "lockVersion"),
                (String) check.get("assetCode"),
                (String) check.get("warehouseId"),
                (String) check.get("locationId"));
    }

    static Operator operator(Map<String, Object> row) {
        return new Operator(row.get("id").toString(), (String) row.get("name"));
    }

    static AttachmentEvidence attachmentEvidence(Map<String, Object> row) {
        return new AttachmentEvidence(
                (String) row.get("objectId"),
                (String) row.get("filename"),
                (String) row.get("contentType"),
                ((Number) row.get("sizeBytes")).longValue(),
                (String) row.get("scanStatus"),
                ((Number) row.get("businessVersion")).longValue(),
                row.get("createdAt"),
                ((Number) row.get("revisionNumber")).longValue(),
                (String) row.get("previousObjectId"),
                (String) row.get("correctionReason"),
                (String) row.get("nextObjectId"));
    }

    static AttachmentReceipt attachmentReceipt(AttachmentIntake.QuarantinedAttachment result,
            String filename, long version) {
        return new AttachmentReceipt(
                result.file().id().toString(),
                filename,
                result.format().mime(),
                result.file().size(),
                result.scanResult().name(),
                version + 1);
    }

    private static long num(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    /** Pre-batch InboundService.putTime: absent guarded key when the column is null. */
    private static String time(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : instant(value).toString();
    }

    /** Pre-batch InboundService.instant(): every driver shape surfaces as the same ISO instant. */
    private static Instant instant(Object value) {
        if (value instanceof Timestamp stamp) {
            return stamp.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toInstant(ZoneOffset.UTC);
        }
        return Instant.parse(value.toString());
    }

    /** Pre-batch InboundService.localDate(value).toString(): yyyy-MM-dd for every DATE shape. */
    private static String date(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof java.sql.Date day) {
            return day.toLocalDate().toString();
        }
        if (value instanceof LocalDate day) {
            return day.toString();
        }
        return LocalDate.parse(value.toString()).toString();
    }
}
