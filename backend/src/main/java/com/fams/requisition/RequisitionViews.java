package com.fams.requisition;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fams.masterdata.RequisitionMasterDataPort;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Typed wire shapes of the requisition domain core and handover exits, mirroring the frozen
 * pre-JAVA-14b1 row projections. {@code RequisitionService.clean} semantics: identifier columns
 * stringify, DATETIME columns become ISO instants (Timestamp/LocalDateTime driver shapes),
 * DATE columns become yyyy-MM-dd strings, JSON columns stay raw JSON trees and every other
 * column keeps its JDBC value; each column key is always present, explicit nulls included
 * (global JsonConfiguration inclusion is ALWAYS). The handover pending/receipt projections
 * keep the pre-refactor handover clean(): only LocalDateTime converts, so DATETIME values of
 * other driver shapes pass through verbatim (Object components, OpenLoanRef precedent).
 */
public final class RequisitionViews {
    private RequisitionViews() {
    }

    /** Responses persisted in idempotency records expose their resource version. */
    public sealed interface Reply permits Detail, ReceiptResult {
        long version();
    }

    public record Page<T>(List<T> items, long total, int page, int pageSize) {
        public Page {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /**
     * Catalog envelope; item choices are the already typed masterdata rows where organizationId
     * exists only on the employee kind (NON_NULL conditional key frozen by JAVA-09).
     */
    public record CatalogPage(
            List<RequisitionMasterDataPort.CatalogChoice> items,
            int page,
            int pageSize,
            String employeeId,
            String organizationId,
            boolean canApplyShared) {
        public CatalogPage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** fams_requisition header row, the list item and detail prefix shape. */
    public record Summary(
            String id,
            String requestNo,
            String creatorAccountId,
            String creatorUsernameSnapshot,
            String requestStatus,
            long requestVersion,
            String parameterVersionId,
            JsonNode parameterSnapshotJson,
            String sourceRequestId,
            String sourceDemandId,
            JsonNode sourceReasonSnapshotJson,
            String submittedAt,
            String completedAt,
            long version,
            String createdAt,
            String updatedAt,
            String requestType,
            String expectedReturnDate) {
    }

    public record Demand(
            String id,
            String requestId,
            long lineNo,
            String categoryId,
            long categoryVersionSnapshot,
            String categoryNameSnapshot,
            String specification,
            String preferredBrand,
            String preferredModel,
            String purpose,
            String expectedDate,
            String custodyType,
            String targetOrganizationId,
            long organizationVersionSnapshot,
            String targetEmployeeId,
            String targetDepartmentEmployeeId,
            long employeeVersionSnapshot,
            String targetLocationId,
            long locationVersionSnapshot,
            JsonNode targetSnapshotJson,
            long requestedQuantity,
            long pendingAllocationQuantity,
            long pendingHandoverQuantity,
            long completedQuantity,
            long unfulfillableQuantity,
            long cancelledQuantity,
            long version) {
    }

    public record Handover(
            String allocationId,
            String deliveredByAccountId,
            String deliveredAt,
            String receiptDeadlineAt,
            String destinationText,
            String temporaryEmployeeId,
            String deliveryEvidence,
            JsonNode targetSnapshotJson,
            JsonNode formalCustodySnapshotJson) {
    }

    public record HandoverEvent(
            String id,
            String allocationId,
            String eventType,
            String actorAccountId,
            String occurredAt,
            String observedAssetCode,
            String evidenceText,
            long allocationVersionBefore,
            long allocationVersionAfter,
            String auditEventId) {
    }

    public record Decision(
            String id,
            String requestId,
            long requestVersion,
            String decisionResult,
            JsonNode approvedScopeJson,
            String commentText,
            String decidedByAccountId,
            String decidedAt,
            String auditEventId) {
    }

    public record Allocation(
            String id,
            String requestId,
            String demandId,
            String ownershipId,
            String assetId,
            long assetVersionSnapshot,
            JsonNode assetSnapshotJson,
            String businessLockId,
            String allocationStatus,
            String predecessorAllocationId,
            long version,
            String allocatedAt,
            String deliveredAt,
            String receivedAt,
            String cancelledAt,
            String cancellationReason,
            String auditEventId,
            JsonNode approvedTargetJson,
            String receiptTargetEmployeeId,
            long lockVersion) {
    }

    public record Unfulfillable(
            String id,
            String requestId,
            String demandId,
            long quantity,
            String reasonCode,
            String reasonDescription,
            JsonNode requirementSnapshotJson,
            JsonNode inventoryQuerySummaryJson,
            String recordedByAccountId,
            String recordedAt,
            String auditEventId) {
    }

    /** activeGuard keeps its raw JDBC value: the TINYINT driver shape is not fixed. */
    public record Ownership(
            String id,
            String requestId,
            String processorAccountId,
            long ownershipVersion,
            String acquiredAt,
            String archivedAt,
            String archiveReason,
            Object activeGuard) {
    }

    public record ChangeProposal(
            String id,
            String requestId,
            long requestVersion,
            long previousRequestVersion,
            String previousRequestStatus,
            JsonNode proposalJson,
            String proposalStatus,
            String proposedByAccountId,
            String proposedAt,
            String decidedAt,
            String auditEventId) {
    }

    public record Followup(
            String sourceRequestId,
            String sourceDemandId,
            String latestRequestId,
            long version,
            String updatedAt) {
    }

    /** get/create/revise/submit/change/cancel/decide/claim/allocate/unfulfillable/dispute/followup body. */
    public record Detail(
            String id,
            String requestNo,
            String creatorAccountId,
            String creatorUsernameSnapshot,
            String requestStatus,
            long requestVersion,
            String parameterVersionId,
            JsonNode parameterSnapshotJson,
            String sourceRequestId,
            String sourceDemandId,
            JsonNode sourceReasonSnapshotJson,
            String submittedAt,
            String completedAt,
            long version,
            String createdAt,
            String updatedAt,
            String requestType,
            String expectedReturnDate,
            List<Demand> demands,
            List<Handover> handovers,
            List<HandoverEvent> handoverEvents,
            List<Decision> decisions,
            List<Allocation> allocations,
            List<Unfulfillable> unfulfillable,
            List<Ownership> ownership,
            List<ChangeProposal> changes,
            List<Followup> followups) implements Reply {
        public Detail {
            demands = demands == null ? null : List.copyOf(demands);
            handovers = handovers == null ? null : List.copyOf(handovers);
            handoverEvents = handoverEvents == null ? null : List.copyOf(handoverEvents);
            decisions = decisions == null ? null : List.copyOf(decisions);
            allocations = allocations == null ? null : List.copyOf(allocations);
            unfulfillable = unfulfillable == null ? null : List.copyOf(unfulfillable);
            ownership = ownership == null ? null : List.copyOf(ownership);
            changes = changes == null ? null : List.copyOf(changes);
            followups = followups == null ? null : List.copyOf(followups);
        }
    }

    /** Inventory picker row (fams_asset columns narrowed by the inventory query). */
    public record InventoryAsset(
            String id,
            String assetCode,
            String name,
            String brandId,
            String model,
            long version,
            String categoryId) {
    }

    /** Pending receipt row under the handover clean projection. */
    public record PendingReceiptItem(
            String id,
            String requestId,
            String requestNo,
            JsonNode assetSnapshotJson,
            long version,
            Object deliveredAt,
            Object receiptDeadlineAt,
            String destinationText) {
    }

    public record Grant(
            String grant, String expiresAt, long requestVersion, long allocationVersion) {
    }

    public record ReceiptResult(String allocationId, String requestId, String result, long version)
            implements Reply {
    }

    /** Receipt head delivered_at/receipt_deadline_at stay raw JDBC values (handover clean). */
    public record ReceiptHandover(Object deliveredAt, Object receiptDeadlineAt, String destinationText) {
    }

    public record ReceiptDetail(
            String requestId,
            String requestNo,
            String requestType,
            String expectedReturnDate,
            long requestVersion,
            String allocationId,
            String allocationStatus,
            long allocationVersion,
            JsonNode asset,
            ReceiptHandover handover) {
    }

    static Summary summary(ObjectMapper json, Map<String, Object> row) {
        return new Summary(
                id(row, "id"),
                (String) row.get("request_no"),
                id(row, "creator_account_id"),
                (String) row.get("creator_username_snapshot"),
                (String) row.get("request_status"),
                num(row, "request_version"),
                id(row, "parameter_version_id"),
                node(json, row, "parameter_snapshot_json"),
                id(row, "source_request_id"),
                id(row, "source_demand_id"),
                node(json, row, "source_reason_snapshot_json"),
                time(row, "submitted_at"),
                time(row, "completed_at"),
                num(row, "version"),
                time(row, "created_at"),
                time(row, "updated_at"),
                (String) row.get("request_type"),
                date(row, "expected_return_date"));
    }

    static Detail detail(
            ObjectMapper json,
            Map<String, Object> header,
            List<Map<String, Object>> demandRows,
            List<Map<String, Object>> handoverRows,
            List<Map<String, Object>> handoverEventRows,
            List<Map<String, Object>> decisionRows,
            List<Map<String, Object>> allocationRows,
            List<Map<String, Object>> unfulfillableRows,
            List<Map<String, Object>> ownershipRows,
            List<Map<String, Object>> changeRows,
            List<Map<String, Object>> followupRows) {
        var s = summary(json, header);
        return new Detail(
                s.id(),
                s.requestNo(),
                s.creatorAccountId(),
                s.creatorUsernameSnapshot(),
                s.requestStatus(),
                s.requestVersion(),
                s.parameterVersionId(),
                s.parameterSnapshotJson(),
                s.sourceRequestId(),
                s.sourceDemandId(),
                s.sourceReasonSnapshotJson(),
                s.submittedAt(),
                s.completedAt(),
                s.version(),
                s.createdAt(),
                s.updatedAt(),
                s.requestType(),
                s.expectedReturnDate(),
                demandRows.stream().map(row -> demand(json, row)).toList(),
                handoverRows.stream().map(row -> handover(json, row)).toList(),
                handoverEventRows.stream().map(row -> handoverEvent(row)).toList(),
                decisionRows.stream().map(row -> decision(json, row)).toList(),
                allocationRows.stream().map(row -> allocation(json, row)).toList(),
                unfulfillableRows.stream().map(row -> unfulfillable(json, row)).toList(),
                ownershipRows.stream().map(RequisitionViews::ownership).toList(),
                changeRows.stream().map(row -> changeProposal(json, row)).toList(),
                followupRows.stream().map(RequisitionViews::followup).toList());
    }

    static Demand demand(ObjectMapper json, Map<String, Object> row) {
        return new Demand(
                id(row, "id"),
                id(row, "request_id"),
                num(row, "line_no"),
                id(row, "category_id"),
                num(row, "category_version_snapshot"),
                (String) row.get("category_name_snapshot"),
                (String) row.get("specification"),
                (String) row.get("preferred_brand"),
                (String) row.get("preferred_model"),
                (String) row.get("purpose"),
                date(row, "expected_date"),
                (String) row.get("custody_type"),
                id(row, "target_organization_id"),
                num(row, "organization_version_snapshot"),
                id(row, "target_employee_id"),
                id(row, "target_department_employee_id"),
                num(row, "employee_version_snapshot"),
                id(row, "target_location_id"),
                num(row, "location_version_snapshot"),
                node(json, row, "target_snapshot_json"),
                num(row, "requested_quantity"),
                num(row, "pending_allocation_quantity"),
                num(row, "pending_handover_quantity"),
                num(row, "completed_quantity"),
                num(row, "unfulfillable_quantity"),
                num(row, "cancelled_quantity"),
                num(row, "version"));
    }

    static Handover handover(ObjectMapper json, Map<String, Object> row) {
        return new Handover(
                id(row, "allocation_id"),
                id(row, "delivered_by_account_id"),
                time(row, "delivered_at"),
                time(row, "receipt_deadline_at"),
                (String) row.get("destination_text"),
                id(row, "temporary_employee_id"),
                (String) row.get("delivery_evidence"),
                node(json, row, "target_snapshot_json"),
                node(json, row, "formal_custody_snapshot_json"));
    }

    static HandoverEvent handoverEvent(Map<String, Object> row) {
        return new HandoverEvent(
                id(row, "id"),
                id(row, "allocation_id"),
                (String) row.get("event_type"),
                id(row, "actor_account_id"),
                time(row, "occurred_at"),
                (String) row.get("observed_asset_code"),
                (String) row.get("evidence_text"),
                num(row, "allocation_version_before"),
                num(row, "allocation_version_after"),
                id(row, "audit_event_id"));
    }

    static Decision decision(ObjectMapper json, Map<String, Object> row) {
        return new Decision(
                id(row, "id"),
                id(row, "request_id"),
                num(row, "request_version"),
                (String) row.get("decision_result"),
                node(json, row, "approved_scope_json"),
                (String) row.get("comment_text"),
                id(row, "decided_by_account_id"),
                time(row, "decided_at"),
                id(row, "audit_event_id"));
    }

    static Allocation allocation(ObjectMapper json, Map<String, Object> row) {
        return new Allocation(
                id(row, "id"),
                id(row, "request_id"),
                id(row, "demand_id"),
                id(row, "ownership_id"),
                id(row, "asset_id"),
                num(row, "asset_version_snapshot"),
                node(json, row, "asset_snapshot_json"),
                id(row, "business_lock_id"),
                (String) row.get("allocation_status"),
                id(row, "predecessor_allocation_id"),
                num(row, "version"),
                time(row, "allocated_at"),
                time(row, "delivered_at"),
                time(row, "received_at"),
                time(row, "cancelled_at"),
                (String) row.get("cancellation_reason"),
                id(row, "audit_event_id"),
                node(json, row, "approved_target_json"),
                id(row, "receipt_target_employee_id"),
                num(row, "lock_version"));
    }

    static Unfulfillable unfulfillable(ObjectMapper json, Map<String, Object> row) {
        return new Unfulfillable(
                id(row, "id"),
                id(row, "request_id"),
                id(row, "demand_id"),
                num(row, "quantity"),
                (String) row.get("reason_code"),
                (String) row.get("reason_description"),
                node(json, row, "requirement_snapshot_json"),
                node(json, row, "inventory_query_summary_json"),
                id(row, "recorded_by_account_id"),
                time(row, "recorded_at"),
                id(row, "audit_event_id"));
    }

    static Ownership ownership(Map<String, Object> row) {
        return new Ownership(
                id(row, "id"),
                id(row, "request_id"),
                id(row, "processor_account_id"),
                num(row, "ownership_version"),
                time(row, "acquired_at"),
                time(row, "archived_at"),
                (String) row.get("archive_reason"),
                row.get("active_guard"));
    }

    static ChangeProposal changeProposal(ObjectMapper json, Map<String, Object> row) {
        return new ChangeProposal(
                id(row, "id"),
                id(row, "request_id"),
                num(row, "request_version"),
                num(row, "previous_request_version"),
                (String) row.get("previous_request_status"),
                node(json, row, "proposal_json"),
                (String) row.get("proposal_status"),
                id(row, "proposed_by_account_id"),
                time(row, "proposed_at"),
                time(row, "decided_at"),
                id(row, "audit_event_id"));
    }

    static Followup followup(Map<String, Object> row) {
        return new Followup(
                id(row, "source_request_id"),
                id(row, "source_demand_id"),
                id(row, "latest_request_id"),
                num(row, "version"),
                time(row, "updated_at"));
    }

    static InventoryAsset inventoryAsset(Map<String, Object> row) {
        return new InventoryAsset(
                id(row, "id"),
                (String) row.get("asset_code"),
                (String) row.get("name"),
                id(row, "brand_id"),
                (String) row.get("model"),
                num(row, "version"),
                id(row, "category_id"));
    }

    static PendingReceiptItem pendingReceiptItem(ObjectMapper json, Map<String, Object> row) {
        return new PendingReceiptItem(
                id(row, "id"),
                id(row, "request_id"),
                (String) row.get("request_no"),
                node(json, row, "asset_snapshot_json"),
                num(row, "version"),
                rawTime(row, "delivered_at"),
                rawTime(row, "receipt_deadline_at"),
                (String) row.get("destination_text"));
    }

    static ReceiptHandover receiptHandover(Map<String, Object> row) {
        return new ReceiptHandover(
                rawTime(row, "delivered_at"),
                rawTime(row, "receipt_deadline_at"),
                (String) row.get("destination_text"));
    }

    /**
     * Reassignment processing item (JAVA-14c): one frozen row of
     * {@code RequisitionReassignmentService.checks} projected to the wire. The internal
     * {@code checks}/{@code processing}/{@code owner} row Maps stay Maps; only this exit is typed.
     */
    public record ReassignmentItem(
            String id,
            long version,
            String status,
            long assetVersion,
            long lockVersion,
            String assetCode) {
    }

    /** Warehouse operator candidate row of the reassignment snapshot (a.id, e.name). */
    public record Operator(String id, String name) {
    }

    /**
     * Reassignment inspect exit (JAVA-14c): header/ownership versions plus items and operators.
     * unavailableReason is a conditional key — the legacy LinkedHashMap only put it when an
     * unavailability had been declared (global inclusion is ALWAYS, so NON_NULL freezes it).
     */
    public record ReassignmentSnapshot(
            String id,
            long version,
            long ownershipVersion,
            String processorAccountId,
            List<ReassignmentItem> items,
            List<Operator> operators,
            @JsonInclude(JsonInclude.Include.NON_NULL) String unavailableReason) {
        public ReassignmentSnapshot {
            items = items == null ? null : List.copyOf(items);
            operators = operators == null ? null : List.copyOf(operators);
        }
    }

    static ReassignmentItem reassignmentItem(Map<String, Object> check) {
        return new ReassignmentItem(
                (String) check.get("id"),
                num(check, "version"),
                (String) check.get("status"),
                num(check, "assetVersion"),
                num(check, "lockVersion"),
                (String) check.get("assetCode"));
    }

    static Operator operator(Map<String, Object> row) {
        return new Operator(id(row, "id"), (String) row.get("name"));
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
        return value instanceof Timestamp timestamp
                ? timestamp.toInstant().toString()
                : ((LocalDateTime) value).toInstant(ZoneOffset.UTC).toString();
    }

    private static String date(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        return value instanceof java.sql.Date day
                ? day.toLocalDate().toString()
                : value.toString();
    }

    /** Handover clean(): only LocalDateTime converts, every other driver shape passes through. */
    private static Object rawTime(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof LocalDateTime timestamp
                ? timestamp.toInstant(ZoneOffset.UTC).toString()
                : value;
    }

    private static JsonNode node(ObjectMapper json, Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : json.readTree(value.toString());
    }
}
