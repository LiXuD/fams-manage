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
        /**
         * Copies the page items into an unmodifiable list so a caller holding the envelope cannot mutate
         * the rows a service already returned. A null list is preserved as null rather than being turned
         * into an empty one, because the two are distinguishable on the wire and no projection in this
         * domain ever builds a page without items.
         *
         * @param items request views of the page, replaced by an immutable copy when non-null
         * @param total number of rows matching the visibility scope across all pages
         * @param page one-based page number the caller asked for, echoed unchanged
         * @param pageSize page size the caller asked for, echoed unchanged
         * @param canListOverdueDispositions whether the caller may open the overdue disposition queue
         * @param businessDate business-zone date the overdue cutoff was resolved against, null on the
         *     plain list projection
         */
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
        /**
         * Copies the history rows into an unmodifiable list so the detail assembly cannot be mutated
         * through the returned view. A null history is preserved as null, which is the marker that this
         * view came from a list projection rather than from the detail read; the key is {@code NON_NULL},
         * so the two cases are also distinguishable on the wire.
         *
         * @param id stringified fams_scrap_request primary key
         * @param assetId stringified fams_asset primary key the request targets
         * @param sourceType frozen source vocabulary of the request, such as {@code STANDALONE} or
         *     {@code LOSS}
         * @param sourceBusinessId stringified identifier of the originating loss, repair or replacement
         *     row, null for a standalone request
         * @param version current request row version, echoed by clients as {@code If-Match-Version}
         * @param assetVersion pinned fams_asset version the request froze at submission
         * @param ownershipVersion pinned asset ownership version the request froze at submission
         * @param processorAccountId stringified account holding the request, null before it is claimed
         * @param status current request status driving every action flag
         * @param reason applicant-facing reason text recorded at submission
         * @param observedCondition physical condition reported for the asset
         * @param proposedDisposition disposition the requester proposed, which the decision may override
         * @param source asset identity echo read out of the frozen source snapshot
         * @param initialSource publication origin of the asset, null unless it came from an initial batch
         * @param decision parsed decision snapshot, null before any decision, with the attachment
         *     evidence key removed
         * @param factoryDisposition disposal method carried by a factory replacement source, null otherwise
         * @param initialFactoryDispositionAt occurrence time of that factory disposal, present only when
         *     the request also carries an initial batch id
         * @param execution parsed execution snapshot, reduced to {@code writtenOffAt} for a loss source
         * @param lockVersion version of the unreleased scrap business lock, null once it is released
         * @param disposition disposition row projection, null when no disposition exists
         * @param initialFactoryDispositionEvidence evidence reference for an initial factory disposal,
         *     disclosed only to an actor who may decide or execute it
         * @param actions permission flags computed for the reading actor
         * @param correction newest correction row projection, null when there is none
         * @param reversal newest reversal row projection, null when there is none
         * @param recovery newest recovery row projection, null when there is none
         * @param history up to 100 newest event rows, present only on the detail assembly
         */
        public Request {
            history = history == null ? null : List.copyOf(history);
        }
    }

    /**
     * Asset identity as the request froze it, read out of {@code source_snapshot_json} rather than out
     * of the live asset row. The keys change shape on purpose: the snapshot stores the snake_case column
     * names {@code asset_code}, {@code name} and {@code serial_number}, while the wire shape is
     * camelCase, and the mapping is fixed in {@code ScrapViews.source}. A missing serial number becomes
     * an empty string, so the key is always present even for assets that never carried one.
     *
     * @param assetCode frozen asset code, which stays as submitted even if the asset is later renamed
     * @param name frozen asset name
     * @param serialNumber frozen serial number, empty when the asset has none
     */
    public record Source(String assetCode, String name, String serialNumber) {
    }

    /**
     * Publication origin of the asset, present only when the request row carries an
     * {@code initial_batch_id}. It lets a reviewer trace a factory-replacement scrap back to the initial
     * publication batch and the exact sheet row the asset was created from; the whole object is omitted
     * for assets that entered the register some other way.
     *
     * @param batchId stringified initial publication batch identifier
     * @param sheetRow one-based row of the import sheet the asset came from, null when the batch did not
     *     record row positions
     */
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

    /**
     * Facts of a completed physical disposal, read out of {@code completion_snapshot_json}. The row is
     * only assembled when the disposition status is {@code DISPOSED}, so a pending or written-off
     * disposition carries no completion at all. {@code occurredAt} and {@code method} come from the
     * nested {@code facts} object, while {@code operatorName} sits at the snapshot root; the two levels
     * are read separately on purpose and the split is part of the frozen snapshot shape.
     *
     * <p>A factory-replacement disposal is a paper write-off with no physical handover, so both
     * {@code recipient} and {@code operatorName} are forced to null for it and the keys are omitted from
     * the wire body.
     *
     * @param occurredAt disposal time as recorded by the operator, passed through as stored text
     * @param method disposal method chosen from the catalog, such as sale, donation or destruction
     * @param recipient party that received the asset, null for a factory disposal
     * @param operatorName display name of the person who performed the disposal, null for a factory disposal
     */
    public record Completion(
            String occurredAt,
            String method,
            @JsonInclude(JsonInclude.Include.NON_NULL) String recipient,
            @JsonInclude(JsonInclude.Include.NON_NULL) String operatorName) {
    }

    /**
     * Projection of the newest {@code fams_scrap_correction} row of a request, joined with its target
     * location name. The read query keeps at most one row: the active one when a correction is in
     * flight, otherwise the newest completed one, so the client always sees the correction that governs
     * the current asset state. A correction moves an asset that was scrapped by mistake back to a bin,
     * which is why it pins the target location version and the asset, ownership and lock versions
     * separately — each of the four can move independently while the correction is being decided.
     *
     * @param id stringified correction row primary key
     * @param status correction status, from {@code PENDING_ADMIN_DECISION} through
     *     {@code WAITING_WAREHOUSE_CLAIM} and {@code PROCESSING} to {@code COMPLETED}
     * @param version current correction row version, echoed as {@code If-Match-Version}
     * @param assetVersion pinned fams_asset version the correction expects to find at execution
     * @param lockVersion version of the scrap business lock the correction must still hold, null when no
     *     lock was carried over
     * @param targetLocationId stringified bin location the asset is to be returned to
     * @param targetLocationVersion pinned version of that location, compared again at execution
     * @param targetLocationName resolved display name of that location, read through the join
     * @param ownershipVersion pinned asset ownership version the correction expects
     * @param processorAccountId stringified warehouse account holding the correction, null before claim
     * @param reason administrator-facing justification recorded when the correction was requested
     */
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

    /**
     * Projection of the newest {@code fams_scrap_reversal} row of a request, joined with its target
     * location name. A reversal undoes an executed physical scrap by putting the asset back into a bin,
     * so it is only opened while the disposition is still {@code PENDING_DISPOSITION} and before the
     * asset has been handed on. Like the correction it keeps one row — active first, otherwise newest
     * completed — but it carries no lock version, because a reversal always acts on an already released
     * scrap lock.
     *
     * @param id stringified reversal row primary key
     * @param status reversal status, from {@code PENDING_ADMIN_DECISION} through
     *     {@code WAITING_WAREHOUSE_CLAIM} and {@code PROCESSING} to {@code COMPLETED}
     * @param version current reversal row version, echoed as {@code If-Match-Version}
     * @param assetVersion pinned fams_asset version the reversal expects to find at execution
     * @param targetLocationId stringified bin location the asset is to be returned to
     * @param targetLocationVersion pinned version of that location, compared again at execution
     * @param targetLocationName resolved display name of that location, read through the join
     * @param ownershipVersion pinned asset ownership version the reversal expects
     * @param processorAccountId stringified warehouse account holding the reversal, null before claim
     * @param reason administrator-facing justification recorded when the reversal was requested
     */
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

    /**
     * Projection of the newest {@code fams_scrap_recovery} row of a request, joined with the target
     * location, the temporary location and the temporary custodian employee. A recovery follows a loss
     * write-off: the asset was financially written off and then physically found, so it must first be
     * parked in a temporary bin under a named temporary custodian and only afterwards be moved to its
     * final target. That two-stage shape is why the record carries both a temporary and a final
     * location, each with its own pinned version, plus the custodian identifier and resolved name.
     *
     * @param id stringified recovery row primary key
     * @param status recovery status, from {@code PENDING_ADMIN_DECISION} through
     *     {@code WAITING_WAREHOUSE_CLAIM} and {@code PROCESSING} to {@code COMPLETED}
     * @param version current recovery row version, echoed as {@code If-Match-Version}
     * @param assetVersion pinned fams_asset version the recovery expects to find at execution
     * @param foundAt instant the asset was physically found, formatted by SQL as an ISO-8601 text value
     * @param temporaryLocationId stringified bin the asset is parked in while the recovery is open
     * @param temporaryLocationVersion pinned version of that temporary location
     * @param temporaryLocationName resolved display name of the temporary location
     * @param temporaryCustodianEmployeeId stringified employee accountable for the parked asset
     * @param temporaryCustodianName resolved display name of that employee, read through the join
     * @param targetLocationId stringified final location the asset is to be returned to
     * @param targetLocationVersion pinned version of the final location, compared again at execution
     * @param targetLocationName resolved display name of the final location
     * @param ownershipVersion pinned asset ownership version the recovery expects
     * @param processorAccountId stringified warehouse account holding the recovery, null before claim
     * @param reason justification recorded when the recovery was requested
     */
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

    /**
     * One selectable target offered by the execution catalog. The identifier is stringified even though
     * it is a numeric primary key, matching the domain-wide rule that non-version identifiers travel as
     * strings so a client cannot lose precision; the version stays numeric because it is a counter the
     * client echoes back unchanged as a pinned reference version.
     *
     * @param id stringified primary key of the location, bin or employee row
     * @param name display name of that row, resolved by the catalog query
     * @param version current optimistic version of that row at the time the catalog was read
     */
    public record Choice(String id, String name, long version) {
    }

    /** catalog envelope; the page carries no capability flags because admission was already decided. */
    public record ChoicePage(List<Choice> items, long total, int page, int pageSize) {
        /**
         * Copies the catalog items into an unmodifiable list so the page cannot be mutated through the
         * returned envelope. A null list is preserved as null rather than normalised to empty, keeping
         * the same convention as {@link RequestPage}.
         *
         * @param items choices of the page, replaced by an immutable copy when non-null
         * @param total number of rows the catalog query matched across all pages
         * @param page one-based page number the caller asked for, echoed unchanged
         * @param pageSize page size the caller asked for, echoed unchanged
         */
        public ChoicePage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /**
     * Evidence permissions of one scrap request for the calling actor, together with the two versions an
     * upload must match. The flags are computed under the same lock as the versions, so a client can send
     * {@code requestVersion} or {@code dispositionVersion} straight back as {@code If-Match-Version}
     * without re-reading. {@code canRead} is answered without throwing: a caller lacking the sensitive
     * field permission still receives a usable envelope with every flag false, which lets the client
     * render the evidence section as unavailable instead of failing the whole detail read.
     *
     * @param canRead whether the caller holds the scrap attachment sensitive field permission
     * @param canUploadRequest whether the caller created the request and it is still awaiting decision
     * @param canUploadDisposition whether the caller processes a disposition that is still pending
     * @param requestVersion current fams_scrap_request version to match on a request evidence upload
     * @param dispositionVersion current fams_scrap_disposition version to match on a disposition upload,
     *     null when the request has no disposition row
     */
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

    /**
     * Download authorisation handed to a client that wants the bytes of one evidence object. The URL is
     * built by the controller from the prefix the caller used, so the operator and mini program clients
     * each receive a URL under their own base path and neither has to rewrite it; the token inside is the
     * signed grant issued by the attachment signer and is bound to the caller's identity. The grant is
     * deliberately short-lived, which is why the expiry travels next to the URL instead of being implied.
     *
     * @param url absolute path of the content endpoint with the grant token as its query parameter
     * @param expiresAtEpochSecond epoch second after which the token is refused, at most one minute out
     */
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
