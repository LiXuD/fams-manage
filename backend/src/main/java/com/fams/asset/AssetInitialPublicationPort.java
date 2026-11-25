package com.fams.asset;

import com.fams.masterdata.AssetMasterDataPort;
import com.fams.business.InitialRepairPort;
import com.fams.business.InitialScrapPort;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Asset-owned insertion of an approved initial custody fact and its label token. */
@Component
@Profile("fams-product")
public class AssetInitialPublicationPort {
    /**
     * One approved initial-import row, already validated and resolved by the import domain. It is
     * never serialized: {@link #create} reads it component-by-component into the fams_asset insert
     * map, so the component names carry no stored or wire shape and only the Chinese {@code state}
     * vocabulary is contractual. The state fields describe what the sheet said, while
     * {@code preScrapState} and {@code preRepairState} carry the opening state that a pending-scrap
     * or repair overlay replaced. Non-null {@code repairFacts} or {@code lossFacts} is what opens
     * the corresponding downstream record, and {@code factoryDisposition} selects the
     * factory-disposition scrap variant.
     *
     * @param code asset code as typed, or blank to allocate one from the category sequence
     * @param name asset name, required and trimmed to at most 256 characters
     * @param categoryId resolved fams_asset_category id and code-allocation scope
     * @param organizationId resolved owning organization id
     * @param model manufacturer model text, stored as given
     * @param unit measurement unit text, stored as given
     * @param serial serial number; blank becomes null and the uppercase form is derived from it
     * @param state Chinese sheet state: 在库, 在用个人, 借出, 在用公用 or 待报废
     * @param warehouseId storage warehouse id, only kept when the opening state is 在库
     * @param locationId current location id for every custody type
     * @param usingOrganizationId using organization id, only kept outside storage custody
     * @param employeeId responsible employee id, routed to the personal or shared column by custody
     * @param borrowedDate borrowing start date, compared against repair and loss acceptance times
     * @param expectedReturnDate expected return date carried into the opened loan
     * @param preScrapState opening state recorded before the 待报废 overlay was applied
     * @param scrapReason trimmed scrap reason for the pending-scrap request
     * @param scrapCondition trimmed physical condition for the pending-scrap request
     * @param proposedDisposition trimmed proposed disposition for the pending-scrap request
     * @param preRepairState opening state recorded before the repair overlay was applied
     * @param repairFacts non-null repair opening facts, including the optional factory leg
     * @param lossFacts non-null loss opening facts driving the loss-sourced scrap variant
     * @param factoryDisposition non-null factory disposition facts driving that scrap variant
     */
    public record InitialAsset(String code, String name, long categoryId, long organizationId,
                               String model, String unit, String serial, String state,
                               Long warehouseId, Long locationId, Long usingOrganizationId,
                               Long employeeId, LocalDate borrowedDate, LocalDate expectedReturnDate,
                               String preScrapState, String scrapReason, String scrapCondition,
                               String proposedDisposition, String preRepairState, InitialRepairPort.Facts repairFacts,
                               InitialScrapPort.LossFacts lossFacts,
                               InitialScrapPort.FactoryDisposition factoryDisposition) { }
    /**
     * Execution context shared by every row of one import batch. It is read component-by-component
     * into the insert map and the event summary, so no component name reaches storage except through
     * the explicit {@code batchId}/{@code sheetRow} event keys. {@code timezone} is the frozen zone
     * of the batch parameter version and is used to compare sheet dates against acceptance instants;
     * {@code now} is the single instant stamped on the card, tag and event.
     *
     * @param batchId import batch identity recorded in every event and downstream opening
     * @param sheetRow 1-based sheet row number, must be at least 2 because row 1 is the header
     * @param actorId importing account id, used as creator and event actor
     * @param username account name snapshot stored on the card
     * @param parameterId parameter version id frozen for the batch
     * @param businessDate created and inbound business date written to the card
     * @param now instant stamped on the card, tag and timeline event
     * @param administratorId administrator account passed to a repair opening
     * @param timezone frozen zone id used to interpret sheet dates
     */
    public record Context(UUID batchId, int sheetRow, long actorId, String username,
                          long parameterId, LocalDate businessDate, Instant now,
                          long administratorId, String timezone) { }
    /**
     * Identity of the card just published. Both components are returned to the import domain, which
     * uses {@code id} to open downstream records and {@code code} for the per-row result sheet; the
     * record is never serialized as a whole.
     *
     * @param id generated fams_asset primary key
     * @param code asset code finally stored, either the typed one or a freshly allocated one
     */
    public record Created(long id, String code) { }

