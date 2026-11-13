package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
        reminder(930913, "LOST_REPORT", 12, "LOSS_REVIEW", "SMS_FALLBACK",
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
            {"930703", "msg3", "EMPLOYEE"}};
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
        todo(930801, "ASSET_ADMIN", null, "INBOUND", 1, "APPROVAL");
        todo(930802, "WAREHOUSE_OPERATOR", null, "SCRAP", 2, "CLAIM");
        todo(930803, "EMPLOYEE", EMPLOYEE, "REQUISITION", 3, "RECEIPT");
        todo(930804, "TECH_ASSISTANT", null, "REPAIR", 4, "OPINION");
        todo(930805, "ASSET_ADMIN", null, "TRANSFER", 5, "OVERDUE");
        reminder(930901, "INBOUND", 11, "ADMIN_DECISION", "OVERDUE", "2026-11-08", "ASSET_ADMIN", null);
        reminder(930902, "INBOUND", 11, "ADMIN_DECISION", "OVERDUE", "2026-11-09", "ASSET_ADMIN", null);
        reminder(930911, "LOST_REPORT", 12, "LOSS_REVIEW", "OVERDUE", "2026-11-08", "WAREHOUSE_OPERATOR", WAREHOUSE);
        reminder(930912, "LOST_REPORT", 12, "LOSS_REVIEW", "OVERDUE", "2026-11-09", "WAREHOUSE_OPERATOR", WAREHOUSE);
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
                java.sql.Timestamp.valueOf("2026-11-08 12:00:00"), notice, type, pool, recipient);
    }

    @SpringBootApplication(scanBasePackages = "com.fams")
    static class TestApplication { }
}
