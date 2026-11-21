package com.fams.loan;

import com.fams.business.ReturnLossOpeningPort;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Typed wire shapes of the loan domain, mirroring contracts/fams-core-v1.yaml.
 * Loan rows follow the frozen LoanService.clean projection (DATE columns become
 * toString date strings); asset-return rows follow the frozen AssetReturnService.view
 * projection (identifiers stringified, timestamps ISO instants, no DATE columns exist).
 * Conditional keys use NON_NULL; explicit null keys keep default inclusion.
 */
public final class LoanViews {
    private LoanViews() {
    }

    /** Responses persisted in idempotency records expose their resource identity. */
    public sealed interface Reply permits Extension, AssetReturn, AssetReturnDetail {
        String id();

        long version();
    }

    public record Page<T>(List<T> items, long total, int page, int pageSize) {
        public Page {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** List rows carry the loan plus joined asset columns; detail adds extensions and reminders. */
    public record Loan(
            String id,
            String assetId,
            String borrowerEmployeeId,
            String usingOrganizationId,
            String sourceType,
            String sourceRequestId,
            String sourceAllocationId,
            String initialBatchId,
            Long initialSheetRow,
            String borrowedDate,
            String originalReturnDate,
            String expectedReturnDate,
            String relationStatus,
            String closedAt,
            long version,
            String createdAt,
            String updatedAt,
            String openAssetId,
            String assetCode,
            String name,
            String lifecycleStatus,
            String exceptionStatus,
            long currentAssetVersion,
            boolean overdue,
            boolean lostLiability) {
    }

    public record LoanDetail(
            String id,
            String assetId,
            String borrowerEmployeeId,
            String usingOrganizationId,
            String sourceType,
            String sourceRequestId,
            String sourceAllocationId,
            String initialBatchId,
            Long initialSheetRow,
            String borrowedDate,
            String originalReturnDate,
            String expectedReturnDate,
            String relationStatus,
            String closedAt,
            long version,
            String createdAt,
            String updatedAt,
            String openAssetId,
            String assetCode,
            String name,
            String lifecycleStatus,
            String exceptionStatus,
            long currentAssetVersion,
            boolean overdue,
            boolean lostLiability,
            List<Extension> extensions,
            List<Reminder> reminders) {
        public LoanDetail {
            extensions = extensions == null ? null : List.copyOf(extensions);
            reminders = reminders == null ? null : List.copyOf(reminders);
        }
    }

    public record Extension(
            String id,
            String loanId,
            String requestId,
            long targetVersion,
            String originalDueDate,
            String requestedDueDate,
            String reason,
            String proposedBy,
            String extensionStatus,
            String decidedBy,
            String decisionReason,
            String decidedBusinessDate,
            String overdueFrom,
            String overdueThrough,
            String createdAt,
            String decidedAt,
            long version) implements Reply {
    }

    public record Reminder(
            String id,
            String loanId,
            String expectedReturnDate,
            String reminderBusinessDate,
            String reminderType,
            String overdueFrom,
            String createdAt) {
    }

    public record AssetReturn(
            String id,
            String assetId,
            String loanId,
            long assetVersion,
            String responsibleEmployeeId,
            String usingOrganizationId,
            String proposedBy,
            String reason,
            String returnStatus,
            String decidedBy,
            String decisionReason,
            String processorAccountId,
            String inspectionResult,
            String inspectionReason,
            String warehouseId,
            String locationId,
            String createdAt,
            String decidedAt,
            String completedAt,
            long version,
            long ownershipVersion,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode lossRecovery,
            @JsonInclude(JsonInclude.Include.NON_NULL) ReturnLossOpeningPort.Summary returnLossReport)
            implements Reply {
    }

    public record AssetReturnDetail(
            String id,
            String assetId,
            String loanId,
            long assetVersion,
            String responsibleEmployeeId,
            String usingOrganizationId,
            String proposedBy,
            String reason,
            String returnStatus,
            String decidedBy,
            String decisionReason,
            String processorAccountId,
            String inspectionResult,
            String inspectionReason,
            String warehouseId,
            String locationId,
            String createdAt,
            String decidedAt,
            String completedAt,
            long version,
            long ownershipVersion,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode lossRecovery,
            ReturnAsset asset,
            Long lockVersion,
            boolean canReportMissing,
            List<Followup> followups) implements Reply {
        public AssetReturnDetail {
            followups = followups == null ? null : List.copyOf(followups);
        }
    }

    public record ReturnAsset(
            String id, String assetCode, String name, long version, String lifecycleStatus, String exceptionStatus) {
    }

    public record ReturnAssetChoice(String id, String assetCode, String name, long version, String lifecycleStatus) {
    }

    public sealed interface ReturnCatalogChoice permits WarehouseChoice, LocationChoice {
    }

    public record WarehouseChoice(String id, String name, long version) implements ReturnCatalogChoice {
    }

    public record LocationChoice(String id, String name, long version, String warehouseId)
            implements ReturnCatalogChoice {
    }

    public record ReturnCatalogPage(List<ReturnCatalogChoice> items, int page, int pageSize) {
        public ReturnCatalogPage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /**
     * REPAIR followups never carry loss-report keys; LOST_REPORT always carries lossReport
     * (explicit null when no report exists). Replay resolves the subtype through the
     * businessType key already present on the wire, so stored JSON gains no extra property.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "businessType", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = RepairFollowup.class, name = "REPAIR"),
            @JsonSubTypes.Type(value = LostReportFollowup.class, name = "LOST_REPORT")})
    public sealed interface Followup permits RepairFollowup, LostReportFollowup {
    }

    public record RepairFollowup(
            String id,
            String returnId,
            String assetId,
            String loanId,
            String businessType,
            String followupStatus,
            String processorAccountId,
            String reason,
            String createdAt,
            long version,
            String linkedRepairId,
            String lastRepairId,
            String repairStatus) implements Followup {
    }

    public record LostReportFollowup(
            String id,
            String returnId,
            String assetId,
            String loanId,
            String businessType,
            String followupStatus,
            String processorAccountId,
            String reason,
            String createdAt,
            long version,
            String linkedRepairId,
            String lastRepairId,
            String repairStatus,
            ReturnLossOpeningPort.Summary lossReport,
            @JsonInclude(JsonInclude.Include.NON_NULL) String recoveryReturnStatus,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long missingLossLockVersion,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean canRetryMissingLoss) implements Followup {
    }

    public record ReassignmentItem(
            String id, long version, String status, long assetVersion, long lockVersion, String assetCode) {
    }

    public record Operator(String id, String name) {
    }

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

    static Loan loan(ObjectMapper json, Clock clock, Map<String, Object> row) {
        boolean open = "OPEN".equals(row.get("relation_status")) && !"SCRAPPED".equals(row.get("lifecycle_status"));
        boolean lost = "LOST".equals(row.get("exception_status"));
        boolean overdue = open && !lost && dateValue(row, "expected_return_date").isBefore(today(json, clock, row));
        return new Loan(
                id(row, "id"),
                id(row, "asset_id"),
                id(row, "borrower_employee_id"),
                id(row, "using_organization_id"),
                (String) row.get("source_type"),
                id(row, "source_request_id"),
                id(row, "source_allocation_id"),
                (String) row.get("initial_batch_id"),
                numOrNull(row, "initial_sheet_row"),
                date(row, "borrowed_date"),
                date(row, "original_return_date"),
                date(row, "expected_return_date"),
                (String) row.get("relation_status"),
                time(row, "closed_at"),
                num(row, "version"),
                time(row, "created_at"),
                time(row, "updated_at"),
                id(row, "open_asset_id"),
                (String) row.get("asset_code"),
                (String) row.get("name"),
                (String) row.get("lifecycle_status"),
                (String) row.get("exception_status"),
                num(row, "current_asset_version"),
                overdue,
                open && lost);
    }

    static LoanDetail loanDetail(
            ObjectMapper json, Clock clock, Map<String, Object> row, List<Extension> extensions, List<Reminder> reminders) {
        var base = loan(json, clock, row);
        return new LoanDetail(
                base.id(),
                base.assetId(),
                base.borrowerEmployeeId(),
                base.usingOrganizationId(),
                base.sourceType(),
                base.sourceRequestId(),
                base.sourceAllocationId(),
                base.initialBatchId(),
                base.initialSheetRow(),
                base.borrowedDate(),
                base.originalReturnDate(),
                base.expectedReturnDate(),
                base.relationStatus(),
                base.closedAt(),
                base.version(),
                base.createdAt(),
                base.updatedAt(),
                base.openAssetId(),
                base.assetCode(),
                base.name(),
                base.lifecycleStatus(),
                base.exceptionStatus(),
                base.currentAssetVersion(),
                base.overdue(),
                base.lostLiability(),
                extensions,
                reminders);
    }

    static Extension extension(Map<String, Object> row) {
        return new Extension(
                id(row, "id"),
                id(row, "loan_id"),
                id(row, "request_id"),
                num(row, "target_version"),
                date(row, "original_due_date"),
                date(row, "requested_due_date"),
                (String) row.get("reason"),
                id(row, "proposed_by"),
                (String) row.get("extension_status"),
                id(row, "decided_by"),
                (String) row.get("decision_reason"),
                date(row, "decided_business_date"),
                date(row, "overdue_from"),
                date(row, "overdue_through"),
                time(row, "created_at"),
                time(row, "decided_at"),
                num(row, "version"));
    }

    static Reminder reminder(Map<String, Object> row) {
        return new Reminder(
                id(row, "id"),
                id(row, "loan_id"),
                date(row, "expected_return_date"),
                date(row, "reminder_business_date"),
                (String) row.get("reminder_type"),
                date(row, "overdue_from"),
                time(row, "created_at"));
    }

    static AssetReturn assetReturn(ObjectMapper json, Map<String, Object> row) {
        return assetReturn(json, row, null);
    }

    static AssetReturn assetReturn(
            ObjectMapper json, Map<String, Object> row, ReturnLossOpeningPort.Summary returnLossReport) {
        return new AssetReturn(
                id(row, "id"),
                id(row, "asset_id"),
                id(row, "loan_id"),
                num(row, "asset_version"),
                id(row, "responsible_employee_id"),
                id(row, "using_organization_id"),
                id(row, "proposed_by"),
                (String) row.get("reason"),
                (String) row.get("return_status"),
                id(row, "decided_by"),
                (String) row.get("decision_reason"),
                id(row, "processor_account_id"),
                (String) row.get("inspection_result"),
                (String) row.get("inspection_reason"),
                id(row, "warehouse_id"),
                id(row, "location_id"),
                time(row, "created_at"),
                time(row, "decided_at"),
                time(row, "completed_at"),
                num(row, "version"),
                num(row, "ownership_version"),
                lossRecovery(json, row),
                returnLossReport);
    }

    static AssetReturnDetail assetReturnDetail(
            ObjectMapper json, Map<String, Object> row, ReturnAsset asset, Long lockVersion,
            boolean canReportMissing, List<Followup> followups) {
        var base = assetReturn(json, row);
        return new AssetReturnDetail(
                base.id(),
                base.assetId(),
                base.loanId(),
                base.assetVersion(),
                base.responsibleEmployeeId(),
                base.usingOrganizationId(),
                base.proposedBy(),
                base.reason(),
                base.returnStatus(),
                base.decidedBy(),
                base.decisionReason(),
                base.processorAccountId(),
                base.inspectionResult(),
                base.inspectionReason(),
                base.warehouseId(),
                base.locationId(),
                base.createdAt(),
                base.decidedAt(),
                base.completedAt(),
                base.version(),
                base.ownershipVersion(),
                base.lossRecovery(),
                asset,
                lockVersion,
                canReportMissing,
                followups);
    }

    static ReturnAsset returnAsset(Map<String, Object> row) {
        return new ReturnAsset(
                id(row, "id"), (String) row.get("asset_code"), (String) row.get("name"),
                num(row, "version"), (String) row.get("lifecycle_status"), (String) row.get("exception_status"));
    }

    static ReturnAssetChoice returnAssetChoice(Map<String, Object> row) {
        return new ReturnAssetChoice(
                id(row, "id"), (String) row.get("asset_code"), (String) row.get("name"),
                num(row, "version"), (String) row.get("lifecycle_status"));
    }

    static ReturnCatalogChoice returnCatalogChoice(String kind, Map<String, Object> row) {
        return switch (kind) {
            case "warehouse" -> new WarehouseChoice(id(row, "id"), (String) row.get("name"), num(row, "version"));
            case "location" -> new LocationChoice(
                    id(row, "id"), (String) row.get("name"), num(row, "version"), id(row, "warehouse_id"));
            default -> throw new LoanFailure("RETURN_CATALOG_NOT_FOUND", 404);
        };
    }

    static RepairFollowup repairFollowup(Map<String, Object> row) {
        return new RepairFollowup(
                id(row, "id"),
                id(row, "return_id"),
                id(row, "asset_id"),
                id(row, "loan_id"),
                (String) row.get("business_type"),
                (String) row.get("followup_status"),
                id(row, "processor_account_id"),
                (String) row.get("reason"),
                time(row, "created_at"),
                num(row, "version"),
                id(row, "linked_repair_id"),
                id(row, "last_repair_id"),
                (String) row.get("repair_status"));
    }

    static LostReportFollowup lostReportFollowup(
            Map<String, Object> row, ReturnLossOpeningPort.Summary lossReport, String recoveryReturnStatus,
            Long missingLossLockVersion, Boolean canRetryMissingLoss) {
        return new LostReportFollowup(
                id(row, "id"),
                id(row, "return_id"),
                id(row, "asset_id"),
                id(row, "loan_id"),
                (String) row.get("business_type"),
                (String) row.get("followup_status"),
                id(row, "processor_account_id"),
                (String) row.get("reason"),
                time(row, "created_at"),
                num(row, "version"),
                id(row, "linked_repair_id"),
                id(row, "last_repair_id"),
                (String) row.get("repair_status"),
                lossReport,
                recoveryReturnStatus,
                missingLossLockVersion,
                canRetryMissingLoss);
    }

    /** checks() rows already use camel keys; values stay the raw JDBC numbers. */
    static ReassignmentItem reassignmentItem(Map<String, Object> check) {
        return new ReassignmentItem(
                (String) check.get("id"),
                ((Number) check.get("version")).longValue(),
                (String) check.get("status"),
                ((Number) check.get("assetVersion")).longValue(),
                ((Number) check.get("lockVersion")).longValue(),
                (String) check.get("assetCode"));
    }

    static Operator operator(Map<String, Object> row) {
        return new Operator(id(row, "id"), (String) row.get("name"));
    }

    static ReassignmentSnapshot reassignmentSnapshot(
            long id, Map<String, Object> row, List<ReassignmentItem> items, List<Operator> operators,
            String unavailableReason) {
        return new ReassignmentSnapshot(
                Long.toString(id),
                num(row, "version"),
                num(row, "ownership_version"),
                id(row, "processor_account_id"),
                items,
                operators,
                unavailableReason);
    }

    private static JsonNode lossRecovery(ObjectMapper json, Map<String, Object> row) {
        if (!row.containsKey("parameter_snapshot_json")) {
            return null;
        }
        var snapshot = json.readTree(row.get("parameter_snapshot_json").toString());
        return snapshot.has("lossRecovery") ? snapshot.get("lossRecovery") : null;
    }

    private static LocalDate today(ObjectMapper json, Clock clock, Map<String, Object> row) {
        var zone = ZoneId.of(json.readTree(row.get("parameter_snapshot_json").toString()).get("timezone").asText());
        return clock.instant().atZone(zone).toLocalDate();
    }

    private static String id(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    private static long num(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static Long numOrNull(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : ((Number) value).longValue();
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

    // clean() stringifies DATE columns; drivers surface them as java.sql.Date or LocalDate
    // and both toString to the same yyyy-MM-dd wire form.
    private static LocalDate dateValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : LocalDate.parse(value.toString());
    }

    private static String date(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }
}
