package com.fams.loan;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Initial borrowing is a sourced opening fact, never a fabricated requisition or approval. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class LoanInitialPort implements com.fams.business.InitialBorrowingPort {
    private final JdbcTemplate db;

    LoanInitialPort(JdbcTemplate db) {
        this.db = db;
    }

    public void open(long asset, long employee, long organization, UUID batch, int row,
            LocalDate borrowed, LocalDate due, LocalDate businessDate, long parameter, Instant time) {
        if (borrowed == null || due == null || borrowed.isAfter(businessDate) || !due.isAfter(borrowed)) {
            throw new IllegalStateException("ASSET_IMPORT_BORROW_DATE_INVALID");
        }
        String snapshot = db.queryForObject(
                "SELECT snapshot_json FROM fams_business_parameter_version WHERE id=?", String.class, parameter);
        db.update("INSERT INTO fams_loan (asset_id,borrower_employee_id,using_organization_id,"
                + "source_type,initial_batch_id,initial_sheet_row,borrowed_date,original_return_date,"
                + "expected_return_date,parameter_snapshot_json,created_at,updated_at)"
                + " VALUES (?,?,?,'INITIAL_IMPORT',?,?,?,?,?,?,?,?)",
                asset, employee, organization, batch.toString(), row, borrowed, due, due,
                snapshot, Timestamp.from(time), Timestamp.from(time));
    }

    public void cancel(long asset, UUID batch, Instant time) {
        assertReversible(asset, batch);
        db.update("UPDATE fams_loan SET relation_status='VOID',closed_at=?,updated_at=?,version=version+1"
                + " WHERE asset_id=?", Timestamp.from(time), Timestamp.from(time), asset);
    }

    public void assertReversible(long asset, UUID batch) {
        var rows = db.queryForList("SELECT * FROM fams_loan WHERE asset_id=? FOR UPDATE", asset);
        if (rows.isEmpty()) {
            return;
        }
        var loan = rows.getFirst();
        if (rows.size() != 1 || !"INITIAL_IMPORT".equals(loan.get("source_type"))
                || !batch.toString().equals(loan.get("initial_batch_id"))
                || !"OPEN".equals(loan.get("relation_status"))
                || ((Number) loan.get("version")).longValue() != 0) {
            throw new IllegalStateException("ASSET_IMPORT_ROLLBACK_DOWNSTREAM");
        }
    }
}
