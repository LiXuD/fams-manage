package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        classes = AuthHttpTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=fams-product",
            "spring.flyway.locations=classpath:db/migration/d6_04",
            "spring.session.jdbc.initialize-schema=never",
            "mybatis.configuration.map-underscore-to-camel-case=true",
            "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef"
        })
@EnabledIfSystemProperty(named = "d6.04.runtime", matches = "true")
class AuthHttpTest {

    private static final String INITIAL_PASSWORD = "correct-horse-battery-staple";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TASK_DATABASE_URL = Pattern.compile(
            "jdbc:mysql://127\\.0\\.0\\.1:(\\d{4,5})/"
                    + "(fams-d6-04-db-[0-9a-f]{16})"
                    + "\\?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureTaskDatabase(DynamicPropertyRegistry registry) {
        DatabaseSettings settings = DatabaseSettings.fromEnvironment(System.getenv());
        registry.add("spring.datasource.url", settings::url);
        registry.add("spring.datasource.username", settings::username);
        registry.add("spring.datasource.password", settings::password);
    }

    @BeforeEach
    void seedAccount() {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION");
        jdbcTemplate.update("DELETE FROM fams_security_event");
        jdbcTemplate.update("DELETE FROM fams_idempotency_record");
        jdbcTemplate.update("DELETE FROM fams_account_field_permission");
        jdbcTemplate.update("DELETE FROM fams_account_scope_warehouse");
        jdbcTemplate.update("DELETE FROM fams_account_scope_organization");
        jdbcTemplate.update("DELETE FROM fams_account_data_scope");
        jdbcTemplate.update("DELETE FROM fams_account_authorization");
        jdbcTemplate.update("DELETE FROM fams_account_role");
        jdbcTemplate.update("DELETE FROM fams_system_account");
        jdbcTemplate.update("DELETE FROM fams_employee");
        jdbcTemplate.update("DELETE FROM fams_organization");

        Instant now = Instant.parse("2026-09-21T02:00:00Z");
        Timestamp timestamp = Timestamp.from(now);
        jdbcTemplate.update(
                "INSERT INTO fams_organization "
                        + "(id, code, code_normalized, name, status, version, created_at, updated_at) "
                        + "VALUES (1, 'ROOT', 'ROOT', 'Root organization', 'ACTIVE', 0, ?, ?)",
                timestamp,
                timestamp);
        jdbcTemplate.update(
                "INSERT INTO fams_employee "
                        + "(id, employee_no, employee_no_normalized, name, organization_id, status, version, "
                        + "created_at, updated_at) VALUES (1, 'E001', 'E001', 'Alice', 1, 'ACTIVE', 0, ?, ?)",
                timestamp,
                timestamp);
        jdbcTemplate.update(
                "INSERT INTO fams_system_account "
                        + "(id, employee_id, username, username_normalized, password_hash, status, "
                        + "initial_password_required, failed_attempts, auth_epoch, authorization_version, "
                        + "version, created_at, updated_at) "
                        + "VALUES (1, 1, 'alice', 'ALICE', ?, 'ENABLED', TRUE, 0, 3, 7, 0, ?, ?)",
                new BCryptPasswordEncoder(4).encode(INITIAL_PASSWORD),
                timestamp,
                timestamp);
        jdbcTemplate.update(
                "INSERT INTO fams_account_role (account_id, role_code, granted_at) "
                        + "VALUES (1, 'SYSTEM_ADMIN', ?)",
                timestamp);
    }

