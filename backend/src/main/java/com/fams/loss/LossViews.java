package com.fams.loss;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.fams.asset.AssetLossPort;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Typed wire shapes of the loss domain, mirroring contracts/fams-core-v1.yaml.
 * {@link LossReport} is the frozen pre-JAVA-11a LossService.view projection: {@code id} and
 * {@code *_id} columns stringify, DATETIME columns become ISO instants (Timestamp/LocalDateTime
 * driver shapes), DATE becomes a yyyy-MM-dd string, the source snapshot stays raw JSON, and
 * repairId/returnId are always present (explicit null included) while transferId is conditional.
 * {@link LossEvent} keeps the frozen wire() row projection under its original snake_case keys.
 * Conditional keys use NON_NULL; explicit null keys keep default inclusion.
 */
public final class LossViews {
    private LossViews() {
    }

    public record Page<T>(List<T> items, long total, int page, int pageSize) {
        public Page {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** fams_loss_report header row; the shape stored in every non-recovery loss receipt. */
    public record LossReport(
            String id,
            String assetId,
            String creatorAccountId,
            long version,
            long assetVersion,
            String status,
            String lastSeenAt,
            String lastLocation,
            String circumstances,
            String processorAccountId,
            long ownershipVersion,
            String recoveredAt,
            String confirmedLostAt,
            String lastFollowUpAt,
            String nextReviewDate,
            JsonNode source,
            String repairId,
            String returnId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String transferId) {
    }

    /** Loss report after a recovery-request receipt: view keys plus the three recovery keys. */
    public record LossReportWithRecovery(
            String id,
            String assetId,
            String creatorAccountId,
            long version,
            long assetVersion,
            String status,
            String lastSeenAt,
            String lastLocation,
            String circumstances,
            String processorAccountId,
            long ownershipVersion,
            String recoveredAt,
            String confirmedLostAt,
            String lastFollowUpAt,
            String nextReviewDate,
            JsonNode source,
            String repairId,
            String returnId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String transferId,
            String recoveryId,
            long recoveryVersion,
            String recoveryStatus) {
    }

    /** get() header detail; every extra key is always present, nulls included. */
    public record LossDetail(
            String id,
            String assetId,
            String creatorAccountId,
            long version,
            long assetVersion,
            String status,
            String lastSeenAt,
            String lastLocation,
            String circumstances,
            String processorAccountId,
            long ownershipVersion,
            String recoveredAt,
            String confirmedLostAt,
            String lastFollowUpAt,
            String nextReviewDate,
            JsonNode source,
            String repairId,
            String returnId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String transferId,
            Long lockVersion,
            boolean photosAvailable,
            List<LossService.Photo> photos,
            List<LossEvent> history,
            long historyTotal,
            JsonNode confirmation,
            AssetLossPort.RecoveryView recoveryAsset,
            List<LossRecovery> recoveries,
            long recoveriesTotal,
            ReportActions actions) {
        public LossDetail {
            photos = photos == null ? null : List.copyOf(photos);
            history = history == null ? null : List.copyOf(history);
            recoveries = recoveries == null ? null : List.copyOf(recoveries);
        }
    }

    /** Header-detail action matrix; keys mirror the pre-typing actions LinkedHashMap. */
    public record ReportActions(
            boolean recoverOriginal,
            boolean requestRecovery,
            boolean confirmLost,
            boolean followUp,
            boolean decide,
            boolean cancel,
            boolean addPhoto,
            boolean claim,
            boolean search,
            boolean recoverFactory,
            boolean requestFactoryRecovery,
            boolean recoverInternalRepair,
            boolean requestInternalRepairRecovery,
            boolean requestScrap) {
    }

    /**
     * fams_loss_event history row. The frozen wire() projection renamed nothing, so the
     * original snake_case keys stay on the wire (JAVA-02 RepairFollowupSource precedent);
     * {@code id}/{@code *_id} are stringified, versions stay numbers and detail_json is the
     * raw LONGTEXT JSON string.
     */
    public record LossEvent(
            @JsonProperty("id") String id,
            @JsonProperty("report_id") String reportId,
            @JsonProperty("action") String action,
            @JsonProperty("before_version") long beforeVersion,
            @JsonProperty("after_version") long afterVersion,
            @JsonProperty("actor_account_id") String actorAccountId,
            @JsonProperty("actor_username") String actorUsername,
            @JsonProperty("actor_role") String actorRole,
            @JsonProperty("detail_json") String detailJson,
            @JsonProperty("occurred_at") String occurredAt) {
    }

    /** One fams_loss_recovery attempt row inside the header detail. */
    public record LossRecovery(
            String id,
            long version,
            long assetVersion,
            String status,
            String creatorAccountId,
            String processorAccountId,
            long ownershipVersion,
            JsonNode request,
            JsonNode approval,
            JsonNode execution,
            RecoveryActions actions) {
    }

    /** Per-recovery action matrix; keys mirror the pre-typing Map.of (order never contractual). */
    public record RecoveryActions(
            boolean decide, boolean cancel, boolean claim, boolean execute) {
    }

    /** registerPhoto receipt stored under the PHOTO operation (LossPhotoReply contract shape). */
    public record PhotoReceipt(String id, String objectId, long version) {
    }

    /** download-grant reply assembled by the controller from the signer's Grant. */
    public record PhotoGrant(String url, long expiresAtEpochSecond) {
    }

    /** Verbatim pre-JAVA-11a LossService.view projection of a fams_loss_report header row. */
    static LossReport lossReport(ObjectMapper json, Map<String, Object> row) {
        var recovery = row.get("recovery_snapshot_json");
        var recoveryTree = recovery == null ? null : json.readTree(recovery.toString());
        var repair = recoveryTree == null ? null : recoveryTree.get("repairId");
        var linkedReturn = recoveryTree == null ? null : recoveryTree.get("returnId");
        var linkedTransfer = recoveryTree == null ? null : recoveryTree.get("transferId");
        return new LossReport(
                id(row, "id"),
                id(row, "asset_id"),
                id(row, "creator_account_id"),
                num(row, "version"),
                num(row, "asset_version"),
                (String) row.get("report_status"),
                time(row, "last_seen_at"),
                (String) row.get("last_location"),
                (String) row.get("circumstances"),
                id(row, "processor_account_id"),
                num(row, "ownership_version"),
                time(row, "recovered_at"),
                time(row, "confirmed_lost_at"),
                time(row, "last_follow_up_at"),
                date(row, "next_review_date"),
                json.readTree(row.get("source_snapshot_json").toString()),
                repair == null ? null : repair.asText(),
                linkedReturn == null ? null : linkedReturn.asText(),
                linkedTransfer == null ? null : linkedTransfer.asText());
    }

    /** Copy of a loss report with an explicit repairId (the recoverToRepair receipt override). */
    static LossReport withRepairId(LossReport base, String repairId) {
        return new LossReport(
                base.id(),
                base.assetId(),
                base.creatorAccountId(),
                base.version(),
                base.assetVersion(),
                base.status(),
                base.lastSeenAt(),
                base.lastLocation(),
                base.circumstances(),
                base.processorAccountId(),
                base.ownershipVersion(),
                base.recoveredAt(),
                base.confirmedLostAt(),
                base.lastFollowUpAt(),
                base.nextReviewDate(),
                base.source(),
                repairId,
                base.returnId(),
                base.transferId());
    }

    static LossReportWithRecovery lossReportWithRecovery(
            ObjectMapper json, Map<String, Object> row, String recoveryId, long recoveryVersion,
            String recoveryStatus) {
        var base = lossReport(json, row);
        return new LossReportWithRecovery(
                base.id(),
                base.assetId(),
                base.creatorAccountId(),
                base.version(),
                base.assetVersion(),
                base.status(),
                base.lastSeenAt(),
                base.lastLocation(),
                base.circumstances(),
                base.processorAccountId(),
                base.ownershipVersion(),
                base.recoveredAt(),
                base.confirmedLostAt(),
                base.lastFollowUpAt(),
                base.nextReviewDate(),
                base.source(),
                base.repairId(),
                base.returnId(),
                base.transferId(),
                recoveryId,
                recoveryVersion,
                recoveryStatus);
    }

    static LossDetail lossDetail(
            LossReport base, Long lockVersion, boolean photosAvailable, List<LossService.Photo> photos,
            List<LossEvent> history, long historyTotal, JsonNode confirmation,
            AssetLossPort.RecoveryView recoveryAsset, List<LossRecovery> recoveries,
            long recoveriesTotal, ReportActions actions) {
        return new LossDetail(
                base.id(),
                base.assetId(),
                base.creatorAccountId(),
                base.version(),
                base.assetVersion(),
                base.status(),
                base.lastSeenAt(),
                base.lastLocation(),
                base.circumstances(),
                base.processorAccountId(),
                base.ownershipVersion(),
                base.recoveredAt(),
                base.confirmedLostAt(),
                base.lastFollowUpAt(),
                base.nextReviewDate(),
                base.source(),
                base.repairId(),
                base.returnId(),
                base.transferId(),
                lockVersion,
                photosAvailable,
                photos,
                history,
                historyTotal,
                confirmation,
                recoveryAsset,
                recoveries,
                recoveriesTotal,
                actions);
    }

    /** Verbatim pre-JAVA-11a wire() projection restricted to a fams_loss_event row. */
    static LossEvent lossEvent(Map<String, Object> row) {
        return new LossEvent(
                id(row, "id"),
                id(row, "report_id"),
                (String) row.get("action"),
                num(row, "before_version"),
                num(row, "after_version"),
                id(row, "actor_account_id"),
                (String) row.get("actor_username"),
                (String) row.get("actor_role"),
                (String) row.get("detail_json"),
                time(row, "occurred_at"));
    }

    /** Verbatim pre-JAVA-11a recoveryView row projection; the action booleans come from the caller. */
    static LossRecovery lossRecovery(
            ObjectMapper json, Map<String, Object> row, RecoveryActions actions) {
        return new LossRecovery(
                id(row, "id"),
                num(row, "version"),
                num(row, "asset_version"),
                (String) row.get("recovery_status"),
                id(row, "creator_account_id"),
                id(row, "processor_account_id"),
                num(row, "ownership_version"),
                json.readTree(row.get("request_snapshot_json").toString()),
                row.get("approval_snapshot_json") == null
                        ? null
                        : json.readTree(row.get("approval_snapshot_json").toString()),
                row.get("execution_snapshot_json") == null
                        ? null
                        : json.readTree(row.get("execution_snapshot_json").toString()),
                actions);
    }

    private static String id(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    private static long num(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static String time(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        return (value instanceof Timestamp time
                ? time.toInstant()
                : ((LocalDateTime) value).toInstant(ZoneOffset.UTC)).toString();
    }

    // wire() stringifies DATE via toLocalDate(); java.sql.Date and LocalDate share the
    // yyyy-MM-dd text form (JAVA-01 dual-driver-shape rule).
    private static String date(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }
}
