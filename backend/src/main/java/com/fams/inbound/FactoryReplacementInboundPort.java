package com.fams.inbound;

import com.fams.auth.InboundActorContext;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.Role;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Creates only a draft; normal creator submission, administrator decision and acceptance remain separate. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class FactoryReplacementInboundPort {
    public static final class Failure extends RuntimeException {
        private final int status;
        public Failure(String code, int status) {
            super(code);
            this.status = status;
        }
        public int status() { return status; }
    }
    private final InboundService service;
    private final RequisitionActorContext actors;
    private final JdbcTemplate db;
    private final Clock clock;
    public FactoryReplacementInboundPort(InboundService service, RequisitionActorContext actors,
            JdbcTemplate db, Clock clock) {
        this.service = service;
        this.actors = actors;
        this.db = db;
        this.clock = clock;
    }
    public Map<String, Object> create(RequisitionActorContext.Actor actor, UUID source,
            long oldAsset, long asset, long assetVersion, long repair, long round, LocalDate date,
            long warehouse, long warehouseVersion, long location, long locationVersion) {
        try {
            return createOwned(actor, source, oldAsset, asset, assetVersion, repair, round, date,
                    warehouse, warehouseVersion, location, locationVersion);
        } catch (InboundFailure failure) {
            throw new Failure(failure.code(), failure.status());
        }
    }
    private Map<String, Object> createOwned(RequisitionActorContext.Actor actor, UUID source,
            long oldAsset, long asset, long assetVersion, long repair, long round, LocalDate date,
            long warehouse, long warehouseVersion, long location, long locationVersion) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(Role.WAREHOUSE_OPERATOR)) {
            throw InboundFailure.conflict("INBOUND_REPLACEMENT_WAREHOUSE_REQUIRED");
        }
        var owner = new InboundActorContext.Actor(current.id(), current.username(),
                Role.WAREHOUSE_OPERATOR.name(), actor.requestId().toString());
        var receipt = service.create(owner, "factory-replacement:" + source, Map.of(
                "businessDate", date.toString(), "items", List.of(Map.of(
                        "asset", Map.of("id", Long.toString(asset), "version", assetVersion),
                        "targetWarehouse", Map.of("id", Long.toString(warehouse), "version", warehouseVersion),
                        "targetLocation", Map.of("id", Long.toString(location), "version", locationVersion)))));
        long receiptId = Long.parseLong(receipt.get("id").toString());
        db.update("INSERT INTO fams_inbound_factory_replacement_origin(receipt_id,source_id,"
                        + "asset_id,original_asset_id,repair_id,round_id,recorded_by,recorded_at)"
                        + " VALUES (?,?,?,?,?,?,?,?)", receiptId, source.toString(), asset, oldAsset,
                repair, round, current.id(), clock.instant().truncatedTo(ChronoUnit.MILLIS));
        return service.get(owner, receiptId);
    }
    public Map<String, Object> context(RequisitionActorContext.Actor actor, String source) {
        var current = actors.revalidateLocked(actor);
        String role = current.has(Role.WAREHOUSE_OPERATOR) ? Role.WAREHOUSE_OPERATOR.name()
                : Role.ASSET_ADMIN.name();
        if (!current.has(Role.WAREHOUSE_OPERATOR) && !current.has(Role.ASSET_ADMIN)) {
            throw new Failure("INBOUND_RECEIPT_NOT_FOUND", 404);
        }
        // Owner visibility, including unsubmitted creator-private drafts, is authoritative.
        for (var row : db.queryForList("SELECT o.receipt_id FROM fams_inbound_factory_replacement_origin o"
                + " JOIN fams_inbound_receipt r ON r.id=o.receipt_id WHERE o.source_id=?"
                + " ORDER BY (r.receipt_status='COMPLETED') DESC,"
                + " (r.receipt_status NOT IN ('DRAFT','CANCELLED','REJECTED')) DESC,o.receipt_id DESC", source)) {
            try {
                var receipt = service.get(new InboundActorContext.Actor(current.id(), current.username(),
                        role, actor.requestId().toString()), ((Number) row.get("receipt_id")).longValue());
                return Map.of("id", receipt.get("id"), "version", receipt.get("version"),
                        "status", receipt.get("status"), "receiptNumber", receipt.get("receiptNumber"));
            } catch (InboundFailure failure) {
                if (failure.status() != 404) { throw new Failure(failure.code(), failure.status()); }
            }
        }
        return null;
    }
}
