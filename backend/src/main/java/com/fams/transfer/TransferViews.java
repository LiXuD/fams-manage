package com.fams.transfer;

import com.fams.asset.AssetTransferPort;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Typed wire shapes of the transfer domain, mirroring contracts/fams-core-v1.yaml.
 * Identifiers are strings, timestamps are ISO instants and stored snapshots stay raw JSON,
 * exactly as the previous row-projection responses serialized them.
 */
public final class TransferViews {
    private TransferViews() {
    }

    /** Responses persisted in idempotency records expose their resource identity. */
    public sealed interface Reply permits Detail, Item, UnavailableReceipt {
        String id();

        long version();
    }

    public record Page<T>(List<T> items, long total, int page, int pageSize) {
        public Page {
            items = items == null ? null : List.copyOf(items);
        }
    }

    public record CatalogPage(List<CatalogChoice> items, int page, int pageSize) {
        public CatalogPage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    public sealed interface CatalogChoice
            permits PersonnelChoice, OrganizationChoice, WarehouseChoice, LocationChoice, EmployeeChoice {
    }

    public record PersonnelChoice(String id, String name, long version, String status, String organizationId)
            implements CatalogChoice {
    }

    public record OrganizationChoice(String id, String name, long version) implements CatalogChoice {
    }

    public record WarehouseChoice(String id, String name, long version) implements CatalogChoice {
    }

    public record LocationChoice(String id, String name, long version, String warehouseId, String locationType)
            implements CatalogChoice {
    }

    public record EmployeeChoice(String id, String name, long version, String organizationId, long sharedEligible)
            implements CatalogChoice {
    }

    public record EligibleAsset(
            String id,
            String assetCode,
            String name,
            long version,
            String lifecycleStatus,
            String custodyType,
            String owningOrganizationId,
            String usingOrganizationId,
            String responsibleEmployeeId,
            String departmentResponsibleEmployeeId,
            String storageWarehouseId,
            String currentLocationId) {
    }

    /** List rows carry only the request header; detail adds items, events and quantities. */
    public record Summary(
            String id,
            String creatorAccountId,
            String reason,
            String requestStatus,
            long version,
            String decidedBy,
            String decisionReason,
            Long approvedVersion,
            String processorAccountId,
            String createdAt,
            String updatedAt,
            String decidedAt,
            long ownershipVersion) {
    }

    public record Detail(
            String id,
            String creatorAccountId,
            String reason,
            String requestStatus,
            long version,
            String decidedBy,
            String decisionReason,
            Long approvedVersion,
            String processorAccountId,
            String createdAt,
            String updatedAt,
            String decidedAt,
            long ownershipVersion,
            List<Item> items,
            List<Event> events,
            long totalQuantity,
            long completedQuantity,
            long cancelledQuantity,
            long pendingQuantity,
            long sourceGroups,
            long targetGroups) implements Reply {
        public Detail {
            items = items == null ? null : List.copyOf(items);
            events = events == null ? null : List.copyOf(events);
        }
    }

    public record Item(
            String id,
            String requestId,
            String assetId,
            long assetVersion,
            JsonNode sourceSnapshot,
            JsonNode targetSnapshot,
            String receiverEmployeeId,
            Boolean receiptRequired,
            String itemStatus,
            long version,
            String destination,
            String deliveryEvidence,
            String deliveredAt,
            String receiptDeadlineAt,
            String completedAt,
            String completedBy,
            JsonNode afterSnapshot,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long lockVersion) implements Reply {
    }

    public record Event(
            String id, String itemId, String eventType, String actorAccountId, String occurredAt, JsonNode detail) {
    }

    public record PendingReceipt(
            String id,
            String requestId,
            String itemStatus,
            long version,
            String destination,
            String deliveredAt,
            String receiptDeadlineAt,
            String assetCode,
            String name,
            long requestVersion) {
    }

    public record Grant(String grant, String itemId, long itemVersion, long requestVersion, String expiresAt) {
    }

    public record UnavailableReceipt(String id, long version, boolean recorded) implements Reply {
    }

    public record Operator(String id, String name) {
    }

