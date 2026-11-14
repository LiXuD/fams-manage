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
    public record InitialAsset(String code, String name, long categoryId, long organizationId,
                               String model, String unit, String serial, String state,
                               Long warehouseId, Long locationId, Long usingOrganizationId,
                               Long employeeId, LocalDate borrowedDate, LocalDate expectedReturnDate,
                               String preScrapState, String scrapReason, String scrapCondition,
                               String proposedDisposition, String preRepairState, InitialRepairPort.Facts repairFacts,
                               InitialScrapPort.LossFacts lossFacts,
                               InitialScrapPort.FactoryDisposition factoryDisposition) { }
    public record Context(UUID batchId, int sheetRow, long actorId, String username,
                          long parameterId, LocalDate businessDate, Instant now, long administratorId, String timezone) { }
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
                    context.batchId(), context.sheetRow(), context.actorId(), context.username(), context.administratorId(),
                    context.timezone(), context.now()));
        }
        if (pendingScrap) {
            var scrap = new com.fams.business.InitialScrapPort.Opening(id,
                    repairOpening ? asset.repairFacts().factory() != null ? "FACTORY_REPAIR" : "UNDER_REPAIR" : custodyLifecycle,
                    asset.scrapReason().strip(), asset.scrapCondition().strip(),
                    asset.proposedDisposition().strip(), context.actorId(),
                    context.batchId(), context.sheetRow(), context.now());
            if (lossOpening) {
                scraps.openLoss(scrap, asset.lossFacts(), context.businessDate());
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

    @Transactional
    public void assertReversible(long assetId, UUID batchId) {
        loans.assertReversible(assetId, batchId);
        scraps.assertReversible(assetId, batchId);
        repairs.assertReversible(assetId, batchId);
        if (downstream.stream().anyMatch(port -> port.hasPublishedUse(assetId))) {
            throw new IllegalStateException("ASSET_IMPORT_ROLLBACK_DOWNSTREAM");
        }
    }

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
