package com.fams.reminder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fams.job.DurableJobService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/** Tier ladder, recipient resolution and occurrence dedupe across every reminder stage. */
@SpringBootTest(classes = BusinessReminderRuntimeCase.TestApplication.class, properties = {
    "spring.profiles.active=fams-product", "spring.flyway.locations=classpath:db/migration/d6_04",
    "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
    "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra",
    "fams.attachments.cleanup-enabled=false"
})
@EnabledIfSystemProperty(named = "d6.24.reminder.runtime", matches = "true")
class BusinessReminderRuntimeCase {
    private static final long PARAMETER = 930100;
    private static final long ADMIN = 930100;
    private static final long WAREHOUSE_ACCOUNT = 930101;
    private static final long EXECUTOR_ACCOUNT = 930102;
    private static final long RECEIVER_EMPLOYEE = 930101;

    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("D6_24_TEST_DATASOURCE_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("D6_24_TEST_DATASOURCE_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("D6_24_TEST_DATASOURCE_PASSWORD"));
    }

    @Test
    void noticesFollowEachStageLadderAndDeduplicateByOccurrence() {
        seed();
        scan("2026-11-09");
        assertEquals(List.of("ADMIN_DECISION|UNRESOLVED_AFTER_1_WORKDAY|2026-11-09|ASSET_ADMIN|-"),
                notices("INBOUND", 930200));
        assertEquals(List.of("FIRST_EXECUTION|OVERDUE|2026-11-09|WAREHOUSE_OPERATOR|-"),
                notices("INBOUND", 930201));
        assertEquals(List.of(), notices("INBOUND", 930202));
        assertEquals(List.of("HANDOVER_RECEIPT|DUE_IN_24_HOURS|2026-11-08|EMPLOYEE|" + RECEIVER_EMPLOYEE,
                "HANDOVER_RECEIPT|DUE_TODAY|2026-11-09|EMPLOYEE|" + RECEIVER_EMPLOYEE),
                notices("TRANSFER_ITEM", 930500));
        assertEquals(List.of(
                "INVENTORY_COMPLETION|DUE_IN_1_DAY|2026-11-06|EMPLOYEE|" + WAREHOUSE_ACCOUNT,
                "INVENTORY_COMPLETION|DUE_IN_1_DAY|2026-11-06|EMPLOYEE|" + EXECUTOR_ACCOUNT,
                "INVENTORY_COMPLETION|DUE_IN_3_DAYS|2026-11-04|EMPLOYEE|" + WAREHOUSE_ACCOUNT,
                "INVENTORY_COMPLETION|DUE_IN_3_DAYS|2026-11-04|EMPLOYEE|" + EXECUTOR_ACCOUNT,
                "INVENTORY_COMPLETION|DUE_TODAY|2026-11-07|EMPLOYEE|" + WAREHOUSE_ACCOUNT,
                "INVENTORY_COMPLETION|DUE_TODAY|2026-11-07|EMPLOYEE|" + EXECUTOR_ACCOUNT,
                "INVENTORY_COMPLETION|OVERDUE|2026-11-08|EMPLOYEE|" + WAREHOUSE_ACCOUNT,
                "INVENTORY_COMPLETION|OVERDUE|2026-11-08|EMPLOYEE|" + EXECUTOR_ACCOUNT),
                notices("INVENTORY_TASK", 930400));
        assertEquals(List.of(), notices("INVENTORY_TASK", 930401));
        assertEquals(List.of("FACTORY_RETURN|DUE_IN_3_DAYS|2026-10-29|WAREHOUSE_OPERATOR|-",
                "FACTORY_RETURN|DUE_TODAY|2026-11-01|WAREHOUSE_OPERATOR|-",
                "FACTORY_RETURN|OVERDUE_EVERY_7_DAYS|2026-11-08|WAREHOUSE_OPERATOR|-"),
                notices("REPAIR_FACTORY_ROUND", 930600));
        assertEquals(List.of("LOSS_REVIEW|DUE_IN_1_DAY|2026-11-07|WAREHOUSE_OPERATOR|" + WAREHOUSE_ACCOUNT,
                "LOSS_REVIEW|OVERDUE|2026-11-09|WAREHOUSE_OPERATOR|" + WAREHOUSE_ACCOUNT),
                notices("LOST_REPORT", 930300));
        assertEquals(List.of("SCRAP_DISPOSITION|DUE_IN_3_DAYS|2026-11-03|WAREHOUSE_OPERATOR|" + WAREHOUSE_ACCOUNT,
                "SCRAP_DISPOSITION|DUE_TODAY|2026-11-06|WAREHOUSE_OPERATOR|" + WAREHOUSE_ACCOUNT,
                "SCRAP_DISPOSITION|OVERDUE|2026-11-07|WAREHOUSE_OPERATOR|" + WAREHOUSE_ACCOUNT),
                notices("SCRAP", 930301));
        assertEquals(20, count());

        // A repeated scan of the same minute-long cycle never notifies the same occurrence twice.
        scan("2026-11-09");
        assertEquals(20, count());
        assertEquals(1, weekly());

        scan("2026-11-13");
        assertEquals(List.of("WAREHOUSE_CLAIM|DUE_IN_1_DAY|2026-11-11|WAREHOUSE_OPERATOR|-",
                "WAREHOUSE_CLAIM|OVERDUE|2026-11-13|WAREHOUSE_OPERATOR|-"),
                notices("INBOUND", 930202));
        assertEquals(List.of("HANDOVER_RECEIPT|DUE_IN_24_HOURS|2026-11-08|EMPLOYEE|" + RECEIVER_EMPLOYEE,
                "HANDOVER_RECEIPT|DUE_TODAY|2026-11-09|EMPLOYEE|" + RECEIVER_EMPLOYEE,
                "HANDOVER_RECEIPT|OVERDUE|2026-11-10|EMPLOYEE|" + RECEIVER_EMPLOYEE),
                notices("TRANSFER_ITEM", 930500));
        // The weekly ladder stays on the same occurrence bucket however many scans ran in between.
        assertEquals(1, weekly());

        db.update("UPDATE fams_inventory_task SET due_at='2026-11-14 12:00:00' WHERE id=930401");
        scan("2026-11-16");
        assertEquals(List.of("INVENTORY_COMPLETION|DUE_IN_1_DAY|2026-11-13|ASSET_ADMIN|-",
                "INVENTORY_COMPLETION|DUE_IN_3_DAYS|2026-11-11|ASSET_ADMIN|-",
                "INVENTORY_COMPLETION|DUE_TODAY|2026-11-14|ASSET_ADMIN|-",
                "INVENTORY_COMPLETION|OVERDUE|2026-11-15|ASSET_ADMIN|-"),
                notices("INVENTORY_TASK", 930401));
        assertEquals(List.of("2026-11-08", "2026-11-15"), weeklyNoticeDates());
        verifyCustodianSmsFallback();

    }

    private void verifyCustodianSmsFallback() {
        db.update("INSERT INTO fams_account_role (account_id,role_code,granted_at)"
                + " VALUES (?,'WAREHOUSE_OPERATOR','2026-11-01 12:00:00')",
                WAREHOUSE_ACCOUNT);
        var clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC);
        var jobs = new DurableJobService(db, clock);
        var queue = new NotificationDeliveryQueue(db, jobs, clock);
        queue.enqueue("custodian-reminder");
        queue.enqueue("custodian-reminder");
        assertEquals(List.of("LOSS_REVIEW", "SCRAP_DISPOSITION"), db.queryForList("""
                SELECT r.stage FROM fams_notification_delivery d
                JOIN fams_business_reminder r ON r.id=d.reminder_id
                ORDER BY r.stage
                """, String.class));
        for (int index = 0; index < 2; index++) {
            var claim = jobs.claim(NotificationDeliveryQueue.KIND, "custodian", Duration.ofMinutes(1)).orElseThrow();
            assertEquals("custodian-reminder", queue.eligibleTemplate(claim));
            var stage = db.queryForObject("""
                    SELECT r.stage FROM fams_notification_delivery d
                    JOIN fams_business_reminder r ON r.id=d.reminder_id WHERE d.job_id=?
                    """, String.class, claim.id().toString());
            if ("LOSS_REVIEW".equals(stage)) {
                db.update("UPDATE fams_loss_report SET next_review_date='2026-11-12' WHERE id=930300");
            } else {
                db.update("UPDATE fams_scrap_disposition SET processor_account_id=? WHERE request_id=930301",
                        EXECUTOR_ACCOUNT);
            }
            assertNull(queue.eligibleTemplate(claim));
            jobs.fail(claim, "RECIPIENT_UNAVAILABLE_OR_READ", false);
        }
        assertTrue(jobs.claim(NotificationDeliveryQueue.KIND, "custodian", Duration.ofMinutes(1)).isEmpty());
    }

    private void scan(String date) {
        new BusinessReminderService(db, json,
                Clock.fixed(Instant.parse(date + "T00:00:00Z"), ZoneOffset.UTC)).scan();
    }

    private List<String> notices(String businessType, long businessId) {
        return db.queryForList("SELECT stage,reminder_type,notice_date,recipient_pool_type,recipient_account_id"
                + " FROM fams_business_reminder WHERE business_type=? AND business_id=?"
                + " ORDER BY stage,reminder_type,notice_date,recipient_account_id", businessType, businessId)
                .stream()
                .map(row -> row.get("stage") + "|" + row.get("reminder_type") + "|" + row.get("notice_date") + "|"
                        + row.get("recipient_pool_type") + "|"
                        + (row.get("recipient_account_id") == null ? "-" : row.get("recipient_account_id")))
                .toList();
    }

    private long count() {
        return db.queryForObject("SELECT COUNT(*) FROM fams_business_reminder", Long.class);
    }

    private long weekly() {
        return db.queryForObject("SELECT COUNT(*) FROM fams_business_reminder"
                + " WHERE reminder_type='OVERDUE_EVERY_7_DAYS'", Long.class);
    }

    private List<String> weeklyNoticeDates() {
        return db.queryForList("SELECT notice_date FROM fams_business_reminder"
                + " WHERE reminder_type='OVERDUE_EVERY_7_DAYS' ORDER BY notice_date", String.class);
    }

    private void seed() {
        db.update("INSERT INTO fams_organization (id,code,code_normalized,name,status,version,created_at,updated_at)"
                + " VALUES (?,?,?,'Reminder','ACTIVE',0,'2026-11-01 12:00:00','2026-11-01 12:00:00')",
                PARAMETER, "RMD" + PARAMETER, "RMD" + PARAMETER);
        for (long id : new long[] {ADMIN, WAREHOUSE_ACCOUNT, EXECUTOR_ACCOUNT}) {
            db.update("INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,organization_id,status,"
                    + "version,created_at,updated_at) VALUES (?,?,?,'Reminder',?,'ACTIVE',0,"
                    + "'2026-11-01 12:00:00','2026-11-01 12:00:00')", id, "RMD" + id, "RMD" + id, PARAMETER);
            db.update("INSERT INTO fams_system_account (id,employee_id,username,username_normalized,password_hash,"
                    + "status,initial_password_required,failed_attempts,auth_epoch,authorization_version,version,"
                    + "created_at,updated_at) VALUES (?,?,?,?,'unused','ENABLED',FALSE,0,0,0,0,"
                    + "'2026-11-01 12:00:00','2026-11-01 12:00:00')", id, id, "rmd" + id, "RMD" + id);
        }
        db.update("INSERT INTO fams_business_parameter_version (id,version,status,effective_at,actor_account_id,"
                + "actor_username,snapshot_json,created_at) VALUES (?,?,'ACTIVE','2026-11-01 12:00:00',?,'rmd"
                + PARAMETER + "',?,'2026-11-01 12:00:00')", PARAMETER, PARAMETER, ADMIN, snapshot());
        for (long id = 930300; id <= 930305; id++) {
            db.update("INSERT INTO fams_asset (id,asset_code,name,lifecycle_status,exception_status,card_stage,"
                    + "created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,"
                    + "created_at,updated_at,created_by,updated_by) VALUES (?,?,?,'IN_STOCK','NORMAL','ACTIVE',"
                    + "'2026-11-01',?,?,'rmd','2026-11-01 12:00:00','2026-11-01 12:00:00',?,?)",
                    id, "RMD" + id, "Reminder asset", PARAMETER, ADMIN, ADMIN, ADMIN);
        }
        inbound(930200, "PENDING_ADMIN_DECISION", "2026-11-10 12:00:00", null, null);
        inbound(930201, "WAREHOUSE_PROCESSING", null, null, "2026-11-08 12:00:00");
        inbound(930202, "WAITING_WAREHOUSE_CLAIM", null, "2026-11-12 12:00:00", null);
        db.update("INSERT INTO fams_transfer (id,creator_account_id,reason,request_status,created_at,updated_at)"
                + " VALUES (930500,?,'Reminder','PROCESSING','2026-11-08 12:00:00','2026-11-08 12:00:00')", ADMIN);
        db.update("INSERT INTO fams_transfer_item (id,request_id,asset_id,asset_version,source_snapshot_json,"
                + "target_snapshot_json,parameter_snapshot_json,receiver_employee_id,receipt_required,item_status,"
                + "delivered_at,receipt_deadline_at) VALUES (930500,?,?,0,?,?,?,?,TRUE,'DELIVERED',"
                + "'2026-11-08 12:00:00','2026-11-09 12:00:00')",
                930500, 930305, snapshot(), snapshot(), snapshot(), RECEIVER_EMPLOYEE);
        task(930400, "2026-11-07 12:00:00");
        task(930401, "2026-11-20 12:00:00");
        for (long item = 0; item < 2; item++) {
            db.update("INSERT INTO fams_inventory_item (task_id,asset_id,asset_version,snapshot_json,"
                    + "executor_account_id) VALUES (930400,?,?,?,?)",
                    930302 + item, 0, snapshot(), item == 0 ? WAREHOUSE_ACCOUNT : EXECUTOR_ACCOUNT);
        }
        db.update("INSERT INTO fams_repair_request (id,asset_id,creator_account_id,asset_version,"
                + "source_snapshot_json,timezone_id,fault,impact,occurred_at,repair_status,created_at,updated_at)"
                + " VALUES (930600,930304,?,0,?,'Asia/Shanghai','Reminder fault','MEDIUM',"
                + "'2026-11-01 12:00:00','WAREHOUSE_PROCESSING','2026-11-01 12:00:00','2026-11-01 12:00:00')",
                ADMIN, snapshot());
        db.update("INSERT INTO fams_repair_factory_scope (id,repair_id,scope_status,request_snapshot_json,"
                + "request_reason,requested_by,requested_at,decision_snapshot_json,decision_reason,decided_by,"
                + "decided_at) VALUES (930600,930600,'APPROVED',?,'Reminder',?, '2026-11-01 12:00:00',?,'Reminder',"
                + "?, '2026-11-01 12:00:00')", snapshot(), ADMIN, snapshot(), ADMIN);
        db.update("INSERT INTO fams_repair_factory_round (id,repair_id,asset_id,scope_id,round_number,round_status,"
                + "provider_name,service_contact,service_phone,expected_return_date,fault,original_identity_json,"
                + "created_by,created_at,dispatched_at,dispatched_by,dispatch_evidence)"
                + " VALUES (930600,930600,930304,930600,1,'DISPATCHED','Provider','Contact','13800000000',"
                + "'2026-11-01','Reminder fault',?,?, '2026-11-01 12:00:00','2026-11-01 13:00:00',?,"
                + "'Reminder handoff')", snapshot(), ADMIN, ADMIN);
        db.update("INSERT INTO fams_loss_report (id,asset_id,creator_account_id,asset_version,source_snapshot_json,"
                + "last_seen_at,last_location,circumstances,report_status,processor_account_id,next_review_date,"
                + "created_at,updated_at) VALUES (930300,930300,?,0,?,'2026-11-01 12:00:00','Reminder place',"
                + "'Reminder circumstances','SEARCHING',?,'2026-11-08','2026-11-01 12:00:00','2026-11-01 12:00:00')",
                ADMIN, snapshot(), WAREHOUSE_ACCOUNT);
        db.update("INSERT INTO fams_scrap_request (id,asset_id,creator_account_id,source_type,source_snapshot_json,"
                + "reason,observed_condition,proposed_disposition,request_status,asset_version,created_at,updated_at)"
                + " VALUES (930301,930301,?,'STANDALONE',?,'Reminder reason','Worn','Recycle','EXECUTED',0,"
                + "'2026-11-01 12:00:00','2026-11-01 12:00:00')", ADMIN, snapshot());
        db.update("INSERT INTO fams_scrap_disposition (request_id,processor_account_id,disposition_status,due_date,"
                + "custody_snapshot_json,created_at,updated_at) VALUES (930301,?,'PENDING_DISPOSITION','2026-11-06',"
                + "?, '2026-11-01 12:00:00','2026-11-01 12:00:00')", WAREHOUSE_ACCOUNT, snapshot());
    }

    private void inbound(long id, String status, String adminDue, String claimDue, String firstExecutionDue) {
        db.update("INSERT INTO fams_inbound_receipt (id,receipt_no,creator_account_id,creator_username_snapshot,"
                + "receipt_status,business_date,parameter_version_id,timezone_snapshot,"
                + "admin_decision_deadline_workdays,warehouse_claim_deadline_workdays,"
                + "warehouse_first_execution_deadline_workdays,submitted_at,admin_decision_due_at,"
                + "warehouse_claim_due_at,warehouse_first_execution_due_at,version,created_at,updated_at,"
                + "created_by,updated_by) VALUES (?,?,?,'rmd',?,'2026-11-05',?,'Asia/Shanghai',2,1,2,"
                + "'2026-11-06 12:00:00',?,?,?,0,'2026-11-05 12:00:00','2026-11-05 12:00:00',?,?)",
                id, "RMD" + id, ADMIN, status, PARAMETER, adminDue, claimDue, firstExecutionDue, ADMIN, ADMIN);
    }

    private void task(long id, String dueAt) {
        db.update("INSERT INTO fams_inventory_task (id,name,task_status,range_json,starts_at,due_at,timezone_id,"
                + "parameter_version_id,creator_account_id,created_at,updated_at) VALUES (?,?,'IN_PROGRESS','{}',"
                + "'2026-11-01 12:00:00',?,'Asia/Shanghai',?,?,'2026-11-01 12:00:00','2026-11-01 12:00:00')",
                id, "Reminder task " + id, dueAt, PARAMETER, ADMIN);
    }

    private static String snapshot() {
        return "{\"timezone\":\"Asia/Shanghai\",\"workWeek\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\","
                + "\"FRIDAY\"],\"holidays\":[],\"adjustedWorkdays\":[]}";
    }

    @SpringBootApplication(scanBasePackages = "com.fams")
    static class TestApplication { }
}
