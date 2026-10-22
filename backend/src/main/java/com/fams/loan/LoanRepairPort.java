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

    /** A separately approved extension may advance dates/version without replacing the borrower. */
    public Context requireIdentity(long assetId, Context frozen, Long expectedVersion) {
        var current = open(assetId);
        if (frozen == null) {
            if (current != null || expectedVersion != null) {
                throw new Failure("REPAIR_LOAN_CHANGED");
            }
        } else if (current == null
                || current.id() != frozen.id()
                || current.borrowerEmployeeId() != frozen.borrowerEmployeeId()
                || current.organizationId() != frozen.organizationId()
                || !java.util.Objects.equals(current.version(), expectedVersion)) {
            throw new Failure("REPAIR_LOAN_CHANGED");
        }
        return current;
    }

    public void closeForRepairInbound(
            long assetId,
            Context frozen,
            Long expectedVersion,
            long repair,
            long actor,
            java.time.Instant actualReturn,
            java.time.Instant recordedAt) {
        var current = requireIdentity(assetId, frozen, expectedVersion);
        if (current == null) {
            return;
        }
        if (db.update(
                        "UPDATE fams_loan SET"
                            + " relation_status='RETURNED',closed_at=?,updated_at=?,version=version+1"
                            + " WHERE id=? AND relation_status='OPEN' AND version=?",
                        actualReturn,
                        recordedAt,
                        current.id(),
                        current.version())
                != 1) {
            throw new Failure("REPAIR_LOAN_CHANGED");
        }
        db.update(
                "INSERT INTO"
                    + " fams_loan_repair_return(repair_id,loan_id,asset_id,before_loan_version,after_loan_version,returned_at,actor_account_id)"
                    + " VALUES (?,?,?,?,?,?,?)",
                repair,
                current.id(),
                assetId,
                current.version(),
                current.version() + 1,
                actualReturn,
                actor);
    }
}
