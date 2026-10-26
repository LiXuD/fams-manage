package com.fams.repair;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Caller holds the repair header in its existing transaction. All financial reads are current
 * locking reads: identity revalidation can establish an older REPEATABLE_READ snapshot before that
 * lock.
 */
final class RepairFactoryCosts {
    private final JdbcTemplate db;

    RepairFactoryCosts(JdbcTemplate db) {
        this.db = db;
    }

    Map<String, Object> approved(long repair) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_repair_factory_scope WHERE repair_id=? AND"
                            + " scope_status='APPROVED' ORDER BY id DESC LIMIT 1 FOR UPDATE",
                        repair);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    RepairFactoryCostPolicy policy(Map<String, Object> scope) {
        return scope == null
                ? RepairFactoryCostPolicy.parse(null, null)
                : RepairFactoryCostPolicy.parse(
                        scope.get("cost_policy").toString(),
                        scope.get("cost_limit") == null
                                ? null
                                : scope.get("cost_limit").toString());
    }

    BigDecimal total(long repair) {
        return db
                .queryForList(
                        "SELECT actual_cost FROM fams_repair_factory_round WHERE repair_id=? FOR"
                            + " UPDATE",
                        repair)
                .stream()
                .map(row -> row.get("actual_cost"))
                .filter(Objects::nonNull)
                .map(value -> (BigDecimal) value)
                .reduce(new BigDecimal("0.00"), BigDecimal::add);
    }

    long unassessed(long repair) {
        return db.queryForList(
                        "SELECT id FROM fams_repair_factory_round WHERE repair_id=? AND"
                            + " round_status='RECEIVED' AND actual_cost IS NULL FOR UPDATE",
                        repair)
                .size();
    }

    boolean blocked(long repair) {
        return unassessed(repair) != 0 || !policy(approved(repair)).covers(total(repair));
    }

    Map<String, Object> basis(long repair) {
        var result = new LinkedHashMap<String, Object>();
        result.put("confirmedTotal", total(repair).toPlainString());
        result.put("unassessedCount", unassessed(repair));
        result.put(
                "confirmedRoundCount",
                db.queryForList(
                                "SELECT id FROM fams_repair_factory_round WHERE repair_id=?"
                                        + " AND actual_cost IS NOT NULL FOR UPDATE",
                                repair)
                        .size());
        var quotes =
                db.queryForList(
                        "SELECT q.id FROM fams_repair_factory_quote q JOIN"
                            + " fams_repair_factory_round r ON r.id=q.round_id WHERE r.repair_id=?"
                            + " ORDER BY q.id DESC LIMIT 1 FOR UPDATE",
                        repair);
        result.put(
                "latestQuoteId", quotes.isEmpty() ? "0" : quotes.getFirst().get("id").toString());
        return result;
    }

    Map<String, Object> summary(long repair) {
        var policy = policy(approved(repair));
        var result = new LinkedHashMap<String, Object>(basis(repair));
        result.put("costPolicy", policy.mode());
        result.put("costLimit", policy.limit() == null ? null : policy.limit().toPlainString());
        result.put("overrun", !policy.covers(total(repair)));
        result.put("followupOpen", blocked(repair));
        return result;
    }

    Map<String, Object> dispatchScope(long repair, Map<String, Object> round) {
        var scope = approved(repair);
        if (scope == null || blocked(repair)) {
            throw new RepairFailure(409, "REPAIR_FACTORY_COST_SCOPE_REQUIRED");
        }
        var policy = policy(scope);
        var quotes =
                db.queryForList(
                        "SELECT amount FROM fams_repair_factory_quote WHERE round_id=?"
                                + " ORDER BY id DESC LIMIT 1 FOR UPDATE",
                        round.get("id"));
        if ("LIMITED".equals(policy.mode()) && quotes.isEmpty()) {
            throw new RepairFailure(409, "REPAIR_FACTORY_QUOTE_REQUIRED");
        }
        BigDecimal current =
                round.get("actual_cost") == null
                        ? BigDecimal.ZERO
                        : (BigDecimal) round.get("actual_cost");
        BigDecimal projected =
                quotes.isEmpty() ? current : (BigDecimal) quotes.getFirst().get("amount");
        if (!policy.covers(total(repair).subtract(current).add(projected))) {
            throw new RepairFailure(409, "REPAIR_FACTORY_COST_SCOPE_REQUIRED");
        }
        return scope;
    }
}
