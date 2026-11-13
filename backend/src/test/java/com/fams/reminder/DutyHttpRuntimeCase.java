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
        provider.addBean("sms", new MiniNotificationSmsPort() {
            @Override public void sendBindingCode(String phone, String code) {
                throw new AssertionError("The reminder worker must not send binding codes");
            }

            @Override public Result sendReminder(long recipient, String template, String key) {
                assertEquals(ADMIN_TWO, recipient);
                assertEquals("reminder-test", template);
                calls.add(key);
                return calls.size() == 1 ? Result.UNKNOWN : Result.ACCEPTED;
            }
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
