package com.fams.loan;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Immutable daily notices and a current authoritative todo; never changes asset or loan lifecycle. */
@Service
@Profile("fams-product")
public class LoanReminderService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;

    LoanReminderService(JdbcTemplate db, ObjectMapper json, Clock clock) {
        this.db = db;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void refresh(long id) {
        var rows = db.queryForList("SELECT * FROM fams_loan WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) { return; }
        var loan = rows.getFirst();
        var asset = db.queryForMap("SELECT lifecycle_status,exception_status FROM fams_asset WHERE id=? FOR UPDATE", loan.get("asset_id"));
        Timestamp now = Timestamp.from(clock.instant());
        String type = null;
        LocalDate today = clock.instant().atZone(ZoneId.of(json.readTree(loan.get("parameter_snapshot_json").toString())
                .path("timezone").asText())).toLocalDate();
        LocalDate due = LocalDate.parse(loan.get("expected_return_date").toString());
        if ("OPEN".equals(loan.get("relation_status")) && !"SCRAPPED".equals(asset.get("lifecycle_status"))) {
            if ("LOST".equals(asset.get("exception_status"))) { type = "LOST_LIABILITY"; }
            else {
                long remaining = ChronoUnit.DAYS.between(today, due);
                if (remaining < 0) { type = "OVERDUE"; }
                else if (remaining == 0) { type = "DUE_TODAY"; }
                else if (remaining == 1) { type = "DUE_IN_1_DAY"; }
                else if (remaining == 3) { type = "DUE_IN_3_DAYS"; }
            }
        }
        // Close obsolete current reminders while retaining immutable historical notices and their dates.
        db.update("UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE business_type='LOAN' AND business_id=?"
                + " AND closed_at IS NULL AND (? IS NULL OR todo_type<>?)", now, id, type, type);
        if (type == null) { return; }
        db.update("INSERT INTO fams_loan_reminder (loan_id,expected_return_date,reminder_business_date,reminder_type,overdue_from,created_at)"
                + " VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id", id, due, today, type, due.isBefore(today) ? due.plusDays(1) : null, now);
        var accounts = db.queryForList("SELECT id FROM fams_system_account WHERE employee_id=? AND status='ENABLED'", Long.class,
                loan.get("borrower_employee_id"));
        boolean disabled = "DISABLED".equals(db.queryForObject("SELECT status FROM fams_employee WHERE id=?", String.class,
                loan.get("borrower_employee_id")));
        Long assignee = disabled || accounts.isEmpty() || "LOST_LIABILITY".equals(type) ? null : accounts.getFirst();
        db.update("INSERT INTO fams_todo (pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,opened_at)"
                + " VALUES (?,?,'LOAN',?,?,'OPEN',?) ON DUPLICATE KEY UPDATE pool_type=VALUES(pool_type),"
                + "assignee_account_id=VALUES(assignee_account_id)",
                assignee == null ? "ASSET_ADMIN" : "EMPLOYEE", assignee, id, type, now);
    }
}