    public record ReassignmentSnapshot(
            String id,
            long version,
            long ownershipVersion,
            String processorAccountId,
            List<Item> items,
            List<Operator> operators,
            @JsonInclude(JsonInclude.Include.NON_NULL) String unavailableReason) {
        public ReassignmentSnapshot {
            items = items == null ? null : List.copyOf(items);
            operators = operators == null ? null : List.copyOf(operators);
        }
    }

    public record EmployeeRef(String id, String name, String status, String organizationId) {
    }

    public record HandoverAsset(
            String id,
            String assetCode,
            String name,
            String lifecycleStatus,
            String exceptionStatus,
            String custodyType,
            long version,
            String currentLocationId,
            String usingOrganizationId,
            String businessType,
            String businessId) {
    }

    public record PendingReceiptRef(String id, String requestId, String itemStatus) {
    }

    public record ProcessingTransferRef(String id, String requestStatus) {
    }

    // expectedReturnDate stays the raw JDBC value: DATE columns have surfaced as both
    // java.sql.Date and LocalDate across driver versions, and the wire passes them through.
    public record OpenLoanRef(String id, String assetId, Object expectedReturnDate, String relationStatus) {
    }

    public record OpenReturnRef(String id, String assetId, String returnStatus) {
    }

    public record OpenRequisitionRef(String id, String requestNo, String requestStatus) {
    }

    public record PersonnelHandover(
            EmployeeRef employee,
            List<HandoverAsset> assets,
            List<PendingReceiptRef> pendingReceipts,
            List<ProcessingTransferRef> processingTransfers,
            List<OpenLoanRef> openLoans,
            List<OpenReturnRef> openReturns,
            List<OpenRequisitionRef> openRequisitions) {
        public PersonnelHandover {
            assets = assets == null ? null : List.copyOf(assets);
            pendingReceipts = pendingReceipts == null ? null : List.copyOf(pendingReceipts);
            processingTransfers = processingTransfers == null ? null : List.copyOf(processingTransfers);
            openLoans = openLoans == null ? null : List.copyOf(openLoans);
            openReturns = openReturns == null ? null : List.copyOf(openReturns);
            openRequisitions = openRequisitions == null ? null : List.copyOf(openRequisitions);
        }
    }

    /** Stored target snapshots keep string identifiers; the shape is persisted verbatim. */
    public record TargetWire(
            String owningOrganizationId,
            String custodyType,
            String usingOrganizationId,
            String employeeId,
            String warehouseId,
            String locationId,
            Map<String, Long> referenceVersions) {
        public TargetWire {
            referenceVersions = referenceVersions == null ? null : Map.copyOf(referenceVersions);
        }
    }

    public record SourceView(
            String id,
            long version,
            String assetCode,
            String name,
            String owningOrganizationId,
            String custodyType,
            String usingOrganizationId,
            String responsibleEmployeeId,
            String departmentResponsibleEmployeeId,
            String storageWarehouseId,
            String currentLocationId,
            String lifecycleStatus,
            String exceptionStatus,
            String cardStage,
            String parameterVersionId) {
    }

    public record PhysicalView(String destination, String deliveryEvidence, String deliveredAt,
            String receiptDeadlineAt) {
    }

    public record TargetChangeContext(
            String businessType,
            String businessId,
            String itemId,
            long version,
            long approvedVersion,
            long itemVersion,
            String itemStatus,
            String assetId,
            long assetVersion,
            String assetCode,
            String processorAccountId,
            long ownershipVersion,
            long lockVersion,
            TargetWire target,
            SourceView source,
            PhysicalView physical) implements com.fams.targetchange.TargetChangeViews.Context {
    }

    static Summary summary(Map<String, Object> row) {
        return new Summary(
                id(row, "id"),
                id(row, "creator_account_id"),
                (String) row.get("reason"),
                (String) row.get("request_status"),
                num(row, "version"),
                id(row, "decided_by"),
                (String) row.get("decision_reason"),
                row.get("approved_version") == null ? null : num(row, "approved_version"),
                id(row, "processor_account_id"),
                time(row, "created_at"),
                time(row, "updated_at"),
                time(row, "decided_at"),
                num(row, "ownership_version"));
    }

