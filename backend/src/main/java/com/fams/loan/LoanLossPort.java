package com.fams.loan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Loan owns current reminders and final termination of the original lost borrowing. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class LoanLossPort {
    private static final Logger LOG = LoggerFactory.getLogger(LoanLossPort.class);

    private final JdbcTemplate db;
    private final LoanReminderService reminders;

    public LoanLossPort(JdbcTemplate db, LoanReminderService reminders) {
        this.db = db;
        this.reminders = reminders;
    }

    public void refreshLiability(long asset) {
        LOG.debug("Refreshing loan liability reminders for open borrowings of asset={}", asset);
        for (long id :
                db.queryForList(
                        "SELECT id FROM fams_loan WHERE asset_id=?"
                                + " AND relation_status='OPEN' FOR UPDATE",
                        Long.class,
                        asset)) {
            reminders.refresh(id);
        }
    }

    public static final class Failure extends RuntimeException {
        public Failure(String code) {
            super(code);
        }
    }

    public record OpenBorrowing(long id, long version) {}

    public OpenBorrowing requireOpenBorrowing(long asset, long borrower, long organization) {
        return openBorrowing(asset, borrower, organization)
                .orElseThrow(() -> new Failure("LOSS_LOAN_IDENTITY_CHANGED"));
    }

    public java.util.Optional<OpenBorrowing> openBorrowing(long asset, long borrower, long organization) {
        LOG.debug("Locking open fams_loan FOR UPDATE asset={} borrower={} organization={}",
                asset, borrower, organization);
        var rows = db.queryForList("SELECT id,version FROM fams_loan WHERE asset_id=? AND borrower_employee_id=?"
                + " AND using_organization_id=? AND relation_status='OPEN' FOR UPDATE", asset, borrower, organization);
        if (rows.size() != 1) {
            return java.util.Optional.empty();
        }
        var loan = rows.getFirst();
        return java.util.Optional.of(new OpenBorrowing(((Number) loan.get("id")).longValue(),
                ((Number) loan.get("version")).longValue()));
    }

    public boolean hasOpenBorrowing(long asset) {
        LOG.debug("Checking locked open fams_loan rows for asset={}", asset);
        return !db.queryForList("SELECT id FROM fams_loan WHERE asset_id=? AND relation_status='OPEN' FOR UPDATE",
                asset).isEmpty();
    }

    public void terminateLost(long asset, long borrower, long organization, long loanId, long version,
            java.time.Instant time) {
        if (db.update(
                "UPDATE fams_loan SET relation_status='LOST_TERMINATED',closed_at=?,updated_at=?,version=version+1"
                        + " WHERE id=? AND asset_id=? AND borrower_employee_id=? AND using_organization_id=?"
                        + " AND relation_status='OPEN' AND version=?",
                time, time, loanId, asset, borrower, organization, version) != 1) {
            throw new Failure("LOSS_LOAN_IDENTITY_CHANGED");
        }
        db.update("UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE business_type='LOAN'"
                + " AND business_id=? AND closed_at IS NULL", time, loanId);
        LOG.debug("Terminated fams_loan id={} as LOST_TERMINATED and closed LOAN todos", loanId);
    }

    public void requireRecovery(long asset, String lifecycle, Long borrower, Long organization) {
        LOG.debug("Locking open fams_loan for recovery check asset={} lifecycle={}", asset, lifecycle);
        var rows =
                db.queryForList(
                        "SELECT borrower_employee_id,using_organization_id FROM fams_loan WHERE"
                                + " asset_id=? AND relation_status='OPEN' FOR UPDATE",
                        asset);
        if ("BORROWED".equals(lifecycle)
                ? rows.size() != 1
                        || !java.util.Objects.equals(
                                rows.getFirst().get("borrower_employee_id"), borrower)
                        || !java.util.Objects.equals(
                                rows.getFirst().get("using_organization_id"), organization)
                : !rows.isEmpty()) {
            throw new Failure("LOSS_LOAN_IDENTITY_CHANGED");
        }
    }
}
