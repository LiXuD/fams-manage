package com.fams.repair;

import com.fams.asset.AssetHandoverPort;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.loan.LoanRepairPort;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Inventory supplies immutable evidence, while formal approval, ownership and completion stay
 * repair-owned.
 */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryRepairPort {
    public record Link(String repairId, String assetId, long version, String status) {}

    public record Evidence(
            String sourceKind,
            String taskId,
            String observationId,
            String clientEventId,
            List<UUID> photoIds,
            String originalObservedAt,
            String originalFactsJson,
            Instant verifiedDiscoveryAt,
            String verificationEvidence) {
        public Evidence {
            photoIds = List.copyOf(photoIds);
        }
    }

    public static final class Failure extends RuntimeException {
        private final int status;

        public Failure(int status, String code) {
            super(code);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final RepairService repairs;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;

    public InventoryRepairPort(
            RepairService repairs, JdbcTemplate db, ObjectMapper json, Clock clock) {
        this.repairs = repairs;
        this.db = db;
        this.json = json;
        this.clock = clock;
    }

    public Link create(
            Actor actor,
            UUID key,
            long asset,
            long version,
            String fault,
            String impact,
            Evidence evidence) {
        return translated(
                () -> {
                    repairs.requireInventoryAdministrator(actor);
                    var row =
                            repairs.submit(
                                    actor,
                                    key,
                                    new RepairInput.Submission(
                                            asset,
                                            version,
                                            fault,
                                            impact,
                                            evidence.verifiedDiscoveryAt()));
                    long id = Long.parseLong(row.get("id").toString());
                    attach(id, evidence);
                    return link(row);
                });
    }

    public Link link(Actor actor, long repair, long asset, long version, Evidence evidence) {
        return translated(
                () -> {
                    repairs.requireInventoryAdministrator(actor);
                    var row = repairs.get(actor, repair);
                    if (!Long.toString(asset).equals(row.get("assetId"))
                            || ((Number) row.get("version")).longValue() != version
                            || !List.of(
                                            "PENDING_ADMIN_DECISION",
                                            "WAITING_WAREHOUSE_CLAIM",
                                            "WAREHOUSE_PROCESSING")
                                    .contains(row.get("status"))) {
                        throw new Failure(409, "INVENTORY_REPAIR_LINK_CHANGED");
                    }
                    attach(repair, evidence);
                    return link(row);
                });
    }

    public Link progress(Actor actor, long repair, long asset) {
        return translated(
                () -> {
                    repairs.requireInventoryAdministrator(actor);
                    var row = repairs.get(actor, repair);
                    if (!Long.toString(asset).equals(row.get("assetId"))) {
                        throw new Failure(409, "INVENTORY_REPAIR_LINK_CHANGED");
                    }
                    return link(row);
                });
    }

    public Evidence evidence(Actor actor, long repair, long observation) {
        return translated(
                () -> {
                    repairs.requireInventoryEvidenceReader(actor, repair);
                    var rows =
                            db.queryForList(
                                    "SELECT source_context_json FROM fams_repair_inventory_evidence"
                                        + " WHERE repair_id=? AND observation_id=?",
                                    repair,
                                    observation);
                    if (rows.size() != 1) {
                        throw new Failure(404, "INVENTORY_REPAIR_EVIDENCE_NOT_FOUND");
                    }
                    return json.readValue(
                            rows.getFirst().get("source_context_json").toString(), Evidence.class);
                });
    }

    private void attach(long repair, Evidence evidence) {
        if (!List.of("CONDITION", "LATE").contains(evidence.sourceKind())) {
            throw new Failure(400, "INVENTORY_REPAIR_SOURCE_INVALID");
        }
        var existing =
                db.queryForList(
                        "SELECT source_context_json FROM fams_repair_inventory_evidence WHERE"
                            + " repair_id=? AND observation_id=? FOR UPDATE",
                        repair,
                        evidence.observationId());
        if (!existing.isEmpty()) {
            if (!json.readTree(existing.getFirst().get("source_context_json").toString())
                    .equals(json.valueToTree(evidence))) {
                throw new Failure(409, "INVENTORY_REPAIR_EVIDENCE_CHANGED");
            }
            return;
        }
        db.update(
                "INSERT INTO"
                    + " fams_repair_inventory_evidence(repair_id,observation_id,source_kind,source_context_json,created_at)"
                    + " VALUES (?,?,?,?,?)",
                repair,
                evidence.observationId(),
                evidence.sourceKind(),
                json.writeValueAsString(evidence),
                clock.instant());
    }

    private Link link(Map<String, Object> row) {
        return new Link(
                row.get("id").toString(),
                row.get("assetId").toString(),
                ((Number) row.get("version")).longValue(),
                row.get("status").toString());
    }

    private <T> T translated(Supplier<T> action) {
        try {
            return action.get();
        } catch (RepairFailure failure) {
            throw new Failure(failure.status(), failure.getMessage());
        } catch (AssetHandoverPort.Failure failure) {
            throw new Failure(failure.status(), failure.getMessage());
        } catch (LoanRepairPort.Failure failure) {
            throw new Failure(409, failure.getMessage());
        }
    }
}
