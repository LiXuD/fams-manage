package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = {AuthHttpTest.TestApplication.class, MiniSessionHttpTest.Configuration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.profiles.active=fams-product",
            "spring.flyway.locations=classpath:db/migration/d6_04",
            "spring.session.jdbc.initialize-schema=never",
            "mybatis.configuration.map-underscore-to-camel-case=true",
            "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef"})
@EnabledIfSystemProperty(named = "d6.04.runtime", matches = "true")
class MiniSessionHttpTest {

    private static final long ACCOUNT = 910001;
    private static final Instant NOW = Instant.parse("2026-10-05T02:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicReference<Instant> TIME = new AtomicReference<>(NOW);
    private static final AtomicBoolean PROVIDER_FAILS = new AtomicBoolean();
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MiniSessionService sessions;
    @Autowired
    private AuthApplicationService auth;
    @Autowired
    private AuthPayloadFingerprint fingerprint;
    @Autowired
    private Clock clock;
    @Autowired
    private TransactionTemplate transactions;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        registry.add("spring.datasource.url", settings::url);
        registry.add("spring.datasource.username", settings::username);
        registry.add("spring.datasource.password", settings::password);
    }

    @BeforeEach
    void seedVerifiedBindingFixture() {
        TIME.set(NOW);
        PROVIDER_FAILS.set(false);
        jdbc.update("""
                INSERT INTO fams_organization (id, code, code_normalized, name, status, version, created_at, updated_at)
                VALUES (?, 'MINITEST', 'MINITEST', 'Mini test', 'ACTIVE', 0, ?, ?)
                """, ACCOUNT, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO fams_employee (id, employee_no, employee_no_normalized, name, organization_id,
                    status, version, created_at, updated_at)
                VALUES (?, 'MINITEST', 'MINITEST', 'Mini test', ?, 'ACTIVE', 0, ?, ?)
                """, ACCOUNT, ACCOUNT, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO fams_system_account (id, employee_id, username, username_normalized, password_hash,
                    status, initial_password_required, auth_epoch, authorization_version, created_at, updated_at)
                VALUES (?, ?, 'mini-test', 'MINI-TEST', 'not-a-login-hash', 'ENABLED', FALSE, 3, 7, ?, ?)
                """, ACCOUNT, ACCOUNT, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("INSERT INTO fams_account_role (account_id, role_code, granted_at) VALUES (?, 'EMPLOYEE', ?)",
                ACCOUNT, Timestamp.from(NOW));
        binding("verified-openid");
    }

    @AfterEach
    void cleanupOwnedFixture() {
        jdbc.update("DELETE FROM fams_mini_session WHERE account_id = ?", ACCOUNT);
        jdbc.update("DELETE FROM fams_mini_binding WHERE account_id = ?", ACCOUNT);
        jdbc.update("DELETE FROM fams_security_event WHERE account_id = ?", ACCOUNT);
        jdbc.update("DELETE FROM fams_account_role WHERE account_id = ?", ACCOUNT);
        jdbc.update("DELETE FROM fams_system_account WHERE id = ?", ACCOUNT);
        jdbc.update("DELETE FROM fams_employee WHERE id = ?", ACCOUNT);
        jdbc.update("DELETE FROM fams_organization WHERE id = ?", ACCOUNT);
    }

    @Test
    void realHttpPersistsOnlyDigestsAndRecreatedServiceCanReadAndLogout() throws Exception {
        String first = loginToken("bound");
        String second = loginToken("bound");
        assertNotEquals(first, second);
        assertEquals(43, first.length());
        assertEquals(200, request("GET", "/api/v1/mini/auth/session", null, first).statusCode());
        assertEquals(401, request("GET", "/api/v1/auth/me", null, first).statusCode());
        assertEquals(401, request("GET", "/api/v1/mini/auth/session", null, null).statusCode());
        assertEquals(401, request("GET", "/api/v1/mini/auth/session", null, "openid").statusCode());
        String stored = jdbc.queryForObject("SELECT token_hash FROM fams_mini_session LIMIT 1", String.class);
        assertNotEquals(first, stored);
        assertTrue(stored.matches("[0-9a-f]{64}"));
        assertFalse(jdbc.queryForObject("SELECT identity_hash FROM fams_mini_binding WHERE account_id = ?",
                String.class, ACCOUNT).contains("verified-openid"));
        var recreated = new MiniSessionService(jdbc, auth, fingerprint, clock);
        transactions.executeWithoutResult(ignored -> assertEquals(ACCOUNT, recreated.requireCurrent(first).account().id()));
        assertEquals(204, request("POST", "/api/v1/mini/auth/logout", null, first).statusCode());
        assertEquals(401, request("GET", "/api/v1/mini/auth/session", null, first).statusCode());
        assertEquals(200, request("GET", "/api/v1/mini/auth/session", null, second).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM fams_security_event WHERE account_id = ? "
                + "AND event_type = 'MINI_SESSION_REVOKED'", Integer.class, ACCOUNT));
    }

