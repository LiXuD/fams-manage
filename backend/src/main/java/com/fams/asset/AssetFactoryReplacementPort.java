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
    /** Created replacement identity read field-by-field by repair; never serialized as a whole. */
    public record Replaced(String id, long version) {
    }

    /**
     * Immutable replacement provenance. This record is the whole HTTP body of
     * {@code GET /api/v1/assets/{id}/factory-replacements}; component names mirror the frozen
     * camelCase wire keys (JAVA-09 ExecutorChoice precedent) and null serials keep explicit keys.
     */
    public record TraceItem(
            String sourceId,
            String originalAssetId,
            String replacementAssetId,
            String repairId,
            String roundId,
            String originalAssetCode,
            String replacementAssetCode,
            String originalSerialNumber,
            String replacementSerialNumber) {

        static TraceItem fromRow(Map<String, Object> row) {
            return new TraceItem(
                    (String) row.get("source_id"),
                    row.get("original_asset_id").toString(),
                    row.get("asset_id").toString(),
                    row.get("repair_id").toString(),
                    row.get("round_id").toString(),
                    (String) row.get("original_code"),
                    (String) row.get("replacement_code"),
                    (String) row.get("original_serial"),
                    (String) row.get("replacement_serial"));
        }
    }

    /** Frozen {@code Map.of("items", ...)} envelope of the trace endpoint. */
    public record Trace(List<TraceItem> items) {
        /**
         * Freezes the item list of the response envelope. The copy makes the published body
         * immutable, so a later mutation of the caller's list cannot change what was serialized.
         *
         * @param items ordered provenance rows to publish; copied, never retained by reference
         */
        public Trace {
            items = List.copyOf(items);
        }
    }

    private final AssetService service;
    private final AssetMapper mapper;
    private final RequisitionActorContext actors;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;

    /**
     * Wires the port to the asset write path, the mapper, the requisition actor context, raw JDBC
     * for the provenance table and the clock. The class-level {@code MANDATORY} propagation means
     * the caller must already have opened the transaction that this port joins.
     *
     * @param service asset service used to create the replacement card and to gate trace reads
     * @param mapper fams_asset locking and timeline-event writer
     * @param actors context that revalidates the requisition actor against the live account
     * @param db raw JDBC handle for fams_asset_factory_replacement_origin and the trace query
     * @param json codec used to copy the typed create request before forcing its source
     * @param clock timestamp source for provenance rows and events
     */
    public AssetFactoryReplacementPort(AssetService service, AssetMapper mapper,
            RequisitionActorContext actors, JdbcTemplate db, ObjectMapper json, Clock clock) {
        this.service = service;
        this.mapper = mapper;
        this.actors = actors;
        this.db = db;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Creates the replacement card and records its immutable origin link in one caller-owned
     * transaction. The original asset must still be locked at the expected version and be under
     * repair, the new serial must be present, at most 128 characters and different from the
     * original one, and the request source must be absent or already
     * {@code FACTORY_REPLACEMENT}. Domain failures are translated so the requisition caller only
     * ever sees {@code AssetHandoverPort.Failure}.
     *
     * @param actor requisition actor revalidated for the warehouse-operator role
     * @param source idempotency source id, also passed to the asset service as the create key
     * @param oldAsset original fams_asset id being replaced
     * @param oldVersion optimistic version of the original asset the caller read
     * @param repair repair request id recorded in the provenance row
     * @param round factory-repair round id recorded in the provenance row
     * @param input create payload copied and forced to the factory-replacement source
     * @return id and version of the newly created replacement card
     * @throws AssetHandoverPort.Failure 403 without the warehouse-operator role, 409 when the
     *     original changed, when the serial is missing or not distinct, or when the origin insert
     *     hits a duplicate identity, and 400 when the request source is invalid
     */
    public Replaced create(RequisitionActorContext.Actor actor, UUID source,
            long oldAsset, long oldVersion, long repair, long round, AssetCreateRequest input) {
        try {
            return createOwned(actor, source, oldAsset, oldVersion, repair, round, input);
        } catch (AssetOperationException failure) {
            throw new AssetHandoverPort.Failure(failure.code(), failure.status());
        } catch (org.springframework.dao.DuplicateKeyException failure) {
            throw new AssetHandoverPort.Failure("ASSET_REPLACEMENT_IDENTITY_CONFLICT", 409);
        }
    }

    private Replaced createOwned(RequisitionActorContext.Actor actor, UUID source,
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
            throw AssetOperationException.conflict("ASSET_REPLACEMENT_NEW_SERIAL_REQUIRED",
                    "Distinct new serial required.");
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
        return new Replaced(created.getId(), created.getVersion());
    }

    /**
     * Reads the replacement provenance trace of one asset. This is the only method in the port that
     * opens a transaction of its own, a read-only REQUIRED one, because a trace must stay readable
     * outside a factory-replacement write. Reading the asset through the service first applies the
     * ordinary card visibility rules, and the trace query repeats the same draft and cancelled
     * exclusion so a replacement the actor may not see is not leaked through the provenance of the
     * original. Both directions are returned: rows where the asset is the replacement and rows where
     * it is the original, ordered by recording time then replacement id.
     *
     * @param actor read actor whose visibility scope filters draft and cancelled replacements
     * @param asset fams_asset primary key whose provenance is traced, as either side of the link
     * @return trace envelope whose single {@code items} key holds the ordered provenance rows
     * @throws AssetOperationException as raised by the asset service when the actor may not read the
     *     card at all
     */
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    public Trace trace(AssetActorContext.Actor actor, long asset) {
        service.get(actor, asset);
        var rows = db.queryForList(
                "SELECT o.*,a.asset_code AS replacement_code FROM fams_asset_factory_replacement_origin o"
                + " JOIN fams_asset a ON a.id=o.asset_id WHERE (o.asset_id=? OR o.original_asset_id=?)"
                + " AND (a.card_stage NOT IN ('DRAFT','CANCELLED') OR a.creator_account_id=?)"
                + " ORDER BY o.recorded_at,o.asset_id", asset, asset, actor.id());
        return new Trace(rows.stream().map(TraceItem::fromRow).toList());
    }
}
