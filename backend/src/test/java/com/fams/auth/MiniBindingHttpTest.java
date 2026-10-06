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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = {AuthHttpTest.TestApplication.class, MiniBindingHttpTest.Configuration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.profiles.active=fams-product",
            "spring.flyway.locations=classpath:db/migration/d6_04",
            "spring.session.jdbc.initialize-schema=never",
            "mybatis.configuration.map-underscore-to-camel-case=true",
            "fams.mini.binding-support-text=Contact your corporate administrator.",
            "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef"})
@EnabledIfSystemProperty(named = "d6.04.runtime", matches = "true")
class MiniBindingHttpTest {
    private static final long EMPLOYEE = 920001;
    private static final long ADMIN = 920002;
    private static final String PHONE = "13800138000";
    private static final String PASSWORD = "mini-binding-test-password";
    private static final String DEVICE = "00000000-0000-4000-8000-000000000001";
    private static final Instant NOW = Instant.parse("2026-10-05T02:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicReference<Instant> TIME = new AtomicReference<>(NOW);
    private static final AtomicReference<String> OTP = new AtomicReference<>();
    private static final AtomicReference<Path> SMS_FILE = new AtomicReference<>();
    private static final AtomicBoolean SMS_FAILS = new AtomicBoolean();
    private static final AtomicBoolean WECHAT_FAILS = new AtomicBoolean();
    private final HttpClient http = HttpClient.newHttpClient();
    @Value("${local.server.port}")
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuthPayloadFingerprint fingerprint;
    @Autowired
    private MiniBindingService bindings;
    @Autowired
    private MiniSessionService sessions;
    @Autowired
    private MiniPhoneCorrectionService corrections;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        registry.add("spring.datasource.url", settings::url);
        registry.add("spring.datasource.username", settings::username);
        registry.add("spring.datasource.password", settings::password);
    }

    @BeforeEach
    void seedOwnedEmployees() throws Exception {
        TIME.set(NOW);
        SMS_FAILS.set(false);
        WECHAT_FAILS.set(false);
        OTP.set(null);
        jdbc.update("""
                INSERT INTO fams_organization (id, code, code_normalized, name, status, version, created_at, updated_at)
                VALUES (?, 'BINDINGTEST', 'BINDINGTEST', 'Binding test', 'ACTIVE', 0, ?, ?)
                """, EMPLOYEE, Timestamp.from(NOW), Timestamp.from(NOW));
        for (long id : new long[]{EMPLOYEE, ADMIN}) {
            String no = "BIND" + id;
            jdbc.update("""
                    INSERT INTO fams_employee (id, employee_no, employee_no_normalized, name, organization_id,
                        mobile_lookup_hash, status, version, created_at, updated_at)
                    VALUES (?, ?, ?, 'Binding test', ?, ?, 'ACTIVE', 0, ?, ?)
                    """, id, no, no, EMPLOYEE, phoneHash(id == ADMIN ? "13700137000" : PHONE),
                    Timestamp.from(NOW), Timestamp.from(NOW));
            jdbc.update("""
                    INSERT INTO fams_system_account (id, employee_id, username, username_normalized, password_hash,
                        status, initial_password_required, auth_epoch, authorization_version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'ENABLED', FALSE, 0, 0, ?, ?)
                    """, id, id, no, no, new BCryptPasswordEncoder(4).encode(PASSWORD),
                    Timestamp.from(NOW), Timestamp.from(NOW));
            jdbc.update("INSERT INTO fams_account_role (account_id, role_code, granted_at) VALUES (?, ?, ?)",
                    id, id == ADMIN ? "SYSTEM_ADMIN" : "EMPLOYEE", Timestamp.from(NOW));
        }
    }

