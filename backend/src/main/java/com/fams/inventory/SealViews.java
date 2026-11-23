package com.fams.inventory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Typed wire shapes of the inventory seal/synchronization/observation HTTP exits (JAVA-17b),
 * mirroring the frozen pre-refactor Map assemblies of {@code InventorySealService},
 * {@code InventorySynchronizationService} and {@code InventoryObservationService}.
 *
 * <p>Projection semantics come from the seal {@code wire(row)} helper that still builds the
 * immutable report packets: {@code id}/{@code *_id} columns stringify per value (so
 * {@code timezone_id} stays a string even though it is a VARCHAR), {@code *_json} text columns
 * parse into raw JSON trees, Timestamp/LocalDateTime columns become ISO instants and every other
 * column keeps its JDBC value. Row projections therefore keep their original snake_case keys
 * through {@link JsonProperty} (JAVA-11a/16a precedent) declared in {@code SELECT *} column order,
 * which also keeps the hashed snapshot texts byte-stable.
 *
 * <p>Each key the legacy Map always put stays a default-inclusion component (the global
 * JsonConfiguration inclusion is ALWAYS, so explicit nulls serialize); each key the legacy code
 * put only under a guard carries {@code NON_NULL}. Component order is a contract for the two
 * persisted JSON texts that feed digests: the synchronization roster behind
 * {@code sync_digest}/{@code synchronizationDigest} and the observation receipt behind
 * {@code receipt_json}.
 */
public final class SealViews {
    private SealViews() {
    }

