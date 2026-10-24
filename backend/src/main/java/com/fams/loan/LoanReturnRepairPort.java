package com.fams.loan;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Return owns the completed inspection, followup obligation and atomic lock adoption. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class LoanReturnRepairPort {
    public record Context(
            long id,
            long version,
            long returnId,
            long assetId,
            Long loanId,
            long processorAccountId,
            long warehouseId,
            long locationId,
            Instant inspectedAt,
            String reason,
            String parametersJson,
            long lockVersion) {}

    private final JdbcTemplate db;
    private final Clock clock;

    public LoanReturnRepairPort(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public Map<String, Object> list(int page, int size) {
        String from = " FROM fams_return_followup WHERE business_type='REPAIR'";
        var rows =
                db.queryForList(
                        "SELECT id,return_id,asset_id,loan_id,followup_status,"
                            + "processor_account_id,reason,version,linked_repair_id,last_repair_id,repair_status"
                                + from
                                + " ORDER BY id DESC LIMIT ? OFFSET ?",
                        size,
                        ((long) page - 1) * size);
        return Map.of(
                "items",
                rows.stream().map(this::wire).toList(),
                "total",
                Objects.requireNonNull(db.queryForObject("SELECT COUNT(*)" + from, Long.class)),
                "page",
                page,
                "pageSize",
                size);
    }

    public Context pending(long id) {
        var rows =
                db.queryForList(
                        "SELECT"
                            + " f.*,r.return_status,r.inspection_result,r.completed_at,r.warehouse_id,r.location_id,r.parameter_snapshot_json"
                            + " FROM fams_return_followup f JOIN fams_asset_return r ON"
                            + " r.id=f.return_id WHERE f.id=? FOR UPDATE",
                        id);
        if (rows.size() != 1) {
            throw changed();
        }
        var row = rows.getFirst();
        if (!"REPAIR".equals(row.get("business_type"))
                || !"PENDING".equals(row.get("followup_status"))
                || row.get("linked_repair_id") != null
                || !"COMPLETED".equals(row.get("return_status"))
                || !"DAMAGED".equals(row.get("inspection_result"))) {
            throw changed();
        }
        Long loan = nullable(row.get("loan_id"));
        if (loan != null
                && !Objects.equals(
                        1L,
                        db.queryForObject(
                                "SELECT COUNT(*) FROM fams_loan WHERE id=? AND asset_id=? AND"
                                        + " relation_status='RETURNED'",
                                Long.class,
                                loan,
                                row.get("asset_id")))) {
            throw changed();
        }
        var locks =
                db.queryForList(
                        "SELECT lock_version FROM fams_asset_business_lock WHERE"
                                + " asset_id=? AND business_type='RETURN_REPAIR' AND business_id=?"
                                + " AND business_item_id=? AND released_at IS NULL FOR UPDATE",
                        row.get("asset_id"),
                        id,
                        id);
        if (locks.size() != 1) {
            throw changed();
        }
        Object time = row.get("completed_at");
        Instant inspected =
                time instanceof java.sql.Timestamp stamp
                        ? stamp.toInstant()
                        : ((java.time.LocalDateTime) time).toInstant(java.time.ZoneOffset.UTC);
        return new Context(
                id,
                number(row, "version"),
                number(row, "return_id"),
                number(row, "asset_id"),
                loan,
                number(row, "processor_account_id"),
                number(row, "warehouse_id"),
                number(row, "location_id"),
                inspected,
                row.get("reason").toString(),
                row.get("parameter_snapshot_json").toString(),
                number(locks.getFirst(), "lock_version"));
    }

    public void adopt(Context source, long repair) {
        if (db.update(
                        "UPDATE fams_return_followup SET"
                            + " followup_status='ACCEPTED',linked_repair_id=?,"
                            + "last_repair_id=?,repair_status='PENDING_ADMIN_DECISION',version=version+1"
                            + " WHERE id=? AND version=? AND followup_status='PENDING' AND"
                            + " linked_repair_id IS NULL",
                        repair,
                        repair,
                        source.id(),
                        source.version())
                != 1) {
            throw changed();
        }
        if (db.update(
                        "UPDATE fams_asset_business_lock SET"
                            + " business_type='REPAIR',business_id=?,business_item_id=?,lock_version=lock_version+1"
                            + " WHERE asset_id=? AND business_type='RETURN_REPAIR' AND"
                            + " business_id=? AND business_item_id=? AND released_at IS NULL AND"
                            + " lock_version=?",
                        repair,
                        repair,
                        source.assetId(),
                        source.id(),
                        source.id(),
                        source.lockVersion())
                != 1) {
            throw changed();
        }
        closeTodos(source.id());
    }

    public void progress(long followup, long repair, String status, Long processor) {
        var source = linked(followup, repair);
        if (processor != null) {
            db.update(
                    "UPDATE fams_return_followup SET processor_account_id=? WHERE id=?",
                    processor,
                    followup);
        }
        boolean retry = "REJECTED".equals(status) || "CANCELLED".equals(status);
        if (retry) {
            if (db.update(
                            "UPDATE fams_asset_business_lock SET"
                                + " business_type='RETURN_REPAIR',business_id=?,business_item_id=?,lock_version=lock_version+1"
                                + " WHERE asset_id=? AND business_type='REPAIR' AND business_id=?"
                                + " AND released_at IS NULL",
                            followup,
                            followup,
                            source.get("asset_id"),
                            repair)
                    != 1) {
                throw changed();
            }
        }
        db.update(
                "UPDATE fams_return_followup SET followup_status=?,repair_status=?,"
                        + "linked_repair_id=?,version=version+1 WHERE id=?",
                retry ? "PENDING" : "COMPLETED".equals(status) ? "CLOSED" : "ACCEPTED",
                status,
                retry ? null : repair,
                followup);
        if (retry) {
            closeTodos(followup);
            db.update(
                    "INSERT INTO"
                        + " fams_todo(pool_type,business_type,business_id,todo_type,todo_status,opened_at)"
                        + " VALUES ('ASSET_ADMIN','RETURN_REPAIR',?,'FOLLOWUP','OPEN',?)",
                    followup,
                    clock.instant());
        }
    }

    private Map<String, Object> linked(long followup, long repair) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_return_followup WHERE id=? AND business_type='REPAIR'"
                            + " AND linked_repair_id=? AND followup_status='ACCEPTED' FOR UPDATE",
                        followup,
                        repair);
        if (rows.size() != 1) {
            throw changed();
        }
        return rows.getFirst();
    }

    private void closeTodos(long id) {
        db.update(
                "UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE"
                        + " business_type='RETURN_REPAIR' AND business_id=? AND closed_at IS NULL",
                clock.instant(),
                id);
    }

    private LoanRepairPort.Failure changed() {
        return new LoanRepairPort.Failure("REPAIR_RETURN_SOURCE_CHANGED");
    }

    private long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private Long nullable(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private Map<String, Object> wire(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        row.forEach(
                (key, value) ->
                        result.put(
                                key,
                                (key.equals("id") || key.endsWith("_id")) && value != null
                                        ? value.toString()
                                        : value));
        return result;
    }
}
