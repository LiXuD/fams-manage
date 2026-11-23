package com.fams.inventory;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.fams.masterdata.InventoryReferencePort;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.StringNode;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed wire shapes of the inventory 主档/迟交/迟交损坏 HTTP exits (JAVA-17d), mirroring the frozen
 * pre-refactor row projections. Two distinct projections exist in this domain and must not be
 * mixed: {@code InventoryService.clean} is a flat, non-recursive projection of one JDBC row
 * ({@code id}/{@code *_id} stringify per value, {@code range_json} round-trips through
 * {@link InventoryInput.Range} so stored numeric identifiers leave as text, every other
 * {@code *_json} column becomes a raw tree, DATETIME becomes an ISO instant string and all other
 * columns pass through); {@code InventoryLateService.wire}/{@code InventoryLateDamageService.wire}
 * walk the row recursively, so nested {@code *_id} keys inside a parsed {@code *_json} document are
 * stringified too ({@link #inventoryWire}). Sub-table rows keep their original snake_case keys
 * (JAVA-11a LossEvent precedent) and every key the legacy Map put stays present, explicit null
 * included (global JsonConfiguration inclusion is ALWAYS); the role-gated task-view keys and the
 * two late-case variant keys are modelled as separate records instead of {@code NON_NULL}, because
 * the legacy code removed or emptied them structurally, not on a null test.
 */
public final class InventoryViews {
    private InventoryViews() {
    }

    // ================= 盘点主档 (InventoryController) =================

    /** POST /inventory-tasks/preview and the publish guard payload: SHA-256 digest of the range. */
    public record InventoryPreviewDigest(long count, String digest) {}

    /** GET /inventory-tasks/catalog/{kind}: the masterdata choices without a total key. */
    public record InventoryReferenceChoicePage(
            List<InventoryReferencePort.Choice> items, int page, int pageSize) {
        public InventoryReferenceChoicePage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /**
     * fams_inventory_task under the frozen {@code clean} projection. This is also the body every
     * inventory write operation persists into {@code fams_idempotency_record.response_body_json}
     * (the legacy code stored {@code clean(task(id,false))}), so declaration order stays the DDL
     * column order and newly stored receipts stay byte-identical.
     */
    public record InventoryTaskRow(
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

    /** Task views of the list item and the detail header; the admin sees every column. */
    public sealed interface InventoryTaskView
            permits InventoryTaskAdminView, InventoryTaskExecutorView {}

    /**
     * Admin task view: the full {@code clean} row plus the derived {@code overdue} flag. The legacy
     * assembly removed six keys only for a non-admin caller, so the two key sets are separate
     * records rather than {@code NON_NULL} components (an admin DRAFT row still emits an explicit
     * {@code publisher_account_id}/{@code end_reason} null).
     */
    public record InventoryTaskAdminView(
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
            @JsonProperty("end_reason") String endReason,
            @JsonProperty("overdue") boolean overdue)
            implements InventoryTaskView {}

    /** Executor task view: the same row without the six admin-only keys, plus {@code overdue}. */
    public record InventoryTaskExecutorView(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("task_status") String taskStatus,
            @JsonProperty("starts_at") String startsAt,
            @JsonProperty("due_at") String dueAt,
            @JsonProperty("timezone_id") String timezoneId,
            @JsonProperty("parameter_version_id") String parameterVersionId,
            @JsonProperty("assignment_version") long assignmentVersion,
            @JsonProperty("version") long version,
            @JsonProperty("copied_from_task_id") String copiedFromTaskId,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt,
            @JsonProperty("published_at") String publishedAt,
            @JsonProperty("started_at") String startedAt,
            @JsonProperty("ended_at") String endedAt,
            @JsonProperty("overdue") boolean overdue)
            implements InventoryTaskView {}

    /** fams_inventory_item under the frozen {@code clean} projection of the detail exit. */
    public record InventoryItemRow(
            @JsonProperty("id") String id,
            @JsonProperty("task_id") String taskId,
            @JsonProperty("asset_id") String assetId,
            @JsonProperty("asset_version") long assetVersion,
            @JsonProperty("snapshot_json") JsonNode snapshotJson,
            @JsonProperty("executor_account_id") String executorAccountId,
            @JsonProperty("assignment_version") long assignmentVersion,
            @JsonProperty("result_count") long resultCount,
            @JsonProperty("recheck_generation") long recheckGeneration) {}

    /** GET /inventory-tasks. */
    public record InventoryTaskPage(
            List<InventoryTaskView> items,
            long total,
            int page,
            int pageSize,
            boolean canManage) {
        public InventoryTaskPage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** GET /inventory-tasks/{id}. */
    public record InventoryTaskDetail(
            InventoryTaskView task,
            List<InventoryItemRow> items,
            long total,
            int page,
            int pageSize,
            boolean canManage) {
        public InventoryTaskDetail {
            items = items == null ? null : List.copyOf(items);
        }
    }

    // ================= 迟交补充证据 (InventoryLateController) =================

    /**
     * fams_inventory_task header of the late exits under the frozen recursive {@code wire}
     * projection: {@code range_json} keeps the stored document as-is (no Range round-trip), unlike
     * the 主档 {@link InventoryTaskRow}.
     */
    public record InventoryLateTaskHeader(
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

    /** fams_inventory_late_case row under the frozen recursive {@code wire} projection. */
    public record InventoryLateCaseEvidence(
            @JsonProperty("observation_id") String observationId,
            @JsonProperty("task_id") String taskId,
            @JsonProperty("comparison_kind") String comparisonKind,
            @JsonProperty("needs_review") Object needsReview,
            @JsonProperty("report_sha256") String reportSha256,
            @JsonProperty("accepted_observation_id") String acceptedObservationId,
            @JsonProperty("snapshot_json") JsonNode snapshotJson,
            @JsonProperty("snapshot_sha256") String snapshotSha256,
            @JsonProperty("created_at") String createdAt) {}

    /**
     * The late review chip: the legacy code emitted either a two-key placeholder for a case without
     * any decision (so an unreviewed case never looks decided) or the whole latest decision row.
     * Different key sets, hence a sealed pair rather than {@code NON_NULL} components.
     */
    public sealed interface InventoryLateReview
            permits InventoryLateReviewPlaceholder, InventoryLateDecisionRow {}

    /** Placeholder chip of a never-decided case ({@code decision_version} 0). */
    public record InventoryLateReviewPlaceholder(
            @JsonProperty("decision_version") long decisionVersion,
            @JsonProperty("result") String result)
            implements InventoryLateReview {}

    /** fams_inventory_late_decision row under the frozen recursive {@code wire} projection. */
    public record InventoryLateDecisionRow(
            @JsonProperty("observation_id") String observationId,
            @JsonProperty("decision_version") long decisionVersion,
            @JsonProperty("method") String method,
            @JsonProperty("result") String result,
            @JsonProperty("reason") String reason,
            @JsonProperty("evidence") String evidence,
            @JsonProperty("linked_task_id") String linkedTaskId,
            @JsonProperty("actor_account_id") String actorAccountId,
            @JsonProperty("actor_username") String actorUsername,
            @JsonProperty("decided_at") String decidedAt)
            implements InventoryLateReview {}

    /**
     * The damage chip of a late case: the legacy code emitted an empty object when no
     * fams_inventory_late_damage row exists, so absence is an emitted {@code {}} and not a missing
     * or null key.
     */
    public sealed interface InventoryLateDamageFollowup
            permits InventoryLateDamageAbsent, InventoryLateDamageRow {}

    /** Empty object emitted when the late case carries no damage follow-up row. */
    public record InventoryLateDamageAbsent() implements InventoryLateDamageFollowup {}

    /** fams_inventory_late_damage row under the frozen recursive {@code wire} projection. */
    public record InventoryLateDamageRow(
            @JsonProperty("observation_id") String observationId,
            @JsonProperty("version") long version,
            @JsonProperty("damage_status") String damageStatus,
            @JsonProperty("repair_id") String repairId,
            @JsonProperty("repair_status") String repairStatus,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt)
            implements InventoryLateDamageFollowup {}

    /** One list row / the detail case: evidence, review chip, damage chip and todo state. */
    public record InventoryLateCaseView(
            InventoryLateCaseEvidence evidence,
            InventoryLateReview review,
            InventoryLateDamageFollowup damageFollowup,
            boolean todoOpen) {}

    /** GET /inventory-late/tasks/{task}. */
    public record InventoryLateCasePage(
            InventoryLateTaskHeader task,
            List<InventoryLateCaseView> items,
            int page,
            int pageSize,
            long total,
            boolean supplementOnly) {
        public InventoryLateCasePage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** GET /inventory-late/tasks/{task}/observations/{observation}. */
    public record InventoryLateCaseDetail(
            InventoryLateTaskHeader task,
            @JsonProperty("case") InventoryLateCaseView lateCase,
            InventoryLateDecisionPage history,
            boolean supplementOnly) {}

    /** Decision history page of the late case detail exit. */
    public record InventoryLateDecisionPage(
            List<InventoryLateDecisionRow> items, int page, int pageSize, long total) {
        public InventoryLateDecisionPage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /**
     * POST /inventory-late/tasks/{task}/prepare body, also stored in
     * fams_inventory_review_receipt.response_json.
     */
    public record InventoryLatePrepareReceipt(
            String taskId,
            int prepared,
            boolean moreMayRemain,
            long taskVersion,
            boolean supplementOnly) {}

    /**
     * POST /inventory-late/tasks/{task}/observations/{observation}/decisions body, also stored in
     * fams_inventory_review_receipt.response_json. Declaration order is the legacy LinkedHashMap
     * insertion order, so newly stored receipt text stays byte-identical.
     */
    public record InventoryLateDecisionReceipt(
            String taskId,
            String observationId,
            long version,
            String result,
            String method,
            String linkedTaskId,
            String processedBy,
            String processedAt,
            boolean supplementOnly,
            boolean originalReportChanged) {}

    // ================= 迟交损坏返修 (InventoryLateDamageController) =================

    /** fams_inventory_late_damage_decision row under the frozen recursive {@code wire} shape. */
    public record InventoryLateDamageDecisionRow(
            @JsonProperty("observation_id") String observationId,
            @JsonProperty("before_version") long beforeVersion,
            @JsonProperty("after_version") long afterVersion,
            @JsonProperty("method") String method,
            @JsonProperty("damage_status") String damageStatus,
            @JsonProperty("repair_id") String repairId,
            @JsonProperty("repair_status") String repairStatus,
            @JsonProperty("reviewed_asset_version") Long reviewedAssetVersion,
            @JsonProperty("reason") String reason,
            @JsonProperty("evidence") String evidence,
            @JsonProperty("actor_account_id") String actorAccountId,
            @JsonProperty("actor_username") String actorUsername,
            @JsonProperty("decided_at") String decidedAt) {}

    /** Damage decision history page of the damage detail exit. */
    public record InventoryLateDamageDecisionPage(
            List<InventoryLateDamageDecisionRow> items, int page, int pageSize, long total) {
        public InventoryLateDamageDecisionPage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** GET /inventory-late/tasks/{task}/observations/{observation}/damage-repair. */
    public record InventoryLateDamageDetail(
            String taskId,
            long taskVersion,
            String observationId,
            String assetId,
            JsonNode currentAsset,
            InventoryLateDamageRow damage,
            JsonNode originalEvidence,
            InventoryLateDamageDecisionPage history,
            String recheckResult,
            boolean todoOpen,
            boolean supplementOnly,
            boolean originalReportChanged) {}

    /**
     * POST /inventory-late/tasks/{task}/observations/{observation}/damage-repair body, also stored
     * in fams_inventory_review_receipt.response_json (declaration order = legacy put order).
     */
    public record InventoryLateDamageDecisionReceipt(
            String taskId,
            String observationId,
            long version,
            String method,
            String damageStatus,
            String repairId,
            String repairStatus,
            String recheckResult,
            boolean todoOpen,
            String processedBy,
            String processedAt,
            boolean supplementOnly,
            boolean originalReportChanged) {}

    // ================= 行 -> 视图工厂 =================

    /** Verbatim pre-JAVA-17d InventoryService.clean projection of one fams_inventory_task row. */
    static InventoryTaskRow inventoryTaskRow(ObjectMapper json, Map<String, Object> row) {
        return new InventoryTaskRow(
                inventoryId(row, "id"),
                inventoryText(row, "name"),
                inventoryText(row, "task_status"),
                row.get("range_json") == null
                        ? null
                        : inventoryNormalizedRange(json, row.get("range_json")),
                inventoryTime(row, "starts_at"),
                inventoryTime(row, "due_at"),
                inventoryId(row, "timezone_id"),
                inventoryId(row, "parameter_version_id"),
                inventoryNumber(row, "expected_count"),
                inventoryNumber(row, "captured_result_count"),
                inventoryNumber(row, "assignment_version"),
                inventoryNumber(row, "version"),
                inventoryId(row, "creator_account_id"),
                inventoryId(row, "publisher_account_id"),
                inventoryId(row, "copied_from_task_id"),
                inventoryTime(row, "created_at"),
                inventoryTime(row, "updated_at"),
                inventoryTime(row, "published_at"),
                inventoryTime(row, "started_at"),
                inventoryTime(row, "ended_at"),
                inventoryText(row, "end_reason"));
    }

    /** Admin task view: the frozen clean row plus the derived overdue flag. */
    static InventoryTaskAdminView inventoryTaskAdminView(InventoryTaskRow base, boolean overdue) {
        return new InventoryTaskAdminView(
                base.id(),
                base.name(),
                base.taskStatus(),
                base.rangeJson(),
                base.startsAt(),
                base.dueAt(),
                base.timezoneId(),
                base.parameterVersionId(),
                base.expectedCount(),
                base.capturedResultCount(),
                base.assignmentVersion(),
                base.version(),
                base.creatorAccountId(),
                base.publisherAccountId(),
                base.copiedFromTaskId(),
                base.createdAt(),
                base.updatedAt(),
                base.publishedAt(),
                base.startedAt(),
                base.endedAt(),
                base.endReason(),
                overdue);
    }

    /**
     * Executor task view: the legacy {@code taskView} removed range_json, creator/publisher
     * accounts, end_reason and both counters before adding {@code overdue}; the remaining values
     * are the same projected ones.
     */
    static InventoryTaskExecutorView inventoryTaskExecutorView(
            InventoryTaskRow base, boolean overdue) {
        return new InventoryTaskExecutorView(
                base.id(),
                base.name(),
                base.taskStatus(),
                base.startsAt(),
                base.dueAt(),
                base.timezoneId(),
                base.parameterVersionId(),
                base.assignmentVersion(),
                base.version(),
                base.copiedFromTaskId(),
                base.createdAt(),
                base.updatedAt(),
                base.publishedAt(),
                base.startedAt(),
                base.endedAt(),
                overdue);
    }

    /** Verbatim pre-JAVA-17d InventoryService.clean projection of one fams_inventory_item row. */
    static InventoryItemRow inventoryItemRow(ObjectMapper json, Map<String, Object> row) {
        return new InventoryItemRow(
                inventoryId(row, "id"),
                inventoryId(row, "task_id"),
                inventoryId(row, "asset_id"),
                inventoryNumber(row, "asset_version"),
                inventoryNode(json, row, "snapshot_json"),
                inventoryId(row, "executor_account_id"),
                inventoryNumber(row, "assignment_version"),
                inventoryNumber(row, "result_count"),
                inventoryNumber(row, "recheck_generation"));
    }

    /** Verbatim pre-JAVA-17d InventoryService.rangeJson snapshot of a published range. */
    static String inventoryRangeJson(ObjectMapper json, InventoryInput.Range range) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("usingOrganizationId", inventoryIdentifier(range.usingOrganizationId()));
        snapshot.put("owningOrganizationId", inventoryIdentifier(range.owningOrganizationId()));
        snapshot.put("categoryId", inventoryIdentifier(range.categoryId()));
        snapshot.put("warehouseId", inventoryIdentifier(range.warehouseId()));
        snapshot.put("locationId", inventoryIdentifier(range.locationId()));
        snapshot.put("responsibleEmployeeId", inventoryIdentifier(range.responsibleEmployeeId()));
        snapshot.put("lifecycle", range.lifecycle());
        snapshot.put("exception", range.exception());
        return json.writeValueAsString(snapshot);
    }

    /**
     * The legacy {@code clean} range branch: any stored shape (JSON text, JDBC value or an
     * already-parsed receipt node) is decoded into {@link InventoryInput.Range} and re-emitted in
     * the canonical key order, which is also what gets persisted by the write exits.
     */
    static JsonNode inventoryNormalizedRange(ObjectMapper json, Object value) {
        return json.readTree(
                inventoryRangeJson(
                        json,
                        json.readValue(
                                value instanceof CharSequence text
                                        ? text.toString()
                                        : json.writeValueAsString(value),
                                InventoryInput.Range.class)));
    }

    /** Verbatim pre-JAVA-17d InventoryLateService.wire projection of a fams_inventory_task row. */
    static InventoryLateTaskHeader inventoryLateTaskHeader(
            ObjectMapper json, Map<String, Object> row) {
        return new InventoryLateTaskHeader(
                inventoryId(row, "id"),
                inventoryText(row, "name"),
                inventoryText(row, "task_status"),
                inventoryLateNode(json, row, "range_json"),
                inventoryTime(row, "starts_at"),
                inventoryTime(row, "due_at"),
                inventoryId(row, "timezone_id"),
                inventoryId(row, "parameter_version_id"),
                inventoryNumber(row, "expected_count"),
                inventoryNumber(row, "captured_result_count"),
                inventoryNumber(row, "assignment_version"),
                inventoryNumber(row, "version"),
                inventoryId(row, "creator_account_id"),
                inventoryId(row, "publisher_account_id"),
                inventoryId(row, "copied_from_task_id"),
                inventoryTime(row, "created_at"),
                inventoryTime(row, "updated_at"),
                inventoryTime(row, "published_at"),
                inventoryTime(row, "started_at"),
                inventoryTime(row, "ended_at"),
                inventoryText(row, "end_reason"));
    }

    /** Verbatim pre-JAVA-17d InventoryLateService.view assembly of one late case row. */
    static InventoryLateCaseView inventoryLateCaseView(
            ObjectMapper json,
            Map<String, Object> row,
            Map<String, Object> previous,
            Map<String, Object> damage,
            boolean todoOpen) {
        return new InventoryLateCaseView(
                inventoryLateEvidence(json, row),
                previous.isEmpty()
                        ? new InventoryLateReviewPlaceholder(
                                0,
                                Boolean.TRUE.equals(row.get("needs_review"))
                                        ? "OPEN"
                                        : "INFORMATION")
                        : inventoryLateDecision(previous),
                damage == null ? new InventoryLateDamageAbsent() : inventoryLateDamage(damage),
                todoOpen);
    }

    static InventoryLateCaseEvidence inventoryLateEvidence(
            ObjectMapper json, Map<String, Object> row) {
        return new InventoryLateCaseEvidence(
                inventoryId(row, "observation_id"),
                inventoryId(row, "task_id"),
                inventoryText(row, "comparison_kind"),
                row.get("needs_review"),
                inventoryText(row, "report_sha256"),
                inventoryId(row, "accepted_observation_id"),
                inventoryLateNode(json, row, "snapshot_json"),
                inventoryText(row, "snapshot_sha256"),
                inventoryTime(row, "created_at"));
    }

    static InventoryLateDecisionRow inventoryLateDecision(Map<String, Object> row) {
        return new InventoryLateDecisionRow(
                inventoryId(row, "observation_id"),
                inventoryNumber(row, "decision_version"),
                inventoryText(row, "method"),
                inventoryText(row, "result"),
                inventoryText(row, "reason"),
                inventoryText(row, "evidence"),
                inventoryId(row, "linked_task_id"),
                inventoryId(row, "actor_account_id"),
                inventoryText(row, "actor_username"),
                inventoryTime(row, "decided_at"));
    }

    static List<InventoryLateDecisionRow> inventoryLateDecisions(List<Map<String, Object>> rows) {
        return rows.stream().map(InventoryViews::inventoryLateDecision).toList();
    }

    static InventoryLateDamageRow inventoryLateDamage(Map<String, Object> row) {
        return new InventoryLateDamageRow(
                inventoryId(row, "observation_id"),
                inventoryNumber(row, "version"),
                inventoryText(row, "damage_status"),
                inventoryId(row, "repair_id"),
                inventoryText(row, "repair_status"),
                inventoryTime(row, "created_at"),
                inventoryTime(row, "updated_at"));
    }

    static InventoryLateDamageDecisionRow inventoryLateDamageDecision(Map<String, Object> row) {
        return new InventoryLateDamageDecisionRow(
                inventoryId(row, "observation_id"),
                inventoryNumber(row, "before_version"),
                inventoryNumber(row, "after_version"),
                inventoryText(row, "method"),
                inventoryText(row, "damage_status"),
                inventoryId(row, "repair_id"),
                inventoryText(row, "repair_status"),
                row.get("reviewed_asset_version") == null
                        ? null
                        : inventoryNumber(row, "reviewed_asset_version"),
                inventoryText(row, "reason"),
                inventoryText(row, "evidence"),
                inventoryId(row, "actor_account_id"),
                inventoryText(row, "actor_username"),
                inventoryTime(row, "decided_at"));
    }

    static List<InventoryLateDamageDecisionRow> inventoryLateDamageDecisions(
            List<Map<String, Object>> rows) {
        return rows.stream().map(InventoryViews::inventoryLateDamageDecision).toList();
    }

    // ================= 共用投影规则 =================

    /** The legacy {@code clean}/{@code wire} identifier branch: per-value toString, null stays. */
    private static String inventoryId(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    /** The legacy {@code identifier} helper of {@code rangeJson}: nullable Long to text. */
    private static String inventoryIdentifier(Long id) {
        return id == null ? null : id.toString();
    }

    /** A plain textual column: both legacy projections passed the JDBC String through. */
    private static String inventoryText(Map<String, Object> row, String key) {
        return (String) row.get(key);
    }

    /** The legacy {@code ((Number) row.get(key)).longValue()} read of a BIGINT column. */
    private static long inventoryNumber(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    /**
     * The legacy DATETIME branch: ISO instants for both driver shapes, null stays null and any
     * other value (an already-normalized ISO string read back from a stored receipt) passes
     * through as its text, like the legacy fall-through did.
     */
    private static String inventoryTime(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toInstant(ZoneOffset.UTC).toString();
        }
        return value.toString();
    }

    /** The flat legacy {@code clean} JSON branch: {@code readTree(value.toString())}, no recursion. */
    private static JsonNode inventoryNode(
            ObjectMapper json, Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : json.readTree(value.toString());
    }

    /**
     * The late {@code wire} JSON branch: a String column is parsed and then walked recursively, so
     * nested identifiers keep the same textual form as in the legacy Map-domain walk.
     */
    private static JsonNode inventoryLateNode(
            ObjectMapper json, Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        return value instanceof String text
                ? inventoryWire(json, json.readTree(text))
                : json.valueToTree(value);
    }

    /** Verbatim {@code InventoryLateService.wire} rules moved into the JSON-tree domain. */
    private static JsonNode inventoryWire(ObjectMapper json, JsonNode value) {
        if (value.isObject()) {
            var result = json.createObjectNode();
            value.properties()
                    .forEach(
                            entry ->
                                    result.set(
                                            entry.getKey(),
                                            inventoryWireField(
                                                    json, entry.getKey(), entry.getValue())));
            return result;
        }
        if (value.isArray()) {
            var result = json.createArrayNode();
            value.forEach(item -> result.add(inventoryWire(json, item)));
            return result;
        }
        return value;
    }

    private static JsonNode inventoryWireField(
            ObjectMapper json, String field, JsonNode value) {
        if (value.isNull()) {
            return value;
        }
        if (field.equals("id") || field.endsWith("_id")) {
            return StringNode.valueOf(
                    value.isValueNode() ? value.asText() : value.toString());
        }
        JsonNode content = value;
        if (field.endsWith("_json") && value.isTextual()) {
            content = json.readTree(value.asText());
        }
        return inventoryWire(json, content);
    }
}
