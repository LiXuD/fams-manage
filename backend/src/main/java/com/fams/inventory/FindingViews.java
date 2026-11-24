package com.fams.inventory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.fams.attachment.AttachmentEvidencePort;
import com.fams.asset.AssetInventoryReviewPort;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed wire shapes of the inventory finding/review/photo HTTP exits (JAVA-17c), mirroring the
 * frozen pre-refactor Map assemblies of {@code InventoryFindingService}, {@code
 * InventoryReviewService}, {@code InventoryPhotoService} and the two photo read controllers.
 *
 * <p>Two projections coexist in this domain and must not be conflated:
 *
 * <ul>
 *   <li>{@code InventoryFindingService.wire(row)} is flat: it parses only {@code facts_json}/
 *       {@code receipt_json}, stringifies Number values under {@code id}/{@code *_id} and turns the
 *       Timestamp/LocalDateTime driver shapes into ISO instants. Nested JSON trees stay raw.
 *   <li>{@code InventoryReviewService.wire(value)} is recursive over maps and lists: it parses
 *       every {@code *_json} string child, stringifies Number values under {@code id}/{@code *_id}
 *       <em>and</em> the camel {@code *Id} keys (which only ever fire inside {@code facts_json} and
 *       the asset snapshot), and normalises DATETIME values at every depth.
 * </ul>
 *
 * The same {@code fams_inventory_observation} row therefore serialises differently through the two
 * exits ({@code facts_json.itemId} is a number for findings and a string for reviews), so each row
 * record carries two factories and the wire-shape oracle freezes both.
 *
 * <p>Column key sets follow the frozen {@code SELECT *} order of the migrated schema
 * (V31/V32/V33/V34/V37/V42). Every key the legacy Map always put stays a default-inclusion
 * component (the global mapper uses {@code JsonInclude.Include.ALWAYS}, so explicit nulls
 * serialize); every key the legacy code put only under a guard carries {@code NON_NULL}.
 */
public final class FindingViews {
    private FindingViews() {
    }

    // ==================== finding targets (GET finding-targets) ====================

    /**
     * One candidate the admin may bind a finding to. The legacy exit used two different
     * {@code Map.of} assemblies: FORMAL_INBOUND carried {@code inboundReceiptId/receiptNumber/
     * status}, DUPLICATE_ASSET carried {@code assetName}; the three-or-one keys the other kind never
     * put stay {@code NON_NULL}.
     */
    public record Target(
            String kind,
            String assetId,
            String assetCode,
            long ownerVersion,
            @JsonInclude(JsonInclude.Include.NON_NULL) String assetName,
            @JsonInclude(JsonInclude.Include.NON_NULL) String inboundReceiptId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String receiptNumber,
            @JsonInclude(JsonInclude.Include.NON_NULL) String status) {
    }

