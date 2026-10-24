package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fams.inventory.InventoryService;

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
import java.util.UUID;

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
@EnabledIfSystemProperty(named = "d6.17.inventory.runtime", matches = "true")
class InventoryObservationHttpRuntimeCase {
    private static java.nio.file.Path photoRoot;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcTemplate db;
    @Autowired InventoryService service;
    @Autowired MiniSessionService miniSessions;
    @Autowired com.fams.attachment.AttachmentCleanupService evidenceCleanup;
    @Autowired com.fams.inventory.InventoryUploadRetention uploadRetention;
    @Autowired com.fams.asset.AssetInitialPublicationPort initialAssets;
    private Session admin;
    private Session employee;
    private Session other;

    private record Session(String cookie, String csrf) {}

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        registry.add("spring.datasource.url", settings::url);
        registry.add("spring.datasource.username", settings::username);
        registry.add("spring.datasource.password", settings::password);
        if (photoRoot == null) {
            try {
                photoRoot = java.nio.file.Files.createTempDirectory("fams-inventory-photos-");
            } catch (java.io.IOException error) {
                throw new IllegalStateException(error);
            }
        }
        registry.add("fams.attachments.quarantine-root", () -> photoRoot.toString());
        registry.add("fams.test.proxy-evidence", () -> "true");
    }

    @BeforeEach
    void seed() throws Exception {
        db.update(
                "INSERT INTO"
                    + " fams_organization(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (933000,'INVOWN','INVOWN','Inventory"
                    + " owned','ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_asset_category(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (933000,'INVOWN','INVOWN','Inventory"
                    + " category','ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_warehouse(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (933000,'INVOWN','INVOWN','Inventory"
                    + " warehouse','ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_location(id,code,code_normalized,name,location_type,warehouse_id,status,created_at,updated_at)"
                    + " VALUES (933000,'INVOWN','INVOWN','Inventory"
                    + " bin','BIN',933000,'ACTIVE',NOW(3),NOW(3))");
        for (int i = 1; i <= 3; i++) {
            long id = 933000 + i;
            db.update(
                    "INSERT INTO"
                        + " fams_employee(id,employee_no,employee_no_normalized,name,organization_id,status,created_at,updated_at)"
                        + " VALUES (?,?,?,?,933000,'ACTIVE',NOW(3),NOW(3))",
                    id,
                    "INV" + i,
                    "INV" + i,
                    "Inventory person " + i);
            db.update(
                    "INSERT INTO"
                        + " fams_system_account(id,employee_id,username,username_normalized,password_hash,status,initial_password_required,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,'ENABLED',FALSE,NOW(3),NOW(3))",
                    id,
                    id,
                    "inv" + i,
                    "INV" + i,
                    new BCryptPasswordEncoder(4).encode(PASSWORD));
            db.update(
                    "INSERT INTO fams_account_role(account_id,role_code,granted_at) VALUES"
                            + " (?,?,NOW(3))",
                    id,
                    i == 1 ? "ASSET_ADMIN" : "EMPLOYEE");
        }
        db.update(
                "INSERT INTO"
                    + " fams_business_parameter_version(id,version,status,effective_at,actor_account_id,actor_username,snapshot_json,created_at)"
                    + " VALUES"
                    + " (933000,933000,'ACTIVE',NOW(3),933001,'inv1','{\"timezone\":\"Asia/Shanghai\"}',NOW(3))");
        db.update(
                "UPDATE fams_business_parameter_state SET"
                        + " current_version=933000,current_version_id=933000 WHERE singleton_id=1");
        for (long id : new long[] {933010, 933011}) {
            db.update(
                    "INSERT INTO"
                        + " fams_asset(id,asset_code,name,category_id,lifecycle_status,exception_status,card_stage,custody_type,storage_warehouse_id,current_location_id,created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by)"
                        + " VALUES (?,?,'Inventory"
                        + " asset',933000,'IN_STOCK','NORMAL','ACTIVE','STORAGE',933000,933000,CURRENT_DATE,933000,933001,'inv1',NOW(3),NOW(3),933001,933001)",
                    id,
                    "INV-ASSET-" + id);
        }
        db.update(
                "INSERT INTO"
                    + " fams_asset(id,asset_code,name,category_id,lifecycle_status,exception_status,card_stage,created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by)"
                    + " VALUES"
                    + " (933012,'INV-DRAFT','Unpublished',933000,'WAITING_INBOUND','NORMAL','DRAFT',CURRENT_DATE,933000,933001,'inv1',NOW(3),NOW(3),933001,933001)");
        admin = login(1);
        employee = login(2);
        other = login(3);
    }

    @AfterEach
    void cleanup() {
        db.update(
                "DELETE FROM fams_todo WHERE business_type='INVENTORY_LATE' AND business_id IN (SELECT observation_id FROM fams_inventory_late_case WHERE task_id IN (SELECT id FROM fams_inventory_task WHERE creator_account_id=933001))");
        db.update(
                "DELETE FROM fams_inventory_late_decision WHERE observation_id IN (SELECT observation_id FROM fams_inventory_late_case WHERE task_id IN (SELECT id FROM fams_inventory_task WHERE creator_account_id=933001))");
        db.update("DELETE FROM fams_inventory_late_damage_decision WHERE observation_id IN (SELECT observation_id FROM fams_inventory_late_case WHERE task_id IN (SELECT id FROM fams_inventory_task WHERE creator_account_id=933001))");
        db.update("DELETE FROM fams_inventory_late_damage WHERE observation_id IN (SELECT observation_id FROM fams_inventory_late_case WHERE task_id IN (SELECT id FROM fams_inventory_task WHERE creator_account_id=933001))");
        db.update(
                "DELETE FROM fams_inventory_late_case WHERE task_id IN (SELECT id FROM fams_inventory_task WHERE creator_account_id=933001)");
        for (String table : new String[] {"fams_inventory_sealed_item", "fams_inventory_sealed_evidence", "fams_inventory_sealed_sync", "fams_inventory_sealed_report", "fams_inventory_finding_recollection", "fams_inventory_selection_event", "fams_inventory_unobserved_reason", "fams_inventory_item_review", "fams_inventory_finding_link", "fams_inventory_review_decision",
                "fams_inventory_review_receipt", "fams_inventory_review_subject"}) {
            db.update("DELETE FROM " + table + " WHERE task_id IN"
                    + " (SELECT id FROM fams_inventory_task WHERE creator_account_id=933001)");
        }
        String ownedInbound = " IN (SELECT id FROM fams_inbound_receipt WHERE creator_account_id=933003)";
        db.update("DELETE FROM fams_todo WHERE business_type='INBOUND' AND business_id" + ownedInbound);
        db.update("DELETE FROM fams_asset_business_lock WHERE business_type='INBOUND' AND business_id" + ownedInbound);
        db.update("DELETE FROM fams_inbound_acceptance WHERE receipt_item_id IN"
                + " (SELECT id FROM fams_inbound_receipt_item WHERE receipt_id" + ownedInbound + ")");
        db.update("DELETE FROM fams_inbound_approval_scope_item WHERE decision_id IN"
                + " (SELECT id FROM fams_inbound_decision WHERE receipt_id" + ownedInbound + ")");
        for (String table : new String[] {"fams_inbound_processing_ownership", "fams_inbound_decision", "fams_inbound_receipt_item"}) {
            db.update("DELETE FROM " + table + " WHERE receipt_id" + ownedInbound);
        }
        db.update("DELETE FROM fams_inbound_receipt WHERE creator_account_id=933003");
        db.update("DELETE FROM fams_asset_timeline_event WHERE asset_id=933012");
        db.update("DELETE FROM fams_number_allocation WHERE namespace_key='INBOUND_RECEIPT:ENTERPRISE:FIN:NONE:'");
        db.update("DELETE FROM fams_number_sequence WHERE namespace_key='INBOUND_RECEIPT:ENTERPRISE:FIN:NONE:'");
        db.update("DELETE FROM fams_numbering_rule WHERE id=933090");
        db.update(
                "DELETE FROM fams_attachment_deletion_job WHERE object_id IN (SELECT object_id FROM"
                        + " fams_attachment WHERE business_type='INVENTORY_EVENT')");
        db.update(
                "DELETE FROM fams_attachment_cleanup_audit WHERE object_id IN (SELECT object_id"
                        + " FROM fams_attachment WHERE business_type='INVENTORY_EVENT')");
        db.update(
                "DELETE FROM fams_inventory_photo_receipt WHERE scope_id IN (SELECT id FROM"
                        + " fams_inventory_upload_scope WHERE actor_account_id BETWEEN 933001 AND"
                        + " 933003)");
        db.update(
                "DELETE FROM fams_attachment WHERE business_type='INVENTORY_EVENT' AND business_id"
                        + " IN (SELECT id FROM fams_inventory_upload_scope WHERE actor_account_id"
                        + " BETWEEN 933001 AND 933003)");
        db.update(
                "DELETE FROM fams_attachment_scope WHERE business_type='INVENTORY_EVENT' AND"
                        + " business_id IN (SELECT id FROM fams_inventory_upload_scope WHERE"
                        + " actor_account_id BETWEEN 933001 AND 933003)");
        try (var files = java.nio.file.Files.walk(photoRoot)) {
            for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (!file.equals(photoRoot)) {
                    java.nio.file.Files.deleteIfExists(file);
                }
            }
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
        db.update(
                "DELETE FROM fams_inventory_round_result WHERE item_id IN (SELECT id FROM"
                    + " fams_inventory_item WHERE task_id IN (SELECT id FROM fams_inventory_task"
                    + " WHERE creator_account_id=933001))");
        db.update(
                "DELETE FROM fams_inventory_observation WHERE actor_account_id BETWEEN 933001 AND"
                        + " 933003");
        db.update(
                "DELETE FROM fams_inventory_upload_scope WHERE actor_account_id BETWEEN 933001 AND"
                        + " 933003");
        db.update(
                "DELETE FROM fams_inventory_clock_calibration WHERE actor_account_id BETWEEN 933001"
                        + " AND 933003");
        db.update("DELETE FROM fams_mini_session WHERE account_id BETWEEN 933001 AND 933003");
        db.update("DELETE FROM fams_mini_binding WHERE account_id BETWEEN 933001 AND 933003");
        for (String table :
                new String[] {
                    "fams_inventory_assignment",
                    "fams_inventory_participant",
                    "fams_inventory_task_event",
                    "fams_inventory_item"
                }) {
            db.update(
                    "DELETE FROM "
                            + table
                            + " WHERE task_id IN (SELECT id FROM fams_inventory_task WHERE"
                            + " creator_account_id=933001)");
        }
        db.update(
                "DELETE FROM fams_inventory_task WHERE creator_account_id=933001 AND"
                        + " copied_from_task_id IS NOT NULL");
        db.update("DELETE FROM fams_inventory_task WHERE creator_account_id=933001");
        db.update(
                "DELETE FROM fams_idempotency_record WHERE actor_account_id BETWEEN 933001 AND"
                        + " 933003");
        db.update("DELETE FROM fams_audit_event WHERE actor_account_id BETWEEN 933001 AND 933003");
        db.update("DELETE FROM fams_asset WHERE id BETWEEN 933010 AND 933012");
        db.update(
                "UPDATE fams_business_parameter_state SET current_version=0,current_version_id=NULL"
                        + " WHERE singleton_id=1");
        db.update("DELETE FROM fams_business_parameter_version WHERE id=933000");
        db.update("DELETE FROM fams_security_event WHERE account_id BETWEEN 933001 AND 933003");
        db.update("DELETE FROM fams_account_role WHERE account_id BETWEEN 933001 AND 933003");
        db.update("DELETE FROM fams_system_account WHERE id BETWEEN 933001 AND 933003");
        db.update("DELETE FROM fams_employee WHERE id BETWEEN 933001 AND 933003");
        for (String table :
                new String[] {
                    "fams_location", "fams_warehouse", "fams_asset_category", "fams_organization"
                }) {
            db.update("DELETE FROM " + table + " WHERE id=933000");
        }
    }

    @Test
    void normalDuplicateConflictReplayAndCancellationUseRealHttpAndMysql() throws Exception {
        String task = prepared();
        var event = observation(task);
        var first = ok(observe(employee, task, event));
        assertEquals("ACCEPTED", first.path("outcome").asText());
        assertTrue(first.path("counted").asBoolean());
        assertEquals(first, ok(observe(employee, task, event)));
        var duplicate = event.deepCopy();
        duplicate.put("eventId", key());
        assertEquals("DUPLICATE", ok(observe(employee, task, duplicate)).path("outcome").asText());
        var conflict = event.deepCopy();
        conflict.put("eventId", key());
        ((tools.jackson.databind.node.ObjectNode) conflict.path("actual"))
                .put("locationId", "933001");
        assertEquals(
                "CONFLICT_REVIEW", ok(observe(employee, task, conflict)).path("outcome").asText());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT SUM(result_count) FROM fams_inventory_item WHERE task_id=?",
                        Integer.class,
                        task));
        assertEquals(
                3,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_observation WHERE task_id=?",
                        Integer.class,
                        task));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset WHERE id IN (933010,933011) AND"
                                + " last_inventory_business_date IS NOT NULL",
                        Integer.class));
        long version =
                db.queryForObject(
                        "SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task);
        assertEquals(
                409,
                call(
                                admin,
                                "/" + task + "/cancel",
                                "{\"reason\":\"cannot discard observed facts\"}",
                                key(),
                                version)
                        .statusCode());
        var changed = event.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) changed.path("actual"))
                .put("locationId", "933001");
        assertEquals(409, observe(employee, task, changed).statusCode());
    }

    @Test
    void reassignThenOldEvidenceAndRevocationDoNotRestoreCountingOrAccess() throws Exception {
        String task = prepared();
        var event = observation(task);
        long item =
                db.queryForObject(
                        "SELECT id FROM fams_inventory_item WHERE task_id=? AND asset_id=933010",
                        Long.class,
                        task);
        long version =
                db.queryForObject(
                        "SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task);
        ok(
                call(
                        admin,
                        "/" + task + "/assignments",
                        "{\"itemIds\":[\""
                                + item
                                + "\"],\"executorAccountId\":\"933003\",\"recheck\":false,\"reason\":\"replace"
                                + " executor\"}",
                        key(),
                        version));
        assertEquals(
                "OLD_ASSIGNMENT_REVIEW",
                ok(observe(employee, task, event)).path("outcome").asText());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT SUM(result_count) FROM fams_inventory_item WHERE task_id=?",
                        Integer.class,
                        task));
        db.update(
                "UPDATE fams_inventory_participant SET revoked_at=NOW(3) WHERE task_id=? AND"
                        + " account_id=933002",
                task);
        assertEquals(403, observe(employee, task, event).statusCode());
    }

    @Test
    void cancelledTaskOnlyAppendsLateEvidenceWithoutOriginalCountsOrVersionChanges()
            throws Exception {
        String task = prepared();
        var event = observation(task);
        long version =
                db.queryForObject(
                        "SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task);
        ok(call(admin, "/" + task + "/cancel", "{\"reason\":\"end collection\"}", key(), version));
        long frozen =
                db.queryForObject(
                        "SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task);
        assertEquals("LATE_REVIEW", ok(observe(employee, task, event)).path("outcome").asText());
        assertEquals(
                frozen,
                db.queryForObject(
                        "SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT captured_result_count FROM fams_inventory_task WHERE id=?",
                        Integer.class,
                        task));
    }

    @Test
    void nativeCollectionSurvivesTwoForcedProcessDeathsAndActualLostHttpReply() throws Exception {
        String task = prepared();
        String code = "inventory-collection-native-933002";
        db.update(
                "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES"
                        + " (933002,?,NOW(3))",
                miniSessions.identityHash(
                        new MiniWechatIdentityPort.Identity("requisition-test-app", code)));
        var state = java.nio.file.Files.createTempDirectory("fams-inventory-collection-");
        var script =
                java.nio.file.Path.of(System.getProperty("user.dir"))
                        .getParent()
                        .resolve("apps/miniprogram/test/runtime-inventory-collection-http.mjs");
        try {
            for (String stage : java.util.List.of("collect", "lost", "retry")) {
                var builder = new ProcessBuilder("rtk", "proxy", "node", script.toString());
                builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
                builder.environment().put("FAMS_TEST_INVENTORY_TASK", task);
                builder.environment().put("FAMS_TEST_INVENTORY_CODE", code);
                builder.environment().put("FAMS_TEST_INVENTORY_STATE", state.toString());
                builder.environment().put("FAMS_TEST_INVENTORY_STAGE", stage);
                var log = state.resolve("run-" + stage + ".log");
                var process =
                        builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
                try {
                    long deadline =
                            System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
                    while (!java.nio.file.Files.exists(state.resolve("ready-" + stage))
                            && process.isAlive()
                            && System.nanoTime() < deadline) {
                        Thread.sleep(50);
                    }
                    assertTrue(
                            java.nio.file.Files.exists(state.resolve("ready-" + stage)),
                            java.nio.file.Files.readString(log));
                    if (stage.equals("retry")) {
                        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                        assertEquals(0, process.exitValue(), java.nio.file.Files.readString(log));
                    }
                } finally {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    if (process.isAlive()) {
                        process.destroyForcibly();
                    }
                    process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                }
            }
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_inventory_observation WHERE task_id=?",
                            Integer.class,
                            task));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT captured_result_count FROM fams_inventory_task WHERE id=?",
                            Integer.class,
                            task));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT SUM(result_count) FROM fams_inventory_item WHERE task_id=?",
                            Integer.class,
                            task));
        } finally {
            try (var files = java.nio.file.Files.walk(state)) {
                for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    java.nio.file.Files.deleteIfExists(file);
                }
            }
        }
    }

    @Test
    void nativeReviewPhotoBytesSurviveForcedDeathOnlyUntilStartupAndRespectCurrentRole()
            throws Exception {
        String task = prepared();
        var event = observation(task);
        byte[] bytes =
                java.util.Base64.getDecoder()
                        .decode(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jSZkAAAAASUVORK5CYII=");
        var selected = ok(uploadPhoto(employee, task, event, key(), bytes));
        var unused = ok(uploadPhoto(employee, task, event, key(), bytes));
        ((tools.jackson.databind.node.ArrayNode) event.path("photoIds"))
                .add(selected.path("objectId").asText());
        assertEquals("ACCEPTED", ok(observe(employee, task, event)).path("outcome").asText());
        String observation =
                db.queryForObject(
                        "SELECT CAST(id AS CHAR) FROM fams_inventory_observation WHERE task_id=?",
                        String.class,
                        task);
        var original =
                db.queryForList("SELECT * FROM fams_inventory_observation WHERE task_id=?", task);
        var identity =
                new MiniWechatIdentityPort.Identity("review-photo-app", "native-photo-admin");
        db.update(
                "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES"
                    + " (933001,?,NOW(3))",
                miniSessions.identityHash(identity));
        var grant = miniSessions.start(identity, UUID.randomUUID());
        String digest =
                java.util.HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var state = java.nio.file.Files.createTempDirectory("fams-native-review-photo-");
        var script =
                java.nio.file.Path.of(System.getProperty("user.dir"))
                        .getParent()
                        .resolve("apps/miniprogram/test/runtime-inventory-review-photos-http.mjs");
        try {
            for (String stage : java.util.List.of("crash", "recover")) {
                var builder = new ProcessBuilder("rtk", "proxy", "node", script.toString());
                builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
                builder.environment().put("FAMS_TEST_INVENTORY_TASK", task);
                builder.environment()
                        .put("FAMS_TEST_INVENTORY_ITEM", event.path("itemId").asText());
                builder.environment().put("FAMS_TEST_PHOTO_OBSERVATION", observation);
                builder.environment()
                        .put("FAMS_TEST_PHOTO_OBJECT", selected.path("objectId").asText());
                builder.environment()
                        .put("FAMS_TEST_PHOTO_UNUSED", unused.path("objectId").asText());
                builder.environment().put("FAMS_TEST_PHOTO_SHA256", digest);
                builder.environment().put("FAMS_TEST_PHOTO_STATE", state.toString());
                builder.environment().put("FAMS_TEST_PHOTO_STAGE", stage);
                builder.environment()
                        .put(
                                "FAMS_TEST_MINI_GRANT",
                                JSON.writeValueAsString(
                                        java.util.Map.of(
                                                "sessionToken",
                                                grant.sessionToken(),
                                                "accountId",
                                                grant.accountId(),
                                                "bindingId",
                                                grant.bindingId())));
                var log = state.resolve("run-" + stage + ".log");
                var process =
                        builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
                try {
                    long deadline =
                            System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
                    while (!java.nio.file.Files.exists(state.resolve("ready-" + stage))
                            && process.isAlive()
                            && System.nanoTime() < deadline) {
                        Thread.sleep(25);
                    }
                    assertTrue(
                            java.nio.file.Files.exists(state.resolve("ready-" + stage)),
                            java.nio.file.Files.readString(log));
                    if (stage.equals("recover")) {
                        db.update(
                                "DELETE FROM fams_account_role WHERE account_id=933001 AND"
                                    + " role_code='ASSET_ADMIN'");
                        java.nio.file.Files.writeString(
                                state.resolve("continue-recover"), "resume");
                        assertTrue(
                                process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS),
                                java.nio.file.Files.readString(log));
                        assertEquals(0, process.exitValue(), java.nio.file.Files.readString(log));
                    }
                } finally {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    if (process.isAlive()) {
                        process.destroyForcibly();
                    }
                    process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                }
                if (stage.equals("crash")) {
                    var preview =
                            JSON.readTree(
                                    java.nio.file.Files.readString(
                                            state.resolve("preview-crash.json")));
                    var file = java.nio.file.Path.of(preview.path("filePath").asText()).normalize();
                    assertTrue(file.startsWith(state.normalize()));
                    assertTrue(java.nio.file.Files.exists(file));
                    assertEquals(digest, preview.path("sha256").asText());
                }
            }
            var proof = JSON.readTree(java.nio.file.Files.readString(state.resolve("proof.json")));
            assertEquals(digest, proof.path("byteSha256").asText());
            for (String check :
                    java.util.List.of(
                            "startupRemovedKilledPreview",
                            "newProcess",
                            "originalReferencesOnly",
                            "roleLossRejectedSignedRead",
                            "roleLossClearedPageAndBytes",
                            "noNewLogin",
                            "grantsNotPersisted")) {
                assertTrue(proof.path(check).asBoolean(), check);
            }
            assertEquals(
                    original,
                    db.queryForList(
                            "SELECT * FROM fams_inventory_observation WHERE task_id=?", task));
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_inventory_item_review WHERE task_id=?",
                            Integer.class,
                            task));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT captured_result_count FROM fams_inventory_task WHERE id=?",
                            Integer.class,
                            task));
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_asset WHERE id=933010 AND (version<>0 OR"
                                + " last_inventory_business_date IS NOT NULL)",
                            Integer.class));
            System.out.println("D6-18A6 native photo runtime proof: " + proof);
        } finally {
            try (var files = java.nio.file.Files.walk(state)) {
                for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    java.nio.file.Files.deleteIfExists(file);
                }
            }
        }
    }

    @Test
    void actualDamagedPhotoLateEvidenceKeepsTodoAndRejectsTextOnlySupplement() throws Exception {
        String task = prepared();
        var event = observation(task);
        ((tools.jackson.databind.node.ObjectNode) event.path("actual")).put("condition", "DAMAGED");
        byte[] bytes = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jSZkAAAAASUVORK5CYII=");
        var photo = ok(uploadPhoto(employee, task, event, key(), bytes));
        ((tools.jackson.databind.node.ArrayNode) event.path("photoIds")).add(photo.path("objectId").asText());
        long version = db.queryForObject("SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task);
        ok(call(admin, "/" + task + "/cancel", "{\"reason\":\"原任务明确取消，已保存离线照片只能作为合法补充线索\"}", key(), version));
        var originalTask = db.queryForMap("SELECT * FROM fams_inventory_task WHERE id=?", task);
        var originalAsset = db.queryForMap("SELECT * FROM fams_asset WHERE id=933010");
        assertEquals("LATE_REVIEW", ok(observe(employee, task, event)).path("outcome").asText());
        long observation = db.queryForObject("SELECT id FROM fams_inventory_observation WHERE task_id=? AND client_event_id=?", Long.class, task, event.path("eventId").asText());
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/inventory-late/tasks/" + task + "/observations/" + observation + "/decisions"))
                .header("Cookie", admin.cookie()).header("X-CSRF-Token", admin.csrf())
                .header("Idempotency-Key", key()).header("If-Match-Version", "0")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(java.util.Map.of(
                        "method", "NOTE", "reason", "只有损坏照片，文字不能替代真实报修或复盘",
                        "evidence", "保留实际上传照片与原观察，独立实物义务仍开放")))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, response.statusCode(), response.body());
        assertEquals("INVENTORY_LATE_RECHECK_REQUIRED", JSON.readTree(response.body()).path("code").asText());
        assertEquals(2L, db.queryForObject("SELECT COUNT(*) FROM fams_todo WHERE business_type='INVENTORY_LATE' AND business_id=? AND closed_at IS NULL", Long.class, observation));
        assertEquals("OPEN", db.queryForObject("SELECT damage_status FROM fams_inventory_late_damage WHERE observation_id=?", String.class, observation));
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_inventory_late_decision WHERE observation_id=?", Long.class, observation));
        assertEquals(originalTask, db.queryForMap("SELECT * FROM fams_inventory_task WHERE id=?", task));
        assertEquals(originalAsset, db.queryForMap("SELECT * FROM fams_asset WHERE id=933010"));
    }

    @Test
    void photoUploadReplaysExactBytesAndRealMetadataBeforeObservation() throws Exception {
        String task = prepared();
        var event = observation(task);
        String photo = key();
        byte[] bytes =
                java.util.Base64.getDecoder()
                        .decode(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jSZkAAAAASUVORK5CYII=");
        var first = ok(uploadPhoto(employee, task, event, photo, bytes));
        assertEquals(first, ok(uploadPhoto(employee, task, event, photo, bytes)));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_attachment WHERE"
                                + " business_type='INVENTORY_EVENT'",
                        Integer.class));
        ((tools.jackson.databind.node.ArrayNode) event.path("photoIds"))
                .add(first.path("objectId").asText());
        assertEquals("ACCEPTED", ok(observe(employee, task, event)).path("outcome").asText());
        String photos =
                "/api/v1/inventory-observations/tasks/"
                        + task
                        + "/events/"
                        + event.path("eventId").asText()
                        + "/photos";
        var listed =
                ok(
                        http.send(
                                HttpRequest.newBuilder(
                                                URI.create("http://127.0.0.1:" + port + photos))
                                        .header("Cookie", employee.cookie())
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString()));
        assertTrue(listed.path("items").get(0).path("createdAt").asText().endsWith("Z"));
        String grantPath = photos + "/" + first.path("objectId").asText() + "/download-grant";
        var grant =
                ok(
                        http.send(
                                HttpRequest.newBuilder(
                                                URI.create("http://127.0.0.1:" + port + grantPath))
                                        .header("Cookie", employee.cookie())
                                        .header("X-CSRF-Token", employee.csrf())
                                        .POST(HttpRequest.BodyPublishers.noBody())
                                        .build(),
                                HttpResponse.BodyHandlers.ofString()));
        String url = "http://127.0.0.1:" + port + grant.path("url").asText();
        var content =
                http.send(
                        HttpRequest.newBuilder(URI.create(url))
                                .header("Cookie", employee.cookie())
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, content.statusCode());
        org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, content.body());
        assertTrue(content.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals(
                403,
                http.send(
                                HttpRequest.newBuilder(URI.create(url))
                                        .header("Cookie", other.cookie())
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString())
                        .statusCode());
        assertEquals(
                403,
                http.send(
                                HttpRequest.newBuilder(
                                                URI.create(url.replace("grant=", "grant=invalid")))
                                        .header("Cookie", employee.cookie())
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString())
                        .statusCode());

        assertEquals(
                403,
                http.send(
                                HttpRequest.newBuilder(
                                                URI.create("http://127.0.0.1:" + port + photos))
                                        .header("Cookie", other.cookie())
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString())
                        .statusCode());
        assertEquals(403, uploadPhoto(other, task, event, key(), bytes).statusCode());
        long count;
        try (var files = java.nio.file.Files.walk(photoRoot)) {
            count = files.filter(java.nio.file.Files::isRegularFile).count();
        }
        assertEquals(1, count);
    }

    @Test
    void expiredFirstUploadDiscardsStagedPhotosButOriginalReceiptStillReplaysAfterExpiry()
            throws Exception {
        String task = prepared();
        var accepted = observation(task);
        var original = ok(observe(employee, task, accepted));
        db.update(
                "UPDATE fams_inventory_clock_calibration SET server_time=UTC_TIMESTAMP(3)-INTERVAL"
                        + " 73 HOUR WHERE id=?",
                accepted.path("calibrationId").asText());
        assertEquals(original, ok(observe(employee, task, accepted)));
        var expired = observation(task);
        String photo = key();
        byte[] bytes =
                java.util.Base64.getDecoder()
                        .decode(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jSZkAAAAASUVORK5CYII=");
        var uploaded = ok(uploadPhoto(employee, task, expired, photo, bytes));
        ((tools.jackson.databind.node.ArrayNode) expired.path("photoIds"))
                .add(uploaded.path("objectId").asText());
        db.update(
                "UPDATE fams_inventory_clock_calibration SET server_time=UTC_TIMESTAMP(3)-INTERVAL"
                        + " 73 HOUR WHERE id=?",
                expired.path("calibrationId").asText());
        Instant old =
                Instant.now()
                        .minusSeconds(73 * 3600)
                        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        expired.put("rawScannedAt", old.toString())
                .put("rawObservedAt", old.toString())
                .put("firstPersistedAt", old.toString())
                .put("expiresAt", old.plusSeconds(72 * 3600).toString());
        db.update(
                "UPDATE fams_inventory_clock_calibration SET client_time=?,server_time=? WHERE"
                        + " id=?",
                java.sql.Timestamp.from(old),
                java.sql.Timestamp.from(old),
                expired.path("calibrationId").asText());
        // A slow first event upload may arrive after abandoned-photo retention already ran.
        db.update(
                "UPDATE fams_inventory_upload_scope SET created_at=? WHERE task_id=? AND"
                        + " client_event_id=?",
                java.sql.Timestamp.from(old),
                task,
                expired.path("eventId").asText());
        uploadRetention.expire(50);
        assertEquals("EXPIRED", ok(observe(employee, task, expired)).path("outcome").asText());
        assertEquals(
                "{}",
                db.queryForObject(
                        "SELECT CAST(facts_json AS CHAR) FROM fams_inventory_observation WHERE"
                                + " client_event_id=?",
                        String.class,
                        expired.path("eventId").asText()));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT captured_result_count FROM fams_inventory_task WHERE id=?",
                        Integer.class,
                        task));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_attachment WHERE object_id=? AND deleted_at IS"
                                + " NOT NULL",
                        Integer.class,
                        uploaded.path("objectId").asText()));
        evidenceCleanup.deletePending(10);
        try (var files = java.nio.file.Files.walk(photoRoot)) {
            assertEquals(0, files.filter(java.nio.file.Files::isRegularFile).count());
        }
        db.update(
                "UPDATE fams_system_account SET status='DISABLED',auth_epoch=auth_epoch+1 WHERE"
                        + " id=933002");
        assertEquals(401, observe(employee, task, accepted).statusCode());
    }

    @Test
    void actualManualFindingBlocksCancellationWithoutCreatingFormalAssetOrCount() throws Exception {
        String task = prepared();
        var event = observation(task);
        event.putNull("itemId");
        event.putNull("assetVersion");
        event.put("source", "MANUAL");
        event.put("manualReason", "No readable registered label");
        event.putNull("assetCode");
        ((tools.jackson.databind.node.ObjectNode) event.path("actual"))
                .put("description", "Physical object awaiting administrator verification");
        byte[] bytes =
                java.util.Base64.getDecoder()
                        .decode(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jSZkAAAAASUVORK5CYII=");
        var photo = ok(uploadPhoto(employee, task, event, key(), bytes));
        ((tools.jackson.databind.node.ArrayNode) event.path("photoIds"))
                .add(photo.path("objectId").asText());
        assertEquals("FINDING_REVIEW", ok(observe(employee, task, event)).path("outcome").asText());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT SUM(result_count) FROM fams_inventory_item WHERE task_id=?",
                        Integer.class,
                        task));
        assertEquals(
                3,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset WHERE id BETWEEN 933010 AND 933012",
                        Integer.class));
        long version =
                db.queryForObject(
                        "SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task);
        assertEquals(
                409,
                call(
                                admin,
                                "/" + task + "/cancel",
                                "{\"reason\":\"cannot discard manual finding\"}",
                                key(),
                                version)
                        .statusCode());
    }

    @Test
    void abandonedPhotoScopeExpiresOnceWithoutTaskCountOrVersionMutation() throws Exception {
        String task = prepared();
        var event = observation(task);
        byte[] bytes =
                java.util.Base64.getDecoder()
                        .decode(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jSZkAAAAASUVORK5CYII=");
        var receipt = ok(uploadPhoto(employee, task, event, key(), bytes));
        long version =
                db.queryForObject(
                        "SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task);
        db.update(
                "UPDATE fams_inventory_upload_scope SET created_at=UTC_TIMESTAMP(3)-INTERVAL 73"
                        + " HOUR WHERE task_id=?",
                task);
        uploadRetention.expire(50);
        uploadRetention.expire(50);
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_attachment_deletion_job WHERE object_id=?",
                        Integer.class,
                        receipt.path("objectId").asText()));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_upload_scope WHERE task_id=? AND"
                                + " discarded_at IS NOT NULL",
                        Integer.class,
                        task));
        assertEquals(
                version,
                db.queryForObject(
                        "SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT captured_result_count FROM fams_inventory_task WHERE id=?",
                        Integer.class,
                        task));
        assertEquals(410, uploadPhoto(employee, task, event, key(), bytes).statusCode());
        evidenceCleanup.deletePending(50);
    }

    @Test
    void simultaneousEventsCountOnceAndExplicitRecheckKeepsASeparateRound() throws Exception {
        String task = prepared();
        var first = observation(task);
        var second = first.deepCopy();
        second.put("eventId", key());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> ok(observe(employee, task, first)).path("outcome").asText());
            var b = pool.submit(() -> ok(observe(employee, task, second)).path("outcome").asText());
            var outcomes =
                    new java.util.ArrayList<>(
                            java.util.List.of(
                                    a.get(20, java.util.concurrent.TimeUnit.SECONDS),
                                    b.get(20, java.util.concurrent.TimeUnit.SECONDS)));
            outcomes.sort(String::compareTo);
            assertEquals(java.util.List.of("ACCEPTED", "DUPLICATE"), outcomes);
        } finally {
            pool.shutdownNow();
        }
        long version =
                db.queryForObject(
                        "SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task);
        ok(
                call(
                        admin,
                        "/" + task + "/assignments",
                        "{\"itemIds\":[\""
                                + first.path("itemId").asText()
                                + "\"],\"executorAccountId\":\"933002\",\"recheck\":true,\"reason\":\"explicit"
                                + " legitimate second round\"}",
                        key(),
                        version));
        var recheck = first.deepCopy();
        recheck.put("eventId", key()).put("assignmentVersion", 2).put("recheckGeneration", 1);
        assertEquals("ACCEPTED", ok(observe(employee, task, recheck)).path("outcome").asText());
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_round_result WHERE item_id=?",
                        Integer.class,
                        first.path("itemId").asText()));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT SUM(result_count) FROM fams_inventory_item WHERE task_id=?",
                        Integer.class,
                        task));
    }

    @Test
    void assignmentRacingUploadNeverCountsOldExecutorUnderNewOwnership() throws Exception {
        String task = prepared();
        var event = observation(task);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var upload = pool.submit(() -> observe(employee, task, event));
            var assignment =
                    pool.submit(
                            () ->
                                    call(
                                            admin,
                                            "/" + task + "/assignments",
                                            "{\"itemIds\":[\""
                                                    + event.path("itemId").asText()
                                                    + "\"],\"executorAccountId\":\"933003\",\"recheck\":false,\"reason\":\"race"
                                                    + " executor replacement\"}",
                                            key(),
                                            3));
            var posted = upload.get(30, java.util.concurrent.TimeUnit.SECONDS);
            var reassigned = assignment.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(
                    java.util.List.of(200, 409).contains(reassigned.statusCode()),
                    reassigned.body());
            if (posted.statusCode() == 409) {
                posted = observe(employee, task, event);
            }
            String outcome = ok(posted).path("outcome").asText();
            long owner =
                    db.queryForObject(
                            "SELECT executor_account_id FROM fams_inventory_item WHERE id=?",
                            Long.class,
                            event.path("itemId").asText());
            assertEquals(owner == 933003 ? "OLD_ASSIGNMENT_REVIEW" : "ACCEPTED", outcome);
            assertEquals(
                    owner == 933003 ? 0 : 1,
                    db.queryForObject(
                            "SELECT result_count FROM fams_inventory_item WHERE id=?",
                            Integer.class,
                            event.path("itemId").asText()));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_inventory_observation WHERE task_id=?",
                            Integer.class,
                            task));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancellationRacingFirstUploadEitherPreservesObservationOrAppendsOnlyLateEvidence()
            throws Exception {
        String task = prepared();
        var event = observation(task);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var upload = pool.submit(() -> observe(employee, task, event));
            var cancellation =
                    pool.submit(
                            () ->
                                    call(
                                            admin,
                                            "/" + task + "/cancel",
                                            "{\"reason\":\"race cancel with first observation\"}",
                                            key(),
                                            3));
            var posted = upload.get(30, java.util.concurrent.TimeUnit.SECONDS);
            var cancelled = cancellation.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(
                    java.util.List.of(200, 409).contains(cancelled.statusCode()), cancelled.body());
            if (posted.statusCode() == 409) {
                posted = observe(employee, task, event);
            }
            String outcome = ok(posted).path("outcome").asText();
            String state =
                    db.queryForObject(
                            "SELECT task_status FROM fams_inventory_task WHERE id=?",
                            String.class,
                            task);
            assertEquals("CANCELLED".equals(state) ? "LATE_REVIEW" : "ACCEPTED", outcome);
            assertEquals(
                    "CANCELLED".equals(state) ? 0 : 1,
                    db.queryForObject(
                            "SELECT captured_result_count FROM fams_inventory_task WHERE id=?",
                            Integer.class,
                            task));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_inventory_observation WHERE task_id=?",
                            Integer.class,
                            task));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rejectedFactsAndForeignCalibrationLeaveNoObservationOrTaskMutation() throws Exception {
        String task = prepared();
        var original = observation(task);
        var cases = new java.util.ArrayList<tools.jackson.databind.node.ObjectNode>();
        cases.add(original.deepCopy().put("source", "OTHER"));
        cases.add(original.deepCopy().put("source", "CODE"));
        cases.add(original.deepCopy().put("source", "MANUAL").put("manualReason", " "));
        cases.add(original.deepCopy().put("itemId", "0"));
        cases.add(original.deepCopy().put("assetVersion", -1));
        for (String field : java.util.List.of("locationId", "responsibleEmployeeId")) {
            var invalid = original.deepCopy();
            ((tools.jackson.databind.node.ObjectNode) invalid.path("actual")).put(field, "0");
            cases.add(invalid);
        }
        for (String condition : java.util.List.of("OTHER", "DAMAGED", "UNKNOWN")) {
            var invalid = original.deepCopy();
            ((tools.jackson.databind.node.ObjectNode) invalid.path("actual"))
                    .put("condition", condition);
            cases.add(invalid);
        }
        for (String custody : java.util.List.of("OTHER", "PERSONAL", "SHARED")) {
            var invalid = original.deepCopy();
            ((tools.jackson.databind.node.ObjectNode) invalid.path("actual"))
                    .put("custodyType", custody);
            cases.add(invalid);
        }
        for (String field : java.util.List.of("locationId", "custodyType")) {
            var invalid = original.deepCopy();
            ((tools.jackson.databind.node.ObjectNode) invalid.path("actual")).putNull(field);
            cases.add(invalid);
        }
        var blankReason = original.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) blankReason.path("actual"))
                .putNull("locationId").put("unknownReason", " ");
        cases.add(blankReason);
        cases.add(original.deepCopy().putNull("itemId"));
        for (String invalidTime : java.util.List.of("1999-12-31T23:59:59Z",
                "2100-01-01T00:00:01Z", "2026-10-16T00:00:00.000001Z")) {
            cases.add(original.deepCopy().put("rawObservedAt", invalidTime));
        }
        cases.add(original.deepCopy().put("calibrationId", key()));
        for (var invalid : cases) {
            var response = observe(employee, task, invalid);
            assertEquals(400, response.statusCode(), response.body());
        }
        var foreignPerson = original.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) foreignPerson.path("actual"))
                .put("responsibleEmployeeId", "933003");
        assertEquals(403, observe(employee, task, foreignPerson).statusCode());
        var foreignDevice = original.deepCopy().put("deviceId", key());
        assertEquals(403, observe(employee, task, foreignDevice).statusCode());
        String foreignCalibration = ok(observationCall(other, "/calibrations",
                "{\"deviceId\":\"" + original.path("deviceId").asText()
                        + "\",\"clientTime\":\"" + original.path("firstPersistedAt").asText()
                        + "\"}")).path("calibrationId").asText();
        assertEquals(403, observe(employee, task,
                original.deepCopy().put("calibrationId", foreignCalibration)).statusCode());
        assertEquals(403, observe(admin, task, original).statusCode());
        var unauthorizedPhoto = original.deepCopy();
        unauthorizedPhoto.putArray("photoIds").add(key());
        assertEquals(403, observe(employee, task, unauthorizedPhoto).statusCode());
        var duplicatePhoto = original.deepCopy();
        String photo = key();
        duplicatePhoto.putArray("photoIds").add(photo).add(photo);
        assertEquals(400, observe(employee, task, duplicatePhoto).statusCode());
        assertEquals(0, db.queryForObject(
                "SELECT COUNT(*) FROM fams_inventory_observation WHERE task_id=?",
                Integer.class, task));
        assertEquals(0L, db.queryForObject(
                "SELECT captured_result_count FROM fams_inventory_task WHERE id=?",
                Long.class, task));
        assertEquals(3L, db.queryForObject(
                "SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task));
        assertEquals(0L, db.queryForObject(
                "SELECT SUM(result_count) FROM fams_inventory_item WHERE task_id=?",
                Long.class, task));
    }

    @Test
    void identityLookupSeparatesMalformedUnknownOutsideAndOtherExecutors() throws Exception {
        String task = prepared();
        String suffix = "/tasks/" + task + "/identification";
        for (String[] lookup : new String[][] {
            {"SCAN", "broken-label", "INVALID_TAG"},
            {"SCAN", "A".repeat(43), "UNVERIFIED"},
            {"CODE", "INV-DRAFT", "UNVERIFIED"},
            {"CODE", "missing", "UNVERIFIED"},
            {"CODE", "INV-ASSET-933011", "NOT_ASSIGNED"},
            {"CODE", "INV-ASSET-933010", "ASSIGNED"}
        }) {
            var body = JSON.createObjectNode().put("source", lookup[0]).put("value", lookup[1]);
            var result = ok(observationCall(employee, suffix, JSON.writeValueAsString(body)));
            assertEquals(lookup[2], result.path("kind").asText());
            if (!"ASSIGNED".equals(lookup[2])) {
                assertTrue(result.path("snapshot").isMissingNode());
            }
        }
        String outside = create(Instant.now().plusSeconds(3600)).path("id").asText();
        var result = ok(observationCall(admin, "/tasks/" + outside + "/identification",
                "{\"source\":\"CODE\",\"value\":\"INV-ASSET-933010\"}"));
        assertEquals("TASK_OUTSIDE", result.path("kind").asText());
        assertTrue(result.path("snapshot").isMissingNode());
        assertEquals(0, db.queryForObject(
                "SELECT COUNT(*) FROM fams_inventory_observation WHERE task_id=?",
                Integer.class, task));
    }

    @Test
    void expiredForeignEventCannotDisposeAnotherExecutorsUploadScope() throws Exception {
        String task = prepared();
        var original = observation(task);
        byte[] bytes = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jSZkAAAAASUVORK5CYII=");
        var uploaded = ok(uploadPhoto(employee, task, original, key(), bytes));
        ok(call(admin, "/" + task + "/assignments",
                "{\"itemIds\":[\"" + original.path("itemId").asText()
                        + "\"],\"executorAccountId\":\"933003\",\"recheck\":false,"
                        + "\"reason\":\"new authorized executor\"}", key(), 3));
        Instant old = Instant.now().minusSeconds(73 * 3600)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String device = key();
        var calibration = ok(observationCall(other, "/calibrations",
                "{\"deviceId\":\"" + device + "\",\"clientTime\":\"" + old + "\"}"));
        db.update("UPDATE fams_inventory_clock_calibration SET server_time=? WHERE id=?",
                java.sql.Timestamp.from(old), calibration.path("calibrationId").asText());
        var foreign = original.deepCopy().put("deviceId", device)
                .put("calibrationId", calibration.path("calibrationId").asText())
                .put("rawScannedAt", old.toString()).put("rawObservedAt", old.toString())
                .put("firstPersistedAt", old.toString())
                .put("expiresAt", old.plusSeconds(72 * 3600).toString());
        foreign.putArray("photoIds").add(uploaded.path("objectId").asText());
        assertEquals(403, observe(other, task, foreign).statusCode());
        foreign.putArray("photoIds");
        assertEquals(403, observe(other, task, foreign).statusCode());
        Instant current = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var currentCalibration = ok(observationCall(other, "/calibrations",
                "{\"deviceId\":\"" + device + "\",\"clientTime\":\"" + current + "\"}"));
        foreign.put("calibrationId", currentCalibration.path("calibrationId").asText())
                .put("rawScannedAt", current.toString()).put("rawObservedAt", current.toString())
                .put("firstPersistedAt", current.toString())
                .put("expiresAt", current.plusSeconds(72 * 3600).toString());
        assertEquals(403, observe(other, task, foreign).statusCode());
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM fams_attachment WHERE object_id=? AND deleted_at IS NULL",
                Integer.class, uploaded.path("objectId").asText()));
        assertEquals(0, db.queryForObject(
                "SELECT COUNT(*) FROM fams_attachment_deletion_job WHERE object_id=?",
                Integer.class, uploaded.path("objectId").asText()));
        assertEquals(0, db.queryForObject(
                "SELECT COUNT(*) FROM fams_inventory_observation WHERE task_id=?",
                Integer.class, task));
    }


    @Test
    void findingNonEnterpriseAndDuplicateCardDecisionsKeepRawEvidenceAndOriginalReplies() throws Exception {
        String task = prepared();
        var event = observation(task).putNull("itemId").putNull("assetVersion")
                .put("assignmentVersion", 0).put("source", "MANUAL")
                .put("manualReason", "现场未见正式标签，先保留盘盈线索").putNull("assetCode");
        ((tools.jackson.databind.node.ObjectNode) event.path("actual"))
                .put("description", "现场发现一台待核实权属设备，附原始照片");
        byte[] bytes = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jSZkAAAAASUVORK5CYII=");
        var photo = ok(uploadPhoto(employee, task, event, key(), bytes));
        event.putArray("photoIds").add(photo.path("objectId").asText());
        assertEquals("FINDING_REVIEW", ok(observe(employee, task, event)).path("outcome").asText());
        String id = db.queryForObject("SELECT CAST(id AS CHAR) FROM fams_inventory_observation"
                + " WHERE task_id=? AND client_event_id=?", String.class, task, event.path("eventId").asText());
        var original = db.queryForList("SELECT * FROM fams_inventory_observation WHERE id=?", id);
        long version = db.queryForObject("SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task);
        String base = "/api/v1/inventory-reviews/tasks/" + task;
        ok(reviewCall(admin, "POST", base + "/differences/preparation",
                "{\"observationIds\":[\"" + id + "\"]}", key(), version));
        String path = base + "/findings/" + id;
        var detail = ok(reviewCall(admin, "GET", path, null, null, null));
        assertEquals(0, detail.path("subject").path("version").asLong());
        assertTrue(detail.path("observation").path("facts_json").isObject());
        String body = "{\"subjectVersion\":0,\"method\":\"NON_ENTERPRISE\","
                + "\"reason\":\"核对为外部施工单位设备\",\"evidence\":\"施工单位设备登记及现场责任人确认，保留原始照片引用\"}";
        String requestKey = key();
        long expected = detail.path("taskVersion").asLong();
        assertEquals(403, reviewCall(employee, "POST", path + "/decisions", body, requestKey, expected).statusCode());
        var result = ok(reviewCall(admin, "POST", path + "/decisions", body, requestKey, expected));
        assertEquals("CLOSED", result.path("status").asText());
        assertEquals(result, ok(reviewCall(admin, "POST", path + "/decisions", body, requestKey, expected)));
        assertEquals(409, reviewCall(admin, "POST", path + "/decisions", body, key(), expected).statusCode());
        assertEquals(409, reviewCall(admin, "POST", path + "/decisions", body.replace("外部施工单位", "其他外部单位"), requestKey, expected).statusCode());
        assertEquals(original, db.queryForList("SELECT * FROM fams_inventory_observation WHERE id=?", id));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_inventory_review_decision WHERE observation_id=?", Integer.class, id));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_inventory_finding_link WHERE observation_id=?", Integer.class, id));
        assertEquals(1L, db.queryForObject("SELECT captured_result_count FROM fams_inventory_task WHERE id=?", Long.class, task));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_asset WHERE id IN (933010,933011)"
                + " AND (version<>0 OR last_inventory_business_date IS NOT NULL)", Integer.class));
        var second = event.deepCopy().put("eventId", key());
        var secondPhoto = ok(uploadPhoto(employee, task, second, key(), bytes));
        second.putArray("photoIds").add(secondPhoto.path("objectId").asText());
        assertEquals("FINDING_REVIEW", ok(observe(employee, task, second)).path("outcome").asText());
        String secondId = db.queryForObject("SELECT CAST(id AS CHAR) FROM fams_inventory_observation"
                + " WHERE task_id=? AND client_event_id=?", String.class, task, second.path("eventId").asText());
        long next = db.queryForObject("SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task);
        ok(reviewCall(admin, "POST", base + "/differences/preparation",
                "{\"observationIds\":[\"" + secondId + "\"]}", key(), next));
        var secondView = ok(reviewCall(admin, "GET", base + "/findings/" + secondId, null, null, null));
        long secondVersion = secondView.path("taskVersion").asLong();
        String duplicate = "{\"subjectVersion\":0,\"method\":\"DUPLICATE_ASSET\",\"assetId\":\"933010\","
                + "\"ownerVersion\":0,\"reason\":\"核对为已登记资产重复线索\","
                + "\"evidence\":\"现场设备编号与现有正式资产卡一致，原照片与线索保留\"}";
        String missingOwner = duplicate.replace("DUPLICATE_ASSET", "LINK_INBOUND")
                .replace("\"ownerVersion\":0", "\"ownerVersion\":0,\"inboundReceiptId\":\"999999\"");
        assertEquals(409, reviewCall(admin, "POST", base + "/findings/" + secondId + "/decisions",
                missingOwner, key(), secondVersion).statusCode());
        var secondResult = ok(reviewCall(admin, "POST", base + "/findings/" + secondId + "/decisions",
                duplicate, key(), secondVersion));
        assertEquals("CLOSED", secondResult.path("status").asText());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_inventory_finding_link WHERE"
                + " observation_id=? AND link_kind='DUPLICATE_ASSET' AND asset_id=933010", Integer.class, secondId));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_asset WHERE id IN (933010,933011)"
                + " AND (version<>0 OR last_inventory_business_date IS NOT NULL)", Integer.class));
        db.update("DELETE FROM fams_account_role WHERE account_id=933001 AND role_code='ASSET_ADMIN'");
        assertEquals(403, reviewCall(admin, "POST", path + "/decisions", body, requestKey, expected).statusCode());
    }


    @Test
    void findingClosesOnlyAfterRealWarehouseInboundAcceptanceAndKeepsOriginalLinkReceipt() throws Exception {
        String task = prepared();
        configureFindingWarehouse();
        db.update("UPDATE fams_asset SET asset_code='旧/码 甲' WHERE id=933012");
        String draftBody = "{\"businessDate\":\"" + java.time.LocalDate.now() + "\",\"items\":[{\"asset\":{\"id\":\"933012\",\"version\":0},"
                + "\"targetWarehouse\":{\"id\":\"933000\",\"version\":0},\"targetLocation\":{\"id\":\"933000\",\"version\":0}}]}";
        var created = reviewCall(other, "POST", "/api/v1/inbound-receipts", draftBody, key(), 0L);
        assertEquals(201, created.statusCode(), created.body());
        var receipt = JSON.readTree(created.body());
        String receiptId = receipt.path("id").asText();
        var event = observation(task).putNull("itemId").putNull("assetVersion").put("assignmentVersion", 0)
                .put("source", "MANUAL").put("manualReason", "现场盘盈设备待补建卡入库").putNull("assetCode");
        ((tools.jackson.databind.node.ObjectNode) event.path("actual")).put("description", "现场实物与待入库草稿设备特征一致，待正式验收");
        byte[] bytes = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jSZkAAAAASUVORK5CYII=");
        event.putArray("photoIds").add(ok(uploadPhoto(employee, task, event, key(), bytes)).path("objectId").asText());
        assertEquals("FINDING_REVIEW", ok(observe(employee, task, event)).path("outcome").asText());
        String id = db.queryForObject("SELECT CAST(id AS CHAR) FROM fams_inventory_observation WHERE task_id=? AND client_event_id=?", String.class, task, event.path("eventId").asText());
        var original = db.queryForList("SELECT * FROM fams_inventory_observation WHERE id=?", id);
        String base = "/api/v1/inventory-reviews/tasks/" + task;
        long version = db.queryForObject("SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task);
        ok(reviewCall(admin, "POST", base + "/differences/preparation", "{\"observationIds\":[\"" + id + "\"]}", key(), version));
        String path = base + "/findings/" + id;
        long expected = ok(reviewCall(admin, "GET", path, null, null, null)).path("taskVersion").asLong();
        String link = "{\"subjectVersion\":0,\"method\":\"LINK_INBOUND\",\"assetId\":\"933012\",\"inboundReceiptId\":\"" + receiptId
                + "\",\"ownerVersion\":0,\"reason\":\"正式单据实物核对一致待入库\",\"evidence\":\"现场照片及设备特征与该入库项目一致，保持处理直至库管实际验收\"}";
        assertEquals(409, reviewCall(admin, "POST", path + "/decisions", link, key(), expected).statusCode());
        receipt = ok(reviewCall(other, "POST", "/api/v1/inbound-receipts/" + receiptId + "/submit", null, key(), 0L));
        assertEquals("PENDING_ADMIN_DECISION", receipt.path("status").asText());
        link = link.replace("\"ownerVersion\":0", "\"ownerVersion\":1");
        db.execute("CREATE TRIGGER d618_finding_audit_failure BEFORE INSERT ON fams_audit_event FOR EACH ROW"
                + " BEGIN IF NEW.action='INVENTORY_FINDING_DECIDED' THEN SIGNAL SQLSTATE '45000'"
                + " SET MESSAGE_TEXT='owned finding audit failure'; END IF; END");
        try {
            assertEquals(500, reviewCall(admin, "POST", path + "/decisions", link, key(), expected).statusCode());
        } finally {
            db.execute("DROP TRIGGER d618_finding_audit_failure");
        }
        assertEquals(0L, db.queryForObject("SELECT version FROM fams_inventory_review_subject WHERE observation_id=? AND dimension='FINDING'", Long.class, id));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_inventory_finding_link WHERE observation_id=?", Integer.class, id));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_inventory_review_decision WHERE observation_id=?", Integer.class, id));
        assertEquals(expected, db.queryForObject("SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task));
        String originalKey = key();
        var nativeIdentity = new MiniWechatIdentityPort.Identity("finding-app", "finding-admin");
        db.update("INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES (933001,?,NOW(3))", miniSessions.identityHash(nativeIdentity));
        var nativeGrant = miniSessions.start(nativeIdentity, UUID.randomUUID());
        var nativeScript = java.nio.file.Path.of(System.getProperty("user.dir")).getParent()
                .resolve("apps/miniprogram/test/runtime-inventory-finding-http.mjs");
        var builder = new ProcessBuilder("rtk", "proxy", "node", nativeScript.toString());
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_INVENTORY_TASK", task);
        builder.environment().put("FAMS_TEST_FINDING_OBSERVATION", id);
        builder.environment().put("FAMS_TEST_FINDING_RECEIPT", receipt.path("receiptNumber").asText());
        builder.environment().put("FAMS_TEST_FINDING_KEY", originalKey);
        builder.environment().put("FAMS_TEST_FINDING_ASSET_CODE", "旧/码 甲");
        builder.environment().put("FAMS_TEST_FINDING_REASON", JSON.readTree(link).path("reason").asText());
        builder.environment().put("FAMS_TEST_FINDING_EVIDENCE", JSON.readTree(link).path("evidence").asText());
        builder.environment().put("FAMS_TEST_MINI_GRANT", JSON.writeValueAsString(java.util.Map.of(
                "sessionToken", nativeGrant.sessionToken(), "accountId", nativeGrant.accountId(), "bindingId", nativeGrant.bindingId())));
        var nativeProcess = builder.redirectErrorStream(true).start();
        try {
            assertTrue(nativeProcess.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
            String output = new String(nativeProcess.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, nativeProcess.exitValue(), output);
            var proof = JSON.readTree(output.strip());
            assertTrue(proof.path("nativeFindingOriginalReplyRecovered").asBoolean());
            assertTrue(proof.path("originalKeyPayloadVersionRetained").asBoolean());
            assertTrue(proof.path("formalOwnerLookup").asBoolean());
            assertTrue(proof.path("noPrematureClosure").asBoolean());
            System.out.println("D6-18A7 native finding runtime proof: " + proof);
        } finally {
            if (nativeProcess.isAlive()) {
                nativeProcess.descendants().forEach(ProcessHandle::destroyForcibly);
                nativeProcess.destroyForcibly();
                nativeProcess.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        var first = ok(reviewCall(admin, "POST", path + "/decisions", link, originalKey, expected));
        assertEquals("PROCESSING", first.path("status").asText());
        receipt = ok(reviewCall(admin, "POST", "/api/v1/inbound-receipts/" + receiptId + "/decision", "{\"decision\":\"APPROVED\"}", key(), 1L));
        receipt = ok(reviewCall(other, "POST", "/api/v1/inbound-receipts/" + receiptId + "/claim", null, key(), receipt.path("version").asLong()));
        String refresh = link.replace("LINK_INBOUND", "REFRESH_INBOUND").replace("\"subjectVersion\":0", "\"subjectVersion\":1").replace("\"ownerVersion\":1", "\"ownerVersion\":" + receipt.path("version").asLong());
        long beforeRefresh = first.path("version").asLong();
        var pending = ok(reviewCall(admin, "POST", path + "/decisions", refresh, key(), beforeRefresh));
        assertEquals("PROCESSING", pending.path("status").asText());
        String item = receipt.path("items").get(0).path("id").asText();
        var accepted = ok(reviewCall(other, "POST", "/api/v1/inbound-receipts/" + receiptId + "/items/" + item + "/accept",
                JSON.writeValueAsString(java.util.Map.of("assetVersion", 1, "lockVersion", 0, "observedAssetCode", "旧/码 甲")), key(), receipt.path("version").asLong()));
        String completed = link.replace("LINK_INBOUND", "REFRESH_INBOUND").replace("\"subjectVersion\":0", "\"subjectVersion\":2")
                .replace("\"ownerVersion\":1", "\"ownerVersion\":" + accepted.path("receipt").path("version").asLong());
        var beforeAsset = db.queryForList("SELECT * FROM fams_asset WHERE id=933012");
        var closed = ok(reviewCall(admin, "POST", path + "/decisions", completed, key(), pending.path("version").asLong()));
        assertEquals("CLOSED", closed.path("status").asText());
        assertEquals(first, ok(reviewCall(admin, "POST", path + "/decisions", link, originalKey, expected)));
        assertEquals(original, db.queryForList("SELECT * FROM fams_inventory_observation WHERE id=?", id));
        assertEquals(beforeAsset, db.queryForList("SELECT * FROM fams_asset WHERE id=933012"));
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM fams_inventory_finding_link WHERE observation_id=?", Integer.class, id));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_inbound_acceptance WHERE asset_id=933012", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=933012 AND released_at IS NULL", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_todo WHERE business_type='INBOUND' AND business_id=? AND closed_at IS NULL", Integer.class, receiptId));
        assertEquals(1L, db.queryForObject("SELECT captured_result_count FROM fams_inventory_task WHERE id=?", Long.class, task));
    }


    @Test
    void rejectedAndCancelledFindingInboundLinksReopenOnlyCurrentVersionAndRetainHistory() throws Exception {
        String task = prepared();
        configureFindingWarehouse();
        var event = observation(task).putNull("itemId").putNull("assetVersion").put("assignmentVersion", 0)
                .put("source", "MANUAL").put("manualReason", "现场发现待核实企业设备").putNull("assetCode");
        ((tools.jackson.databind.node.ObjectNode) event.path("actual")).put("description", "保留盘盈线索与原始照片，按正式入库处理");
        byte[] bytes = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jSZkAAAAASUVORK5CYII=");
        event.putArray("photoIds").add(ok(uploadPhoto(employee, task, event, key(), bytes)).path("objectId").asText());
        assertEquals("FINDING_REVIEW", ok(observe(employee, task, event)).path("outcome").asText());
        String id = db.queryForObject("SELECT CAST(id AS CHAR) FROM fams_inventory_observation WHERE task_id=? AND client_event_id=?", String.class, task, event.path("eventId").asText());
        var original = db.queryForList("SELECT * FROM fams_inventory_observation WHERE id=?", id);
        String base = "/api/v1/inventory-reviews/tasks/" + task;
        long version = db.queryForObject("SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task);
        ok(reviewCall(admin, "POST", base + "/differences/preparation", "{\"observationIds\":[\"" + id + "\"]}", key(), version));
        String path = base + "/findings/" + id;
        for (boolean reject : new boolean[] {false, true}) {
            long assetVersion = db.queryForObject("SELECT version FROM fams_asset WHERE id=933012", Long.class);
            String draft = "{\"businessDate\":\"" + java.time.LocalDate.now() + "\",\"items\":[{\"asset\":{\"id\":\"933012\",\"version\":" + assetVersion
                    + "},\"targetWarehouse\":{\"id\":\"933000\",\"version\":0},\"targetLocation\":{\"id\":\"933000\",\"version\":0}}]}";
            var created = reviewCall(other, "POST", "/api/v1/inbound-receipts", draft, key(), 0L);
            assertEquals(201, created.statusCode(), created.body());
            String receipt = JSON.readTree(created.body()).path("id").asText();
            var submitted = ok(reviewCall(other, "POST", "/api/v1/inbound-receipts/" + receipt + "/submit", null, key(), 0L));
            var detail = ok(reviewCall(admin, "GET", path, null, null, null));
            var body = JSON.createObjectNode().put("subjectVersion", detail.path("subject").path("version").asLong()).put("method", "LINK_INBOUND")
                    .put("assetId", "933012").put("inboundReceiptId", receipt).put("ownerVersion", submitted.path("version").asLong())
                    .put("reason", "核对正式入库与现场照片").put("evidence", "正式入库项目与线索对应，保持处理中并保留此前所有决定");
            var linked = ok(reviewCall(admin, "POST", path + "/decisions", body.toString(), key(), detail.path("taskVersion").asLong()));
            assertEquals("PROCESSING", linked.path("status").asText());
            var replaced = body.deepCopy().put("method", "NON_ENTERPRISE");
            replaced.remove("assetId");
            replaced.remove("inboundReceiptId");
            replaced.remove("ownerVersion");
            replaced.put("subjectVersion", linked.path("subjectVersion").asLong());
            assertEquals(409, reviewCall(admin, "POST", path + "/decisions", replaced.toString(), key(), linked.path("version").asLong()).statusCode());
            var ended = ok(reviewCall(reject ? admin : other, "POST", "/api/v1/inbound-receipts/" + receipt + (reject ? "/decision" : "/cancel"),
                    reject ? "{\"decision\":\"REJECTED\",\"reason\":\"核对资料不符，退回重新办理\"}" : "{\"reason\":\"现场资料需重新核实，取消此单\"}", key(), submitted.path("version").asLong()));
            var refresh = body.deepCopy().put("method", "REFRESH_INBOUND").put("subjectVersion", linked.path("subjectVersion").asLong()).put("ownerVersion", ended.path("version").asLong());
            var reopened = ok(reviewCall(admin, "POST", path + "/decisions", refresh.toString(), key(), linked.path("version").asLong()));
            assertEquals("OPEN", reopened.path("status").asText());
            assertEquals(original, db.queryForList("SELECT * FROM fams_inventory_observation WHERE id=?", id));
        }
        assertEquals(4, db.queryForObject("SELECT COUNT(*) FROM fams_inventory_review_decision WHERE observation_id=?", Integer.class, id));
        assertEquals(4, db.queryForObject("SELECT COUNT(*) FROM fams_inventory_finding_link WHERE observation_id=?", Integer.class, id));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_inbound_acceptance WHERE asset_id=933012", Integer.class));
        assertEquals(1L, db.queryForObject("SELECT captured_result_count FROM fams_inventory_task WHERE id=?", Long.class, task));
    }


    @Test
    void concurrentFindingAdministratorsKeepOneDecisionAndAuditTheRejectedVersion() throws Exception {
        String task = prepared();
        db.update("INSERT INTO fams_account_role(account_id,role_code,granted_at) VALUES (933003,'ASSET_ADMIN',NOW(3))");
        other = login(3);
        var event = observation(task).putNull("itemId").putNull("assetVersion").put("assignmentVersion", 0)
                .put("source", "MANUAL").put("manualReason", "盘盈权属尚需独立核实").putNull("assetCode");
        ((tools.jackson.databind.node.ObjectNode) event.path("actual")).put("description", "现场线索与原照片保持不变，由管理员独立决定");
        byte[] bytes = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jSZkAAAAASUVORK5CYII=");
        event.putArray("photoIds").add(ok(uploadPhoto(employee, task, event, key(), bytes)).path("objectId").asText());
        assertEquals("FINDING_REVIEW", ok(observe(employee, task, event)).path("outcome").asText());
        String id = db.queryForObject("SELECT CAST(id AS CHAR) FROM fams_inventory_observation WHERE task_id=? AND client_event_id=?", String.class, task, event.path("eventId").asText());
        var original = db.queryForList("SELECT * FROM fams_inventory_observation WHERE id=?", id);
        String base = "/api/v1/inventory-reviews/tasks/" + task;
        String path = base + "/findings/" + id + "/decisions";
        String valid = "{\"subjectVersion\":0,\"method\":\"NON_ENTERPRISE\",\"reason\":\"现场权属核对为施工单位设备\",\"evidence\":\"设备编号及现场登记证明非企业资产，保留全部原始线索\"}";
        long before = db.queryForObject("SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task);
        assertEquals(409, reviewCall(admin, "POST", path, valid, key(), before).statusCode());
        ok(reviewCall(admin, "POST", base + "/differences/preparation", "{\"observationIds\":[\"" + id + "\"]}", key(), before));
        long version = db.queryForObject("SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task);
        for (String invalid : new String[] {
            valid.replace("NON_ENTERPRISE", "UNKNOWN_METHOD"),
            valid.replace("\"method\":", "\"assetId\":\"933010\",\"method\":"),
            valid.replace("NON_ENTERPRISE", "DUPLICATE_ASSET"),
            valid.replace("NON_ENTERPRISE", "LINK_INBOUND"),
            valid.replace("现场权属核对为施工单位设备", " ")
        }) {
            assertEquals(400, reviewCall(admin, "POST", path, invalid, key(), version).statusCode());
        }
        String unavailable = valid.replace("NON_ENTERPRISE", "DUPLICATE_ASSET").replace("\"method\":", "\"assetId\":\"999999\",\"ownerVersion\":0,\"method\":");
        assertEquals(409, reviewCall(admin, "POST", path, unavailable, key(), version).statusCode());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_inventory_review_decision WHERE observation_id=?", Integer.class, id));
        var start = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
        int index = 0;
        for (Session actor : java.util.List.of(admin, other)) {
            final String requestBody = valid.replace("施工单位", index++ == 0 ? "施工甲单位" : "施工乙单位");
            final String requestKey = key();
            futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                    return reviewCall(actor, "POST", path, requestBody, requestKey, version);
                } catch (Exception error) {
                    throw new java.util.concurrent.CompletionException(error);
                }
            }));
        }
        start.countDown();
        var responses = futures.stream().map(java.util.concurrent.CompletableFuture::join).toList();
        assertEquals(java.util.List.of(200, 409), responses.stream().map(HttpResponse::statusCode).sorted().toList());
        var conflict = JSON.readTree(responses.stream().filter(row -> row.statusCode() == 409).findFirst().orElseThrow().body());
        assertTrue(conflict.path("fieldErrors").path("processedBy").asText().startsWith("inv"));
        assertTrue(!Instant.parse(conflict.path("fieldErrors").path("processedAt").asText()).isAfter(Instant.now()));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_inventory_review_decision WHERE observation_id=?", Integer.class, id));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_audit_event WHERE resource_type='INVENTORY_TASK' AND resource_id=? AND action='INVENTORY_FINDING_CONFLICT_REJECTED'", Integer.class, task));
        assertEquals(original, db.queryForList("SELECT * FROM fams_inventory_observation WHERE id=?", id));
    }


    @Test
    void findingOriginalReceiptSurvivesItsCommittingJvmForcedDeathAndAFreshJvm() throws Exception {
        String task = prepared();
        var event = observation(task).putNull("itemId").putNull("assetVersion").put("assignmentVersion", 0)
                .put("source", "MANUAL").put("manualReason", "现场独立核实盘盈权属").putNull("assetCode");
        ((tools.jackson.databind.node.ObjectNode) event.path("actual")).put("description", "原始线索照片保留，独立进程完成盘盈权属复核");
        byte[] bytes = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jSZkAAAAASUVORK5CYII=");
        event.putArray("photoIds").add(ok(uploadPhoto(employee, task, event, key(), bytes)).path("objectId").asText());
        ok(observe(employee, task, event));
        String id = db.queryForObject("SELECT CAST(id AS CHAR) FROM fams_inventory_observation WHERE task_id=? AND client_event_id=?", String.class, task, event.path("eventId").asText());
        var originalRaw = db.queryForList("SELECT * FROM fams_inventory_observation WHERE id=?", id);
        long version = db.queryForObject("SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task);
        ok(reviewCall(admin, "POST", "/api/v1/inventory-reviews/tasks/" + task + "/differences/preparation", "{\"observationIds\":[\"" + id + "\"]}", key(), version));
        version = db.queryForObject("SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task);
        String body = "{\"subjectVersion\":0,\"method\":\"NON_ENTERPRISE\",\"reason\":\"核实为外部施工单位设备\",\"evidence\":\"现场登记及原始设备照片证明为外部施工单位自有设备\"}";
        String originalKey = key();
        var state = java.nio.file.Files.createTempDirectory("fams-finding-jvm-");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = null;
        try {
            JsonNode first = null;
            for (String stage : java.util.List.of("hold", "replay")) {
                var builder = new ProcessBuilder("rtk", "proxy", System.getProperty("java.home") + "/bin/java",
                        "-cp", classpath, "com.fams.auth.InventoryFindingProcess");
                builder.environment().put("FAMS_TEST_FINDING_TASK", task);
                builder.environment().put("FAMS_TEST_FINDING_OBSERVATION", id);
                builder.environment().put("FAMS_TEST_FINDING_COOKIE", admin.cookie());
                builder.environment().put("FAMS_TEST_FINDING_CSRF", admin.csrf());
                builder.environment().put("FAMS_TEST_FINDING_KEY", originalKey);
                builder.environment().put("FAMS_TEST_FINDING_VERSION", Long.toString(version));
                builder.environment().put("FAMS_TEST_FINDING_BODY", body);
                builder.environment().put("FAMS_TEST_FINDING_STAGE", stage);
                var proofPath = state.resolve(stage + ".json");
                var logPath = state.resolve(stage + ".log");
                builder.environment().put("FAMS_TEST_FINDING_PROOF", proofPath.toString());
                if (first != null) {
                    builder.environment().put("FAMS_TEST_FINDING_ORIGINAL", first.path("response").toString());
                }
                process = builder.redirectErrorStream(true).redirectOutput(logPath.toFile()).start();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
                while (!java.nio.file.Files.exists(proofPath)) {
                    assertTrue(process.isAlive(), java.nio.file.Files.readString(logPath));
                    assertTrue(System.nanoTime() < deadline, "Owned finding JVM startup deadline");
                    Thread.sleep(50);
                }
                var proof = JSON.readTree(java.nio.file.Files.readString(proofPath));
                if (first == null) {
                    first = proof;
                    var children = process.descendants().toList();
                    children.forEach(ProcessHandle::destroyForcibly);
                    for (var child : children) {
                        child.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
                    }
                    if (process.isAlive()) {
                        process.destroyForcibly();
                    }
                    assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                    assertTrue(ProcessHandle.of(proof.path("pid").asLong()).map(handle -> !handle.isAlive()).orElse(true));
                } else {
                    assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
                    assertEquals(0, process.exitValue(), java.nio.file.Files.readString(logPath));
                    assertTrue(first.path("pid").asLong() != proof.path("pid").asLong());
                    assertTrue(proof.path("originalMatched").asBoolean());
                    assertEquals(first.path("response"), proof.path("response"));
                }
            }
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_inventory_review_decision WHERE observation_id=?", Integer.class, id));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_inventory_review_receipt WHERE task_id=? AND operation=?", Integer.class, task, "FINDING:" + id));
            assertEquals(originalRaw, db.queryForList("SELECT * FROM fams_inventory_observation WHERE id=?", id));
            System.out.println("D6-18A7 JVM proof: committingJvmKilled=true freshJvmOriginalReply=true singleDecision=true rawUnchanged=true");
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            try (var files = java.nio.file.Files.walk(state)) {
                for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    java.nio.file.Files.deleteIfExists(file);
                }
            }
        }
    }


    @Test
    void itemlessTimeAndFindingCloseIndependentlyOnlyWithActualRecollectionAndFrozenLineage()
            throws Exception {
        String task = prepared();
        String original = a8Finding(task, true), counter = a8Finding(task, false);
        var rawBefore =
                db.queryForList(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=? ORDER BY id",
                        task);
        var assetBefore =
                db.queryForList("SELECT * FROM fams_asset WHERE id IN (933010,933011) ORDER BY id");
        String base = "/api/v1/inventory-reviews/tasks/" + task;
        ok(
                reviewCall(
                        admin,
                        "POST",
                        base + "/differences/preparation",
                        "{\"observationIds\":[\"" + original + "\",\"" + counter + "\"]}",
                        key(),
                        a8Version(task)));
        var detail = ok(reviewCall(admin, "GET", base + "/findings/" + original, null, null, null));
        assertEquals("OPEN", detail.path("subject").path("subject_status").asText());
        assertEquals("OPEN", detail.path("timeSubject").get(0).path("subject_status").asText());
        String body =
                "{\"subjectVersion\":0,\"counterObservationId\":\""
                        + counter
                        + "\",\"reason\":\"实际重新到现场采集\",\"evidence\":\"两次照片设备特征与位置核对一致，原时间仍不确定\"}";
        long version = a8Version(task);
        String requestKey = key();
        var time =
                ok(
                        reviewCall(
                                admin,
                                "POST",
                                base + "/findings/" + original + "/time-decisions",
                                body,
                                requestKey,
                                version));
        assertEquals("UNVERIFIED", time.path("originalTimeConfidence").asText());
        assertEquals(
                time,
                ok(
                        reviewCall(
                                admin,
                                "POST",
                                base + "/findings/" + original + "/time-decisions",
                                body,
                                requestKey,
                                version)));
        detail = ok(reviewCall(admin, "GET", base + "/findings/" + original, null, null, null));
        assertEquals("OPEN", detail.path("subject").path("subject_status").asText());
        assertEquals("CLOSED", detail.path("timeSubject").get(0).path("subject_status").asText());
        assertEquals(
                counter, detail.path("recollections").get(0).path("counterObservationId").asText());
        a8CloseFinding(task, original);
        a8CloseFinding(task, counter);
        for (var item :
                db.queryForList(
                        "SELECT id FROM fams_inventory_item WHERE task_id=? ORDER BY id", task)) {
            ok(
                    reviewCall(
                            admin,
                            "POST",
                            "/api/v1/inventory-readiness/tasks/"
                                    + task
                                    + "/items/"
                                    + item.get("id")
                                    + "/unobserved",
                            "{\"recheckGeneration\":0,\"assetVersion\":0,\"kind\":\"UNABLE_TO_OBSERVE\",\"reason\":\"设备所在房间暂不能进入\",\"evidence\":\"现场门禁通知与任务范围核对，未把盘盈计为应盘项\"}",
                            key(),
                            a8Version(task)));
        }
        ok(
                reviewCall(
                        admin,
                        "POST",
                        "/api/v1/inventory-readiness/tasks/" + task + "/waiting-review",
                        "{\"reason\":\"应盘项明确记录未盘原因并独立处理任务外线索\"}",
                        key(),
                        a8Version(task)));
        var flight =
                ok(
                        reviewCall(
                                admin,
                                "GET",
                                "/api/v1/inventory-sealing/tasks/" + task,
                                null,
                                null,
                                null));
        assertEquals(
                200,
                reviewCall(
                                admin,
                                "POST",
                                "/api/v1/inventory-sealing/tasks/" + task + "/seal",
                                "{\"reason\":\"核对独立时间与归属处理后冻结报告\",\"synchronizationDigest\":\""
                                        + flight.path("synchronizationDigest").asText()
                                        + "\",\"missingSynchronizationReason\":\"未收到设备完整同步声明，保留待补证名单及原始线索\"}",
                                key(),
                                a8Version(task))
                        .statusCode());
        assertEquals(
                rawBefore,
                db.queryForList(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=? ORDER BY id",
                        task));
        assertEquals(
                assetBefore,
                db.queryForList(
                        "SELECT * FROM fams_asset WHERE id IN (933010,933011) ORDER BY id"));
        var frozen =
                db.queryForObject(
                        "SELECT snapshot_json FROM fams_inventory_sealed_evidence WHERE task_id=?"
                                + " AND observation_id=?",
                        String.class,
                        task,
                        original);
        assertEquals(
                counter,
                JSON.readTree(frozen)
                        .path("recollections")
                        .get(0)
                        .path("counter_observation_id")
                        .asText());
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT captured_result_count FROM fams_inventory_task WHERE id=?",
                        Integer.class,
                        task));
    }

    @Test
    void findingFirstNeverClosesTimeAndRecollectionRejectsSelfOtherTaskAndUncertainCounter()
            throws Exception {
        String task = prepared();
        String older = a8Finding(task, false);
        String original = a8Finding(task, true), uncertain = a8Finding(task, true);
        String counter = a8Finding(task, false);
        String otherTask = prepared();
        String otherCounter = a8Finding(otherTask, false);
        String base = "/api/v1/inventory-reviews/tasks/" + task;
        ok(
                reviewCall(
                        admin,
                        "POST",
                        base + "/differences/preparation",
                        "{\"observationIds\":[\"" + original + "\"]}",
                        key(),
                        a8Version(task)));
        a8CloseFinding(task, original);
        var detail = ok(reviewCall(admin, "GET", base + "/findings/" + original, null, null, null));
        assertEquals("CLOSED", detail.path("subject").path("subject_status").asText());
        assertEquals("OPEN", detail.path("timeSubject").get(0).path("subject_status").asText());
        String path = base + "/findings/" + original + "/time-decisions";
        for (String id : new String[] {original, uncertain, otherCounter, older, "999999"}) {
            String body =
                    "{\"subjectVersion\":0,\"counterObservationId\":\""
                            + id
                            + "\",\"reason\":\"核对原始线索与实际重采\",\"evidence\":\"两次现场照片与设备特征核对，保留独立时间责任\"}";
            assertEquals(
                    id.equals(original) ? 400 : 409,
                    reviewCall(admin, "POST", path, body, key(), a8Version(task)).statusCode());
        }
        assertEquals(
                400,
                reviewCall(
                                admin,
                                "GET",
                                base
                                        + "/findings/"
                                        + original
                                        + "/recollection-targets?pageSize=101",
                                null,
                                null,
                                null)
                        .statusCode());
        var targets =
                ok(
                        reviewCall(
                                admin,
                                "GET",
                                base
                                        + "/findings/"
                                        + original
                                        + "/recollection-targets?page=1&pageSize=1",
                                null,
                                null,
                                null));
        assertEquals(1, targets.path("total").asInt());
        assertEquals(counter, targets.path("items").get(0).path("id").asText());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_finding_recollection WHERE task_id=?",
                        Integer.class,
                        task));
    }

    @Test
    void recollectionAuditFailureRollsBackAllFactsAndOriginalRetryRevalidatesCurrentRole()
            throws Exception {
        String task = prepared();
        String original = a8Finding(task, true), counter = a8Finding(task, false);
        String base = "/api/v1/inventory-reviews/tasks/" + task;
        ok(
                reviewCall(
                        admin,
                        "POST",
                        base + "/differences/preparation",
                        "{\"observationIds\":[\"" + original + "\"]}",
                        key(),
                        a8Version(task)));
        String path = base + "/findings/" + original + "/time-decisions";
        String body =
                "{\"subjectVersion\":0,\"counterObservationId\":\""
                        + counter
                        + "\",\"reason\":\"两次实物照片特征一致\",\"evidence\":\"本次已实际重新采集，原线索时间不确定且不改原到期点\"}";
        long version = a8Version(task);
        String requestKey = key();
        assertEquals(
                403, reviewCall(employee, "POST", path, body, requestKey, version).statusCode());
        db.execute(
                "CREATE TRIGGER d618_recollect_audit_failure BEFORE INSERT ON fams_audit_event FOR"
                        + " EACH ROW BEGIN IF NEW.action='INVENTORY_FINDING_TIME_RECOLLECTED' THEN"
                        + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned recollection audit"
                        + " failure'; END IF; END");
        try {
            assertEquals(
                    500, reviewCall(admin, "POST", path, body, requestKey, version).statusCode());
        } finally {
            db.execute("DROP TRIGGER d618_recollect_audit_failure");
        }
        assertEquals(version, a8Version(task));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_finding_recollection WHERE task_id=?",
                        Integer.class,
                        task));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_review_decision WHERE task_id=?",
                        Integer.class,
                        task));
        var result = ok(reviewCall(admin, "POST", path, body, requestKey, version));
        assertEquals(result, ok(reviewCall(admin, "POST", path, body, requestKey, version)));
        assertEquals(409, reviewCall(admin, "POST", path, body, key(), version).statusCode());
        assertEquals(
                409,
                reviewCall(admin, "POST", path, body.replace("两次实物", "不同实物"), requestKey, version)
                        .statusCode());
        db.update(
                "DELETE FROM fams_account_role WHERE account_id=933001 AND"
                        + " role_code='ASSET_ADMIN'");
        assertEquals(403, reviewCall(admin, "POST", path, body, requestKey, version).statusCode());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_finding_recollection WHERE task_id=?",
                        Integer.class,
                        task));
    }

    @Test
    void recollectionOriginalReceiptSurvivesItsCommittingJvmForcedDeathAndAFreshJvm()
            throws Exception {
        String task = prepared();
        String id = a8Finding(task, true), counter = a8Finding(task, false);
        var originalRaw =
                db.queryForList("SELECT * FROM fams_inventory_observation WHERE id=?", id);
        ok(
                reviewCall(
                        admin,
                        "POST",
                        "/api/v1/inventory-reviews/tasks/" + task + "/differences/preparation",
                        "{\"observationIds\":[\"" + id + "\"]}",
                        key(),
                        a8Version(task)));
        long version = a8Version(task);
        String body =
                "{\"subjectVersion\":0,\"counterObservationId\":\""
                        + counter
                        + "\",\"reason\":\"实际重新采集同一设备\",\"evidence\":\"两次现场照片特征与设备位置核对一致，原时间仍不确定\"}";
        String originalKey = key();
        var state = java.nio.file.Files.createTempDirectory("fams-finding-jvm-");
        String classpath =
                System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = null;
        try {
            JsonNode first = null;
            for (String stage : java.util.List.of("hold", "replay")) {
                var builder =
                        new ProcessBuilder(
                                "rtk",
                                "proxy",
                                System.getProperty("java.home") + "/bin/java",
                                "-cp",
                                classpath,
                                "com.fams.auth.InventoryRecollectionProcess");
                builder.environment().put("FAMS_TEST_FINDING_TASK", task);
                builder.environment().put("FAMS_TEST_FINDING_OBSERVATION", id);
                builder.environment().put("FAMS_TEST_FINDING_COOKIE", admin.cookie());
                builder.environment().put("FAMS_TEST_FINDING_CSRF", admin.csrf());
                builder.environment().put("FAMS_TEST_FINDING_KEY", originalKey);
                builder.environment().put("FAMS_TEST_FINDING_VERSION", Long.toString(version));
                builder.environment().put("FAMS_TEST_FINDING_BODY", body);
                builder.environment().put("FAMS_TEST_FINDING_STAGE", stage);
                var proofPath = state.resolve(stage + ".json");
                var logPath = state.resolve(stage + ".log");
                builder.environment().put("FAMS_TEST_FINDING_PROOF", proofPath.toString());
                if (first != null) {
                    builder.environment()
                            .put("FAMS_TEST_FINDING_ORIGINAL", first.path("response").toString());
                }
                process =
                        builder.redirectErrorStream(true).redirectOutput(logPath.toFile()).start();
                long deadline =
                        System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
                while (!java.nio.file.Files.exists(proofPath)) {
                    assertTrue(process.isAlive(), java.nio.file.Files.readString(logPath));
                    assertTrue(System.nanoTime() < deadline, "Owned finding JVM startup deadline");
                    Thread.sleep(50);
                }
                var proof = JSON.readTree(java.nio.file.Files.readString(proofPath));
                if (first == null) {
                    first = proof;
                    var children = process.descendants().toList();
                    children.forEach(ProcessHandle::destroyForcibly);
                    for (var child : children) {
                        child.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
                    }
                    if (process.isAlive()) {
                        process.destroyForcibly();
                    }
                    assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                    assertTrue(
                            ProcessHandle.of(proof.path("pid").asLong())
                                    .map(handle -> !handle.isAlive())
                                    .orElse(true));
                } else {
                    assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
                    assertEquals(0, process.exitValue(), java.nio.file.Files.readString(logPath));
                    assertTrue(first.path("pid").asLong() != proof.path("pid").asLong());
                    assertTrue(proof.path("originalMatched").asBoolean());
                    assertEquals(first.path("response"), proof.path("response"));
                }
            }
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_inventory_review_decision WHERE"
                                + " observation_id=?",
                            Integer.class,
                            id));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_inventory_review_receipt WHERE task_id=? AND"
                                + " operation=?",
                            Integer.class,
                            task,
                            "FINDING_TIME:" + id));
            assertEquals(
                    originalRaw,
                    db.queryForList("SELECT * FROM fams_inventory_observation WHERE id=?", id));
            System.out.println(
                    "D6-18A8 time JVM proof: committingJvmKilled=true freshJvmOriginalReply=true"
                        + " singleDecision=true rawUnchanged=true");
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            try (var files = java.nio.file.Files.walk(state)) {
                for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    java.nio.file.Files.deleteIfExists(file);
                }
            }
        }
    }

    @Test
    void nativeRecollectionActualOpaqueReplyLossRecoversOriginalAndKeepsFindingOpen()
            throws Exception {
        String task = prepared();
        String id = a8Finding(task, true), counter = a8Finding(task, false);
        var originalRaw =
                db.queryForList(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=? ORDER BY id",
                        task);
        ok(
                reviewCall(
                        admin,
                        "POST",
                        "/api/v1/inventory-reviews/tasks/" + task + "/differences/preparation",
                        "{\"observationIds\":[\"" + id + "\"]}",
                        key(),
                        a8Version(task)));
        var nativeIdentity =
                new MiniWechatIdentityPort.Identity("recollection-app", "recollection-admin");
        db.update(
                "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES"
                    + " (933001,?,NOW(3))",
                miniSessions.identityHash(nativeIdentity));
        var grant = miniSessions.start(nativeIdentity, UUID.randomUUID());
        var script =
                java.nio.file.Path.of(System.getProperty("user.dir"))
                        .getParent()
                        .resolve("apps/miniprogram/test/runtime-inventory-recollection-http.mjs");
        var builder = new ProcessBuilder("rtk", "proxy", "node", script.toString());
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_INVENTORY_TASK", task);
        builder.environment().put("FAMS_TEST_FINDING_OBSERVATION", id);
        builder.environment().put("FAMS_TEST_FINDING_COUNTER", counter);
        builder.environment().put("FAMS_TEST_FINDING_KEY", key());
        builder.environment()
                .put(
                        "FAMS_TEST_MINI_GRANT",
                        JSON.writeValueAsString(
                                java.util.Map.of(
                                        "sessionToken",
                                        grant.sessionToken(),
                                        "accountId",
                                        grant.accountId(),
                                        "bindingId",
                                        grant.bindingId())));
        var process = builder.redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
            String output =
                    new String(
                            process.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            var proof = JSON.readTree(output.strip());
            assertTrue(proof.path("nativeTimeOriginalReplyRecovered").asBoolean());
            assertTrue(proof.path("findingRemainsOpen").asBoolean());
            System.out.println("D6-18A8 native time proof: " + proof);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        assertEquals(
                originalRaw,
                db.queryForList(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=? ORDER BY id",
                        task));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_finding_recollection WHERE task_id=?",
                        Integer.class,
                        task));
    }

    @Test
    void twoActualAdministratorsRaceIndependentTimeVersionWithRecordedWinner() throws Exception {
        String task = prepared();
        String id = a8Finding(task, true), counter = a8Finding(task, false);
        ok(
                reviewCall(
                        admin,
                        "POST",
                        "/api/v1/inventory-reviews/tasks/" + task + "/differences/preparation",
                        "{\"observationIds\":[\"" + id + "\"]}",
                        key(),
                        a8Version(task)));
        db.update(
                "INSERT INTO fams_account_role(account_id,role_code,granted_at) VALUES"
                    + " (933003,'ASSET_ADMIN',NOW(3))");
        other = login(3);
        long version = a8Version(task);
        String body =
                "{\"subjectVersion\":0,\"counterObservationId\":\""
                        + counter
                        + "\",\"reason\":\"两次照片现场核对一致\",\"evidence\":\"实际重新采集同一设备，原时间不确定且盘盈归属独立处理\"}";
        var start = new java.util.concurrent.CountDownLatch(1);
        var futures =
                new java.util.ArrayList<
                        java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
        for (Session actor : java.util.List.of(admin, other)) {
            String requestKey = key();
            futures.add(
                    java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    start.await();
                                    return reviewCall(
                                            actor,
                                            "POST",
                                            "/api/v1/inventory-reviews/tasks/"
                                                    + task
                                                    + "/findings/"
                                                    + id
                                                    + "/time-decisions",
                                            body,
                                            requestKey,
                                            version);
                                } catch (Exception error) {
                                    throw new java.util.concurrent.CompletionException(error);
                                }
                            }));
        }
        start.countDown();
        var replies = futures.stream().map(java.util.concurrent.CompletableFuture::join).toList();
        assertEquals(
                java.util.List.of(200, 409),
                replies.stream().map(HttpResponse::statusCode).sorted().toList());
        var conflict =
                JSON.readTree(
                        replies.stream()
                                .filter(r -> r.statusCode() == 409)
                                .findFirst()
                                .orElseThrow()
                                .body());
        assertTrue(conflict.path("fieldErrors").path("processedBy").asText().startsWith("inv"));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_finding_recollection WHERE task_id=?",
                        Integer.class,
                        task));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_audit_event WHERE resource_id=? AND"
                            + " action='INVENTORY_FINDING_CONFLICT_REJECTED'",
                        Integer.class,
                        task));
    }

    private long a8Version(String task) {
        return java.util.Objects.requireNonNull(
                db.queryForObject(
                        "SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task));
    }

    private String a8Finding(String task, boolean uncertain) throws Exception {
        var event =
                observation(task)
                        .putNull("itemId")
                        .putNull("assetVersion")
                        .put("assignmentVersion", 0)
                        .put("source", "MANUAL")
                        .put("manualReason", "现场未贴正式标签，保留任务外实物证据")
                        .putNull("assetCode")
                        .put("clockAnomaly", uncertain);
        ((tools.jackson.databind.node.ObjectNode) event.path("actual"))
                .put("description", "同一现场外部施工单位设备，照片特征待独立核实");
        byte[] bytes =
                java.util.Base64.getDecoder()
                        .decode(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jSZkAAAAASUVORK5CYII=");
        var photo = ok(uploadPhoto(employee, task, event, key(), bytes));
        event.putArray("photoIds").add(photo.path("objectId").asText());
        assertEquals(
                uncertain ? "TIME_REVIEW" : "FINDING_REVIEW",
                ok(observe(employee, task, event)).path("outcome").asText());
        return db.queryForObject(
                "SELECT CAST(id AS CHAR) FROM fams_inventory_observation WHERE task_id=? AND"
                        + " client_event_id=?",
                String.class,
                task,
                event.path("eventId").asText());
    }

    private void a8CloseFinding(String task, String id) throws Exception {
        ok(
                reviewCall(
                        admin,
                        "POST",
                        "/api/v1/inventory-reviews/tasks/"
                                + task
                                + "/findings/"
                                + id
                                + "/decisions",
                        "{\"subjectVersion\":0,\"method\":\"NON_ENTERPRISE\",\"reason\":\"核对为外部施工单位设备\",\"evidence\":\"核对施工单位登记和两次照片，明确不属于企业资产\"}",
                        key(),
                        a8Version(task)));
    }
    private void configureFindingWarehouse() throws Exception {
        db.update("INSERT INTO fams_account_role(account_id,role_code,granted_at) VALUES (933003,'WAREHOUSE_OPERATOR',NOW(3))");
        other = login(3);
        db.update("UPDATE fams_asset SET creator_account_id=933003,category_id=933000,owning_organization_id=933000,model='M1',unit='piece' WHERE id=933012");
        db.update("INSERT INTO fams_numbering_rule(id,code,code_normalized,target,mode,fixed_prefix,date_part,sequence_length,status,version,created_at,updated_at)"
                + " VALUES (933090,'FIND-IN','FIND-IN','INBOUND_RECEIPT','ENTERPRISE','FIN','NONE',8,'ACTIVE',0,NOW(3),NOW(3))");
        db.update("UPDATE fams_business_parameter_version SET snapshot_json=? WHERE id=933000",
                "{\"timezone\":\"Asia/Shanghai\",\"workWeek\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\"],\"holidays\":[],\"adjustedWorkdays\":[],"
                + "\"adminDecisionDeadlineWorkdays\":2,\"warehouseClaimDeadlineWorkdays\":1,\"warehouseFirstExecutionDeadlineWorkdays\":2}");
    }

    private HttpResponse<String> reviewCall(Session actor, String method, String path,
            String body, String requestKey, Long version) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Cookie", actor.cookie()).header("X-CSRF-Token", actor.csrf());
        if (requestKey != null) {
            builder.header("Idempotency-Key", requestKey);
        }
        if (version != null) {
            builder.header("If-Match-Version", version.toString());
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> uploadPhoto(
            Session actor, String task, JsonNode event, String key, byte[] bytes) throws Exception {
        String boundary = "famsInventoryPhotoBoundary";
        var body = new java.io.ByteArrayOutputStream();
        body.write(
                ("--"
                                + boundary
                                + "\r\n"
                                + "Content-Disposition: form-data; name=\"file\";"
                                + " filename=\"evidence.png\"\r\n"
                                + "Content-Type: image/png\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.write(bytes);
        body.write(
                ("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return http.send(
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/api/v1/inventory-observations/tasks/"
                                                + task
                                                + "/events/"
                                                + event.path("eventId").asText()
                                                + "/photos"))
                        .header("Cookie", actor.cookie())
                        .header("X-CSRF-Token", actor.csrf())
                        .header("Idempotency-Key", key)
                        .header("If-Match-Version", "0")
                        .header("X-FAMS-Device-Id", event.path("deviceId").asText())
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String prepared() throws Exception {
        String task = create(Instant.now().plusSeconds(3600)).path("id").asText();
        publish(task);
        long item =
                db.queryForObject(
                        "SELECT id FROM fams_inventory_item WHERE task_id=? AND asset_id=933010",
                        Long.class,
                        task);
        ok(
                call(
                        admin,
                        "/" + task + "/assignments",
                        "{\"itemIds\":[\""
                                + item
                                + "\"],\"executorAccountId\":\"933002\",\"recheck\":false,\"reason\":\"initial"
                                + " assignment\"}",
                        key(),
                        1));
        ok(call(admin, "/" + task + "/start", null, key(), 2));
        return task;
    }

    private tools.jackson.databind.node.ObjectNode observation(String task) throws Exception {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String device = key();
        var calibration =
                ok(
                        observationCall(
                                employee,
                                "/calibrations",
                                "{\"deviceId\":\""
                                        + device
                                        + "\",\"clientTime\":\""
                                        + now
                                        + "\"}"));
        long item =
                db.queryForObject(
                        "SELECT id FROM fams_inventory_item WHERE task_id=? AND asset_id=933010",
                        Long.class,
                        task);
        var input =
                JSON.createObjectNode()
                        .put("eventId", key())
                        .put("itemId", Long.toString(item))
                        .put("assignmentVersion", 1)
                        .put("recheckGeneration", 0)
                        .put("assetVersion", 0)
                        .put("deviceId", device)
                        .put("source", "SCAN")
                        .put("assetCode", "INV-ASSET-933010")
                        .put("rawScannedAt", now.toString())
                        .put("rawObservedAt", now.toString())
                        .put("firstPersistedAt", now.toString())
                        .put("expiresAt", now.plusSeconds(72 * 3600).toString())
                        .put("calibrationId", calibration.path("calibrationId").asText())
                        .put("clockAnomaly", false);
        input.putObject("actual")
                .put("locationId", "933000")
                .put("custodyType", "STORAGE")
                .put("condition", "GOOD");
        input.putArray("photoIds");
        return input;
    }

    private HttpResponse<String> observe(Session actor, String task, JsonNode input)
            throws Exception {
        return observationCall(actor, "/tasks/" + task + "/events", JSON.writeValueAsString(input));
    }

    private HttpResponse<String> observationCall(Session actor, String suffix, String body)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/api/v1/inventory-observations"
                                                + suffix))
                        .header("Cookie", actor.cookie())
                        .header("X-CSRF-Token", actor.csrf())
                        .header("Content-Type", "application/json");
        if (suffix.endsWith("/events")) {
            var event = JSON.readTree(body);
            builder.header("Idempotency-Key", event.path("eventId").asText())
                    .header("If-Match-Version", event.path("assignmentVersion").asText());
        }
        return http.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode create(Instant start) throws Exception {
        String body =
                "{\"name\":\"Owned inventory\",\"range\":{\"categoryId\":933000},\"startsAt\":\""
                        + start.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                        + "\",\"dueAt\":\""
                        + start.plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                        + "\"}";
        return ok(call(admin, "", body, key(), 0));
    }

    private void publish(String id) throws Exception {
        var preview = ok(call(admin, "/preview", "{\"categoryId\":933000}", key(), 0));
        ok(
                call(
                        admin,
                        "/" + id + "/publication",
                        "{\"expectedCount\":2,\"previewDigest\":\""
                                + preview.path("digest").asText()
                                + "\"}",
                        key(),
                        0));
    }

    private JsonNode ok(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private String key() {
        return UUID.randomUUID().toString();
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
                                                "{\"username\":\"inv"
                                                        + index
                                                        + "\",\"password\":\""
                                                        + PASSWORD
                                                        + "\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        var body = ok(response);
        return new Session(
                response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                body.path("csrfToken").asText());
    }

    private HttpResponse<String> call(
            Session actor, String suffix, String body, String key, long version) throws Exception {
        var builder =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/api/v1/inventory-tasks"
                                                + suffix))
                        .header("Cookie", actor.cookie());
        if (key != null) {
            builder.header("X-CSRF-Token", actor.csrf())
                    .header("Idempotency-Key", key)
                    .header("If-Match-Version", Long.toString(version));
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return http.send(
                builder.method(
                                key == null ? "GET" : "POST",
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
