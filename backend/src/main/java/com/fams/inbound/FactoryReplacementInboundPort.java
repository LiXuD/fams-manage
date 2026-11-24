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
        /**
         * Narrows an untyped receipt view map to its stringified id, tolerating a missing id by
         * propagating null instead of failing.
         *
         * @param receipt receipt view map as returned by {@code InboundService}
         * @return receipt identity holding the stringified id
         */
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
        /**
         * Extracts the frozen four-key projection from an untyped receipt view map, coercing the
         * numeric version to a long.
         *
         * @param receipt receipt view map as returned by {@code InboundService}
         * @return context projection embedded in the repair replacement view response
         */
        public static Context fromView(Map<String, Object> receipt) {
            return new Context((String) receipt.get("id"),
                    ((Number) receipt.get("version")).longValue(),
                    (String) receipt.get("status"), (String) receipt.get("receiptNumber"));
        }
    }

    /**
     * Public failure surrogate for the package-private {@code InboundFailure}: the port translates
     * inbound-domain rejections into this type so cross-domain callers (repair) never need access
     * to the inbound package internals. Carries the original error code as the message.
     */
    public static final class Failure extends RuntimeException {
        private final int status;
        /**
         * Creates a failure with the inbound error code and the HTTP status to return.
         *
         * @param code inbound error code, stored as the exception message
         * @param status HTTP status code the caller's error handler maps this failure to
         */
        public Failure(String code, int status) {
            super(code);
            this.status = status;
        }
        /**
         * Returns the HTTP status code associated with this failure.
         *
         * @return HTTP status code to surface in the error response
         */
        public int status() { return status; }
    }
    private final InboundService service;
    private final RequisitionActorContext actors;
    private final JdbcTemplate db;
    private final Clock clock;
    /**
     * Wires the port to the inbound service, actor revalidation, the replacement-origin table and
     * the time source used to stamp origin rows.
     *
     * @param service inbound service creating and reading receipts on behalf of the repair flow
     * @param actors context revalidating the repair caller before acting as a warehouse operator
     * @param db JDBC template writing {@code fams_inbound_factory_replacement_origin}
     * @param clock time source for the origin row's recorded-at timestamp
     */
    public FactoryReplacementInboundPort(InboundService service, RequisitionActorContext actors,
            JdbcTemplate db, Clock clock) {
        this.service = service;
        this.actors = actors;
        this.db = db;
        this.clock = clock;
    }
    /**
     * Creates the draft inbound receipt for a factory replacement asset: the caller must hold the
     * warehouse-operator role, the receipt is created through {@code InboundService} with the new
     * asset, target warehouse and location versions, and the replacement origin (source, old and
     * new asset, repair and round) is recorded for traceability. Submission, decision and
     * acceptance stay in the normal inbound workflow.
     *
     * @param actor repair-flow actor, revalidated and required to hold the warehouse-operator role
     * @param source replacement source id linking receipt and repair round
     * @param oldAsset id of the asset being replaced
     * @param asset id of the replacement asset being received
     * @param assetVersion optimistic version of the replacement asset
     * @param repair factory repair id that produced the replacement
     * @param round repair round id within that repair
     * @param date business date recorded on the receipt
     * @param warehouse target warehouse id
     * @param warehouseVersion optimistic version of the target warehouse
     * @param location target location id
     * @param locationVersion optimistic version of the target location
     * @return identity of the created draft receipt
     * @throws Failure wrapping the inbound error code and status when the role check, receipt
     *         creation or version validation is rejected
     */
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
    /**
     * Resolves the current inbound context for a replacement source: candidates are ordered with
     * completed receipts first, then active ones, then the newest id, and each is read through the
     * owner visibility rules of {@code InboundService} (including creator-private drafts). Missing
     * candidates are skipped; a non-404 inbound rejection is surfaced to the caller.
     *
     * @param actor repair-flow actor, must hold the warehouse-operator or asset-admin role
     * @param source replacement source id to resolve receipts for
     * @return the best-visible receipt context, or null when no receipt is visible for the source
     * @throws Failure with code {@code INBOUND_RECEIPT_NOT_FOUND} (404) when the caller holds
     *         neither required role, or wrapping a non-404 inbound rejection
     */
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