    /** Generic page envelope of the sealed report section exits. */
    public record Page<T>(List<T> items, long total, int page, int pageSize) {
        public Page {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /**
     * fams_inventory_task under the frozen wire() projection; the preflight envelope exposes it
     * verbatim while the seal keeps writing the same keys into its immutable report header.
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
     * One preflight blocker. {@code itemId}/{@code observationId} were only put by the evidence
     * and subject blockers, so they stay the two conditional keys.
     */
    public record Blocker(
            String code,
            @JsonInclude(JsonInclude.Include.NON_NULL) String itemId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String observationId) {}

    /**
     * GET /inventory-sealing/tasks/{task}. The sealed branch only emitted task/sealed/report, the
     * open branch never emitted report, so the remaining seven keys are the conditional set.
     */
    public record Preflight(
            TaskRow task,
            boolean sealed,
            @JsonInclude(JsonInclude.Include.NON_NULL) SealedReportRow report,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Blocker> blockers,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer blockerCount,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<SyncParticipant> synchronization,
            @JsonInclude(JsonInclude.Include.NON_NULL) String synchronizationDigest,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long missingSynchronizationCount,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean declarationOnly,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean canSealWithoutWaiver) {
        public Preflight {
            blockers = blockers == null ? null : List.copyOf(blockers);
            synchronization = synchronization == null ? null : List.copyOf(synchronization);
        }
    }

    /**
     * POST /inventory-sealing/tasks/{task}/seal and the stored fams_inventory_review_receipt
     * SEAL reply body (legacy {@code Map.of} assembly, seven always-present keys).
     */
    public record SealReceipt(
            String taskId,
            long version,
            String status,
            String reportSha256,
            long acceptedCount,
            long notObservedCount,
            long missingSynchronizationCount) {}

    /** fams_inventory_sealed_report header row under the frozen wire() projection. */
    public record SealedReportRow(
            @JsonProperty("task_id") String taskId,
            @JsonProperty("task_version") long taskVersion,
            @JsonProperty("timezone_id") String timezoneId,
            @JsonProperty("expected_count") long expectedCount,
            @JsonProperty("accepted_count") long acceptedCount,
            @JsonProperty("not_observed_count") long notObservedCount,
            @JsonProperty("captured_count") long capturedCount,
            @JsonProperty("actor_account_id") String actorAccountId,
            @JsonProperty("actor_username") String actorUsername,
            @JsonProperty("seal_reason") String sealReason,
            @JsonProperty("missing_sync_reason") String missingSyncReason,
            @JsonProperty("sync_digest") String syncDigest,
            @JsonProperty("report_sha256") String reportSha256,
            @JsonProperty("snapshot_json") JsonNode snapshotJson,
            @JsonProperty("created_at") String createdAt) {}

    /** One frozen sealed item/evidence/synchronization page row. */
    public record SnapshotRow(
            @JsonProperty("snapshot_json") JsonNode snapshotJson,
            @JsonProperty("snapshot_sha256") String snapshotSha256) {}

    /**
     * One device chip of the synchronization roster. {@code bindingId} was always put (explicit
     * null for an unbound observation), while the three declaration keys were only put when a
     * declaration row existed for the device.
     */
    public record DeviceRow(
            String deviceId,
            String bindingId,
            boolean current,
            String reason,
            @JsonInclude(JsonInclude.Include.NON_NULL) String declarationId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String declaredAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long unresolvedCount) {}

    /**
     * One participant row of the synchronization roster. The roster is the fingerprint input of
     * {@code sync_digest}, so this component order is the legacy LinkedHashMap put order.
     */
    public record SyncParticipant(
            String accountId,
            String name,
            boolean current,
            String reason,
            List<DeviceRow> devices) {
        public SyncParticipant {
            devices = devices == null ? null : List.copyOf(devices);
        }
    }

    /** GET /inventory-synchronization/tasks/{task}. */
    public record SynchronizationStatus(
            List<SyncParticipant> items,
            long total,
            int page,
            int pageSize,
            long taskVersion,
            boolean declarationOnly) {
        public SynchronizationStatus {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** GET /mini/inventory-synchronization/tasks/{task}/devices/{device}. */
    public record OwnDeviceState(
            String taskId,
            long taskVersion,
            String taskStatus,
            long assignmentVersion,
            String deviceId,
            String observationWatermark,
            DeviceRow device,
            boolean declarationOnly) {}

    /**
     * POST /mini/inventory-synchronization/tasks/{task}/declarations and the stored
     * fams_inventory_sync_receipt reply body; the same six keys are persisted into the task event
     * and audit {@code detail_json}.
     */
    public record SyncDeclarationReceipt(
            String declarationId,
            String taskId,
            long taskVersion,
            String deviceId,
            String declaredAt,
            boolean declarationOnly) {}

    /** POST /inventory-observations/calibrations ({@code Map.of} three keys). */
    public record CalibrationResult(String calibrationId, String serverTime, String clientTime) {}

    /**
     * GET and POST /inventory-observations/tasks/{taskId}/identification. One record covers the
     * five legacy {@code kind} branches; each branch only put its own keys, so every non-kind
     * component is conditional.
     */
    public record Identification(
            String kind,
            @JsonInclude(JsonInclude.Include.NON_NULL) String message,
            @JsonInclude(JsonInclude.Include.NON_NULL) String assetCode,
            @JsonInclude(JsonInclude.Include.NON_NULL) String name,
            @JsonInclude(JsonInclude.Include.NON_NULL) String itemId,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long assignmentVersion,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long recheckGeneration,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long assetVersion,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode snapshot) {}

    /**
     * POST /inventory-observations/tasks/{taskId}/events and the stored
     * fams_inventory_observation.receipt_json replay body. The component order is the legacy
     * LinkedHashMap put order because the same text is written into receipt_json and both audit
     * {@code detail_json} columns.
     */
    public record ObservationReceipt(
            String eventId,
            String outcome,
            boolean counted,
            List<String> differences,
            String receivedAt,
            boolean recollectionRequired) {
        public ObservationReceipt {
            differences = differences == null ? null : List.copyOf(differences);
        }
    }

    /** The frozen {@code wire(task)} projection of one fams_inventory_task row. */
    static TaskRow taskRow(ObjectMapper json, Map<String, Object> row) {
        return new TaskRow(
                id(row, "id"),
                id(row, "name"),
                id(row, "task_status"),
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
                id(row, "end_reason"));
    }

    /** The frozen {@code cast(wire(row))} projection of one sealed report header row. */
    static SealedReportRow sealedReportRow(ObjectMapper json, Map<String, Object> row) {
        return new SealedReportRow(
                id(row, "task_id"),
                num(row, "task_version"),
                id(row, "timezone_id"),
                num(row, "expected_count"),
                num(row, "accepted_count"),
                num(row, "not_observed_count"),
                num(row, "captured_count"),
                id(row, "actor_account_id"),
                id(row, "actor_username"),
                id(row, "seal_reason"),
                id(row, "missing_sync_reason"),
                id(row, "sync_digest"),
                id(row, "report_sha256"),
                node(json, row, "snapshot_json"),
                time(row, "created_at"));
    }

    /** The frozen {@code wire(row)} projection of one sealed snapshot page row. */
    static SnapshotRow snapshotRow(ObjectMapper json, Map<String, Object> row) {
        return new SnapshotRow(
                node(json, row, "snapshot_json"), id(row, "snapshot_sha256"));
    }

    /**
     * The legacy device() LinkedHashMap assembly: the four always-put keys plus the three
     * declaration keys only when the device has a declaration row.
     */
    static DeviceRow device(
            String deviceId, Long bindingId, String reason, Map<String, Object> declaration) {
        if (declaration == null) {
            return new DeviceRow(deviceId, text(bindingId), "DECLARED".equals(reason), reason,
                    null, null, null);
        }
        return new DeviceRow(
                deviceId,
                text(bindingId),
                "DECLARED".equals(reason),
                reason,
                declaration.get("id").toString(),
                time(declaration, "declared_at"),
                num(declaration, "unresolved_count"));
    }

    /** The legacy {@code binding == null ? null : binding.toString()} chip. */
    private static String text(Long value) {
        return value == null ? null : value.toString();
    }

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

    /** The legacy wire() DATETIME branch: ISO instants for both driver shapes, null stays null. */
    static String timeValue(Object value) {
        if (value == null) {
            return null;
        }
        return (value instanceof Timestamp timestamp
                        ? timestamp.toInstant()
                        : ((LocalDateTime) value).toInstant(ZoneOffset.UTC))
                .toString();
    }

    private static JsonNode node(ObjectMapper json, Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : json.readTree(value.toString());
    }
}
