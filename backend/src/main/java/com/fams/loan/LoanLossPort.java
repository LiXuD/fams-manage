package com.fams.loan;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Loan owns switching current reminders; loss never closes or renews the original relationship. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class LoanLossPort {
    private final JdbcTemplate db;
    private final LoanReminderService reminders;

    public LoanLossPort(JdbcTemplate db, LoanReminderService reminders) {
        this.db = db;
        this.reminders = reminders;
    }

    public void refreshLiability(long asset) {
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

    public void requireRecovery(long asset, String lifecycle, Long borrower, Long organization) {
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