    @Test
    void realCookieCsrfPasswordRotationAndLogoutFlow() throws Exception {
        HttpResponse<String> login = request(
                "POST",
                "/api/v1/auth/login",
                "{\"username\":\"alice\",\"password\":\"" + INITIAL_PASSWORD + "\"}",
                null,
                Map.of());

        assertEquals(200, login.statusCode());
        String loginSetCookie = login.headers().firstValue("Set-Cookie").orElse(null);
        assertNotNull(loginSetCookie);
        assertTrue(loginSetCookie.startsWith("FAMS_SESSION="));
        assertTrue(loginSetCookie.contains("Path=/api"));
        assertTrue(loginSetCookie.contains("Secure"));
        assertTrue(loginSetCookie.contains("HttpOnly"));
        assertTrue(loginSetCookie.contains("SameSite=Lax"));
        String oldCookie = cookiePair(loginSetCookie);
        JsonNode loginBody = JSON.readTree(login.body());
        String csrf = loginBody.path("csrfToken").asText();
        assertTrue(csrf.length() >= 16);
        assertTrue(loginBody.path("requiresInitialPasswordChange").asBoolean());

        assertEquals(200, request("GET", "/api/v1/auth/me", null, oldCookie, Map.of()).statusCode());

        HttpResponse<String> missingCsrf = request(
                "POST",
                "/api/v1/auth/password",
                passwordChangeBody(),
                oldCookie,
                Map.of("Idempotency-Key", "password-change-0001"));
        assertEquals(403, missingCsrf.statusCode());
        assertEquals("AUTH_CSRF_INVALID", JSON.readTree(missingCsrf.body()).path("code").asText());

        HttpResponse<String> wrongCsrf = request(
                "POST",
                "/api/v1/auth/password",
                passwordChangeBody(),
                oldCookie,
                Map.of(
                        "Idempotency-Key", "password-change-0001",
                        "X-CSRF-Token", "wrong-token-value"));
        assertEquals(403, wrongCsrf.statusCode());

        HttpResponse<String> changed = request(
                "POST",
                "/api/v1/auth/password",
                passwordChangeBody(),
                oldCookie,
                Map.of(
                        "Idempotency-Key", "password-change-0001",
                        "X-CSRF-Token", csrf));
        assertEquals(200, changed.statusCode());
        String changedSetCookie = changed.headers().firstValue("Set-Cookie").orElse(null);
        assertNotNull(changedSetCookie);
        String newCookie = cookiePair(changedSetCookie);
        assertNotEquals(oldCookie, newCookie);
        JsonNode changedBody = JSON.readTree(changed.body());
        assertFalse(changedBody.path("requiresInitialPasswordChange").asBoolean());

        assertEquals(401, request("GET", "/api/v1/auth/me", null, oldCookie, Map.of()).statusCode());
        HttpResponse<String> permissions = request(
                "GET", "/api/v1/auth/permissions", null, newCookie, Map.of());
        assertEquals(200, permissions.statusCode());
        JsonNode permissionBody = JSON.readTree(permissions.body());
        assertTrue(permissionBody.path("permissions").toString().contains("ACCOUNT_ADMIN"));
        assertFalse(permissionBody.path("permissions").toString().contains("ASSET_LEDGER_READ"));

        HttpResponse<String> replay = request(
                "POST",
                "/api/v1/auth/password",
                passwordChangeBody(),
                newCookie,
                Map.of(
                        "Idempotency-Key", "password-change-0001",
                        "X-CSRF-Token", changedBody.path("csrfToken").asText()));
        assertEquals(200, replay.statusCode());
        String replayCookie = cookiePair(replay.headers().firstValue("Set-Cookie").orElseThrow());
        JsonNode replayBody = JSON.readTree(replay.body());
        Long authEpoch = jdbcTemplate.queryForObject(
                "SELECT auth_epoch FROM fams_system_account WHERE id = 1",
                Long.class);
        assertEquals(4L, authEpoch);

        HttpResponse<String> conflict = request(
                "POST",
                "/api/v1/auth/password",
                "{\"currentPassword\":\"" + INITIAL_PASSWORD
                        + "\",\"newPassword\":\"different-password-2026\"}",
                replayCookie,
                Map.of(
                        "Idempotency-Key", "password-change-0001",
                        "X-CSRF-Token", replayBody.path("csrfToken").asText()));
        assertEquals(409, conflict.statusCode());
        assertEquals("AUTH_IDEMPOTENCY_CONFLICT", JSON.readTree(conflict.body()).path("code").asText());

        HttpResponse<String> logout = request(
                "POST",
                "/api/v1/auth/logout",
                null,
                replayCookie,
                Map.of("X-CSRF-Token", replayBody.path("csrfToken").asText()));
        assertEquals(204, logout.statusCode());
        String logoutCookie = logout.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(logoutCookie.startsWith("FAMS_SESSION="));
        assertTrue(logoutCookie.contains("Max-Age=0"));
        assertEquals(401, request("GET", "/api/v1/auth/me", null, replayCookie, Map.of()).statusCode());

        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fams_security_event WHERE account_id = 1",
                Integer.class);
        assertTrue(eventCount != null && eventCount >= 5);
        String eventDetails = jdbcTemplate.queryForObject(
                "SELECT GROUP_CONCAT(detail_json) FROM fams_security_event WHERE account_id = 1",
                String.class);
        assertNotNull(eventDetails);
        assertFalse(eventDetails.contains(INITIAL_PASSWORD));
        assertFalse(eventDetails.contains(csrf));
        assertFalse(eventDetails.contains(oldCookie));
    }

    @Test
    void accountDisableAndAuthorizationChangeInvalidateOldSessionsOnNextRequest() throws Exception {
        HttpResponse<String> firstLogin = login();
        String firstCookie = cookiePair(firstLogin.headers().firstValue("Set-Cookie").orElseThrow());
        jdbcTemplate.update("UPDATE fams_employee SET status = 'DISABLED' WHERE id = 1");
        assertEquals(401, request("GET", "/api/v1/auth/me", null, firstCookie, Map.of()).statusCode());

        jdbcTemplate.update("UPDATE fams_employee SET status = 'ACTIVE' WHERE id = 1");
        HttpResponse<String> accountDisableLogin = login();
        String accountDisableCookie = cookiePair(
                accountDisableLogin.headers().firstValue("Set-Cookie").orElseThrow());
        jdbcTemplate.update(
                "UPDATE fams_system_account SET status = 'DISABLED', auth_epoch = auth_epoch + 1 WHERE id = 1");
        assertEquals(401, request("GET", "/api/v1/auth/me", null, accountDisableCookie, Map.of()).statusCode());

        jdbcTemplate.update("UPDATE fams_system_account SET status = 'ENABLED' WHERE id = 1");
        HttpResponse<String> secondLogin = login();
        String secondCookie = cookiePair(secondLogin.headers().firstValue("Set-Cookie").orElseThrow());
        jdbcTemplate.update(
                "UPDATE fams_system_account SET authorization_version = authorization_version + 1 WHERE id = 1");
        assertEquals(401, request("GET", "/api/v1/auth/permissions", null, secondCookie, Map.of()).statusCode());
    }

    @Test
    void realDatabaseUsesOneLoginFailureShapeAndPersistsTheTemporaryLock() throws Exception {
        JsonNode unknown = JSON.readTree(request(
                "POST",
                "/api/v1/auth/login",
                "{\"username\":\"unknown\",\"password\":\"wrong-password\"}",
                null,
                Map.of()).body());
        JsonNode wrong = JSON.readTree(request(
                "POST",
                "/api/v1/auth/login",
                "{\"username\":\"alice\",\"password\":\"wrong-password\"}",
                null,
                Map.of()).body());

        jdbcTemplate.update("UPDATE fams_system_account SET status = 'DISABLED' WHERE id = 1");
        JsonNode disabled = JSON.readTree(login().body());
        jdbcTemplate.update(
                "UPDATE fams_system_account SET status = 'LOCKED', locked_until = DATE_ADD(UTC_TIMESTAMP(3), "
                        + "INTERVAL 15 MINUTE) WHERE id = 1");
        JsonNode locked = JSON.readTree(login().body());

        assertEquals("AUTHENTICATION_FAILED", unknown.path("code").asText());
        assertEquals(unknown.path("code").asText(), wrong.path("code").asText());
        assertEquals(unknown.path("code").asText(), disabled.path("code").asText());
        assertEquals(unknown.path("code").asText(), locked.path("code").asText());
        assertEquals("Authentication failed.", unknown.path("message").asText());

        jdbcTemplate.update(
                "UPDATE fams_system_account SET status = 'ENABLED', failed_attempts = 4, locked_until = NULL "
                        + "WHERE id = 1");
        HttpResponse<String> fifthFailure = request(
                "POST",
                "/api/v1/auth/login",
                "{\"username\":\"alice\",\"password\":\"wrong-password\"}",
                null,
                Map.of());
        assertEquals(401, fifthFailure.statusCode());
        Map<String, Object> persisted = jdbcTemplate.queryForMap(
                "SELECT status, failed_attempts, locked_until FROM fams_system_account WHERE id = 1");
        assertEquals("LOCKED", persisted.get("status"));
        assertEquals(5, ((Number) persisted.get("failed_attempts")).intValue());
        assertNotNull(persisted.get("locked_until"));
    }

    private HttpResponse<String> login() throws Exception {
        return request(
                "POST",
                "/api/v1/auth/login",
                "{\"username\":\"alice\",\"password\":\"" + INITIAL_PASSWORD + "\"}",
                null,
                Map.of());
    }

    private String passwordChangeBody() {
        return "{\"currentPassword\":\"" + INITIAL_PASSWORD
                + "\",\"newPassword\":\"new-password-value-2026\"}";
    }

    private HttpResponse<String> request(
            String method,
            String path,
            String body,
            String cookie,
            Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        headers.forEach(builder::header);
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String cookiePair(String setCookie) {
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    record DatabaseSettings(String url, String username, String password) {

        static DatabaseSettings fromEnvironment(Map<String, String> environment) {
            String url = required(environment, "D6_04_TEST_DATASOURCE_URL");
            Matcher matcher = TASK_DATABASE_URL.matcher(url);
            if (!matcher.matches()) {
                throw new IllegalStateException(
                        "D6_04_TEST_DATASOURCE_URL must identify a task-owned random database");
            }
            String username = required(environment, "D6_04_TEST_DATASOURCE_USERNAME");
            if (!"d6_04_runtime".equals(username)) {
                throw new IllegalStateException(
                        "D6_04_TEST_DATASOURCE_USERNAME must use the task-owned account");
            }
            String password = required(environment, "D6_04_TEST_DATASOURCE_PASSWORD");
            if (password.length() < 32) {
                throw new IllegalStateException(
                        "D6_04_TEST_DATASOURCE_PASSWORD must be an unpredictable task credential");
            }
            return new DatabaseSettings(url, username, password);
        }

        private static String required(Map<String, String> environment, String key) {
            String value = environment.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(key + " is required before the test context starts");
            }
            return value;
        }
    }

    @SpringBootApplication(scanBasePackages = "com.fams")
    static class TestApplication {
        public static void main(String[] args) {
            SpringApplication.run(TestApplication.class, args);
        }
    }
}
