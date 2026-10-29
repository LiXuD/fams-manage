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
}
