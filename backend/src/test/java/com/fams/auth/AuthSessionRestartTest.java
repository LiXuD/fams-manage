package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import com.fams.contract.probe.ContractProbeApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@EnabledIfSystemProperty(named = "d6.04.runtime", matches = "true")
class AuthSessionRestartTest {

    private static final String INITIAL_PASSWORD = "correct-horse-battery-staple";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void jdbcSessionSurvivesARealApplicationContextRestart() throws Exception {
        AuthHttpTest.DatabaseSettings database =
                AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        String cookie;

        try (ConfigurableApplicationContext first = start(database)) {
            seed(first.getBean(JdbcTemplate.class));
            int port = localPort(first);
            HttpResponse<String> login = request(
                    port,
                    "/api/v1/auth/login",
                    "POST",
                    "{\"username\":\"alice\",\"password\":\"" + INITIAL_PASSWORD + "\"}",
                    null);
            assertEquals(200, login.statusCode());
            assertTrue(JSON.readTree(login.body()).path("requiresInitialPasswordChange").asBoolean());
            String setCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
            cookie = setCookie.substring(0, setCookie.indexOf(';'));
        }

        try (ConfigurableApplicationContext second = start(database)) {
            HttpResponse<String> current = request(
                    localPort(second),
                    "/api/v1/auth/me",
                    "GET",
                    null,
                    cookie);
            assertEquals(200, current.statusCode());
            JsonNode body = JSON.readTree(current.body());
            assertEquals("alice", body.path("username").asText());
            assertEquals("101", body.path("id").asText());
        }
    }

    private ConfigurableApplicationContext start(AuthHttpTest.DatabaseSettings database) {
        return new SpringApplicationBuilder(ContractProbeApplication.class)
                .profiles("fams-product")
                .properties(Map.of(
                        "server.port", "0",
                        "spring.datasource.url", database.url(),
                        "spring.datasource.username", database.username(),
                        "spring.datasource.password", database.password(),
                        "spring.flyway.locations", "classpath:db/migration/d6_04",
                        "spring.session.jdbc.initialize-schema", "never",
                        "fams.security.idempotency-hmac-key", "test-idempotency-hmac-key-0123456789abcdef"))
                .run();
    }

    private void seed(JdbcTemplate jdbcTemplate) {
        Instant now = Instant.parse("2026-09-21T02:00:00Z");
        Timestamp timestamp = Timestamp.from(now);
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
        jdbcTemplate.update(
                "INSERT INTO fams_organization "
                        + "(id, code, code_normalized, name, status, version, created_at, updated_at) "
                        + "VALUES (101, 'RESTART', 'RESTART', 'Restart organization', 'ACTIVE', 0, ?, ?)",
                timestamp,
                timestamp);
        jdbcTemplate.update(
                "INSERT INTO fams_employee "
                        + "(id, employee_no, employee_no_normalized, name, organization_id, status, version, "
                        + "created_at, updated_at) VALUES (101, 'ER101', 'ER101', 'Alice', 101, 'ACTIVE', 0, ?, ?)",
                timestamp,
                timestamp);
        jdbcTemplate.update(
                "INSERT INTO fams_system_account "
                        + "(id, employee_id, username, username_normalized, password_hash, status, "
                        + "initial_password_required, failed_attempts, auth_epoch, authorization_version, "
                        + "version, created_at, updated_at) "
                        + "VALUES (101, 101, 'alice', 'ALICE', ?, 'ENABLED', TRUE, 0, 3, 7, 0, ?, ?)",
                new BCryptPasswordEncoder(4).encode(INITIAL_PASSWORD),
                timestamp,
                timestamp);
        jdbcTemplate.update(
                "INSERT INTO fams_account_role (account_id, role_code, granted_at) "
                        + "VALUES (101, 'SYSTEM_ADMIN', ?)",
                timestamp);
    }

    private int localPort(ConfigurableApplicationContext context) {
        WebApplicationContext webContext = (WebApplicationContext) context;
        return Integer.parseInt(webContext.getEnvironment().getProperty("local.server.port"));
    }

    private HttpResponse<String> request(
            int port,
            String path,
            String method,
            String body,
            String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
