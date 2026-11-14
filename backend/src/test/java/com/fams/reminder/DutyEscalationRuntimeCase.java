package com.fams.reminder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = BusinessReminderRuntimeCase.TestApplication.class, properties = {
    "spring.profiles.active=fams-product", "spring.flyway.locations=classpath:db/migration/d6_04",
    "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
    "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra",
    "fams.attachments.cleanup-enabled=false", "fams.asset-import.worker.enabled=false"
})
@EnabledIfSystemProperty(named = "d6.24.duty.escalation.runtime", matches = "true")
class DutyEscalationRuntimeCase {
    private static final long ADMIN = 940001;
    private static final long BACKUP = 940002;
    private static final long OTHER_ADMIN = 940003;
    private static final long WAREHOUSE = 940004;
    private static final long PARAMETER = 940010;
    private static final long DISABLED_THRESHOLD = 940011;
    private static final String DEADLINE = "2026-11-09 16:00:00";
    private static final String OVERDUE_AT = "2026-11-09 16:01:00";
    private static final String UNREAD = "DUTY_UNREAD_ESCALATION";
    private static final String GROUP = "OVERDUE_UNRESOLVED_WORKDAY";

    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("D6_24C2_TEST_DATASOURCE_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("D6_24C2_TEST_DATASOURCE_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("D6_24C2_TEST_DATASOURCE_PASSWORD"));
    }

    @Test
    void scansEveryStageAcrossPagesUsingFrozenThresholdsAndDeduplicatesRecipients() {
        seed();
        for (int offset = 0; offset <= 200; offset++) {
            inbound(941000 + offset, PARAMETER, "PENDING_ADMIN_DECISION", "ADMIN_DECISION");
            inbound(942000 + offset, PARAMETER, "WAITING_WAREHOUSE_CLAIM", "WAREHOUSE_CLAIM");
            inbound(943000 + offset, PARAMETER, "WAREHOUSE_PROCESSING", "FIRST_EXECUTION");
            transfer(944000 + offset);
            requisition(945000 + offset);
        }
        inbound(946000, DISABLED_THRESHOLD, "PENDING_ADMIN_DECISION", "ADMIN_DECISION");
        markRead(ADMIN, 941000);

        scan("2026-11-09T20:00:59Z");
        assertEquals(0L, count(UNREAD));
        scan("2026-11-09T20:01:00Z");
        assertEquals(401L, count(UNREAD));
        assertEquals(List.of(), recipients(941000, UNREAD));
        assertEquals(List.of(ADMIN), recipients(941200, UNREAD));
        assertEquals(List.of(WAREHOUSE), recipients(942200, UNREAD));
        assertEquals(List.of(), recipients(943200, UNREAD));
        assertEquals(0L, count(GROUP));

        db.update("""
                INSERT INTO fams_audit_event
                    (request_id,actor_account_id,actor_role,action,resource_type,resource_id,occurred_at,detail_json)
                VALUES (UUID(),?,'WAREHOUSE_OPERATOR','INBOUND_CONTROLLED_REASSIGNMENT','INBOUND',
                        943200,'2026-11-10 12:00:00','{}')
                """, WAREHOUSE);
        scan("2026-11-10T15:59:59Z");
        assertEquals(0L, count(GROUP));
        scan("2026-11-10T16:00:00Z");
        assertEquals(List.of(WAREHOUSE), recipients(943200, UNREAD));
        assertEquals(List.of(), recipients(944200, UNREAD));
        assertEquals(List.of(), recipients(945200, UNREAD));
        scan("2026-11-10T16:01:00Z");
        assertEquals(List.of(ADMIN), recipients(944200, UNREAD));
        assertEquals(List.of(ADMIN), recipients(945200, UNREAD));
        assertEquals(List.of(), recipients(946000, UNREAD));
        assertEquals(1405L, count(UNREAD));

        // A primary may read independently. A disabled primary falls through to the backup,
        // then to the remaining active member of the pool.
        db.update("UPDATE fams_employee SET status='DISABLED' WHERE id=?", ADMIN);
        scan("2026-11-10T16:01:00Z");
        assertEquals(List.of(BACKUP), recipients(941000, UNREAD));
        db.update("UPDATE fams_employee SET status='DISABLED' WHERE id=?", BACKUP);
        scan("2026-11-10T16:01:00Z");
        assertEquals(List.of(BACKUP, OTHER_ADMIN), recipients(941000, UNREAD));
        db.update("UPDATE fams_employee SET status='ACTIVE' WHERE id IN (?,?)", ADMIN, BACKUP);

        assertEquals(List.of(ADMIN, BACKUP, OTHER_ADMIN), recipients(941000, GROUP));
        assertEquals(List.of(), recipients(943200, GROUP));
        long unread = count(UNREAD);
        long group = count(GROUP);
        scan("2026-11-10T16:01:00Z");
        assertEquals(unread, count(UNREAD));
        assertEquals(group, count(GROUP));
    }

    private void scan(String time) {
        new DutyEscalationService(db, json, Clock.fixed(Instant.parse(time), ZoneOffset.UTC)).escalate();
    }

    private long count(String type) {
        return db.queryForObject("SELECT COUNT(*) FROM fams_business_reminder WHERE reminder_type=?",
                Long.class, type);
    }

    private List<Long> recipients(long id, String type) {
        return db.queryForList("""
                SELECT DISTINCT recipient_account_id FROM fams_business_reminder
                WHERE business_id=? AND reminder_type=? ORDER BY recipient_account_id
                """, Long.class, id, type);
    }

    private void markRead(long reader, long id) {
        db.update("""
                INSERT INTO fams_message_receipt (account_id,message_kind,message_id,read_at)
                SELECT ?,'REMINDER',id,created_at FROM fams_business_reminder WHERE business_id=?
                """, reader, id);
    }

    private void seed() {
        db.update("""
                INSERT INTO fams_organization (id,code,code_normalized,name,status,created_at,updated_at)
                VALUES (940000,'DUTY','DUTY','Duty','ACTIVE',NOW(3),NOW(3))
                """);
        for (long id : List.of(ADMIN, BACKUP, OTHER_ADMIN, WAREHOUSE)) {
            db.update("""
                    INSERT INTO fams_employee
                        (id,employee_no,employee_no_normalized,name,organization_id,status,created_at,updated_at)
                    VALUES (?,?,?,'Duty',940000,'ACTIVE',NOW(3),NOW(3))
                    """, id, "D" + id, "D" + id);
            db.update("""
                    INSERT INTO fams_system_account
                        (id,employee_id,username,username_normalized,password_hash,status,
                         initial_password_required,created_at,updated_at)
                    VALUES (?,?,?,?,'unused','ENABLED',FALSE,NOW(3),NOW(3))
                    """, id, id, "d" + id, "D" + id);
            db.update("INSERT INTO fams_account_role (account_id,role_code,granted_at) VALUES (?,?,NOW(3))",
                    id, id == WAREHOUSE ? "WAREHOUSE_OPERATOR" : "ASSET_ADMIN");
        }
        db.update("""
                INSERT INTO fams_business_parameter_version
                    (id,version,status,effective_at,actor_account_id,actor_username,snapshot_json,created_at)
                VALUES (?,?,'SUPERSEDED','2026-11-01',?,'duty',?,'2026-11-01'),
                       (?,?,'ACTIVE','2026-11-02',?,'duty',?,'2026-11-02')
                """, PARAMETER, PARAMETER, ADMIN, snapshot(4, 1, 24), DISABLED_THRESHOLD, DISABLED_THRESHOLD,
                ADMIN, snapshot(0, 0, 0));
        db.update("""
                INSERT INTO fams_duty_assignment
                    (role_code,sequence_no,primary_account_id,backup_account_id,duty_status,
                     effective_at,created_by,created_at)
                VALUES ('ASSET_ADMIN',1,?,?,'ACTIVE',NOW(3),?,NOW(3)),
                       ('WAREHOUSE_OPERATOR',1,?,NULL,'ACTIVE',NOW(3),?,NOW(3))
                """, ADMIN, BACKUP, ADMIN, WAREHOUSE, ADMIN);
        db.update("""
                INSERT INTO fams_asset_category (id,code,code_normalized,name,status,created_at,updated_at)
                VALUES (940000,'DUTY','DUTY','Duty','ACTIVE',NOW(3),NOW(3))
                """);
        db.update("""
                INSERT INTO fams_location (id,code,code_normalized,name,location_type,status,created_at,updated_at)
                VALUES (940000,'DUTY','DUTY','Duty','ROOM','ACTIVE',NOW(3),NOW(3))
                """);
        db.update("""
                INSERT INTO fams_audit_event
                    (id,request_id,actor_account_id,actor_role,action,resource_type,resource_id,occurred_at,detail_json)
                VALUES (940000,UUID(),?,'WAREHOUSE_OPERATOR','TEST_ALLOCATION','REQUISITION',940000,NOW(3),'{}')
                """, WAREHOUSE);
        db.update("""
                INSERT INTO fams_transfer (id,creator_account_id,reason,request_status,created_at,updated_at)
                VALUES (940000,?,'Duty','PROCESSING',NOW(3),NOW(3))
                """, ADMIN);
        db.update("""
                INSERT INTO fams_requisition
                    (id,request_no,creator_account_id,creator_username_snapshot,request_status,parameter_version_id,
                     parameter_snapshot_json,created_at,updated_at)
                VALUES (940000,'DUTY',?,'duty','WAITING_HANDOVER',?,?,NOW(3),NOW(3))
                """, ADMIN, PARAMETER, snapshot(4, 1, 24));
        db.update("""
                INSERT INTO fams_requisition_ownership (id,request_id,processor_account_id,acquired_at)
                VALUES (940000,940000,?,NOW(3))
                """, WAREHOUSE);
        db.update("""
                INSERT INTO fams_requisition_demand
                    (id,request_id,line_no,category_id,category_version_snapshot,category_name_snapshot,specification,
                     purpose,expected_date,custody_type,target_organization_id,organization_version_snapshot,
                     target_employee_id,employee_version_snapshot,target_location_id,location_version_snapshot,
                     target_snapshot_json,requested_quantity,pending_allocation_quantity,pending_handover_quantity)
                VALUES (940000,940000,1,940000,0,'Duty','Duty','Duty','2026-11-09','PERSONAL',940000,0,?,0,
                        940000,0,'{}',201,0,201)
                """, ADMIN);
    }

    private String snapshot(int hours, int days, int receiptHours) {
        return json.writeValueAsString(Map.of(
                "timezone", "Asia/Shanghai",
                "workWeek", List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"),
                "notificationEscalationThresholds", Map.of(
                        "administratorUnreadHours", hours,
                        "onDutyWarehouseOperatorUnreadHours", hours,
                        "firstExecutionOverdueNaturalDays", days,
                        "receiptDueUnreadHours", receiptHours)));
    }

    private void inbound(long id, long parameter, String status, String stage) {
        db.update("""
                INSERT INTO fams_inbound_receipt
                    (id,receipt_no,creator_account_id,creator_username_snapshot,receipt_status,business_date,
                     parameter_version_id,timezone_snapshot,admin_decision_deadline_workdays,
                     warehouse_claim_deadline_workdays,warehouse_first_execution_deadline_workdays,
                     submitted_at,admin_decision_due_at,warehouse_claim_due_at,warehouse_first_execution_due_at,
                     created_at,updated_at,created_by,updated_by)
                VALUES (?,?,?,'duty',?,'2026-11-05',?,'Asia/Shanghai',2,1,2,'2026-11-06',
                        ?,?,?,NOW(3),NOW(3),?,?)
                """, id, "D" + id, ADMIN, status, parameter, DEADLINE, DEADLINE, DEADLINE, ADMIN, ADMIN);
        notice(id, "INBOUND", stage, "ADMIN_DECISION".equals(stage) ? "ASSET_ADMIN" : "WAREHOUSE_OPERATOR", null);
    }

    private void transfer(long id) {
        asset(id);
        db.update("""
                INSERT INTO fams_transfer_item
                    (id,request_id,asset_id,asset_version,source_snapshot_json,target_snapshot_json,
                     parameter_snapshot_json,receiver_employee_id,receipt_required,item_status,
                     delivered_at,receipt_deadline_at)
                VALUES (?,940000,?,0,'{}','{}',?, ?,TRUE,'DELIVERED','2026-11-06',?)
                """, id, id, snapshot(4, 1, 24), OTHER_ADMIN, DEADLINE);
        notice(id, "TRANSFER_ITEM", "HANDOVER_RECEIPT", "EMPLOYEE", OTHER_ADMIN);
    }

    private void requisition(long id) {
        asset(id);
        db.update("""
                INSERT INTO fams_asset_business_lock
                    (id,asset_id,business_type,business_id,business_item_id,acquired_at)
                VALUES (?,?,'REQUISITION',940000,?,'2026-11-06')
                """, id, id, id);
        db.update("""
                INSERT INTO fams_requisition_allocation
                    (id,request_id,demand_id,ownership_id,asset_id,asset_version_snapshot,asset_snapshot_json,
                     business_lock_id,allocation_status,allocated_at,delivered_at,audit_event_id)
                VALUES (?,940000,940000,940000,?,0,'{}',?,'DELIVERED_WAITING_RECEIPT',
                        '2026-11-06','2026-11-06',940000)
                """, id, id, id);
        db.update("""
                INSERT INTO fams_requisition_handover
                    (allocation_id,delivered_by_account_id,delivered_at,receipt_deadline_at,destination_text,
                     temporary_employee_id,delivery_evidence,target_snapshot_json,formal_custody_snapshot_json)
                VALUES (?,?,'2026-11-06',?,'Duty',?,'Duty','{}','{}')
                """, id, WAREHOUSE, DEADLINE, OTHER_ADMIN);
        notice(id, "REQUISITION_ALLOCATION", "HANDOVER_RECEIPT", "EMPLOYEE", OTHER_ADMIN);
    }

    private void asset(long id) {
        db.update("""
                INSERT INTO fams_asset
                    (id,asset_code,name,lifecycle_status,exception_status,card_stage,created_business_date,
                     parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,
                     created_by,updated_by)
                VALUES (?,?,'Duty','IN_STOCK','NORMAL','ACTIVE','2026-11-05',?,?,'duty',NOW(3),NOW(3),?,?)
                """, id, "D" + id, PARAMETER, ADMIN, ADMIN, ADMIN);
    }

    private void notice(long id, String type, String stage, String pool, Long recipient) {
        db.update("""
                INSERT INTO fams_business_reminder
                    (business_type,business_id,stage,deadline_at,notice_date,reminder_business_date,reminder_type,
                     recipient_pool_type,recipient_account_id,created_at)
                VALUES (?,?,?,?,'2026-11-10','2026-11-10','OVERDUE',?,?,?)
                """, type, id, stage, DEADLINE, pool, recipient, OVERDUE_AT);
    }
}
