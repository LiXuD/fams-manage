package com.fams.inbound;

import com.fams.auth.InboundActorContext;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger LOG = LoggerFactory.getLogger(FactoryReplacementInboundPort.class);
    /**
     * Narrow identity of the created receipt (JAVA-10c): the whole InboundService.get document
     * had exactly one consumer read, the stringified "id" bound into
     * fams_repair_factory_replacement.inbound_receipt_id and embedded under
     * inboundReceiptId in the still-untyped repair HTTP result (typed there by the inbound
     * HTTP batch).
     */
    public record Receipt(String id) {
        public static Receipt fromView(Map<String, Object> receipt) {
            Object id = receipt.get("id");
            return new Receipt(id == null ? null : id.toString());
        }
    }

    /**
     * Frozen four-key projection embedded verbatim as the "inbound" object of the still
     * untyped repair replacementView HTTP response (JAVA-10c; key names mirror the pre-refactor
     * Map.of, values are the InboundService.receipt wire types: String id/status/receiptNumber,
     * numeric version).
     */
    public record Context(String id, long version, String status, String receiptNumber) {
        public static Context fromView(Map<String, Object> receipt) {
            return new Context((String) receipt.get("id"),
                    ((Number) receipt.get("version")).longValue(),
                    (String) receipt.get("status"), (String) receipt.get("receiptNumber"));
        }
    }

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
    public Receipt create(RequisitionActorContext.Actor actor, UUID source,
            long oldAsset, long asset, long assetVersion, long repair, long round, LocalDate date,
            long warehouse, long warehouseVersion, long location, long locationVersion) {
        try {
            return Receipt.fromView(createOwned(actor, source, oldAsset, asset, assetVersion,
                    repair, round, date, warehouse, warehouseVersion, location, locationVersion));
        } catch (InboundFailure failure) {
            throw new Failure(failure.code(), failure.status());
        }
    }
    private Map<String, Object> createOwned(RequisitionActorContext.Actor actor, UUID source,
            long oldAsset, long asset, long assetVersion, long repair, long round, LocalDate date,
            long warehouse, long warehouseVersion, long location, long locationVersion) {
        LOG.debug("INSERT fams_inbound_factory_replacement_origin for asset {} source {}.", asset, source);
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
    public Context context(RequisitionActorContext.Actor actor, String source) {
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
                return Context.fromView(receipt);
            } catch (InboundFailure failure) {
                if (failure.status() != 404) { throw new Failure(failure.code(), failure.status()); }
            }
        }
        return null;
    }
}
