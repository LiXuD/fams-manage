package com.fams.asset;

import com.fams.masterdata.AssetMasterDataPort;
import java.time.Instant;
import java.time.LocalDate;
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
                               Long employeeId) { }
    public record Context(UUID batchId, int sheetRow, long actorId, String username,
                          long parameterId, LocalDate businessDate, Instant now) { }
    public record Created(long id, String code) { }

    private final AssetMapper mapper;
    private final AssetMasterDataPort masterData;
    private final AssetSecretCodec codec;
    private final ObjectMapper json;

    AssetInitialPublicationPort(AssetMapper mapper, AssetMasterDataPort masterData,
            AssetSecretCodec codec, ObjectMapper json) {
        this.mapper = mapper;
        this.masterData = masterData;
        this.codec = codec;
        this.json = json;
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
        String custody = switch (asset.state()) {
            case "在库" -> "STORAGE";
            case "在用个人" -> "PERSONAL";
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
        values.put("lifecycle", "STORAGE".equals(custody) ? "IN_STOCK" : "IN_USE");
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
        return new Created(id, code);
    }
}
