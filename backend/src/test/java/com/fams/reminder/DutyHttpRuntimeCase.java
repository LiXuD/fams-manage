package com.fams.reminder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fams.auth.MiniNotificationSmsPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Duty configuration over real HTTP sessions: only a system administrator rotates the notification contact, the
 * chain stays immutable, an invalid contact stays visible as invalid, and nothing here grants approval rights.
 */
@SpringBootTest(classes = DutyHttpRuntimeCase.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.profiles.active=fams-product", "spring.flyway.locations=classpath:db/migration/d6_04",
    "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
    "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra",
    "fams.attachments.cleanup-enabled=false"
})
@EnabledIfSystemProperty(named = "d6.24.duty.runtime", matches = "true")
class DutyHttpRuntimeCase {
    private static final String PASSWORD = "owned-duty-24c-password";
    private static final long SYSTEM_ADMIN = 931001;
    private static final long ADMIN_ONE = 931002;
    private static final long ADMIN_TWO = 931003;
    private static final long WAREHOUSE = 931004;
    private static final long EMPLOYEE = 931005;
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired ConfigurationAnomalyService anomalies;
    @Autowired ReassignmentReminderService reassignments;
    @Autowired NotificationDeliveryQueue deliveries;
    @Autowired com.fams.job.DurableJobService jobs;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("D6_24C_TEST_DATASOURCE_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("D6_24C_TEST_DATASOURCE_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("D6_24C_TEST_DATASOURCE_PASSWORD"));
    }

    @Test
    void dutyRotationIsAdminOnlyVersionGuardedAndNotificationOnly() throws Exception {
        seed();
        var system = login("duty1");
        var administrator = login("duty2");
        var warehouse = login("duty4");
        var employee = login("duty5");

        // First assignment for a pool starts at version zero and stores the chosen contact pair.
        var created = ok(put(system, "ASSET_ADMIN", 0, ADMIN_ONE, ADMIN_TWO));
        assertEquals("1", created.path("version").asText());
        assertEquals(Long.toString(ADMIN_ONE), created.path("primary").path("id").asText());
        assertTrue(created.path("primary").path("active").asBoolean());
        assertEquals(Long.toString(ADMIN_TWO), created.path("backup").path("id").asText());

        // Only a system administrator rotates the duty contact, and every candidate must hold the pool role.
        assertEquals(403, put(administrator, "ASSET_ADMIN", 1, ADMIN_TWO, null).statusCode());
        assertEquals(403, put(warehouse, "WAREHOUSE_OPERATOR", 0, WAREHOUSE, null).statusCode());
        assertEquals(403, code(put(employee, "ASSET_ADMIN", 1, EMPLOYEE, null)));
        assertEquals(409, code(put(system, "ASSET_ADMIN", 1, EMPLOYEE, null)));
        assertEquals(400, code(put(system, "ASSET_ADMIN", 1, ADMIN_ONE, ADMIN_ONE)));

        // A lost response replayed on the same key returns the original receipt without a second chain row.
        var key = UUID.randomUUID().toString();
        assertEquals("2", ok(put(system, "ASSET_ADMIN", 1, ADMIN_TWO, null, key)).path("version").asText());
        assertEquals("2", ok(put(system, "ASSET_ADMIN", 1, ADMIN_TWO, null, key)).path("version").asText());
        assertEquals(2, rows("ASSET_ADMIN"));
        // The same key with a different payload is a conflict rather than a silent rotation.
        assertEquals(409, code(put(system, "ASSET_ADMIN", 1, ADMIN_ONE, null, key)));
        // A stale version cannot overwrite the chain either.
        assertEquals(409, code(put(system, "ASSET_ADMIN", 1, ADMIN_ONE, null)));

        // Superseded rows stay for escalation attribution while only one row remains active.
        assertEquals("3", ok(put(system, "ASSET_ADMIN", 2, ADMIN_ONE, ADMIN_TWO)).path("version").asText());
        assertEquals(3, rows("ASSET_ADMIN"));
        assertEquals(1, active("ASSET_ADMIN"));
        assertEquals("1", ok(put(system, "WAREHOUSE_OPERATOR", 0, WAREHOUSE, null)).path("version").asText());

        // Reading is role-scoped: an administrator sees both pools, a warehouse operator sees only their own.
        assertEquals(2, ok(get(system)).path("items").size());
        assertEquals(2, ok(get(administrator)).path("items").size());
        var own = ok(get(warehouse));
        assertEquals(1, own.path("items").size());
        assertEquals("WAREHOUSE_OPERATOR", own.path("items").get(0).path("roleCode").asText());
        assertEquals(403, code(get(employee)));

        // Losing the duty contact's employee record keeps the historical chain row visible but marks it ineffective.
        db.update("UPDATE fams_employee SET status='DISABLED' WHERE id=?", ADMIN_ONE);
        var invalidated = ok(get(system));
        assertEquals("ASSET_ADMIN", invalidated.path("items").get(0).path("roleCode").asText());
        assertFalse(invalidated.path("items").get(0).path("primary").path("active").asBoolean());
        assertTrue(invalidated.path("items").get(0).path("backup").path("active").asBoolean());
        // A duty person never gains approval rights, so no business fact is reachable through this endpoint.
        assertEquals(0, count("fams_todo"));
        assertEquals(0, count("fams_business_reminder"));
        // Three administrator rotations plus one warehouse rotation, each with exactly one audit row.
        assertEquals(4, db.queryForObject("SELECT COUNT(*) FROM fams_audit_event"
                + " WHERE action='DUTY_ASSIGNMENT_REPLACED'", Long.class));

        db.update("INSERT INTO fams_business_parameter_version (id,version,status,effective_at,actor_account_id,"
                + "actor_username,snapshot_json,created_at) VALUES (931000,1,'ACTIVE',NOW(3),931001,'duty1','{}',NOW(3))");
        db.update("""
                INSERT INTO fams_inbound_receipt (id,receipt_no,creator_account_id,creator_username_snapshot,
                    receipt_status,business_date,parameter_version_id,timezone_snapshot,
                    admin_decision_deadline_workdays,warehouse_claim_deadline_workdays,
                    warehouse_first_execution_deadline_workdays,submitted_at,admin_decision_due_at,
                    version,created_at,updated_at,created_by,updated_by)
                VALUES (931000,'C3-INBOUND',931004,'duty4','PENDING_ADMIN_DECISION','2026-11-13',931000,
                    'Asia/Shanghai',2,1,2,'2026-11-13 01:00:00','2026-11-17 16:00:00',3,NOW(3),NOW(3),931004,931004)
                """);
        db.update("INSERT INTO fams_todo (id,pool_type,business_type,business_id,todo_type,todo_status,opened_at)"
                + " VALUES (931000,'ASSET_ADMIN','INBOUND',931000,'ADMIN_DECISION','OPEN',NOW(3))");
        db.update("UPDATE fams_employee SET status='DISABLED' WHERE id=?", ADMIN_TWO);
        anomalies.scan();
        anomalies.scan();

        assertNotificationAdapterReadiness();
        var path = "/configuration-anomalies";
        var openPage = ok(get(system, path));
        var open = openPage.path("items").valueStream()
                .filter(item -> "NO_ASSET_ADMIN".equals(item.path("type").asText())).findFirst().orElseThrow();
        assertEquals("OPEN", open.path("status").asText());
        assertFalse(open.has("resolvedAt"));
        assertEquals("931000", open.path("relatedTodos").get(0).path("id").asText());
        assertEquals("931000", open.path("relatedTodos").get(0).path("parameterVersionId").asText());
        assertEquals("2026-11-17T16:00:00Z", open.path("relatedTodos").get(0).path("deadline").asText());
        assertEquals(403, code(get(warehouse, path)));
        assertEquals(404, code(get(system, path + "/999999")));

        db.update("UPDATE fams_employee SET status='ACTIVE' WHERE id=?", ADMIN_TWO);
        anomalies.scan();
        var resolved = ok(get(system, path + "/" + open.path("id").asText()));
        assertEquals("RESOLVED", resolved.path("status").asText());
        assertTrue(resolved.hasNonNull("resolvedAt"));
        assertEquals(open.path("relatedTodos"), resolved.path("relatedTodos"));
        var restored = login("duty3");
        assertTrue(ok(get(restored, "/messages")).path("items").valueStream()
                .anyMatch(item -> "931000".equals(item.path("id").asText())));
        assertEquals(403, code(get(system, "/messages")));
        assertEquals(3, db.queryForObject("SELECT version FROM fams_inbound_receipt WHERE id=931000", Integer.class));
        assertEquals(1, count("fams_todo"));

        db.update("""
                INSERT INTO fams_todo (pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,opened_at)
                VALUES ('WAREHOUSE_OPERATOR',931004,'TRANSFER',931001,'EXECUTION','OPEN',NOW(3))
                """);
        assertEquals(200, code(setAccountStatus(system, WAREHOUSE, 0, "DISABLE")));
        assertEquals(1, db.queryForObject("""
                SELECT COUNT(*) FROM fams_todo WHERE business_type='TRANSFER' AND business_id=931001
                    AND todo_type='REASSIGNMENT_REQUIRED' AND active_guard=1
                """, Integer.class));
        reassignments.scan();
        assertEquals(1, db.queryForObject("""
                SELECT COUNT(*) FROM fams_todo WHERE business_type='TRANSFER' AND business_id=931001
                    AND todo_type='REASSIGNMENT_REQUIRED' AND pool_type='ASSET_ADMIN' AND active_guard=1
                """, Integer.class));
        assertEquals(WAREHOUSE, db.queryForObject("""
                SELECT assignee_account_id FROM fams_todo WHERE business_type='TRANSFER' AND business_id=931001
                    AND todo_type='EXECUTION' AND active_guard=1
                """, Long.class));
        assertEquals(200, code(setAccountStatus(system, WAREHOUSE, 1, "ENABLE")));
        assertEquals(1, db.queryForObject("""
                SELECT COUNT(*) FROM fams_todo WHERE business_type='TRANSFER' AND business_id=931001
                    AND todo_type='REASSIGNMENT_REQUIRED' AND todo_status='CLOSED' AND closed_at IS NOT NULL
                """, Integer.class));

        db.update("""
                INSERT INTO fams_business_reminder (business_type,business_id,stage,deadline_at,notice_date,
                    reminder_business_date,reminder_type,recipient_pool_type,recipient_account_id,created_at)
                VALUES ('INBOUND',931000,'ADMIN_DECISION','2026-11-17 16:00:00',UTC_DATE(),UTC_DATE(),
                    'DUTY_UNREAD_ESCALATION','ASSET_ADMIN',931003,UTC_TIMESTAMP(3))
                """);
        deliveries.enqueue("reminder-test");
        deliveries.enqueue("reminder-test");
        assertEquals(1, count("fams_notification_delivery"));
        var claim = jobs.claim(NotificationDeliveryQueue.KIND, "first", java.time.Duration.ofMinutes(1)).orElseThrow();
        assertEquals("reminder-test", deliveries.eligibleTemplate(claim));
        assertTrue(jobs.claim(NotificationDeliveryQueue.KIND, "second", java.time.Duration.ofMinutes(1)).isEmpty());
        jobs.fail(claim, "SMS_RESULT_UNKNOWN", true);
        db.update("UPDATE fams_durable_job SET next_attempt_at=UTC_TIMESTAMP(3) WHERE id=?", claim.id().toString());
        var retry = jobs.claim(NotificationDeliveryQueue.KIND, "second", java.time.Duration.ofMinutes(1)).orElseThrow();
        assertEquals(claim.id(), retry.id());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> jobs.complete(claim, "SMS_ACCEPTED", () -> deliveries.accepted(claim)));
        jobs.complete(retry, "SMS_ACCEPTED", () -> deliveries.accepted(retry));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_notification_delivery"
                + " WHERE accepted_at IS NOT NULL", Integer.class));
        db.update("""
                INSERT INTO fams_business_reminder (business_type,business_id,stage,deadline_at,notice_date,
                    reminder_business_date,reminder_type,recipient_pool_type,recipient_account_id,created_at)
                VALUES ('INBOUND',931000,'ADMIN_DECISION','2026-11-17 16:00:00',UTC_DATE()-INTERVAL 1 DAY,
                    UTC_DATE(),'DUTY_UNREAD_ESCALATION','ASSET_ADMIN',931003,UTC_TIMESTAMP(3))
                """);
        var provider = new StaticListableBeanFactory();
        var calls = new ArrayList<String>();
        provider.addBean("sms", (MiniNotificationSmsPort) (recipient, template, deliveryKey) -> {
            assertEquals(ADMIN_TWO, recipient);
            assertEquals("reminder-test", template);
            calls.add(deliveryKey);
            return calls.size() == 1 ? MiniNotificationSmsPort.Result.UNKNOWN : MiniNotificationSmsPort.Result.ACCEPTED;
        });
        var adapters = provider.getBeanProvider(MiniNotificationSmsPort.class);
        new NotificationDeliveryWorker(jobs, deliveries, adapters, "reminder-test").poll();
        assertEquals(1, calls.size());
        db.update("UPDATE fams_durable_job SET next_attempt_at=UTC_TIMESTAMP(3) WHERE id=?", calls.getFirst());
        new NotificationDeliveryWorker(jobs, deliveries, adapters, "reminder-test").poll();
        assertEquals(2, calls.size());
        assertEquals(calls.getFirst(), calls.getLast());
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM fams_notification_delivery"
                + " WHERE accepted_at IS NOT NULL", Integer.class));

        var report = "/operations-reports?from=2026-11-01&toExclusive=2026-12-01";
        assertEquals(403, code(get(system, report)));
        assertEquals(0, ok(get(restored, report)).path("inventory").path("expected").asInt());
        db.update("""
                INSERT INTO fams_inventory_task (id,name,task_status,range_json,starts_at,due_at,timezone_id,
                    parameter_version_id,creator_account_id,created_at,updated_at,expected_count)
                VALUES (931000,'Report fixture','COMPLETED','{}','2026-11-01 00:00:00','2026-11-20 00:00:00',
                    'UTC',931000,931003,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),4)
                """);
        db.update("""
                INSERT INTO fams_inventory_sealed_report (task_id,task_version,timezone_id,expected_count,
                    accepted_count,not_observed_count,captured_count,actor_account_id,actor_username,
                    seal_reason,sync_digest,report_sha256,snapshot_json,created_at)
                VALUES (931000,1,'UTC',4,3,1,3,931003,'duty3','Report fixture',REPEAT('0',64),
                    REPEAT('1',64),'{}','2026-11-13 09:00:00')
                """);
        var inventory = ok(get(restored, report)).path("inventory");
        assertEquals(4, inventory.path("expected").asInt());
        assertEquals(3, inventory.path("accepted").asInt());
        assertEquals(1, inventory.path("notObserved").asInt());
        var export = get(restored, report.replace("?", "/inventory.csv?"));
        assertEquals(200, code(export));
        assertEquals("taskId,sealedAt,expected,accepted,notObserved\r\n931000,2026-11-13T09:00:00Z,4,3,1",
                export.body());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_audit_event"
                + " WHERE action='OPERATIONS_REPORT_EXPORTED'", Integer.class));

        db.update("""
                INSERT INTO fams_asset (id,asset_code,name,lifecycle_status,exception_status,card_stage,
                    created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,
                    created_at,updated_at,created_by,updated_by)
                VALUES
                    (9007199254740993,'REPORT-A','=1+1','UNDER_REPAIR','NORMAL','ACTIVE',
                        '2026-11-13',931000,931004,'duty4',NOW(3),NOW(3),931004,931004),
                    (9007199254740994,'REPORT-B','Draft','WAITING_INBOUND','NORMAL','DRAFT',
                        '2026-11-13',931000,931004,'duty4',NOW(3),NOW(3),931004,931004),
                    (9007199254740992,'REPORT-C','Existing','UNDER_REPAIR','NORMAL','ACTIVE',
                        '2026-11-13',931000,931004,'duty4',NOW(3),NOW(3),931004,931004)
                """);
        var assets = "/operations-reports/assets";
        assertEquals(403, code(get(system, assets)));
        assertEquals(403, code(get(login("duty4"), assets + ".csv")));
        assertEquals(400, code(get(restored, assets + "?warehouseId=-1")));
        var firstPage = ok(get(restored, assets + "?pageSize=1"));
        assertEquals("9007199254740993", firstPage.path("nextBefore").asText());
        assertEquals("REPORT-A", firstPage.path("items").get(0).path("assetCode").asText());
        var secondPage = ok(get(restored, assets + "?pageSize=1&before=" + firstPage.path("nextBefore").asText()));
        assertEquals("REPORT-C", secondPage.path("items").get(0).path("assetCode").asText());
        assertTrue(secondPage.path("nextBefore").isNull());
        assertEquals(0, ok(get(restored, assets + "?custody=SHARED")).path("items").size());
        var custodyCsv = get(restored, assets + ".csv?inventoryBefore=2026-11-13");
        assertEquals(200, code(custodyCsv));
        assertTrue(custodyCsv.body().contains("'=1+1"));
        assertFalse(custodyCsv.body().contains("REPORT-B"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_audit_event"
                + " WHERE resource_type='ASSET_CUSTODY_REPORT' AND JSON_EXTRACT(detail_json,'$.rows')=2", Integer.class));

        db.update("""
                INSERT INTO fams_transfer (id,creator_account_id,reason,request_status,created_at,updated_at)
                VALUES (931000,931004,'Report fixture','PARTIALLY_COMPLETED',NOW(3),NOW(3))
                """);
        db.update("""
                INSERT INTO fams_transfer_item (id,request_id,asset_id,asset_version,source_snapshot_json,
                    target_snapshot_json,parameter_snapshot_json,receipt_required,item_status,
                    completed_at,completed_by,after_snapshot_json)
                VALUES
                    (9007199254740993,931000,9007199254740993,0,'{}','{}','{}',false,'COMPLETED',
                        '2026-11-13 09:00:00',931004,'{}'),
                    (9007199254740992,931000,9007199254740992,0,'{}','{}','{}',false,'COMPLETED',
                        '2026-11-13 09:00:00',931004,'{}'),
                    (9007199254740994,931000,9007199254740994,0,'{}','{}','{}',false,'READY',NULL,NULL,NULL)
                """);
        var movement = "/operations-reports/movements";
        var range = "?from=2026-11-13&toExclusive=2026-11-14&kind=";
        for (String kind : new String[]{"INBOUND", "REQUISITION", "REQUISITION_RETURN", "BORROW", "BORROW_RETURN"}) {
            assertTrue(ok(get(restored, movement + range + kind)).path("items").isEmpty(), kind);
        }
        db.update("""
                INSERT INTO fams_asset_return (id,asset_id,asset_version,responsible_employee_id,using_organization_id,
                    proposed_by,reason,return_status,inspection_result,parameter_snapshot_json,created_at,completed_at)
                VALUES (931000,9007199254740993,0,931005,931000,931004,'Missing fixture',
                    'TRANSFERRED','MISSING','{}','2026-11-12 09:00:00','2026-11-13 00:00:00')
                """);
        var returnRow = ok(get(restored, movement + range + "REQUISITION_RETURN")).path("items").get(0);
        assertEquals("MISSING", returnRow.path("outcome").asText());
        assertEquals("2026-11-13T00:00:00Z", returnRow.path("occurredAt").asText());
        assertTrue(ok(get(restored, movement + "?from=2026-11-12&toExclusive=2026-11-13&kind=REQUISITION_RETURN"))
                .path("items").isEmpty());
        assertEquals(400, code(get(restored, movement + range + "INVALID")));
        assertEquals(400, code(get(restored, movement + "?kind=TRANSFER")));
        var transfers = movement + range + "TRANSFER";
        assertEquals(403, code(get(system, transfers)));
        assertEquals(400, code(get(restored, transfers + "&beforeId=1")));
        var transferPage = ok(get(restored, transfers + "&pageSize=1"));
        var cursor = transferPage.path("nextBefore");
        assertEquals("9007199254740993", cursor.path("factId").asText());
        assertEquals("2026-11-13T09:00:00Z", cursor.path("occurredAt").asText());
        var nextTransfer = ok(get(restored, transfers + "&pageSize=1&beforeAt=" + cursor.path("occurredAt").asText()
                + "&beforeId=" + cursor.path("factId").asText()));
        assertEquals("REPORT-C", nextTransfer.path("items").get(0).path("assetCode").asText());
        assertTrue(nextTransfer.path("nextBefore").isNull());
        var transferCsv = movement + ".csv" + range + "TRANSFER&assetId=9007199254740993";
        assertEquals(403, code(get(login("duty4"), transferCsv)));
        var exportedTransfer = get(restored, transferCsv);
        assertEquals(200, code(exportedTransfer));
        assertTrue(exportedTransfer.body().contains("REPORT-A"));
        assertFalse(exportedTransfer.body().contains("REPORT-C"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_audit_event"
                + " WHERE resource_type='ASSET_MOVEMENT_REPORT' AND JSON_EXTRACT(detail_json,'$.rows')=1", Integer.class));

        String batch = "00000000-0000-4000-8000-000000093100";
        db.update("""
                INSERT INTO fams_asset_import_batch
                    (id,actor_account_id,upload_key,source_sha256,source_bytes,source_xlsx,created_at,updated_at)
                VALUES (?,931004,'overdue-report-fixture',SHA2('overdue-report-fixture',256),1,X'01',NOW(3),NOW(3))
                """, batch);
        var now = java.time.Instant.now();
        var earlyZone = java.time.ZoneOffset.ofHours(-12);
        var earlyDate = now.atZone(earlyZone).toLocalDate();
        db.update("""
                INSERT INTO fams_loan (id,asset_id,borrower_employee_id,using_organization_id,source_type,
                    initial_batch_id,initial_sheet_row,borrowed_date,original_return_date,expected_return_date,
                    parameter_snapshot_json,relation_status,closed_at,created_at,updated_at)
                VALUES
                    (9007199254740993,9007199254740993,931005,931000,'INITIAL_IMPORT',?,2,
                        '2020-01-01','2020-02-01',?,JSON_OBJECT('timezone','+14:00'),'OPEN',NULL,NOW(3),NOW(3)),
                    (9007199254740995,9007199254740992,931005,931000,'INITIAL_IMPORT',?,3,
                        '2020-01-01','2020-02-01',?,JSON_OBJECT('timezone','-12:00'),'OPEN',NULL,NOW(3),NOW(3)),
                    (9007199254740996,9007199254740993,931005,931000,'INITIAL_IMPORT',?,4,
                        '2020-01-01','2020-02-01','2020-02-01',JSON_OBJECT('timezone','UTC'),'RETURNED',NOW(3),NOW(3),NOW(3))
                """, batch, earlyDate, batch, earlyDate, batch);
        var overdue = "/operations-reports/overdue-loans";
        assertEquals(403, code(get(system, overdue)));
        assertEquals(403, code(get(login("duty4"), overdue + ".csv")));
        assertEquals(400, code(get(restored, overdue + "?borrowerId=0")));
        var overduePage = ok(get(restored, overdue + "?pageSize=1&organizationId=931000"));
        assertEquals("9007199254740993", overduePage.path("items").get(0).path("loanId").asText());
        assertEquals(earlyDate.toString(), overduePage.path("items").get(0).path("expectedReturnDate").asText());
        assertTrue(overduePage.path("nextBefore").isNull());
        db.update("UPDATE fams_loan SET expected_return_date='2020-02-01' WHERE id=9007199254740995");
        var twoOverdue = ok(get(restored, overdue + "?pageSize=1"));
        assertEquals("9007199254740995", twoOverdue.path("nextBefore").asText());
        assertEquals("9007199254740993", ok(get(restored, overdue + "?pageSize=1&before="
                + twoOverdue.path("nextBefore").asText())).path("items").get(0).path("loanId").asText());
        db.update("UPDATE fams_asset SET exception_status='LOST' WHERE id=9007199254740992");
        assertTrue(ok(get(restored, overdue + "?borrowerId=931004")).path("items").isEmpty());
        var overdueCsv = get(restored, overdue + ".csv?borrowerId=931005");
        assertEquals(200, code(overdueCsv));
        assertTrue(overdueCsv.body().contains("REPORT-A"));
        assertFalse(overdueCsv.body().contains("REPORT-C"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_audit_event"
                + " WHERE resource_type='OVERDUE_LOAN_REPORT' AND JSON_EXTRACT(detail_json,'$.rows')=1", Integer.class));

        verifyLossOperationsReport(restored);

        db.update("""
                INSERT INTO fams_inventory_task (id,name,task_status,range_json,starts_at,due_at,timezone_id,
                    creator_account_id,created_at,updated_at)
                VALUES (931001,'Overdue task','IN_PROGRESS','{}',UTC_TIMESTAMP(3)-INTERVAL 3 DAY,
                    UTC_TIMESTAMP(3)-INTERVAL 2 DAY,'UTC',931003,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
                """);
        db.update("""
                INSERT INTO fams_inventory_item (task_id,asset_id,asset_version,snapshot_json,executor_account_id)
                VALUES (931001,9007199254740993,0,'{}',931005)
                """);
        db.update("""
                INSERT INTO fams_business_reminder (business_type,business_id,stage,deadline_at,notice_date,
                    reminder_business_date,reminder_type,recipient_pool_type,recipient_account_id,created_at)
                SELECT 'INVENTORY_TASK',id,'INVENTORY_COMPLETION',due_at,UTC_DATE(),UTC_DATE(),
                    'OVERDUE','EMPLOYEE',931005,UTC_TIMESTAMP(3) FROM fams_inventory_task WHERE id=931001
                """);
        db.update("""
                UPDATE fams_transfer_item SET item_status='DELIVERED',receiver_employee_id=931005,
                    receipt_required=TRUE,delivered_at=UTC_TIMESTAMP(3)-INTERVAL 4 DAY,
                    receipt_deadline_at=UTC_TIMESTAMP(3)-INTERVAL 2 DAY WHERE id=9007199254740994
                """);
        db.update("""
                INSERT INTO fams_business_reminder (business_type,business_id,stage,deadline_at,notice_date,
                    reminder_business_date,reminder_type,recipient_pool_type,recipient_account_id,created_at)
                SELECT 'TRANSFER_ITEM',id,'HANDOVER_RECEIPT',receipt_deadline_at,UTC_DATE(),UTC_DATE(),
                    'OVERDUE','EMPLOYEE',931005,UTC_TIMESTAMP(3) FROM fams_transfer_item WHERE id=9007199254740994
                """);
        deliveries.enqueue("reminder-test");
        deliveries.enqueue("reminder-test");
        assertEquals(4, count("fams_notification_delivery"));
        var checkedStages = new java.util.HashSet<String>();
        for (int index = 0; index < 2; index++) {
            var sms = jobs.claim(NotificationDeliveryQueue.KIND, "overdue", java.time.Duration.ofMinutes(1)).orElseThrow();
            assertEquals("reminder-test", deliveries.eligibleTemplate(sms));
            var stage = db.queryForObject("""
                    SELECT r.stage FROM fams_notification_delivery d
                    JOIN fams_business_reminder r ON r.id=d.reminder_id WHERE d.job_id=?
                    """, String.class, sms.id().toString());
            checkedStages.add(stage);
            if ("INVENTORY_COMPLETION".equals(stage)) {
                db.update("""
                        INSERT INTO fams_message_receipt (account_id,message_kind,message_id,read_at)
                        SELECT 931005,'REMINDER',reminder_id,UTC_TIMESTAMP(3)
                        FROM fams_notification_delivery WHERE job_id=?
                        """, sms.id().toString());
            } else {
                db.update("UPDATE fams_transfer_item SET receiver_employee_id=931004 WHERE id=9007199254740994");
            }
            assertTrue(deliveries.eligibleTemplate(sms) == null);
            jobs.fail(sms, "RECIPIENT_UNAVAILABLE_OR_READ", false);
        }
        assertEquals(java.util.Set.of("INVENTORY_COMPLETION", "HANDOVER_RECEIPT"), checkedStages);
        var reportWarehouse = login("duty4");
        verifyScrapDispositionReport(restored, reportWarehouse);
        verifyRepairOperationsReport(restored, reportWarehouse);
        verifyAssetTimelineReport(restored, reportWarehouse);
        verifyInventoryLateReport(restored, reportWarehouse);
        verifyBusinessExceptionReport(restored, reportWarehouse);
        verifyInboundTimingReport(restored, reportWarehouse);
    }

    private void verifyInboundTimingReport(Session administrator, Session warehouse) throws Exception {
        db.update("""
                INSERT INTO fams_warehouse (id,code,code_normalized,name,status,created_at,updated_at)
                VALUES (931001,'REPORT','REPORT','Report warehouse','ACTIVE',NOW(3),NOW(3))
                """);
        db.update("""
                INSERT INTO fams_inbound_decision (receipt_id,decision_result,decided_by_account_id,
                    decided_by_username_snapshot,decided_role,receipt_version_before,decided_at)
                VALUES (931000,'APPROVED',931003,'duty3','ASSET_ADMIN',3,'2026-11-13 02:00:00')
                """);
        db.update("""
                INSERT INTO fams_inbound_processing_ownership
                    (id,receipt_id,processor_account_id,processor_username_snapshot,ownership_version,acquired_at)
                VALUES (931001,931000,931005,'duty5',1,'2026-11-13 03:00:00')
                """);
        db.update("""
                INSERT INTO fams_audit_event
                    (id,request_id,actor_account_id,actor_role,action,resource_type,resource_id,occurred_at,detail_json)
                VALUES (9007199254741010,UUID(),931004,'WAREHOUSE_OPERATOR','INBOUND_WAREHOUSE_CLAIMED',
                        'INBOUND',931000,'2026-11-13 03:00:00','{}'),
                    (9007199254741011,UUID(),931003,'ASSET_ADMIN','INBOUND_CONTROLLED_REASSIGNMENT',
                        'INBOUND',931000,'2026-11-13 04:00:00',JSON_OBJECT('processorAccountId','931005')),
                    (9007199254741012,UUID(),931005,'WAREHOUSE_OPERATOR','INBOUND_ITEM_ACCEPTED',
                        'INBOUND',931000,'2026-11-13 05:00:00','{}'),
                    (9007199254741013,UUID(),931005,'WAREHOUSE_OPERATOR','INBOUND_ITEM_ACCEPTED',
                        'INBOUND',931000,'2026-11-13 06:00:00','{}')
                """);
        db.update("""
                INSERT INTO fams_inbound_receipt_item (id,receipt_id,asset_id,asset_version_snapshot,
                    asset_code_snapshot,asset_name_snapshot,target_warehouse_id,warehouse_version_snapshot,
                    warehouse_code_snapshot,warehouse_name_snapshot,target_location_id,location_version_snapshot,
                    location_code_snapshot,location_name_snapshot,execution_status,created_at,updated_at)
                VALUES (931001,931000,9007199254740993,0,'REPORT-A','Fixture',931001,0,'REPORT','Fixture',
                    931001,0,'REPORT','Fixture','ACCEPTED',NOW(3),NOW(3)),
                    (931002,931000,9007199254740992,0,'REPORT-C','Fixture',931001,0,'REPORT','Fixture',
                    931001,0,'REPORT','Fixture','ACCEPTED',NOW(3),NOW(3))
                """);
        db.update("""
                INSERT INTO fams_inbound_acceptance (receipt_item_id,asset_id,ownership_id,accepted_by_account_id,
                    accepted_by_username_snapshot,accepted_at,business_date,receipt_version_before,
                    asset_version_before,asset_version_after,lock_version,observed_asset_code,storage_warehouse_id,
                    storage_location_id,warehouse_version_at_acceptance,location_version_at_acceptance,audit_event_id)
                VALUES (931001,9007199254740993,931001,931005,'duty5','2026-11-13 05:00:00','2026-11-13',
                    6,0,1,0,'REPORT-A',931001,931001,0,0,9007199254741012),
                    (931002,9007199254740992,931001,931005,'duty5','2026-11-13 06:00:00','2026-11-13',
                    7,0,1,0,'REPORT-C',931001,931001,0,0,9007199254741013)
                """);
        var endpoint = "/operations-reports/inbound-timing";
        var claim = "?kind=CLAIM&receiptId=931000";
        assertEquals(403, code(get(warehouse, endpoint + claim)));
        assertEquals(403, code(get(warehouse, endpoint + ".csv" + claim)));
        assertEquals(400, code(get(administrator, endpoint + "?kind=UNKNOWN")));
        var approval = ok(get(administrator, endpoint + "?kind=APPROVAL&status=APPROVED")).path("items").get(0);
        assertEquals(3600, approval.path("elapsedSeconds").asLong());
        var claimed = ok(get(administrator, endpoint + claim)).path("items").get(0);
        assertEquals("931004", claimed.path("processorAccountId").asText());
        assertEquals(3600, claimed.path("elapsedSeconds").asLong());
        var accepted = ok(get(administrator, endpoint + "?kind=FIRST_EXECUTION")).path("items");
        assertEquals(1, accepted.size());
        assertEquals("931005", accepted.get(0).path("processorAccountId").asText());
        assertEquals(7200, accepted.get(0).path("elapsedSeconds").asLong());
        var reassigned = ok(get(administrator, endpoint + "?kind=REASSIGNMENT")).path("items").get(0);
        assertEquals("9007199254741011", reassigned.path("factId").asText());
        assertTrue(reassigned.path("elapsedSeconds").isNull());
        assertEquals(0, ok(get(administrator, endpoint + claim + "&before=9007199254741010")).path("items").size());
        var csv = get(administrator, endpoint + ".csv" + claim);
        assertEquals(200, code(csv));
        assertEquals(2, csv.body().lines().count());
        assertEquals(1, db.queryForObject("""
                SELECT COUNT(*) FROM fams_audit_event WHERE resource_type='INBOUND_TIMING_REPORT'
                    AND JSON_EXTRACT(detail_json,'$.rows')=1 AND JSON_EXTRACT(detail_json,'$.receiptId')=931000
                    AND JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.kind'))='CLAIM'
                """, Integer.class));
    }

    private void verifyBusinessExceptionReport(Session administrator, Session warehouse) throws Exception {
        db.update("""
                INSERT INTO fams_proxy_confirmation (id,business_type,business_id,item_id,business_snapshot_json,
                    evidence_metadata_json,confirmation_status,observed_asset_code,actual_signer,actual_received_at,
                    cannot_confirm_reason,contact_record,evidence_kind,collection_method,creator_account_id,
                    submitted_at,executed_at,created_at,updated_at)
                VALUES (9007199254740993,'REPAIR',931001,1,'{}','{}','EXECUTED','=1+1','Private signer',
                    '2026-11-01','reason','Private contact','SIGNED_RECEIPT','CODE_CONFIRMED',931004,
                    '2026-11-02','2026-11-03',NOW(3),NOW(3)),
                    (9007199254740994,'REPAIR',931001,1,'{}','{}','DRAFT','Draft','Signer',
                    '2026-11-01','reason','Contact','SIGNED_RECEIPT','CODE_CONFIRMED',931004,NULL,NULL,NOW(3),NOW(3))
                """);
        db.update("""
                INSERT INTO fams_scrap_reversal (id,request_id,asset_id,creator_account_id,reason,asset_version,
                    target_location_id,target_location_version,reversal_status,request_snapshot_json,created_at,updated_at)
                VALUES (9007199254740993,9007199254740995,9007199254740993,931004,'fixture',0,931001,0,
                    'COMPLETED','{}','2026-11-02','2026-11-03'),
                    (9007199254740994,9007199254740995,9007199254740993,931004,'fixture',0,931001,0,
                    'REJECTED','{}','2026-11-01','2026-11-02')
                """);
        db.update("""
                INSERT INTO fams_scrap_recovery (id,request_id,asset_id,creator_account_id,reason,found_at,
                    temporary_location_id,temporary_location_version,temporary_custodian_employee_id,asset_version,
                    target_location_id,target_location_version,recovery_status,request_snapshot_json,created_at,updated_at)
                VALUES (9007199254740993,9007199254740994,9007199254740993,931004,'fixture','2026-11-01',
                    931001,0,931004,0,931001,0,'PROCESSING','{}','2026-11-02','2026-11-03')
                """);
        var endpoint = "/operations-reports/business-exceptions";
        var proxy = "?kind=PROXY_CONFIRMATION&businessId=931001";
        assertEquals(403, code(get(warehouse, endpoint + proxy)));
        assertEquals(403, code(get(warehouse, endpoint + ".csv" + proxy)));
        assertEquals(400, code(get(administrator, endpoint + "?kind=UNKNOWN")));
        var proxies = ok(get(administrator, endpoint + proxy)).path("items");
        assertEquals(1, proxies.size());
        assertEquals("2026-11-03T00:00:00Z", proxies.get(0).path("processedAt").asText());
        assertFalse(proxies.toString().contains("Private"));
        var first = ok(get(administrator, endpoint + "?kind=SCRAP_REVERSAL&pageSize=1"));
        assertEquals("9007199254740994", first.path("nextBefore").asText());
        assertTrue(first.path("items").get(0).path("processedAt").isNull());
        var reversal = ok(get(administrator, endpoint + "?kind=SCRAP_REVERSAL&before=9007199254740994"));
        assertEquals("COMPLETED", reversal.path("items").get(0).path("status").asText());
        var recovery = ok(get(administrator, endpoint + "?kind=SCRAP_RECOVERY&status=PROCESSING"));
        assertEquals("9007199254740994", recovery.path("items").get(0).path("businessId").asText());
        assertTrue(recovery.path("items").get(0).path("processedAt").isNull());
        var csv = get(administrator, endpoint + ".csv" + proxy + "&status=EXECUTED");
        assertEquals(200, code(csv));
        assertEquals(2, csv.body().lines().count());
        assertTrue(csv.body().contains("'=1+1"));
        assertFalse(csv.body().contains("Private"));
        assertEquals(1, db.queryForObject("""
                SELECT COUNT(*) FROM fams_audit_event WHERE resource_type='BUSINESS_EXCEPTION_REPORT'
                    AND JSON_EXTRACT(detail_json,'$.rows')=1 AND JSON_EXTRACT(detail_json,'$.businessId')=931001
                    AND JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.status'))='EXECUTED'
                """, Integer.class));
    }

    private void verifyInventoryLateReport(Session administrator, Session warehouse) throws Exception {
        db.update("""
                INSERT INTO fams_inventory_task (id,name,task_status,range_json,starts_at,due_at,
                    creator_account_id,created_at,updated_at)
                VALUES (931002,'Recheck','READY','{}',UTC_TIMESTAMP(3)+INTERVAL 1 DAY,
                    UTC_TIMESTAMP(3)+INTERVAL 2 DAY,931003,NOW(3),NOW(3))
                """);
        for (long id : new long[]{9007199254740993L, 9007199254740994L}) {
            db.update("""
                    INSERT INTO fams_inventory_observation (id,task_id,client_event_id,actor_account_id,
                        device_id,assignment_version,recheck_generation,source,asset_code,raw_observed_at,
                        first_persisted_at,client_expires_at,received_at,facts_json,payload_sha256,fact_sha256,
                        outcome,receipt_json)
                    VALUES (?,931000,UUID(),931004,UUID(),0,0,'CODE','=1+1','2026-11-01','2026-11-01',
                        '2026-11-04','2026-11-02','{}',REPEAT('0',64),REPEAT('1',64),'LATE_EVIDENCE','{}')
                    """, id);
            db.update("""
                    INSERT INTO fams_inventory_late_case (observation_id,task_id,comparison_kind,needs_review,
                        snapshot_json,snapshot_sha256,created_at)
                    VALUES (?,931000,'FROZEN_CONFLICT',TRUE,'{}',REPEAT('2',64),NOW(3))
                    """, id);
        }
        db.update("""
                INSERT INTO fams_inventory_late_decision (observation_id,decision_version,method,result,
                    reason,evidence,linked_task_id,actor_account_id,actor_username,decided_at)
                VALUES (9007199254740994,1,'NOTE','REVIEWED','reason','evidence',NULL,931003,'duty3',NOW(3)),
                    (9007199254740994,2,'LINK_RECHECK','FOLLOWUP_OPEN','reason','evidence',931002,931003,'duty3',NOW(3))
                """);
        var endpoint = "/operations-reports/inventory-late";
        assertEquals(403, code(get(warehouse, endpoint)));
        assertEquals(403, code(get(warehouse, endpoint + ".csv")));
        assertEquals(400, code(get(administrator, endpoint + "?result=INVALID")));
        var page = ok(get(administrator, endpoint + "?taskId=931000&pageSize=1"));
        assertEquals("9007199254740994", page.path("nextBefore").asText());
        var linked = page.path("items").get(0);
        assertEquals("FOLLOWUP_OPEN", linked.path("result").asText());
        assertEquals("931002", linked.path("linkedTaskId").asText());
        assertEquals("READY", linked.path("linkedTaskStatus").asText());
        var next = ok(get(administrator, endpoint + "?taskId=931000&before=9007199254740994"));
        assertEquals("OPEN", next.path("items").get(0).path("result").asText());
        assertTrue(next.path("nextBefore").isNull());
        assertEquals(0, ok(get(administrator, endpoint + "?taskId=931002")).path("items").size());
        var csv = get(administrator, endpoint + ".csv?taskId=931000&result=FOLLOWUP_OPEN");
        assertEquals(200, code(csv));
        assertEquals(2, csv.body().lines().count());
        assertTrue(csv.body().contains("'=1+1"));
        assertEquals(1, db.queryForObject("""
                SELECT COUNT(*) FROM fams_audit_event WHERE resource_type='INVENTORY_LATE_REPORT'
                    AND JSON_EXTRACT(detail_json,'$.rows')=1 AND JSON_EXTRACT(detail_json,'$.taskId')=931000
                    AND JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.result'))='FOLLOWUP_OPEN'
                """, Integer.class));
    }

    private void verifyAssetTimelineReport(Session administrator, Session warehouse) throws Exception {
        db.update("""
                INSERT INTO fams_asset_timeline_event
                    (id,asset_id,event_type,business_type,business_id,occurred_at,summary_json,actor_account_id)
                VALUES
                    (9007199254740993,9007199254740993,'ASSET_UPDATED','ASSET',9007199254740993,
                     '2026-11-01',JSON_OBJECT('summary','=1+1','privateField','private fixture'),931004),
                    (9007199254740994,9007199254740993,'REPAIR_ACCEPTED_IN_PLACE','REPAIR',931001,
                     '2026-11-01',JSON_OBJECT('summary','Repair accepted'),931004),
                    (9007199254740995,9007199254740994,'ASSET_DRAFT_CREATED','ASSET',9007199254740994,
                     '2026-11-01',JSON_OBJECT('summary','Draft'),931004),
                    (9007199254740996,9007199254740993,'REPAIR_FACTORY_DISPATCHED','ASSET',9007199254740993,
                     '2026-11-01',JSON_OBJECT('repairId','931001','roundId','9007199254740995'),931004)
                """);
        var endpoint = "/operations-reports/asset-timeline";
        var asset = "?assetId=9007199254740993";
        assertEquals(403, code(get(warehouse, endpoint + asset)));
        assertEquals(403, code(get(warehouse, endpoint + ".csv" + asset)));
        assertEquals(400, code(get(administrator, endpoint + asset + "&beforeId=9007199254740994")));
        var factory = ok(get(administrator, endpoint + asset + "&eventType=REPAIR_FACTORY_DISPATCHED"))
                .path("items").get(0);
        assertEquals("REPAIR", factory.path("businessType").asText());
        assertEquals("931001", factory.path("businessId").asText());
        assertEquals("9007199254740995", factory.path("factoryRoundId").asText());
        var page = ok(get(administrator, endpoint + asset + "&pageSize=1"));
        assertEquals("9007199254740996", page.path("items").get(0).path("eventId").asText());
        assertEquals("931001", page.path("items").get(0).path("businessId").asText());
        var cursor = page.path("nextBefore");
        var next = ok(get(administrator, endpoint + asset + "&beforeAt=" + cursor.path("occurredAt").asText()
                + "&beforeId=" + cursor.path("eventId").asText()));
        assertEquals("9007199254740994", next.path("items").get(0).path("eventId").asText());
        assertEquals("=1+1", next.path("items").get(1).path("summary").asText());
        assertEquals(0, ok(get(administrator, endpoint + "?assetId=9007199254740994")).path("items").size());
        var csv = get(administrator, endpoint + ".csv" + asset + "&eventType=ASSET_UPDATED");
        assertEquals(200, code(csv));
        assertEquals(2, csv.body().lines().count());
        assertTrue(csv.body().contains("'=1+1"));
        assertFalse(csv.body().contains("private fixture"));
        assertEquals(1, db.queryForObject("""
                SELECT COUNT(*) FROM fams_audit_event WHERE resource_type='ASSET_TIMELINE_REPORT'
                    AND resource_id=9007199254740993 AND JSON_EXTRACT(detail_json,'$.rows')=1
                    AND JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.eventType'))='ASSET_UPDATED'
                """, Integer.class));
    }

    private void verifyRepairOperationsReport(Session administrator, Session warehouse) throws Exception {
        db.update("""
                INSERT INTO fams_location (id,code,code_normalized,name,location_type,status,created_at,updated_at)
                VALUES (931001,'REPAIR-REPORT','REPAIR-REPORT','Fixture','ROOM','ACTIVE',NOW(3),NOW(3))
                """);
        db.update("""
                INSERT INTO fams_repair_request (id,asset_id,creator_account_id,asset_version,source_snapshot_json,
                    timezone_id,fault,impact,occurred_at,created_at,updated_at)
                VALUES (931001,9007199254740993,931005,0,'{}','UTC','fixture','LOW',NOW(3),NOW(3),NOW(3))
                """);
        db.update("""
                INSERT INTO fams_repair_event (id,repair_id,action,before_version,after_version,actor_account_id,
                    actor_username,actor_role,detail_json,occurred_at)
                VALUES
                    (9007199254740993,931001,'REPAIR_ACCEPTED_IN_PLACE',0,1,931004,'duty4','WAREHOUSE_OPERATOR',
                     '{}','2026-11-01'),
                    (9007199254740994,931001,'REPAIR_COMPLETED_IN_PLACE',1,2,931004,'duty4','WAREHOUSE_OPERATOR',
                     '{}','2026-11-01')
                """);
        db.update("""
                INSERT INTO fams_repair_factory_scope (id,repair_id,request_snapshot_json,request_reason,
                    requested_by,requested_at)
                VALUES (931001,931001,'{}','fixture',931004,'2026-11-01')
                """);
        db.update("""
                INSERT INTO fams_repair_factory_round (id,repair_id,asset_id,scope_id,round_number,round_status,
                    provider_name,service_contact,service_phone,expected_return_date,fault,original_identity_json,
                    created_by,created_at,dispatched_at,dispatched_by,dispatch_evidence,actual_cost,received_at,
                    received_by,receive_location_id,receive_location_version,condition_result,treatment_result)
                VALUES
                    (9007199254740993,931001,9007199254740993,931001,1,'RECEIVED','provider','contact','test',
                     '2026-11-09','fixture','{}',931004,'2026-11-01','2026-11-01',931004,'fixture',0,
                     '2026-11-01',931004,931001,0,'STILL_FAULTY','fixture'),
                    (9007199254740994,931001,9007199254740993,931001,2,'RECEIVED','provider','contact','test',
                     '2026-11-09','fixture','{}',931004,'2026-11-01','2026-11-02',931004,'fixture',12.34,
                     '2026-11-02',931004,931001,0,'STILL_FAULTY','fixture'),
                    (9007199254740995,931001,9007199254740993,931001,3,'DISPATCHED','provider','contact','test',
                     '2026-11-09','fixture','{}',931004,'2026-11-01','2026-11-03',931004,'fixture',NULL,
                     NULL,NULL,NULL,NULL,NULL,NULL)
                """);
        var endpoint = "/operations-reports/repair-operations";
        var window = "?from=2026-11-01&toExclusive=2026-11-04&assetId=9007199254740993";
        assertEquals(403, code(get(warehouse, endpoint + window + "&kind=FACTORY")));
        assertEquals(403, code(get(warehouse, endpoint + ".csv" + window + "&kind=FACTORY")));
        assertEquals(400, code(get(administrator, endpoint + "?from=2026-11-04&toExclusive=2026-11-01&kind=FACTORY")));
        var factory = ok(get(administrator, endpoint + window + "&kind=FACTORY&pageSize=1"));
        assertEquals(3, factory.path("totals").path("count").asInt());
        assertEquals(2, factory.path("totals").path("confirmedCostCount").asInt());
        assertEquals("12.34", factory.path("totals").path("actualCost").asText());
        assertTrue(factory.path("items").get(0).path("actualCost").isNull());
        assertEquals("9007199254740995", factory.path("nextBefore").asText());
        var next = ok(get(administrator, endpoint + window + "&kind=FACTORY&before=9007199254740995"));
        assertEquals("9007199254740994", next.path("items").get(0).path("factId").asText());
        assertEquals(0, next.path("items").get(1).path("actualCost").asInt());
        var internal = ok(get(administrator, endpoint + window + "&kind=INTERNAL"));
        assertEquals(1, internal.path("totals").path("count").asInt());
        assertEquals("REPAIR_ACCEPTED_IN_PLACE", internal.path("items").get(0).path("status").asText());
        var csv = get(administrator, endpoint + ".csv" + window + "&kind=FACTORY");
        assertEquals(200, code(csv));
        assertEquals(4, csv.body().lines().count());
        assertEquals(1, db.queryForObject("""
                SELECT COUNT(*) FROM fams_audit_event WHERE resource_type='REPAIR_OPERATIONS_REPORT'
                    AND JSON_EXTRACT(detail_json,'$.rows')=3
                    AND JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.kind'))='FACTORY'
                """, Integer.class));
    }

    private void verifyScrapDispositionReport(Session administrator, Session warehouse) throws Exception {
        db.update("""
                UPDATE fams_business_parameter_version SET snapshot_json=JSON_OBJECT('timezone','+14:00')
                WHERE id=931000
                """);
        db.update("UPDATE fams_business_parameter_state SET current_version_id=931000,current_version=931000");
        var date = java.time.LocalDate.now(java.time.ZoneOffset.ofHours(14));
        for (long id = 9007199254740991L; id <= 9007199254740996L; id++) {
            db.update("""
                    INSERT INTO fams_scrap_request (id,asset_id,creator_account_id,source_type,source_snapshot_json,
                        reason,observed_condition,proposed_disposition,request_status,asset_version,created_at,updated_at)
                    VALUES (?,9007199254740993,931004,'STANDALONE','{}','fixture','fixture','fixture',?,0,NOW(3),NOW(3))
                    """, id, id == 9007199254740996L ? "CANCELLED" : "EXECUTED");
        }
        db.update("""
                INSERT INTO fams_scrap_disposition (request_id,processor_account_id,disposition_status,due_date,
                    custody_snapshot_json,created_at,updated_at)
                VALUES
                    (9007199254740991,931004,'PENDING_DISPOSITION',?,'{}',NOW(3),NOW(3)),
                    (9007199254740992,931004,'PENDING_DISPOSITION',?,'{}',NOW(3),NOW(3)),
                    (9007199254740993,931004,'DISPOSED',?,'{}',NOW(3),'2026-11-13 00:00:00'),
                    (9007199254740994,931005,'LOSS_WRITTEN_OFF',NULL,'{}',NOW(3),NOW(3)),
                    (9007199254740995,931004,'REVERSED',?,'{}',NOW(3),NOW(3))
                """, date.minusDays(1), date, date.minusDays(1), date.minusDays(1));
        var endpoint = "/operations-reports/scrap-dispositions";
        assertEquals(403, code(get(warehouse, endpoint)));
        assertEquals(403, code(get(warehouse, endpoint + ".csv")));
        assertEquals(400, code(get(administrator, endpoint + "?state=EXECUTED")));
        var page = ok(get(administrator, endpoint + "?pageSize=1"));
        assertEquals(date.toString(), page.path("businessDate").asText());
        assertEquals(5, page.path("counts").size());
        assertTrue(page.path("counts").valueStream().allMatch(count -> count.path("count").asInt() == 1));
        assertEquals("REVERSED", page.path("items").get(0).path("state").asText());
        assertEquals("9007199254740995", page.path("nextBefore").asText());
        var next = ok(get(administrator, endpoint + "?pageSize=1&before=" + page.path("nextBefore").asText()));
        assertEquals("LOSS_WRITTEN_OFF", next.path("items").get(0).path("state").asText());
        assertTrue(next.path("items").get(0).path("dueDate").isNull());
        var pending = ok(get(administrator, endpoint + "?state=PENDING_DISPOSITION"));
        assertEquals("9007199254740992", pending.path("items").get(0).path("requestId").asText());
        var disposed = ok(get(administrator, endpoint + "?state=DISPOSED"));
        assertEquals("2026-11-13T00:00:00Z", disposed.path("items").get(0).path("dispositionRecordedAt").asText());
        var csv = get(administrator, endpoint + ".csv?processorId=931004&state=OVERDUE");
        assertEquals(200, code(csv));
        assertEquals(2, csv.body().lines().count());
        assertTrue(csv.body().lines().skip(1).findFirst().orElseThrow().startsWith("9007199254740991,"));
        assertEquals(1, db.queryForObject("""
                SELECT COUNT(*) FROM fams_audit_event WHERE resource_type='SCRAP_DISPOSITION_REPORT'
                    AND JSON_EXTRACT(detail_json,'$.rows')=1
                    AND JSON_EXTRACT(detail_json,'$.processorId')=931004
                    AND JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.state'))='OVERDUE'
                """, Integer.class));
    }

    private void verifyLossOperationsReport(Session administrator) throws Exception {
        db.update("""
                INSERT INTO fams_loss_report (id,asset_id,creator_account_id,using_organization_id,asset_version,
                    source_snapshot_json,last_seen_at,last_location,circumstances,report_status,
                    confirmed_lost_at,recovered_at,created_at,updated_at)
                VALUES
                    (9007199254740993,9007199254740993,931004,931000,0,'{}','2026-11-01 00:00:00','X','X',
                        'SEARCHING',NULL,NULL,'2026-11-02 00:00:00',NOW(3)),
                    (9007199254740994,9007199254740993,931004,931000,0,'{}','2026-11-01 00:00:00','X','X',
                        'CONFIRMED_LOST','2026-11-03 00:00:00','2026-11-04 00:00:00','2026-11-02 00:00:00',NOW(3)),
                    (9007199254740995,9007199254740992,931004,NULL,0,'{}','2026-11-01 00:00:00','X','X',
                        'CONFIRMED_LOST','2026-11-03 00:00:00',NULL,'2026-11-02 00:00:00',NOW(3)),
                    (9007199254740996,9007199254740992,931004,931000,0,'{}','2026-11-01 00:00:00','X','X',
                        'REJECTED',NULL,NULL,'2026-11-02 00:00:00',NOW(3))
                """);
        var endpoint = "/operations-reports/loss";
        var currentWarehouse = login("duty4");
        assertEquals(403, code(get(currentWarehouse, endpoint)));
        assertEquals(403, code(get(currentWarehouse, endpoint + ".csv")));
        assertEquals(400, code(get(administrator, endpoint + "?state=REJECTED")));
        var page = ok(get(administrator, endpoint + "?organizationId=931000&pageSize=1"));
        var first = page.path("items").get(0);
        assertEquals("9007199254740994", first.path("reportId").asText());
        assertEquals("RECOVERED", first.path("state").asText());
        assertEquals("2026-11-03T00:00:00Z", first.path("confirmedLostAt").asText());
        assertEquals("2026-11-04T00:00:00Z", first.path("recoveredAt").asText());
        assertEquals(2, page.path("counts").size());
        assertEquals("9007199254740994", page.path("nextBefore").asText());
        var next = ok(get(administrator, endpoint + "?organizationId=931000&pageSize=1&before="
                + page.path("nextBefore").asText()));
        assertEquals("9007199254740993", next.path("items").get(0).path("reportId").asText());
        assertTrue(next.path("nextBefore").isNull());
        var confirmed = ok(get(administrator, endpoint + "?state=CONFIRMED_LOST"));
        assertEquals(1, confirmed.path("items").size());
        assertEquals("9007199254740995", confirmed.path("items").get(0).path("reportId").asText());
        assertTrue(confirmed.path("items").get(0).path("organization").isNull());
        var csv = get(administrator, endpoint + ".csv?organizationId=931000&state=RECOVERED");
        assertEquals(200, code(csv));
        assertTrue(csv.body().contains("9007199254740994"));
        assertEquals(2, csv.body().lines().count());
        assertTrue(csv.body().lines().skip(1).findFirst().orElseThrow().startsWith("9007199254740994,"));
        assertEquals(1, db.queryForObject("""
                SELECT COUNT(*) FROM fams_audit_event WHERE resource_type='LOSS_REPORT_EXPORT'
                    AND JSON_EXTRACT(detail_json,'$.rows')=1
                    AND JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.state'))='RECOVERED'
                """, Integer.class));
    }

    private void assertNotificationAdapterReadiness() {
        var providers = new StaticListableBeanFactory();
        var sms = providers.getBeanProvider(MiniNotificationSmsPort.class);
        var wechat = providers.getBeanProvider(WechatSubscriptionPort.class);
        var check = new ConfigurationAnomalyService(db, java.time.Clock.systemUTC(), sms, wechat,
                "reminder-test", "wechat-test");
        check.scan();
        assertEquals(2, openChannelAnomalies());

        providers.addBean("sms", (MiniNotificationSmsPort) (account, template, key) -> {
            throw new AssertionError("Readiness checks must never send a text message");
        });
        providers.addBean("wechat", (WechatSubscriptionPort) delivery -> {
            throw new AssertionError("Readiness checks must never send a subscription message");
        });
        check.scan();
        assertEquals(0, openChannelAnomalies());

        providers.addBean("otherWechat", (WechatSubscriptionPort) delivery -> WechatSubscriptionPort.Result.REJECTED);
        check.scan();
        assertEquals(1, openChannelAnomalies());
    }

    private int openChannelAnomalies() {
        return db.queryForObject("""
                SELECT COUNT(*) FROM fams_configuration_anomaly
                WHERE active_slot=1 AND anomaly_type IN ('SMS_CHANNEL_MISSING','WECHAT_TEMPLATE_MISSING')
                """, Integer.class);
    }

    private long rows(String role) {
        return db.queryForObject("SELECT COUNT(*) FROM fams_duty_assignment WHERE role_code=?",
                Long.class, role);
    }

    private long active(String role) {
        return db.queryForObject("SELECT COUNT(*) FROM fams_duty_assignment WHERE role_code=?"
                + " AND duty_status='ACTIVE'", Long.class, role);
    }

    private long count(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private int code(HttpResponse<String> response) {
        return response.statusCode();
    }

    private HttpResponse<String> get(Session session) throws Exception {
        return get(session, "/duty-assignments");
    }

    private HttpResponse<String> get(Session session, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Cookie", session.cookie()).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> setAccountStatus(Session session, long id, long version, String action)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/system-accounts/" + id + "/status"))
                .header("Content-Type", "application/json").header("Cookie", session.cookie())
                .header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match-Version", Long.toString(version))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("action", action, "reason", "Verification of ownership reminder"))))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(Session session, String role, long version, long primary, Long backup)
            throws Exception {
        return put(session, role, version, primary, backup, UUID.randomUUID().toString());
    }

    private HttpResponse<String> put(Session session, String role, long version, long primary, Long backup,
            String key) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("primaryAccountId", Long.toString(primary));
        body.put("backupAccountId", backup == null ? null : Long.toString(backup));
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/duty-assignments/" + role))
                .header("Content-Type", "application/json").header("Cookie", session.cookie())
                .header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", key)
                .header("If-Match-Version", Long.toString(version))
                .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://127.0.0.1:" + port + "/api/v1";
    }

    private JsonNode ok(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private record Session(String cookie, String csrf) { }

    private Session login(String username) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(base() + "/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("username", username, "password", PASSWORD)))).build(),
                HttpResponse.BodyHandlers.ofString());
        var body = ok(response);
        return new Session(response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                body.path("csrfToken").asText());
    }

    private void seed() {
        var hash = new BCryptPasswordEncoder(4).encode(PASSWORD);
        db.update("INSERT INTO fams_organization (id,code,code_normalized,name,status,version,created_at,updated_at)"
                + " VALUES (931000,'DUTY','DUTY','Duty','ACTIVE',0,NOW(3),NOW(3))");
        String[][] people = {{"931001", "duty1", "SYSTEM_ADMIN"}, {"931002", "duty2", "ASSET_ADMIN"},
            {"931003", "duty3", "ASSET_ADMIN"}, {"931004", "duty4", "WAREHOUSE_OPERATOR"},
            {"931005", "duty5", "EMPLOYEE"}};
        for (var person : people) {
            var id = Long.parseLong(person[0]);
            db.update("INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,organization_id,"
                    + "status,version,created_at,updated_at) VALUES (?,?,?,?,931000,'ACTIVE',0,NOW(3),NOW(3))",
                    id, person[1].toUpperCase(), person[1].toUpperCase(), "Duty person " + person[2]);
            db.update("INSERT INTO fams_system_account (id,employee_id,username,username_normalized,password_hash,"
                    + "status,initial_password_required,created_at,updated_at)"
                    + " VALUES (?,?,?,?,?,'ENABLED',FALSE,NOW(3),NOW(3))",
                    id, id, person[1], person[1].toUpperCase(), hash);
            db.update("INSERT INTO fams_account_role (account_id,role_code,granted_at) VALUES (?,?,NOW(3))",
                    id, person[2]);
        }
    }

    @SpringBootApplication(scanBasePackages = "com.fams")
    static class TestApplication { }
}
