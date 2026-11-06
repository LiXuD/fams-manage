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
        String scraps = " IN (SELECT id FROM fams_scrap_request WHERE asset_id BETWEEN 951020 AND 951023)";
        db.update("DELETE FROM fams_todo WHERE business_type='SCRAP' AND business_id" + scraps);
        db.update("DELETE FROM fams_scrap_disposition WHERE request_id" + scraps);
        db.update("DELETE FROM fams_scrap_event WHERE request_id" + scraps);
        db.update("DELETE FROM fams_scrap_receipt WHERE scrap_request_id" + scraps);
        db.update("DELETE FROM fams_scrap_request WHERE asset_id BETWEEN 951020 AND 951023");
        cleanLinkedReturns();
        cleanLinkedTransfers();
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
        db.update("DELETE FROM fams_location WHERE id BETWEEN 951000 AND 951002");
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
    void actualNativePageHandsDamagedRecoveryToRepairAndReplaysCommittedIntent()
            throws Exception {
        try {
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
                            .resolve("apps/miniprogram/test/runtime-loss-damaged-http.mjs");
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
                    output.contains("\"nativeActualLossDamagedRepair\":true"), output);
            assertEquals(
                    "RECOVERED",
                    db.queryForObject(
                            "SELECT report_status FROM fams_loss_report WHERE asset_id=951020",
                            String.class));
            assertEquals(
                    1L,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_repair_request WHERE asset_id=951020 AND"
                                    + " repair_status='PENDING_ADMIN_DECISION'",
                            Long.class));
            assertEquals(
                    1L,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=951020"
                                    + " AND business_type='REPAIR' AND lock_version=1"
                                    + " AND released_at IS NULL",
                            Long.class));
            assertEquals(
                    "IN_USE",
                    db.queryForObject(
                            "SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class));
            assertEquals(
                    951002L,
                    db.queryForObject(
                            "SELECT responsible_employee_id FROM fams_asset WHERE id=951020",
                            Long.class));
        } finally {
            cleanLinkedRepairs();
        }
    }

    @Test
    void actualNativePageRecoversClosedDamagedLossThroughNewApprovalAndRepair()
            throws Exception {
        try {
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
                            .resolve("apps/miniprogram/test/runtime-loss-closed-damaged-http.mjs");
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
                    output.contains("\"nativeActualLossClosedDamagedRepair\":true"), output);
            assertEquals(
                    "CONFIRMED_LOST",
                    db.queryForObject(
                            "SELECT report_status FROM fams_loss_report WHERE asset_id=951020",
                            String.class));
            assertEquals(
                    1L,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_repair_request WHERE asset_id=951020 AND"
                                    + " repair_status='PENDING_ADMIN_DECISION'",
                            Long.class));
            assertEquals(
                    1L,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=951020"
                                    + " AND business_type='REPAIR' AND lock_version=1"
                                    + " AND released_at IS NULL",
                            Long.class));
            assertEquals(
                    "IN_USE",
                    db.queryForObject(
                            "SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class));
            assertEquals(
                    951002L,
                    db.queryForObject(
                            "SELECT responsible_employee_id FROM fams_asset WHERE id=951020",
                            Long.class));
        } finally {
            cleanLinkedRepairs();
        }
    }

    @Test
    void actualNativePageRecoversClosedLossThroughNewApprovalAndFormalReturn()
            throws Exception {
        try {
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
                            .resolve("apps/miniprogram/test/runtime-loss-closed-return-http.mjs");
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
                    output.contains("\"nativeActualLossClosedReturn\":true"), output);
            assertEquals(
                    "CONFIRMED_LOST",
                    db.queryForObject(
                            "SELECT report_status FROM fams_loss_report WHERE asset_id=951020",
                            String.class));
            assertEquals(
                    1L,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_asset_return WHERE asset_id=951020 AND"
                                    + " return_status='PENDING_APPROVAL'",
                            Long.class));
            assertEquals(
                    1L,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=951020"
                                    + " AND business_type='ASSET_RETURN' AND lock_version=1"
                                    + " AND released_at IS NULL",
                            Long.class));
            assertEquals(
                    "IN_USE",
                    db.queryForObject(
                            "SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class));
            assertEquals(
                    951002L,
                    db.queryForObject(
                            "SELECT responsible_employee_id FROM fams_asset WHERE id=951020",
                            Long.class));
        } finally {
            cleanLinkedReturns();
        }
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
    void confirmedBorrowedLossSupportsLinkedScrapAndRejectionWithoutClosingOriginalBorrowing() throws Exception {
        borrowing(true);
        db.update("UPDATE fams_loan SET parameter_snapshot_json=JSON_OBJECT('timezone','Asia/Shanghai') WHERE asset_id=951020");
        var searching = searchingReport();
        String report = searching.path("id").asText();
        var confirmed = ok(post(warehouse, "/" + report + "/confirm-lost", confirmation(searching),
                searching.path("version").asLong(), key()));
        long sourceVersion = confirmed.path("version").asLong(), assetVersion = confirmed.path("assetVersion").asLong();
        var eligible = ok(get(employee, "/" + report));
        assertEquals(true, eligible.path("actions").path("requestScrap").asBoolean());
        assertEquals(assetVersion, eligible.path("recoveryAsset").path("version").asLong());
        assertEquals(true, ok(get(warehouse, "/" + report)).path("actions").path("requestScrap").asBoolean());

        var originalLoan = db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020");
        var originalLoss = db.queryForMap("SELECT * FROM fams_loss_report WHERE id=?", Long.parseLong(report));
        String from = "/from-loss/" + report, requestKey = key();
        var payload = JSON.createObjectNode().put("assetVersion", assetVersion).put("reason", "原借用资产已确认遗失")
                .put("observedCondition", "持续查找未能收回同一原机").put("proposedDisposition", "按确认遗失依据申请报废");
        String body = JSON.writeValueAsString(payload);
        assertEquals(403, postScrap(other, from, body, sourceVersion, key()).statusCode());
        assertEquals(403, postScrap(otherWarehouse, from, body, sourceVersion, key()).statusCode());
        assertEquals(409, postScrap(employee, from, body, sourceVersion - 1, key()).statusCode());
        db.execute("CREATE TRIGGER owned_repair_audit_failure BEFORE INSERT ON fams_audit_event"
                + " FOR EACH ROW BEGIN IF NEW.action='SCRAP_SUBMITTED' THEN SIGNAL"
                + " SQLSTATE '45000' SET MESSAGE_TEXT='owned linked scrap audit failure'; END IF; END");
        assertEquals(500, postScrap(employee, from, body, sourceVersion, requestKey).statusCode());
        db.execute("DROP TRIGGER owned_repair_audit_failure");
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_scrap_request WHERE asset_id=951020", Long.class));
        assertEquals("BORROWED", db.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class));
        var submitted = ok(postScrap(employee, from, body, sourceVersion, requestKey));
        assertEquals(submitted, ok(postScrap(employee, from, body, sourceVersion, requestKey)));
        assertEquals(409, postScrap(employee, from, body.replace("持续查找", "再次查找"), sourceVersion, requestKey).statusCode());
        assertEquals(409, postScrap(admin, from, body, sourceVersion, key()).statusCode());
        String scrap = submitted.path("id").asText();
        long frozenVersion = submitted.path("assetVersion").asLong();
        var row = db.queryForMap("SELECT source_type,source_business_id,source_snapshot_json FROM fams_scrap_request WHERE id=?", Long.parseLong(scrap));
        assertEquals("LOSS", row.get("source_type"));
        assertEquals(Long.parseLong(report), row.get("source_business_id"));
        var proof = JSON.readTree(row.get("source_snapshot_json").toString()).path("lossEvidence");
        assertEquals(originalLoan.get("id").toString(), proof.path("loanId").asText());
        assertEquals(JSON.readTree(originalLoss.get("confirmation_snapshot_json").toString()), proof.path("confirmation"));
        assertEquals("PENDING_SCRAP", db.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class));
        var frozen = ok(get(warehouse, "/" + report));
        assertEquals(false, frozen.path("actions").path("requestScrap").asBoolean(true));
        assertEquals(false, frozen.path("actions").path("followUp").asBoolean(true));
        assertEquals("PENDING_SCRAP", frozen.path("recoveryAsset").path("lifecycle").asText());
        assertEquals("LOST", db.queryForObject("SELECT exception_status FROM fams_asset WHERE id=951020", String.class));
        assertEquals(409, post(warehouse, "/" + report + "/follow-up", followUp(confirmed), sourceVersion, key()).statusCode());
        var reject = JSON.createObjectNode().put("assetVersion", frozenVersion).put("lockVersion", 0)
                .put("result", "REJECT").put("reason", "保留原遗失与借用关系，继续核查");
        String rejection = JSON.writeValueAsString(reject), rejectKey = key();
        var restored = ok(postScrap(admin, "/" + scrap + "/decision", rejection, 0, rejectKey));
        assertEquals("REJECTED", restored.path("status").asText());
        assertEquals(restored, ok(postScrap(admin, "/" + scrap + "/decision", rejection, 0, rejectKey)));
        assertEquals("BORROWED", db.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class));
        assertEquals("LOST", db.queryForObject("SELECT exception_status FROM fams_asset WHERE id=951020", String.class));
        assertEquals(originalLoan, db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020"));
        assertEquals(originalLoss, db.queryForMap("SELECT * FROM fams_loss_report WHERE id=?", Long.parseLong(report)));
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=951020 AND released_at IS NULL", Long.class));
        var reopened = ok(get(employee, "/" + report));
        assertEquals(true, reopened.path("actions").path("requestScrap").asBoolean());
        assertEquals(restored.path("assetVersion"), reopened.path("recoveryAsset").path("version"));
        payload.put("assetVersion", restored.path("assetVersion").asLong());
        var resubmitted = ok(postScrap(employee, from, JSON.writeValueAsString(payload), sourceVersion, key()));
        assertEquals("PENDING_ADMIN_DECISION", resubmitted.path("status").asText());
        db.update("UPDATE fams_loan SET version=version+1 WHERE asset_id=951020");
        reject.put("assetVersion", resubmitted.path("assetVersion").asLong());
        var corrective = ok(postScrap(admin, "/" + resubmitted.path("id").asText() + "/decision",
                JSON.writeValueAsString(reject), 0, key()));
        assertEquals("RESTORE_REQUIRED", corrective.path("status").asText());
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=951020 AND released_at IS NULL", Long.class));
    }

    @Test
    void approvedLostScrapAtomicallyWritesOffAndTerminatesBorrowing() throws Exception {
        borrowing(true);
        db.update("UPDATE fams_loan SET parameter_snapshot_json=JSON_OBJECT('timezone','Asia/Shanghai') WHERE asset_id=951020");
        var searching = searchingReport();
        String report = searching.path("id").asText();
        var confirmed = ok(post(warehouse, "/" + report + "/confirm-lost", confirmation(searching),
                searching.path("version").asLong(), key()));
        var loss = db.queryForMap("SELECT * FROM fams_loss_report WHERE id=?", Long.parseLong(report));
        var loan = db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020");
        var payload = JSON.createObjectNode().put("assetVersion", confirmed.path("assetVersion").asLong())
                .put("reason", "已确认遗失申请报废").put("observedCondition", "原资产持续查找未能找回")
                .put("proposedDisposition", "遗失核销");
        var submitted = ok(postScrap(employee, "/from-loss/" + report, payload.toString(),
                confirmed.path("version").asLong(), key()));
        String path = "/" + submitted.path("id").asText();
        long id = submitted.path("id").asLong(), assetVersion = submitted.path("assetVersion").asLong();
        var versions = JSON.createObjectNode().put("assetVersion", assetVersion).put("lockVersion", 0);
        var decision = versions.deepCopy().put("result", "APPROVE").put("reason", "确认遗失证据完整")
                .put("approvedDisposition", "物理销毁");
        assertEquals(409, postScrap(admin, path + "/decision", decision.toString(), 0, key()).statusCode());
        decision.put("approvedDisposition", "LOSS_WRITTEN_OFF");
        ok(postScrap(admin, path + "/decision", decision.toString(), 0, key()));
        var claimed = ok(postScrap(otherWarehouse, path + "/claim", versions.toString(), 1, key()));
        var input = versions.deepCopy().put("ownershipVersion", claimed.path("ownershipVersion").asLong())
                .put("evidence", "经核对原报失证据，确认原资产未找回，办理遗失核销");
        String execution = path + "/execute-loss", body = input.toString(), requestKey = key();
        long version = claimed.path("version").asLong();
        assertEquals(403, postScrap(warehouse, execution, body, version, key()).statusCode());
        assertEquals(409, postScrap(otherWarehouse, execution, body, version - 1, key()).statusCode());
        var frozen = db.queryForMap("SELECT * FROM fams_asset WHERE id=951020");
        db.execute("CREATE TRIGGER owned_repair_audit_failure BEFORE INSERT ON fams_audit_event"
                + " FOR EACH ROW BEGIN IF NEW.action='SCRAP_LOSS_WRITTEN_OFF' THEN SIGNAL"
                + " SQLSTATE '45000' SET MESSAGE_TEXT='owned write-off audit failure'; END IF; END");
        assertEquals(500, postScrap(otherWarehouse, execution, body, version, requestKey).statusCode());
        db.execute("DROP TRIGGER owned_repair_audit_failure");
        assertEquals(frozen, db.queryForMap("SELECT * FROM fams_asset WHERE id=951020"));
        assertEquals(loan, db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020"));
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_scrap_disposition WHERE request_id=?", Long.class, id));
        var executed = ok(postScrap(otherWarehouse, execution, body, version, requestKey));
        assertEquals("EXECUTED", executed.path("status").asText());
        assertEquals(executed, ok(postScrap(otherWarehouse, execution, body, version, requestKey)));
        assertEquals(409, postScrap(otherWarehouse, execution, input.put("evidence", "变更核销依据不允许重用原键").toString(),
                version, requestKey).statusCode());
        assertEquals(409, postScrap(otherWarehouse, execution, body, version, key()).statusCode());
        var asset = db.queryForMap("SELECT * FROM fams_asset WHERE id=951020");
        assertEquals("SCRAPPED", asset.get("lifecycle_status"));
        assertEquals("LOST", asset.get("exception_status"));
        assertEquals(frozen.get("responsible_employee_id"), asset.get("responsible_employee_id"));
        assertEquals(frozen.get("current_location_id"), asset.get("current_location_id"));
        var closed = db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020");
        assertEquals("LOST_TERMINATED", closed.get("relation_status"));
        org.junit.jupiter.api.Assertions.assertNotNull(closed.get("closed_at"));
        assertEquals(null, closed.get("open_asset_id"));
        for (String field : List.of("borrowed_date", "original_return_date", "expected_return_date", "borrower_employee_id")) {
            assertEquals(loan.get(field), closed.get(field));
        }
        assertEquals(loss, db.queryForMap("SELECT * FROM fams_loss_report WHERE id=?", Long.parseLong(report)));
        var disposition = db.queryForMap("SELECT * FROM fams_scrap_disposition WHERE request_id=?", id);
        assertEquals("LOSS_WRITTEN_OFF", disposition.get("disposition_status"));
        assertEquals(null, disposition.get("due_date"));
        var proof = JSON.readTree(disposition.get("completion_snapshot_json").toString()).path("lossEvidence");
        assertEquals(report, proof.path("reportId").asText());
        assertEquals(JSON.readTree(loss.get("confirmation_snapshot_json").toString()), proof.path("confirmation"));
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=951020"
                + " AND released_at IS NULL", Long.class));
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_todo WHERE closed_at IS NULL AND"
                + " ((business_type='LOST_REPORT' AND business_id=?) OR (business_type='SCRAP' AND business_id=?)"
                + " OR (business_type='LOAN' AND business_id=?))", Long.class, Long.parseLong(report), id, loan.get("id")));
    }

    @Test
    void actualNativePageSubmitsAndWritesOffLinkedLossWithOriginalRetries() throws Exception {
        borrowing(true);
        db.update("UPDATE fams_loan SET parameter_snapshot_json=JSON_OBJECT('timezone','Asia/Shanghai') WHERE asset_id=951020");
        var searching = searchingReport();
        String report = searching.path("id").asText();
        ok(post(warehouse, "/" + report + "/confirm-lost", confirmation(searching),
                searching.path("version").asLong(), key()));
        var grants = new java.util.LinkedHashMap<String, Object>();
        for (var account : Map.of("employee", 951002L, "admin", 951001L, "warehouse", 951003L).entrySet()) {
            var identity = new MiniWechatIdentityPort.Identity("owned-native-loss-scrap", account.getKey());
            db.update("INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES (?,?,NOW(3))",
                    account.getValue(), miniSessions.identityHash(identity));
            var grant = miniSessions.start(identity, UUID.randomUUID());
            grants.put(account.getKey(), Map.of("sessionToken", grant.sessionToken(),
                    "accountId", account.getValue().toString(), "bindingId", grant.bindingId()));
        }
        var script = java.nio.file.Path.of(System.getProperty("user.dir")).getParent()
                .resolve("apps/miniprogram/test/runtime-loss-scrap-http.mjs");
        var builder = new ProcessBuilder("rtk", "proxy", System.getProperty("fams.test.node",
                System.getenv().getOrDefault("FAMS_TEST_NODE_EXECUTABLE", "node")), script.toString())
                .redirectErrorStream(true);
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_LOSS_REPORT", report);
        builder.environment().put("FAMS_TEST_LOSS_GRANTS", JSON.writeValueAsString(grants));
        var process = builder.start();
        try {
            org.junit.jupiter.api.Assertions.assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), result);
        org.junit.jupiter.api.Assertions.assertTrue(result.contains("\"nativeLossScrap\":true"), result);
        long scrap = db.queryForObject("SELECT id FROM fams_scrap_request WHERE asset_id=951020", Long.class);
        assertEquals(2L, db.queryForObject("SELECT COUNT(*) FROM fams_scrap_receipt WHERE scrap_request_id=?"
                + " AND (operation LIKE 'SUBMIT_LOSS:%' OR operation LIKE 'EXECUTE_LOSS:%')", Long.class, scrap));
        assertEquals("LOST_TERMINATED", db.queryForObject("SELECT relation_status FROM fams_loan WHERE asset_id=951020", String.class));
    }

    @Test
    void nonBorrowedConfirmedLossRestoresOriginalCustodyAndWritesOffWithoutBorrowing() throws Exception {
        for (long asset : List.of(951020L, 951022L, 951023L)) {
            Session applicant = asset == 951020L ? employee : asset == 951022L ? login(5) : warehouse;
            var original = db.queryForMap("SELECT * FROM fams_asset WHERE id=?", asset);
            var submitted = ok(post(applicant, "", submission(asset), 0, key()));
            String report = submitted.path("id").asText(), lossPath = "/" + report;
            var photographed = ok(upload(applicant, report, submitted.path("version").asLong(), key(), png()));
            var approved = ok(post(admin, lossPath + "/decision", decision(), photographed.path("version").asLong(), key()));
            ok(post(warehouse, lossPath + "/claim", null, approved.path("version").asLong(), key()));
            var row = ok(get(warehouse, lossPath));
            ok(post(warehouse, lossPath + "/search", search(row), row.path("version").asLong(), key()));
            row = ok(get(warehouse, lossPath));
            var confirmed = ok(post(warehouse, lossPath + "/confirm-lost", confirmation(row), row.path("version").asLong(), key()));
            var eligible = ok(get(applicant, lossPath));
            assertEquals(true, eligible.path("actions").path("requestScrap").asBoolean());
            long reportVersion = confirmed.path("version").asLong();
            var body = JSON.createObjectNode().put("assetVersion", eligible.path("recoveryAsset").path("version").asLong())
                    .put("reason", "原资产已确认遗失").put("observedCondition", "查找后仍无法收回同一原机")
                    .put("proposedDisposition", "遗失核销");
            String from = "/from-loss/" + report;
            assertEquals(403, postScrap(other, from, body.toString(), reportVersion, key()).statusCode());
            var first = ok(postScrap(applicant, from, body.toString(), reportVersion, key()));
            var rejection = JSON.createObjectNode().put("assetVersion", first.path("assetVersion").asLong())
                    .put("lockVersion", 0).put("result", "REJECT").put("reason", "保留原保管事实并继续跟进");
            var restored = ok(postScrap(admin, "/" + first.path("id").asText() + "/decision", rejection.toString(), 0, key()));
            assertEquals("REJECTED", restored.path("status").asText());
            assertEquals(original.get("lifecycle_status"), db.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=?", String.class, asset));
            body.put("assetVersion", restored.path("assetVersion").asLong());
            var next = ok(postScrap(applicant, from, body.toString(), reportVersion, key()));
            String scrapPath = "/" + next.path("id").asText();
            var versions = JSON.createObjectNode().put("assetVersion", next.path("assetVersion").asLong()).put("lockVersion", 0);
            var approval = versions.deepCopy().put("result", "APPROVE").put("reason", "核对原报告及保管事实")
                    .put("approvedDisposition", "遗失核销");
            ok(postScrap(admin, scrapPath + "/decision", approval.toString(), 0, key()));
            var claim = ok(postScrap(warehouse, scrapPath + "/claim", versions.toString(), 1, key()));
            var execution = versions.put("ownershipVersion", claim.path("ownershipVersion").asLong())
                    .put("evidence", "核实原报失证据及历史保管关系后执行遗失核销");
            var closed = ok(postScrap(warehouse, scrapPath + "/execute-loss", execution.toString(), 2, key()));
            assertEquals("EXECUTED", closed.path("status").asText());
            var terminal = db.queryForMap("SELECT * FROM fams_asset WHERE id=?", asset);
            assertEquals("SCRAPPED", terminal.get("lifecycle_status"));
            assertEquals("LOST", terminal.get("exception_status"));
            for (String field : List.of("custody_type", "current_location_id", "storage_warehouse_id",
                    "responsible_employee_id", "using_organization_id", "department_responsible_employee_id")) {
                assertEquals(original.get(field), terminal.get(field));
            }
            assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_loan WHERE asset_id=?", Long.class, asset));
            var disposition = db.queryForMap("SELECT disposition_status,completion_snapshot_json FROM fams_scrap_disposition"
                    + " WHERE request_id=?", next.path("id").asLong());
            assertEquals("LOSS_WRITTEN_OFF", disposition.get("disposition_status"));
            var proof = JSON.readTree(disposition.get("completion_snapshot_json").toString()).path("lossEvidence");
            assertEquals(false, proof.has("loanId"));
            assertEquals(report, proof.path("reportId").asText());
        }
    }

    private HttpResponse<String> postScrap(Session session, String path, String body, long version, String key) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/scrap-requests" + path))
                .header("Cookie", session.cookie()).header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", key)
                .header("If-Match-Version", Long.toString(version)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
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

    @Test
    void damagedRecoveryAdoptsSameLockAndRealRepairCompletesWithoutChangingLoan() throws Exception {
        borrowing(false);
        db.update(
                "UPDATE fams_loan SET parameter_snapshot_json=? WHERE asset_id=951020",
                JSON.writeValueAsString(Map.of("timezone", "Asia/Shanghai")));
        var loan = db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020");
        var row = searchingReport();
        String path = "/" + row.path("id").asText() + "/recover-to-repair";
        String body = damagedRecoveryBody(row), request = key();
        var originalLock =
                db.queryForMap(
                        "SELECT * FROM fams_asset_business_lock WHERE asset_id=951020 AND"
                                + " released_at IS NULL");
        try {
            var result = ok(post(warehouse, path, body, row.path("version").asLong(), request));
            assertEquals(
                    result, ok(post(warehouse, path, body, row.path("version").asLong(), request)));
            String repair = result.path("repairId").asText();
            assertFalse(repair.isBlank());
            var lock =
                    db.queryForMap(
                            "SELECT * FROM fams_asset_business_lock WHERE asset_id=951020 AND"
                                    + " released_at IS NULL");
            assertEquals(originalLock.get("id"), lock.get("id"));
            assertEquals("REPAIR", lock.get("business_type"));
            assertEquals(repair, lock.get("business_id").toString());
            assertEquals(
                    ((Number) originalLock.get("lock_version")).longValue() + 1,
                    ((Number) lock.get("lock_version")).longValue());
            assertEquals(
                    "BORROWED",
                    db.queryForObject(
                            "SELECT lifecycle_status FROM fams_asset WHERE id=951020",
                            String.class));
            assertEquals(
                    repair,
                    ok(get(employee, "/" + row.path("id").asText())).path("repairId").asText());
            assertEquals(
                    row.path("id").asText(),
                    JSON.readTree(
                                    db.queryForObject(
                                            "SELECT source_snapshot_json FROM fams_repair_request"
                                                    + " WHERE id=?",
                                            String.class,
                                            repair))
                            .path("lossRecovery")
                            .path("reportId")
                            .asText());
            assertEquals(
                    409,
                    post(warehouse, path, body, row.path("version").asLong(), key()).statusCode());
            var repairRow =
                    ok(
                            repairWrite(
                                    admin,
                                    repair + "/decision",
                                    Map.of(
                                            "result",
                                            "APPROVE",
                                            "allowedMethod",
                                            "INTERNAL_NO_COST",
                                            "reason",
                                            "核实损坏找回来源后批准内部处理"),
                                    0));
            repairRow =
                    ok(
                            repairWrite(
                                    warehouse,
                                    repair + "/claim",
                                    null,
                                    repairRow.path("version").asLong()));
            for (String action : List.of("accept-in-place", "complete-in-place")) {
                long lockVersion =
                        db.queryForObject(
                                "SELECT lock_version FROM fams_asset_business_lock WHERE"
                                        + " asset_id=951020 AND released_at IS NULL",
                                Long.class);
                repairRow =
                        ok(
                                repairWrite(
                                        warehouse,
                                        repair + "/" + action,
                                        Map.of(
                                                "assetVersion",
                                                repairRow.path("assetVersion").asLong(),
                                                "ownershipVersion",
                                                repairRow.path("ownershipVersion").asLong(),
                                                "lockVersion",
                                                lockVersion,
                                                "observedCode",
                                                row.path("recoveryAsset")
                                                        .path("assetCode")
                                                        .asText(),
                                                "actualLocationId",
                                                951001,
                                                "neverLeftCustodian",
                                                true,
                                                "evidence",
                                                "亲见损坏原物在原责任人登记位置完成本次内部维修操作"),
                                        repairRow.path("version").asLong()));
            }
            assertEquals("COMPLETED", repairRow.path("status").asText());
            assertEquals(loan, db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020"));
            assertRecovered(row.path("id").asText());
        } finally {
            cleanLinkedRepairs();
        }
    }

    @Test
    void lateLossAuditFailureRollsBackCreatedRepairAndLockAndCanRetrySameKey() throws Exception {
        var row = searchingReport();
        String path = "/" + row.path("id").asText() + "/recover-to-repair",
                body = damagedRecoveryBody(row),
                request = key();
        var before = linkedRecoveryDatabaseFacts();
        try {
            db.execute(
                    "CREATE TRIGGER owned_loss_link_failure BEFORE INSERT ON fams_loss_event FOR"
                            + " EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Owned late loss"
                            + " failure'");
            assertEquals(
                    500,
                    post(warehouse, path, body, row.path("version").asLong(), request)
                            .statusCode());
            assertEquals(before, linkedRecoveryDatabaseFacts());
            db.execute("DROP TRIGGER owned_loss_link_failure");
            ok(post(warehouse, path, body, row.path("version").asLong(), request));
        } finally {
            db.execute("DROP TRIGGER IF EXISTS owned_loss_link_failure");
            cleanLinkedRepairs();
        }
    }

    @Test
    void damagedRecoveryRejectsWrongHandlerStaleVersionsChangedIdentityAndInvalidImpact()
            throws Exception {
        var row = searchingReport();
        String path = "/" + row.path("id").asText() + "/recover-to-repair",
                body = damagedRecoveryBody(row);
        long version = row.path("version").asLong();
        var before = linkedRecoveryDatabaseFacts();
        assertEquals(403, post(employee, path, body, version, key()).statusCode());
        assertEquals(409, post(otherWarehouse, path, body, version, key()).statusCode());
        assertEquals(409, post(warehouse, path, body, version - 1, key()).statusCode());
        for (String field : List.of("assetVersion", "ownershipVersion", "lockVersion")) {
            var changed = JSON.readTree(body);
            ((tools.jackson.databind.node.ObjectNode) changed.get("recovery")).put(field, 999);
            assertEquals(
                    409,
                    post(warehouse, path, JSON.writeValueAsString(changed), version, key())
                            .statusCode());
        }
        for (var invalid :
                List.of(
                        Map.of("condition", "INTACT"),
                        Map.of("stillOriginalCustodian", false),
                        Map.of("observedAssetCode", "NOT-THE-ORIGINAL"),
                        Map.of("locationVersion", 999))) {
            var changed = JSON.readTree(body);
            ((tools.jackson.databind.node.ObjectNode) changed.path("recovery").get("facts"))
                    .setAll((tools.jackson.databind.node.ObjectNode) JSON.valueToTree(invalid));
            assertEquals(
                    409,
                    post(warehouse, path, JSON.writeValueAsString(changed), version, key())
                            .statusCode());
        }
        var invalid = (tools.jackson.databind.node.ObjectNode) JSON.readTree(body);
        invalid.put("impact", "UNSUPPORTED");
        assertEquals(
                400,
                post(warehouse, path, JSON.writeValueAsString(invalid), version, key())
                        .statusCode());
        assertEquals(before, linkedRecoveryDatabaseFacts());
    }

    @Test
    void competingDamagedAndOriginalRecoveryCannotCreateTwoDestinations() throws Exception {
        var row = searchingReport();
        String path = "/" + row.path("id").asText();
        String damaged = damagedRecoveryBody(row), original = recoveryBody(row, null);
        long version = row.path("version").asLong();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> {
                                start.await();
                                return post(
                                        warehouse,
                                        path + "/recover-to-repair",
                                        damaged,
                                        version,
                                        key());
                            });
            var b =
                    pool.submit(
                            () -> {
                                start.await();
                                return post(
                                        warehouse,
                                        path + "/recover-original",
                                        original,
                                        version,
                                        key());
                            });
            start.countDown();
            assertEquals(
                    List.of(200, 409),
                    java.util.stream.Stream.of(a.get().statusCode(), b.get().statusCode())
                            .sorted()
                            .toList());
            assertEquals(
                    1L,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_loss_event WHERE report_id=? AND action IN"
                                    + " ('LOSS_RECOVERED','LOSS_RECOVERED_TO_REPAIR')",
                            Long.class,
                            row.path("id").asText()));
        } finally {
            cleanLinkedRepairs();
        }
    }

    @Test
    void closedDamagedRecoveryNeedsNewApprovalAndAdoptsItsOwnItemLock() throws Exception {
        borrowing(false);
        db.update("UPDATE fams_loan SET parameter_snapshot_json=? WHERE asset_id=951020",
                JSON.writeValueAsString(Map.of("timezone", "Asia/Shanghai")));
        var loan = db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020");
        var row = confirmedReport();
        String id = row.path("id").asText();
        var history = db.queryForMap("SELECT confirmation_snapshot_json,confirmed_lost_at,"
                + "source_snapshot_json FROM fams_loss_report WHERE id=?", id);
        try {
            assertEquals(409, post(warehouse, "/" + id + "/recover-to-repair",
                    damagedRecoveryBody(row), row.path("version").asLong(), key()).statusCode());
            db.execute("ALTER TABLE fams_loss_recovery AUTO_INCREMENT=951100");
            var requested = ok(post(employee, "/" + id + "/repair-recovery-requests",
                    damagedRequest(row), row.path("version").asLong(), key()));
            String recovery = requested.path("recoveryId").asText(), base = "/recoveries/" + recovery;
            assertFalse(id.equals(recovery));
            assertEquals(409, post(warehouse, base + "/claim", null, 0, key()).statusCode());
            assertEquals(403, post(employee, base + "/decision", decision(), 0, key()).statusCode());
            ok(post(admin, base + "/decision", decision(), 0, key()));
            ok(post(otherWarehouse, base + "/claim", null, 1, key()));
            row = ok(get(otherWarehouse, "/" + id));
            var attempt = row.path("recoveries").get(0);
            String body = damagedExecution(row, attempt);
            long version = attempt.path("version").asLong();
            assertEquals(409, post(warehouse, base + "/execute-to-repair", body, version, key()).statusCode());
            assertEquals(409, post(otherWarehouse, base + "/execute", recoveryBody(row, attempt),
                    version, key()).statusCode());
            var originalLock = db.queryForMap("SELECT * FROM fams_asset_business_lock WHERE"
                    + " asset_id=951020 AND released_at IS NULL");
            assertEquals(Long.parseLong(recovery), ((Number) originalLock.get("business_item_id")).longValue());
            String request = key();
            var result = ok(post(otherWarehouse, base + "/execute-to-repair", body, version, request));
            assertEquals(result, ok(post(otherWarehouse, base + "/execute-to-repair", body, version, request)));
            assertEquals("CONFIRMED_LOST", result.path("status").asText());
            assertEquals("COMPLETED", result.path("recoveryStatus").asText());
            String repair = result.path("repairId").asText();
            assertFalse(repair.isBlank());
            var lock = db.queryForMap("SELECT * FROM fams_asset_business_lock WHERE"
                    + " asset_id=951020 AND released_at IS NULL");
            assertEquals(originalLock.get("id"), lock.get("id"));
            assertEquals("REPAIR", lock.get("business_type"));
            assertEquals(repair, lock.get("business_id").toString());
            assertEquals(1L, ((Number) lock.get("lock_version")).longValue());
            var source = JSON.readTree(db.queryForObject("SELECT source_snapshot_json FROM"
                    + " fams_repair_request WHERE id=?", String.class, repair));
            assertEquals(recovery, source.path("lossRecovery").path("recoveryId").asText());
            assertEquals("PENDING_ADMIN_DECISION", db.queryForObject("SELECT repair_status FROM"
                    + " fams_repair_request WHERE id=?", String.class, repair));
            assertEquals(history, db.queryForMap("SELECT confirmation_snapshot_json,confirmed_lost_at,"
                    + "source_snapshot_json FROM fams_loss_report WHERE id=?", id));
            assertEquals(loan, db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020"));
            assertEquals("NORMAL", db.queryForObject("SELECT exception_status FROM fams_asset WHERE id=951020", String.class));
            assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_todo WHERE business_type='LOST_REPORT' AND business_id=? AND closed_at IS NULL", Long.class, id));
        } finally {
            cleanLinkedRepairs();
        }
    }

    @Test
    void intactApprovalCannotAuthorizeDamageAndDamageRequestCannotUseOrdinaryEntry() throws Exception {
        var row = confirmedReport();
        String id = row.path("id").asText();
        assertEquals(409, post(employee, "/" + id + "/recovery-requests", damagedRequest(row),
                row.path("version").asLong(), key()).statusCode());
        var requested = ok(post(employee, "/" + id + "/recovery-requests", recoveryRequest(row),
                row.path("version").asLong(), key()));
        String base = "/recoveries/" + requested.path("recoveryId").asText();
        ok(post(admin, base + "/decision", decision(), 0, key()));
        ok(post(warehouse, base + "/claim", null, 1, key()));
        row = ok(get(warehouse, "/" + id));
        var before = linkedRecoveryDatabaseFacts();
        assertEquals(409, post(warehouse, base + "/execute-to-repair",
                damagedExecution(row, row.path("recoveries").get(0)), 2, key()).statusCode());
        assertEquals(before, linkedRecoveryDatabaseFacts());
    }

    @Test
    void closedDamagedRecoveryChecksEveryVersionRollsBackLateFailureAndHasOneWinner() throws Exception {
        var row = confirmedReport();
        String id = row.path("id").asText();
        var requested = ok(post(employee, "/" + id + "/repair-recovery-requests",
                damagedRequest(row), row.path("version").asLong(), key()));
        String base = "/recoveries/" + requested.path("recoveryId").asText();
        ok(post(admin, base + "/decision", decision(), 0, key()));
        ok(post(warehouse, base + "/claim", null, 1, key()));
        row = ok(get(warehouse, "/" + id));
        String body = damagedExecution(row, row.path("recoveries").get(0));
        var before = linkedRecoveryDatabaseFacts();
        try {
            for (String field : List.of("assetVersion", "ownershipVersion", "lockVersion")) {
                var invalid = (tools.jackson.databind.node.ObjectNode) JSON.readTree(body);
                ((tools.jackson.databind.node.ObjectNode) invalid.get("recovery")).put(field, 999);
                assertEquals(409, post(warehouse, base + "/execute-to-repair",
                        JSON.writeValueAsString(invalid), 2, key()).statusCode());
                assertEquals(before, linkedRecoveryDatabaseFacts());
            }
            for (String field : List.of("assetVersion", "ownershipVersion", "lockVersion")) {
                var missing = (tools.jackson.databind.node.ObjectNode) JSON.readTree(body);
                ((tools.jackson.databind.node.ObjectNode) missing.get("recovery")).remove(field);
                assertEquals(400, post(warehouse, base + "/execute-to-repair",
                        JSON.writeValueAsString(missing), 2, key()).statusCode());
                assertEquals(before, linkedRecoveryDatabaseFacts());
            }
            assertEquals(409, post(warehouse, base + "/execute-to-repair", body, 1, key()).statusCode());
            assertEquals(before, linkedRecoveryDatabaseFacts());
            String request = key();
            db.execute("CREATE TRIGGER owned_loss_link_failure BEFORE INSERT ON fams_loss_event FOR"
                    + " EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Owned late closed loss failure'");
            assertEquals(500, post(warehouse, base + "/execute-to-repair", body, 2, request).statusCode());
            assertEquals(before, linkedRecoveryDatabaseFacts());
            db.execute("DROP TRIGGER owned_loss_link_failure");
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var one = pool.submit(() -> post(warehouse, base + "/execute-to-repair", body, 2, request).statusCode());
                var two = pool.submit(() -> post(warehouse, base + "/execute-to-repair", body, 2, key()).statusCode());
                assertEquals(List.of(200, 409), java.util.stream.Stream.of(one.get(), two.get()).sorted().toList());
            }
            assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM fams_repair_request WHERE asset_id=951020", Long.class));
        } finally {
            db.execute("DROP TRIGGER IF EXISTS owned_loss_link_failure");
            cleanLinkedRepairs();
        }
    }

    private String damagedRequest(JsonNode row) {
        var input = (tools.jackson.databind.node.ObjectNode) JSON.readTree(recoveryRequest(row));
        ((tools.jackson.databind.node.ObjectNode) input.get("facts")).put("condition", "DAMAGED");
        return JSON.writeValueAsString(input);
    }

    private String damagedExecution(JsonNode row, JsonNode recovery) {
        var input = (tools.jackson.databind.node.ObjectNode) JSON.readTree(recoveryBody(row, recovery));
        ((tools.jackson.databind.node.ObjectNode) input.get("facts")).put("condition", "DAMAGED");
        return JSON.writeValueAsString(Map.of("recovery", input,
                "fault", "原机电源损坏无法工作，已经现场核实实物故障", "impact", "HIGH"));
    }

    @Test
    void changedCustodyRecoveryReturnsBorrowedAssetOnlyAfterApprovalAndActualAcceptance() throws Exception {
        borrowing(true);
        db.update("UPDATE fams_loan SET parameter_snapshot_json=? WHERE asset_id=951020",
                JSON.writeValueAsString(Map.of("timezone", "Asia/Shanghai")));
        long loanId = db.queryForObject("SELECT id FROM fams_loan WHERE asset_id=951020", Long.class);
        loanReminders.refresh(loanId);
        var historicalReminders = db.queryForList("SELECT * FROM fams_loan_reminder WHERE loan_id=?", loanId);
        var row = searchingReport();
        var loan = db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020");
        var originalLock = db.queryForMap("SELECT * FROM fams_asset_business_lock WHERE asset_id=951020 AND released_at IS NULL");
        db.update("UPDATE fams_employee SET status='DISABLED' WHERE id=951002");
        String path = "/" + row.path("id").asText() + "/recover-to-return", body = returnBody(row, null), request = key();
        var result = ok(post(warehouse, path, body, row.path("version").asLong(), request));
        assertEquals(result, ok(post(warehouse, path, body, row.path("version").asLong(), request)));
        String id = result.path("returnId").asText();
        assertFalse(id.isBlank());
        assertEquals(loan, db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020"));
        assertEquals("BORROWED", db.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class));
        assertEquals(951002L, db.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=951020", Long.class));
        var lock = db.queryForMap("SELECT * FROM fams_asset_business_lock WHERE asset_id=951020 AND released_at IS NULL");
        assertEquals(originalLock.get("id"), lock.get("id"));
        assertEquals("ASSET_RETURN", lock.get("business_type"));
        assertEquals(1L, ((Number) lock.get("lock_version")).longValue());
        assertEquals("ASSET_RETURN", JSON.readTree(db.queryForObject("SELECT recovery_snapshot_json FROM fams_loss_report WHERE id=?", String.class, row.path("id").asText())).path("destination").asText());
        assertEquals(403, returnWrite(other, id + "/decision", Map.of("approved", true, "reason", "无权批准"), 0).statusCode());
        ok(returnWrite(admin, id + "/decision", Map.of("approved", false, "reason", "补充移交证据后重送"), 0));
        assertEquals(lock, db.queryForMap("SELECT * FROM fams_asset_business_lock WHERE asset_id=951020 AND released_at IS NULL"));
        assertEquals(409, returnWrite(warehouse, id + "/claim", null, 1).statusCode());
        ok(returnWrite(admin, id + "/resubmit-loss", Map.of("approved", false, "reason", "补充证据完整，重新送审"), 1));
        ok(returnWrite(admin, id + "/decision", Map.of("approved", true, "reason", "核对找回事实批准归还退库"), 2));
        ok(returnWrite(warehouse, id + "/claim", null, 3));
        var accepted = ok(returnWrite(warehouse, id + "/inspect", Map.of("result", "NORMAL", "reason", "已接收完好原机并当场验收入库",
                "observedCode", "REP-ASSET-951020", "warehouseId", 951000, "warehouseVersion", 0,
                "locationId", 951000, "locationVersion", 0, "assetVersion", result.path("assetVersion").asLong(), "lockVersion", 1), 4));
        assertEquals("COMPLETED", accepted.path("returnStatus").asText());
        assertEquals(historicalReminders, db.queryForList("SELECT * FROM fams_loan_reminder WHERE loan_id=?", loanId));
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_todo WHERE business_type='LOAN' AND business_id=? AND closed_at IS NULL", Long.class, loanId));
        assertEquals("RETURNED", db.queryForObject("SELECT relation_status FROM fams_loan WHERE asset_id=951020", String.class));
        var after = db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020");
        for (String date : List.of("borrowed_date", "original_return_date", "expected_return_date")) { assertEquals(loan.get(date), after.get(date)); }
        assertEquals("IN_STOCK", db.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class));
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=951020 AND released_at IS NULL", Long.class));
    }

    @Test
    void closedReturnRecoveryRequiresFreshPurposeApprovalAndAdoptsNewItemWithoutErasingLoss() throws Exception {
        var row = confirmedReport();
        String id = row.path("id").asText();
        var history = db.queryForMap("SELECT confirmation_snapshot_json,confirmed_lost_at,source_snapshot_json FROM fams_loss_report WHERE id=?", id);
        db.execute("ALTER TABLE fams_loss_recovery AUTO_INCREMENT=951100");
        var request = ok(post(employee, "/" + id + "/return-recovery-requests", returnRequest(row), row.path("version").asLong(), key()));
        String rid = request.path("recoveryId").asText(), base = "/recoveries/" + rid;
        assertFalse(id.equals(rid));
        assertEquals(409, post(warehouse, base + "/claim", null, 0, key()).statusCode());
        ok(post(admin, base + "/decision", decision(), 0, key()));
        ok(post(otherWarehouse, base + "/claim", null, 1, key()));
        row = ok(get(otherWarehouse, "/" + id));
        var attempt = row.path("recoveries").get(0);
        assertEquals("ASSET_RETURN", attempt.path("request").path("destination").asText());
        assertEquals(409, post(otherWarehouse, base + "/execute", recoveryBody(row, attempt), 2, key()).statusCode());
        assertEquals(409, post(warehouse, base + "/execute-to-return", returnBody(row, attempt), 2, key()).statusCode());
        var result = ok(post(otherWarehouse, base + "/execute-to-return", returnBody(row, attempt), 2, key()));
        assertEquals("COMPLETED", result.path("recoveryStatus").asText());
        assertEquals("CONFIRMED_LOST", result.path("status").asText());
        assertEquals(history, db.queryForMap("SELECT confirmation_snapshot_json,confirmed_lost_at,source_snapshot_json FROM fams_loss_report WHERE id=?", id));
        assertEquals("PENDING_APPROVAL", db.queryForObject("SELECT return_status FROM fams_asset_return WHERE id=?", String.class, result.path("returnId").asText()));
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_loan WHERE asset_id=951020", Long.class));
    }

    @Test
    void returnRecoveryRejectsMissingVersionsIdentityDriftAndRollsBackEveryOwnerOnLateAuditFailure() throws Exception {
        var row = searchingReport();
        String path = "/" + row.path("id").asText() + "/recover-to-return", body = returnBody(row, null), request = key();
        var before = returnDatabaseFacts();
        var missing = (tools.jackson.databind.node.ObjectNode) JSON.readTree(body);
        missing.remove("assetVersion");
        assertEquals(400, post(warehouse, path, JSON.writeValueAsString(missing), row.path("version").asLong(), key()).statusCode());
        for (String field : List.of("assetVersion", "ownershipVersion", "lockVersion")) {
            var stale = (tools.jackson.databind.node.ObjectNode) JSON.readTree(body);
            stale.put(field, stale.path(field).asLong() + 1);
            assertEquals(409, post(warehouse, path, JSON.writeValueAsString(stale), row.path("version").asLong(), key()).statusCode());
        }
        var wrong = (tools.jackson.databind.node.ObjectNode) JSON.readTree(body);
        ((tools.jackson.databind.node.ObjectNode) wrong.get("facts")).put("observedAssetCode", "WRONG");
        assertEquals(409, post(warehouse, path, JSON.writeValueAsString(wrong), row.path("version").asLong(), key()).statusCode());
        assertEquals(before, returnDatabaseFacts());
        try {
            db.execute("CREATE TRIGGER owned_loss_return_failure BEFORE INSERT ON fams_loss_event FOR EACH ROW"
                    + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Owned late loss return failure'");
            assertEquals(500, post(warehouse, path, body, row.path("version").asLong(), request).statusCode());
            assertEquals(before, returnDatabaseFacts());
        } finally { db.execute("DROP TRIGGER IF EXISTS owned_loss_return_failure"); }
        ok(post(warehouse, path, body, row.path("version").asLong(), request));
    }

    @Test
    void concurrentReturnHandoffHasOneWinnerAndRejectedReturnCannotExposeAvailableStock() throws Exception {
        var row = searchingReport();
        String path = "/" + row.path("id").asText() + "/recover-to-return", body = returnBody(row, null);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var first = executor.submit(() -> {
                start.await();
                return post(warehouse, path, body, row.path("version").asLong(), key());
            });
            var second = executor.submit(() -> {
                start.await();
                return post(warehouse, path, body, row.path("version").asLong(), key());
            });
            start.countDown();
            assertEquals(List.of(200, 409), java.util.stream.Stream.of(first.get().statusCode(), second.get().statusCode()).sorted().toList());
        }
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM fams_asset_return WHERE asset_id=951020", Long.class));
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=951020 AND released_at IS NULL AND business_type='ASSET_RETURN'", Long.class));
        String id = db.queryForObject("SELECT CAST(id AS CHAR) FROM fams_asset_return WHERE asset_id=951020", String.class);
        ok(returnWrite(admin, id + "/decision", Map.of("approved", false, "reason", "核对补充证据后重新送审"), 0));
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=951020 AND released_at IS NULL", Long.class));
        assertEquals("IN_USE", db.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class));
    }

    @Test
    void searchingRecoveryTransfersOnlyAfterApprovalAndActualReceipt() throws Exception {
        var row = searchingReport();
        String path = "/" + row.path("id").asText() + "/recover-to-transfer";
        String key = key();
        var target = transferRecoveryTarget();
        var payload = Map.of("recovery", JSON.readTree(returnBody(row, null)), "target", target,
                "reason", "原机找回后移交新保管人");
        long version = row.path("version").asLong();
        var lock = db.queryForMap("SELECT * FROM fams_asset_business_lock WHERE asset_id=951020"
                + " AND released_at IS NULL");
        assertEquals(409, post(otherWarehouse, path, JSON.writeValueAsString(payload), version, key).statusCode());
        target.put("referenceVersions", Map.of("usingOrganization", 0, "employee", 1, "location", 0));
        assertEquals(409, post(warehouse, path, JSON.writeValueAsString(payload), version, key).statusCode());
        assertEquals(lock, db.queryForMap("SELECT * FROM fams_asset_business_lock WHERE asset_id=951020"
                + " AND released_at IS NULL"));
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_transfer_item WHERE asset_id=951020", Long.class));
        target.put("referenceVersions", Map.of("usingOrganization", 0, "employee", 0, "location", 0));
        String body = JSON.writeValueAsString(payload);
        db.execute("CREATE TRIGGER owned_loss_transfer_failure BEFORE INSERT ON fams_loss_event FOR EACH ROW"
                + " BEGIN IF NEW.action='LOSS_RECOVERED_TO_TRANSFER' THEN SIGNAL SQLSTATE '45000'"
                + " SET MESSAGE_TEXT='owned transfer recovery audit failure'; END IF; END");
        try {
            assertEquals(500, post(warehouse, path, body, version, key).statusCode());
            assertEquals("SEARCHING", db.queryForObject(
                    "SELECT exception_status FROM fams_asset WHERE id=951020", String.class));
            assertEquals(lock, db.queryForMap("SELECT * FROM fams_asset_business_lock WHERE asset_id=951020"
                    + " AND released_at IS NULL"));
            assertEquals(0L, db.queryForObject(
                    "SELECT COUNT(*) FROM fams_transfer_item WHERE asset_id=951020", Long.class));
        } finally {
            db.execute("DROP TRIGGER IF EXISTS owned_loss_transfer_failure");
        }
        JsonNode recovered;
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> post(warehouse, path, body, version, key));
            String secondKey = key();
            var second = executor.submit(() -> post(warehouse, path, body, version, secondKey));
            var responses = List.of(first.get(), second.get());
            assertEquals(List.of(200, 409), responses.stream().map(HttpResponse::statusCode).sorted().toList());
            recovered = ok(responses.stream().filter(response -> response.statusCode() == 200).findFirst().orElseThrow());
            String committedKey = responses.getFirst().statusCode() == 200 ? key : secondKey;
            assertEquals(recovered, ok(post(warehouse, path, body, version, committedKey)));
        }
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM fams_transfer_item WHERE asset_id=951020", Long.class));
        String transfer = recovered.path("transferId").asText();
        assertFalse(transfer.isBlank());
        var adopted = db.queryForMap("SELECT * FROM fams_asset_business_lock WHERE asset_id=951020"
                + " AND released_at IS NULL");
        assertEquals(lock.get("id"), adopted.get("id"));
        assertEquals("TRANSFER", adopted.get("business_type"));
        String id = "/" + transfer;
        ok(transferWrite(admin, id + "/decision", Map.of("approved", false, "reason", "重新核对后送审"), 1));
        assertEquals(adopted, db.queryForMap("SELECT * FROM fams_asset_business_lock WHERE asset_id=951020"
                + " AND released_at IS NULL"));
        ok(transferWrite(warehouse, id + "/submit", null, 2));
        ok(transferWrite(admin, id + "/decision", Map.of("approved", true, "reason", "批准实物调拨"), 3));
        var claimed = ok(transferWrite(warehouse, id + "/claim", null, 4));
        var item = claimed.path("items").get(0);
        String itemId = item.path("id").asText();
        var delivered = ok(transferWrite(warehouse, id + "/items/" + itemId + "/deliver",
                Map.of("observedCode", "REP-ASSET-951020", "destination", "现场新保管点",
                        "evidence", "已实物交出等待接收", "itemVersion", item.path("version").asLong()), 5));
        assertEquals(951002L, db.queryForObject(
                "SELECT responsible_employee_id FROM fams_asset WHERE id=951020", Long.class));
        var receiver = login(6);
        var grant = ok(transferWrite(receiver, "/receipts/" + itemId + "/grant", null, 0));
        ok(transferWrite(receiver, "/receipts/" + itemId + "/receive", Map.of(
                "grant", grant.path("grant").asText(), "observedCode", "REP-ASSET-951020",
                "physicalChecked", true), delivered.path("version").asLong()));
        assertEquals(951006L, db.queryForObject(
                "SELECT responsible_employee_id FROM fams_asset WHERE id=951020", Long.class));
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_asset_business_lock"
                + " WHERE asset_id=951020 AND released_at IS NULL", Long.class));
    }

    @Test
    void closedTransferRecoveryRequiresNewAuthorizationAndPreservesLossHistory() throws Exception {
        var row = confirmedReport();
        String id = row.path("id").asText();
        String historySql = "SELECT confirmation_snapshot_json,confirmed_lost_at,source_snapshot_json"
                + " FROM fams_loss_report WHERE id=?";
        var history = db.queryForMap(historySql, id);
        var requested = ok(post(employee, "/" + id + "/transfer-recovery-requests", returnRequest(row),
                row.path("version").asLong(), key()));
        String recovery = requested.path("recoveryId").asText();
        String base = "/recoveries/" + recovery;
        assertEquals(409, post(warehouse, base + "/claim", null, 0, key()).statusCode());
        ok(post(admin, base + "/decision", decision(), 0, key()));
        ok(post(warehouse, base + "/claim", null, 1, key()));
        row = ok(get(warehouse, "/" + id));
        var attempt = row.path("recoveries").get(0);
        assertEquals("TRANSFER", attempt.path("request").path("destination").asText());
        String facts = returnBody(row, attempt);
        assertEquals(409, post(warehouse, base + "/execute-to-return", facts, 2, key()).statusCode());
        var lock = db.queryForMap("SELECT * FROM fams_asset_business_lock WHERE asset_id=951020"
                + " AND released_at IS NULL");
        assertEquals(Long.parseLong(recovery), ((Number) lock.get("business_item_id")).longValue());
        String body = JSON.writeValueAsString(Map.of("recovery", JSON.readTree(facts),
                "target", transferRecoveryTarget(), "reason", "找到完好原机后正式调拨"));
        String key = key();
        var result = ok(post(warehouse, base + "/execute-to-transfer", body, 2, key));
        assertEquals(result, ok(post(warehouse, base + "/execute-to-transfer", body, 2, key)));
        assertEquals("CONFIRMED_LOST", result.path("status").asText());
        assertEquals("COMPLETED", result.path("recoveryStatus").asText());
        assertEquals(history, db.queryForMap(historySql, id));
        var adopted = db.queryForMap("SELECT * FROM fams_asset_business_lock WHERE asset_id=951020"
                + " AND released_at IS NULL");
        assertEquals(lock.get("id"), adopted.get("id"));
        assertEquals(result.path("transferId").asLong(), ((Number) adopted.get("business_id")).longValue());
    }

    @Test
    void stockRecoveryRemainsLockedUntilWarehouseVerification() throws Exception {
        db.update("INSERT INTO fams_location(id,code,code_normalized,name,location_type,warehouse_id,"
                + "status,created_at,updated_at) VALUES"
                + " (951002,'RECOVERYBIN','RECOVERYBIN','Target bin','BIN',951000,'ACTIVE',NOW(3),NOW(3))");
        var row = ok(post(warehouse, "", submission(951023), 0, key()));
        String path = "/" + row.path("id").asText();
        row = ok(upload(warehouse, row.path("id").asText(), 0, key(), png()));
        row = ok(post(admin, path + "/decision", decision(), row.path("version").asLong(), key()));
        row = ok(post(warehouse, path + "/claim", null, row.path("version").asLong(), key()));
        row = ok(get(warehouse, path));
        ok(post(warehouse, path + "/search", search(row), row.path("version").asLong(), key()));
        row = ok(get(warehouse, path));
        var recovery = (tools.jackson.databind.node.ObjectNode) JSON.readTree(returnBody(row, null));
        ((tools.jackson.databind.node.ObjectNode) recovery.get("facts")).put("locationId", "951001");
        var target = transferRecoveryTarget();
        target.put("custodyType", "STORAGE");
        target.put("warehouseId", "951000");
        target.put("usingOrganizationId", null);
        target.put("employeeId", null);
        target.put("locationId", "951002");
        target.put("referenceVersions", Map.of("warehouse", 0, "location", 0));
        var result = ok(post(warehouse, path + "/recover-to-transfer", JSON.writeValueAsString(Map.of(
                "recovery", recovery, "target", target, "reason", "原机找回后移至已确认仓位")),
                row.path("version").asLong(), key()));
        String transfer = "/" + result.path("transferId").asText();
        var lock = db.queryForMap("SELECT * FROM fams_asset_business_lock WHERE asset_id=951023"
                + " AND released_at IS NULL");
        ok(transferWrite(warehouse, transfer + "/cancel", null, 1));
        assertEquals(lock, db.queryForMap("SELECT * FROM fams_asset_business_lock WHERE asset_id=951023"
                + " AND released_at IS NULL"));
        ok(transferWrite(warehouse, transfer + "/submit", null, 2));
        ok(transferWrite(admin, transfer + "/decision", Map.of("approved", true, "reason", "批准新仓位"), 3));
        var claimed = ok(transferWrite(warehouse, transfer + "/claim", null, 4));
        var item = claimed.path("items").get(0);
        assertEquals(951000L, db.queryForObject(
                "SELECT current_location_id FROM fams_asset WHERE id=951023", Long.class));
        ok(transferWrite(warehouse, transfer + "/items/" + item.path("id").asText() + "/verify",
                Map.of("observedCode", "REP-ASSET-951023", "destination", "新仓位",
                        "evidence", "库管已现场核验实物及目标仓位", "itemVersion", item.path("version").asLong()), 5));
        assertEquals(951002L, db.queryForObject(
                "SELECT current_location_id FROM fams_asset WHERE id=951023", Long.class));
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_asset_business_lock"
                + " WHERE asset_id=951023 AND released_at IS NULL", Long.class));
    }

    private Map<String, Object> transferRecoveryTarget() {
        var target = new java.util.LinkedHashMap<String, Object>();
        target.put("owningOrganizationId", null);
        target.put("custodyType", "PERSONAL");
        target.put("usingOrganizationId", "951000");
        target.put("employeeId", "951006");
        target.put("warehouseId", null);
        target.put("locationId", "951001");
        target.put("referenceVersions", Map.of("usingOrganization", 0, "employee", 0, "location", 0));
        return target;
    }

    private HttpResponse<String> transferWrite(Session session, String path, Object body, long version)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/transfers" + path))
                .header("Cookie", session.cookie()).header("X-CSRF-Token", session.csrf())
                .header("Idempotency-Key", key()).header("If-Match-Version", Long.toString(version))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private void cleanLinkedTransfers() {
        String items = "SELECT id FROM fams_transfer_item WHERE asset_id BETWEEN 951020 AND 951023";
        String transfers = "SELECT id FROM fams_transfer WHERE creator_account_id BETWEEN 951001 AND 951010";
        db.update("DELETE FROM fams_transfer_receipt_grant WHERE item_id IN (" + items + ")");
        db.update("DELETE FROM fams_transfer_event WHERE request_id IN (" + transfers + ")");
        db.update("DELETE FROM fams_todo WHERE business_type='TRANSFER' AND business_id IN (" + transfers + ")");
        db.update("DELETE FROM fams_transfer_item WHERE asset_id BETWEEN 951020 AND 951023");
        db.update("DELETE FROM fams_transfer WHERE creator_account_id BETWEEN 951001 AND 951010");
    }

    private String returnBody(JsonNode row, JsonNode recovery) {
        var body = (tools.jackson.databind.node.ObjectNode) JSON.readTree(recoveryBody(row, recovery));
        var facts = (tools.jackson.databind.node.ObjectNode) body.get("facts");
        facts.put("stillOriginalCustodian", false);
        facts.put("locationId", "951000");
        facts.put("locationVersion", 0);
        facts.put("evidence", "原机实物已找回，在不同实际位置由库管暂管并核对完好，需要关联归还退库。");
        return JSON.writeValueAsString(body);
    }

    private String returnRequest(JsonNode row) {
        return JSON.writeValueAsString(Map.of("assetVersion", row.path("recoveryAsset").path("version").asLong(),
                "facts", JSON.readTree(returnBody(row, null)).get("facts")));
    }

    private HttpResponse<String> returnWrite(Session s, String path, Object body, long version) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/asset-returns/" + path))
                .header("Cookie", s.cookie()).header("X-CSRF-Token", s.csrf()).header("Idempotency-Key", key())
                .header("If-Match-Version", Long.toString(version)).header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> returnDatabaseFacts() {
        var result = new java.util.LinkedHashMap<>(linkedRecoveryDatabaseFacts());
        for (String table : List.of("fams_asset_return", "fams_return_followup", "fams_idempotency_record")) {
            result.put(table, db.queryForList("SELECT * FROM " + table).stream().map(JSON::writeValueAsString).sorted().toList());
        }
        return result;
    }

    private void cleanLinkedReturns() {
        String ids = "SELECT id FROM fams_asset_return WHERE asset_id BETWEEN 951020 AND 951023";
        db.update("DELETE FROM fams_todo WHERE business_type='ASSET_RETURN' AND business_id IN (" + ids + ")");
        db.update("DELETE FROM fams_return_followup WHERE return_id IN (" + ids + ")");
        db.update("DELETE FROM fams_idempotency_record WHERE actor_account_id BETWEEN 951001 AND 951010");
        db.update("DELETE FROM fams_asset_return WHERE asset_id BETWEEN 951020 AND 951023");
    }

    private String damagedRecoveryBody(JsonNode row) {
        var recovery =
                (tools.jackson.databind.node.ObjectNode) JSON.readTree(recoveryBody(row, null));
        ((tools.jackson.databind.node.ObjectNode) recovery.get("facts"))
                .put("condition", "DAMAGED");
        return JSON.writeValueAsString(
                Map.of("recovery", recovery, "fault", "原机在登记位置找到但电源故障无法正常工作", "impact", "MEDIUM"));
    }

    private HttpResponse<String> repairWrite(
            Session session, String path, Object body, long version) throws Exception {
        return http.send(
                HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + port + "/api/v1/repairs/" + path))
                        .header("Cookie", session.cookie())
                        .header("X-CSRF-Token", session.csrf())
                        .header("Idempotency-Key", key())
                        .header("If-Match-Version", Long.toString(version))
                        .header("Content-Type", "application/json")
                        .POST(
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(
                                                JSON.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> linkedRecoveryDatabaseFacts() {
        var result = new java.util.LinkedHashMap<>(recoveryDatabaseFacts());
        for (String table :
                List.of(
                        "fams_repair_request",
                        "fams_repair_event",
                        "fams_repair_receipt",
                        "fams_audit_event")) {
            result.put(
                    table,
                    db.queryForList("SELECT * FROM " + table).stream()
                            .map(JSON::writeValueAsString)
                            .sorted()
                            .toList());
        }
        return result;
    }

    private void cleanLinkedRepairs() {
        String ids = "SELECT id FROM fams_repair_request WHERE asset_id BETWEEN 951020 AND 951023";
        db.update(
                "DELETE FROM fams_todo WHERE business_type='REPAIR' AND business_id IN ("
                        + ids
                        + ")");
        for (String table :
                List.of("fams_repair_decision", "fams_repair_receipt", "fams_repair_event")) {
            db.update("DELETE FROM " + table + " WHERE repair_id IN (" + ids + ")");
        }
        db.update("DELETE FROM fams_repair_request WHERE asset_id BETWEEN 951020 AND 951023");
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
