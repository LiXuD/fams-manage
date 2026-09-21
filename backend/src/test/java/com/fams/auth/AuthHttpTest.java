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
        jdbcTemplate.update("DELETE FROM fams_warehouse");
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
    void systemAdministratorManagesAccountsRolesAndLifecycleWithOptimisticWrites() throws Exception {
        Instant now = Instant.parse("2026-09-21T02:00:00Z");
        Timestamp timestamp = Timestamp.from(now);
        jdbcTemplate.update(
                "INSERT INTO fams_employee "
                        + "(id, employee_no, employee_no_normalized, name, organization_id, status, version, "
                        + "created_at, updated_at) VALUES (2, 'E002', 'E002', 'Bob', 1, 'ACTIVE', 0, ?, ?)",
                timestamp,
                timestamp);

        HttpResponse<String> login = login();
        String cookie = cookiePair(login.headers().firstValue("Set-Cookie").orElseThrow());
        String csrf = JSON.readTree(login.body()).path("csrfToken").asText();
        HttpResponse<String> systemAdministratorRole = request(
                "GET", "/api/v1/role-configurations/SYSTEM_ADMIN", null, cookie, Map.of());
        assertEquals(200, systemAdministratorRole.statusCode(), systemAdministratorRole.body());
        JsonNode systemAdministratorRoleBody = JSON.readTree(systemAdministratorRole.body());
        assertFalse(systemAdministratorRoleBody.path("systemAdminBusinessPermissions").asBoolean());
        assertFalse(systemAdministratorRoleBody.path("permissions").toString().contains("ASSET_LEDGER_READ"));
        String createBody = "{\"username\":\"bob\",\"employee\":{\"id\":\"2\",\"version\":0},"
                + "\"initialPassword\":\"initial-password-value-2026\"}";

        HttpResponse<String> created = request(
                "POST",
                "/api/v1/system-accounts",
                createBody,
                cookie,
                Map.of(
                        "Idempotency-Key", "account-create-0001",
                        "X-CSRF-Token", csrf));
        assertEquals(201, created.statusCode(), created.body());
        JsonNode createdBody = JSON.readTree(created.body());
        String accountId = createdBody.path("id").asText();
        assertEquals("bob", createdBody.path("username").asText());
        assertEquals(0L, createdBody.path("version").asLong());
        assertTrue(createdBody.path("requiresInitialPasswordChange").asBoolean());

        HttpResponse<String> replay = request(
                "POST",
                "/api/v1/system-accounts",
                createBody,
                cookie,
                Map.of(
                        "Idempotency-Key", "account-create-0001",
                        "X-CSRF-Token", csrf));
        assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(accountId, JSON.readTree(replay.body()).path("id").asText());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fams_system_account WHERE username_normalized = 'BOB'",
                Integer.class));

        HttpResponse<String> list = request(
                "GET", "/api/v1/system-accounts?page=1&pageSize=20", null, cookie, Map.of());
        assertEquals(200, list.statusCode(), list.body());
        assertEquals(2, JSON.readTree(list.body()).path("total").asInt());

        HttpResponse<String> granted = request(
                "POST",
                "/api/v1/system-accounts/" + accountId + "/roles",
                "{\"role\":\"ASSET_ADMIN\",\"reason\":\"Assign asset administrator\"}",
                cookie,
                Map.of(
                        "Idempotency-Key", "account-role-grant-0001",
                        "X-CSRF-Token", csrf,
                        "If-Match-Version", "0"));
        assertEquals(200, granted.statusCode(), granted.body());
        assertEquals(1L, JSON.readTree(granted.body()).path("version").asLong());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fams_account_role WHERE account_id = ? AND role_code = 'ASSET_ADMIN'",
                Integer.class,
                Long.parseLong(accountId)));

        HttpResponse<String> targetLogin = request(
                "POST",
                "/api/v1/auth/login",
                "{\"username\":\"bob\",\"password\":\"initial-password-value-2026\"}",
                null,
                Map.of());
        assertEquals(200, targetLogin.statusCode(), targetLogin.body());
        String targetCookie = cookiePair(targetLogin.headers().firstValue("Set-Cookie").orElseThrow());

        HttpResponse<String> revoked = request(
                "POST",
                "/api/v1/system-accounts/" + accountId + "/roles/revoke",
                "{\"role\":\"ASSET_ADMIN\",\"reason\":\"Remove temporary assignment\"}",
                cookie,
                Map.of(
                        "Idempotency-Key", "account-role-revoke-0001",
                        "X-CSRF-Token", csrf,
                        "If-Match-Version", "1"));
        assertEquals(200, revoked.statusCode(), revoked.body());
        assertEquals(2L, JSON.readTree(revoked.body()).path("version").asLong());
        assertEquals(401, request(
                "GET", "/api/v1/auth/me", null, targetCookie, Map.of()).statusCode());

        HttpResponse<String> targetLoginAfterRevoke = request(
                "POST",
                "/api/v1/auth/login",
                "{\"username\":\"bob\",\"password\":\"initial-password-value-2026\"}",
                null,
                Map.of());
        String targetCookieAfterRevoke = cookiePair(
                targetLoginAfterRevoke.headers().firstValue("Set-Cookie").orElseThrow());

        HttpResponse<String> reset = request(
                "POST",
                "/api/v1/system-accounts/" + accountId + "/initial-password-reset",
                "{\"newInitialPassword\":\"replacement-password-2026\","
                        + "\"reason\":\"User requested reset\"}",
                cookie,
                Map.of(
                        "Idempotency-Key", "account-password-reset-0001",
                        "X-CSRF-Token", csrf,
                        "If-Match-Version", "2"));
        assertEquals(200, reset.statusCode(), reset.body());
        assertEquals(3L, JSON.readTree(reset.body()).path("version").asLong());
        assertEquals(401, request(
                "GET", "/api/v1/auth/me", null, targetCookieAfterRevoke, Map.of()).statusCode());

        HttpResponse<String> disabled = request(
                "POST",
                "/api/v1/system-accounts/" + accountId + "/status",
                "{\"action\":\"DISABLE\",\"reason\":\"Employment ended\"}",
                cookie,
                Map.of(
                        "Idempotency-Key", "account-disable-0001",
                        "X-CSRF-Token", csrf,
                        "If-Match-Version", "3"));
        assertEquals(200, disabled.statusCode(), disabled.body());
        assertEquals("DISABLED", JSON.readTree(disabled.body()).path("status").asText());
        assertEquals(4L, JSON.readTree(disabled.body()).path("version").asLong());

        HttpResponse<String> stale = request(
                "POST",
                "/api/v1/system-accounts/" + accountId + "/status",
                "{\"action\":\"ENABLE\",\"reason\":\"Stale administrator screen\"}",
                cookie,
                Map.of(
                        "Idempotency-Key", "account-enable-stale-0001",
                        "X-CSRF-Token", csrf,
                        "If-Match-Version", "3"));
        assertEquals(409, stale.statusCode(), stale.body());
        assertEquals("AUTH_VERSION_CONFLICT", JSON.readTree(stale.body()).path("code").asText());
    }

    @Test
    void businessRoleCannotUseSystemAccountAdministration() throws Exception {
        Instant now = Instant.parse("2026-09-21T02:00:00Z");
        Timestamp timestamp = Timestamp.from(now);
        String businessPassword = "business-role-password-2026";
        jdbcTemplate.update(
                "INSERT INTO fams_employee "
                        + "(id, employee_no, employee_no_normalized, name, organization_id, status, version, "
                        + "created_at, updated_at) VALUES (2, 'E002', 'E002', 'Bob', 1, 'ACTIVE', 0, ?, ?)",
                timestamp,
                timestamp);
        jdbcTemplate.update(
                "INSERT INTO fams_system_account "
                        + "(id, employee_id, username, username_normalized, password_hash, status, "
                        + "initial_password_required, failed_attempts, auth_epoch, authorization_version, "
                        + "version, created_at, updated_at) "
                        + "VALUES (2, 2, 'bob', 'BOB', ?, 'ENABLED', FALSE, 0, 0, 0, 0, ?, ?)",
                new BCryptPasswordEncoder(4).encode(businessPassword),
                timestamp,
                timestamp);
        jdbcTemplate.update(
                "INSERT INTO fams_account_role (account_id, role_code, granted_at) "
                        + "VALUES (2, 'ASSET_ADMIN', ?)",
                timestamp);

        HttpResponse<String> login = request(
                "POST",
                "/api/v1/auth/login",
                "{\"username\":\"bob\",\"password\":\"" + businessPassword + "\"}",
                null,
                Map.of());
        assertEquals(200, login.statusCode(), login.body());
        String cookie = cookiePair(login.headers().firstValue("Set-Cookie").orElseThrow());

        HttpResponse<String> forbidden = request(
                "GET", "/api/v1/system-accounts", null, cookie, Map.of());
        assertEquals(403, forbidden.statusCode(), forbidden.body());
        assertEquals("AUTH_FORBIDDEN", JSON.readTree(forbidden.body()).path("code").asText());
        assertEquals("AUTHORIZATION_REJECTED", jdbcTemplate.queryForObject(
                "SELECT event_type FROM fams_security_event WHERE account_id = 2 ORDER BY id DESC LIMIT 1",
                String.class));
    }

    @Test
    void authorizationReplacementCreatesImmutableGenerationsAndInvalidatesTargetSessions() throws Exception {
        Instant now = Instant.parse("2026-09-21T02:00:00Z");
        Timestamp timestamp = Timestamp.from(now);
        String businessPassword = "business-role-password-2026";
        jdbcTemplate.update(
                "INSERT INTO fams_employee "
                        + "(id, employee_no, employee_no_normalized, name, organization_id, status, version, "
                        + "created_at, updated_at) VALUES (2, 'E002', 'E002', 'Bob', 1, 'ACTIVE', 0, ?, ?)",
                timestamp,
                timestamp);
        jdbcTemplate.update(
                "INSERT INTO fams_system_account "
                        + "(id, employee_id, username, username_normalized, password_hash, status, "
                        + "initial_password_required, failed_attempts, auth_epoch, authorization_version, "
                        + "version, created_at, updated_at) "
                        + "VALUES (2, 2, 'bob', 'BOB', ?, 'ENABLED', FALSE, 0, 0, 0, 0, ?, ?)",
                new BCryptPasswordEncoder(4).encode(businessPassword),
                timestamp,
                timestamp);
        jdbcTemplate.update(
                "INSERT INTO fams_account_role (account_id, role_code, granted_at) "
                        + "VALUES (2, 'ASSET_ADMIN', ?)",
                timestamp);
        jdbcTemplate.update(
                "INSERT INTO fams_warehouse "
                        + "(id, code, code_normalized, name, status, version, created_at, updated_at) "
                        + "VALUES (10, 'WH-01', 'WH-01', 'Main warehouse', 'ACTIVE', 3, ?, ?)",
                timestamp,
                timestamp);

        HttpResponse<String> administratorLogin = login();
        String administratorCookie = cookiePair(
                administratorLogin.headers().firstValue("Set-Cookie").orElseThrow());
        String administratorCsrf = JSON.readTree(administratorLogin.body()).path("csrfToken").asText();
        HttpResponse<String> targetLogin = request(
                "POST",
                "/api/v1/auth/login",
                "{\"username\":\"bob\",\"password\":\"" + businessPassword + "\"}",
                null,
                Map.of());
        String targetCookie = cookiePair(targetLogin.headers().firstValue("Set-Cookie").orElseThrow());
        String replaceBody = "{\"dataScopes\":["
                + "{\"scopeType\":\"SPECIFIED_ORGANIZATIONS\","
                + "\"organizations\":[{\"id\":\"1\",\"version\":0}],\"warehouses\":[]},"
                + "{\"scopeType\":\"SPECIFIED_WAREHOUSES\",\"organizations\":[],"
                + "\"warehouses\":[{\"id\":\"10\",\"version\":3}]}],"
                + "\"fieldPermissions\":[\"EMPLOYEE_PHONE\",\"BULK_EXPORT\"],"
                + "\"reason\":\"Assign reviewed operating scope\"}";

        HttpResponse<String> unconfigured = request(
                "GET", "/api/v1/system-accounts/2/authorization/ASSET_ADMIN",
                null, administratorCookie, Map.of());
        assertEquals(200, unconfigured.statusCode(), unconfigured.body());
        assertEquals(0L, JSON.readTree(unconfigured.body()).path("version").asLong());
        assertEquals("REVOKED", JSON.readTree(unconfigured.body()).path("status").asText());

        Map<String, String> invalidHeaders = Map.of(
                "Idempotency-Key", "authorization-invalid-0001",
                "X-CSRF-Token", administratorCsrf,
                "If-Match-Version", "0");
        assertEquals(409, request(
                "PATCH", "/api/v1/system-accounts/2/authorization/WAREHOUSE_OPERATOR",
                replaceBody, administratorCookie, invalidHeaders).statusCode());
        assertEquals(409, request(
                "PATCH", "/api/v1/system-accounts/2/authorization/ASSET_ADMIN",
                replaceBody.replace("[{\"id\":\"1\",\"version\":0}]", "[]"),
                administratorCookie, invalidHeaders).statusCode());
        assertEquals(409, request(
                "PATCH", "/api/v1/system-accounts/2/authorization/ASSET_ADMIN",
                replaceBody.replace("{\"scopeType\":\"SPECIFIED_WAREHOUSES\"",
                        "{\"scopeType\":\"SPECIFIED_ORGANIZATIONS\""),
                administratorCookie, invalidHeaders).statusCode());

        HttpResponse<String> staleReference = request(
                "PATCH",
                "/api/v1/system-accounts/2/authorization/ASSET_ADMIN",
                replaceBody.replace("\"version\":3", "\"version\":2"),
                administratorCookie,
                Map.of(
                        "Idempotency-Key", "authorization-stale-ref-0001",
                        "X-CSRF-Token", administratorCsrf,
                        "If-Match-Version", "0"));
        assertEquals(409, staleReference.statusCode(), staleReference.body());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fams_account_authorization WHERE account_id = 2",
                Integer.class));

        HttpResponse<String> systemAdminEscalation = request(
                "PATCH",
                "/api/v1/system-accounts/1/authorization/SYSTEM_ADMIN",
                replaceBody,
                administratorCookie,
                Map.of(
                        "Idempotency-Key", "authorization-admin-scope-0001",
                        "X-CSRF-Token", administratorCsrf,
                        "If-Match-Version", "0"));
        assertEquals(409, systemAdminEscalation.statusCode(), systemAdminEscalation.body());

        HttpResponse<String> replaced = request(
                "PATCH",
                "/api/v1/system-accounts/2/authorization/ASSET_ADMIN",
                replaceBody,
                administratorCookie,
                Map.of(
                        "Idempotency-Key", "authorization-replace-0001",
                        "X-CSRF-Token", administratorCsrf,
                        "If-Match-Version", "0"));
        assertEquals(200, replaced.statusCode(), replaced.body());
        JsonNode replacedBody = JSON.readTree(replaced.body());
        assertEquals("ACTIVE", replacedBody.path("status").asText());
        assertEquals(1L, replacedBody.path("version").asLong());
        assertEquals(2, replacedBody.path("dataScopes").size());
        assertTrue(replacedBody.path("fieldPermissions").toString().contains("EMPLOYEE_PHONE"));
        assertEquals(401, request(
                "GET", "/api/v1/auth/permissions", null, targetCookie, Map.of()).statusCode());

        HttpResponse<String> replay = request(
                "PATCH",
                "/api/v1/system-accounts/2/authorization/ASSET_ADMIN",
                replaceBody,
                administratorCookie,
                Map.of(
                        "Idempotency-Key", "authorization-replace-0001",
                        "X-CSRF-Token", administratorCsrf,
                        "If-Match-Version", "0"));
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(replacedBody.path("id").asText(), JSON.readTree(replay.body()).path("id").asText());

        HttpResponse<String> keyConflict = request(
                "PATCH",
                "/api/v1/system-accounts/2/authorization/ASSET_ADMIN",
                replaceBody.replace("BULK_EXPORT", "REPAIR_COST"),
                administratorCookie,
                Map.of(
                        "Idempotency-Key", "authorization-replace-0001",
                        "X-CSRF-Token", administratorCsrf,
                        "If-Match-Version", "0"));
        assertEquals(409, keyConflict.statusCode(), keyConflict.body());
        assertEquals(
                "AUTH_IDEMPOTENCY_CONFLICT",
                JSON.readTree(keyConflict.body()).path("code").asText());

        HttpResponse<String> current = request(
                "GET",
                "/api/v1/system-accounts/2/authorization/ASSET_ADMIN",
                null,
                administratorCookie,
                Map.of());
        assertEquals(200, current.statusCode(), current.body());
        assertEquals(1L, JSON.readTree(current.body()).path("version").asLong());

        HttpResponse<String> refreshedTargetLogin = request(
                "POST",
                "/api/v1/auth/login",
                "{\"username\":\"bob\",\"password\":\"" + businessPassword + "\"}",
                null,
                Map.of());
        String refreshedTargetCookie = cookiePair(
                refreshedTargetLogin.headers().firstValue("Set-Cookie").orElseThrow());
        HttpResponse<String> permissions = request(
                "GET", "/api/v1/auth/permissions", null, refreshedTargetCookie, Map.of());
        assertEquals(200, permissions.statusCode(), permissions.body());
        JsonNode permissionBody = JSON.readTree(permissions.body());
        assertTrue(permissionBody.path("dataScopes").toString().contains("SPECIFIED_ORGANIZATIONS"));
        assertTrue(permissionBody.path("dataScopes").toString().contains("SPECIFIED_WAREHOUSES"));
        assertTrue(permissionBody.path("dataScopes").toString().contains("\"id\":\"1\""));
        assertTrue(permissionBody.path("dataScopes").toString().contains("\"id\":\"10\""));
        assertTrue(permissionBody.path("fieldPermissions").toString().contains("BULK_EXPORT"));

        jdbcTemplate.update("UPDATE fams_warehouse SET version = 4 WHERE id = 10");
        HttpResponse<String> staleWarehousePermission = request(
                "GET", "/api/v1/auth/permissions", null, refreshedTargetCookie, Map.of());
        assertEquals(200, staleWarehousePermission.statusCode(), staleWarehousePermission.body());
        assertFalse(JSON.readTree(staleWarehousePermission.body()).path("dataScopes")
                .toString().contains("\"id\":\"10\""));

        HttpResponse<String> revoked = request(
                "POST",
                "/api/v1/system-accounts/2/authorization/ASSET_ADMIN/revoke",
                "{\"reason\":\"Temporary scope expired\"}",
                administratorCookie,
                Map.of(
                        "Idempotency-Key", "authorization-revoke-0001",
                        "X-CSRF-Token", administratorCsrf,
                        "If-Match-Version", "1"));
        assertEquals(200, revoked.statusCode(), revoked.body());
        JsonNode revokedBody = JSON.readTree(revoked.body());
        assertEquals("REVOKED", revokedBody.path("status").asText());
        assertEquals(2L, revokedBody.path("version").asLong());
        assertEquals(0, revokedBody.path("dataScopes").size());
        assertEquals(0, revokedBody.path("fieldPermissions").size());
        assertEquals(401, request(
                "GET", "/api/v1/auth/permissions", null, refreshedTargetCookie, Map.of()).statusCode());
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fams_account_authorization WHERE account_id = 2 "
                        + "AND role_code = 'ASSET_ADMIN'",
                Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fams_account_authorization WHERE account_id = 2 "
                        + "AND role_code = 'ASSET_ADMIN' AND superseded_at IS NULL",
                Integer.class));
        assertEquals(409, request(
                "POST", "/api/v1/system-accounts/2/authorization/ASSET_ADMIN/revoke",
                "{\"reason\":\"Already revoked\"}", administratorCookie,
                Map.of("Idempotency-Key", "authorization-revoke-again-0001",
                        "X-CSRF-Token", administratorCsrf, "If-Match-Version", "2"))
                .statusCode());
    }

    @Test
    void administrationRejectsInvalidRelationsStatesAndPasswordsWithoutPartialWrites() throws Exception {
        Instant now = Instant.parse("2026-09-21T02:00:00Z");
        Timestamp timestamp = Timestamp.from(now);
        jdbcTemplate.update(
                "INSERT INTO fams_employee "
                        + "(id, employee_no, employee_no_normalized, name, organization_id, status, version, "
                        + "created_at, updated_at) VALUES (2, 'E002', 'E002', 'Bob', 1, 'ACTIVE', 3, ?, ?)",
                timestamp,
                timestamp);
        HttpResponse<String> login = login();
        String cookie = cookiePair(login.headers().firstValue("Set-Cookie").orElseThrow());
        String csrf = JSON.readTree(login.body()).path("csrfToken").asText();
        Map<String, String> createHeaders = Map.of(
                "Idempotency-Key", "admin-negative-create-0001",
                "X-CSRF-Token", csrf);
        String createBody = "{\"username\":\"bob\",\"employee\":{\"id\":\"2\",\"version\":3},"
                + "\"initialPassword\":\"initial-password-value-2026\"}";

        assertEquals(404, request("GET", "/api/v1/system-accounts/999", null, cookie, Map.of())
                .statusCode());
        assertEquals(200, request("GET", "/api/v1/role-configurations", null, cookie, Map.of())
                .statusCode());
        assertEquals(404, request(
                "POST", "/api/v1/system-accounts",
                createBody.replace("\"id\":\"2\"", "\"id\":\"999\""),
                cookie, createHeaders).statusCode());
        assertEquals(409, request(
                "POST", "/api/v1/system-accounts",
                createBody.replace("\"version\":3", "\"version\":2"),
                cookie, createHeaders).statusCode());
        assertEquals(400, request(
                "POST", "/api/v1/system-accounts",
                createBody.replace("initial-password-value-2026", "short"),
                cookie, createHeaders).statusCode());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fams_system_account WHERE employee_id = 2", Integer.class));

        HttpResponse<String> created = request(
                "POST", "/api/v1/system-accounts", createBody, cookie, createHeaders);
        assertEquals(201, created.statusCode(), created.body());
        String accountId = JSON.readTree(created.body()).path("id").asText();
        assertEquals(409, request(
                "POST", "/api/v1/system-accounts",
                createBody.replace("initial-password-value-2026", "another-password-value-2026"),
                cookie, createHeaders).statusCode());
        assertEquals(409, request(
                "POST", "/api/v1/system-accounts", createBody, cookie,
                Map.of("Idempotency-Key", "admin-negative-create-0002", "X-CSRF-Token", csrf))
                .statusCode());

        String statusPath = "/api/v1/system-accounts/" + accountId + "/status";
        assertEquals(409, request(
                "POST", statusPath, "{\"action\":\"ENABLE\",\"reason\":\"Already enabled\"}",
                cookie, Map.of("Idempotency-Key", "admin-enable-noop-0001",
                        "X-CSRF-Token", csrf, "If-Match-Version", "0")).statusCode());
        assertEquals(409, request(
                "POST", statusPath, "{\"action\":\"UNLOCK\",\"reason\":\"Not locked\"}",
                cookie, Map.of("Idempotency-Key", "admin-unlock-noop-0001",
                        "X-CSRF-Token", csrf, "If-Match-Version", "0")).statusCode());
        jdbcTemplate.update("UPDATE fams_system_account SET status = 'LOCKED' WHERE id = ?",
                Long.parseLong(accountId));
        HttpResponse<String> unlocked = request(
                "POST", statusPath, "{\"action\":\"UNLOCK\",\"reason\":\"Identity checked\"}",
                cookie, Map.of("Idempotency-Key", "admin-unlock-valid-0001",
                        "X-CSRF-Token", csrf, "If-Match-Version", "0"));
        assertEquals(200, unlocked.statusCode(), unlocked.body());
        assertEquals("ENABLED", JSON.readTree(unlocked.body()).path("status").asText());

        String rolePath = "/api/v1/system-accounts/" + accountId + "/roles";
        String roleBody = "{\"role\":\"ASSET_ADMIN\",\"reason\":\"Review assignment\"}";
        Map<String, String> roleHeaders = Map.of(
                "Idempotency-Key", "admin-role-valid-0001",
                "X-CSRF-Token", csrf,
                "If-Match-Version", "1");
        assertEquals(200, request("POST", rolePath, roleBody, cookie, roleHeaders).statusCode());
        assertEquals(409, request(
                "POST", rolePath, roleBody, cookie,
                Map.of("Idempotency-Key", "admin-role-duplicate-0001",
                        "X-CSRF-Token", csrf, "If-Match-Version", "2")).statusCode());
        assertEquals(409, request(
                "POST", rolePath + "/revoke",
                "{\"role\":\"WAREHOUSE_OPERATOR\",\"reason\":\"Not assigned\"}",
                cookie, Map.of("Idempotency-Key", "admin-role-missing-0001",
                        "X-CSRF-Token", csrf, "If-Match-Version", "2")).statusCode());
        assertEquals(400, request(
                "POST", "/api/v1/system-accounts/" + accountId + "/initial-password-reset",
                "{\"newInitialPassword\":\"short\",\"reason\":\"Too short\"}",
                cookie, Map.of("Idempotency-Key", "admin-weak-reset-0001",
                        "X-CSRF-Token", csrf, "If-Match-Version", "2")).statusCode());
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
