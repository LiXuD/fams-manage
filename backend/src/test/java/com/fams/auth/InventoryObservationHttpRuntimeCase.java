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
