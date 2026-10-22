package com.fams.loan;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/** Loan owns open borrowing identity and dates; a repair never silently renews or closes them. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class LoanRepairPort {
    public record Context(
            long id,
            long version,
            long borrowerEmployeeId,
            long organizationId,
            LocalDate expectedReturnDate) {}

    public static final class Failure extends RuntimeException {
        public Failure(String code) {
            super(code);
        }
    }

    private final JdbcTemplate db;

    public LoanRepairPort(JdbcTemplate db) {
        this.db = db;
    }

    public Context open(long assetId) {
        var rows =
                db.queryForList(
                        "SELECT"
                            + " id,version,borrower_employee_id,using_organization_id,expected_return_date"
                            + " FROM fams_loan WHERE asset_id=? AND relation_status='OPEN' FOR"
                            + " UPDATE",
                        assetId);
        if (rows.isEmpty()) {
            return null;
        }
        if (rows.size() != 1) {
            throw new Failure("REPAIR_LOAN_CHANGED");
        }
        var row = rows.getFirst();
        Object value = row.get("expected_return_date");
        LocalDate due =
                value instanceof java.sql.Date date ? date.toLocalDate() : (LocalDate) value;
        return new Context(
                ((Number) row.get("id")).longValue(),
                ((Number) row.get("version")).longValue(),
                ((Number) row.get("borrower_employee_id")).longValue(),
                ((Number) row.get("using_organization_id")).longValue(),
                due);
    }

    public void requireUnchanged(long assetId, Context expected, LocalDate businessDate) {
        var current = open(assetId);
        if (!java.util.Objects.equals(current, expected)) {
            throw new Failure("REPAIR_LOAN_CHANGED");
        }
        if (current != null && current.expectedReturnDate().isBefore(businessDate)) {
            throw new Failure("REPAIR_BORROWING_EXPIRED_INBOUND_REQUIRED");
        }
    }
}
