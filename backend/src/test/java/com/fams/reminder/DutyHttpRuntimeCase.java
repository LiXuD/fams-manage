package com.fams.reminder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
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
