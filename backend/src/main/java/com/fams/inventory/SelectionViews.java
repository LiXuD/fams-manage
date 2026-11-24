package com.fams.inventory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.fams.asset.AssetInventoryReviewPort;
import com.fams.loss.InventoryLossPort;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Typed wire shapes of the inventory selection/difference/readiness HTTP exits (JAVA-17a). Every
 * record mirrors, key by key, the frozen pre-refactor row projection of the three services:
 *
 * <ul>
 *   <li>{@code InventorySelectionService.wire}: parses {@code *_json} string columns into trees,
 *       stringifies numeric {@code id}/{@code *_id}/{@code *Id} values, renders DATETIME as an ISO
 *       instant and passes every other value through;
 *   <li>{@code InventoryDifferenceService.wire}: the same, minus the {@code *_json} parse and with
 *       only {@code id}/{@code *_id} stringified;
 *   <li>{@code InventoryReadinessService.wire}: parses {@code *_json} and stringifies <em>any</em>
 *       non-null {@code id}/{@code *_id} value, not only numbers.
 * </ul>
 *
 * <p>The three variants coincide on the column sets these exits actually project: the shared
 * review/decision/transfer/repair tables carry no {@code *_json} column, and every JSON payload that
 * does reach a wire ({@code fams_asset_inventory_source_v1} snapshot, {@code range_json}, {@code
 * physical_snapshot_json}) only holds strings, nulls and plain integers under non-{@code _id} keys,
 * so the legacy recursion into a parsed payload never rewrote a value and the raw tree carried here
 * is equivalent.
 *
 * <p>Each key the legacy Map always put stays a default-inclusion component (the global
 * JsonConfiguration uses {@code Include.ALWAYS}, so explicit nulls serialize); each key the legacy
 * code put only under a guard carries {@code NON_NULL}. Row projections keep their SQL snake_case
 * names (JAVA-11a LossEvent precedent).
 */
public final class SelectionViews {
    private SelectionViews() {
    }

    // ================= shared row projections =================

    /**
     * Review chip of the selection detail exit: the legacy assembly emitted either the single-key
     * marker {@code {"version":0}} (no fams_inventory_item_review row yet) or the whole 13-key row
     * below, so the two key sets stay two record types behind this interface. There is deliberately
     * no {@code @JsonTypeInfo}: the wire tree carries no discriminator key and Jackson serializes the
     * runtime type, exactly like the legacy Map did.
     */
    public sealed interface ReviewChip permits ReviewState, NoReview {}

    /** The frozen empty-review marker (the legacy {@code Map.of("version", 0)}). */
    public record NoReview(long version) implements ReviewChip {}

