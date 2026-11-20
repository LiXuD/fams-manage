package com.fams.masterdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * P25 system settings over real HTTP: the merged operation log is read-only, role and field authorization are
 * revalidated inside every query, sensitive detail is masked, and the notification channel chain is version-guarded.
 */
@SpringBootTest(classes = SystemSettingsHttpRuntimeCase.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.profiles.active=fams-product", "spring.flyway.locations=classpath:db/migration/d6_04",
    "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
    "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra",
    "fams.attachments.cleanup-enabled=false"
})
@EnabledIfSystemProperty(named = "d6.p25.settings.runtime", matches = "true")
class SystemSettingsHttpRuntimeCase {
    private static final String PASSWORD = "owned-p25-settings-password";
    private static final long SYSTEM_ADMIN = 932001;
    private static final long AUDITOR = 932002;
    private static final long EMPLOYEE = 932003;
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired NotificationChannelConfigPort channels;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("D6_05_TEST_DATASOURCE_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("D6_05_TEST_DATASOURCE_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("D6_05_TEST_DATASOURCE_PASSWORD"));
    }

    @Test
    void operationLogAuthorizationMaskingPaginationAndChannelVersionChain() throws Exception {
        clean();
        seed();
        var system = login("p25sys");
        var auditor = login("p25audit");
        var employee = login("p25employee");

        // The log is closed to unrelated roles and to an audit role without the field grant.
        assertEquals(403, code(get(employee, "/operation-logs")));
        var denied = get(auditor, "/operation-logs");
        assertEquals(403, code(denied));
        assertEquals("AUTH_FORBIDDEN", json.readTree(denied.body()).path("code").asText());

        // Seed one business audit row and 25 security rows with sensitive detail inside a fixed window.
        db.update("""
                INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,action,resource_type,
                    resource_id,before_version,after_version,occurred_at,detail_json)
                VALUES (?,932001,'SYSTEM_ADMIN','ASSET_UPDATED','ASSET',932100,1,2,
                    '2027-01-01 00:00:00','{"remark":"ok","contactPhone":"13987654321"}')
                """, UUID.randomUUID().toString());
        for (int index = 0; index < 25; index++) {
            db.update("""
                    INSERT INTO fams_security_event (account_id,event_type,request_id,occurred_at,detail_json)
                    VALUES (932002,'PASSWORD_CHANGED',?,?,'{"contactPhone":"13812345678",
                        "initialPassword":"$2a$04$abcdefghijklmnopqrstuv","nested":{"token":"abc"}}')
                    """, UUID.randomUUID().toString(),
                    java.sql.Timestamp.from(java.time.Instant.parse(
                            "2026-11-01T00:%02d:00Z".formatted(index))));
        }

        // SYSTEM_ADMIN reads by role: merged projection, newest first, sensitive values masked.
        var merged = ok(get(system, "/operation-logs?page=1&pageSize=20"));
        assertTrue(merged.path("total").asLong() >= 26);
        var first = merged.path("items").get(0);
        assertEquals("BUSINESS", first.path("source").asText());
        assertEquals("ASSET_UPDATED", first.path("eventType").asText());
        assertEquals("SYSTEM_ADMIN", first.path("actorRole").asText());
        assertEquals("ASSET", first.path("resourceType").asText());
        assertEquals("139****4321", first.path("detail").path("contactPhone").asText());
        assertFalse(merged.toString().contains("13987654321"));

        String window = "/operation-logs?source=SECURITY&actorAccountId=932002"
                + "&occurredFrom=2026-11-01T00:00:00Z&occurredTo=2026-11-01T00:24:00Z";
        var pageOne = ok(get(system, window + "&page=1&pageSize=20"));
        assertEquals(25, pageOne.path("total").asLong());
        assertEquals(20, pageOne.path("items").size());
        assertEquals("2026-11-01T00:24:00Z", pageOne.path("items").get(0).path("occurredAt").asText());
        var masked = pageOne.path("items").get(0).path("detail");
        assertEquals("138****5678", masked.path("contactPhone").asText());
        assertEquals("***", masked.path("initialPassword").asText());
        assertEquals("***", masked.path("nested").path("token").asText());
        assertFalse(pageOne.toString().contains("13812345678"));
        assertFalse(pageOne.toString().contains("$2a$"));
        var pageTwo = ok(get(system, window + "&page=2&pageSize=20"));
        assertEquals(5, pageTwo.path("items").size());
        assertEquals("2026-11-01T00:00:00Z", pageTwo.path("items").get(4).path("occurredAt").asText());
        assertEquals(400, code(get(system, "/operation-logs?source=UNKNOWN")));

        // Granting the field permission invalidates the old session immediately; a fresh login can read.
        var grant = replaceAuthorization(system, 0, "[\"SYSTEM_OPERATION_LOG\"]");
        assertEquals(200, code(grant));
        assertEquals(1, ok(grant).path("version").asInt());
        assertEquals(401, code(get(auditor, "/operation-logs")));
        var grantedAuditor = login("p25audit");
        assertEquals(200, code(get(grantedAuditor, "/operation-logs")));
        assertEquals(403, code(get(grantedAuditor, "/notification-channels")));

        // Revocation ends the grant at once: the live session dies and a fresh login is denied again.
        assertEquals(200, code(revokeAuthorization(system, 1)));
        assertEquals(401, code(get(grantedAuditor, "/operation-logs")));
        var revokedAuditor = login("p25audit");
        assertEquals(403, code(get(revokedAuditor, "/operation-logs")));

        // A regrant works under a new generation, and disabling the account kills even a valid session.
        assertEquals(200, code(replaceAuthorization(system, 2, "[\"SYSTEM_OPERATION_LOG\"]")));
        var regranted = login("p25audit");
        assertEquals(200, code(get(regranted, "/operation-logs")));
        db.update("UPDATE fams_system_account SET status='DISABLED' WHERE id=?", AUDITOR);
        assertEquals(401, code(get(regranted, "/operation-logs")));
        db.update("UPDATE fams_system_account SET status='ENABLED' WHERE id=?", AUDITOR);

        // Channel configuration starts unconfigured at version zero and is administrator-only.
        var initial = ok(get(system, "/notification-channels"));
        assertEquals(0, initial.path("version").asInt());
        assertEquals("", initial.path("smsTemplate").asText());
        assertEquals("", initial.path("wechatTemplate").asText());
        assertEquals(403, code(get(employee, "/notification-channels")));
        assertEquals(400, code(replaceChannels(system, 0, "bad template!", "", UUID.randomUUID().toString())));
        assertEquals(403, code(http.send(HttpRequest.newBuilder(URI.create(base() + "/notification-channels"))
                .header("Content-Type", "application/json").header("Cookie", system.cookie())
                .header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match-Version", "0")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"smsTemplate\":\"SMS_P25_01\",\"wechatTemplate\":\"\"}"))
                .build(), HttpResponse.BodyHandlers.ofString())));

        // One replacement activates version one; a lost reply replays on the same key without a second row.
        String key = UUID.randomUUID().toString();
        var created = replaceChannels(system, 0, "SMS_P25_01", "WX_P25_01", key);
        assertEquals(201, code(created));
        assertEquals(1, json.readTree(created.body()).path("version").asInt());
        var replay = replaceChannels(system, 0, "SMS_P25_01", "WX_P25_01", key);
        assertEquals(201, code(replay));
        assertEquals(json.readTree(created.body()), json.readTree(replay.body()));
        assertEquals(1, count("fams_notification_channel_version"));
        assertEquals(409, code(replaceChannels(system, 0, "SMS_P25_02", "", key)));
        assertEquals(409, code(replaceChannels(system, 0, "SMS_P25_02", "", UUID.randomUUID().toString())));

        // Clearing a template is a legitimate new version; consumers read only the current ACTIVE row.
        var cleared = replaceChannels(system, 1, "", "WX_P25_02", UUID.randomUUID().toString());
        assertEquals(201, code(cleared));
        assertEquals(2, json.readTree(cleared.body()).path("version").asInt());
        assertEquals(2, count("fams_notification_channel_version"));
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM fams_notification_channel_version WHERE status='ACTIVE'", Long.class));
        assertEquals("", channels.currentTemplates().smsTemplate());
        assertEquals("WX_P25_02", channels.currentTemplates().wechatTemplate());

        // Every replacement is audited, and the audit trail itself is visible through the masked log.
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM fams_security_event"
                + " WHERE detail_json LIKE '%NOTIFICATION_CHANNEL_CONFIG_REPLACED%'", Long.class));
        assertTrue(ok(get(system, "/operation-logs?source=SECURITY&page=1&pageSize=20")).path("items")
                .valueStream().anyMatch(item -> "MASTER_DATA_CHANGED".equals(item.path("eventType").asText())));
    }

    private void clean() {
        db.update("DELETE FROM SPRING_SESSION_ATTRIBUTES");
        db.update("DELETE FROM SPRING_SESSION");
        db.update("DELETE FROM fams_account_authorization WHERE account_id BETWEEN 932000 AND 932999");
        db.update("DELETE FROM fams_account_role WHERE account_id BETWEEN 932000 AND 932999");
        db.update("DELETE FROM fams_audit_event WHERE actor_account_id BETWEEN 932000 AND 932999");
        db.update("DELETE FROM fams_security_event WHERE account_id BETWEEN 932000 AND 932999");
        db.update("DELETE FROM fams_idempotency_record WHERE resource_type='NOTIFICATION_CHANNEL_CONFIG'");
        db.update("DELETE FROM fams_system_account WHERE id BETWEEN 932000 AND 932999");
        db.update("DELETE FROM fams_employee WHERE id BETWEEN 932000 AND 932999");
        db.update("DELETE FROM fams_organization WHERE id BETWEEN 932000 AND 932999");
        db.update("UPDATE fams_notification_channel_state"
                + " SET current_version=0, current_version_id=NULL WHERE singleton_id=1");
        db.update("DELETE FROM fams_notification_channel_version");
    }

    private void seed() {
        var hash = new BCryptPasswordEncoder(4).encode(PASSWORD);
        db.update("INSERT INTO fams_organization (id,code,code_normalized,name,status,version,created_at,updated_at)"
                + " VALUES (932000,'P25','P25','P25','ACTIVE',0,NOW(3),NOW(3))");
        String[][] people = {{"932001", "p25sys", "SYSTEM_ADMIN"}, {"932002", "p25audit", "AUDIT_READ_ONLY"},
            {"932003", "p25employee", "EMPLOYEE"}};
        for (var person : people) {
            var id = Long.parseLong(person[0]);
            db.update("INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,organization_id,"
                    + "status,version,created_at,updated_at) VALUES (?,?,?,?,932000,'ACTIVE',0,NOW(3),NOW(3))",
                    id, person[1].toUpperCase(), person[1].toUpperCase(), "P25 person " + person[2]);
            db.update("INSERT INTO fams_system_account (id,employee_id,username,username_normalized,password_hash,"
                    + "status,initial_password_required,created_at,updated_at)"
                    + " VALUES (?,?,?,?,?,'ENABLED',FALSE,NOW(3),NOW(3))",
                    id, id, person[1], person[1].toUpperCase(), hash);
            db.update("INSERT INTO fams_account_role (account_id,role_code,granted_at) VALUES (?,?,NOW(3))",
                    id, person[2]);
        }
    }

    private HttpResponse<String> replaceAuthorization(Session session, long version, String fieldPermissions)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(
                base() + "/system-accounts/" + AUDITOR + "/authorization/AUDIT_READ_ONLY"))
                .header("Content-Type", "application/json").header("Cookie", session.cookie())
                .header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match-Version", Long.toString(version))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                        "{\"dataScopes\":[],\"fieldPermissions\":" + fieldPermissions
                                + ",\"reason\":\"P25 log authorization verification\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> revokeAuthorization(Session session, long version) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(
                base() + "/system-accounts/" + AUDITOR + "/authorization/AUDIT_READ_ONLY/revoke"))
                .header("Content-Type", "application/json").header("Cookie", session.cookie())
                .header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match-Version", Long.toString(version))
                .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"P25 revocation verification\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> replaceChannels(Session session, long version, String sms, String wechat,
            String key) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/notification-channels"))
                .header("Content-Type", "application/json").header("Cookie", session.cookie())
                .header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", key)
                .header("If-Match-Version", Long.toString(version))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("smsTemplate", sms, "wechatTemplate", wechat))))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(Session session, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Cookie", session.cookie()).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private long count(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private String base() {
        return "http://127.0.0.1:" + port + "/api/v1";
    }

    private int code(HttpResponse<String> response) {
        return response.statusCode();
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

    @SpringBootApplication(scanBasePackages = "com.fams")
    static class TestApplication { }
}
