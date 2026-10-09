package com.fams.requisition;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/** The requisition domain owns a separately approved change to an outstanding borrow due date. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class RequisitionBorrowDatePort implements com.fams.business.BorrowRequestDatePort {
    private final JdbcTemplate db;

    RequisitionBorrowDatePort(JdbcTemplate db) { this.db = db; }

    public Map<String, Object> eligible(long account, boolean administrator, boolean warehouse, int page, int size) {
        String scope = administrator ? "1=1" : "r.creator_account_id=?"
                + (warehouse ? " OR EXISTS (SELECT 1 FROM fams_requisition_ownership o WHERE o.request_id=r.id"
                        + " AND o.processor_account_id=? AND o.archived_at IS NULL)" : "");
        String from = " FROM fams_requisition r WHERE r.request_type='BORROW' AND r.request_status IN"
                + " ('WAITING_WAREHOUSE_CLAIM','WAREHOUSE_PROCESSING','WAITING_HANDOVER') AND (" + scope + ")";
        var args = new java.util.ArrayList<Object>();
        if (!administrator) {
            args.add(account);
            if (warehouse) { args.add(account); }
        }
        Long total = db.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        var rows = db.queryForList("SELECT r.id,r.version,r.expected_return_date,r.request_status" + from
                + " ORDER BY r.id DESC LIMIT ? OFFSET ?", args.toArray());
        return Map.of("items", rows, "total", total, "page", page, "pageSize", size);
    }

    public Map<String, Object> outstanding(long id) {
        var row = snapshot(id);
        if (!java.util.List.of("WAITING_WAREHOUSE_CLAIM", "WAREHOUSE_PROCESSING", "WAITING_HANDOVER")
                        .contains(row.get("request_status"))) {
            throw new IllegalStateException("LOAN_TARGET_CLOSED");
        }
        return row;
    }

    public Map<String, Object> snapshot(long id) {
        var rows = db.queryForList("SELECT * FROM fams_requisition WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) { throw new IllegalStateException("LOAN_TARGET_NOT_FOUND"); }
        var row = rows.getFirst();
        if (!"BORROW".equals(row.get("request_type"))) {
            throw new IllegalStateException("LOAN_TARGET_CLOSED");
        }
        return row;
    }

    public void extend(long id, long version, LocalDate due, Instant time) {
        var row = outstanding(id);
        if (((Number) row.get("version")).longValue() != version) {
            throw new IllegalStateException("LOAN_TARGET_CHANGED");
        }
        db.update("UPDATE fams_requisition SET expected_return_date=?,updated_at=?,version=version+1"
                + " WHERE id=? AND version=?", due, Timestamp.from(time), id, version);
    }
}
