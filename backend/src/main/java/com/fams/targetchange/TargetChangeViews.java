package com.fams.targetchange;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed wire shapes of the cross-domain target change envelope (JAVA-04). Contexts are only
 * compared as JSON trees and serialized into source_snapshot_json, never deserialized back
 * into a typed context, so the interface needs no Jackson polymorphism. It is deliberately
 * not sealed: the transfer context record stays in TransferViews (JAVA-01) and implements
 * this interface covariantly, keeping the transfer-to-targetchange dependency one-way
 * (ArchUnit top-level modules must remain acyclic).
 */
public final class TargetChangeViews {
    private TargetChangeViews() {
    }

    /**
     * The workflow reads version/assetCode generically and serializes target for tree
     * comparison only; the three domains keep their own target shapes (typed transfer wire,
     * raw requisition/repair maps), hence the Object accessor.
     */
    public interface Context {
        String businessType();

        long version();

        String assetCode();

        Object target();
    }

    /** Proposal/decision responses persisted in idempotency records expose resource identity. */
    public interface Reply {
        String id();

        long version();
    }

    /**
     * clean() keeps the raw snake_case column names; *_json columns become trees, DATETIME
     * columns ISO instants and id/*_id columns strings. targetDisplay is a conditional key,
     * present only when the domain produced a non-empty display.
     */
    public record Change(
            String id,
            @JsonProperty("business_type") String businessType,
            @JsonProperty("business_id") String businessId,
            @JsonProperty("item_id") String itemId,
            @JsonProperty("source_snapshot_json") JsonNode sourceSnapshotJson,
            @JsonProperty("proposed_target_json") JsonNode proposedTargetJson,
            String reason,
            @JsonProperty("observed_asset_code") String observedAssetCode,
            @JsonProperty("physical_verification") String physicalVerification,
            @JsonProperty("change_status") String changeStatus,
            @JsonProperty("creator_account_id") String creatorAccountId,
            @JsonProperty("approver_account_id") String approverAccountId,
            @JsonProperty("decision_reason") String decisionReason,
            long version,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("decided_at") String decidedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> targetDisplay) implements Reply {
        public Change {
            targetDisplay = targetDisplay == null ? null : Map.copyOf(targetDisplay);
        }
    }

    public record Page(List<Change> items, long total, int page, int pageSize, boolean canApprove, boolean canExecute) {
        public Page {
            items = items == null ? null : List.copyOf(items);
        }
    }

    public record RequisitionContext(
            String businessType,
            String businessId,
            String itemId,
            long requestVersion,
            long version,
            long itemVersion,
            String itemStatus,
            long demandVersion,
            String assetId,
            long assetVersion,
            String assetCode,
            RequisitionTarget target,
            long ownershipVersion,
            String processorAccountId,
            long lockVersion,
            List<Handover> handover) implements Context {
        public RequisitionContext {
            handover = handover == null ? null : List.copyOf(handover);
        }
    }

    public record RequisitionTarget(
            String custodyType,
            String organizationId,
            long organizationVersion,
            String employeeId,
            long employeeVersion,
            String locationId,
            long locationVersion) {
    }

    /**
     * Handover rows keep the raw JDBC time values: the requisition clean() projection only
     * converts LocalDateTime and passes Timestamp through untouched (unlike the workflow
     * clean()), so the components stay Object to avoid guessing driver behaviour.
     */
    public record Handover(Object deliveredAt, Object receiptDeadlineAt, String destinationText) {
    }

    /**
     * Repair keeps the approved return target as a raw map: it is read from stored JSON or
     * built from the asset-owned source snapshot, and its JDBC-derived values pass through
     * verbatim. lastPhysicalTime stays the raw repair wire() projection for the same reason.
     */
    public record RepairContext(
            String businessType,
            String businessId,
            String itemId,
            long version,
            long ownershipVersion,
            String processorAccountId,
            long assetVersion,
            String assetCode,
            Map<String, Object> target,
            String approvedTargetId,
            Long loanVersion,
            Object lastPhysicalTime,
            Long lockVersion) implements Context {
        public RepairContext {
            target = target == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(target));
        }
    }

    /** Frozen pre-JAVA-04 TargetChangeWorkflow.clean projection (display appended by caller). */
    public static Change change(ObjectMapper json, Map<String, Object> row, Map<String, String> display) {
        var projected = new LinkedHashMap<String, Object>();
        for (var entry : row.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value != null && key.endsWith("_json")) {
                value = json.readTree(value.toString());
            } else if (value instanceof Timestamp timestamp) {
                value = timestamp.toInstant().toString();
            } else if (value instanceof LocalDateTime time) {
                value = time.toInstant(ZoneOffset.UTC).toString();
            } else if (value != null && (key.equals("id") || key.endsWith("_id"))) {
                value = value.toString();
            }
            projected.put(key, value);
        }
        return new Change(
                (String) projected.get("id"),
                (String) projected.get("business_type"),
                (String) projected.get("business_id"),
                (String) projected.get("item_id"),
                (JsonNode) projected.get("source_snapshot_json"),
                (JsonNode) projected.get("proposed_target_json"),
                (String) projected.get("reason"),
                (String) projected.get("observed_asset_code"),
                (String) projected.get("physical_verification"),
                (String) projected.get("change_status"),
                (String) projected.get("creator_account_id"),
                (String) projected.get("approver_account_id"),
                (String) projected.get("decision_reason"),
                ((Number) projected.get("version")).longValue(),
                (String) projected.get("created_at"),
                (String) projected.get("decided_at"),
                display == null || display.isEmpty() ? null : display);
    }

    /** Frozen pre-JAVA-04 RequisitionHandoverService.clean projection for handover rows. */
    public static Handover handover(ObjectMapper json, Map<String, Object> row) {
        var projected = new LinkedHashMap<String, Object>();
        row.forEach(
                (key, value) -> {
                    StringBuilder name = new StringBuilder();
                    boolean upper = false;
                    for (char c : key.toCharArray()) {
                        if (c == '_') {
                            upper = true;
                        } else {
                            name.append(upper ? Character.toUpperCase(c) : c);
                            upper = false;
                        }
                    }
                    projected.put(
                            name.toString(),
                            value == null
                                    ? null
                                    : key.endsWith("_json")
                                            ? json.readTree(value.toString())
                                            : key.equals("id") || key.endsWith("_id")
                                                    ? value.toString()
                                                    : value instanceof LocalDateTime time
                                                            ? time.toInstant(ZoneOffset.UTC).toString()
                                                            : value);
                });
        return new Handover(
                projected.get("deliveredAt"), projected.get("receiptDeadlineAt"), (String) projected.get("destinationText"));
    }
}