    private final AssetMapper mapper;
    private final AssetMasterDataPort masterData;
    private final AssetSecretCodec codec;
    private final ObjectMapper json;
    private final com.fams.business.InitialBorrowingPort loans;
    private final com.fams.business.InitialScrapPort scraps;
    private final InitialRepairPort repairs;
    private final java.util.List<AssetDownstreamUsePort> downstream;

    AssetInitialPublicationPort(AssetMapper mapper, AssetMasterDataPort masterData,
            AssetSecretCodec codec, ObjectMapper json, com.fams.business.InitialBorrowingPort loans,
            com.fams.business.InitialScrapPort scraps, InitialRepairPort repairs,
            java.util.List<AssetDownstreamUsePort> downstream) {
        this.mapper = mapper;
        this.masterData = masterData;
        this.codec = codec;
        this.json = json;
        this.loans = loans;
        this.scraps = scraps;
        this.repairs = repairs;
        this.downstream = java.util.List.copyOf(downstream);
    }

    /**
     * Publishes one approved import row as a live asset card inside its own transaction. The
     * published card is ACTIVE at version 0 with source text INITIAL_IMPORT, its tag token is
     * minted and stored both encrypted and hashed, and every downstream fact the row asserted
     * (borrowing, repair, pending scrap and its loss, factory-disposition or repair variants) is
     * opened before the method returns, so a row is never published half-way. A blank code
     * allocates one from the category sequence and marks that reservation used only after the
     * insert succeeded. Custody is derived from the Chinese state and drives which of the three
     * responsibility columns receives the employee id.
     *
     * @param asset approved row to publish; must be non-null with a name of at most 256 characters
     * @param context batch execution context; must be non-null with a positive actor id and a sheet
     *     row of at least 2
     * @return generated card id and the asset code actually stored
     * @throws IllegalArgumentException when either argument is malformed, the actor id is not
     *     positive, the sheet row is below 2, the code exceeds 128 characters, the name is missing
     *     or too long, or the sheet state is not one of the five known values
     * @throws IllegalStateException when a borrowing start date falls after the repair acceptance
     *     instant or after the loss last-seen instant, reported with the corresponding
     *     ASSET_IMPORT_INITIAL_*_BORROW_TIME_INVALID marker
     */
    @Transactional
    public Created create(InitialAsset asset, Context context) {
        if (asset == null || context == null || context.actorId() < 1 || context.sheetRow() < 2) {
            throw new IllegalArgumentException("ASSET_IMPORT_ROW_INVALID");
        }
        String code = asset.code() == null ? "" : asset.code().strip();
        Long reservationId = null;
        if (code.isEmpty()) {
            var allocation = masterData.reserveAssetCodes(asset.categoryId(), 1).getFirst();
            code = allocation.code();
            reservationId = allocation.reservationId();
        }
        if (code.length() > 128 || asset.name() == null || asset.name().isBlank()
                || asset.name().strip().length() > 256) {
            throw new IllegalArgumentException("ASSET_IMPORT_ROW_INVALID");
        }
        boolean pendingScrap = "待报废".equals(asset.state());
        boolean repairOpening = asset.repairFacts() != null;
        boolean lossOpening = asset.lossFacts() != null;
        String opening = repairOpening ? asset.preRepairState()
                : pendingScrap ? asset.preScrapState() : asset.state();
        if (asset.repairFacts() != null && "借出".equals(opening)
                && asset.borrowedDate().isAfter(LocalDate.ofInstant(asset.repairFacts().acceptedAt(),
                        java.time.ZoneId.of(context.timezone())))) {
            throw new IllegalStateException("ASSET_IMPORT_INITIAL_REPAIR_BORROW_TIME_INVALID");
        }
        if (lossOpening && "借出".equals(opening)
                && asset.borrowedDate().isAfter(LocalDate.ofInstant(asset.lossFacts().lastSeenAt(),
                        java.time.ZoneId.of(context.timezone())))) {
            throw new IllegalStateException("ASSET_IMPORT_INITIAL_LOSS_BORROW_TIME_INVALID");
        }
        String custody = switch (opening) {
            case "在库" -> "STORAGE";
            case "在用个人", "借出" -> "PERSONAL";
            case "在用公用" -> "SHARED";
            default -> throw new IllegalArgumentException("ASSET_IMPORT_ROW_INVALID");
        };
        Map<String, Object> values = new HashMap<>();
        values.put("code", code);
        values.put("name", asset.name().strip());
        values.put("categoryId", asset.categoryId());
        values.put("organizationId", asset.organizationId());
        values.put("model", asset.model());
        values.put("unit", asset.unit());
        values.put("serial", asset.serial() == null || asset.serial().isBlank()
                ? null : asset.serial().strip());
        values.put("serialNormalized", values.get("serial") == null ? null
                : ((String) values.get("serial")).toUpperCase(Locale.ROOT));
        String custodyLifecycle = "STORAGE".equals(custody) ? "IN_STOCK" : "借出".equals(opening) ? "BORROWED" : "IN_USE";
        values.put("lifecycle", pendingScrap && !repairOpening && !lossOpening ? "PENDING_SCRAP" : custodyLifecycle);
        values.put("custody", custody);
        values.put("warehouseId", "STORAGE".equals(custody) ? asset.warehouseId() : null);
        values.put("locationId", asset.locationId());
        values.put("usingOrganizationId", "STORAGE".equals(custody) ? null
                : asset.usingOrganizationId());
        values.put("employeeId", "PERSONAL".equals(custody) ? asset.employeeId() : null);
        values.put("departmentEmployeeId", "SHARED".equals(custody) ? asset.employeeId() : null);
        values.put("businessDate", context.businessDate());
        values.put("parameterId", context.parameterId());
        values.put("actorId", context.actorId());
        values.put("username", context.username());
        values.put("now", context.now());
        mapper.insertInitial(values);
        long id = ((Number) values.get("id")).longValue();
        if (reservationId != null) { masterData.markUsed(reservationId); }
        String token = codec.token();
        mapper.insertTag(id, codec.encrypt(token), codec.hash(token), context.now());
        mapper.event(id, "ASSET_INITIAL_IMPORTED", context.now(), json.writeValueAsString(Map.of(
                "batchId", context.batchId().toString(), "sheetRow", context.sheetRow(),
                "source", "INITIAL_IMPORT")), context.actorId());
        if ("借出".equals(opening)) {
            loans.open(id, asset.employeeId(), asset.usingOrganizationId(), context.batchId(),
                    context.sheetRow(), asset.borrowedDate(), asset.expectedReturnDate(),
                    context.businessDate(), context.parameterId(), context.now());
        }
        if (repairOpening) {
            repairs.open(new InitialRepairPort.Opening(id, (String) values.get("serial"), asset.repairFacts(),
                    context.batchId(), context.sheetRow(), context.actorId(), context.username(),
                    context.administratorId(), context.timezone(), context.now()));
        }
        if (pendingScrap) {
            var scrap = new com.fams.business.InitialScrapPort.Opening(id,
                    repairOpening ? asset.repairFacts().factory() != null
                            ? "FACTORY_REPAIR" : "UNDER_REPAIR" : custodyLifecycle,
                    asset.scrapReason().strip(), asset.scrapCondition().strip(),
                    asset.proposedDisposition().strip(), context.actorId(),
                    context.batchId(), context.sheetRow(), context.now());
            if (lossOpening) {
                scraps.openLoss(scrap, asset.lossFacts(), context.businessDate(), context.timezone());
            } else if (asset.factoryDisposition() != null) {
                scraps.openFactoryDisposition(scrap, asset.factoryDisposition());
            } else if (repairOpening) {
                scraps.openRepair(scrap);
            } else {
                scraps.open(scrap);
            }
        }
        return new Created(id, code);
    }

