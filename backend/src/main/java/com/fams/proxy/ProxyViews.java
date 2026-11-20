package com.fams.proxy;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed wire shapes of the cross-domain proxy receipt envelope (JAVA-04), mirroring
 * contracts/fams-core-v1.yaml ProxyConfirmationSnapshot (oneOf, discriminator businessType).
 * Identifiers are strings and stored snapshots stay raw JSON, exactly as the previous
 * Map-based domain projections serialized them.
 */
public final class ProxyViews {
    private ProxyViews() {
    }

    /**
     * The three domain snapshots share the businessType key already present on the wire and
     * constrained by the contract discriminator, so replay resolves the subtype through
     * EXISTING_PROPERTY and stored JSON gains no extra property (JAVA-02 Followup precedent).
     * targetEmployeeId and assetCode are the only fields the orchestrator reads generically.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "businessType", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = RequisitionSnapshot.class, name = "REQUISITION"),
            @JsonSubTypes.Type(value = TransferSnapshot.class, name = "TRANSFER"),
            @JsonSubTypes.Type(value = RepairSnapshot.class, name = "REPAIR")})
    public sealed interface ProxySnapshot permits RequisitionSnapshot, TransferSnapshot, RepairSnapshot {
        String businessType();

        String targetEmployeeId();

        String assetCode();
    }

    public record RequisitionSnapshot(
            String businessType,
            String businessId,
            String itemId,
            String requestNo,
            long version,
            long requestVersion,
            long itemVersion,
            String assetId,
            long assetVersion,
            String assetCode,
            String targetEmployeeId,
            String custodyType,
            long lockVersion,
            String processorAccountId,
            long ownershipVersion,
            long demandVersion) implements ProxySnapshot {
    }

    public record TransferSnapshot(
            String businessType,
            String businessId,
            String itemId,
            String requestNo,
            long version,
            long approvedVersion,
            long itemVersion,
            String assetId,
            long assetVersion,
            String assetCode,
            String targetEmployeeId,
            String custodyType,
            String processorAccountId,
            long ownershipVersion,
            long lockVersion,
            JsonNode sourceSnapshot,
            JsonNode targetSnapshot,
            JsonNode parameterSnapshot) implements ProxySnapshot {
    }

    /**
     * Repair keeps the asset-owned return target and the loan summary as raw maps: both are
     * produced by excluded packages (asset/loan) and their JDBC-derived values pass through
     * verbatim. deliveredAt stays the raw wire() projection for the same reason.
     */
    public record RepairSnapshot(
            String businessType,
            String businessId,
            String itemId,
            String assetId,
            long version,
            long ownershipVersion,
            String processorAccountId,
            long assetVersion,
            String assetCode,
            String handoverId,
            Object deliveredAt,
            String targetEmployeeId,
            Map<String, Object> target,
            Map<String, Object> loan,
            String approvedTargetId,
            Long lockVersion) implements ProxySnapshot {
        public RepairSnapshot {
            target = target == null ? null : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(target));
            loan = loan == null ? null : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(loan));
        }
    }

    /** Execution results persisted into confirmation rows and result notices. */
    public sealed interface ReceiptResult permits TransferReceiptResult, RequisitionReceiptResult, RepairReceiptResult {
        String targetEmployeeId();
    }

    public record TransferReceiptResult(
            String confirmationId,
            String requestId,
            String itemId,
            String result,
            String assetCode,
            String targetEmployeeId) implements ReceiptResult {
    }

    public record RequisitionReceiptResult(
            String confirmationId,
            String requestId,
            String allocationId,
            String result,
            String targetEmployeeId,
            String assetCode) implements ReceiptResult {
    }

    public record RepairReceiptResult(
            String businessId,
            String repairId,
            String itemId,
            String assetCode,
            String targetEmployeeId,
            String status,
            long version) implements ReceiptResult {
    }

    /** Confirmation responses persisted in idempotency records expose their resource identity. */
    public interface Reply {
        String id();

        long version();
    }

    /**
     * Row projection of fams_proxy_confirmation. DATETIME columns stay the raw JDBC value
     * (LocalDateTime or Timestamp across driver versions) because the previous wire() passed
     * them through untouched; the HTTP mapper renders them exactly as before.
     */
    public record Confirmation(
            String id,
            String businessType,
            String businessId,
            String itemId,
            JsonNode businessSnapshotJson,
            JsonNode evidenceMetadataJson,
            String confirmationStatus,
            long version,
            String previousConfirmationId,
            String observedAssetCode,
            String actualSigner,
            Object actualReceivedAt,
            String cannotConfirmReason,
            String contactRecord,
            Boolean delegatedReceipt,
            String delegationRelationship,
            String delegationBasis,
            String evidenceKind,
            String collectionMethod,
            String manualReason,
            JsonNode evidenceSnapshotJson,
            String creatorAccountId,
            Object submittedAt,
            String approverAccountId,
            String decisionReason,
            Object decidedAt,
            String executorAccountId,
            Object executedAt,
            JsonNode executionResultJson,
            Object createdAt,
            Object updatedAt) implements Reply {
    }

    public record ConfirmationPage(
            List<Confirmation> items, int page, int pageSize, long total, boolean canApprove, boolean canExecute) {
        public ConfirmationPage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** Frozen pre-JAVA-04 RequisitionProxyConfirmationService.wire projection. */
    public static Confirmation confirmation(ObjectMapper json, Map<String, Object> row) {
        var projected = new LinkedHashMap<String, Object>();
        row.forEach(
                (k, v) -> {
                    StringBuilder key = new StringBuilder();
                    boolean upper = false;
                    for (char c : k.toCharArray()) {
                        if (c == '_') {
                            upper = true;
                        } else {
                            key.append(upper ? Character.toUpperCase(c) : c);
                            upper = false;
                        }
                    }
                    projected.put(
                            key.toString(),
                            v == null
                                    ? null
                                    : k.endsWith("_json")
                                            ? json.readTree(v.toString())
                                            : k.equals("id") || k.endsWith("_id")
                                                    ? v.toString()
                                                    : v);
                });
        return new Confirmation(
                (String) projected.get("id"),
                (String) projected.get("businessType"),
                (String) projected.get("businessId"),
                (String) projected.get("itemId"),
                (JsonNode) projected.get("businessSnapshotJson"),
                (JsonNode) projected.get("evidenceMetadataJson"),
                (String) projected.get("confirmationStatus"),
                ((Number) projected.get("version")).longValue(),
                (String) projected.get("previousConfirmationId"),
                (String) projected.get("observedAssetCode"),
                (String) projected.get("actualSigner"),
                projected.get("actualReceivedAt"),
                (String) projected.get("cannotConfirmReason"),
                (String) projected.get("contactRecord"),
                (Boolean) projected.get("delegatedReceipt"),
                (String) projected.get("delegationRelationship"),
                (String) projected.get("delegationBasis"),
                (String) projected.get("evidenceKind"),
                (String) projected.get("collectionMethod"),
                (String) projected.get("manualReason"),
                (JsonNode) projected.get("evidenceSnapshotJson"),
                (String) projected.get("creatorAccountId"),
                projected.get("submittedAt"),
                (String) projected.get("approverAccountId"),
                (String) projected.get("decisionReason"),
                projected.get("decidedAt"),
                (String) projected.get("executorAccountId"),
                projected.get("executedAt"),
                (JsonNode) projected.get("executionResultJson"),
                projected.get("createdAt"),
                projected.get("updatedAt"));
    }
}