    @AfterEach
    void cleanupOwnedFixtures() {
        jdbc.update("DELETE FROM fams_mini_binding_challenge WHERE account_id IN (?, ?)", EMPLOYEE, ADMIN);
        jdbc.update("DELETE FROM fams_mini_session WHERE account_id IN (?, ?)", EMPLOYEE, ADMIN);
        jdbc.update("DELETE FROM fams_mini_binding WHERE account_id IN (?, ?)", EMPLOYEE, ADMIN);
        jdbc.update("DELETE FROM fams_mini_phone_receipt WHERE actor_id IN (?, ?)", EMPLOYEE, ADMIN);
        jdbc.update("DELETE FROM fams_mini_unbind_receipt WHERE actor_id IN (?, ?)", EMPLOYEE, ADMIN);
        jdbc.update("DELETE FROM fams_security_event WHERE account_id IN (?, ?)", EMPLOYEE, ADMIN);
        jdbc.update("DELETE FROM fams_account_role WHERE account_id IN (?, ?)", EMPLOYEE, ADMIN);
        jdbc.update("DELETE FROM fams_system_account WHERE id IN (?, ?)", EMPLOYEE, ADMIN);
        jdbc.update("DELETE FROM fams_employee WHERE id IN (?, ?)", EMPLOYEE, ADMIN);
        jdbc.update("DELETE FROM fams_organization WHERE id = ?", EMPLOYEE);
        for (long id : new long[]{EMPLOYEE, ADMIN}) {
            jdbc.update("DELETE FROM fams_mini_binding_limit WHERE subject_hash = ?",
                    fingerprint.fingerprint("mini-employee-limit/v1", Long.toString(id)));
        }
        jdbc.update("DELETE FROM fams_mini_binding_limit WHERE subject_hash = ?",
                fingerprint.fingerprint("mini-device/v1", DEVICE));
    }

    @Test
    void completeHttpBindingReplayLoginAndSelfUnbindPersistsRevocation() throws Exception {
        String challenge = challenge("first", "BIND");
        String otp = OTP.get();
        var bound = verify(challenge, otp, "first", "binding-key-000001");
        assertEquals(200, bound.statusCode(), bound.body());
        assertEquals(bound.body(), verify(challenge, otp, "first", "binding-key-000001").body());
        assertEquals(409, verify(challenge, otp, "first", "binding-key-000002").statusCode());
        assertEquals(1, activeBindings());
        String token = token("first");
        assertEquals(200, send("GET", "/api/v1/mini/auth/session", null,
                Map.of(MiniAuthController.HEADER, token)).statusCode());
        String stored = jdbc.queryForObject("SELECT otp_hash FROM fams_mini_binding_challenge WHERE account_id = ?",
                String.class, EMPLOYEE);
        assertEquals(64, stored.length());
        assertNotEquals(otp, stored);
        String events = jdbc.queryForList("SELECT detail_json FROM fams_security_event WHERE account_id = ?",
                String.class, EMPLOYEE).toString();
        assertFalse(events.contains(otp));
        assertFalse(events.contains(PHONE));
        var headers = Map.of(MiniAuthController.HEADER, token, "Idempotency-Key", "self-unbind-key-0001",
                "If-Match-Version", "0");
        assertEquals(204, send("POST", "/api/v1/mini/auth/unbind",
                Map.of("reason", "Device replaced", "verificationMethod", "SELF_SESSION"), headers).statusCode());
        assertEquals(0, activeBindings());
        assertEquals(401, send("GET", "/api/v1/mini/auth/session", null,
                Map.of(MiniAuthController.HEADER, token)).statusCode());
        assertEquals(409, verify(challenge, otp, "first", "binding-key-000001").statusCode());
    }

    @Test
    void registeredPhoneMustMatchAndOtpExpiresAtFiveMinutes() throws Exception {
        var wrongPhone = send("POST", "/api/v1/mini/auth/binding-challenges", Map.of("employeeNo", "BIND" + EMPLOYEE,
                "phone", "13900139000", "deviceId", DEVICE, "code", "first", "action", "BIND"), Map.of());
        assertEquals(400, wrongPhone.statusCode());
        assertEquals(null, OTP.get());
        TIME.set(NOW.plusSeconds(60));
        String challenge = challenge("first", "BIND");
        TIME.set(NOW.plusSeconds(360));
        assertEquals(400, verify(challenge, OTP.get(), "first", "binding-key-000001").statusCode());
        assertEquals(0, activeBindings());
    }

