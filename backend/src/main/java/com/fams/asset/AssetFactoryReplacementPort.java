package com.fams.asset;

import com.fams.auth.AssetActorContext;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.Role;
import com.fams.product.contract.model.AssetCreateRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The asset owner allocates a new code/tag and retains immutable replacement provenance. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class AssetFactoryReplacementPort {
    private final AssetService service;
    private final AssetMapper mapper;
    private final RequisitionActorContext actors;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;

    public AssetFactoryReplacementPort(AssetService service, AssetMapper mapper,
            RequisitionActorContext actors, JdbcTemplate db, ObjectMapper json, Clock clock) {
        this.service = service;
        this.mapper = mapper;
        this.actors = actors;
        this.db = db;
        this.json = json;
        this.clock = clock;
    }

    public Map<String, Object> create(RequisitionActorContext.Actor actor, UUID source,
            long oldAsset, long oldVersion, long repair, long round, AssetCreateRequest input) {
        try {
            return createOwned(actor, source, oldAsset, oldVersion, repair, round, input);
        } catch (AssetOperationException failure) {
            throw new AssetHandoverPort.Failure(failure.code(), failure.status());
        } catch (org.springframework.dao.DuplicateKeyException failure) {
            throw new AssetHandoverPort.Failure("ASSET_REPLACEMENT_IDENTITY_CONFLICT", 409);
        }
    }

    private Map<String, Object> createOwned(RequisitionActorContext.Actor actor, UUID source,
            long oldAsset, long oldVersion, long repair, long round, AssetCreateRequest input) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(Role.WAREHOUSE_OPERATOR)) {
            throw AssetOperationException.invalid("ASSET_REPLACEMENT_WAREHOUSE_REQUIRED", "Warehouse required.");
        }
        var old = mapper.lock(oldAsset);
        if (old == null || ((Number) old.get("version")).longValue() != oldVersion
                || !"ACTIVE".equals(old.get("card_stage"))
                || !"NORMAL".equals(old.get("exception_status"))
                || !List.of("UNDER_REPAIR", "FACTORY_REPAIR").contains(old.get("lifecycle_status"))) {
            throw AssetOperationException.conflict("ASSET_REPLACEMENT_ORIGINAL_CHANGED", "Original changed.");
        }
        String serial = input.getSerialNumber();
        if (serial == null || serial.isBlank() || serial.length() > 128
                || old.get("serial_number") != null
                && serial.strip().equalsIgnoreCase(old.get("serial_number").toString().strip())) {
            throw AssetOperationException.conflict("ASSET_REPLACEMENT_NEW_SERIAL_REQUIRED", "Distinct new serial required.");
        }
        if (input.getSource() != null && !"FACTORY_REPLACEMENT".equals(input.getSource())) {
            throw AssetOperationException.invalid("ASSET_REPLACEMENT_SOURCE_INVALID", "Replacement source required.");
        }
        // Copy before forcing source; never mutate the caller's typed idempotency input.
        var copy = json.readValue(json.writeValueAsString(input), AssetCreateRequest.class);
        copy.setSource("FACTORY_REPLACEMENT");
        var owner = new AssetActorContext.Actor(current.id(), current.username(), true, false,
                Role.WAREHOUSE_OPERATOR.name(), actor.requestId().toString());
        var created = service.create(owner, copy, "factory-replacement:" + source);
        long asset = Long.parseLong(created.getId());
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        db.update("INSERT INTO fams_asset_factory_replacement_origin(source_id,asset_id,"
                        + "original_asset_id,repair_id,round_id,original_code,original_serial,"
                        + "replacement_serial,recorded_by,recorded_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                source.toString(), asset, oldAsset, repair, round, old.get("asset_code"),
                old.get("serial_number"), serial.strip(), current.id(), now);
        mapper.event(asset, "FACTORY_REPLACEMENT_ORIGIN", now,
                json.writeValueAsString(Map.of("sourceId", source.toString(), "originalAssetId",
                        Long.toString(oldAsset), "repairId", Long.toString(repair),
                        "roundId", Long.toString(round))), current.id());
        return Map.of("id", created.getId(), "version", created.getVersion(),
                "assetCode", created.getAssetCode());
    }

    public Map<String, Object> trace(AssetActorContext.Actor actor, long asset) {
        service.get(actor, asset);
        var rows = db.queryForList("SELECT o.*,a.asset_code AS replacement_code FROM fams_asset_factory_replacement_origin o"
                + " JOIN fams_asset a ON a.id=o.asset_id WHERE (o.asset_id=? OR o.original_asset_id=?)"
                + " AND (a.card_stage NOT IN ('DRAFT','CANCELLED') OR a.creator_account_id=?)"
                + " ORDER BY o.recorded_at,o.asset_id", asset, asset, actor.id());
        return Map.of("items", rows.stream().map(row -> {
            var item = new java.util.LinkedHashMap<String, Object>();
            item.put("sourceId", row.get("source_id"));
            item.put("originalAssetId", row.get("original_asset_id").toString());
            item.put("replacementAssetId", row.get("asset_id").toString());
            item.put("repairId", row.get("repair_id").toString());
            item.put("roundId", row.get("round_id").toString());
            item.put("originalAssetCode", row.get("original_code"));
            item.put("replacementAssetCode", row.get("replacement_code"));
            item.put("originalSerialNumber", row.get("original_serial"));
            item.put("replacementSerialNumber", row.get("replacement_serial"));
            return item;
        }).toList());
    }
}