    static Detail detail(
            Map<String, Object> request, List<Item> items, List<Event> events, long sourceGroups, long targetGroups) {
        var header = summary(request);
        return new Detail(
                header.id(),
                header.creatorAccountId(),
                header.reason(),
                header.requestStatus(),
                header.version(),
                header.decidedBy(),
                header.decisionReason(),
                header.approvedVersion(),
                header.processorAccountId(),
                header.createdAt(),
                header.updatedAt(),
                header.decidedAt(),
                header.ownershipVersion(),
                items,
                events,
                items.size(),
                items.stream().filter(i -> "COMPLETED".equals(i.itemStatus())).count(),
                items.stream().filter(i -> "CANCELLED".equals(i.itemStatus())).count(),
                items.stream()
                        .filter(i -> !List.of("COMPLETED", "CANCELLED").contains(i.itemStatus()))
                        .count(),
                sourceGroups,
                targetGroups);
    }

    static Item item(ObjectMapper json, Map<String, Object> row) {
        return new Item(
                id(row, "id"),
                id(row, "request_id"),
                id(row, "asset_id"),
                num(row, "asset_version"),
                json.readTree(row.get("source_snapshot_json").toString()),
                wireTarget(json, row.get("target_snapshot_json").toString()),
                id(row, "receiver_employee_id"),
                (Boolean) row.get("receipt_required"),
                (String) row.get("item_status"),
                num(row, "version"),
                (String) row.get("destination"),
                (String) row.get("delivery_evidence"),
                time(row, "delivered_at"),
                time(row, "receipt_deadline_at"),
                time(row, "completed_at"),
                id(row, "completed_by"),
                row.get("after_snapshot_json") == null
                        ? null
                        : json.readTree(row.get("after_snapshot_json").toString()),
                row.get("lock_version") == null ? null : num(row, "lock_version"));
    }

    static Event event(ObjectMapper json, Map<String, Object> row) {
        return new Event(
                id(row, "id"),
                id(row, "item_id"),
                (String) row.get("event_type"),
                id(row, "actor_account_id"),
                time(row, "occurred_at"),
                json.readTree(row.get("detail_json").toString()));
    }

    static EligibleAsset eligibleAsset(Map<String, Object> row) {
        return new EligibleAsset(
                id(row, "id"),
                (String) row.get("asset_code"),
                (String) row.get("name"),
                num(row, "version"),
                (String) row.get("lifecycle_status"),
                (String) row.get("custody_type"),
                id(row, "owning_organization_id"),
                id(row, "using_organization_id"),
                id(row, "responsible_employee_id"),
                id(row, "department_responsible_employee_id"),
                id(row, "storage_warehouse_id"),
                id(row, "current_location_id"));
    }

    static PendingReceipt pendingReceipt(Map<String, Object> row) {
        return new PendingReceipt(
                id(row, "id"),
                id(row, "request_id"),
                (String) row.get("item_status"),
                num(row, "version"),
                (String) row.get("destination"),
                time(row, "delivered_at"),
                time(row, "receipt_deadline_at"),
                (String) row.get("asset_code"),
                (String) row.get("name"),
                num(row, "request_version"));
    }

    static CatalogChoice catalogChoice(String kind, Map<String, Object> row) {
        return switch (kind) {
            case "personnel" -> new PersonnelChoice(
                    id(row, "id"), (String) row.get("name"), num(row, "version"),
                    (String) row.get("status"), id(row, "organization_id"));
            case "organization" -> new OrganizationChoice(
                    id(row, "id"), (String) row.get("name"), num(row, "version"));
            case "warehouse" -> new WarehouseChoice(
                    id(row, "id"), (String) row.get("name"), num(row, "version"));
            case "location" -> new LocationChoice(
                    id(row, "id"), (String) row.get("name"), num(row, "version"),
                    id(row, "warehouse_id"), (String) row.get("location_type"));
            case "employee" -> new EmployeeChoice(
                    id(row, "id"), (String) row.get("name"), num(row, "version"),
                    id(row, "organization_id"), num(row, "shared_eligible"));
            default -> throw new TransferFailure("TRANSFER_CATALOG_NOT_FOUND", 404);
        };
    }