    @Test
    void fiveFailuresLockEmployeeForThirtyMinutesAndSurviveHttpRejections() throws Exception {
        String challenge = challenge("first", "BIND");
        String otp = OTP.get();
        String wrong = otp.equals("000000") ? "000001" : "000000";
        for (int i = 0; i < 5; i++) {
            assertEquals(400, verify(challenge, wrong, "first", "binding-key-000001").statusCode());
        }
        assertEquals(429, verify(challenge, otp, "first", "binding-key-000001").statusCode());
        assertEquals(5, jdbc.queryForObject("SELECT failures FROM fams_mini_binding_limit WHERE subject_hash = ?",
                Integer.class, fingerprint.fingerprint("mini-employee-limit/v1", Long.toString(EMPLOYEE))));
        TIME.set(NOW.plusSeconds(1800));
        String renewed = challenge("first", "BIND");
        assertEquals(200, verify(renewed, OTP.get(), "first", "binding-key-000002").statusCode());
    }

    @Test
    void persistentCooldownAndDailyQuotaApplyEvenWhenSmsFails() throws Exception {
        SMS_FAILS.set(true);
        for (int i = 0; i < 10; i++) {
            TIME.set(NOW.plusSeconds(i * 60L));
            assertEquals(503, issue("first", "BIND").statusCode());
            assertEquals(429, issue("first", "BIND").statusCode());
        }
        TIME.set(NOW.plusSeconds(600));
        assertEquals(429, issue("first", "BIND").statusCode());
        assertEquals(10, jdbc.queryForObject("SELECT COUNT(*) FROM fams_mini_binding_challenge "
                + "WHERE account_id = ? AND state = 'FAILED'", Integer.class, EMPLOYEE));
        TIME.set(NOW.plusSeconds(86400));
        SMS_FAILS.set(false);
        assertEquals(200, issue("first", "BIND").statusCode());
    }

    @Test
    void freshWechatIdentityAndDeviceMustMatchChallenge() throws Exception {
        String challenge = challenge("first", "BIND");
        assertEquals(400, verify(challenge, OTP.get(), "second", "binding-key-000001").statusCode());
        assertEquals(400, send("POST", "/api/v1/mini/auth/binding",
                Map.of("code", "first", "challengeToken", challenge, "deviceId", UUID.randomUUID(), "otp", OTP.get()),
                Map.of("Idempotency-Key", "binding-key-000001", "If-Match-Version", "0")).statusCode());
        WECHAT_FAILS.set(true);
        assertEquals(503, verify(challenge, OTP.get(), "first", "binding-key-000001").statusCode());
        WECHAT_FAILS.set(false);
        assertEquals(0, activeBindings());
    }

    @Test
    void rebindRequiresNewSmsAndRevokesEveryOldSession() throws Exception {
        String first = challenge("first", "BIND");
        assertEquals(200, verify(first, OTP.get(), "first", "binding-key-000001").statusCode());
        String old = token("first");
        TIME.set(NOW.plusSeconds(60));
        assertEquals(409, issue("second", "BIND").statusCode());
        TIME.set(NOW.plusSeconds(120));
        String second = challenge("second", "REBIND");
        assertEquals(200, verify(second, OTP.get(), "second", "binding-key-000002").statusCode());
        assertEquals(1, activeBindings());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM fams_mini_binding WHERE account_id = ?", Integer.class, EMPLOYEE));
        assertEquals(401, send("GET", "/api/v1/mini/auth/session", null, Map.of(MiniAuthController.HEADER, old)).statusCode());
        assertEquals(200, send("GET", "/api/v1/mini/auth/session", null,
                Map.of(MiniAuthController.HEADER, token("second"))).statusCode());
    }