    /**
     * Rollback precondition check for one published card. Every downstream domain is asked first
     * whether its opening is still exactly the untouched initial-import one, then each registered
     * {@link AssetDownstreamUsePort} is polled for a published use. Nothing is written here; the
     * method only decides whether {@link #cancel} may run at all, and it runs inside the caller's
     * transaction so the locks the delegated ports take are held until the rollback commits.
     *
     * @param assetId fams_asset primary key whose publication is being rolled back
     * @param batchId import batch the publication belonged to; downstream rows must still carry it
     * @throws IllegalStateException with marker ASSET_IMPORT_ROLLBACK_DOWNSTREAM when any
     *     downstream opening has moved on or any consumer reports a published use
     */
    @Transactional
    public void assertReversible(long assetId, UUID batchId) {
        loans.assertReversible(assetId, batchId);
        scraps.assertReversible(assetId, batchId);
        repairs.assertReversible(assetId, batchId);
        if (downstream.stream().anyMatch(port -> port.hasPublishedUse(assetId))) {
            throw new IllegalStateException("ASSET_IMPORT_ROLLBACK_DOWNSTREAM");
        }
    }

    /**
     * Undoes one publication after {@link #assertReversible} accepted it. The card is re-checked
     * under a lock because the assertion and the cancellation are separate calls: it must still be
     * ACTIVE, still carry source text INITIAL_IMPORT and still sit at version 0, and its tag row
     * must still exist. Downstream repair, borrowing and scrap openings are cancelled first, the
     * tag token is archived with reason INITIAL_IMPORT_ROLLBACK and then deleted, and only then is
     * the card itself cancelled and the ASSET_INITIAL_ROLLED_BACK event written. The method declares
     * no propagation of its own and therefore must be called inside the rollback service's
     * transaction.
     *
     * @param assetId fams_asset primary key to cancel
     * @param batchId import batch id embedded in the rollback event summary
     * @param actorId account id recorded as the cancelling actor
     * @param reason operator-facing rollback reason stored on the card and in the event
     * @param now instant stamped on the tag archive, the card and the event
     * @throws IllegalStateException with marker ASSET_IMPORT_ROLLBACK_DOWNSTREAM when the card is
     *     missing, no longer an untouched initial import, when its tag row is gone, or when the
     *     final cancellation did not affect exactly one row
     */
    public void cancel(long assetId, UUID batchId, long actorId, String reason, Instant now) {
        Map<String, Object> asset = mapper.lock(assetId);
        if (asset == null || !"ACTIVE".equals(asset.get("card_stage"))
                || !"INITIAL_IMPORT".equals(asset.get("source_text"))
                || ((Number) asset.get("version")).longValue() != 0) {
            throw new IllegalStateException("ASSET_IMPORT_ROLLBACK_DOWNSTREAM");
        }
        Map<String, Object> tag = mapper.tag(assetId);
        if (tag == null) { throw new IllegalStateException("ASSET_IMPORT_ROLLBACK_DOWNSTREAM"); }
        repairs.cancel(assetId, batchId, actorId, now);
        loans.cancel(assetId, batchId, now);
        scraps.cancel(assetId, batchId, now);
        mapper.archiveTag(assetId, (String) tag.get("token_hash"),
                ((LocalDateTime) tag.get("issued_at")).toInstant(ZoneOffset.UTC), now,
                "INITIAL_IMPORT_ROLLBACK");
        mapper.deleteTag(assetId);
        if (mapper.cancelInitial(assetId, reason, actorId, now) != 1) {
            throw new IllegalStateException("ASSET_IMPORT_ROLLBACK_DOWNSTREAM");
        }
        mapper.event(assetId, "ASSET_INITIAL_ROLLED_BACK", now,
                json.writeValueAsString(Map.of("batchId", batchId.toString(), "reason", reason)), actorId);
    }
}