    /** Envelope of the finding-targets exit (legacy {@code Map.of("items", ...)}). */
    public record FindingTargets(List<Target> items) {
        /**
         * Copies the candidate list of the finding-targets envelope. Null stays null rather than an
         * empty list, which keeps the ALWAYS-included key distinguishable from the "no candidate
         * matched this code or receipt number" case the exit really emits as an empty list.
         *
         * @param items formal-inbound or duplicate-asset candidates the admin may bind the finding
         *     to, or null when the envelope was not projected
         */
        public FindingTargets {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** Formal inbound candidate read through the typed {@code InventoryInboundPort.Context}. */
    public static Target formalInboundTarget(
            String assetId, String assetCode, long ownerVersion, String inboundReceiptId,
            String receiptNumber, String status) {
        return new Target(
                "FORMAL_INBOUND", assetId, assetCode, ownerVersion, null, inboundReceiptId,
                receiptNumber, status);
    }

    /** Duplicate asset card candidate read from the current asset snapshot. */
    public static Target duplicateAssetTarget(
            String assetId, String assetCode, String assetName, long ownerVersion) {
        return new Target(
                "DUPLICATE_ASSET", assetId, assetCode, ownerVersion, assetName, null, null, null);
    }

    // ==================== frozen row projections ====================

    /**
     * fams_inventory_review_subject row (13 columns) under the flat finding wire.
     * observation_id/task_id/item_id/reviewer_account_id stringify; recheck_generation and version
     * stay numeric; the six nullable review columns stay explicitly present.
     */
    public record SubjectRow(
            @JsonProperty("observation_id") String observationId,
            @JsonProperty("dimension") String dimension,
            @JsonProperty("task_id") String taskId,
            @JsonProperty("item_id") String itemId,
            @JsonProperty("recheck_generation") long recheckGeneration,
            @JsonProperty("subject_status") String subjectStatus,
            @JsonProperty("method") String method,
            @JsonProperty("reason") String reason,
            @JsonProperty("evidence") String evidence,
            @JsonProperty("reviewer_account_id") String reviewerAccountId,
            @JsonProperty("reviewer_username") String reviewerUsername,
            @JsonProperty("reviewed_at") String reviewedAt,
            @JsonProperty("version") long version)
            implements SubjectProjection {
    }

    /**
     * The finding detail put {@code Map.of()} when no FINDING-dimension subject row existed, so the
     * empty state is a JSON object with zero keys, not an all-null row.
     */
    public sealed interface SubjectProjection permits SubjectRow, NoSubject {
    }

    /** Empty-subject state of the finding detail exit. */
    public record NoSubject() implements SubjectProjection {
    }

    /**
     * fams_inventory_observation row (24 columns). facts_json/receipt_json keep whatever projection
     * the consuming exit uses: see {@link #observation} (flat, findings) and
     * {@link #reviewObservation} (recursive, reviews).
     */
    public record ObservationRow(
            @JsonProperty("id") String id,
            @JsonProperty("task_id") String taskId,
            @JsonProperty("client_event_id") String clientEventId,
            @JsonProperty("actor_account_id") String actorAccountId,
            @JsonProperty("binding_id") String bindingId,
            @JsonProperty("device_id") String deviceId,
            @JsonProperty("item_id") String itemId,
            @JsonProperty("assignment_version") long assignmentVersion,
            @JsonProperty("recheck_generation") long recheckGeneration,
            @JsonProperty("snapshot_asset_version") Long snapshotAssetVersion,
            @JsonProperty("source") String source,
            @JsonProperty("asset_code") String assetCode,
            @JsonProperty("raw_observed_at") String rawObservedAt,
            @JsonProperty("first_persisted_at") String firstPersistedAt,
            @JsonProperty("client_expires_at") String clientExpiresAt,
            @JsonProperty("calibrated_observed_at") String calibratedObservedAt,
            @JsonProperty("calibrated_persisted_at") String calibratedPersistedAt,
            @JsonProperty("calibration_id") String calibrationId,
            @JsonProperty("received_at") String receivedAt,
            @JsonProperty("facts_json") JsonNode factsJson,
            @JsonProperty("payload_sha256") String payloadSha256,
            @JsonProperty("fact_sha256") String factSha256,
            @JsonProperty("outcome") String outcome,
            @JsonProperty("receipt_json") JsonNode receiptJson) {
    }

    /**
     * fams_inventory_review_decision row joined to fams_inventory_finding_link
     * ({@code SELECT d.*, CAST(...) AS assetId, CAST(...) AS inboundReceiptId, l.link_kind,
     * l.owner_version}). The two CAST aliases arrive as strings and the link columns are null on a
     * decision without a link.
     */
    public record DecisionHistoryRow(
            @JsonProperty("id") String id,
            @JsonProperty("task_id") String taskId,
            @JsonProperty("observation_id") String observationId,
            @JsonProperty("dimension") String dimension,
            @JsonProperty("before_version") long beforeVersion,
            @JsonProperty("after_version") long afterVersion,
            @JsonProperty("method") String method,
            @JsonProperty("subject_status") String subjectStatus,
            @JsonProperty("reason") String reason,
            @JsonProperty("evidence") String evidence,
            @JsonProperty("reviewed_asset_version") long reviewedAssetVersion,
            @JsonProperty("actor_account_id") String actorAccountId,
            @JsonProperty("actor_username") String actorUsername,
            @JsonProperty("occurred_at") String occurredAt,
            @JsonProperty("assetId") String assetId,
            @JsonProperty("inboundReceiptId") String inboundReceiptId,
            @JsonProperty("link_kind") String linkKind,
            @JsonProperty("owner_version") Long ownerVersion) {
    }

    /** fams_inventory_review_decision row joined to fams_inventory_finding_recollection. */
    public record DecisionRecollectionRow(
            @JsonProperty("id") String id,
            @JsonProperty("task_id") String taskId,
            @JsonProperty("observation_id") String observationId,
            @JsonProperty("dimension") String dimension,
            @JsonProperty("before_version") long beforeVersion,
            @JsonProperty("after_version") long afterVersion,
            @JsonProperty("method") String method,
            @JsonProperty("subject_status") String subjectStatus,
            @JsonProperty("reason") String reason,
            @JsonProperty("evidence") String evidence,
            @JsonProperty("reviewed_asset_version") long reviewedAssetVersion,
            @JsonProperty("actor_account_id") String actorAccountId,
            @JsonProperty("actor_username") String actorUsername,
            @JsonProperty("occurred_at") String occurredAt,
            @JsonProperty("counterObservationId") String counterObservationId,
            @JsonProperty("counter_observed_at") String counterObservedAt) {
    }

    /** fams_inventory_task row (21 columns) under the recursive review wire. */
    public record TaskRow(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("task_status") String taskStatus,
            @JsonProperty("range_json") JsonNode rangeJson,
            @JsonProperty("starts_at") String startsAt,
            @JsonProperty("due_at") String dueAt,
            @JsonProperty("timezone_id") String timezoneId,
            @JsonProperty("parameter_version_id") String parameterVersionId,
            @JsonProperty("expected_count") long expectedCount,
            @JsonProperty("captured_result_count") long capturedResultCount,
            @JsonProperty("assignment_version") long assignmentVersion,
            @JsonProperty("version") long version,
            @JsonProperty("creator_account_id") String creatorAccountId,
            @JsonProperty("publisher_account_id") String publisherAccountId,
            @JsonProperty("copied_from_task_id") String copiedFromTaskId,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt,
            @JsonProperty("published_at") String publishedAt,
            @JsonProperty("started_at") String startedAt,
            @JsonProperty("ended_at") String endedAt,
            @JsonProperty("end_reason") String endReason) {
    }

    /** fams_inventory_item row (9 columns) under the recursive review wire. */
    public record ItemRow(
            @JsonProperty("id") String id,
            @JsonProperty("task_id") String taskId,
            @JsonProperty("asset_id") String assetId,
            @JsonProperty("asset_version") long assetVersion,
            @JsonProperty("snapshot_json") JsonNode snapshotJson,
            @JsonProperty("executor_account_id") String executorAccountId,
            @JsonProperty("assignment_version") long assignmentVersion,
            @JsonProperty("result_count") long resultCount,
            @JsonProperty("recheck_generation") long recheckGeneration) {
    }

    /**
     * fams_inventory_item_review row (13 columns) under the recursive review wire. observation_id
     * and verified_observed_at are null for NOT_OBSERVED reviews and stay explicitly present.
     */
    public record ItemReviewRow(
            @JsonProperty("item_id") String itemId,
            @JsonProperty("recheck_generation") long recheckGeneration,
            @JsonProperty("task_id") String taskId,
            @JsonProperty("observation_id") String observationId,
            @JsonProperty("review_status") String reviewStatus,
            @JsonProperty("verified_observed_at") String verifiedObservedAt,
            @JsonProperty("reviewed_asset_version") long reviewedAssetVersion,
            @JsonProperty("reason") String reason,
            @JsonProperty("evidence") String evidence,
            @JsonProperty("reviewer_account_id") String reviewerAccountId,
            @JsonProperty("reviewer_username") String reviewerUsername,
            @JsonProperty("reviewed_at") String reviewedAt,
            @JsonProperty("version") long version)
            implements ReviewState {
    }

    /**
     * The review exits put {@code Map.of("version", 0)} while an item carries no review row; that
     * single-key object is a state of the same union, not an ItemReviewRow with dropped nulls.
     */
    public sealed interface ReviewState permits ItemReviewRow, InitialReview {
    }

    /** Pre-review state of one item (the legacy {@code {"version":0}} object). */
    public record InitialReview(long version) implements ReviewState {
    }

    /** Review-state union for the GET review detail (the stored batch receipt keeps a tree). */
    public static ReviewState reviewState(ObjectMapper json, Map<String, Object> row) {
        return row == null ? new InitialReview(0) : itemReview(json, row);
    }

    /**
     * Same review-state union as a tree. The normal-acceptance document is stored in
     * {@code fams_inventory_review_receipt.response_json} and replayed into
     * {@link NormalBatchItem}, whose union component has to stay deserialisable, so that exit keeps
     * the tree while {@link #reviewState} types the GET detail surface. Both paths share
     * {@link #itemReview}/{@link InitialReview}, and the wire-shape suite asserts the two
     * serialisations byte-for-byte.
     */
    public static JsonNode reviewNode(ObjectMapper json, Map<String, Object> row) {
        return json.valueToTree(reviewState(json, row));
    }

    // ==================== finding exits ====================

    /**
     * GET /inventory-reviews/tasks/{task}/findings/{observation}. {@code subject} is the empty/full
     * union; {@code taskVersion} keeps the JDBC task version as a number and {@code historyTotal}
     * the COUNT row.
     */
    public record FindingDetail(
            List<SubjectRow> timeSubject,
            List<DecisionRecollectionRow> recollections,
            long taskVersion,
            ObservationRow observation,
            SubjectProjection subject,
            List<DecisionHistoryRow> history,
            long historyTotal) {
        /**
         * Copies the three list segments of the finding detail body. The copy freezes the read order
         * the exit documents (subject, history, TIME subjects, recollections, history count) against
         * any later mutation of the query results.
         *
         * @param timeSubject fams_inventory_review_subject rows of the TIME dimension, or null when
         *     absent
         * @param recollections recollection decisions joined to their counter observation, in
         *     descending decision_version order and capped at 100, or null when absent
         * @param history FINDING decisions with their finding-link columns, in descending
         *     after_version order and capped at 100, or null when absent
         */
        public FindingDetail {
            timeSubject = timeSubject == null ? null : List.copyOf(timeSubject);
            recollections = recollections == null ? null : List.copyOf(recollections);
            history = history == null ? null : List.copyOf(history);
        }
    }

    /**
     * Items page of both observation-list exits (recollection targets and the review observation
     * list): legacy {@code Map.of("items", ..., "total", ..., "page", ..., "pageSize", ...)}.
     */
    public record ObservationPage(
            List<ObservationRow> items, long total, int page, int pageSize) {
        /**
         * Copies the observation rows of the frozen items page. Null stays null rather than an empty
         * list, keeping the ALWAYS-included key distinguishable from a projected but empty page.
         *
         * @param items observation rows of this page in ascending observation id order, or null when
         *     the page was not projected
         */
        public ObservationPage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /**
     * decide exit and its fams_inventory_review_receipt.response_json shape. The legacy assembly
     * was a LinkedHashMap in exactly this order, so new stored rows keep the byte order.
     */
    public record DecisionReceipt(
            String taskId, String observationId, long version, long subjectVersion, String status) {
    }

    /** recollect exit and its stored receipt shape (legacy Map.of order was never stable). */
    public record RecollectionReceipt(
            String taskId,
            String observationId,
            long version,
            long subjectVersion,
            String status,
            String method,
            String counterObservationId,
            String originalTimeConfidence) {
    }

    // ==================== review exits ====================

    /**
     * One item row of the review detail. {@code current} is the arbitrary asset snapshot (already
     * review-wired) and {@code review} the review-state union; {@code blockedReason} is an
     * always-present explicit null while the item is acceptable.
     */
    public record ReviewTaskItem(
            ItemRow item,
            JsonNode current,
            List<AssetInventoryReviewPort.Movement> movementEvents,
            ObservationRow candidate,
            ReviewState review,
            boolean canAcceptNormal,
            String blockedReason) {
        /**
         * Copies the movement events of this review item. An item whose asset context could not be
         * resolved is projected with an empty list rather than null, so a null here means the row was
         * built outside the review detail exit.
         *
         * @param movementEvents period movements of the asset behind the item's snapshot, or null
         *     when the segment was not projected
         */
        public ReviewTaskItem {
            movementEvents = movementEvents == null ? null : List.copyOf(movementEvents);
        }
    }

    /** GET /inventory-reviews/tasks/{task} document. */
    public record ReviewTaskDetail(
            TaskRow task,
            List<ReviewTaskItem> items,
            long total,
            int page,
            int pageSize,
            long reviewedCount) {
        /**
         * Copies the review item page of the frozen document. Null stays null rather than an empty
         * list, keeping the ALWAYS-included key distinguishable from a projected but empty page.
         *
         * @param items review items of this page in ascending asset_id order, or null when the page
         *     was not projected
         */
        public ReviewTaskDetail {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /**
     * One outcome line of the normal-acceptance batch. The legacy exit produced two different
     * {@code Map.of} shapes (accepted: {@code itemId/status/review}; conflict:
     * {@code itemId/status/code/current}), so the three mutually exclusive keys stay conditional.
     * {@code review}/{@code current} carry the stored review-state union as a tree because this
     * document is replayed from {@code response_json}.
     */
    public record NormalBatchItem(
            String itemId,
            String status,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode review,
            @JsonInclude(JsonInclude.Include.NON_NULL) String code,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode current) {
    }

    /** acceptNormal exit and its stored receipt shape. */
    public record NormalBatchReceipt(
            String taskId,
            long version,
            int accepted,
            int conflicts,
            List<NormalBatchItem> items,
            long reviewedCount) {
        /**
         * Copies the per-item outcomes into the receipt that is replayed from
         * fams_inventory_review_receipt.response_json, so an ACCEPT_NORMAL retry must return the
         * identical outcome list. Null stays null rather than becoming an empty list.
         *
         * @param items one outcome per requested item in the stable asset_id order the batch locked
         *     them in, or null when absent
         */
        public NormalBatchReceipt {
            items = items == null ? null : List.copyOf(items);
        }
    }

    // ==================== photo exits ====================

    /**
     * upload exit (both the fresh registration and the exact-replay branch): the legacy two-key
     * {@code Map.of}. {@code objectId} is a CHAR(36) column read straight from the receipt row.
     */
    public record PhotoReceipt(String objectId, String photoId) {
    }

    /**
     * Observation photo list envelope. Rows keep the frozen {@code InventoryPhotoReadService.Photo}
     * projection (its raw {@code createdAt} passthrough is asserted by the attachment wire-shape
     * suite, so the record stays where that test can reach it).
     */
    public record PhotoList(List<InventoryPhotoReadService.Photo> items) {
        /**
         * Copies the photo rows of the observation list envelope. Null stays null rather than an
         * empty list, so the ALWAYS-included key keeps telling an unprojected envelope apart from an
         * INVENTORY_EVENT scope that holds no photo.
         *
         * @param items frozen photo projections of the authorized upload scope, or null when the
         *     envelope was not projected
         */
        public PhotoList {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /**
     * Repair-evidence photo list envelope. The legacy controller passed the
     * {@code AttachmentEvidencePort.Metadata} rows through untouched, so {@code createdAt} stays the
     * raw JDBC value (Timestamp or LocalDateTime across drivers).
     */
    public record RepairPhotoList(List<AttachmentEvidencePort.Metadata> items) {
        /**
         * Copies the metadata rows of the repair-evidence list envelope. Null stays null rather than
         * an empty list, so the ALWAYS-included key keeps telling an unprojected envelope apart from
         * a repair whose evidence lists no photo id.
         *
         * @param items attachment metadata rows filtered to the repair evidence photo ids, or null
         *     when the envelope was not projected
         */
        public RepairPhotoList {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** Download-grant envelope of both photo controllers. */
    public record DownloadGrant(String url, long expiresAtEpochSecond) {
    }

    // ==================== factories ====================

    /** Flat finding wire of one fams_inventory_observation row (JSON trees stay raw). */
    public static ObservationRow observation(ObjectMapper json, Map<String, Object> row) {
        return new ObservationRow(
                text(row, "id"),
                text(row, "task_id"),
                text(row, "client_event_id"),
                text(row, "actor_account_id"),
                text(row, "binding_id"),
                text(row, "device_id"),
                text(row, "item_id"),
                num(row, "assignment_version"),
                num(row, "recheck_generation"),
                boxed(row, "snapshot_asset_version"),
                text(row, "source"),
                text(row, "asset_code"),
                time(row, "raw_observed_at"),
                time(row, "first_persisted_at"),
                time(row, "client_expires_at"),
                time(row, "calibrated_observed_at"),
                time(row, "calibrated_persisted_at"),
                text(row, "calibration_id"),
                time(row, "received_at"),
                node(json, row, "facts_json"),
                text(row, "payload_sha256"),
                text(row, "fact_sha256"),
                text(row, "outcome"),
                node(json, row, "receipt_json"));
    }

    /** Recursive review wire of one fams_inventory_observation row (JSON trees are wired too). */
    public static ObservationRow reviewObservation(ObjectMapper json, Map<String, Object> row) {
        var base = observation(json, row);
        return new ObservationRow(
                base.id(),
                base.taskId(),
                base.clientEventId(),
                base.actorAccountId(),
                base.bindingId(),
                base.deviceId(),
                base.itemId(),
                base.assignmentVersion(),
                base.recheckGeneration(),
                base.snapshotAssetVersion(),
                base.source(),
                base.assetCode(),
                base.rawObservedAt(),
                base.firstPersistedAt(),
                base.clientExpiresAt(),
                base.calibratedObservedAt(),
                base.calibratedPersistedAt(),
                base.calibrationId(),
                base.receivedAt(),
                wiredJson(json, row, "facts_json"),
                base.payloadSha256(),
                base.factSha256(),
                base.outcome(),
                wiredJson(json, row, "receipt_json"));
    }

    /** Flat finding wire of one fams_inventory_review_subject row. */
    public static SubjectRow subject(ObjectMapper json, Map<String, Object> row) {
        return new SubjectRow(
                text(row, "observation_id"),
                text(row, "dimension"),
                text(row, "task_id"),
                text(row, "item_id"),
                num(row, "recheck_generation"),
                text(row, "subject_status"),
                text(row, "method"),
                text(row, "reason"),
                text(row, "evidence"),
                text(row, "reviewer_account_id"),
                text(row, "reviewer_username"),
                time(row, "reviewed_at"),
                num(row, "version"));
    }

    /** Empty-subject state of the finding detail exit. */
    public static SubjectProjection noSubject() {
        return new NoSubject();
    }

    /** Flat finding wire of a decision row joined to its finding link. */
    public static DecisionHistoryRow decisionHistory(
            ObjectMapper json, Map<String, Object> row) {
        return new DecisionHistoryRow(
                text(row, "id"),
                text(row, "task_id"),
                text(row, "observation_id"),
                text(row, "dimension"),
                num(row, "before_version"),
                num(row, "after_version"),
                text(row, "method"),
                text(row, "subject_status"),
                text(row, "reason"),
                text(row, "evidence"),
                num(row, "reviewed_asset_version"),
                text(row, "actor_account_id"),
                text(row, "actor_username"),
                time(row, "occurred_at"),
                text(row, "assetId"),
                text(row, "inboundReceiptId"),
                text(row, "link_kind"),
                boxed(row, "owner_version"));
    }

    /** Flat finding wire of a decision row joined to its recollection. */
    public static DecisionRecollectionRow decisionRecollection(
            ObjectMapper json, Map<String, Object> row) {
        return new DecisionRecollectionRow(
                text(row, "id"),
                text(row, "task_id"),
                text(row, "observation_id"),
                text(row, "dimension"),
                num(row, "before_version"),
                num(row, "after_version"),
                text(row, "method"),
                text(row, "subject_status"),
                text(row, "reason"),
                text(row, "evidence"),
                num(row, "reviewed_asset_version"),
                text(row, "actor_account_id"),
                text(row, "actor_username"),
                time(row, "occurred_at"),
                text(row, "counterObservationId"),
                time(row, "counter_observed_at"));
    }

    /** Recursive review wire of one fams_inventory_task row. */
    public static TaskRow task(ObjectMapper json, Map<String, Object> row) {
        return new TaskRow(
                text(row, "id"),
                text(row, "name"),
                text(row, "task_status"),
                wiredJson(json, row, "range_json"),
                time(row, "starts_at"),
                time(row, "due_at"),
                text(row, "timezone_id"),
                text(row, "parameter_version_id"),
                num(row, "expected_count"),
                num(row, "captured_result_count"),
                num(row, "assignment_version"),
                num(row, "version"),
                text(row, "creator_account_id"),
                text(row, "publisher_account_id"),
                text(row, "copied_from_task_id"),
                time(row, "created_at"),
                time(row, "updated_at"),
                time(row, "published_at"),
                time(row, "started_at"),
                time(row, "ended_at"),
                text(row, "end_reason"));
    }

    /** Recursive review wire of one fams_inventory_item row. */
    public static ItemRow item(ObjectMapper json, Map<String, Object> row) {
        return new ItemRow(
                text(row, "id"),
                text(row, "task_id"),
                text(row, "asset_id"),
                num(row, "asset_version"),
                wiredJson(json, row, "snapshot_json"),
                text(row, "executor_account_id"),
                num(row, "assignment_version"),
                num(row, "result_count"),
                num(row, "recheck_generation"));
    }

    /** Recursive review wire of one fams_inventory_item_review row. */
    public static ItemReviewRow itemReview(ObjectMapper json, Map<String, Object> row) {
        return new ItemReviewRow(
                text(row, "item_id"),
                num(row, "recheck_generation"),
                text(row, "task_id"),
                text(row, "observation_id"),
                text(row, "review_status"),
                time(row, "verified_observed_at"),
                num(row, "reviewed_asset_version"),
                text(row, "reason"),
                text(row, "evidence"),
                text(row, "reviewer_account_id"),
                text(row, "reviewer_username"),
                time(row, "reviewed_at"),
                num(row, "version"));
    }

    /**
     * Review-wired tree of one row value: the stored {@code *_json} text parsed and pushed through
     * the recursive projection, exactly as the pre-refactor review Map assembly put it.
     */
    public static JsonNode wiredJson(ObjectMapper json, Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null
                ? null
                : json.valueToTree(wire(json, json.readValue(value.toString(), Object.class)));
    }

    /** Review-wired tree of an already parsed JSON value (the asset snapshot document). */
    public static JsonNode wiredTree(ObjectMapper json, Object parsed) {
        return json.valueToTree(wire(json, parsed));
    }

    /**
     * Verbatim pre-refactor {@code InventoryReviewService.wire(Object)}: every {@code *_json} string
     * child is parsed, Number values under {@code id}/{@code *_id}/{@code *Id} stringify, the two
     * DATETIME driver shapes become ISO instants and maps/lists recurse. Kept as the single source
     * for the arbitrary snapshot trees ({@code current}) and for the review row factories.
     */
    static Object wire(ObjectMapper json, Object value) {
        if (value instanceof Map<?, ?> row) {
            Map<String, Object> result = new LinkedHashMap<>();
            row.forEach(
                    (key, child) -> {
                        String name = key.toString();
                        Object data =
                                name.endsWith("_json") && child instanceof String text
                                        ? json.readValue(text, Object.class)
                                        : child;
                        result.put(
                                name,
                                data instanceof Number
                                                && (name.equals("id")
                                                        || name.endsWith("_id")
                                                        || name.endsWith("Id"))
                                        ? data.toString()
                                        : wire(json, data));
                    });
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(child -> wire(json, child)).toList();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof LocalDateTime time) {
            return time.toInstant(ZoneOffset.UTC).toString();
        }
        return value;
    }

    /**
     * Flat finding wire value: {@code id}/{@code *_id} Numbers stringify (the legacy
     * {@code value.toString()} branch, identical for the CHAR columns that already arrive as
     * strings) and every other scalar passes through as text.
     */
    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    private static long num(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    /** Nullable numeric column (LEFT JOIN miss or NULL column) keeps an explicit null. */
    private static Long boxed(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : ((Number) value).longValue();
    }

    /** Legacy wire DATETIME branch: ISO instant for both driver shapes, null stays null. */
    private static String time(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof LocalDateTime time) {
            return time.toInstant(ZoneOffset.UTC).toString();
        }
        return value.toString();
    }

    /** Flat finding wire of a JSON column: parsed tree, no nested projection. */
    private static JsonNode node(ObjectMapper json, Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : json.readTree(value.toString());
    }
}