    /**
     * fams_inventory_item_review row. {@code observationId} and {@code verifiedObservedAt} are the two
     * nullable review columns and stay explicit nulls for a NOT_OBSERVED review, which is why the
     * full-row shape cannot be merged with {@link NoReview}.
     */
    public record ReviewState(
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
            implements ReviewChip {}

    /** fams_inventory_selection_event row: the selection detail exit's history segment. */
    public record SelectionEventRow(
            @JsonProperty("item_id") String itemId,
            @JsonProperty("recheck_generation") long recheckGeneration,
            @JsonProperty("review_version") long reviewVersion,
            @JsonProperty("task_id") String taskId,
            @JsonProperty("observation_id") String observationId,
            @JsonProperty("review_status") String reviewStatus,
            @JsonProperty("verified_observed_at") String verifiedObservedAt,
            @JsonProperty("time_source") String timeSource,
            @JsonProperty("reviewed_asset_version") long reviewedAssetVersion,
            @JsonProperty("physical_snapshot_json") JsonNode physicalSnapshotJson,
            @JsonProperty("movement_digest") String movementDigest,
            @JsonProperty("movement_count") long movementCount,
            @JsonProperty("reason") String reason,
            @JsonProperty("evidence") String evidence,
            @JsonProperty("actor_account_id") String actorAccountId,
            @JsonProperty("actor_username") String actorUsername,
            @JsonProperty("recorded_at") String recordedAt) {}

    /**
     * fams_inventory_review_decision row. Both the selection evidence-subjects exit and the
     * difference list exit project it identically (the table has no JSON column and every id-like
     * column is numeric, so the two wire variants agree).
     */
    public record DecisionRow(
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
            @JsonProperty("occurred_at") String occurredAt) {}

    /** fams_inventory_review_transfer row. */
    public record TransferLinkRow(
            @JsonProperty("observation_id") String observationId,
            @JsonProperty("dimension") String dimension,
            @JsonProperty("decision_version") long decisionVersion,
            @JsonProperty("transfer_id") String transferId,
            @JsonProperty("transfer_item_id") String transferItemId,
            @JsonProperty("created_at") String createdAt) {}

    /** fams_inventory_review_repair row (its dimension is CHECK-frozen to CONDITION). */
    public record RepairLinkRow(
            @JsonProperty("observation_id") String observationId,
            @JsonProperty("dimension") String dimension,
            @JsonProperty("decision_version") long decisionVersion,
            @JsonProperty("repair_id") String repairId,
            @JsonProperty("created_at") String createdAt) {}

    /** fams_inventory_evidence_disposition row (the counter-evidence decision fact). */
    public record EvidenceDispositionRow(
            @JsonProperty("observation_id") String observationId,
            @JsonProperty("task_id") String taskId,
            @JsonProperty("item_id") String itemId,
            @JsonProperty("recheck_generation") long recheckGeneration,
            @JsonProperty("counter_observation_id") String counterObservationId,
            @JsonProperty("counter_review_version") long counterReviewVersion,
            @JsonProperty("disposition") String disposition,
            @JsonProperty("reviewed_asset_version") long reviewedAssetVersion,
            @JsonProperty("context_digest") String contextDigest,
            @JsonProperty("reason") String reason,
            @JsonProperty("evidence") String evidence,
            @JsonProperty("actor_account_id") String actorAccountId,
            @JsonProperty("actor_username") String actorUsername,
            @JsonProperty("recorded_at") String recordedAt,
            @JsonProperty("version") long version) {}

    /** fams_inventory_unobserved_reason row (the readiness history segment). */
    public record UnobservedReasonRow(
            @JsonProperty("id") String id,
            @JsonProperty("task_id") String taskId,
            @JsonProperty("item_id") String itemId,
            @JsonProperty("recheck_generation") long recheckGeneration,
            @JsonProperty("reason_kind") String reasonKind,
            @JsonProperty("reason") String reason,
            @JsonProperty("evidence") String evidence,
            @JsonProperty("reviewed_asset_version") long reviewedAssetVersion,
            @JsonProperty("actor_account_id") String actorAccountId,
            @JsonProperty("actor_username") String actorUsername,
            @JsonProperty("recorded_at") String recordedAt) {}

    /**
     * fams_inventory_task row as the readiness detail exit projects it. {@code rangeJson} carries the
     * parsed {@code range_json}: the {@code InventoryInput.Range} shape whose camel {@code *Id}
     * integers no wire variant stringified (none of those keys ends in {@code _id}).
     */
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
            @JsonProperty("end_reason") String endReason) {}

    /**
     * The readiness item join: fams_inventory_item columns plus the four review/reason LEFT JOIN
     * columns and the valid-observation count. Every LEFT JOIN column stays an explicit null when the
     * item has no review row and no unobserved reason.
     */
    public record ReadinessItemRow(
            @JsonProperty("id") String id,
            @JsonProperty("task_id") String taskId,
            @JsonProperty("asset_id") String assetId,
            @JsonProperty("asset_version") long assetVersion,
            @JsonProperty("snapshot_json") JsonNode snapshotJson,
            @JsonProperty("executor_account_id") String executorAccountId,
            @JsonProperty("assignment_version") long assignmentVersion,
            @JsonProperty("result_count") long resultCount,
            @JsonProperty("recheck_generation") long recheckGeneration,
            @JsonProperty("review_status") String reviewStatus,
            @JsonProperty("review_version") Long reviewVersion,
            @JsonProperty("reason_kind") String reasonKind,
            @JsonProperty("reason") String reason,
            @JsonProperty("evidence") String evidence,
            @JsonProperty("actor_username") String actorUsername,
            @JsonProperty("recorded_at") String recordedAt,
            @JsonProperty("valid_raw_count") long validRawCount) {}

