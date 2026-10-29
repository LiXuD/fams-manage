package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Actual HTTP/security, transactional MySQL and stored image bytes; scanner is an explicit fixture.
 */
@SpringBootTest(
        classes = {
            HandoverHttpTest.TestApplication.class,
            HandoverHttpTest.MiniConfiguration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=fams-product",
            "spring.flyway.locations=classpath:db/migration/d6_04",
            "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
            "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra",
            "fams.inventory.start-poll-ms=3600000"
        })
@EnabledIfSystemProperty(named = "d6.21.loss.runtime", matches = "true")
class LossHttpRuntimeCase {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PASSWORD = "owned-repair-19-password";
    static java.nio.file.Path photoRoot;
    final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcTemplate db;
    @Autowired MiniSessionService miniSessions;
    @Autowired com.fams.loan.LoanReminderService loanReminders;
    Map<String, Object> parameters;
    Session admin, employee, warehouse, otherWarehouse, other, system;

    record Session(String cookie, String csrf) {}

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        var s = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        r.add("spring.datasource.url", s::url);
        r.add("spring.datasource.username", s::username);
        r.add("spring.datasource.password", s::password);
        if (photoRoot == null) {
            try {
                photoRoot = java.nio.file.Files.createTempDirectory("fams-repair-photos-");
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
        r.add("fams.attachments.quarantine-root", () -> photoRoot.toString());
        r.add("fams.test.proxy-evidence", () -> "true");
    }

    @BeforeEach
    void seed() throws Exception {
        parameters =
                db.queryForMap(
                        "SELECT current_version,current_version_id FROM"
                                + " fams_business_parameter_state WHERE singleton_id=1");
        for (long id : new long[] {951000, 951099}) {
            db.update(
                    "INSERT INTO"
                        + " fams_organization(id,code,code_normalized,name,status,created_at,updated_at)"
                        + " VALUES (?,?,?,'Repair organization','ACTIVE',NOW(3),NOW(3))",
                    id,
                    "R" + id,
                    "R" + id);
        }
        for (String table : List.of("fams_asset_category", "fams_warehouse")) {
            db.update(
                    "INSERT INTO "
                            + table
                            + "(id,code,code_normalized,name,status,created_at,updated_at) VALUES"
                            + " (951000,'REPAIROWN','REPAIROWN','Repair"
                            + " fixture','ACTIVE',NOW(3),NOW(3))");
        }
        db.update(
                "INSERT INTO"
                    + " fams_location(id,code,code_normalized,name,location_type,warehouse_id,status,created_at,updated_at)"
                    + " VALUES (951000,'REPAIRBIN','REPAIRBIN','Original"
                    + " bin','BIN',951000,'ACTIVE',NOW(3),NOW(3)),(951001,'REPAIRROOM','REPAIRROOM','Original"
                    + " room','ROOM',NULL,'ACTIVE',NOW(3),NOW(3))");
        String[] roles = {
            "ASSET_ADMIN",
            "EMPLOYEE",
            "WAREHOUSE_OPERATOR",
            "WAREHOUSE_OPERATOR",
            "DEPARTMENT_ASSET_MANAGER",
            "EMPLOYEE",
            "EMPLOYEE",
            "SYSTEM_ADMIN",
            "TECH_ASSISTANT",
            "ASSET_ADMIN"
        };
        for (int i = 1; i <= roles.length; i++) {
            long id = 951000 + i;
            db.update(
                    "INSERT INTO"
                        + " fams_employee(id,employee_no,employee_no_normalized,name,organization_id,status,created_at,updated_at)"
                        + " VALUES (?,?,?,'Repair person',?,'ACTIVE',NOW(3),NOW(3))",
                    id,
                    "REP" + i,
                    "REP" + i,
                    i == 7 ? 951099 : 951000);
            db.update(
                    "INSERT INTO"
                        + " fams_system_account(id,employee_id,username,username_normalized,password_hash,status,initial_password_required,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,'ENABLED',FALSE,NOW(3),NOW(3))",
                    id,
                    id,
                    "rep" + i,
                    "REP" + i,
                    new BCryptPasswordEncoder(4).encode(PASSWORD));
            db.update(
                    "INSERT INTO fams_account_role(account_id,role_code,granted_at) VALUES"
                            + " (?,?,NOW(3))",
                    id,
                    roles[i - 1]);
        }
        db.update(
                "INSERT INTO"
                    + " fams_business_parameter_version(id,version,status,effective_at,actor_account_id,actor_username,snapshot_json,created_at)"
                    + " VALUES"
                    + " (951000,951000,'ACTIVE',NOW(3),951001,'rep1','{\"timezone\":\"Asia/Shanghai\"}',NOW(3))");
        db.update(
                "UPDATE fams_business_parameter_state SET"
                        + " current_version=951000,current_version_id=951000 WHERE singleton_id=1");
        for (long id : new long[] {951020, 951021, 951022, 951023}) {
            boolean stock = id == 951023, shared = id == 951022;
            db.update(
                    "INSERT INTO"
                        + " fams_asset(id,asset_code,name,category_id,lifecycle_status,exception_status,card_stage,custody_type,storage_warehouse_id,current_location_id,using_organization_id,responsible_employee_id,department_responsible_employee_id,created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by)"
                        + " VALUES (?,?,'Actual repair"
                        + " asset',951000,?,'NORMAL','ACTIVE',?,?,?,?,?,?,CURRENT_DATE,951000,951001,'rep1',NOW(3),NOW(3),951001,951001)",
                    id,
                    "REP-ASSET-" + id,
                    stock ? "IN_STOCK" : "IN_USE",
                    stock ? "STORAGE" : shared ? "SHARED" : "PERSONAL",
                    stock ? 951000 : null,
                    stock ? 951000 : 951001,
                    stock ? null : 951000,
                    stock || shared ? null : id == 951021 ? 951007 : 951002,
                    shared ? 951005 : null);
        }
        admin = login(1);
        employee = login(2);
        warehouse = login(3);
        otherWarehouse = login(4);
        other = login(7);
        system = login(8);
    }

    @AfterEach
    void cleanup() throws Exception {
        db.execute("DROP TRIGGER IF EXISTS owned_repair_audit_failure");
        db.update(
                "DELETE FROM fams_todo WHERE business_type='LOST_REPORT' AND business_id IN (SELECT"
                        + " id FROM fams_loss_report WHERE creator_account_id BETWEEN 951001 AND"
                        + " 951010)");
        db.update("DELETE FROM fams_asset_business_lock WHERE asset_id BETWEEN 951020 AND 951023");
        for (String table : List.of("fams_loss_recovery", "fams_loss_receipt", "fams_loss_event")) {
            db.update(
                    "DELETE FROM "
                            + table
                            + " WHERE report_id IN (SELECT id FROM fams_loss_report WHERE"
                            + " creator_account_id BETWEEN 951001 AND 951010)");
        }
        db.update(
                "DELETE FROM fams_attachment WHERE business_type='LOST_REPORT' AND"
                        + " uploader_account_id BETWEEN 951001 AND 951010");
        db.update(
                "DELETE FROM fams_attachment_scope WHERE business_type='LOST_REPORT' AND"
                    + " business_id IN (SELECT id FROM fams_loss_report WHERE creator_account_id"
                    + " BETWEEN 951001 AND 951010)");
        db.update(
                "DELETE FROM fams_loss_report WHERE creator_account_id BETWEEN 951001 AND 951010");
        db.update("DELETE FROM fams_asset_timeline_event WHERE asset_id BETWEEN 951020 AND 951023");
        db.update(
                "DELETE FROM fams_todo WHERE business_type='LOAN' AND business_id IN"
                        + " (SELECT id FROM fams_loan WHERE asset_id BETWEEN 951020 AND 951023)");
        db.update(
                "DELETE FROM fams_loan_reminder WHERE loan_id IN"
                        + " (SELECT id FROM fams_loan WHERE asset_id BETWEEN 951020 AND 951023)");
        db.update("DELETE FROM fams_loan WHERE asset_id BETWEEN 951020 AND 951023");
        db.update(
                "DELETE FROM fams_asset_import_batch WHERE"
                        + " id='00000000-0000-4000-8000-000000009519'");
        db.update("DELETE FROM fams_asset WHERE id BETWEEN 951020 AND 951023");
        db.update(
                "UPDATE fams_business_parameter_state SET current_version=?,current_version_id=?"
                        + " WHERE singleton_id=1",
                parameters.get("current_version"),
                parameters.get("current_version_id"));
        db.update("DELETE FROM fams_business_parameter_version WHERE id=951000");
        for (String table : List.of("fams_mini_session", "fams_mini_binding")) {
            db.update("DELETE FROM " + table + " WHERE account_id BETWEEN 951001 AND 951010");
        }
        db.update("DELETE FROM fams_audit_event WHERE actor_account_id BETWEEN 951001 AND 951010");
        db.update("DELETE FROM fams_security_event WHERE account_id BETWEEN 951001 AND 951010");
        db.update("DELETE FROM fams_account_role WHERE account_id BETWEEN 951001 AND 951010");
        db.update("DELETE FROM fams_system_account WHERE id BETWEEN 951001 AND 951010");
        db.update("DELETE FROM fams_employee WHERE id BETWEEN 951001 AND 951010");
        db.update("DELETE FROM fams_location WHERE id BETWEEN 951000 AND 951001");
        for (String table : List.of("fams_warehouse", "fams_asset_category")) {
            db.update("DELETE FROM " + table + " WHERE id=951000");
        }
        db.update("DELETE FROM fams_organization WHERE id IN (951000,951099)");
        try (var files = java.nio.file.Files.list(photoRoot)) {
            for (var file : files.toList()) {
                java.nio.file.Files.deleteIfExists(file);
            }
        }
    }

    @Test
    void actualNativePageRetainsCommittedIntentsAndOriginalBytesThroughSearching()
            throws Exception {
        var grants = new java.util.LinkedHashMap<String, Object>();
        for (var entry :
                Map.of(
                                "employee",
                                951002L,
                                "admin",
                                951001L,
                                "warehouse",
                                951003L,
                                "other",
                                951007L)
                        .entrySet()) {
            var identity = new MiniWechatIdentityPort.Identity("owned-loss-native", entry.getKey());
            db.update(
                    "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at)"
                            + " VALUES (?,?,NOW(3))",
                    entry.getValue(),
                    miniSessions.identityHash(identity));
            var grant = miniSessions.start(identity, UUID.randomUUID());
            grants.put(
                    entry.getKey(),
                    Map.of(
                            "sessionToken",
                            grant.sessionToken(),
                            "accountId",
                            entry.getValue().toString(),
                            "bindingId",
                            grant.bindingId()));
        }
        var script =
                java.nio.file.Path.of(System.getProperty("user.dir"))
                        .getParent()
                        .resolve("apps/miniprogram/test/runtime-loss-http.mjs");
        var builder =
                new ProcessBuilder(
                                "rtk",
                                "proxy",
                                System.getProperty(
                                        "fams.test.node",
                                        System.getenv()
                                                .getOrDefault("FAMS_TEST_NODE_EXECUTABLE", "node")),
                                script.toString())
                        .redirectErrorStream(true);
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_LOSS_GRANTS", JSON.writeValueAsString(grants));
        var process = builder.start();
        try {
            org.junit.jupiter.api.Assertions.assertTrue(
                    process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        var output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        org.junit.jupiter.api.Assertions.assertTrue(
                output.contains("\"nativeActualLossSearching\":true"), output);
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_loss_report WHERE asset_id=951020 AND"
                                + " report_status='SEARCHING' AND processor_account_id=951003 AND"
                                + " next_review_date IS NOT NULL",
                        Long.class));
        assertEquals(5L, db.queryForObject("SELECT COUNT(*) FROM fams_loss_event", Long.class));
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset_business_lock"
                                + " WHERE asset_id=951020 AND released_at IS NULL",
                        Long.class));
        assertEquals(
                "IN_USE",
                db.queryForObject(
                        "SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class));
        assertEquals(
                "PERSONAL",
                db.queryForObject(
                        "SELECT custody_type FROM fams_asset WHERE id=951020", String.class));
        assertEquals(
                951002L,
                db.queryForObject(
                        "SELECT responsible_employee_id FROM fams_asset WHERE id=951020",
                        Long.class));
    }

    @Test
    void actualNativePageConfirmsLossAndFollowsUpWithOriginalCommittedIntents() throws Exception {
        var grants = new java.util.LinkedHashMap<String, Object>();
        for (var entry :
                Map.of(
                                "employee",
                                951002L,
                                "admin",
                                951001L,
                                "warehouse",
                                951003L,
                                "other",
                                951007L)
                        .entrySet()) {
            var identity = new MiniWechatIdentityPort.Identity("owned-loss-native", entry.getKey());
            db.update(
                    "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at)"
                            + " VALUES (?,?,NOW(3))",
                    entry.getValue(),
                    miniSessions.identityHash(identity));
            var grant = miniSessions.start(identity, UUID.randomUUID());
            grants.put(
                    entry.getKey(),
                    Map.of(
                            "sessionToken",
                            grant.sessionToken(),
                            "accountId",
                            entry.getValue().toString(),
                            "bindingId",
                            grant.bindingId()));
        }
        var script =
                java.nio.file.Path.of(System.getProperty("user.dir"))
                        .getParent()
                        .resolve("apps/miniprogram/test/runtime-loss-confirmation-http.mjs");
        var builder =
                new ProcessBuilder(
                                "rtk",
                                "proxy",
                                System.getProperty(
                                        "fams.test.node",
                                        System.getenv()
                                                .getOrDefault("FAMS_TEST_NODE_EXECUTABLE", "node")),
                                script.toString())
                        .redirectErrorStream(true);
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_LOSS_GRANTS", JSON.writeValueAsString(grants));
        var process = builder.start();
        try {
            org.junit.jupiter.api.Assertions.assertTrue(
                    process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        var output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        org.junit.jupiter.api.Assertions.assertTrue(
                output.contains("\"nativeActualLossConfirmed\":true"), output);
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_loss_report WHERE asset_id=951020 AND"
                            + " report_status='CONFIRMED_LOST' AND processor_account_id=951003 AND"
                            + " next_review_date IS NOT NULL",
                        Long.class));
        assertEquals(7L, db.queryForObject("SELECT COUNT(*) FROM fams_loss_event", Long.class));
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset_business_lock"
                                + " WHERE asset_id=951020 AND released_at IS NULL",
                        Long.class));
        assertEquals(
                "IN_USE",
                db.queryForObject(
                        "SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class));
        assertEquals(
                "PERSONAL",
                db.queryForObject(
                        "SELECT custody_type FROM fams_asset WHERE id=951020", String.class));
        assertEquals(
                951002L,
                db.queryForObject(
                        "SELECT responsible_employee_id FROM fams_asset WHERE id=951020",
                        Long.class));
    }

    @Test
    void actualNativePageRecoversConfirmedLossWithFreshApprovalAndOriginalCommittedIntents()
            throws Exception {
        var grants = new java.util.LinkedHashMap<String, Object>();
        for (var entry :
                Map.of(
                                "employee",
                                951002L,
                                "admin",
                                951001L,
                                "warehouse",
                                951003L,
                                "other",
                                951007L)
                        .entrySet()) {
            var identity = new MiniWechatIdentityPort.Identity("owned-loss-native", entry.getKey());
            db.update(
                    "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at)"
                            + " VALUES (?,?,NOW(3))",
                    entry.getValue(),
                    miniSessions.identityHash(identity));
            var grant = miniSessions.start(identity, UUID.randomUUID());
            grants.put(
                    entry.getKey(),
                    Map.of(
                            "sessionToken",
                            grant.sessionToken(),
                            "accountId",
                            entry.getValue().toString(),
                            "bindingId",
                            grant.bindingId()));
        }
        var script =
                java.nio.file.Path.of(System.getProperty("user.dir"))
                        .getParent()
                        .resolve("apps/miniprogram/test/runtime-loss-recovery-http.mjs");
        var builder =
                new ProcessBuilder(
                                "rtk",
                                "proxy",
                                System.getProperty(
                                        "fams.test.node",
                                        System.getenv()
                                                .getOrDefault("FAMS_TEST_NODE_EXECUTABLE", "node")),
                                script.toString())
                        .redirectErrorStream(true);
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_LOSS_GRANTS", JSON.writeValueAsString(grants));
        var process = builder.start();
        try {
            org.junit.jupiter.api.Assertions.assertTrue(
                    process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        var output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        org.junit.jupiter.api.Assertions.assertTrue(
                output.contains("\"nativeActualLossRecovered\":true"), output);
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_loss_report WHERE asset_id=951020 AND"
                            + " report_status='CONFIRMED_LOST' AND processor_account_id=951003 AND"
                            + " next_review_date IS NOT NULL",
                        Long.class));
        assertEquals(11L, db.queryForObject("SELECT COUNT(*) FROM fams_loss_event", Long.class));
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset_business_lock"
                                + " WHERE asset_id=951020 AND released_at IS NULL",
                        Long.class));
        assertEquals(
                "IN_USE",
                db.queryForObject(
                        "SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class));
        assertEquals(
                "PERSONAL",
                db.queryForObject(
                        "SELECT custody_type FROM fams_asset WHERE id=951020", String.class));
        assertEquals(
                951002L,
                db.queryForObject(
                        "SELECT responsible_employee_id FROM fams_asset WHERE id=951020",
                        Long.class));
        assertEquals(
                "NORMAL",
                db.queryForObject(
                        "SELECT exception_status FROM fams_asset WHERE id=951020", String.class));
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_loss_recovery WHERE recovery_status='COMPLETED'",
                        Long.class));
    }

    @Test
    void reportingAndApprovalNeverChangeCustodyAndOnlyVerificationStartsSearching()
            throws Exception {
        borrowing(true);
        var original = db.queryForMap("SELECT * FROM fams_asset WHERE id=951020");
        var loan = db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020");
        String key = key(), body = submission(951020);
        var row = ok(post(employee, "", body, 0, key));
        assertEquals(row, ok(post(employee, "", body, 0, key)));
        assertEquals(409, post(employee, "", submission(951021), 0, key).statusCode());
        String id = row.path("id").asText();
        assertEquals(original, db.queryForMap("SELECT * FROM fams_asset WHERE id=951020"));
        assertEquals(403, get(other, "/" + id).statusCode());
        assertEquals(403, get(warehouse, "/" + id).statusCode());
        assertEquals(409, post(admin, "/" + id + "/decision", decision(), 0, key()).statusCode());
        row = addPhoto(id);
        assertEquals(
                403,
                post(
                                employee,
                                "/" + id + "/decision",
                                decision(),
                                row.path("version").asLong(),
                                key())
                        .statusCode());
        row =
                ok(
                        post(
                                admin,
                                "/" + id + "/decision",
                                decision(),
                                row.path("version").asLong(),
                                key()));
        assertEquals("WAITING_WAREHOUSE_CLAIM", row.path("status").asText());
        assertEquals(original, db.queryForMap("SELECT * FROM fams_asset WHERE id=951020"));
        row = ok(post(warehouse, "/" + id + "/claim", null, row.path("version").asLong(), key()));
        row = ok(get(warehouse, "/" + id));
        assertEquals("VERIFYING", row.path("status").asText());
        assertEquals(
                409,
                post(
                                otherWarehouse,
                                "/" + id + "/search",
                                search(row),
                                row.path("version").asLong(),
                                key())
                        .statusCode());
        String verifyKey = key(), verifyBody = search(row);
        long version = row.path("version").asLong();
        var verified = ok(post(warehouse, "/" + id + "/search", verifyBody, version, verifyKey));
        assertEquals(
                verified,
                ok(post(warehouse, "/" + id + "/search", verifyBody, version, verifyKey)));
        assertEquals("SEARCHING", verified.path("status").asText());
        var after = db.queryForMap("SELECT * FROM fams_asset WHERE id=951020");
        for (String field : List.of("exception_status", "version", "updated_by", "updated_at")) {
            original.remove(field);
            after.remove(field);
        }
        assertEquals(original, after);
        assertEquals(loan, db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020"));
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=951020 AND"
                                + " business_type='LOST_REPORT' AND released_at IS NULL",
                        Long.class));
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_todo WHERE business_type='LOST_REPORT' AND"
                            + " business_id=? AND todo_type='SEARCH' AND assignee_account_id=951003"
                            + " AND closed_at IS NULL",
                        Long.class,
                        Long.parseLong(id)));
        assertFalse(ok(get(employee, "/eligible")).toString().contains("REP-ASSET-951020"));
    }

    @Test
    void cancellationAndRejectionReleaseOnlyIndependentLockWithImmutableHistory() throws Exception {
        var row = ok(post(employee, "", submission(951020), 0, key()));
        String id = row.path("id").asText();
        assertEquals(
                403,
                post(other, "/" + id + "/cancel", "{\"reason\":\"无权撤回本人之外报告\"}", 0, key())
                        .statusCode());
        row = ok(post(employee, "/" + id + "/cancel", "{\"reason\":\"已经核实报告填写有误\"}", 0, key()));
        assertEquals("CANCELLED", row.path("status").asText());
        row = ok(post(employee, "", submission(951020), 0, key()));
        id = row.path("id").asText();
        row =
                ok(
                        post(
                                admin,
                                "/" + id + "/decision",
                                "{\"result\":\"REJECT\",\"reason\":\"无效报告且尚未开展核实\"}",
                                0,
                                key()));
        assertEquals("REJECTED", row.path("status").asText());
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=951020 AND"
                                + " released_at IS NULL",
                        Long.class));
        assertEquals(
                "NORMAL",
                db.queryForObject(
                        "SELECT exception_status FROM fams_asset WHERE id=951020", String.class));
        assertEquals(4L, db.queryForObject("SELECT COUNT(*) FROM fams_loss_event", Long.class));
    }

    @Test
    void duplicateAssetReportsAndCompetingClaimsHaveOneWinner() throws Exception {
        var row = ok(post(employee, "", submission(951020), 0, key()));
        String id = row.path("id").asText();
        assertEquals(409, post(admin, "", submission(951020), 0, key()).statusCode());
        row = addPhoto(id);
        row =
                ok(
                        post(
                                admin,
                                "/" + id + "/decision",
                                decision(),
                                row.path("version").asLong(),
                                key()));
        long version = row.path("version").asLong();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one =
                    pool.submit(
                            () -> {
                                start.await();
                                return post(warehouse, "/" + id + "/claim", null, version, key())
                                        .statusCode();
                            });
            var two =
                    pool.submit(
                            () -> {
                                start.await();
                                return post(
                                                otherWarehouse,
                                                "/" + id + "/claim",
                                                null,
                                                version,
                                                key())
                                        .statusCode();
                            });
            start.countDown();
            assertEquals(
                    List.of(200, 409),
                    java.util.stream.Stream.of(one.get(), two.get()).sorted().toList());
        }
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_loss_event WHERE report_id=? AND"
                                + " action='LOSS_CLAIMED'",
                        Long.class,
                        Long.parseLong(id)));
    }

    @Test
    void verificationAuditFailureRollsBackAssetReportTodoAndReceipt() throws Exception {
        var row = ok(post(employee, "", submission(951020), 0, key()));
        String id = row.path("id").asText();
        row = addPhoto(id);
        row =
                ok(
                        post(
                                admin,
                                "/" + id + "/decision",
                                decision(),
                                row.path("version").asLong(),
                                key()));
        row = ok(post(warehouse, "/" + id + "/claim", null, row.path("version").asLong(), key()));
        row = ok(get(warehouse, "/" + id));
        var asset = db.queryForMap("SELECT * FROM fams_asset WHERE id=951020");
        var todos =
                db.queryForList(
                        "SELECT * FROM fams_todo WHERE business_type='LOST_REPORT' AND"
                                + " business_id=? ORDER BY id",
                        Long.parseLong(id));
        db.execute(
                "CREATE TRIGGER owned_repair_audit_failure BEFORE INSERT ON fams_audit_event FOR"
                        + " EACH ROW BEGIN IF NEW.action='LOSS_SEARCH_STARTED' THEN SIGNAL SQLSTATE"
                        + " '45000' SET MESSAGE_TEXT='owned loss audit failure'; END IF; END");
        String key = key(), body = search(row);
        long version = row.path("version").asLong();
        assertEquals(500, post(warehouse, "/" + id + "/search", body, version, key).statusCode());
        assertEquals(asset, db.queryForMap("SELECT * FROM fams_asset WHERE id=951020"));
        assertEquals(
                todos,
                db.queryForList(
                        "SELECT * FROM fams_todo WHERE business_type='LOST_REPORT' AND"
                                + " business_id=? ORDER BY id",
                        Long.parseLong(id)));
        assertEquals("VERIFYING", ok(get(warehouse, "/" + id)).path("status").asText());
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_loss_receipt WHERE request_id=?",
                        Long.class,
                        key));
        db.execute("DROP TRIGGER owned_repair_audit_failure");
        assertEquals(
                "SEARCHING",
                ok(post(warehouse, "/" + id + "/search", body, version, key))
                        .path("status")
                        .asText());
    }

    @Test
    void photosUseOriginalReceiptAndGrantCannotCrossReportOrCurrentIdentity() throws Exception {
        var row = ok(post(employee, "", submission(951020), 0, key()));
        String id = row.path("id").asText(), key = key();
        byte[] png = png();
        var photo = ok(upload(employee, id, 0, key, png));
        assertEquals(photo, ok(upload(employee, id, 0, key, png)));
        assertEquals(1, ok(get(employee, "/" + id)).path("photos").size());
        String grantPath =
                "/" + id + "/photos/" + photo.path("objectId").asText() + "/download-grant";
        assertEquals(403, post(other, grantPath, null, 1, key()).statusCode());
        String url = ok(post(employee, grantPath, null, 1, key())).path("url").asText();
        var downloaded =
                http.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + url))
                                .header("Cookie", employee.cookie())
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, downloaded.statusCode());
        assertArrayEquals(png, downloaded.body());
        assertEquals(403, get(other, url.replace("/api/v1/loss-reports", "")).statusCode());
        db.update(
                "UPDATE fams_system_account SET authorization_version=authorization_version+1 WHERE"
                        + " id=951002");
        assertEquals(401, upload(employee, id, 0, key, png).statusCode());
    }

    @Test
    void scopeDoesNotExposeOtherCustodianOrAllowSystemAdministratorToReport() throws Exception {
        assertFalse(ok(get(employee, "/eligible")).toString().contains("REP-ASSET-951021"));
        assertFalse(ok(get(employee, "/eligible")).toString().contains("REP-ASSET-951022"));
        assertEquals(403, post(employee, "", submission(951021), 0, key()).statusCode());
        assertEquals(403, post(system, "", submission(951020), 0, key()).statusCode());
        assertEquals(
                400,
                post(
                                employee,
                                "",
                                submission(951020)
                                        .replace("2026-10-20T00:00:00Z", "2099-10-20T00:00:00Z"),
                                0,
                                key())
                        .statusCode());
        assertEquals(400, get(admin, "?pageSize=101").statusCode());
    }

    @Test
    void confirmationRetainsExpiredBorrowingAndMovesToThirtyDayResponsibilityFollowUp()
            throws Exception {
        borrowing(true);
        db.update(
                "UPDATE fams_loan SET parameter_snapshot_json='{\"timezone\":\"Asia/Shanghai\"}'"
                        + " WHERE asset_id=951020");
        long loanId =
                db.queryForObject("SELECT id FROM fams_loan WHERE asset_id=951020", Long.class);
        loanReminders.refresh(loanId);
        var loan = db.queryForMap("SELECT * FROM fams_loan WHERE id=?", loanId);
        var notices =
                db.queryForList(
                        "SELECT * FROM fams_loan_reminder WHERE loan_id=? ORDER BY id", loanId);
        var row = searchingReport();
        String id = row.path("id").asText();
        String body = confirmation(row), originalKey = key();
        long version = row.path("version").asLong();
        var result = ok(post(warehouse, "/" + id + "/confirm-lost", body, version, originalKey));
        assertEquals("CONFIRMED_LOST", result.path("status").asText());
        assertEquals(
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                        .plusDays(30)
                        .toString(),
                result.path("nextReviewDate").asText());
        assertEquals(loan, db.queryForMap("SELECT * FROM fams_loan WHERE id=?", loanId));
        assertEquals(
                "BORROWED",
                db.queryForObject(
                        "SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class));
        assertEquals(
                "LOST",
                db.queryForObject(
                        "SELECT exception_status FROM fams_asset WHERE id=951020", String.class));
        assertEquals(
                951002L,
                db.queryForObject(
                        "SELECT responsible_employee_id FROM fams_asset WHERE id=951020",
                        Long.class));
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=951020 AND"
                                + " released_at IS NULL",
                        Long.class));
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_todo WHERE business_type='LOAN' AND"
                            + " business_id=? AND todo_type='LOST_LIABILITY' AND closed_at IS NULL",
                        Long.class,
                        loanId));
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_todo WHERE business_type='LOAN' AND"
                                + " business_id=? AND todo_type<>'LOST_LIABILITY' AND closed_at IS"
                                + " NULL",
                        Long.class,
                        loanId));
        assertEquals(
                notices,
                db.queryForList(
                        "SELECT * FROM fams_loan_reminder WHERE loan_id=? AND"
                                + " reminder_type<>'LOST_LIABILITY' ORDER BY id",
                        loanId));
        assertEquals(1, ok(get(employee, "/follow-ups")).path("total").asInt());
        assertEquals(0, ok(get(other, "/follow-ups")).path("total").asInt());
        assertEquals(400, get(admin, "/follow-ups?pageSize=101").statusCode());
        assertEquals(
                409,
                post(
                                employee,
                                "",
                                submission(951020)
                                        .replace("\"assetVersion\":0", "\"assetVersion\":2"),
                                0,
                                key())
                        .statusCode());
        row = ok(get(warehouse, "/" + id));
        assertFalse(row.path("actions").path("confirmLost").asBoolean());
        String follow = followUp(row), followKey = key();
        long followVersion = row.path("version").asLong();
        var followed =
                ok(post(warehouse, "/" + id + "/follow-up", follow, followVersion, followKey));
        assertEquals("CONFIRMED_LOST", followed.path("status").asText());
        assertFalse(followed.path("lastFollowUpAt").isNull());
        assertEquals(
                followed,
                ok(post(warehouse, "/" + id + "/follow-up", follow, followVersion, followKey)));
        assertEquals(
                result,
                ok(post(warehouse, "/" + id + "/confirm-lost", body, version, originalKey)));
        assertEquals(loan, db.queryForMap("SELECT * FROM fams_loan WHERE id=?", loanId));
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_todo WHERE business_type='LOST_REPORT' AND"
                            + " business_id=? AND todo_type='LOST_FOLLOW_UP' AND closed_at IS NULL",
                        Long.class,
                        Long.parseLong(id)));
    }

    @Test
    void confirmationRequiresCurrentHandlerFourVersionsAndCompleteChronologicalEvidence()
            throws Exception {
        var row = searchingReport();
        String id = row.path("id").asText(), body = confirmation(row);
        long version = row.path("version").asLong();
        assertEquals(
                403, post(admin, "/" + id + "/confirm-lost", body, version, key()).statusCode());
        assertEquals(
                409,
                post(otherWarehouse, "/" + id + "/confirm-lost", body, version, key())
                        .statusCode());
        assertEquals(
                409,
                post(warehouse, "/" + id + "/confirm-lost", body, version - 1, key()).statusCode());
        for (String field : List.of("assetVersion", "ownershipVersion", "lockVersion")) {
            var invalid = new java.util.LinkedHashMap<>(JSON.readValue(body, Map.class));
            invalid.put(field, 999);
            assertEquals(
                    409,
                    post(
                                    warehouse,
                                    "/" + id + "/confirm-lost",
                                    JSON.writeValueAsString(invalid),
                                    version,
                                    key())
                            .statusCode());
        }
        for (String field :
                List.of(
                        "custodianVerification",
                        "searchedScope",
                        "relatedClues",
                        "inabilityReason",
                        "responsibilityEvidence")) {
            var invalid = new java.util.LinkedHashMap<>(JSON.readValue(body, Map.class));
            invalid.put(field, "一          ");
            assertEquals(
                    400,
                    post(
                                    warehouse,
                                    "/" + id + "/confirm-lost",
                                    JSON.writeValueAsString(invalid),
                                    version,
                                    key())
                            .statusCode());
        }
        for (String time : List.of("2000-01-01T00:00:00Z", "2099-01-01T00:00:00Z")) {
            var invalid = new java.util.LinkedHashMap<>(JSON.readValue(body, Map.class));
            invalid.put("confirmedAt", time);
            assertEquals(
                    400,
                    post(
                                    warehouse,
                                    "/" + id + "/confirm-lost",
                                    JSON.writeValueAsString(invalid),
                                    version,
                                    key())
                            .statusCode());
        }
        assertEquals("SEARCHING", ok(get(warehouse, "/" + id)).path("status").asText());
        assertEquals(
                "SEARCHING",
                db.queryForObject(
                        "SELECT exception_status FROM fams_asset WHERE id=951020", String.class));
    }

    @Test
    void concurrentConfirmationHasOneWinnerAndChangedPayloadCannotReuseOriginalKey()
            throws Exception {
        var row = searchingReport();
        String id = row.path("id").asText(), body = confirmation(row);
        long version = row.path("version").asLong();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one =
                    pool.submit(
                            () -> {
                                start.await();
                                return post(
                                                warehouse,
                                                "/" + id + "/confirm-lost",
                                                body,
                                                version,
                                                key())
                                        .statusCode();
                            });
            var two =
                    pool.submit(
                            () -> {
                                start.await();
                                return post(
                                                warehouse,
                                                "/" + id + "/confirm-lost",
                                                body,
                                                version,
                                                key())
                                        .statusCode();
                            });
            start.countDown();
            assertEquals(
                    List.of(200, 409),
                    java.util.stream.Stream.of(one.get(), two.get()).sorted().toList());
        }
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_loss_event WHERE action='LOSS_CONFIRMED'",
                        Long.class));
        var receipt =
                db.queryForMap(
                        "SELECT request_id,response_json FROM fams_loss_receipt WHERE operation=?",
                        "CONFIRM_LOST:" + id);
        assertEquals(
                409,
                post(
                                warehouse,
                                "/" + id + "/confirm-lost",
                                body.replace("无法找回判断", "改变无法找回判断"),
                                version,
                                receipt.get("request_id").toString())
                        .statusCode());
    }

    @Test
    void confirmationAuditFailureRollsBackAssetLockReportReminderAndReceipt() throws Exception {
        borrowing(true);
        db.update(
                "UPDATE fams_loan SET parameter_snapshot_json='{\"timezone\":\"Asia/Shanghai\"}'"
                        + " WHERE asset_id=951020");
        long loanId =
                db.queryForObject("SELECT id FROM fams_loan WHERE asset_id=951020", Long.class);
        loanReminders.refresh(loanId);
        var row = searchingReport();
        String id = row.path("id").asText(), body = confirmation(row), originalKey = key();
        long version = row.path("version").asLong();
        var asset = db.queryForMap("SELECT * FROM fams_asset WHERE id=951020");
        var report =
                db.queryForMap("SELECT * FROM fams_loss_report WHERE id=?", Long.parseLong(id));
        var locks =
                db.queryForList(
                        "SELECT * FROM fams_asset_business_lock WHERE asset_id=951020 ORDER BY id");
        var todos = db.queryForList("SELECT * FROM fams_todo ORDER BY id");
        var reminders = db.queryForList("SELECT * FROM fams_loan_reminder ORDER BY id");
        db.execute(
                "CREATE TRIGGER owned_repair_audit_failure BEFORE INSERT ON fams_audit_event FOR"
                    + " EACH ROW BEGIN IF NEW.action='LOSS_CONFIRMED' THEN SIGNAL SQLSTATE '45000'"
                    + " SET MESSAGE_TEXT='owned loss confirmation failure'; END IF; END");
        assertEquals(
                500,
                post(warehouse, "/" + id + "/confirm-lost", body, version, originalKey)
                        .statusCode());
        assertEquals(asset, db.queryForMap("SELECT * FROM fams_asset WHERE id=951020"));
        assertEquals(
                report,
                db.queryForMap("SELECT * FROM fams_loss_report WHERE id=?", Long.parseLong(id)));
        assertEquals(
                locks,
                db.queryForList(
                        "SELECT * FROM fams_asset_business_lock WHERE asset_id=951020 ORDER BY"
                                + " id"));
        assertEquals(todos, db.queryForList("SELECT * FROM fams_todo ORDER BY id"));
        assertEquals(reminders, db.queryForList("SELECT * FROM fams_loan_reminder ORDER BY id"));
        db.execute("DROP TRIGGER owned_repair_audit_failure");
        assertEquals(
                "CONFIRMED_LOST",
                ok(post(warehouse, "/" + id + "/confirm-lost", body, version, originalKey))
                        .path("status")
                        .asText());
    }

    @Test
    void followUpRejectsOtherHandlerPastDatesAndMissingReasonWithoutReopeningLock()
            throws Exception {
        var row = searchingReport();
        String id = row.path("id").asText();
        row =
                ok(
                        post(
                                warehouse,
                                "/" + id + "/confirm-lost",
                                confirmation(row),
                                row.path("version").asLong(),
                                key()));
        long version = row.path("version").asLong();
        String body = followUp(row);
        assertEquals(
                409,
                post(otherWarehouse, "/" + id + "/follow-up", body, version, key()).statusCode());
        for (var invalid :
                List.of(
                        Map.of("nextReviewDate", "2000-01-01"),
                        Map.of("reviewedAt", "2000-01-01T00:00:00Z"),
                        Map.of("reason", "一"))) {
            var modified = new java.util.LinkedHashMap<>(JSON.readValue(body, Map.class));
            modified.putAll(invalid);
            assertEquals(
                    400,
                    post(
                                    warehouse,
                                    "/" + id + "/follow-up",
                                    JSON.writeValueAsString(modified),
                                    version,
                                    key())
                            .statusCode());
        }
        var extension = new java.util.LinkedHashMap<>(JSON.readValue(body, Map.class));
        extension.put(
                "nextReviewDate",
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                        .plusDays(45)
                        .toString());
        var followed =
                ok(
                        post(
                                warehouse,
                                "/" + id + "/follow-up",
                                JSON.writeValueAsString(extension),
                                version,
                                key()));
        assertEquals(extension.get("nextReviewDate"), followed.path("nextReviewDate").asText());
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=951020 AND"
                                + " released_at IS NULL",
                        Long.class));
    }

    @Test
    void searchingRecoveryRetainsBorrowingAndRestoresOriginalOverdueReminder() throws Exception {
        borrowing(true);
        db.update(
                "UPDATE fams_loan SET parameter_snapshot_json=? WHERE asset_id=951020",
                JSON.writeValueAsString(Map.of("timezone", "Asia/Shanghai")));
        var loan = db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020");
        var row = searchingReport();
        String id = row.path("id").asText(), body = recoveryBody(row, null), request = key();
        long version = row.path("version").asLong();
        assertEquals(
                409,
                post(otherWarehouse, "/" + id + "/recover-original", body, version, key())
                        .statusCode());
        var result = ok(post(warehouse, "/" + id + "/recover-original", body, version, request));
        assertEquals("RECOVERED", result.path("status").asText());
        assertEquals(
                result,
                ok(post(warehouse, "/" + id + "/recover-original", body, version, request)));
        assertEquals(loan, db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020"));
        assertRecovered(id);
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_todo WHERE business_type='LOAN' AND"
                                + " business_id=? AND todo_type='OVERDUE' AND closed_at IS NULL",
                        Long.class,
                        loan.get("id")));
    }

    @Test
    void closedLossRequiresNewApprovalAndNewExclusiveOwnerAndKeepsHistoricLoss() throws Exception {
        var row = confirmedReport();
        String id = row.path("id").asText();
        var original =
                db.queryForMap(
                        "SELECT confirmation_snapshot_json,confirmed_lost_at,source_snapshot_json"
                                + " FROM fams_loss_report WHERE id=?",
                        id);
        assertEquals(
                409,
                post(
                                warehouse,
                                "/" + id + "/recover-original",
                                recoveryBody(row, null),
                                row.path("version").asLong(),
                                key())
                        .statusCode());
        var requested =
                ok(
                        post(
                                employee,
                                "/" + id + "/recovery-requests",
                                recoveryRequest(row),
                                row.path("version").asLong(),
                                key()));
        String recovery = requested.path("recoveryId").asText(), path = "/recoveries/" + recovery;
        assertEquals(409, post(warehouse, path + "/claim", null, 0, key()).statusCode());
        assertEquals(403, post(employee, path + "/decision", decision(), 0, key()).statusCode());
        ok(post(admin, path + "/decision", decision(), 0, key()));
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one =
                    pool.submit(
                            () -> {
                                start.await();
                                return post(warehouse, path + "/claim", null, 1, key())
                                        .statusCode();
                            });
            var two =
                    pool.submit(
                            () -> {
                                start.await();
                                return post(otherWarehouse, path + "/claim", null, 1, key())
                                        .statusCode();
                            });
            start.countDown();
            assertEquals(
                    List.of(200, 409),
                    java.util.stream.Stream.of(one.get(), two.get()).sorted().toList());
        }
        row = ok(get(warehouse, "/" + id));
        var attempt = row.path("recoveries").get(0);
        Session winner =
                attempt.path("processorAccountId").asLong() == 951003 ? warehouse : otherWarehouse;
        Session loser = winner == warehouse ? otherWarehouse : warehouse;
        String body = recoveryBody(row, attempt), request = key();
        long version = attempt.path("version").asLong();
        assertEquals(409, post(loser, path + "/execute", body, version, key()).statusCode());
        var result = ok(post(winner, path + "/execute", body, version, request));
        assertEquals("CONFIRMED_LOST", result.path("status").asText());
        assertEquals("COMPLETED", result.path("recoveryStatus").asText());
        assertEquals(result, ok(post(winner, path + "/execute", body, version, request)));
        assertEquals(
                original,
                db.queryForMap(
                        "SELECT confirmation_snapshot_json,confirmed_lost_at,source_snapshot_json"
                                + " FROM fams_loss_report WHERE id=?",
                        id));
        assertRecovered(id);
        assertEquals(0, ok(get(employee, "/follow-ups")).path("total").asInt());
        assertFalse(ok(get(winner, "/" + id)).path("actions").path("followUp").asBoolean());
    }

    @Test
    void recoveryRejectAndWithdrawalReleaseOnlyOwnLockAndKeepLostLiability() throws Exception {
        var row = confirmedReport();
        String id = row.path("id").asText();
        for (String action : List.of("decision", "cancel")) {
            row = ok(get(employee, "/" + id));
            String body = recoveryRequest(row), request = key();
            long version = row.path("version").asLong();
            var result =
                    ok(post(employee, "/" + id + "/recovery-requests", body, version, request));
            String path = "/recoveries/" + result.path("recoveryId").asText() + "/" + action;
            ok(
                    post(
                            action.equals("decision") ? admin : employee,
                            path,
                            action.equals("decision")
                                    ? "{\"result\":\"REJECT\",\"reason\":\"本次证据需重新核对\"}"
                                    : "{\"reason\":\"发现信息需重新核对\"}",
                            0,
                            key()));
            assertEquals(
                    result,
                    ok(post(employee, "/" + id + "/recovery-requests", body, version, request)));
            assertEquals(
                    "LOST",
                    db.queryForObject(
                            "SELECT exception_status FROM fams_asset WHERE id=951020",
                            String.class));
            assertEquals(
                    0L,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=951020"
                                    + " AND released_at IS NULL",
                            Long.class));
            assertEquals(
                    1L,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_todo WHERE business_type='LOST_REPORT' AND"
                                + " business_id=? AND todo_type='LOST_FOLLOW_UP' AND closed_at IS"
                                + " NULL",
                            Long.class,
                            id));
        }
    }

    @Test
    void originalRecoveryRejectsWrongMachineChangedLocationDamageAndUnlawfulCustodian()
            throws Exception {
        var row = searchingReport();
        String id = row.path("id").asText();
        long version = row.path("version").asLong();
        for (var invalid :
                List.of(
                        Map.of("observedAssetCode", "OTHER-MACHINE"),
                        Map.of("observedSerialNumber", "WRONG-SERIAL"),
                        Map.of("locationId", "951000"),
                        Map.of("locationVersion", 999),
                        Map.of("condition", "DAMAGED"),
                        Map.of("stillOriginalCustodian", false))) {
            var body = JSON.readTree(recoveryBody(row, null));
            ((tools.jackson.databind.node.ObjectNode) body.get("facts"))
                    .setAll((tools.jackson.databind.node.ObjectNode) JSON.valueToTree(invalid));
            assertEquals(
                    409,
                    post(
                                    warehouse,
                                    "/" + id + "/recover-original",
                                    JSON.writeValueAsString(body),
                                    version,
                                    key())
                            .statusCode());
        }
        db.update("UPDATE fams_employee SET status='DISABLED' WHERE id=951002");
        assertEquals(
                409,
                post(
                                warehouse,
                                "/" + id + "/recover-original",
                                recoveryBody(row, null),
                                version,
                                key())
                        .statusCode());
        assertEquals(
                "SEARCHING",
                db.queryForObject(
                        "SELECT exception_status FROM fams_asset WHERE id=951020", String.class));
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=951020 AND"
                                + " released_at IS NULL",
                        Long.class));
    }

    @Test
    void failedRecoveryAuditRollsBackAssetLockReminderAndPermanentReceipt() throws Exception {
        var row = confirmedReport();
        String id = row.path("id").asText();
        var requested =
                ok(
                        post(
                                employee,
                                "/" + id + "/recovery-requests",
                                recoveryRequest(row),
                                row.path("version").asLong(),
                                key()));
        String path = "/recoveries/" + requested.path("recoveryId").asText();
        ok(post(admin, path + "/decision", decision(), 0, key()));
        ok(post(otherWarehouse, path + "/claim", null, 1, key()));
        row = ok(get(otherWarehouse, "/" + id));
        var attempt = row.path("recoveries").get(0);
        String body = recoveryBody(row, attempt), request = key();
        var before = recoveryDatabaseFacts();
        db.execute(
                "CREATE TRIGGER owned_repair_audit_failure BEFORE INSERT ON fams_audit_event FOR"
                        + " EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Owned loss audit"
                        + " failure'");
        assertEquals(
                500,
                post(
                                otherWarehouse,
                                path + "/execute",
                                body,
                                attempt.path("version").asLong(),
                                request)
                        .statusCode());
        assertEquals(before, recoveryDatabaseFacts());
        db.execute("DROP TRIGGER owned_repair_audit_failure");
        ok(
                post(
                        otherWarehouse,
                        path + "/execute",
                        body,
                        attempt.path("version").asLong(),
                        request));
        assertRecovered(id);
    }

    @Test
    void recoveryChecksEveryVersionPhysicalProofAndForeignLockWithoutAnyWrite() throws Exception {
        var row = searchingReport();
        String id = row.path("id").asText(),
                path = "/" + id + "/recover-original",
                original = recoveryBody(row, null);
        long version = row.path("version").asLong();
        var before = recoveryDatabaseFacts();
        assertEquals(409, post(warehouse, path, original, version - 1, key()).statusCode());
        for (String field : List.of("assetVersion", "ownershipVersion", "lockVersion")) {
            var body = (tools.jackson.databind.node.ObjectNode) JSON.readTree(original);
            body.put(field, 999);
            assertEquals(
                    409,
                    post(warehouse, path, JSON.writeValueAsString(body), version, key())
                            .statusCode());
        }
        for (var invalid :
                List.of(
                        Map.of("physicalIdentityVerified", false),
                        Map.of("foundAt", "2099-10-30T00:00:00Z"),
                        Map.of("evidence", "不够字数"))) {
            var body = JSON.readTree(original);
            ((tools.jackson.databind.node.ObjectNode) body.get("facts"))
                    .setAll((tools.jackson.databind.node.ObjectNode) JSON.valueToTree(invalid));
            assertEquals(
                    400,
                    post(warehouse, path, JSON.writeValueAsString(body), version, key())
                            .statusCode());
        }
        assertEquals(before, recoveryDatabaseFacts());
        db.update(
                "UPDATE fams_asset_business_lock SET business_type='REPAIR' WHERE asset_id=951020"
                    + " AND released_at IS NULL");
        before = recoveryDatabaseFacts();
        assertEquals(409, post(warehouse, path, original, version, key()).statusCode());
        assertEquals(before, recoveryDatabaseFacts());
    }

    @Test
    void concurrentRecoveryRequestsHaveOneWinnerAndCannotOverrideLiveRecovery() throws Exception {
        var row = confirmedReport();
        String id = row.path("id").asText(),
                body = recoveryRequest(row),
                path = "/" + id + "/recovery-requests",
                first = key(),
                second = key();
        long version = row.path("version").asLong();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one =
                    pool.submit(
                            () -> {
                                start.await();
                                return post(employee, path, body, version, first);
                            });
            var two =
                    pool.submit(
                            () -> {
                                start.await();
                                return post(employee, path, body, version, second);
                            });
            start.countDown();
            var left = one.get();
            var right = two.get();
            assertEquals(
                    List.of(200, 409),
                    java.util.stream.Stream.of(left.statusCode(), right.statusCode())
                            .sorted()
                            .toList());
            String winner = left.statusCode() == 200 ? first : second;
            assertEquals(
                    409,
                    post(employee, path, body.replace("已对照原机编码", "重新修改原机编码"), version, winner)
                            .statusCode());
        }
        row = ok(get(employee, "/" + id));
        var before = recoveryDatabaseFacts();
        assertEquals(
                409, post(employee, path, body, row.path("version").asLong(), key()).statusCode());
        assertEquals(before, recoveryDatabaseFacts());
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_loss_recovery WHERE report_id=?",
                        Long.class,
                        id));
    }

    @Test
    void recoveryPreservesFutureBorrowingDatesAndRejectsMismatchedLoanIdentity() throws Exception {
        borrowing(false);
        db.update(
                "UPDATE fams_loan SET parameter_snapshot_json=? WHERE asset_id=951020",
                JSON.writeValueAsString(Map.of("timezone", "Asia/Shanghai")));
        var loan = db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020");
        var row = confirmedReport();
        String id = row.path("id").asText();
        db.update("UPDATE fams_loan SET borrower_employee_id=951007 WHERE asset_id=951020");
        var failure =
                post(
                        employee,
                        "/" + id + "/recovery-requests",
                        recoveryRequest(row),
                        row.path("version").asLong(),
                        key());
        assertEquals(409, failure.statusCode());
        assertEquals(
                "LOSS_LOAN_IDENTITY_CHANGED", JSON.readTree(failure.body()).path("code").asText());
        db.update("UPDATE fams_loan SET borrower_employee_id=951002 WHERE asset_id=951020");
        var requested =
                ok(
                        post(
                                employee,
                                "/" + id + "/recovery-requests",
                                recoveryRequest(row),
                                row.path("version").asLong(),
                                key()));
        String path = "/recoveries/" + requested.path("recoveryId").asText();
        ok(post(admin, path + "/decision", decision(), 0, key()));
        ok(post(otherWarehouse, path + "/claim", null, 1, key()));
        row = ok(get(otherWarehouse, "/" + id));
        ok(
                post(
                        otherWarehouse,
                        path + "/execute",
                        recoveryBody(row, row.path("recoveries").get(0)),
                        2,
                        key()));
        assertEquals(loan, db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020"));
        assertRecovered(id);
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_todo WHERE business_type='LOAN' AND"
                            + " business_id=? AND todo_type IN ('OVERDUE','LOST_LIABILITY') AND"
                            + " closed_at IS NULL",
                        Long.class,
                        loan.get("id")));
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_loan_reminder WHERE loan_id=? AND"
                            + " reminder_type='LOST_LIABILITY'",
                        Long.class,
                        loan.get("id")));
    }

    private Map<String, Object> recoveryDatabaseFacts() {
        var result = new java.util.LinkedHashMap<String, Object>();
        for (String table :
                List.of(
                        "fams_asset",
                        "fams_loss_report",
                        "fams_loss_recovery",
                        "fams_loss_event",
                        "fams_loss_receipt",
                        "fams_asset_business_lock",
                        "fams_todo",
                        "fams_asset_timeline_event",
                        "fams_loan",
                        "fams_loan_reminder")) {
            result.put(
                    table,
                    db.queryForList("SELECT * FROM " + table).stream()
                            .map(JSON::writeValueAsString)
                            .sorted()
                            .toList());
        }
        return result;
    }

    private void assertRecovered(String id) {
        assertEquals(
                "NORMAL",
                db.queryForObject(
                        "SELECT exception_status FROM fams_asset WHERE id=951020", String.class));
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_loss_report WHERE id=? AND recovered_at IS NOT"
                                + " NULL",
                        Long.class,
                        id));
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=951020 AND"
                                + " released_at IS NULL",
                        Long.class));
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_todo WHERE business_type='LOST_REPORT' AND"
                                + " business_id=? AND closed_at IS NULL",
                        Long.class,
                        id));
    }

    private JsonNode confirmedReport() throws Exception {
        var row = searchingReport();
        String id = row.path("id").asText();
        ok(
                post(
                        warehouse,
                        "/" + id + "/confirm-lost",
                        confirmation(row),
                        row.path("version").asLong(),
                        key()));
        return ok(get(warehouse, "/" + id));
    }

    private Map<String, Object> recoveryFacts(JsonNode row) {
        var asset = row.path("recoveryAsset");
        return Map.of(
                "foundAt",
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),
                "observedAssetCode",
                asset.path("assetCode").asText(),
                "observedSerialNumber",
                asset.path("serialNumber").asText(""),
                "locationId",
                asset.path("locationId").asText(),
                "locationVersion",
                asset.path("locationVersion").asLong(),
                "physicalIdentityVerified",
                true,
                "condition",
                "INTACT",
                "stillOriginalCustodian",
                true,
                "evidence",
                "已对照原机编码并亲见完好实物仍由原保管人在原登记位置保管");
    }

    private String recoveryRequest(JsonNode row) {
        return JSON.writeValueAsString(
                Map.of(
                        "assetVersion",
                        row.path("recoveryAsset").path("version").asLong(),
                        "facts",
                        recoveryFacts(row)));
    }

    private String recoveryBody(JsonNode row, JsonNode recovery) {
        var owner = recovery == null ? row : recovery;
        return JSON.writeValueAsString(
                Map.of(
                        "assetVersion",
                        owner.path("assetVersion").asLong(),
                        "ownershipVersion",
                        owner.path("ownershipVersion").asLong(),
                        "lockVersion",
                        row.path("lockVersion").asLong(),
                        "facts",
                        recoveryFacts(row)));
    }

    private JsonNode searchingReport() throws Exception {
        var row = ok(post(employee, "", submission(951020), 0, key()));
        String id = row.path("id").asText();
        row = addPhoto(id);
        row =
                ok(
                        post(
                                admin,
                                "/" + id + "/decision",
                                decision(),
                                row.path("version").asLong(),
                                key()));
        row = ok(post(warehouse, "/" + id + "/claim", null, row.path("version").asLong(), key()));
        row = ok(get(warehouse, "/" + id));
        ok(post(warehouse, "/" + id + "/search", search(row), row.path("version").asLong(), key()));
        return ok(get(warehouse, "/" + id));
    }

    private String confirmation(JsonNode row) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("assetVersion", row.path("assetVersion").asLong());
        body.put("ownershipVersion", row.path("ownershipVersion").asLong());
        body.put("lockVersion", row.path("lockVersion").asLong());
        body.put("custodianVerification", "已当面核实最后保管人及原责任事实并保留记录");
        body.put("searchedScope", "已经逐一核查原办公室及所有登记保管位置");
        body.put("relatedClues", "已核对交接维修和盘点记录，尚无找到原物线索");
        body.put("inabilityReason", "无法找回判断依据为现场查找与全部线索均未发现原物");
        body.put("responsibilityEvidence", "原借用或保管责任与最后持有人核实记录共同作为证据");
        body.put(
                "searchedAt",
                Instant.now()
                        .minusSeconds(1)
                        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                        .toString());
        body.put(
                "confirmedAt",
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString());
        return JSON.writeValueAsString(body);
    }

    private String followUp(JsonNode row) {
        return JSON.writeValueAsString(
                Map.of(
                        "ownershipVersion",
                        row.path("ownershipVersion").asLong(),
                        "reviewedAt",
                        Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),
                        "findings",
                        "本轮再次核实原保管人和登记位置仍然没有发现原物",
                        "reason",
                        "保留原责任并继续跟进查找"));
    }

    private String submission(long id) throws Exception {
        return JSON.writeValueAsString(
                Map.of(
                        "assetId",
                        Long.toString(id),
                        "assetVersion",
                        0,
                        "lastSeenAt",
                        "2026-10-20T00:00:00Z",
                        "lastLocation",
                        "最后明确办公室抽屉",
                        "circumstances",
                        "离开办公室后在保管位置暂未找到实物"));
    }

    private String decision() {
        return "{\"result\":\"APPROVE\",\"reason\":\"批准核实查找，尚未判断遗失\"}";
    }

    private String search(JsonNode row) throws Exception {
        return JSON.writeValueAsString(
                Map.of(
                        "assetVersion",
                        row.path("assetVersion").asLong(),
                        "ownershipVersion",
                        row.path("ownershipVersion").asLong(),
                        "lockVersion",
                        row.path("lockVersion").asLong(),
                        "custodianVerification",
                        "已联系最后保管人核实离开办公室经过",
                        "searchedScope",
                        "已经核查抽屉及所有交接位置且保留线索",
                        "searchedAt",
                        Instant.now()
                                .minusSeconds(1)
                                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                                .toString(),
                        "nextReviewDate",
                        java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                                .plusDays(7)
                                .toString()));
    }

    private JsonNode addPhoto(String id) throws Exception {
        return ok(upload(employee, id, 0, key(), png()));
    }

    private byte[] png() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(
                new java.awt.image.BufferedImage(3, 3, java.awt.image.BufferedImage.TYPE_INT_RGB),
                "png",
                bytes);
        return bytes.toByteArray();
    }

    private void borrowing(boolean expired) {
        db.update(
                "INSERT INTO"
                    + " fams_asset_import_batch(id,actor_account_id,upload_key,source_sha256,source_bytes,source_xlsx,created_at,updated_at)"
                    + " VALUES"
                    + " ('00000000-0000-4000-8000-000000009519',951001,'owned-initial-loan',SHA2('owned"
                    + " initial bytes',256),1,X'01',NOW(3),NOW(3))");
        db.update("UPDATE fams_asset SET lifecycle_status='BORROWED' WHERE id=951020");
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        var borrowed = today.minusDays(30);
        var due = expired ? today.minusDays(1) : today.plusDays(10);
        db.update(
                "INSERT INTO"
                    + " fams_loan(asset_id,borrower_employee_id,using_organization_id,source_type,initial_batch_id,initial_sheet_row,borrowed_date,original_return_date,expected_return_date,parameter_snapshot_json,created_at,updated_at)"
                    + " VALUES"
                    + " (951020,951002,951000,'INITIAL_IMPORT','00000000-0000-4000-8000-000000009519',2,?,?,?,'{}',NOW(3),NOW(3))",
                borrowed,
                due,
                due);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private JsonNode ok(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private Session login(int index) throws Exception {
        var response =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:" + port + "/api/v1/auth/login"))
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                JSON.writeValueAsString(
                                                        Map.of(
                                                                "username",
                                                                "rep" + index,
                                                                "password",
                                                                PASSWORD))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        var body = ok(response);
        return new Session(
                response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                body.path("csrfToken").asText());
    }

    private HttpResponse<String> get(Session s, String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:" + port + "/api/v1/loss-reports" + path))
                        .header("Cookie", s.cookie())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Session s, String path, String body, long version, String key)
            throws Exception {
        return http.send(
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:" + port + "/api/v1/loss-reports" + path))
                        .header("Cookie", s.cookie())
                        .header("X-CSRF-Token", s.csrf())
                        .header("Idempotency-Key", key)
                        .header("If-Match-Version", Long.toString(version))
                        .header("Content-Type", "application/json")
                        .POST(
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(
            Session s, String id, long version, String key, byte[] bytes) throws Exception {
        String boundary = "repair-" + key();
        byte[] prefix =
                ("--"
                                + boundary
                                + "\r\n"
                                + "Content-Disposition: form-data; name=\"file\";"
                                + " filename=\"actual.png\"\r\n"
                                + "Content-Type: image/png\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var data = new java.io.ByteArrayOutputStream();
        data.write(prefix);
        data.write(bytes);
        data.write(
                ("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return http.send(
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/api/v1/loss-reports/"
                                                + id
                                                + "/photos"))
                        .header("Cookie", s.cookie())
                        .header("X-CSRF-Token", s.csrf())
                        .header("Idempotency-Key", key)
                        .header("If-Match-Version", Long.toString(version))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(data.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