    @Test
    void unboundAndUnavailableExchangeCannotIssueCredentials() throws Exception {
        var unavailableSms = request("POST", "/api/v1/mini/auth/binding-challenges",
                "{\"code\":\"bound\",\"employeeNo\":\"MINITEST\",\"phone\":\"13800138000\","
                        + "\"deviceId\":\"00000000-0000-4000-8000-000000000001\",\"action\":\"BIND\"}", null);
        assertEquals(503, unavailableSms.statusCode());
        assertEquals("MINI_SMS_UNAVAILABLE", JSON.readTree(unavailableSms.body()).path("code").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM fams_mini_binding_challenge", Integer.class));
        var response = request("POST", "/api/v1/mini/auth/login", "{\"code\":\"unbound\"}", null);
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        JsonNode body = JSON.readTree(response.body());
        assertEquals("UNBOUND", body.path("bindingStatus").asText());
        assertTrue(body.path("sessionToken").isNull());
        assertTrue(body.path("accountId").isNull());
        PROVIDER_FAILS.set(true);
        assertEquals(503, request("POST", "/api/v1/mini/auth/login", "{\"code\":\"bound\"}", null).statusCode());
        assertEquals(400, request("POST", "/api/v1/mini/auth/login", "{\"code\":\" \"}", null).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM fams_mini_session", Integer.class));
    }

    @Test
    void expiryBoundariesAndActivityCannotExtendAbsoluteLifetime() throws Exception {
        String token = loginToken("bound");
        TIME.set(NOW.plusSeconds(1800));
        assertEquals(401, request("GET", "/api/v1/mini/auth/session", null, token).statusCode());
        TIME.set(NOW);
        token = loginToken("bound");
        for (int i = 1; i <= 16; i++) {
            TIME.set(NOW.plusSeconds(i * 1700L));
            assertEquals(200, request("GET", "/api/v1/mini/auth/session", null, token).statusCode());
        }
        assertEquals(NOW.plusSeconds(28800), jdbc.queryForObject(
                "SELECT MAX(idle_expires_at) FROM fams_mini_session", Timestamp.class).toInstant());
        TIME.set(NOW.plusSeconds(28800));
        assertEquals(401, request("GET", "/api/v1/mini/auth/session", null, token).statusCode());
    }

    @Test
    void revokeRebindDisableAndAuthorizationChangesRejectOldCredentials() throws Exception {
        String old = loginToken("bound");
        jdbc.update("UPDATE fams_mini_binding SET revoked_at = ?, version = version + 1 WHERE account_id = ?",
                Timestamp.from(NOW), ACCOUNT);
        assertEquals(401, request("GET", "/api/v1/mini/auth/session", null, old).statusCode());
        assertEquals("UNBOUND", JSON.readTree(request("POST", "/api/v1/mini/auth/login",
                "{\"code\":\"bound\"}", null).body()).path("bindingStatus").asText());
        binding("new-openid");
        String current = loginToken("rebound");
        assertEquals(401, request("GET", "/api/v1/mini/auth/session", null, old).statusCode());
        jdbc.update("UPDATE fams_mini_binding SET version = version + 1 WHERE account_id = ? AND revoked_at IS NULL", ACCOUNT);
        assertEquals(401, request("GET", "/api/v1/mini/auth/session", null, current).statusCode());
        current = loginToken("rebound");
        jdbc.update("UPDATE fams_system_account SET authorization_version = authorization_version + 1 WHERE id = ?", ACCOUNT);
        assertEquals(401, request("GET", "/api/v1/mini/auth/session", null, current).statusCode());
        current = loginToken("rebound");
        jdbc.update("UPDATE fams_employee SET status = 'DISABLED' WHERE id = ?", ACCOUNT);
        assertEquals(401, request("GET", "/api/v1/mini/auth/session", null, current).statusCode());
        assertEquals(401, request("POST", "/api/v1/mini/auth/login", "{\"code\":\"rebound\"}", null).statusCode());
    }

    @Test
    void disableCompetingWithLoginNeverMakesIssuedSessionUsableAfterDisableCommits() throws Exception {
        CountDownLatch disabled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var disabling = pool.submit(() -> transactions.executeWithoutResult(ignored -> {
                jdbc.queryForObject("SELECT id FROM fams_system_account WHERE id = ? FOR UPDATE", Long.class, ACCOUNT);
                jdbc.update("UPDATE fams_system_account SET status = 'DISABLED', auth_epoch = auth_epoch + 1 WHERE id = ?", ACCOUNT);
                disabled.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertTrue(disabled.await(5, TimeUnit.SECONDS));
            var login = pool.submit(() -> request("POST", "/api/v1/mini/auth/login", "{\"code\":\"bound\"}", null));
            release.countDown();
            disabling.get(5, TimeUnit.SECONDS);
            assertEquals(401, login.get(5, TimeUnit.SECONDS).statusCode());
        } finally {
            release.countDown();
        }
    }

    @Test
    void currentStatusRejectsASessionIssuedBeforeDisableCommits() throws Exception {
        String token = loginToken("bound");
        jdbc.update("UPDATE fams_system_account SET auth_epoch = auth_epoch + 1 WHERE id = ?", ACCOUNT);
        assertThrows(AuthOperationException.class, () -> sessions.requireCurrent(token));
        jdbc.update("UPDATE fams_system_account SET status = 'LOCKED' WHERE id = ?", ACCOUNT);
        assertEquals(401, request("POST", "/api/v1/mini/auth/login", "{\"code\":\"bound\"}", null).statusCode());
    }

    private void binding(String openId) {
        jdbc.update("INSERT INTO fams_mini_binding (account_id, identity_hash, created_at) VALUES (?, ?, ?)", ACCOUNT,
                sessions.identityHash(new MiniWechatIdentityPort.Identity("test-app", openId)), Timestamp.from(NOW));
    }

    private String loginToken(String code) throws Exception {
        HttpResponse<String> response = request("POST", "/api/v1/mini/auth/login", "{\"code\":\"" + code + "\"}", null);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
        return JSON.readTree(response.body()).path("sessionToken").asText();
    }

    private HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (token != null) {
            request.header(MiniAuthController.HEADER, token);
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration
    static class Configuration {
        @Bean
        @Primary
        Clock miniTestClock() {
            return new Clock() {
                @Override
                public java.time.ZoneId getZone() {
                    return ZoneOffset.UTC;
                }
                @Override
                public Clock withZone(java.time.ZoneId zone) {
                    return this;
                }
                @Override
                public Instant instant() {
                    return TIME.get();
                }
            };
        }

        @Bean
        MiniWechatIdentityPort testOnlyWechatExchange() {
            return code -> {
                if (PROVIDER_FAILS.get()) {
                    throw new IllegalStateException("Provider unavailable");
                }
                return new MiniWechatIdentityPort.Identity("test-app", switch (code) {
                    case "bound" -> "verified-openid";
                    case "rebound" -> "new-openid";
                    default -> "not-bound";
                });
            };
        }
    }
}