    // ================= selection exits =================

    /** GET …/items/{item}/selection: the frozen detail assembly key by key. */
    public record SelectionDetailView(
            String taskId,
            long taskVersion,
            String itemId,
            long recheckGeneration,
            ReviewChip review,
            String contextDigest,
            List<AssetInventoryReviewPort.Movement> movementEvents,
            List<SelectionEventRow> history,
            int page,
            int pageSize,
            long total) {
        /**
         * Copies the two list segments of the selection detail body. The movement list is the very
         * snapshot the {@code contextDigest} was computed from, so freezing it keeps the digest and
         * the published movements consistent even if the asset review port's caller mutates its list.
         *
         * @param movementEvents period movements behind the context digest, or null when absent
         * @param history fams_inventory_selection_event rows ordered by recheck_generation then
         *     review_version, or null when the page was not projected
         */
        public SelectionDetailView {
            movementEvents = movementEvents == null ? null : List.copyOf(movementEvents);
            history = history == null ? null : List.copyOf(history);
        }
    }

    /**
     * POST …/items/{item}/selection and the SELECT_EVIDENCE idempotency receipt body. {@code review}
     * is always the full row: the review exists once the command committed.
     */
    public record SelectionReply(
            String taskId, String itemId, long version, ReviewState review, String contextDigest) {}

    /**
     * One fams_inventory_review_subject row of GET …/evidence-subjects with its three appended
     * segments, in the legacy order (row columns, then decisions, links, repairLinks).
     */
    public record EvidenceSubjectRow(
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
            @JsonProperty("version") long version,
            List<DecisionRow> decisions,
            List<TransferLinkRow> links,
            List<RepairLinkRow> repairLinks) {
        /**
         * Copies the three appended segments so the subject row keeps the legacy assembly order and
         * cannot be changed through the query result lists it was built from. Null stays null in each
         * segment because the ALWAYS inclusion still has to emit the explicit key.
         *
         * @param decisions fams_inventory_review_decision rows of this subject in ascending
         *     after_version order, or null when absent
         * @param links fams_inventory_review_transfer rows of this subject in ascending
         *     decision_version order, or null when absent
         * @param repairLinks fams_inventory_review_repair rows of this subject in ascending
         *     decision_version order, or null when absent
         */
        public EvidenceSubjectRow {
            decisions = decisions == null ? null : List.copyOf(decisions);
            links = links == null ? null : List.copyOf(links);
            repairLinks = repairLinks == null ? null : List.copyOf(repairLinks);
        }
    }

    /** GET …/evidence-subjects envelope. */
    public record EvidenceSubjectView(List<EvidenceSubjectRow> items, List<String> requiredDimensions) {
        /**
         * Copies both envelope lists. The dimension list is the sorted output of
         * {@code InventoryDifferenceService.requiredDimensions}: every dimension derived from the
         * observation receipt plus its outcome-mapped special dimension, i.e. the review subjects
         * that must exist before this observation can be accepted.
         *
         * @param items fams_inventory_review_subject rows of the observation in ascending dimension
         *     order, or null when absent
         * @param requiredDimensions dimensions the difference service derived for this observation,
         *     sorted, or null when absent
         */
        public EvidenceSubjectView {
            items = items == null ? null : List.copyOf(items);
            requiredDimensions = requiredDimensions == null ? null : List.copyOf(requiredDimensions);
        }
    }

    /** POST …/items/{item}/evidence-decisions and the DECIDE_EVIDENCE receipt body. */
    public record EvidenceDecisionReply(
            String observationId,
            String method,
            long closed,
            String taskId,
            String itemId,
            long version) {}

