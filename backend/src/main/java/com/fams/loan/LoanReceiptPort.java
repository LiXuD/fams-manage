package com.fams.loan;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

/** Approval, asset custody and this relationship share the caller's receipt transaction. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class LoanReceiptPort {
    private final JdbcTemplate db;

    LoanReceiptPort(JdbcTemplate db) {
        this.db = db;
    }

    public void open(
            long asset, long employee, long organization, long request, long allocation,
            LocalDate borrowedDate, LocalDate dueDate, String parameters, Instant time) {
        if (dueDate == null || !dueDate.isAfter(borrowedDate)) {
            throw new IllegalArgumentException("LOAN_RETURN_DATE_INVALID");
        }
        // Read the approved business again inside the transaction rather than trust client dates.
        var source = db.queryForMap(
                "SELECT r.request_type,r.expected_return_date,a.asset_id,a.request_id"
                        + " FROM fams_requisition_allocation a JOIN fams_requisition r ON r.id=a.request_id"
                        + " WHERE a.id=? FOR UPDATE", allocation);
        if (!"BORROW".equals(source.get("request_type"))
                || ((Number) source.get("asset_id")).longValue() != asset
                || ((Number) source.get("request_id")).longValue() != request
                || !dueDate.toString().equals(source.get("expected_return_date").toString())) {
            throw new IllegalStateException("LOAN_RECEIPT_SOURCE_CHANGED");
        }
        db.update(
                "INSERT INTO fams_loan (asset_id,borrower_employee_id,using_organization_id,"
                        + "source_type,source_request_id,source_allocation_id,borrowed_date,"
                        + "original_return_date,expected_return_date,parameter_snapshot_json,created_at,updated_at)"
                        + " VALUES (?,?,?,'RECEIPT',?,?,?,?,?,?,?,?)",
                asset, employee, organization, request, allocation, borrowedDate,
                dueDate, dueDate, parameters, Timestamp.from(time), Timestamp.from(time));
    }
}