    @Test
    void concurrentSameProofCreatesOneBindingAndOneAuditedResult() throws Exception {
        String challenge = challenge("first", "BIND");
        String otp = OTP.get();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var left = pool.submit(() -> verify(challenge, otp, "first", "binding-key-000001"));
            var right = pool.submit(() -> verify(challenge, otp, "first", "binding-key-000001"));
            assertEquals(200, left.get().statusCode());
            assertEquals(200, right.get().statusCode());
        }
        assertEquals(1, activeBindings());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM fams_security_event WHERE account_id = ? "
                + "AND event_type = 'MINI_BINDING_CREATED'", Integer.class, EMPLOYEE));
    }

    @Test
    void adminPhoneCorrectionInvalidatesProofEvenAfterPhoneRestoredAndPreservesExistingBinding() throws Exception {
        String first = challenge("first", "BIND");
        assertEquals(200, verify(first, OTP.get(), "first", "binding-key-000001").statusCode());
        String token = token("first");
        TIME.set(NOW.plusSeconds(60));
        String stale = challenge("second", "REBIND");
        String oldOtp = OTP.get();
        var admin = pcLogin(ADMIN);
        String path = "/api/v1/employees/" + EMPLOYEE + "/phone-correction";
        var body = Map.of("phone", "13900139000", "reason", "Verified with HR", "verificationMethod", "HR_RECORD");
        assertEquals(403, send("POST", path, body, Map.of("Cookie", admin.cookie(),
                "Idempotency-Key", "phone-correct-key-01", "If-Match-Version", "0")).statusCode());
        var headers = admin.headers("phone-correct-key-01", 0);
        var corrected = send("POST", path, body, headers);
        assertEquals(200, corrected.statusCode(), corrected.body());
        assertEquals(corrected.body(), send("POST", path, body, headers).body());
        assertEquals(409, send("POST", path, Map.of("phone", PHONE, "reason", "Changed payload",
                "verificationMethod", "HR_RECORD"), headers).statusCode());
        assertEquals(200, send("POST", path, Map.of("phone", PHONE, "reason", "Restored after verification",
                "verificationMethod", "HR_RECORD"), admin.headers("phone-correct-key-02", 1)).statusCode());
        assertEquals(400, verify(stale, oldOtp, "second", "binding-key-000002").statusCode());
        assertEquals(1, activeBindings());
        assertEquals(200, send("GET", "/api/v1/mini/auth/session", null, Map.of(MiniAuthController.HEADER, token)).statusCode());
        String detail = jdbc.queryForList("SELECT detail_json FROM fams_security_event WHERE account_id = ? "
                + "AND event_type = 'MINI_PHONE_CORRECTED'", String.class, ADMIN).toString();
        assertTrue(detail.contains("beforeEncrypted"));
        assertTrue(detail.contains("afterEncrypted"));
        assertFalse(detail.contains(PHONE));
        assertFalse(detail.contains("13900139000"));
    }

    @Test
    void onlyAdminCanCorrectOrUnbindAndAdminReplayCannotRestoreIdentity() throws Exception {
        String challenge = challenge("first", "BIND");
        assertEquals(200, verify(challenge, OTP.get(), "first", "binding-key-000001").statusCode());
        String token = token("first");
        var employee = pcLogin(EMPLOYEE);
        var admin = pcLogin(ADMIN);
        String path = "/api/v1/system-accounts/" + EMPLOYEE + "/mini-binding/unbind";
        var body = Map.of("reason", "HR verified request", "verificationMethod", "HR_RECORD");
        assertEquals(403, send("POST", path, body, employee.headers("unbind-admin-key-01", 0)).statusCode());
        assertEquals(403, send("POST", "/api/v1/employees/" + EMPLOYEE + "/phone-correction",
                Map.of("phone", PHONE, "reason", "request", "verificationMethod", "HR_RECORD"),
                employee.headers("correct-denied-key-01", 0)).statusCode());
        assertEquals(400, send("POST", path, Map.of("reason", " ", "verificationMethod", "HR_RECORD"),
                admin.headers("unbind-admin-key-01", 0)).statusCode());
        assertEquals(409, send("POST", path, body, admin.headers("unbind-admin-key-01", 9)).statusCode());
        assertEquals(204, send("POST", path, body, admin.headers("unbind-admin-key-01", 0)).statusCode());
        assertEquals(204, send("POST", path, body, admin.headers("unbind-admin-key-01", 0)).statusCode());
        assertEquals(409, send("POST", path, Map.of("reason", "Other request", "verificationMethod", "HR_RECORD"),
                admin.headers("unbind-admin-key-01", 0)).statusCode());
        assertEquals(401, send("GET", "/api/v1/mini/auth/session", null, Map.of(MiniAuthController.HEADER, token)).statusCode());
        assertEquals(0, activeBindings());
    }

    @Test
    void supportIsAnonymousAndValidationCannotIssueProof() throws Exception {
        var support = send("GET", "/api/v1/mini/auth/support", null, Map.of());
        assertEquals(200, support.statusCode());
        assertTrue(support.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertEquals("Contact your corporate administrator.", JSON.readTree(support.body()).path("supportText").asText());
        assertEquals(400, send("POST", "/api/v1/mini/auth/binding-challenges", Map.of(), Map.of()).statusCode());
        assertEquals(400, send("POST", "/api/v1/mini/auth/binding", Map.of(), Map.of()).statusCode());
        assertEquals(0, activeBindings());
    }

    @Test
    void nativePageHandlersReachRealHttpAndPersistBindUnbindAndSessionRevocation() throws Exception {
        Path smsFile = Files.createTempFile("fams-test-sms-", ".txt");
        Path output = Files.createTempFile("fams-test-mini-page-", ".log");
        Process process = null;
        SMS_FILE.set(smsFile);
        try {
            Path script = Path.of(System.getProperty("user.dir")).getParent()
                    .resolve("apps/miniprogram/test/runtime-binding-http.mjs");
            var builder = new ProcessBuilder("rtk", "proxy", "node", script.toString());
            builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
            builder.environment().put("FAMS_TEST_SMS_FILE", smsFile.toString());
            process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Mini page runtime timed out");
            String result = Files.readString(output);
            assertEquals(0, process.exitValue(), result);
            assertTrue(result.contains("cache cleanup PASS"));
            System.out.println(result);
            assertEquals(0, activeBindings());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM fams_mini_binding WHERE account_id = ? "
                    + "AND revoked_at IS NOT NULL", Integer.class, EMPLOYEE));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM fams_mini_session WHERE account_id = ? "
                    + "AND revoked_at IS NULL", Integer.class, EMPLOYEE));
        } finally {
            SMS_FILE.set(null);
            if (process != null && process.isAlive()) { process.destroyForcibly(); }
            Files.deleteIfExists(smsFile);
            Files.deleteIfExists(output);
        }
    }

    @Test
    void genericEmployeeAndCsvUpdateCannotBypassPhoneCorrectionAudit() throws Exception {
        var admin = pcLogin(ADMIN);
        var response = send("PATCH", "/api/v1/employees/" + EMPLOYEE, Map.of("phone", "13900139000"),
                admin.headers("bypass-phone-key-001", 0));
        assertEquals(409, response.statusCode(), response.body());
        assertEquals("EMPLOYEE_PHONE_CORRECTION_REQUIRED", JSON.readTree(response.body()).path("code").asText());
        String csv = "recordType,action,code,displayName,parentCode,organizationCode,phone,expectedVersion\n"
                + "EMPLOYEE,UPDATE,BIND920001,Binding test,,BINDINGTEST,13900139000,0\n";
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/identity-imports"))
                .header("Content-Type", "text/csv").header("Cookie", admin.cookie())
                .header("X-CSRF-Token", admin.csrf()).header("Idempotency-Key", "bypass-csv-key-00001")
                .POST(HttpRequest.BodyPublishers.ofString(csv));
        var imported = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(409, imported.statusCode(), imported.body());
        assertEquals("EMPLOYEE_PHONE_CORRECTION_REQUIRED", JSON.readTree(imported.body()).path("code").asText());
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM fams_employee WHERE id = ?", Long.class, EMPLOYEE));
    }

    @Test
    void credentialRevokedBetweenFilterAndWriteCannotUnbindOrCorrectPhone() throws Exception {
        String challenge = challenge("first", "BIND");
        assertEquals(200, verify(challenge, OTP.get(), "first", "binding-key-000001").statusCode());
        String token = token("first");
        var snapshot = sessions.requireCurrent(token);
        sessions.logout(token, UUID.randomUUID());
        var rejected = assertThrows(AuthOperationException.class, () -> bindings.unbind(EMPLOYEE, EMPLOYEE,
                Long.parseLong(snapshot.bindingId()), 0, "stale-mini-key-0001", "request", "SELF_SESSION",
                token, null, UUID.randomUUID()));
        assertEquals(401, rejected.status());
        var stalePc = new AuthSession(ADMIN, 0, 0, "csrf", NOW.plusSeconds(1800), NOW.plusSeconds(28800), false);
        jdbc.update("UPDATE fams_system_account SET auth_epoch = 1 WHERE id = ?", ADMIN);
        assertEquals(401, assertThrows(AuthOperationException.class, () -> bindings.unbind(ADMIN, EMPLOYEE, null,
                0, "stale-admin-key-001", "request", "HR_RECORD", null, stalePc, UUID.randomUUID())).status());
        assertEquals(401, assertThrows(AuthOperationException.class, () -> corrections.correct(ADMIN, EMPLOYEE,
                0, "13900139000", "HR_RECORD", "request", "stale-phone-key-001", stalePc, UUID.randomUUID())).status());
        assertEquals(1, activeBindings());
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM fams_employee WHERE id = ?", Long.class, EMPLOYEE));
    }

    private int activeBindings() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM fams_mini_binding WHERE account_id = ? AND revoked_at IS NULL",
                Integer.class, EMPLOYEE);
    }

    private HttpResponse<String> issue(String code, String action) throws Exception {
        return send("POST", "/api/v1/mini/auth/binding-challenges", Map.of("employeeNo", "BIND" + EMPLOYEE,
                "phone", PHONE, "deviceId", DEVICE, "code", code, "action", action), Map.of());
    }

    private String challenge(String code, String action) throws Exception {
        var response = issue(code, action);
        assertEquals(200, response.statusCode(), response.body());
        assertFalse(response.body().contains(PHONE));
        assertFalse(response.body().contains(OTP.get()));
        return JSON.readTree(response.body()).path("challengeToken").asText();
    }

    private HttpResponse<String> verify(String challenge, String otp, String code, String key) throws Exception {
        return send("POST", "/api/v1/mini/auth/binding", Map.of("challengeToken", challenge, "otp", otp,
                "code", code, "deviceId", DEVICE), Map.of("Idempotency-Key", key, "If-Match-Version", "0"));
    }

    private String token(String code) throws Exception {
        var response = send("POST", "/api/v1/mini/auth/login", Map.of("code", code), Map.of());
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body()).path("sessionToken").asText();
    }

    private PcSession pcLogin(long id) throws Exception {
        var login = send("POST", "/api/v1/auth/login", Map.of("username", "BIND" + id, "password", PASSWORD), Map.of());
        assertEquals(200, login.statusCode(), login.body());
        return new PcSession(login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                JSON.readTree(login.body()).path("csrfToken").asText());
    }

    private HttpResponse<String> send(String method, String path, Object body, Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json");
        headers.forEach(request::header);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private byte[] phoneHash(String phone) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-idempotency-hmac-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(phone.getBytes(StandardCharsets.UTF_8));
    }

    private record PcSession(String cookie, String csrf) {
        Map<String, String> headers(String key, long version) {
            return Map.of("Cookie", cookie, "X-CSRF-Token", csrf, "Idempotency-Key", key, "If-Match-Version", Long.toString(version));
        }
    }

    @TestConfiguration
    static class Configuration {
        @Bean
        @Primary
        Clock bindingTestClock() {
            return new Clock() {
                @Override
                public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
                @Override
                public Clock withZone(java.time.ZoneId zone) { return this; }
                @Override
                public Instant instant() { return TIME.get(); }
            };
        }

        @Bean
        MiniSmsPort testOnlySms() {
            return (phone, code) -> {
                assertEquals(PHONE, phone);
                OTP.set(code);
                Path file = SMS_FILE.get();
                if (file != null) {
                    try { Files.writeString(file, code); }
                    catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
                }
                if (SMS_FAILS.get()) { throw new IllegalStateException("Test delivery unavailable"); }
            };
        }

        @Bean
        MiniWechatIdentityPort testOnlyBindingWechat() {
            return code -> {
                if (WECHAT_FAILS.get()) { throw new IllegalStateException("Test WeChat unavailable"); }
                String subject = code.startsWith("first-") ? "first" : code;
                return new MiniWechatIdentityPort.Identity("test-app", "binding-" + subject);
            };
        }
    }
}