    /** POST …/items/{item}/acceptance and the ACCEPT_SELECTED receipt body. */
    public record EvidenceAcceptanceReply(
            ReviewState review, String taskId, String itemId, long version) {}

    // ================= difference exits =================

    /**
     * One row of GET …/differences: the subject columns, the joined observation client event id and
     * the three appended segments. The difference list query selects {@code o.client_event_id} while
     * the selection evidence-subjects query does not, hence the separate row type.
     */
    public record DifferenceSubjectRow(
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
            @JsonProperty("version") long version,
            @JsonProperty("client_event_id") String clientEventId,
            List<DecisionRow> decisions,
            List<TransferLinkRow> links,
            List<RepairLinkRow> repairLinks) {
        /**
         * Copies the three appended segments of the difference list row. Null stays null per segment
         * so the ALWAYS inclusion still emits the key, and the copy detaches the row from the
         * per-subject query results it was assembled from.
         *
         * @param decisions fams_inventory_review_decision rows of this subject in ascending
         *     after_version order, or null when absent
         * @param links fams_inventory_review_transfer rows of this subject in ascending
         *     decision_version order, or null when absent
         * @param repairLinks fams_inventory_review_repair rows of this subject in ascending
         *     decision_version order, or null when absent
         */
        public DifferenceSubjectRow {
            decisions = decisions == null ? null : List.copyOf(decisions);
            links = links == null ? null : List.copyOf(links);
            repairLinks = repairLinks == null ? null : List.copyOf(repairLinks);
        }
    }

