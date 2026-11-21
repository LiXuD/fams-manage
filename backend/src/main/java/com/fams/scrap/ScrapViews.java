package com.fams.scrap;

import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Typed wire shapes of the scrap domain, mirroring contracts/fams-core-v1.yaml
 * (ScrapRequest/ScrapRequestPage/ScrapChoicePage/ScrapAttachment*).
 * The eligible envelope (contract ScrapEligiblePage) is owned by AssetScrapPort.
 * Rows follow the frozen ScrapReadService.view projection: identifiers stringified,
 * snapshot JSON kept as JsonNode, disposition and history rows keeping their SQL
 * snake_case keys, and the custody snapshot staying raw JSON text on the wire.
 * Conditional keys use NON_NULL; explicit null keys keep default inclusion.
 * Mutation receipts (ScrapService.Receipt, ScrapReviewService.Receipt/DispositionReceipt)
 * were already typed records before this batch and stay in their services.
 */
public final class ScrapViews {
    private ScrapViews() {
    }

    /** list/overdue envelope; businessDate only exists on the overdue projection. */
    public record RequestPage(
            List<Request> items,
            long total,
            int page,
            int pageSize,
            boolean canListOverdueDispositions,
            @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate businessDate) {
        public RequestPage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** history is only present on the get() detail assembly. */
    public record Request(
            String id,
            String assetId,
            String sourceType,
            String sourceBusinessId,
            long version,
            long assetVersion,
            long ownershipVersion,
            String processorAccountId,
            String status,
            String reason,
            String observedCondition,
            String proposedDisposition,
            Source source,
            InitialSource initialSource,
            JsonNode decision,
            @JsonInclude(JsonInclude.Include.NON_NULL) String factoryDisposition,
            @JsonInclude(JsonInclude.Include.NON_NULL) String initialFactoryDispositionAt,
            JsonNode execution,
            Long lockVersion,
            Disposition disposition,
            @JsonInclude(JsonInclude.Include.NON_NULL) String initialFactoryDispositionEvidence,
            Actions actions,
            Correction correction,
            Reversal reversal,
            Recovery recovery,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<HistoryEntry> history) {
        public Request {
            history = history == null ? null : List.copyOf(history);
        }
    }

    public record Source(String assetCode, String name, String serialNumber) {
    }

    public record InitialSource(String batchId, Long sheetRow) {
    }

    /**
     * Disposition rows keep their SQL snake_case keys; custody_snapshot_json is raw JSON
     * text on the wire (never parsed) and is hidden for LOSS/FACTORY sources. completion
     * only exists for DISPOSED rows; factory completions never carry recipient/operatorName.
     */
    public record Disposition(
            @JsonProperty("disposition_status") String dispositionStatus,
            @JsonProperty("due_date") String dueDate,
            long version,
            @JsonProperty("custody_snapshot_json")
            @JsonInclude(JsonInclude.Include.NON_NULL) String custodySnapshotJson,
            @JsonInclude(JsonInclude.Include.NON_NULL) Completion completion) {
    }

    public record Completion(
            String occurredAt,
            String method,
            @JsonInclude(JsonInclude.Include.NON_NULL) String recipient,
            @JsonInclude(JsonInclude.Include.NON_NULL) String operatorName) {
    }

    public record Correction(
            String id,
            String status,
            long version,
            long assetVersion,
            Long lockVersion,
            String targetLocationId,
            long targetLocationVersion,
            String targetLocationName,
            long ownershipVersion,
            String processorAccountId,
            String reason) {
    }

    public record Reversal(
            String id,
            String status,
            long version,
            long assetVersion,
            String targetLocationId,
            long targetLocationVersion,
            String targetLocationName,
            long ownershipVersion,
            String processorAccountId,
            String reason) {
    }

    public record Recovery(
            String id,
            String status,
            long version,
            long assetVersion,
            String foundAt,
            String temporaryLocationId,
            long temporaryLocationVersion,
            String temporaryLocationName,
            String temporaryCustodianEmployeeId,
            String temporaryCustodianName,
            String targetLocationId,
            long targetLocationVersion,
            String targetLocationName,
            long ownershipVersion,
            String processorAccountId,
            String reason) {
    }

    /** Permission flags in wire order; audit-only readers receive all false. */
    public record Actions(
            boolean decide,
            boolean cancel,
            boolean claim,
            boolean execute,
            boolean executeLoss,
            boolean executeFactory,
            boolean dispose,
            boolean initiateCorrection,
            boolean decideCorrection,
            boolean claimCorrection,
            boolean executeCorrection,
            boolean initiateReversal,
            boolean decideReversal,
            boolean claimReversal,
            boolean executeReversal,
            boolean initiateRecovery,
            boolean decideRecovery,
            boolean claimRecovery,
            boolean executeRecovery) {
    }

    /** Event rows keep snake_case keys; occurred_at passes the raw JDBC temporal value through. */
    public record HistoryEntry(
            long id,
            String action,
            @JsonProperty("actor_username") String actorUsername,
            @JsonProperty("occurred_at") Object occurredAt) {
    }

    public record Choice(String id, String name, long version) {
    }

    public record ChoicePage(List<Choice> items, long total, int page, int pageSize) {
        public ChoicePage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    public record AttachmentCapabilities(
            boolean canRead,
            boolean canUploadRequest,
            boolean canUploadDisposition,
            long requestVersion,
            Long dispositionVersion) {
    }

    /** Persisted into fams_scrap_receipt.response_json and replayed inside the idempotency window. */
    public record AttachmentReceipt(String id, String kind, String objectId, long version) {
    }

    public record AttachmentGrant(String url, long expiresAtEpochSecond) {
    }

    /**
     * Frozen ScrapReadService.view projection: the service supplies the locked row and the
     * lock/disposition/correction/reversal/recovery query results; role checks keep reading
     * the raw rows so processor identity comparisons stay numeric, exactly as before.
     */
    static Request request(Current actor, ObjectMapper json, Map<String, Object> row,
            List<Map<String, Object>> locks, List<Map<String, Object>> dispositions,
            List<Map<String, Object>> correctionRows, List<Map<String, Object>> reversalRows,
            List<Map<String, Object>> recoveryRows, List<HistoryEntry> history) {
        long id = ((Number) row.get("id")).longValue();
        var source = json.readTree(row.get("source_snapshot_json").toString());
        var decision = row.get("decision_snapshot_json") == null
                ? null : json.readTree(row.get("decision_snapshot_json").toString());
        if (decision instanceof tools.jackson.databind.node.ObjectNode object) {
            object.remove("attachmentEvidence");
        }
        boolean lossSource = "LOSS".equals(row.get("source_type"));
        boolean factory = "FACTORY_REPLACEMENT".equals(row.get("source_type"));
        String factoryDisposition = null;
        String initialFactoryDispositionAt = null;
        if (factory) {
            var context = source.path("repairEvidence").path("context");
            factoryDisposition = context.path("method").asText();
            if (row.get("initial_batch_id") != null) {
                initialFactoryDispositionAt = context.path("occurredAt").asText();
            }
        }
        var execution = row.get("execution_snapshot_json") == null
                ? null : json.readTree(row.get("execution_snapshot_json").toString());
        var executionWire = lossSource && execution != null
                ? json.createObjectNode().put("writtenOffAt", execution.path("writtenOffAt").asText()) : execution;
        Long lockVersion = locks.isEmpty() ? null : ((Number) locks.getFirst().get("lock_version")).longValue();
        boolean owner = actor.has(Role.WAREHOUSE_OPERATOR)
                && Objects.equals(actor.id(), row.get("processor_account_id"));
        var dispositionRow = dispositions.isEmpty() ? null : dispositions.getFirst();
        boolean canDispose = dispositionRow != null && actor.has(Role.WAREHOUSE_OPERATOR)
                && Objects.equals(dispositionRow.get("processor_account_id"), actor.id())
                && "EXECUTED".equals(row.get("request_status")) && ScrapReviewService.physicalSource(row.get("source_type"))
                && "PENDING_DISPOSITION".equals(dispositionRow.get("disposition_status"));
        var disposition = dispositionRow == null ? null
                : disposition(json, dispositionRow, lossSource || factory, factory);
        var correction = correctionRows.isEmpty() ? null : correction(correctionRows.getFirst());
        var reversal = reversalRows.isEmpty() ? null : reversal(reversalRows.getFirst());
        var recovery = recoveryRows.isEmpty() ? null : recovery(recoveryRows.getFirst());
        boolean writtenOff = dispositionRow != null && "LOSS_WRITTEN_OFF".equals(dispositionRow.get("disposition_status"));
        boolean pendingDisposal = dispositionRow != null
                && "PENDING_DISPOSITION".equals(dispositionRow.get("disposition_status"));
        boolean correctionPending = correction != null && "PENDING_ADMIN_DECISION".equals(correction.status());
        boolean correctionClaimable = correction != null && "WAITING_WAREHOUSE_CLAIM".equals(correction.status());
        boolean correctionProcessor = correction != null && actor.has(Role.WAREHOUSE_OPERATOR)
                && !correctionRows.isEmpty() && Objects.equals(actor.id(),
                        correctionRows.getFirst().get("processor_account_id"));
        boolean decide = actor.has(Role.ASSET_ADMIN) && "PENDING_ADMIN_DECISION".equals(row.get("request_status"));
        boolean executeFactory = owner && "PROCESSING".equals(row.get("request_status")) && factory;
        // The evidence key is decided before audit-only denial flattens the actions, exactly as before.
        String initialFactoryDispositionEvidence = null;
        if (factory && row.get("initial_batch_id") != null && (decide || executeFactory)) {
            initialFactoryDispositionEvidence =
                    source.path("repairEvidence").path("context").path("evidence").asText();
        }
        var actions = new Actions(
                decide,
                !locks.isEmpty() && ScrapReviewService.mayCancel(actor, row),
                actor.has(Role.WAREHOUSE_OPERATOR)
                        && "WAITING_WAREHOUSE_CLAIM".equals(row.get("request_status")),
                owner && "PROCESSING".equals(row.get("request_status"))
                        && ScrapReviewService.physicalSource(row.get("source_type")),
                owner && "PROCESSING".equals(row.get("request_status")) && lossSource,
                executeFactory,
                canDispose,
                actor.has(Role.ASSET_ADMIN)
                        && "RESTORE_REQUIRED".equals(row.get("request_status"))
                        && "STANDALONE".equals(row.get("source_type")) && correction == null,
                correctionPending && actor.has(Role.ASSET_ADMIN),
                correctionClaimable && actor.has(Role.WAREHOUSE_OPERATOR),
                correctionProcessor && "PROCESSING".equals(correction.status()),
                reversal == null && pendingDisposal
                        && ScrapReadService.mayRequestReversal(actor, row),
                reversal != null
                        && "PENDING_ADMIN_DECISION".equals(reversal.status()) && actor.has(Role.ASSET_ADMIN),
                reversal != null
                        && "WAITING_WAREHOUSE_CLAIM".equals(reversal.status()) && actor.has(Role.WAREHOUSE_OPERATOR),
                reversal != null && actor.has(Role.WAREHOUSE_OPERATOR)
                        && Objects.equals(actor.id(), reversalRows.getFirst().get("processor_account_id"))
                        && "PROCESSING".equals(reversal.status()),
                recovery == null && writtenOff && ScrapReadService.mayRequestRecovery(actor, row),
                recovery != null
                        && "PENDING_ADMIN_DECISION".equals(recovery.status()) && actor.has(Role.ASSET_ADMIN),
                recovery != null
                        && "WAITING_WAREHOUSE_CLAIM".equals(recovery.status()) && actor.has(Role.WAREHOUSE_OPERATOR),
                recovery != null && actor.has(Role.WAREHOUSE_OPERATOR)
                        && Objects.equals(actor.id(), recoveryRows.getFirst().get("processor_account_id"))
                        && "PROCESSING".equals(recovery.status()));
        if (ScrapReadService.auditOnly(actor)) {
            actions = denied();
        }
        return new Request(
                Long.toString(id),
                row.get("asset_id").toString(),
                (String) row.get("source_type"),
                row.get("source_business_id") == null ? null : row.get("source_business_id").toString(),
                ((Number) row.get("version")).longValue(),
                ((Number) row.get("asset_version")).longValue(),
                ((Number) row.get("ownership_version")).longValue(),
                row.get("processor_account_id") == null ? null : row.get("processor_account_id").toString(),
                (String) row.get("request_status"),
                (String) row.get("reason"),
                (String) row.get("observed_condition"),
                (String) row.get("proposed_disposition"),
                source(source),
                initialSource(row),
                decision,
                factoryDisposition,
                initialFactoryDispositionAt,
                executionWire,
                lockVersion,
                disposition,
                initialFactoryDispositionEvidence,
                actions,
                correction,
                reversal,
                recovery,
                history);
    }

    static Actions denied() {
        return new Actions(false, false, false, false, false, false, false, false, false, false,
                false, false, false, false, false, false, false, false, false);
    }

    static Source source(JsonNode source) {
        return new Source(source.path("asset_code").asText(), source.path("name").asText(),
                source.path("serial_number").asText(""));
    }

    static InitialSource initialSource(Map<String, Object> row) {
        return row.get("initial_batch_id") == null ? null
                : new InitialSource(row.get("initial_batch_id").toString(), num(row, "initial_sheet_row"));
    }

    static Disposition disposition(ObjectMapper json, Map<String, Object> row, boolean hideCustody, boolean factory) {
        Completion completion = null;
        Object raw = row.get("completion_snapshot_json");
        if (raw != null && "DISPOSED".equals(row.get("disposition_status"))) {
            var snapshot = json.readTree(raw.toString());
            var facts = snapshot.path("facts");
            completion = factory
                    ? new Completion(facts.path("occurredAt").asText(), facts.path("method").asText(), null, null)
                    : new Completion(facts.path("occurredAt").asText(), facts.path("method").asText(),
                            facts.path("recipient").asText(), snapshot.path("operatorName").asText());
        }
        return new Disposition(
                (String) row.get("disposition_status"),
                (String) row.get("due_date"),
                num(row, "version"),
                hideCustody ? null : (String) row.get("custody_snapshot_json"),
                completion);
    }

    static Correction correction(Map<String, Object> row) {
        return new Correction(
                row.get("id").toString(),
                (String) row.get("correction_status"),
                num(row, "version"),
                num(row, "asset_version"),
                numOrNull(row, "lock_version"),
                row.get("target_location_id").toString(),
                num(row, "target_location_version"),
                (String) row.get("target_location_name"),
                num(row, "ownership_version"),
                id(row, "processor_account_id"),
                (String) row.get("reason"));
    }

    static Reversal reversal(Map<String, Object> row) {
        return new Reversal(
                row.get("id").toString(),
                (String) row.get("reversal_status"),
                num(row, "version"),
                num(row, "asset_version"),
                row.get("target_location_id").toString(),
                num(row, "target_location_version"),
                (String) row.get("target_location_name"),
                num(row, "ownership_version"),
                id(row, "processor_account_id"),
                (String) row.get("reason"));
    }

    static Recovery recovery(Map<String, Object> row) {
        return new Recovery(
                row.get("id").toString(),
                (String) row.get("recovery_status"),
                num(row, "version"),
                num(row, "asset_version"),
                (String) row.get("found_at"),
                row.get("temporary_location_id").toString(),
                num(row, "temporary_location_version"),
                (String) row.get("temporary_location_name"),
                row.get("temporary_custodian_employee_id").toString(),
                (String) row.get("temporary_custodian_name"),
                row.get("target_location_id").toString(),
                num(row, "target_location_version"),
                (String) row.get("target_location_name"),
                num(row, "ownership_version"),
                id(row, "processor_account_id"),
                (String) row.get("reason"));
    }

    static HistoryEntry historyEntry(Map<String, Object> row) {
        return new HistoryEntry(num(row, "id"), (String) row.get("action"),
                (String) row.get("actor_username"), row.get("occurred_at"));
    }

    static Choice choice(Map<String, Object> row) {
        return new Choice(row.get("id").toString(), (String) row.get("name"), num(row, "version"));
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
}
