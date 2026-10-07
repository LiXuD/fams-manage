package com.fams.loan;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("fams-product")
class LoanReminderScheduler {
    private final JdbcTemplate db;
    private final LoanReminderService reminders;

    LoanReminderScheduler(JdbcTemplate db, LoanReminderService reminders) {
        this.db = db;
        this.reminders = reminders;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    void refresh() {
        long after = 0;
        while (true) {
            var ids = db.queryForList("SELECT id FROM fams_loan WHERE id>? ORDER BY id LIMIT 200", Long.class, after);
            if (ids.isEmpty()) { return; }
            for (long id : ids) { reminders.refresh(id); }
            after = ids.getLast();
        }
    }
}