    /** GET …/differences envelope. */
    public record DifferencePage(
            List<DifferenceSubjectRow> items, int page, int pageSize, long total) {
        /**
         * Copies the subject rows of the frozen difference page. Null stays null rather than an empty
         * list, so an unprojected page keeps its explicit {@code items} key.
         *
         * @param items review subjects of this page ordered by observation_id then dimension, or null
         *     when the page was not projected
         */
        public DifferencePage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** GET …/evidence-dispositions envelope. */
    public record DispositionPage(
            List<EvidenceDispositionRow> items, long total, int page, int pageSize) {
        /**
         * Copies the counter-evidence disposition rows of the frozen page. Null stays null, which is
         * not the same as an empty list under the ALWAYS inclusion.
         *
         * @param items fams_inventory_evidence_disposition rows of this page in ascending
         *     observation_id order, or null when the page was not projected
         */
        public DispositionPage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** POST …/differences/preparation and the PREPARE_DIFFERENCES receipt body. */
    public record DifferencePrepareReply(int created, String taskId, long version) {}

    /**
     * One item of the DECIDE_DIFFERENCES payload. {@code linkedRepairId} and {@code repairStatus}
     * were only put by the repair branch's seven-key {@code Map.of}, so they stay the two conditional
     * keys; {@code linkedRequestId} was always put (empty string when no link exists).
     */
    public record DifferenceDecisionItem(
            String observationId,
            String dimension,
            long version,
            String status,
            String linkedRequestId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String linkedRepairId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String repairStatus) {}

    /** POST …/differences/decisions and the DECIDE_DIFFERENCES receipt body. */
    public record DifferenceDecisionReply(
            List<DifferenceDecisionItem> items, String taskId, long version) {
        /**
         * Copies the decision results into the reply that {@code finish} also serializes into
         * fams_inventory_review_receipt.response_json and the task event detail_json, so a replayed
         * request must return the identical list. Null stays null rather than an empty list.
         *
         * @param items one entry per decision in request payload order, or null when absent
         */
        public DifferenceDecisionReply {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** GET …/differences/{observation}/repair-context (the ten-key frozen assembly). */
    public record DifferenceRepairContext(
            String taskId,
            long taskVersion,
            String observationId,
            long subjectVersion,
            String subjectStatus,
            String assetId,
            JsonNode currentAsset,
            String originalObservedAt,
            JsonNode originalFacts,
            List<RepairLinkRow> repairLinks) {
        /**
         * Copies the CONDITION repair links of the frozen ten-key context. Null stays null rather
         * than becoming an empty list, so the explicit key keeps its projected meaning under the
         * ALWAYS inclusion.
         *
         * @param repairLinks fams_inventory_review_repair rows of the CONDITION dimension in
         *     ascending decision_version order, or null when absent
         */
        public DifferenceRepairContext {
            repairLinks = repairLinks == null ? null : List.copyOf(repairLinks);
        }
    }

    // ================= readiness exits =================

    /** One missing-loss row of the readiness detail exit. */
    public record MissingLossRow(
            String reasonId,
            long generation,
            String reason,
            InventoryLossPort.Progress report,
            boolean canSubmit) {}

    /** One readiness item row: the join projection plus the live asset snapshot and three flags. */
    public record ReadinessItem(
            String id,
            ReadinessItemRow item,
            JsonNode current,
            boolean canRecord,
            boolean physicalMissingOpen,
            List<MissingLossRow> missingLosses) {
        /**
         * Copies the missing-loss rows of this readiness item. Null stays null rather than becoming
         * an empty list, which keeps the ALWAYS-included key distinguishable from a projected item
         * that simply has no ASSET_NOT_FOUND reason yet.
         *
         * @param missingLosses ASSET_NOT_FOUND reasons of this item with their loss-domain progress,
         *     ordered by recheck_generation, or null when absent
         */
        public ReadinessItem {
            missingLosses = missingLosses == null ? null : List.copyOf(missingLosses);
        }
    }

    /** GET /inventory-readiness/tasks/{task} envelope. */
    public record ReadinessView(
            TaskRow task,
            List<ReadinessItem> items,
            long total,
            int page,
            int pageSize,
            long missingCount,
            long notObservedCount) {
        /**
         * Copies the readiness items of the frozen envelope. Null stays null rather than an empty
         * list, keeping the explicit key distinguishable from a projected but empty page.
         *
         * @param items readiness items of this page ordered by asset id, or null when the page was not
         *     projected
         */
        public ReadinessView {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** GET …/items/{item}/unobserved-history envelope. */
    public record UnobservedHistoryPage(
            List<UnobservedReasonRow> items, long total, int page, int pageSize) {
        /**
         * Copies the reason rows of the frozen history page. Null stays null rather than an empty
         * list, preserving the explicit key under the ALWAYS inclusion.
         *
         * @param items fams_inventory_unobserved_reason rows of this item in descending
         *     recheck_generation order, or null when the page was not projected
         */
        public UnobservedHistoryPage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** POST …/items/{item}/unobserved and the UNOBSERVED:* receipt body. */
    public record UnobservedReply(
            String taskId,
            String itemId,
            String reasonId,
            long version,
            long reviewVersion,
            String status,
            boolean physicalMissingOpen) {}

    /** POST …/items/{item}/missing/{reason}/loss and the MISSING_LOSS:* receipt body. */
    public record MissingLossReply(
            String taskId, String itemId, String reasonId, String reportId, long version) {}

    /** POST /tasks/{task}/waiting-review and the WAITING_REVIEW receipt body. */
    public record WaitingReply(String taskId, long version, String status, long notObservedCount) {}

    // ================= row factories =================

    /** The detail exit's chip: the single-key marker for an absent row, the full row otherwise. */
    static ReviewChip reviewChip(Map<String, Object> review) {
        return review.isEmpty() ? new NoReview(0) : reviewState(review);
    }

    static ReviewState reviewState(Map<String, Object> row) {
        return new ReviewState(
                id(row, "item_id"),
                num(row, "recheck_generation"),
                id(row, "task_id"),
                id(row, "observation_id"),
                (String) row.get("review_status"),
                time(row, "verified_observed_at"),
                num(row, "reviewed_asset_version"),
                (String) row.get("reason"),
                (String) row.get("evidence"),
                id(row, "reviewer_account_id"),
                (String) row.get("reviewer_username"),
                time(row, "reviewed_at"),
                num(row, "version"));
    }

    static List<SelectionEventRow> selectionEvents(
            ObjectMapper json, List<Map<String, Object>> rows) {
        return rows.stream().map(row -> selectionEvent(json, row)).toList();
    }

    static SelectionEventRow selectionEvent(ObjectMapper json, Map<String, Object> row) {
        return new SelectionEventRow(
                id(row, "item_id"),
                num(row, "recheck_generation"),
                num(row, "review_version"),
                id(row, "task_id"),
                id(row, "observation_id"),
                (String) row.get("review_status"),
                time(row, "verified_observed_at"),
                (String) row.get("time_source"),
                num(row, "reviewed_asset_version"),
                node(json, row, "physical_snapshot_json"),
                (String) row.get("movement_digest"),
                num(row, "movement_count"),
                (String) row.get("reason"),
                (String) row.get("evidence"),
                id(row, "actor_account_id"),
                (String) row.get("actor_username"),
                time(row, "recorded_at"));
    }

    static List<DecisionRow> decisions(List<Map<String, Object>> rows) {
        return rows.stream().map(SelectionViews::decision).toList();
    }

    static DecisionRow decision(Map<String, Object> row) {
        return new DecisionRow(
                id(row, "id"),
                id(row, "task_id"),
                id(row, "observation_id"),
                (String) row.get("dimension"),
                num(row, "before_version"),
                num(row, "after_version"),
                (String) row.get("method"),
                (String) row.get("subject_status"),
                (String) row.get("reason"),
                (String) row.get("evidence"),
                num(row, "reviewed_asset_version"),
                id(row, "actor_account_id"),
                (String) row.get("actor_username"),
                time(row, "occurred_at"));
    }

    static List<TransferLinkRow> transferLinks(List<Map<String, Object>> rows) {
        return rows.stream().map(SelectionViews::transferLink).toList();
    }

    static TransferLinkRow transferLink(Map<String, Object> row) {
        return new TransferLinkRow(
                id(row, "observation_id"),
                (String) row.get("dimension"),
                num(row, "decision_version"),
                id(row, "transfer_id"),
                id(row, "transfer_item_id"),
                time(row, "created_at"));
    }

    static List<RepairLinkRow> repairLinks(List<Map<String, Object>> rows) {
        return rows.stream().map(SelectionViews::repairLink).toList();
    }

    static RepairLinkRow repairLink(Map<String, Object> row) {
        return new RepairLinkRow(
                id(row, "observation_id"),
                (String) row.get("dimension"),
                num(row, "decision_version"),
                id(row, "repair_id"),
                time(row, "created_at"));
    }

    static EvidenceSubjectRow evidenceSubject(
            Map<String, Object> row,
            List<Map<String, Object>> decisionRows,
            List<Map<String, Object>> linkRows,
            List<Map<String, Object>> repairRows) {
        return new EvidenceSubjectRow(
                id(row, "observation_id"),
                (String) row.get("dimension"),
                id(row, "task_id"),
                id(row, "item_id"),
                num(row, "recheck_generation"),
                (String) row.get("subject_status"),
                (String) row.get("method"),
                (String) row.get("reason"),
                (String) row.get("evidence"),
                id(row, "reviewer_account_id"),
                (String) row.get("reviewer_username"),
                time(row, "reviewed_at"),
                num(row, "version"),
                decisions(decisionRows),
                transferLinks(linkRows),
                repairLinks(repairRows));
    }

    static DifferenceSubjectRow differenceSubject(
            Map<String, Object> row,
            List<Map<String, Object>> decisionRows,
            List<Map<String, Object>> linkRows,
            List<Map<String, Object>> repairRows) {
        return new DifferenceSubjectRow(
                id(row, "observation_id"),
                (String) row.get("dimension"),
                id(row, "task_id"),
                id(row, "item_id"),
                num(row, "recheck_generation"),
                (String) row.get("subject_status"),
                (String) row.get("method"),
                (String) row.get("reason"),
                (String) row.get("evidence"),
                id(row, "reviewer_account_id"),
                (String) row.get("reviewer_username"),
                time(row, "reviewed_at"),
                num(row, "version"),
                (String) row.get("client_event_id"),
                decisions(decisionRows),
                transferLinks(linkRows),
                repairLinks(repairRows));
    }

    static List<EvidenceDispositionRow> evidenceDispositions(List<Map<String, Object>> rows) {
        return rows.stream().map(SelectionViews::evidenceDisposition).toList();
    }

    static EvidenceDispositionRow evidenceDisposition(Map<String, Object> row) {
        return new EvidenceDispositionRow(
                id(row, "observation_id"),
                id(row, "task_id"),
                id(row, "item_id"),
                num(row, "recheck_generation"),
                id(row, "counter_observation_id"),
                num(row, "counter_review_version"),
                (String) row.get("disposition"),
                num(row, "reviewed_asset_version"),
                (String) row.get("context_digest"),
                (String) row.get("reason"),
                (String) row.get("evidence"),
                id(row, "actor_account_id"),
                (String) row.get("actor_username"),
                time(row, "recorded_at"),
                num(row, "version"));
    }

    static List<UnobservedReasonRow> unobservedReasons(List<Map<String, Object>> rows) {
        return rows.stream().map(SelectionViews::unobservedReason).toList();
    }

    static UnobservedReasonRow unobservedReason(Map<String, Object> row) {
        return new UnobservedReasonRow(
                id(row, "id"),
                id(row, "task_id"),
                id(row, "item_id"),
                num(row, "recheck_generation"),
                (String) row.get("reason_kind"),
                (String) row.get("reason"),
                (String) row.get("evidence"),
                num(row, "reviewed_asset_version"),
                id(row, "actor_account_id"),
                (String) row.get("actor_username"),
                time(row, "recorded_at"));
    }

    static TaskRow taskRow(ObjectMapper json, Map<String, Object> row) {
        return new TaskRow(
                id(row, "id"),
                (String) row.get("name"),
                (String) row.get("task_status"),
                node(json, row, "range_json"),
                time(row, "starts_at"),
                time(row, "due_at"),
                id(row, "timezone_id"),
                id(row, "parameter_version_id"),
                num(row, "expected_count"),
                num(row, "captured_result_count"),
                num(row, "assignment_version"),
                num(row, "version"),
                id(row, "creator_account_id"),
                id(row, "publisher_account_id"),
                id(row, "copied_from_task_id"),
                time(row, "created_at"),
                time(row, "updated_at"),
                time(row, "published_at"),
                time(row, "started_at"),
                time(row, "ended_at"),
                (String) row.get("end_reason"));
    }

    static ReadinessItemRow readinessItemRow(ObjectMapper json, Map<String, Object> row) {
        return new ReadinessItemRow(
                id(row, "id"),
                id(row, "task_id"),
                id(row, "asset_id"),
                num(row, "asset_version"),
                node(json, row, "snapshot_json"),
                id(row, "executor_account_id"),
                num(row, "assignment_version"),
                num(row, "result_count"),
                num(row, "recheck_generation"),
                (String) row.get("review_status"),
                row.get("review_version") == null ? null : num(row, "review_version"),
                (String) row.get("reason_kind"),
                (String) row.get("reason"),
                (String) row.get("evidence"),
                (String) row.get("actor_username"),
                time(row, "recorded_at"),
                num(row, "valid_raw_count"));
    }

    // ================= value helpers =================

    /** The legacy wire id branch: any present value becomes its text, null stays an explicit null. */
    private static String id(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    private static long num(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static String time(Map<String, Object> row, String key) {
        return timeValue(row.get(key));
    }

    /** The legacy wire DATETIME branch: ISO instants for both driver shapes, null stays. */
    static String timeValue(Object value) {
        if (value == null) {
            return null;
        }
        return (value instanceof Timestamp timestamp
                        ? timestamp.toInstant()
                        : ((LocalDateTime) value).toInstant(ZoneOffset.UTC))
                .toString();
    }

    /** The legacy wire {@code *_json} branch: the stored text becomes a raw tree, null stays. */
    private static JsonNode node(ObjectMapper json, Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : json.readTree(value.toString());
    }
}