    static EmployeeRef employeeRef(Map<String, Object> row) {
        return new EmployeeRef(
                id(row, "id"), (String) row.get("name"), (String) row.get("status"), id(row, "organization_id"));
    }

    static HandoverAsset handoverAsset(Map<String, Object> row) {
        return new HandoverAsset(
                id(row, "id"),
                (String) row.get("asset_code"),
                (String) row.get("name"),
                (String) row.get("lifecycle_status"),
                (String) row.get("exception_status"),
                (String) row.get("custody_type"),
                num(row, "version"),
                id(row, "current_location_id"),
                id(row, "using_organization_id"),
                (String) row.get("business_type"),
                id(row, "business_id"));
    }

    static PendingReceiptRef pendingReceiptRef(Map<String, Object> row) {
        return new PendingReceiptRef(id(row, "id"), id(row, "request_id"), (String) row.get("item_status"));
    }

    static ProcessingTransferRef processingTransferRef(Map<String, Object> row) {
        return new ProcessingTransferRef(id(row, "id"), (String) row.get("request_status"));
    }

    static OpenLoanRef openLoanRef(Map<String, Object> row) {
        return new OpenLoanRef(
                id(row, "id"), id(row, "asset_id"),
                row.get("expected_return_date"), (String) row.get("relation_status"));
    }

    static OpenReturnRef openReturnRef(Map<String, Object> row) {
        return new OpenReturnRef(id(row, "id"), id(row, "asset_id"), (String) row.get("return_status"));
    }

    static OpenRequisitionRef openRequisitionRef(Map<String, Object> row) {
        return new OpenRequisitionRef(id(row, "id"), (String) row.get("request_no"),
                (String) row.get("request_status"));
    }

    static Operator operator(Map<String, Object> row) {
        return new Operator(id(row, "id"), (String) row.get("name"));
    }

    static TargetWire targetWire(AssetTransferPort.Target target) {
        return new TargetWire(
                target.owningOrganizationId() == null ? null : target.owningOrganizationId().toString(),
                target.custodyType(),
                target.usingOrganizationId() == null ? null : target.usingOrganizationId().toString(),
                target.employeeId() == null ? null : target.employeeId().toString(),
                target.warehouseId() == null ? null : target.warehouseId().toString(),
                Long.toString(target.locationId()),
                target.referenceVersions());
    }

    static SourceView sourceView(AssetTransferPort.Snapshot source) {
        return new SourceView(
                source.id().toString(),
                source.version(),
                source.assetCode(),
                source.name(),
                source.owningOrganizationId() == null ? null : source.owningOrganizationId().toString(),
                source.custodyType(),
                source.usingOrganizationId() == null ? null : source.usingOrganizationId().toString(),
                source.responsibleEmployeeId() == null ? null : source.responsibleEmployeeId().toString(),
                source.departmentResponsibleEmployeeId() == null
                        ? null
                        : source.departmentResponsibleEmployeeId().toString(),
                source.storageWarehouseId() == null ? null : source.storageWarehouseId().toString(),
                source.currentLocationId().toString(),
                source.lifecycleStatus(),
                source.exceptionStatus(),
                source.cardStage(),
                source.parameterVersionId().toString());
    }

    static PhysicalView physical(Map<String, Object> row) {
        return new PhysicalView(
                (String) row.get("destination"),
                (String) row.get("delivery_evidence"),
                time(row, "delivered_at"),
                time(row, "receipt_deadline_at"));
    }

    private static JsonNode wireTarget(ObjectMapper json, String value) {
        var node = json.readTree(value);
        if (node instanceof ObjectNode target) {
            for (String field : List.of("owningOrganizationId", "usingOrganizationId",
                    "employeeId", "warehouseId", "locationId")) {
                var identifier = target.get(field);
                if (identifier != null && identifier.isIntegralNumber()) {
                    target.put(field, identifier.asText());
                }
            }
        }
        return node;
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
        Instant instant = value instanceof Timestamp time
                ? time.toInstant()
                : ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
        return instant.toString();
    }
}
