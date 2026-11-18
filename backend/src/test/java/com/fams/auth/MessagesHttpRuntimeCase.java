package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fams.reminder.BusinessReminderService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
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

/** Addressivity, per-person read independence and the overdue exception scope over real HTTP sessions. */
@SpringBootTest(classes = MessagesHttpRuntimeCase.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.profiles.active=fams-product", "spring.flyway.locations=classpath:db/migration/d6_04",
    "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
    "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra",
    "fams.attachments.cleanup-enabled=false"
})
@EnabledIfSystemProperty(named = "d6.24.messages.runtime", matches = "true")
class MessagesHttpRuntimeCase {
    private static final String PASSWORD = "owned-message-24-password";
    private static final long ADMIN = 930701;
    private static final long WAREHOUSE = 930702;
    private static final long EMPLOYEE = 930703;
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired JdbcTemplate db;
    @Autowired MiniSessionService miniSessions;
    @Autowired ObjectMapper json;
    @Autowired BusinessReminderService reminders;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("D6_24B_TEST_DATASOURCE_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("D6_24B_TEST_DATASOURCE_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("D6_24B_TEST_DATASOURCE_PASSWORD"));
    }

    @Test
    void inboxAndExceptionListsFollowAddressivityAndReadStateIsPerPerson() throws Exception {
        seed();
        reminder(930913, "INBOUND", 12, "WAREHOUSE_CLAIM", "SMS_FALLBACK",
                "2026-11-09", "WAREHOUSE_OPERATOR", WAREHOUSE);
        var admin = login("msg1");
        var warehouse = login("msg2");
        var employee = login("msg3");

        // Each inbox holds only the todos and reminders addressed to that person or to one of their pools,
        // and only the newest occurrence of an object stage is active; older notices stay history.
        // The TECH_ASSISTANT pool row belongs to the repair invitation pages, not to these three inboxes.
        assertEquals(List.of("REMINDER:930902", "TODO:930801", "TODO:930805"), keys(inbox(admin, "INBOX")));
        assertEquals(List.of("REMINDER:930912", "TODO:930802"), keys(inbox(warehouse, "INBOX")));
        assertEquals(List.of("TODO:930803"), keys(inbox(employee, "INBOX")));
        assertFalse(keys(inbox(admin, "INBOX")).contains("REMINDER:930901"));
        assertFalse(keys(inbox(warehouse, "INBOX")).contains("REMINDER:930911"));
        assertEquals(3, inbox(admin, "INBOX").path("unread").asInt());
        // Reminders carry the ladder stage, todos carry none, so each client can label both kinds.
        assertEquals("ADMIN_DECISION", objectStage(inbox(admin, "INBOX"), "930902"));
        assertEquals("", objectStage(inbox(admin, "INBOX"), "930801"));

        assertEquals(List.of("REMINDER:930902", "REMINDER:930912", "TODO:930805"),
                keys(inbox(admin, "EXCEPTION")));
        assertEquals(List.of("REMINDER:930912"), keys(inbox(warehouse, "EXCEPTION")));
        assertEquals(403, get(employee, "/api/v1/messages?view=EXCEPTION").statusCode());
        assertEquals(400, get(employee, "/api/v1/messages?view=BOGUS").statusCode());

        var first = keys(inbox(admin, "INBOX")).get(2);
        assertEquals(200, read(admin, first).statusCode());
        assertEquals(2, inbox(admin, "INBOX").path("unread").asInt());
        assertEquals(List.of(first), readFlags(inbox(admin, "INBOX")));
        // Reading is not processing: the shared todo stays open for the pool and unread for every other reader.
        assertEquals(2, inbox(warehouse, "INBOX").path("unread").asInt());
        assertEquals("OPEN", db.queryForObject("SELECT todo_status FROM fams_todo WHERE id=?", String.class,
                Long.parseLong(first.split(":")[1])));

        assertEquals(404, read(employee, "REMINDER:930912").statusCode());
        assertEquals(404, read(admin, "REMINDER:930913").statusCode());
        assertEquals(200, read(admin, "REMINDER:930912").statusCode());
        assertEquals(1, receiptCount(930912));
        assertEquals(404, read(admin, "TODO:930899").statusCode());
        assertEquals(400, readWithoutKey(admin, "TODO:930801").statusCode());

        // The same pair serves the mini session, so the native client reads one authoritative inbox.
        var identity = new MiniWechatIdentityPort.Identity("owned-message-app", "message-warehouse");
        db.update("INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES (?,?,NOW(3))",
                WAREHOUSE, miniSessions.identityHash(identity));
        var grant = miniSessions.start(identity, UUID.randomUUID());
        assertEquals(List.of("REMINDER:930912", "TODO:930802"), keys(miniInbox(grant.sessionToken())));
        assertEquals(200, http.send(post("/api/v1/mini/messages/REMINDER/930912/read",
                grant.sessionToken(), null), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(List.of("REMINDER:930912"), readFlags(miniInbox(grant.sessionToken())));
        // The warehouse reader's own receipt is separate from the administrator's earlier one.
        assertEquals(2, receiptCount(930912));

        var page = ok(get(admin, "/api/v1/messages?page=1&pageSize=1"));
        assertEquals(3, page.path("total").asInt());
        assertEquals(1, page.path("items").size());
        var second = ok(get(admin, "/api/v1/messages?page=2&pageSize=1"));
        assertFalse(keys(page).equals(keys(second)));
        assertEquals(1, second.path("items").size());

        verifyWarehouseExceptions(admin, warehouse, grant.sessionToken());
        verifyInventoryExecutors(admin, warehouse, employee, grant.sessionToken());
        verifyClosedCustodianReminders(admin, warehouse);
        verifyFollowup(admin, warehouse);
    }

    private void verifyFollowup(Session admin, Session warehouse) throws Exception {
        reminder(939801, "INBOUND", 11, "ADMIN_DECISION", "OVERDUE", "2026-11-07", "ASSET_ADMIN", null);
        reminder(939802, "INBOUND", 11, "ADMIN_DECISION", "OVERDUE_UNRESOLVED_WORKDAY", "2026-11-09",
                "ASSET_ADMIN", ADMIN);
        String endpoint = "/api/v1/messages/reminders/939801/follow-up";
        assertTrue(ok(get(admin, endpoint)).path("latest").isNull());
        assertEquals(403, get(warehouse, endpoint).statusCode());
        assertTrue(keys(inbox(admin, "EXCEPTION")).contains("REMINDER:939802"));
        var key = UUID.randomUUID();
        var next = java.time.Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var body = json.writeValueAsString(Map.of("findings", "已联系申请人核对实物情况", "nextFollowupAt", next.toString()));
        assertEquals(403, followup(warehouse, endpoint, key, body).statusCode());
        var past = body.replace(next.toString(), "2000-01-01T00:00:00Z");
        assertEquals(400, followup(admin, endpoint, key, past).statusCode());
        var saved = ok(followup(admin, endpoint, key, body));
        assertEquals(saved, ok(followup(admin, endpoint, key, body)));
        assertEquals(409, followup(admin, endpoint, key, body.replace("实物", "单据")).statusCode());
        assertEquals(saved, ok(get(admin, endpoint)).path("latest"));
        assertFalse(keys(inbox(admin, "EXCEPTION")).contains("REMINDER:939802"));
        assertTrue(keys(inbox(admin, "EXCEPTION")).contains("REMINDER:939801"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_reminder_followup", Integer.class));
        assertEquals(1, db.queryForObject("""
                SELECT COUNT(*) FROM fams_audit_event WHERE action='REMINDER_FOLLOWUP_RECORDED'
                    AND resource_type='INBOUND' AND resource_id=11
                """, Integer.class));
        db.update("""
                UPDATE fams_reminder_followup SET recorded_at=UTC_TIMESTAMP(3)-INTERVAL 2 HOUR,
                    next_followup_at=UTC_TIMESTAMP(3)-INTERVAL 1 HOUR WHERE reminder_id=939801
                """);
        assertTrue(keys(inbox(admin, "EXCEPTION")).contains("REMINDER:939802"));
        db.update("UPDATE fams_inbound_receipt SET receipt_status='WAITING_WAREHOUSE_CLAIM' WHERE id=11");
        assertEquals(409, get(admin, endpoint).statusCode());
        assertEquals(409, followup(admin, endpoint, UUID.randomUUID(), body).statusCode());
        assertFalse(keys(inbox(admin, "EXCEPTION")).contains("REMINDER:939802"));
    }

    private HttpResponse<String> followup(Session session, String path, UUID key, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Cookie", session.cookie()).header("X-CSRF-Token", session.csrf())
                .header("Idempotency-Key", key.toString()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void verifyClosedCustodianReminders(Session admin, Session warehouse) throws Exception {
        db.update("""
                INSERT INTO fams_loss_report (id,asset_id,creator_account_id,asset_version,source_snapshot_json,
                    last_seen_at,last_location,circumstances,report_status,processor_account_id,next_review_date,
                    confirmed_lost_at,created_at,updated_at)
                VALUES (930960,930921,930701,0,'{}',UTC_TIMESTAMP()-INTERVAL 9 DAY,'fixture','fixture',
                    'CONFIRMED_LOST',930702,UTC_DATE()-INTERVAL 4 DAY,UTC_TIMESTAMP()-INTERVAL 8 DAY,NOW(3),NOW(3))
                """);
        db.update("UPDATE fams_asset SET exception_status='LOST' WHERE id=930921");
        db.update("UPDATE fams_asset SET lifecycle_status='SCRAPPED' WHERE id=930922");
        db.update("""
                INSERT INTO fams_scrap_request (id,asset_id,creator_account_id,source_type,source_snapshot_json,
                    reason,observed_condition,proposed_disposition,request_status,asset_version,created_at,updated_at)
                VALUES (930961,930922,930701,'STANDALONE','{}','fixture','fixture','fixture','EXECUTED',0,NOW(3),NOW(3))
                """);
        db.update("""
                INSERT INTO fams_scrap_disposition (request_id,processor_account_id,disposition_status,
                    due_date,due_at,due_timezone,custody_snapshot_json,created_at,updated_at)
                VALUES (930961,930702,'PENDING_DISPOSITION',UTC_DATE()-INTERVAL 2 DAY,
                    UTC_DATE()-INTERVAL 1 DAY,'UTC','{}',NOW(3),NOW(3))
                """);
        reminders.scan();
        var firstLoss = overdueReminder("LOST_REPORT", 930960);
        var scrap = overdueReminder("SCRAP", 930961);
        assertTrue(keys(inbox(warehouse, "EXCEPTION")).containsAll(List.of(firstLoss, scrap)));
        assertEquals(200, read(warehouse, firstLoss).statusCode());
        assertEquals(200, read(warehouse, scrap).statusCode());

        db.update("UPDATE fams_loss_report SET next_review_date=UTC_DATE()-INTERVAL 1 DAY WHERE id=930960");
        assertFalse(keys(inbox(warehouse, "INBOX")).contains(firstLoss));
        assertEquals(404, read(admin, firstLoss).statusCode());
        reminders.scan();
        var newLoss = overdueReminder("LOST_REPORT", 930960);
        assertFalse(firstLoss.equals(newLoss));
        assertTrue(keys(inbox(warehouse, "EXCEPTION")).contains(newLoss));

        db.update("UPDATE fams_asset SET lifecycle_status='SCRAPPED' WHERE id=930921");
        db.update("UPDATE fams_scrap_disposition SET disposition_status='DISPOSED' WHERE request_id=930961");
        for (var item : List.of(newLoss, scrap)) {
            assertFalse(keys(inbox(admin, "EXCEPTION")).contains(item));
            assertFalse(keys(inbox(warehouse, "INBOX")).contains(item));
            assertEquals(404, read(warehouse, item).statusCode());
        }
        db.update("""
                INSERT INTO fams_scrap_request (id,asset_id,creator_account_id,source_type,source_business_id,
                    source_snapshot_json,reason,observed_condition,proposed_disposition,request_status,asset_version,
                    created_at,updated_at)
                VALUES (930962,930921,930701,'LOSS',930960,'{}','fixture','fixture','fixture','EXECUTED',0,NOW(3),NOW(3))
                """);
        db.update("""
                INSERT INTO fams_scrap_disposition (request_id,processor_account_id,disposition_status,
                    custody_snapshot_json,created_at,updated_at)
                VALUES (930962,930702,'LOSS_WRITTEN_OFF','{}',NOW(3),NOW(3))
                """);
        db.update("UPDATE fams_asset SET lifecycle_status='IN_STOCK' WHERE id=930921");
        assertFalse(keys(inbox(admin, "EXCEPTION")).contains(newLoss));
        assertEquals(404, read(warehouse, newLoss).statusCode());
        db.update("UPDATE fams_loss_report SET next_review_date=UTC_DATE()-INTERVAL 2 DAY WHERE id=930960");
        reminders.scan();
        assertEquals(newLoss, overdueReminder("LOST_REPORT", 930960));
        assertEquals(1, receiptCount(Long.parseLong(firstLoss.split(":")[1])));
        assertEquals(1, receiptCount(Long.parseLong(scrap.split(":")[1])));
    }

    private String overdueReminder(String businessType, long businessId) {
        return "REMINDER:" + db.queryForObject("""
                SELECT MAX(id) FROM fams_business_reminder
                WHERE business_type=? AND business_id=? AND reminder_type='OVERDUE'
                """, Long.class, businessType, businessId);
    }

    private void verifyWarehouseExceptions(Session admin, Session warehouse, String miniToken) throws Exception {
        var otherWarehouse = login("msg4");
        todo(930850, "WAREHOUSE_OPERATOR", 930704L, "SCRAP", 20, "OVERDUE");
        todo(930851, "WAREHOUSE_OPERATOR", null, "SCRAP", 21, "OVERDUE");
        reminder(930951, "INBOUND", 22, "FIRST_EXECUTION", "OVERDUE", "2026-11-09", "WAREHOUSE_OPERATOR", 930704L);
        reminder(930952, "INBOUND", 23, "FIRST_EXECUTION", "OVERDUE", "2026-11-09", "WAREHOUSE_OPERATOR", null);

        var personal = List.of("REMINDER:930951", "TODO:930850");
        var shared = List.of("REMINDER:930952", "TODO:930851");
        var ownExceptions = inbox(warehouse, "EXCEPTION");
        assertEquals(List.of("REMINDER:930912", "REMINDER:930952", "TODO:930851"), keys(ownExceptions));
        assertEquals(3, ownExceptions.path("total").asInt());
        assertEquals(2, ownExceptions.path("unread").asInt());
        var miniExceptions = ok(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/mini/messages?view=EXCEPTION")).header("X-FAMS-Mini-Session", miniToken)
                .GET().build(), HttpResponse.BodyHandlers.ofString()));
        assertEquals(keys(ownExceptions), keys(miniExceptions));
        for (var other : List.of(admin, otherWarehouse)) {
            var visible = keys(inbox(other, "EXCEPTION"));
            assertTrue(visible.containsAll(personal));
            assertTrue(visible.containsAll(shared));
        }
        for (var item : personal) {
            assertEquals(404, read(warehouse, item).statusCode());
            assertEquals(200, read(otherWarehouse, item).statusCode());
        }
        for (var item : shared) {
            assertEquals(200, read(warehouse, item).statusCode());
            assertFalse(readFlags(inbox(otherWarehouse, "EXCEPTION")).contains(item));
        }
    }

    private void verifyInventoryExecutors(Session admin, Session warehouse, Session employee, String miniToken)
            throws Exception {
        db.update("""
                INSERT INTO fams_inventory_task (id,name,task_status,range_json,starts_at,due_at,timezone_id,
                    creator_account_id,created_at,updated_at)
                VALUES (930920,'Message inventory','IN_PROGRESS','{}','2026-11-01 12:00:00',
                    '2026-11-08 12:00:00','UTC',930701,NOW(3),NOW(3))
                """);
        db.update("""
                INSERT INTO fams_asset (id,asset_code,name,lifecycle_status,exception_status,card_stage,
                    created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,
                    created_at,updated_at,created_by,updated_by)
                VALUES (930921,'MSG-A','Message asset A','IN_STOCK','NORMAL','ACTIVE',
                        '2026-11-01',930920,930701,'msg1',NOW(3),NOW(3),930701,930701),
                       (930922,'MSG-B','Message asset B','IN_STOCK','NORMAL','ACTIVE',
                        '2026-11-01',930920,930701,'msg1',NOW(3),NOW(3),930701,930701)
                """);
        db.update("""
                INSERT INTO fams_inventory_item (task_id,asset_id,asset_version,snapshot_json,executor_account_id)
                VALUES (930920,930921,0,'{}',930702),(930920,930922,0,'{}',930703)
                """);
        reminder(930921, "INVENTORY_TASK", 930920, "INVENTORY_COMPLETION", "DUE_TODAY", "2026-11-08", "EMPLOYEE", WAREHOUSE);
        reminder(930922, "INVENTORY_TASK", 930920, "INVENTORY_COMPLETION", "OVERDUE", "2026-11-09", "EMPLOYEE", WAREHOUSE);
        reminder(930923, "INVENTORY_TASK", 930920, "INVENTORY_COMPLETION", "OVERDUE", "2026-11-09", "EMPLOYEE", EMPLOYEE);

        assertTrue(keys(inbox(warehouse, "INBOX")).contains("REMINDER:930922"));
        assertFalse(keys(inbox(warehouse, "INBOX")).contains("REMINDER:930921"));
        assertTrue(keys(miniInbox(miniToken)).contains("REMINDER:930922"));
        assertTrue(keys(inbox(employee, "INBOX")).contains("REMINDER:930923"));
        assertFalse(keys(inbox(employee, "INBOX")).contains("REMINDER:930922"));
        assertTrue(keys(inbox(admin, "EXCEPTION")).containsAll(List.of("REMINDER:930922", "REMINDER:930923")));
        assertEquals(200, read(warehouse, "REMINDER:930922").statusCode());
        assertEquals(404, read(warehouse, "REMINDER:930923").statusCode());

        db.update("UPDATE fams_inventory_item SET executor_account_id=930701 WHERE task_id=930920 AND asset_id=930921");
        assertFalse(keys(inbox(warehouse, "INBOX")).contains("REMINDER:930922"));
        assertFalse(keys(inbox(admin, "EXCEPTION")).contains("REMINDER:930922"));
        assertEquals(404, read(warehouse, "REMINDER:930922").statusCode());
        assertTrue(keys(inbox(employee, "INBOX")).contains("REMINDER:930923"));

        db.update("""
                UPDATE fams_inventory_task SET starts_at=UTC_DATE()-INTERVAL 10 DAY,due_at=UTC_DATE()-INTERVAL 2 DAY
                WHERE id=930920
                """);
        reminders.scan();
        var replacementNotice = db.queryForObject("""
                SELECT MAX(id) FROM fams_business_reminder
                WHERE business_type='INVENTORY_TASK' AND business_id=930920 AND stage='INVENTORY_COMPLETION'
                    AND reminder_type='OVERDUE' AND recipient_account_id=930701
                """, Long.class);
        assertTrue(keys(inbox(admin, "INBOX")).contains("REMINDER:" + replacementNotice));

        db.update("UPDATE fams_inventory_task SET task_status='TERMINATED' WHERE id=930920");
        assertFalse(keys(inbox(employee, "INBOX")).contains("REMINDER:930923"));
        assertEquals(404, read(admin, "REMINDER:930923").statusCode());
        assertEquals(1, receiptCount(930922));
    }

    private JsonNode inbox(Session session, String view) throws Exception {
        return ok(get(session, "/api/v1/messages?view=" + view));
    }

    private JsonNode miniInbox(String token) throws Exception {
        return ok(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/mini/messages?view=INBOX")).header("X-FAMS-Mini-Session", token)
                .GET().build(), HttpResponse.BodyHandlers.ofString()));
    }

    private static List<String> keys(JsonNode page) {
        var keys = new ArrayList<String>();
        for (var item : page.path("items")) {
            keys.add(item.path("kind").asText() + ":" + item.path("id").asText());
        }
        keys.sort(String::compareTo);
        return List.copyOf(keys);
    }

    private static String objectStage(JsonNode page, String id) {
        for (var item : page.path("items")) {
            if (id.equals(item.path("id").asText())) {
                return item.path("objectStage").asText();
            }
        }
        throw new IllegalStateException("missing message " + id);
    }

    private static List<String> readFlags(JsonNode page) {
        var keys = new ArrayList<String>();
        for (var item : page.path("items")) {
            if (item.path("read").asBoolean()) {
                keys.add(item.path("kind").asText() + ":" + item.path("id").asText());
            }
        }
        keys.sort(String::compareTo);
        return List.copyOf(keys);
    }

    private long receiptCount(long messageId) {
        return db.queryForObject("SELECT COUNT(*) FROM fams_message_receipt WHERE message_kind='REMINDER'"
                + " AND message_id=?", Long.class, messageId);
    }

    private HttpResponse<String> get(Session session, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Cookie", session.cookie()).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> read(Session session, String kindAndId) throws Exception {
        var parts = kindAndId.split(":");
        return http.send(post("/api/v1/messages/" + parts[0] + "/" + parts[1] + "/read", null, session),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> readWithoutKey(Session session, String kindAndId) throws Exception {
        var parts = kindAndId.split(":");
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/messages/" + parts[0] + "/" + parts[1] + "/read"))
                .header("Cookie", session.cookie()).header("X-CSRF-Token", session.csrf())
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest post(String path, String miniToken, Session session) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.noBody());
        if (miniToken != null) {
            builder.header("X-FAMS-Mini-Session", miniToken);
        } else {
            builder.header("Cookie", session.cookie()).header("X-CSRF-Token", session.csrf());
        }
        return builder.build();
    }

    private JsonNode ok(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private record Session(String cookie, String csrf) { }

    private Session login(String username) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/auth/login")).header("Content-Type", "application/json")
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
                + " VALUES (930700,'MSG','MSG','Messages','ACTIVE',0,NOW(3),NOW(3))");
        String[][] people = {{"930701", "msg1", "ASSET_ADMIN"}, {"930702", "msg2", "WAREHOUSE_OPERATOR"},
            {"930703", "msg3", "EMPLOYEE"}, {"930704", "msg4", "WAREHOUSE_OPERATOR"}};
        for (var person : people) {
            var id = Long.parseLong(person[0]);
            db.update("INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,organization_id,"
                    + "status,version,created_at,updated_at) VALUES (?,?,?,?,930700,'ACTIVE',0,NOW(3),NOW(3))",
                    id, person[1].toUpperCase(), person[1].toUpperCase(), "Message person");
            db.update("INSERT INTO fams_system_account (id,employee_id,username,username_normalized,password_hash,"
                    + "status,initial_password_required,created_at,updated_at)"
                    + " VALUES (?,?,?,?,?,'ENABLED',FALSE,NOW(3),NOW(3))",
                    id, id, person[1], person[1].toUpperCase(), hash);
            db.update("INSERT INTO fams_account_role (account_id,role_code,granted_at) VALUES (?,?,NOW(3))",
                    id, person[2]);
        }
        db.update("""
                INSERT INTO fams_business_parameter_version (id,version,status,effective_at,actor_account_id,
                    actor_username,snapshot_json,created_at)
                VALUES (930920,930920,'ACTIVE','2026-11-01 12:00:00',930701,'msg1',
                    JSON_OBJECT('timezone','UTC','workWeek',JSON_ARRAY('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY')),
                    NOW(3))
                """);
        db.update("""
                INSERT INTO fams_inbound_receipt (id,receipt_no,creator_account_id,creator_username_snapshot,
                    receipt_status,business_date,parameter_version_id,timezone_snapshot,admin_decision_deadline_workdays,
                    warehouse_claim_deadline_workdays,warehouse_first_execution_deadline_workdays,submitted_at,
                    admin_decision_due_at,warehouse_claim_due_at,warehouse_first_execution_due_at,
                    created_at,updated_at,created_by,updated_by)
                SELECT f.id,CONCAT('MSG-',f.id),930701,'msg1',f.status,'2026-11-01',930920,'UTC',2,1,2,
                    '2026-11-01 12:00:00','2026-11-08 12:00:00','2026-11-08 12:00:00','2026-11-08 12:00:00',
                    NOW(3),NOW(3),930701,930701
                FROM (SELECT 11 id,'PENDING_ADMIN_DECISION' status UNION ALL SELECT 12,'WAITING_WAREHOUSE_CLAIM'
                    UNION ALL SELECT 22,'WAREHOUSE_PROCESSING' UNION ALL SELECT 23,'WAREHOUSE_PROCESSING') f
                """);
        todo(930801, "ASSET_ADMIN", null, "INBOUND", 1, "APPROVAL");
        todo(930802, "WAREHOUSE_OPERATOR", null, "SCRAP", 2, "CLAIM");
        todo(930803, "EMPLOYEE", EMPLOYEE, "REQUISITION", 3, "RECEIPT");
        todo(930804, "TECH_ASSISTANT", null, "REPAIR", 4, "OPINION");
        todo(930805, "ASSET_ADMIN", null, "TRANSFER", 5, "OVERDUE");
        reminder(930901, "INBOUND", 11, "ADMIN_DECISION", "OVERDUE", "2026-11-08", "ASSET_ADMIN", null);
        reminder(930902, "INBOUND", 11, "ADMIN_DECISION", "OVERDUE", "2026-11-09", "ASSET_ADMIN", null);
        reminder(930911, "INBOUND", 12, "WAREHOUSE_CLAIM", "OVERDUE", "2026-11-08", "WAREHOUSE_OPERATOR", WAREHOUSE);
        reminder(930912, "INBOUND", 12, "WAREHOUSE_CLAIM", "OVERDUE", "2026-11-09", "WAREHOUSE_OPERATOR", WAREHOUSE);
    }

    private void todo(long id, String pool, Long assignee, String businessType, long businessId, String type) {
        db.update("INSERT INTO fams_todo (id,pool_type,assignee_account_id,business_type,business_id,todo_type,"
                + "todo_status,opened_at) VALUES (?,?,?,?,?,?,?,NOW(3))", id, pool, assignee, businessType,
                businessId, type, "OPEN");
    }

    private void reminder(long id, String businessType, long businessId, String stage, String type, String notice,
            String pool, Long recipient) {
        db.update("INSERT INTO fams_business_reminder (id,business_type,business_id,stage,deadline_at,notice_date,"
                + "reminder_business_date,reminder_type,recipient_pool_type,recipient_account_id,created_at)"
                + " VALUES (?,?,?,?,?,?,'2026-11-09',?,?,?,NOW(3))", id, businessType, businessId, stage,
                java.time.LocalDateTime.parse("2026-11-08T12:00:00"), notice, type, pool, recipient);
    }

    @SpringBootApplication(scanBasePackages = "com.fams")
    static class TestApplication { }
}
