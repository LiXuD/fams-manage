package com.fams.repair;

import com.fams.asset.AssetFactoryRepairPort;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/** Repair-owned facts only; no dependency back to either repair application service. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class RepairFactoryGuard {
    private final JdbcTemplate db;
    private final AssetFactoryRepairPort assets;

    public RepairFactoryGuard(JdbcTemplate db, AssetFactoryRepairPort assets) {
        this.db = db;
        this.assets = assets;
    }

    public Map<String, Object> active(long repair) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_repair_factory_round WHERE repair_id=? AND"
                                + " round_status IN ('PENDING_DISPATCH','DISPATCHED') FOR UPDATE",
                        repair);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public boolean blocked(long repair) {
        return active(repair) != null
                || !db.queryForList(
                                "SELECT id FROM fams_repair_factory_scope WHERE repair_id=? AND"
                                        + " scope_status='PENDING' FOR UPDATE",
                                repair)
                        .isEmpty();
    }

    public void requireInternal(long repair) {
        if (blocked(repair)) {
            throw new RepairFailure(409, "REPAIR_FACTORY_FOLLOWUP_OPEN");
        }
    }

    /** The common repair timeline is visible more widely than the private factory detail. */
    public Map<String, Object> summary(long repair) {
        var rounds =
                db.queryForList(
                        "SELECT"
                            + " id,round_number,round_status,expected_return_date,dispatched_at,received_at,version"
                            + " FROM fams_repair_factory_round WHERE repair_id=? ORDER BY id DESC"
                            + " LIMIT 1",
                        repair);
        var scopes =
                db.queryForList(
                        "SELECT id,scope_status,version FROM fams_repair_factory_scope WHERE"
                                + " repair_id=? ORDER BY id DESC LIMIT 1",
                        repair);
        if (rounds.isEmpty() && scopes.isEmpty()) {
            return null;
        }
        var result = new LinkedHashMap<String, Object>();
        result.put(
                "scope",
                scopes.isEmpty()
                        ? null
                        : Map.of(
                                "id", scopes.getFirst().get("id").toString(),
                                "scopeStatus", scopes.getFirst().get("scope_status"),
                                "version", scopes.getFirst().get("version")));
        Map<String, Object> round = null;
        if (!rounds.isEmpty()) {
            var row = rounds.getFirst();
            round = new LinkedHashMap<>();
            round.put("id", row.get("id").toString());
            round.put("roundNumber", row.get("round_number"));
            round.put("roundStatus", row.get("round_status"));
            round.put("expectedReturnDate", row.get("expected_return_date").toString());
            round.put("dispatchedAt", row.get("dispatched_at"));
            round.put("receivedAt", row.get("received_at"));
            round.put("version", row.get("version"));
        }
        result.put("round", round);
        result.put(
                "actualDispatchCount",
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_factory_round WHERE repair_id=?"
                                + " AND dispatched_at IS NOT NULL",
                        Long.class,
                        repair));
        result.put("followupOpen", blocked(repair));
        return result;
    }

    public boolean dispatchedIdentity(Map<String, Object> repair) {
        var round = active(number(repair, "id"));
        if (round == null || !"DISPATCHED".equals(round.get("round_status"))) {
            return false;
        }
        var current =
                assets.snapshot(
                        number(repair, "asset_id"),
                        number(repair, "asset_version"),
                        "FACTORY_REPAIR");
        assets.requireOriginal(current, round.get("original_identity_json").toString());
        return true;
    }

    public Instant latestPhysicalTime(Map<String, Object> repair) {
        long id = number(repair, "id");
        var recollections =
                db.queryForList(
                        "SELECT r.occurred_at AS actual_time FROM fams_repair_return_recollection r"
                                + " JOIN fams_repair_return_handover h ON h.id=r.handover_id"
                                + " WHERE h.repair_id=? ORDER BY h.id DESC LIMIT 1",
                        id);
        var handling =
                db.queryForList(
                        "SELECT occurred_at AS actual_time FROM fams_repair_physical_handling"
                                + " WHERE repair_id=?",
                        id);
        // Actual handling facts precede the server's recording timestamp in the existing model.
        Instant latest =
                instant(
                        !recollections.isEmpty()
                                ? recollections.getFirst().get("actual_time")
                                : !handling.isEmpty()
                                        ? handling.getFirst().get("actual_time")
                                        : repair.get("accepted_at"));
        var receipts =
                db.queryForList(
                        "SELECT received_at AS actual_time FROM fams_repair_factory_round"
                                + " WHERE repair_id=? AND round_status='RECEIVED'"
                                + " ORDER BY received_at DESC,id DESC LIMIT 1",
                        id);
        if (!receipts.isEmpty()) {
            var candidate = instant(receipts.getFirst().get("actual_time"));
            if (latest == null || candidate.isAfter(latest)) {
                latest = candidate;
            }
        }
        return latest;
    }

    private Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof LocalDateTime local) {
            return local.toInstant(ZoneOffset.UTC);
        }
        return value instanceof Instant time ? time : Instant.parse(value.toString());
    }

    private long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }
}
