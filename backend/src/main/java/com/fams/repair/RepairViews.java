package com.fams.repair;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.fams.inbound.FactoryReplacementInboundPort;
import com.fams.loan.LoanRepairPort;
import com.fams.loan.LoanReturnRepairPort;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Typed wire shapes of the repair domain core exits (JAVA-16a), mirroring the frozen
 * pre-refactor RepairService projections. {@code RepairService.view}/{@code wire} semantics:
 * {@code id}/{@code *_id} columns stringify per value, DATETIME columns become ISO instants
 * (Timestamp/LocalDateTime driver shapes), {@code *_json} string columns parse into raw JSON
 * trees and every other column keeps its JDBC value; sub-table rows keep their original
 * snake_case keys (JAVA-11a LossEvent precedent). Each key the legacy Map always put stays a
 * default-inclusion component (global JsonConfiguration inclusion is ALWAYS, so explicit nulls
 * serialize); each key the legacy code put only under a guard carries {@code NON_NULL}.
 */
public final class RepairViews {
    private RepairViews() {
    }

    public record Page<T>(List<T> items, long total, int page, int pageSize) {
        public Page {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /**
     * fams_repair_request header row (the frozen RepairService.view projection): the list item
     * and the body every view-shaped repair idempotency receipt stores.
     */
    public record RepairSummary(
            String id,
            String assetId,
            String creatorAccountId,
            long version,
            long assetVersion,
            String status,
            String fault,
            String impact,
            String occurredAt,
            String allowedMethod,
            String processorAccountId,
            long ownershipVersion,
            String claimedAt,
            String acceptedAt,
            String completedAt,
            JsonNode source) {}

    /** One fams_repair_inventory_evidence reference projected from its stored source context. */
    public record InventoryEvidenceRef(
            String taskId, String observationId, String clientEventId, JsonNode photoIds) {}

    /** fams_repair_decision row under the frozen wire() projection (snake_case keys). */
    public record DecisionRow(
            @JsonProperty("id") String id,
            @JsonProperty("repair_id") String repairId,
            @JsonProperty("before_version") long beforeVersion,
            @JsonProperty("after_version") long afterVersion,
            @JsonProperty("result") String result,
            @JsonProperty("allowed_method") String allowedMethod,
            @JsonProperty("snapshot_json") JsonNode snapshotJson,
            @JsonProperty("reason") String reason,
            @JsonProperty("actor_account_id") String actorAccountId,
            @JsonProperty("recorded_at") String recordedAt) {}

    /** fams_repair_event history row under the frozen wire() projection. */
    public record EventRow(
            @JsonProperty("id") String id,
            @JsonProperty("repair_id") String repairId,
            @JsonProperty("action") String action,
            @JsonProperty("before_version") long beforeVersion,
            @JsonProperty("after_version") long afterVersion,
            @JsonProperty("actor_account_id") String actorAccountId,
            @JsonProperty("actor_username") String actorUsername,
            @JsonProperty("actor_role") String actorRole,
            @JsonProperty("detail_json") JsonNode detailJson,
            @JsonProperty("occurred_at") String occurredAt) {}

    /** fams_repair_physical_handling row under the frozen wire() projection. */
    public record PhysicalHandlingRow(
            @JsonProperty("repair_id") String repairId,
            @JsonProperty("handler_employee_id") String handlerEmployeeId,
            @JsonProperty("actual_location_id") String actualLocationId,
            @JsonProperty("location_version") long locationVersion,
            @JsonProperty("before_asset_version") long beforeAssetVersion,
            @JsonProperty("occurred_at") String occurredAt,
            @JsonProperty("evidence") String evidence,
            @JsonProperty("actor_account_id") String actorAccountId,
            @JsonProperty("recorded_at") String recordedAt) {}

    /** fams_repair_inbound row under the frozen wire() projection. */
    public record InboundRow(
            @JsonProperty("repair_id") String repairId,
            @JsonProperty("warehouse_id") String warehouseId,
            @JsonProperty("location_id") String locationId,
            @JsonProperty("warehouse_version") long warehouseVersion,
            @JsonProperty("location_version") long locationVersion,
            @JsonProperty("before_asset_version") long beforeAssetVersion,
            @JsonProperty("occurred_at") String occurredAt,
            @JsonProperty("evidence") String evidence,
            @JsonProperty("actor_account_id") String actorAccountId,
            @JsonProperty("recorded_at") String recordedAt) {}

    /**
     * fams_repair_return_handover row. delivered_by/received_by never matched the legacy wire
     * {@code *_id} rule, so they stay numeric; active_repair_id does match it and stringifies.
     */
    public record ReturnHandoverRow(
            @JsonProperty("id") String id,
            @JsonProperty("repair_id") String repairId,
            @JsonProperty("round_number") long roundNumber,
            @JsonProperty("handover_status") String handoverStatus,
            @JsonProperty("active_repair_id") String activeRepairId,
            @JsonProperty("target_snapshot_json") JsonNode targetSnapshotJson,
            @JsonProperty("before_asset_version") long beforeAssetVersion,
            @JsonProperty("delivered_at") String deliveredAt,
            @JsonProperty("delivery_evidence") String deliveryEvidence,
            @JsonProperty("delivered_by") long deliveredBy,
            @JsonProperty("recorded_at") String recordedAt,
            @JsonProperty("receipt_at") String receiptAt,
            @JsonProperty("receipt_evidence") String receiptEvidence,
            @JsonProperty("received_by") Long receivedBy) {}

    /** fams_repair_approved_return_target row; approved_by stays numeric like the legacy wire. */
    public record ApprovedTargetRow(
            @JsonProperty("id") String id,
            @JsonProperty("repair_id") String repairId,
            @JsonProperty("proposal_id") String proposalId,
            @JsonProperty("target_json") JsonNode targetJson,
            @JsonProperty("receiver_account_id") String receiverAccountId,
            @JsonProperty("employee_id") String employeeId,
            @JsonProperty("organization_id") String organizationId,
            @JsonProperty("custody_type") String custodyType,
            @JsonProperty("approved_by") long approvedBy,
            @JsonProperty("approved_at") String approvedAt) {}

    /** fams_repair_return_recollection row under the frozen wire() projection. */
    public record RecollectionRow(
            @JsonProperty("handover_id") String handoverId,
            @JsonProperty("handler_employee_id") String handlerEmployeeId,
            @JsonProperty("actual_location_id") String actualLocationId,
            @JsonProperty("location_version") long locationVersion,
            @JsonProperty("occurred_at") String occurredAt,
            @JsonProperty("evidence") String evidence,
            @JsonProperty("actor_account_id") String actorAccountId,
            @JsonProperty("recorded_at") String recordedAt) {}

    /**
     * Action matrix of the detail exit; keys mirror the legacy actions LinkedHashMap.
     * manageFactory was only put for an admin or the processor operator, so it stays the single
     * conditional key; requestLoss always carried the final (post factory-followup flip) value
     * of requestScrap.
     */
    public record ReportActions(
            boolean approve,
            boolean claim,
            boolean acceptInPlace,
            boolean completeInPlace,
            boolean acceptAway,
            boolean closeInbound,
            boolean addFaultPhoto,
            boolean cancel,
            boolean requestScrap,
            boolean deliverReturn,
            boolean changeReturnTarget,
            boolean receiveReturn,
            boolean refuseReturn,
            boolean recollectReturn,
            boolean reassign,
            boolean proxyReturn,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean manageFactory,
            boolean requestLoss) {}

    /** Latest factory scope chip of the guard summary (camelCase keys owned by 16b's guard). */
    public record FactoryScope(String id, String scopeStatus, long version) {}

    /** Latest factory round chip of the guard summary; dispatched/received stay ISO instants. */
    public record FactoryRound(
            String id,
            long roundNumber,
            String roundStatus,
            String expectedReturnDate,
            String dispatchedAt,
            String receivedAt,
            long version) {}

    /**
     * Conditional detail/reassignment envelope produced by RepairFactoryGuard.summary. The
     * guard still returns its Map (16b surface); this record freezes only the consumed shape.
     */
    public record FactorySummary(
            FactoryScope scope,
            FactoryRound round,
            long actualDispatchCount,
            boolean followupOpen) {}

    /** GET /repairs/{id} and the REASSIGN idempotency receipt shape: header plus every segment. */
    public record RepairDetail(
            String id,
            String assetId,
            String creatorAccountId,
            long version,
            long assetVersion,
            String status,
            String fault,
            String impact,
            String occurredAt,
            String allowedMethod,
            String processorAccountId,
            long ownershipVersion,
            String claimedAt,
            String acceptedAt,
            String completedAt,
            JsonNode source,
            List<InventoryEvidenceRef> inventoryEvidence,
            List<DecisionRow> decisions,
            List<EventRow> history,
            long historyTotal,
            Long lockVersion,
            boolean photosAvailable,
            List<RepairService.Photo> photos,
            JsonNode returnOrigin,
            List<PhysicalHandlingRow> physicalHandling,
            List<InboundRow> inbound,
            Long loanVersion,
            LocalDate loanExpectedReturnDate,
            List<ReturnHandoverRow> returnHandovers,
            List<ApprovedTargetRow> approvedReturnTargets,
            List<RecollectionRow> returnRecollections,
            @JsonInclude(JsonInclude.Include.NON_NULL) FactorySummary factorySummary,
            @JsonInclude(JsonInclude.Include.NON_NULL) String latestLossReportId,
            ReportActions actions) {
        public RepairDetail {
            inventoryEvidence =
                    inventoryEvidence == null ? null : List.copyOf(inventoryEvidence);
            decisions = decisions == null ? null : List.copyOf(decisions);
            history = history == null ? null : List.copyOf(history);
            photos = photos == null ? null : List.copyOf(photos);
            physicalHandling = physicalHandling == null ? null : List.copyOf(physicalHandling);
            inbound = inbound == null ? null : List.copyOf(inbound);
            returnHandovers = returnHandovers == null ? null : List.copyOf(returnHandovers);
            approvedReturnTargets =
                    approvedReturnTargets == null ? null : List.copyOf(approvedReturnTargets);
            returnRecollections =
                    returnRecollections == null ? null : List.copyOf(returnRecollections);
        }
    }

    /**
     * Frozen returnSourceWire projection; the same shape is persisted into
     * fams_repair_return_origin.source_context_json, so key order and values stay verbatim.
     */
    public record ReturnSourceContext(
            String followupId,
            long version,
            String returnId,
            String assetId,
            String closedLoanId,
            String processorAccountId,
            String warehouseId,
            String locationId,
            String inspectedAt,
            String inspectionReason,
            long lockVersion) {}

    /** GET /repairs/return-sources/{id}: the flat context keys plus the live asset snapshot. */
    public record ReturnSourceDetail(
            String followupId,
            long version,
            String returnId,
            String assetId,
            String closedLoanId,
            String processorAccountId,
            String warehouseId,
            String locationId,
            String inspectedAt,
            String inspectionReason,
            long lockVersion,
            JsonNode asset) {}

    /** Open-loan chip of the reassignment snapshot (the loanWire key set). */
    public record LoanRef(
            String id,
            long version,
            String borrowerEmployeeId,
            String organizationId,
            String expectedReturnDate) {}

    /** Warehouse operator candidate row of the reassignment inspect exit (a.id, e.name). */
    public record Operator(String id, String name) {}

    /**
     * Guarded snapshot of the reassignment inspect exit; factorySummary was only put when a
     * factory follow-up existed, so it stays the single conditional key. lastPhysicalTime keeps
     * the java.time.Instant the guard produced (the legacy wire passed it through untouched).
     */
    public record ReassignmentSnapshot(
            String id,
            long version,
            long ownershipVersion,
            String processorAccountId,
            long assetVersion,
            Long lockVersion,
            String acceptedAt,
            List<PhysicalHandlingRow> physicalHandling,
            ReturnHandoverRow outstandingReturn,
            Instant lastPhysicalTime,
            String approvedTargetId,
            LoanRef loan,
            @JsonInclude(JsonInclude.Include.NON_NULL) FactorySummary factorySummary) {
        public ReassignmentSnapshot {
            physicalHandling = physicalHandling == null ? null : List.copyOf(physicalHandling);
        }
    }

    /** GET /repairs/{id}/reassignment; unavailableReason was only put when declared. */
    public record ReassignmentView(
            ReassignmentSnapshot snapshot,
            List<Operator> operators,
            @JsonInclude(JsonInclude.Include.NON_NULL) String unavailableReason) {
        public ReassignmentView {
            operators = operators == null ? null : List.copyOf(operators);
        }
    }

    /** POST /repairs/{id}/processor-unavailability receipt shape. */
    public record UnavailabilityReceipt(String id, long version, boolean recorded) {}

    /** POST /repairs/{id}/photos receipt shape stored under the PHOTO operation. */
    public record PhotoReceipt(String repairId, String objectId, long version) {}

    /** POST download-grant reply assembled by the photo controller from the signer's Grant. */
    public record PhotoGrant(String url, long expiresAtEpochSecond) {}

    static RepairSummary summary(ObjectMapper json, Map<String, Object> row) {
        return new RepairSummary(
                id(row, "id"),
                id(row, "asset_id"),
                id(row, "creator_account_id"),
                num(row, "version"),
                num(row, "asset_version"),
                (String) row.get("repair_status"),
                (String) row.get("fault"),
                (String) row.get("impact"),
                time(row, "occurred_at"),
                (String) row.get("allowed_method"),
                id(row, "processor_account_id"),
                num(row, "ownership_version"),
                time(row, "claimed_at"),
                time(row, "accepted_at"),
                time(row, "completed_at"),
                node(json, row, "source_snapshot_json"));
    }

    static List<DecisionRow> decisions(ObjectMapper json, List<Map<String, Object>> rows) {
        return rows.stream().map(row -> decision(json, row)).toList();
    }

    static List<EventRow> events(ObjectMapper json, List<Map<String, Object>> rows) {
        return rows.stream().map(row -> event(json, row)).toList();
    }

    static List<PhysicalHandlingRow> physicalHandlings(List<Map<String, Object>> rows) {
        return rows.stream().map(RepairViews::physicalHandling).toList();
    }

    static List<InboundRow> inbounds(List<Map<String, Object>> rows) {
        return rows.stream().map(RepairViews::inbound).toList();
    }

    static List<ReturnHandoverRow> returnHandovers(
            ObjectMapper json, List<Map<String, Object>> rows) {
        return rows.stream().map(row -> returnHandover(json, row)).toList();
    }

    static List<ApprovedTargetRow> approvedTargets(
            ObjectMapper json, List<Map<String, Object>> rows) {
        return rows.stream().map(row -> approvedTarget(json, row)).toList();
    }

    static List<RecollectionRow> recollections(List<Map<String, Object>> rows) {
        return rows.stream().map(RepairViews::recollection).toList();
    }

    static List<Operator> operators(List<Map<String, Object>> rows) {
        return rows.stream().map(RepairViews::operator).toList();
    }

    static DecisionRow decision(ObjectMapper json, Map<String, Object> row) {
        return new DecisionRow(
                id(row, "id"),
                id(row, "repair_id"),
                num(row, "before_version"),
                num(row, "after_version"),
                (String) row.get("result"),
                (String) row.get("allowed_method"),
                node(json, row, "snapshot_json"),
                (String) row.get("reason"),
                id(row, "actor_account_id"),
                time(row, "recorded_at"));
    }

    static EventRow event(ObjectMapper json, Map<String, Object> row) {
        return new EventRow(
                id(row, "id"),
                id(row, "repair_id"),
                (String) row.get("action"),
                num(row, "before_version"),
                num(row, "after_version"),
                id(row, "actor_account_id"),
                (String) row.get("actor_username"),
                (String) row.get("actor_role"),
                node(json, row, "detail_json"),
                time(row, "occurred_at"));
    }

    static PhysicalHandlingRow physicalHandling(Map<String, Object> row) {
        return new PhysicalHandlingRow(
                id(row, "repair_id"),
                id(row, "handler_employee_id"),
                id(row, "actual_location_id"),
                num(row, "location_version"),
                num(row, "before_asset_version"),
                time(row, "occurred_at"),
                (String) row.get("evidence"),
                id(row, "actor_account_id"),
                time(row, "recorded_at"));
    }

    static InboundRow inbound(Map<String, Object> row) {
        return new InboundRow(
                id(row, "repair_id"),
                id(row, "warehouse_id"),
                id(row, "location_id"),
                num(row, "warehouse_version"),
                num(row, "location_version"),
                num(row, "before_asset_version"),
                time(row, "occurred_at"),
                (String) row.get("evidence"),
                id(row, "actor_account_id"),
                time(row, "recorded_at"));
    }

    static ReturnHandoverRow returnHandover(ObjectMapper json, Map<String, Object> row) {
        return new ReturnHandoverRow(
                id(row, "id"),
                id(row, "repair_id"),
                num(row, "round_number"),
                (String) row.get("handover_status"),
                id(row, "active_repair_id"),
                node(json, row, "target_snapshot_json"),
                num(row, "before_asset_version"),
                time(row, "delivered_at"),
                (String) row.get("delivery_evidence"),
                num(row, "delivered_by"),
                time(row, "recorded_at"),
                time(row, "receipt_at"),
                (String) row.get("receipt_evidence"),
                row.get("received_by") == null ? null : num(row, "received_by"));
    }

    static ApprovedTargetRow approvedTarget(ObjectMapper json, Map<String, Object> row) {
        return new ApprovedTargetRow(
                id(row, "id"),
                id(row, "repair_id"),
                id(row, "proposal_id"),
                node(json, row, "target_json"),
                id(row, "receiver_account_id"),
                id(row, "employee_id"),
                id(row, "organization_id"),
                (String) row.get("custody_type"),
                num(row, "approved_by"),
                time(row, "approved_at"));
    }

    static RecollectionRow recollection(Map<String, Object> row) {
        return new RecollectionRow(
                id(row, "handover_id"),
                id(row, "handler_employee_id"),
                id(row, "actual_location_id"),
                num(row, "location_version"),
                time(row, "occurred_at"),
                (String) row.get("evidence"),
                id(row, "actor_account_id"),
                time(row, "recorded_at"));
    }

    static Operator operator(Map<String, Object> row) {
        return new Operator(id(row, "id"), (String) row.get("name"));
    }

    static InventoryEvidenceRef inventoryEvidence(JsonNode facts) {
        return new InventoryEvidenceRef(
                facts.path("taskId").asText(),
                facts.path("observationId").asText(),
                facts.path("clientEventId").asText(),
                facts.path("photoIds"));
    }

    /**
     * Maps the frozen RepairFactoryGuard.summary projection (16b-owned assembly) into the typed
     * chip. The guard emits camelCase keys with stringified ids and raw DATETIME values, exactly
     * as the legacy wire() post-processing rendered them.
     */
    @SuppressWarnings("unchecked")
    static FactorySummary factorySummary(Map<String, Object> summary) {
        var scope = (Map<String, Object>) summary.get("scope");
        var round = (Map<String, Object>) summary.get("round");
        return new FactorySummary(
                scope == null
                        ? null
                        : new FactoryScope(
                                (String) scope.get("id"),
                                (String) scope.get("scopeStatus"),
                                num(scope, "version")),
                round == null
                        ? null
                        : new FactoryRound(
                                (String) round.get("id"),
                                num(round, "roundNumber"),
                                (String) round.get("roundStatus"),
                                (String) round.get("expectedReturnDate"),
                                time(round, "dispatchedAt"),
                                time(round, "receivedAt"),
                                num(round, "version")),
                num(summary, "actualDispatchCount"),
                Boolean.TRUE.equals(summary.get("followupOpen")));
    }

    static ReturnSourceContext returnSourceWire(LoanReturnRepairPort.Context source) {
        return new ReturnSourceContext(
                Long.toString(source.id()),
                source.version(),
                Long.toString(source.returnId()),
                Long.toString(source.assetId()),
                source.loanId() == null ? null : source.loanId().toString(),
                Long.toString(source.processorAccountId()),
                Long.toString(source.warehouseId()),
                Long.toString(source.locationId()),
                source.inspectedAt().toString(),
                source.reason(),
                source.lockVersion());
    }

    static ReturnSourceDetail returnSourceDetail(
            LoanReturnRepairPort.Context source, JsonNode asset) {
        var wire = returnSourceWire(source);
        return new ReturnSourceDetail(
                wire.followupId(),
                wire.version(),
                wire.returnId(),
                wire.assetId(),
                wire.closedLoanId(),
                wire.processorAccountId(),
                wire.warehouseId(),
                wire.locationId(),
                wire.inspectedAt(),
                wire.inspectionReason(),
                wire.lockVersion(),
                asset);
    }

    /** Same key/value semantics as the legacy loanWire Map (which ProxyViews still requires). */
    static LoanRef loanRef(LoanRepairPort.Context loan) {
        return new LoanRef(
                Long.toString(loan.id()),
                loan.version(),
                Long.toString(loan.borrowerEmployeeId()),
                Long.toString(loan.organizationId()),
                loan.expectedReturnDate().toString());
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

    /** The legacy wire() DATETIME branch: ISO instants for both driver shapes, null stays. */
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

    // ================= JAVA-16c collaboration exits =================

    /**
     * One fams_repair_invitation row of the staff board under the frozen collaboration wire()
     * projection (snake_case keys, select order preserved). ended_at/end_reason are nullable
     * columns that the legacy Map always emitted, so they stay explicit nulls under ALWAYS.
     */
    public record CollaborationInvitationRow(
            @JsonProperty("id") String id,
            @JsonProperty("technician_account_id") String technicianAccountId,
            @JsonProperty("technician_username") String technicianUsername,
            @JsonProperty("inviter_account_id") String inviterAccountId,
            @JsonProperty("invitation_status") String invitationStatus,
            @JsonProperty("reason") String reason,
            @JsonProperty("version") long version,
            @JsonProperty("invited_at") String invitedAt,
            @JsonProperty("ended_at") String endedAt,
            @JsonProperty("end_reason") String endReason) {}

    /** One fams_repair_opinion row under the frozen collaboration wire() projection. */
    public record CollaborationOpinionRow(
            @JsonProperty("id") String id,
            @JsonProperty("invitation_id") String invitationId,
            @JsonProperty("judgement") String judgement,
            @JsonProperty("method") String method,
            @JsonProperty("opinion_result") String opinionResult,
            @JsonProperty("occurred_at") String occurredAt,
            @JsonProperty("attachment_ids_json") JsonNode attachmentIdsJson,
            @JsonProperty("actor_username") String actorUsername,
            @JsonProperty("recorded_at") String recordedAt) {}

    /** Eligible-technician dropdown row (a.id, a.username, e.name) of the collaboration exit. */
    public record CollaborationTechnician(String id, String username, String name) {}

    /** Asset chip embedded in the collaboration brief (source snapshot assetCode/name). */
    public record CollaborationAsset(String assetCode, String name) {}

    /**
     * Minimal technician-facing invitation brief (the frozen minimal() key set). The legacy
     * version passed the raw JDBC number through; it stays integral in the wire here, matching
     * the JAVA-11a/16a numeric-column precedent.
     */
    public record CollaborationBrief(
            String invitationId,
            String repairId,
            long version,
            String invitedAt,
            CollaborationAsset asset,
            String fault,
            String impact,
            String faultOccurredAt,
            String status) {}

    /**
     * GET /repair-collaborations/{invitation}: the flat brief keys plus every opinion/photo
     * segment; all keys were always present in the legacy LinkedHashMap.
     */
    public record CollaborationInvitationDetail(
            String invitationId,
            String repairId,
            long version,
            String invitedAt,
            CollaborationAsset asset,
            String fault,
            String impact,
            String faultOccurredAt,
            String status,
            List<CollaborationOpinionRow> opinions,
            long opinionTotal,
            boolean photosAvailable,
            List<RepairCollaborationService.Photo> faultPhotos,
            List<RepairCollaborationService.Photo> attachments) {
        public CollaborationInvitationDetail {
            opinions = opinions == null ? null : List.copyOf(opinions);
            faultPhotos = faultPhotos == null ? null : List.copyOf(faultPhotos);
            attachments = attachments == null ? null : List.copyOf(attachments);
        }
    }

    /** GET /repairs/{repair}/collaborations board envelope. */
    public record CollaborationBoard(
            String repairId,
            long requestVersion,
            boolean canInvite,
            List<CollaborationInvitationRow> invitations,
            long invitationTotal,
            List<CollaborationOpinionRow> opinions,
            long opinionTotal) {
        public CollaborationBoard {
            invitations = invitations == null ? null : List.copyOf(invitations);
            opinions = opinions == null ? null : List.copyOf(opinions);
        }
    }

    /** TECH_INVITE/TECH_REVOKE receipt stored under fams_repair_receipt and returned as body. */
    public record CollaborationRequestReceipt(
            String repairId, String invitationId, long requestVersion) {}

    /** TECH_OPINION receipt shape. */
    public record CollaborationOpinionReceipt(
            String repairId, String invitationId, String opinionId, long invitationVersion) {}

    /** TECH_ATTACHMENT receipt shape; also the collaboration photo upload exit. */
    public record CollaborationAttachmentReceipt(
            String repairId, String invitationId, String objectId, long invitationVersion) {}

    static List<CollaborationInvitationRow> collaborationInvitations(
            List<Map<String, Object>> rows) {
        return rows.stream().map(RepairViews::collaborationInvitation).toList();
    }

    static List<CollaborationOpinionRow> collaborationOpinions(
            ObjectMapper json, List<Map<String, Object>> rows) {
        return rows.stream().map(row -> collaborationOpinion(json, row)).toList();
    }

    static List<CollaborationTechnician> collaborationTechnicians(
            List<Map<String, Object>> rows) {
        return rows.stream().map(RepairViews::collaborationTechnician).toList();
    }

    static CollaborationInvitationRow collaborationInvitation(Map<String, Object> row) {
        return new CollaborationInvitationRow(
                id(row, "id"),
                id(row, "technician_account_id"),
                (String) row.get("technician_username"),
                id(row, "inviter_account_id"),
                (String) row.get("invitation_status"),
                (String) row.get("reason"),
                num(row, "version"),
                time(row, "invited_at"),
                time(row, "ended_at"),
                (String) row.get("end_reason"));
    }

    static CollaborationOpinionRow collaborationOpinion(
            ObjectMapper json, Map<String, Object> row) {
        return new CollaborationOpinionRow(
                id(row, "id"),
                id(row, "invitation_id"),
                (String) row.get("judgement"),
                (String) row.get("method"),
                (String) row.get("opinion_result"),
                time(row, "occurred_at"),
                node(json, row, "attachment_ids_json"),
                (String) row.get("actor_username"),
                time(row, "recorded_at"));
    }

    static CollaborationTechnician collaborationTechnician(Map<String, Object> row) {
        return new CollaborationTechnician(
                id(row, "id"), (String) row.get("username"), (String) row.get("name"));
    }

    // ================= JAVA-16b factory exits =================

    /**
     * fams_repair_factory_scope row under the frozen scopeView projection: the legacy fields()
     * helper camelCased the column names and stringified id/*_id/*_by numbers (DECIMAL cost_limit
     * becomes its plain-string text via wire, DATETIME columns become ISO instant strings).
     * requestBasis is always present (NOT NULL column); decisionBasis is the always-keyed
     * nullable decision snapshot.
     */
    public record FactoryScopeView(
            String id,
            String repairId,
            String scopeStatus,
            String allowedMethod,
            String costPolicy,
            String costLimit,
            String requestReason,
            String requestedBy,
            String requestedAt,
            String decisionReason,
            String decidedBy,
            String decidedAt,
            long version,
            JsonNode requestBasis,
            JsonNode decisionBasis) {}

    /** One fams_repair_factory_quote row of the roundView quotes segment. */
    public record FactoryQuoteRow(
            String id,
            String roundId,
            String amount,
            String createdBy,
            String createdAt,
            JsonNode evidenceIds) {}

    /**
     * The single fams_repair_factory_cost fact of a round (the roundView costFact segment);
     * the segment key is always present and null when no cost row exists.
     */
    public record FactoryCostFactRow(
            String roundId,
            String amount,
            String recordedBy,
            String recordedAt,
            JsonNode evidenceIds) {}

    /** Action matrix of GET /factory/rounds/{round} (the legacy Map.of three-key envelope). */
    public record FactoryRoundActions(
            boolean uploadFinancialEvidence, boolean recordQuote, boolean recordCost) {}

    /**
     * One fams_repair_factory_evidence row of the round detail under the frozen wire()
     * projection: the snake_case select keeps its keys, object_id/uploader_account_id stay raw
     * JDBC values (CHAR(36) string, BIGINT number), recorded_at becomes an ISO instant string.
     */
    public record FactoryEvidenceRow(
            @JsonProperty("object_id") String objectId,
            @JsonProperty("purpose") String purpose,
            @JsonProperty("content_sha256") String contentSha256,
            @JsonProperty("uploader_account_id") long uploaderAccountId,
            @JsonProperty("recorded_at") String recordedAt) {}

    /**
     * fams_repair_factory_round row under the frozen roundView projection (27 fields + quotes +
     * costFact + originalIdentity). actions/evidence/files were only put by the round detail
     * exit, so they stay the three conditional keys (NON_NULL); every other key was always
     * emitted by the legacy fields()/put sequence and keeps explicit nulls under ALWAYS.
     */
    public record FactoryRoundView(
            String id,
            String repairId,
            String assetId,
            String scopeId,
            String dispatchScopeId,
            long roundNumber,
            String roundStatus,
            String providerName,
            String serviceContact,
            String servicePhone,
            String expectedReturnDate,
            String fault,
            String createdBy,
            String createdAt,
            String dispatchedAt,
            String dispatchedBy,
            String dispatchEvidence,
            String receivedAt,
            String receivedBy,
            String receiveLocationId,
            Long receiveLocationVersion,
            String conditionResult,
            String treatmentResult,
            String actualCost,
            String cancelReason,
            long version,
            List<FactoryQuoteRow> quotes,
            FactoryCostFactRow costFact,
            JsonNode originalIdentity,
            @JsonInclude(JsonInclude.Include.NON_NULL) FactoryRoundActions actions,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<FactoryEvidenceRow> evidence,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<RepairFactoryService.EvidenceFile>
                    files) {
        public FactoryRoundView {
            quotes = quotes == null ? null : List.copyOf(quotes);
            evidence = evidence == null ? null : List.copyOf(evidence);
            files = files == null ? null : List.copyOf(files);
        }

        /** The round detail exit: roundView keys plus the three always-appended detail keys. */
        public FactoryRoundView withRoundDetail(
                FactoryRoundActions detailActions,
                List<FactoryEvidenceRow> detailEvidence,
                List<RepairFactoryService.EvidenceFile> detailFiles) {
            return new FactoryRoundView(
                    id,
                    repairId,
                    assetId,
                    scopeId,
                    dispatchScopeId,
                    roundNumber,
                    roundStatus,
                    providerName,
                    serviceContact,
                    servicePhone,
                    expectedReturnDate,
                    fault,
                    createdBy,
                    createdAt,
                    dispatchedAt,
                    dispatchedBy,
                    dispatchEvidence,
                    receivedAt,
                    receivedBy,
                    receiveLocationId,
                    receiveLocationVersion,
                    conditionResult,
                    treatmentResult,
                    actualCost,
                    cancelReason,
                    version,
                    quotes,
                    costFact,
                    originalIdentity,
                    detailActions,
                    detailEvidence,
                    detailFiles);
        }
    }

    /**
     * One fams_repair_factory_event history row under the frozen wire() projection: the history
     * exit never camelized, so keys stay snake_case; ids stay raw JDBC numbers (the factory
     * wire() has no id-stringification branch, unlike the repair core wire) and detail_json
     * parses into a tree.
     */
    public record FactoryEventRow(
            @JsonProperty("id") long id,
            @JsonProperty("repair_id") long repairId,
            @JsonProperty("scope_id") Long scopeId,
            @JsonProperty("round_id") Long roundId,
            @JsonProperty("action") String action,
            @JsonProperty("before_version") long beforeVersion,
            @JsonProperty("after_version") long afterVersion,
            @JsonProperty("actor_account_id") long actorAccountId,
            @JsonProperty("actor_username") String actorUsername,
            @JsonProperty("actor_role") String actorRole,
            @JsonProperty("detail_json") JsonNode detailJson,
            @JsonProperty("recorded_at") String recordedAt) {}

    /** Action matrix of GET /factory (the legacy Map.of nine-key envelope, all always present). */
    public record FactoryActions(
            boolean requestScope,
            boolean decideScope,
            boolean withdrawScope,
            boolean createRound,
            boolean dispatch,
            boolean cancelRound,
            boolean receive,
            boolean uploadEvidence,
            boolean requestCostScope) {}

    /**
     * GET /factory costSummary segment (RepairFactoryCosts.summary): basis four keys in the
     * legacy LinkedHashMap order plus the four policy verdict keys; costLimit stays explicit null
     * for UNLIMITED scopes.
     */
    public record FactoryCostSummary(
            String confirmedTotal,
            long unassessedCount,
            long confirmedRoundCount,
            String latestQuoteId,
            String costPolicy,
            String costLimit,
            boolean overrun,
            boolean followupOpen) {}

    /**
     * The factory command exit and idempotency receipt body (the frozen reply() header plus the
     * conditional scope/round segments and per-command appended keys). Every appended key was
     * only put by its own command, so all trailing keys carry NON_NULL; processorAccountId and
     * lockVersion were always put (possibly null) and stay unconditional.
     */
    public record FactoryReply(
            String repairId,
            long version,
            long assetVersion,
            long ownershipVersion,
            String status,
            String processorAccountId,
            Long lockVersion,
            @JsonInclude(JsonInclude.Include.NON_NULL) FactoryScopeView scope,
            @JsonInclude(JsonInclude.Include.NON_NULL) FactoryRoundView round,
            @JsonInclude(JsonInclude.Include.NON_NULL) String partsChangeId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String replacementId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String sourceId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String replacementAssetId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String inboundReceiptId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String objectId) {}

    /**
     * GET /factory detail exit: the reply() header keys plus the always-emitted nullable scope/
     * round/summary chips, photosAvailable, costSummary and the nine-key action matrix.
     */
    public record FactoryDetail(
            String repairId,
            long version,
            long assetVersion,
            long ownershipVersion,
            String status,
            String processorAccountId,
            Long lockVersion,
            FactoryScopeView pendingScope,
            FactoryScopeView approvedScope,
            FactoryRoundView activeRound,
            FactorySummary summary,
            boolean photosAvailable,
            FactoryCostSummary costSummary,
            FactoryActions actions) {}

    /** One fams_repair_factory_parts_change row; roundId carried the path parameter as String. */
    public record FactoryPartRow(
            String id,
            String roundId,
            String assetId,
            String partName,
            String beforeInfo,
            String afterInfo,
            String observedAt,
            String recordedAt,
            JsonNode evidenceIds,
            String recordedBy) {}

    /** GET /factory/rounds/{round}/parts envelope (legacy Map.of key set). */
    public record FactoryPartsPage(
            int page,
            int pageSize,
            long total,
            List<FactoryPartRow> items,
            boolean canRecord) {
        public FactoryPartsPage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /**
     * One fams_repair_factory_replacement row under the frozen replacementView projection; the
     * inbound segment is the JAVA-10c typed FactoryReplacementInboundPort.Context kept verbatim
     * (its four-key envelope is an inbound-domain contract, not a repair Map to type here).
     */
    public record FactoryReplacementRow(
            String id,
            String sourceId,
            String originalAssetId,
            String originalDisposition,
            String observedAt,
            String recordedAt,
            JsonNode evidenceIds,
            FactoryReplacementInboundPort.Context inbound,
            String replacementAssetId) {}

    /** GET /factory/rounds/{round}/replacements envelope (legacy Map.of key set). */
    public record FactoryReplacementsPage(
            List<FactoryReplacementRow> items, boolean canRecord, boolean canRequestScrap) {
        public FactoryReplacementsPage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    static FactoryScopeView factoryScope(ObjectMapper json, Map<String, Object> row) {
        return new FactoryScopeView(
                id(row, "id"),
                id(row, "repair_id"),
                (String) row.get("scope_status"),
                (String) row.get("allowed_method"),
                (String) row.get("cost_policy"),
                money(row, "cost_limit"),
                (String) row.get("request_reason"),
                id(row, "requested_by"),
                time(row, "requested_at"),
                (String) row.get("decision_reason"),
                id(row, "decided_by"),
                time(row, "decided_at"),
                num(row, "version"),
                node(json, row, "request_snapshot_json"),
                row.get("decision_snapshot_json") == null
                        ? null
                        : node(json, row, "decision_snapshot_json"));
    }

    static FactoryQuoteRow factoryQuote(ObjectMapper json, Map<String, Object> row) {
        return new FactoryQuoteRow(
                id(row, "id"),
                id(row, "round_id"),
                money(row, "amount"),
                id(row, "created_by"),
                time(row, "created_at"),
                node(json, row, "evidence_ids_json"));
    }

    static FactoryCostFactRow factoryCostFact(ObjectMapper json, Map<String, Object> row) {
        return new FactoryCostFactRow(
                id(row, "round_id"),
                money(row, "amount"),
                id(row, "recorded_by"),
                time(row, "recorded_at"),
                node(json, row, "evidence_ids_json"));
    }

    static List<FactoryQuoteRow> factoryQuotes(ObjectMapper json, List<Map<String, Object>> rows) {
        return rows.stream().map(row -> factoryQuote(json, row)).toList();
    }

    static FactoryRoundView factoryRound(
            ObjectMapper json,
            Map<String, Object> row,
            List<Map<String, Object>> quoteRows,
            Map<String, Object> costRow) {
        return new FactoryRoundView(
                id(row, "id"),
                id(row, "repair_id"),
                id(row, "asset_id"),
                id(row, "scope_id"),
                id(row, "dispatch_scope_id"),
                num(row, "round_number"),
                (String) row.get("round_status"),
                (String) row.get("provider_name"),
                (String) row.get("service_contact"),
                (String) row.get("service_phone"),
                dateText(row, "expected_return_date"),
                (String) row.get("fault"),
                id(row, "created_by"),
                time(row, "created_at"),
                time(row, "dispatched_at"),
                id(row, "dispatched_by"),
                (String) row.get("dispatch_evidence"),
                time(row, "received_at"),
                id(row, "received_by"),
                id(row, "receive_location_id"),
                row.get("receive_location_version") == null
                        ? null
                        : num(row, "receive_location_version"),
                (String) row.get("condition_result"),
                (String) row.get("treatment_result"),
                money(row, "actual_cost"),
                (String) row.get("cancel_reason"),
                num(row, "version"),
                factoryQuotes(json, quoteRows),
                costRow == null ? null : factoryCostFact(json, costRow),
                node(json, row, "original_identity_json"),
                null,
                null,
                null);
    }

    static List<FactoryEventRow> factoryEvents(ObjectMapper json, List<Map<String, Object>> rows) {
        return rows.stream().map(row -> factoryEvent(json, row)).toList();
    }

    static FactoryEventRow factoryEvent(ObjectMapper json, Map<String, Object> row) {
        return new FactoryEventRow(
                num(row, "id"),
                num(row, "repair_id"),
                row.get("scope_id") == null ? null : num(row, "scope_id"),
                row.get("round_id") == null ? null : num(row, "round_id"),
                (String) row.get("action"),
                num(row, "before_version"),
                num(row, "after_version"),
                num(row, "actor_account_id"),
                (String) row.get("actor_username"),
                (String) row.get("actor_role"),
                node(json, row, "detail_json"),
                time(row, "recorded_at"));
    }

    static List<FactoryEvidenceRow> factoryEvidences(List<Map<String, Object>> rows) {
        return rows.stream().map(RepairViews::factoryEvidence).toList();
    }

    static FactoryEvidenceRow factoryEvidence(Map<String, Object> row) {
        return new FactoryEvidenceRow(
                (String) row.get("object_id"),
                (String) row.get("purpose"),
                (String) row.get("content_sha256"),
                num(row, "uploader_account_id"),
                time(row, "recorded_at"));
    }

    static FactoryPartRow factoryPart(ObjectMapper json, Map<String, Object> row, long roundId) {
        return new FactoryPartRow(
                id(row, "id"),
                Long.toString(roundId),
                id(row, "asset_id"),
                (String) row.get("part_name"),
                (String) row.get("before_info"),
                (String) row.get("after_info"),
                time(row, "observed_at"),
                time(row, "recorded_at"),
                node(json, row, "evidence_ids_json"),
                id(row, "recorded_by"));
    }

    static FactoryReplacementRow factoryReplacement(
            ObjectMapper json, Map<String, Object> row, FactoryReplacementInboundPort.Context inbound) {
        return new FactoryReplacementRow(
                id(row, "id"),
                (String) row.get("source_id"),
                id(row, "original_asset_id"),
                (String) row.get("original_disposition"),
                time(row, "observed_at"),
                time(row, "recorded_at"),
                node(json, row, "evidence_ids_json"),
                inbound,
                inbound == null ? null : id(row, "replacement_asset_id"));
    }

    /** The frozen reply() header plus the conditional scope/round segments and command extras. */
    static FactoryReply factoryReply(
            Map<String, Object> row,
            Long lockVersion,
            FactoryScopeView scope,
            FactoryRoundView round,
            String partsChangeId,
            String replacementId,
            String sourceId,
            String replacementAssetId,
            String inboundReceiptId,
            String objectId) {
        return new FactoryReply(
                id(row, "id"),
                num(row, "version"),
                num(row, "asset_version"),
                num(row, "ownership_version"),
                (String) row.get("repair_status"),
                id(row, "processor_account_id"),
                lockVersion,
                scope,
                round,
                partsChangeId,
                replacementId,
                sourceId,
                replacementAssetId,
                inboundReceiptId,
                objectId);
    }

    /** The frozen get() assembly: reply header + always-keyed scope/round/summary chips. */
    static FactoryDetail factoryDetail(
            Map<String, Object> row,
            Long lockVersion,
            FactoryScopeView pendingScope,
            FactoryScopeView approvedScope,
            FactoryRoundView activeRound,
            FactorySummary summary,
            boolean photosAvailable,
            FactoryCostSummary costSummary,
            FactoryActions actions) {
        return new FactoryDetail(
                id(row, "id"),
                num(row, "version"),
                num(row, "asset_version"),
                num(row, "ownership_version"),
                (String) row.get("repair_status"),
                id(row, "processor_account_id"),
                lockVersion,
                pendingScope,
                approvedScope,
                activeRound,
                summary,
                photosAvailable,
                costSummary,
                actions);
    }

    /** The legacy wire() DECIMAL branch: plain-string money text, null stays, always keyed. */
    private static String money(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        return value instanceof java.math.BigDecimal decimal
                ? decimal.toPlainString()
                : value.toString();
    }

    /** The legacy wire() DATE branch: java.sql.Date becomes its ISO local-date text. */
    private static String dateText(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null
                ? null
                : (value instanceof java.sql.Date date
                        ? date.toLocalDate().toString()
                        : value.toString());
    }
}
