package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
@EnabledIfSystemProperty(named = "d6.18.inventory.runtime", matches = "true")
class InventoryReviewHttpRuntimeCase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcTemplate db;
    @Autowired MiniSessionService miniSessions;
    private Session admin, employee, other, warehouse, system;

    private record Session(String cookie, String csrf) {}

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        registry.add("spring.datasource.url", settings::url);
        registry.add("spring.datasource.username", settings::username);
        registry.add("spring.datasource.password", settings::password);
    }

    @BeforeEach
    void seed() throws Exception {
        db.update(
                "INSERT INTO"
                    + " fams_organization(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (943000,'INVOWN','INVOWN','Inventory"
                    + " owned','ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_asset_category(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (943000,'INVOWN','INVOWN','Inventory"
                    + " category','ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_warehouse(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (943000,'INVOWN','INVOWN','Inventory"
                    + " warehouse','ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_location(id,code,code_normalized,name,location_type,warehouse_id,status,created_at,updated_at)"
                    + " VALUES (943000,'INVOWN','INVOWN','Inventory"
                    + " bin','BIN',943000,'ACTIVE',NOW(3),NOW(3))");
        for (int i = 1; i <= 5; i++) {
            long id = 943000 + i;
            db.update(
                    "INSERT INTO"
                        + " fams_employee(id,employee_no,employee_no_normalized,name,organization_id,status,created_at,updated_at)"
                        + " VALUES (?,?,?,?,943000,'ACTIVE',NOW(3),NOW(3))",
                    id,
                    "REVIEW" + i,
                    "REVIEW" + i,
                    "Inventory person " + i);
            db.update(
                    "INSERT INTO"
                        + " fams_system_account(id,employee_id,username,username_normalized,password_hash,status,initial_password_required,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,'ENABLED',FALSE,NOW(3),NOW(3))",
                    id,
                    id,
                    "review" + i,
                    "REVIEW" + i,
                    new BCryptPasswordEncoder(4).encode(PASSWORD));
            db.update(
                    "INSERT INTO fams_account_role(account_id,role_code,granted_at) VALUES"
                            + " (?,?,NOW(3))",
                    id,
                    i == 1 || i == 3
                            ? "ASSET_ADMIN"
                            : i == 4 ? "WAREHOUSE_OPERATOR" : i == 5 ? "SYSTEM_ADMIN" : "EMPLOYEE");
        }
        db.update(
                "INSERT INTO"
                    + " fams_business_parameter_version(id,version,status,effective_at,actor_account_id,actor_username,snapshot_json,created_at)"
                    + " VALUES"
                    + " (943000,943000,'ACTIVE',NOW(3),943001,'inv1','{\"timezone\":\"Asia/Shanghai\"}',NOW(3))");
        db.update(
                "UPDATE fams_business_parameter_state SET"
                        + " current_version=943000,current_version_id=943000 WHERE singleton_id=1");
        db.update(
                "INSERT INTO"
                    + " fams_location(id,code,code_normalized,name,location_type,warehouse_id,status,created_at,updated_at)"
                    + " VALUES (9007199254740993,'BIGROOM','BIGROOM','Large identifier"
                    + " room','BIN',943000,'ACTIVE',NOW(3),NOW(3)),"
                    + " (943099,'SECOND','SECOND','Second"
                    + " bin','BIN',943000,'ACTIVE',NOW(3),NOW(3))");
        for (long id : new long[] {943010, 943011}) {
            db.update(
                    "INSERT INTO"
                        + " fams_asset(id,asset_code,name,category_id,lifecycle_status,exception_status,card_stage,custody_type,storage_warehouse_id,current_location_id,created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by)"
                        + " VALUES (?,?,'Inventory"
                        + " asset',943000,'IN_STOCK','NORMAL','ACTIVE','STORAGE',943000,9007199254740993,CURRENT_DATE,943000,943001,'inv1',NOW(3),NOW(3),943001,943001)",
                    id,
                    "INV-ASSET-" + id);
        }
        db.update(
                "INSERT INTO"
                    + " fams_asset(id,asset_code,name,category_id,lifecycle_status,exception_status,card_stage,created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by)"
                    + " VALUES"
                    + " (943012,'INV-DRAFT','Unpublished',943000,'WAITING_INBOUND','NORMAL','DRAFT',CURRENT_DATE,943000,943001,'inv1',NOW(3),NOW(3),943001,943001)");
        admin = login(1);
        employee = login(2);
        other = login(3);
        warehouse = login(4);
        system = login(5);
    }

    @AfterEach
    void cleanup() {
        db.update(
                "DELETE FROM fams_inventory_evidence_disposition WHERE actor_account_id BETWEEN"
                        + " 943001 AND 943005");
        db.update(
                "DELETE FROM fams_inventory_selection_event WHERE actor_account_id BETWEEN 943001"
                        + " AND 943005");
        db.update(
                "DELETE FROM fams_inventory_review_transfer WHERE observation_id IN (SELECT id FROM"
                        + " fams_inventory_observation WHERE actor_account_id BETWEEN 943001 AND"
                        + " 943005)");
        db.update(
                "DELETE FROM fams_inventory_review_decision WHERE actor_account_id BETWEEN 943001"
                        + " AND 943005");
        for (String table :
                new String[] {
                    "fams_inventory_review_receipt",
                    "fams_inventory_review_subject",
                    "fams_inventory_item_review",
                    "fams_inventory_round_result"
                }) {
            String where =
                    table.equals("fams_inventory_round_result")
                            ? "item_id IN (SELECT id FROM fams_inventory_item WHERE task_id IN"
                                    + " (SELECT id FROM fams_inventory_task WHERE"
                                    + " creator_account_id=943001))"
                            : "task_id IN (SELECT id FROM fams_inventory_task WHERE"
                                    + " creator_account_id=943001)";
            db.update("DELETE FROM " + table + " WHERE " + where);
        }
        db.update(
                "DELETE FROM fams_inventory_observation WHERE actor_account_id BETWEEN 943001 AND"
                        + " 943005");
        db.update(
                "DELETE FROM fams_inventory_clock_calibration WHERE actor_account_id BETWEEN 943001"
                        + " AND 943005");
        db.update("DELETE FROM fams_mini_session WHERE account_id BETWEEN 943001 AND 943005");
        db.update("DELETE FROM fams_mini_binding WHERE account_id BETWEEN 943001 AND 943005");
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
                            + " creator_account_id=943001)");
        }
        db.update("DELETE FROM fams_inventory_task WHERE creator_account_id=943001");
        db.update(
                "DELETE FROM fams_transfer_receipt_grant WHERE item_id IN (SELECT id FROM"
                    + " fams_transfer_item WHERE request_id IN (SELECT id FROM fams_transfer WHERE"
                    + " creator_account_id=943001))");
        db.update(
                "DELETE FROM fams_transfer_event WHERE request_id IN (SELECT id FROM fams_transfer"
                        + " WHERE creator_account_id=943001)");
        db.update(
                "DELETE FROM fams_todo WHERE business_type='TRANSFER' AND business_id IN (SELECT id"
                        + " FROM fams_transfer WHERE creator_account_id=943001)");
        db.update("DELETE FROM fams_asset_business_lock WHERE asset_id BETWEEN 943010 AND 943012");
        db.update(
                "DELETE FROM fams_transfer_item WHERE request_id IN (SELECT id FROM fams_transfer"
                        + " WHERE creator_account_id=943001)");
        db.update("DELETE FROM fams_transfer WHERE creator_account_id=943001");
        db.update(
                "DELETE FROM fams_idempotency_record WHERE actor_account_id BETWEEN 943001 AND"
                        + " 943005");
        db.update("DELETE FROM fams_audit_event WHERE actor_account_id BETWEEN 943001 AND 943005");
        db.update("DELETE FROM fams_asset_timeline_event WHERE asset_id BETWEEN 943010 AND 943012");
        db.update("DELETE FROM fams_asset WHERE id BETWEEN 943010 AND 943012");
        db.update(
                "UPDATE fams_business_parameter_state SET current_version=0,current_version_id=NULL"
                        + " WHERE singleton_id=1");
        db.update("DELETE FROM fams_business_parameter_version WHERE id=943000");
        db.update("DELETE FROM fams_security_event WHERE account_id BETWEEN 943001 AND 943005");
        db.update("DELETE FROM fams_account_role WHERE account_id BETWEEN 943001 AND 943005");
        db.update("DELETE FROM fams_system_account WHERE id BETWEEN 943001 AND 943005");
        db.update("DELETE FROM fams_employee WHERE id BETWEEN 943001 AND 943005");
        db.update(
                "DELETE FROM fams_location WHERE id IN"
                    + " (943000,943099,9007199254740993,9007199254740995)");
        for (String table :
                new String[] {"fams_warehouse", "fams_asset_category", "fams_organization"}) {
            db.update("DELETE FROM " + table + " WHERE id=943000");
        }
    }

    @Test
    void normalBatchPreservesOriginalReceiptsAndReturnsMixedPerItemConflicts() throws Exception {
        String task = prepared();
        var view = view(task);
        var first = view.path("items").get(0);
        assertEquals(
                "9007199254740993",
                first.path("candidate")
                        .path("facts_json")
                        .path("actual")
                        .path("locationId")
                        .asText());
        assertTrue(
                first.path("candidate")
                        .path("facts_json")
                        .path("actual")
                        .path("locationId")
                        .isString());
        long version = view.path("task").path("version").asLong();
        String key = key();
        String one = batch(view, 0);
        var accepted = ok(review(admin, task, version, key, one));
        assertEquals(1, accepted.path("accepted").asInt());
        assertEquals(accepted, ok(review(admin, task, version, key, one)));
        assertEquals(
                409,
                review(admin, task, version, key, one.replace("normal checked", "changed reason"))
                        .statusCode());
        var stale = review(other, task, version, key(), one);
        assertEquals(409, stale.statusCode(), stale.body());
        assertEquals(
                "review1",
                JSON.readTree(stale.body()).path("fieldErrors").path("processedBy").asText());
        assertTrue(
                JSON.readTree(stale.body())
                        .path("fieldErrors")
                        .path("processedAt")
                        .asText()
                        .endsWith("Z"));
        var next = view(task);
        var both = (tools.jackson.databind.node.ObjectNode) JSON.readTree(batch(next, 0, 1));
        ((tools.jackson.databind.node.ObjectNode) both.path("items").get(0))
                .put("reviewVersion", 0);
        var mixed =
                ok(
                        review(
                                other,
                                task,
                                next.path("task").path("version").asLong(),
                                key(),
                                both.toString()));
        assertEquals(1, mixed.path("accepted").asInt());
        assertEquals(1, mixed.path("conflicts").asInt());
        assertEquals(
                2, count("SELECT COUNT(*) FROM fams_inventory_item_review WHERE task_id=?", task));
        assertEquals(
                2,
                count(
                        "SELECT COUNT(*) FROM fams_inventory_review_subject WHERE task_id=? AND"
                                + " dimension='NORMAL' AND subject_status='CLOSED'",
                        task));
        assertEquals(
                2, count("SELECT COUNT(*) FROM fams_inventory_observation WHERE task_id=?", task));
        assertNull(
                db.queryForObject(
                        "SELECT last_inventory_business_date FROM fams_asset WHERE id=943010",
                        Object.class));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id IN"
                                + " (943010,943011)",
                        null));
        assertTrue(
                count(
                                "SELECT COUNT(*) FROM fams_inventory_task_event WHERE task_id=? AND"
                                        + " action='INVENTORY_REVIEW_CONFLICT_REJECTED'",
                                task)
                        >= 2);
    }

    @Test
    void twoCurrentAdministratorsCompeteWithoutDuplicateConclusions() throws Exception {
        String task = prepared();
        var view = view(task);
        long version = view.path("task").path("version").asLong();
        String body = batch(view, 0);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var a =
                    pool.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return review(admin, task, version, key(), body);
                            });
            var b =
                    pool.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return review(other, task, version, key(), body);
                            });
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            var first = a.get(20, java.util.concurrent.TimeUnit.SECONDS);
            var second = b.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(
                    java.util.Set.of(200, 409),
                    java.util.Set.of(first.statusCode(), second.statusCode()));
            var loser = first.statusCode() == 409 ? first : second;
            assertTrue(
                    JSON.readTree(loser.body())
                            .path("fieldErrors")
                            .path("processedBy")
                            .asText()
                            .startsWith("review"));
            assertEquals(
                    1,
                    count("SELECT COUNT(*) FROM fams_inventory_item_review WHERE task_id=?", task));
            assertEquals(
                    1,
                    count(
                            "SELECT COUNT(*) FROM fams_inventory_task_event WHERE task_id=? AND"
                                    + " action='INVENTORY_NORMAL_REVIEWED'",
                            task));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void conflictingEvidenceRemainsIndependentFromAnotherNormalItem() throws Exception {
        String task = prepared();
        var input = observation(task, 943010);
        ((tools.jackson.databind.node.ObjectNode) input.path("actual")).put("locationId", "943099");
        assertEquals("CONFLICT_REVIEW", ok(observe(task, input)).path("outcome").asText());
        var view = view(task);
        assertEquals(
                "INVENTORY_REVIEW_EVIDENCE_CONFLICT",
                view.path("items").get(0).path("blockedReason").asText());
        var result =
                ok(
                        review(
                                admin,
                                task,
                                view.path("task").path("version").asLong(),
                                key(),
                                batch(view, 0, 1)));
        assertEquals(1, result.path("accepted").asInt());
        assertEquals(1, result.path("conflicts").asInt());
        assertEquals(
                3, count("SELECT COUNT(*) FROM fams_inventory_observation WHERE task_id=?", task));
        assertEquals(
                1, count("SELECT COUNT(*) FROM fams_inventory_item_review WHERE task_id=?", task));
        var history =
                ok(
                        request(
                                admin,
                                "GET",
                                "/api/v1/inventory-reviews/tasks/"
                                        + task
                                        + "/items/"
                                        + view.path("items").get(0).path("item").path("id").asText()
                                        + "/observations",
                                null,
                                null,
                                null));
        assertEquals(2, history.path("total").asInt());
    }

    @Test
    void ordinaryNameChangeIsNormalButActualTransferRoundTripRequiresReview() throws Exception {
        String task = prepared();
        ok(
                request(
                        warehouse,
                        "PATCH",
                        "/api/v1/assets/943010",
                        "{\"name\":\"renamed only\"}",
                        key(),
                        0L));
        var view = view(task);
        assertTrue(view.path("items").get(0).path("canAcceptNormal").asBoolean());
        assertEquals(
                "renamed only", view.path("items").get(0).path("current").path("name").asText());
        assertEquals(
                1,
                ok(review(
                                admin,
                                task,
                                view.path("task").path("version").asLong(),
                                key(),
                                batch(view, 0)))
                        .path("accepted")
                        .asInt());
        transfer(943011, 0, 943099);
        transfer(943011, 1, 9007199254740993L);
        view = view(task);
        var moved = view.path("items").get(1);
        assertEquals(
                moved.path("item").path("snapshot_json").path("locationId"),
                moved.path("current").path("locationId"));
        assertEquals(2, moved.path("movementEvents").size());
        assertEquals("INVENTORY_REVIEW_PERIOD_MOVEMENT", moved.path("blockedReason").asText());
        var result =
                ok(
                        review(
                                admin,
                                task,
                                view.path("task").path("version").asLong(),
                                key(),
                                batch(view, 1)));
        assertEquals(0, result.path("accepted").asInt());
        assertEquals(1, result.path("conflicts").asInt());
    }

    @Test
    void miniBindingAndCurrentRolesPrecedeOriginalReviewReceipt() throws Exception {
        String task = prepared();
        var view = view(task);
        String one = batch(view, 0);
        long version = view.path("task").path("version").asLong();
        assertEquals(403, review(employee, task, version, key(), one).statusCode());
        assertEquals(403, review(system, task, version, key(), one).statusCode());
        var identity = new MiniWechatIdentityPort.Identity("review-app", "admin-review");
        db.update(
                "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES"
                        + " (943001,?,NOW(3))",
                miniSessions.identityHash(identity));
        var grant = miniSessions.start(identity, UUID.randomUUID());
        String key = key();
        String url = "/api/v1/mini/inventory-reviews/tasks/" + task + "/normal-acceptance";
        var response = mini(grant.sessionToken(), url, one, key, version);
        assertEquals(200, response.statusCode(), response.body());
        db.update("UPDATE fams_mini_binding SET revoked_at=NOW(3) WHERE account_id=943001");
        assertEquals(401, mini(grant.sessionToken(), url, one, key, version).statusCode());
        db.update("UPDATE fams_system_account SET auth_epoch=auth_epoch+1 WHERE id=943001");
        assertEquals(401, review(admin, task, version, key, one).statusCode());
        assertEquals(
                1, count("SELECT COUNT(*) FROM fams_inventory_item_review WHERE task_id=?", task));
    }

    @Test
    void nativeReviewHandlersPersistDecisionsAndReplayAnActuallyLostAcceptanceReply()
            throws Exception {
        String task = differenceTask(false);
        var source = view(task).path("items").get(0);
        String item = source.path("item").path("id").asText();
        String observation = source.path("candidate").path("id").asText();
        String counter =
                db.queryForObject(
                        "SELECT CAST(id AS CHAR) FROM fams_inventory_observation WHERE task_id=?"
                                + " AND outcome='CONFLICT_REVIEW'",
                        String.class,
                        task);
        var raw =
                db.queryForList(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=? ORDER BY id",
                        task);
        var identity = new MiniWechatIdentityPort.Identity("review-app", "native-admin-review");
        db.update(
                "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES"
                        + " (943001,?,NOW(3))",
                miniSessions.identityHash(identity));
        var grant = miniSessions.start(identity, UUID.randomUUID());
        var script =
                java.nio.file.Path.of(System.getProperty("user.dir"))
                        .getParent()
                        .resolve("apps/miniprogram/test/runtime-inventory-review-http.mjs");
        var builder = new ProcessBuilder("rtk", "proxy", "node", script.toString());
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_INVENTORY_TASK", task);
        builder.environment().put("FAMS_TEST_INVENTORY_ITEM", item);
        builder.environment().put("FAMS_TEST_INVENTORY_OBSERVATION", observation);
        builder.environment().put("FAMS_TEST_INVENTORY_COUNTER", counter);
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
        var log = java.nio.file.Files.createTempFile("fams-native-review-", ".log");
        try {
            var process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Native review HTTP timed out");
            }
            assertEquals(0, process.exitValue(), java.nio.file.Files.readString(log));
        } finally {
            java.nio.file.Files.deleteIfExists(log);
        }
        assertEquals(
                raw,
                db.queryForList(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=? ORDER BY id",
                        task));
        assertEquals(
                2,
                count(
                        "SELECT COUNT(*) FROM fams_inventory_item_review WHERE task_id=?"
                                + " AND review_status='ACCEPTED'",
                        task));
        assertEquals(
                2,
                count("SELECT COUNT(*) FROM fams_inventory_selection_event WHERE task_id=?", task));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_inventory_evidence_disposition WHERE task_id=?",
                        task));
        assertEquals(
                3,
                count("SELECT COUNT(*) FROM fams_inventory_review_decision WHERE task_id=?", task));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset WHERE id BETWEEN 943010 AND 943011"
                                + " AND (last_inventory_business_date IS NOT NULL OR version<>0)",
                        Integer.class));
        db.update(
                "DELETE FROM fams_account_role WHERE account_id=943001 AND"
                        + " role_code='ASSET_ADMIN'");
        assertEquals(
                403,
                http.send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        "http://127.0.0.1:"
                                                                + port
                                                                + "/api/v1/mini/inventory-reviews/tasks/"
                                                                + task))
                                        .header("X-FAMS-Mini-Session", grant.sessionToken())
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString())
                        .statusCode());
    }

    @Test
    void nativeConflictReturnsOnlyItsOwnApiChannelAndActualReviewer() throws Exception {
        String task = prepared();
        var view = view(task);
        var identity = new MiniWechatIdentityPort.Identity("review-app", "native-conflict-review");
        db.update(
                "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES"
                        + " (943001,?,NOW(3))",
                miniSessions.identityHash(identity));
        var grant = miniSessions.start(identity, UUID.randomUUID());
        String path = "/api/v1/mini/inventory-reviews/tasks/" + task + "/normal-acceptance";
        long version = view.path("task").path("version").asLong();
        String input = batch(view, 0);
        ok(mini(grant.sessionToken(), path, input, key(), version));
        var stale = mini(grant.sessionToken(), path, input, key(), version);
        assertEquals(409, stale.statusCode(), stale.body());
        var fields = JSON.readTree(stale.body()).path("fieldErrors");
        assertEquals(
                "/api/v1/mini/inventory-reviews/tasks/" + task,
                fields.path("readOnlyUrl").asText());
        assertEquals("review1", fields.path("processedBy").asText());
        assertEquals(
                1, count("SELECT COUNT(*) FROM fams_inventory_item_review WHERE task_id=?", task));
    }

    @Test
    void nativeReviewCreatesFormalTransferThroughCurrentOwnerCatalogsAndKeepsItProcessing()
            throws Exception {
        String task = differenceTask(false);
        db.update(
                "INSERT INTO"
                    + " fams_location(id,code,code_normalized,name,location_type,warehouse_id,status,created_at,updated_at)"
                    + " VALUES (9007199254740995,'NATIVEBIG','NATIVEBIG','Native large"
                    + " identifier','BIN',943000,'ACTIVE',NOW(3),NOW(3))");
        var source = view(task).path("items").get(0);
        var identity = new MiniWechatIdentityPort.Identity("review-app", "native-formal-review");
        db.update(
                "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES"
                        + " (943001,?,NOW(3))",
                miniSessions.identityHash(identity));
        var grant = miniSessions.start(identity, UUID.randomUUID());
        var script =
                java.nio.file.Path.of(System.getProperty("user.dir"))
                        .getParent()
                        .resolve("apps/miniprogram/test/runtime-inventory-review-http.mjs");
        var builder = new ProcessBuilder("rtk", "proxy", "node", script.toString());
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_INVENTORY_TASK", task);
        builder.environment()
                .put("FAMS_TEST_INVENTORY_ITEM", source.path("item").path("id").asText());
        builder.environment()
                .put(
                        "FAMS_TEST_INVENTORY_OBSERVATION",
                        source.path("candidate").path("id").asText());
        builder.environment()
                .put(
                        "FAMS_TEST_INVENTORY_COUNTER",
                        db.queryForObject(
                                "SELECT CAST(id AS CHAR) FROM fams_inventory_observation WHERE"
                                        + " task_id=? AND outcome='CONFLICT_REVIEW'",
                                String.class,
                                task));
        builder.environment().put("FAMS_TEST_INVENTORY_MODE", "formal");
        builder.environment().put("FAMS_TEST_INVENTORY_TARGET", "9007199254740995");
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
        var log = java.nio.file.Files.createTempFile("fams-native-formal-review-", ".log");
        try {
            var process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Native formal review HTTP timed out");
            }
            assertEquals(0, process.exitValue(), java.nio.file.Files.readString(log));
        } finally {
            java.nio.file.Files.deleteIfExists(log);
        }
        assertEquals(
                1,
                count("SELECT COUNT(*) FROM fams_transfer WHERE creator_account_id=943001", null));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_todo WHERE business_type='TRANSFER' AND"
                                + " closed_at IS NULL",
                        null));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=943010 AND"
                                + " released_at IS NULL",
                        null));
        assertEquals(
                2,
                count("SELECT COUNT(*) FROM fams_inventory_review_decision WHERE task_id=?", task));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_inventory_item_review WHERE task_id=? AND"
                                + " review_status='ACCEPTED'",
                        task));
    }

    @Test
    void failedSubjectWriteRollsBackSelectionsTaskAndReceiptBeforeOriginalRetry() throws Exception {
        String task = prepared();
        var view = view(task);
        String body = batch(view, 0, 1), key = key();
        long version = view.path("task").path("version").asLong();
        db.execute(
                "CREATE TRIGGER owned_review_failure_943000 BEFORE INSERT ON"
                        + " fams_inventory_review_subject FOR EACH ROW SIGNAL SQLSTATE '45000' SET"
                        + " MESSAGE_TEXT='owned review rollback probe'");
        try {
            assertTrue(review(admin, task, version, key, body).statusCode() >= 400);
            assertEquals(
                    0,
                    count("SELECT COUNT(*) FROM fams_inventory_item_review WHERE task_id=?", task));
            assertEquals(
                    0,
                    count(
                            "SELECT COUNT(*) FROM fams_inventory_review_receipt WHERE task_id=?",
                            task));
            assertEquals(
                    version,
                    db.queryForObject(
                            "SELECT version FROM fams_inventory_task WHERE id=?",
                            Long.class,
                            task));
        } finally {
            db.execute("DROP TRIGGER owned_review_failure_943000");
        }
        var recovered = ok(review(admin, task, version, key, body));
        assertEquals(2, recovered.path("accepted").asInt());
        assertEquals(recovered, ok(review(admin, task, version, key, body)));
    }

    @Test
    void jointDifferenceVersionsKeepOtherDimensionsAndOriginalDecisionsIndependent()
            throws Exception {
        String task = differenceTask(true);
        var subjects = differences(task);
        var input =
                (tools.jackson.databind.node.ObjectNode)
                        JSON.readTree(
                                differenceBody(
                                        subjects, "BOOK_CORRECT", "LOCATION", "PERIOD_MOVEMENT"));
        input.put("assetVersion", 2);
        String body = input.toString();
        long version = view(task).path("task").path("version").asLong();
        String operation = key();
        var accepted = ok(difference(admin, task, version, operation, body));
        assertEquals(accepted, ok(difference(admin, task, version, operation, body)));
        assertEquals(
                2,
                count("SELECT COUNT(*) FROM fams_inventory_review_decision WHERE task_id=?", task));
        assertEquals(
                2,
                count(
                        "SELECT COUNT(*) FROM fams_inventory_review_subject WHERE task_id=? AND"
                                + " subject_status='CLOSED'",
                        task));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_inventory_review_subject WHERE task_id=? AND"
                                + " subject_status='OPEN'",
                        task));
        String stale = differenceBody(subjects, "BOOK_CORRECT", "CONFLICT", "LOCATION");
        var rejected = difference(other, task, accepted.path("version").asLong(), key(), stale);
        assertEquals(409, rejected.statusCode(), rejected.body());
        assertEquals(
                "review1",
                JSON.readTree(rejected.body()).path("fieldErrors").path("processedBy").asText());
        assertEquals(
                2,
                count("SELECT COUNT(*) FROM fams_inventory_review_decision WHERE task_id=?", task));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_inventory_review_subject WHERE task_id=? AND"
                                + " dimension='CONFLICT' AND version<>0",
                        task));
        assertEquals(
                3, count("SELECT COUNT(*) FROM fams_inventory_observation WHERE task_id=?", task));
    }

    @Test
    void linkedFormalTransferStaysProcessingUntilRealWarehouseCompletion() throws Exception {
        String task = differenceTask(false);
        var body = JSON.readTree(differenceBody(differences(task), "FORMAL_TRANSFER", "LOCATION"));
        ((tools.jackson.databind.node.ObjectNode) body)
                .set(
                        "transferTarget",
                        JSON.valueToTree(
                                java.util.Map.of(
                                        "custodyType",
                                        "STORAGE",
                                        "warehouseId",
                                        "943000",
                                        "locationId",
                                        "943099",
                                        "referenceVersions",
                                        java.util.Map.of("warehouse", 0, "location", 0))));
        long version = view(task).path("task").path("version").asLong();
        String operation = key();
        var linked = ok(difference(admin, task, version, operation, body.toString()));
        assertEquals(linked, ok(difference(admin, task, version, operation, body.toString())));
        String transfer = linked.path("items").get(0).path("linkedRequestId").asText();
        assertEquals("PROCESSING", linked.path("items").get(0).path("status").asText());
        assertEquals(
                1,
                count("SELECT COUNT(*) FROM fams_transfer WHERE creator_account_id=943001", null));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=943010 AND"
                                + " released_at IS NULL",
                        null));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_todo WHERE business_type='TRANSFER' AND"
                                + " closed_at IS NULL",
                        null));
        linked =
                ok(
                        difference(
                                admin,
                                task,
                                linked.path("version").asLong(),
                                key(),
                                differenceBody(differences(task), "REFRESH_TRANSFER", "LOCATION")));
        assertEquals("PROCESSING", linked.path("items").get(0).path("status").asText());
        assertEquals(
                409,
                difference(
                                admin,
                                task,
                                linked.path("version").asLong(),
                                key(),
                                differenceBody(differences(task), "BOOK_CORRECT", "LOCATION"))
                        .statusCode());
        var row = ok(request(admin, "GET", "/api/v1/transfers/" + transfer, null, null, null));
        row =
                transferAction(
                        admin,
                        row,
                        "/decision",
                        "{\"approved\":true,\"reason\":\"approved inventory association\"}");
        assertEquals(
                403,
                request(
                                admin,
                                "POST",
                                "/api/v1/transfers/" + transfer + "/claim",
                                null,
                                key(),
                                row.path("version").asLong())
                        .statusCode());
        row = transferAction(warehouse, row, "/claim", null);
        var item = row.path("items").get(0);
        String physical =
                JSON.writeValueAsString(
                        java.util.Map.of(
                                "itemVersion",
                                item.path("version").asLong(),
                                "observedCode",
                                "INV-ASSET-943010",
                                "destination",
                                "checked inventory bin",
                                "evidence",
                                "real warehouse physical verification"));
        transferAction(warehouse, row, "/items/" + item.path("id").asText() + "/verify", physical);
        var refresh =
                (tools.jackson.databind.node.ObjectNode)
                        JSON.readTree(
                                differenceBody(differences(task), "REFRESH_TRANSFER", "LOCATION"));
        refresh.put("assetVersion", 1);
        var completed =
                ok(
                        difference(
                                admin,
                                task,
                                linked.path("version").asLong(),
                                key(),
                                refresh.toString()));
        assertEquals("CLOSED", completed.path("items").get(0).path("status").asText());
        assertEquals(
                3,
                count("SELECT COUNT(*) FROM fams_inventory_review_decision WHERE task_id=?", task));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_inventory_review_transfer WHERE observation_id"
                                + " IN (SELECT id FROM fams_inventory_observation WHERE task_id=?)",
                        task));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_inventory_review_subject WHERE task_id=? AND"
                                + " dimension='CONFLICT' AND subject_status<>'OPEN'",
                        task));
        assertNull(
                db.queryForObject(
                        "SELECT last_inventory_business_date FROM fams_asset WHERE id=943010",
                        Object.class));
    }

    @Test
    void failedAssociationRollsBackTransferTodoLockAndDecisionThenRejectedLinkCanContinue()
            throws Exception {
        String task = differenceTask(false);
        var body =
                (tools.jackson.databind.node.ObjectNode)
                        JSON.readTree(
                                differenceBody(differences(task), "FORMAL_TRANSFER", "LOCATION"));
        body.set(
                "transferTarget",
                JSON.valueToTree(
                        java.util.Map.of(
                                "custodyType",
                                "STORAGE",
                                "warehouseId",
                                "943000",
                                "locationId",
                                "943099",
                                "referenceVersions",
                                java.util.Map.of("warehouse", 0, "location", 0))));
        long version = view(task).path("task").path("version").asLong();
        String operation = key();
        var invalid = (tools.jackson.databind.node.ObjectNode) JSON.readTree(body.toString());
        ((tools.jackson.databind.node.ObjectNode) invalid.path("transferTarget"))
                .put("locationId", "9007199254740993");
        assertEquals(400, difference(admin, task, version, key(), invalid.toString()).statusCode());
        assertEquals(
                0,
                count("SELECT COUNT(*) FROM fams_transfer WHERE creator_account_id=943001", null));

        db.execute(
                "CREATE TRIGGER owned_inventory_link_failure BEFORE INSERT ON"
                        + " fams_inventory_review_transfer FOR EACH ROW SIGNAL SQLSTATE '45000' SET"
                        + " MESSAGE_TEXT='owned association failure'");
        try {
            assertTrue(
                    difference(admin, task, version, operation, body.toString()).statusCode()
                            >= 400);
            assertEquals(
                    0,
                    count(
                            "SELECT COUNT(*) FROM fams_transfer WHERE creator_account_id=943001",
                            null));
            assertEquals(
                    0,
                    count(
                            "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=943010",
                            null));
            assertEquals(
                    0,
                    count(
                            "SELECT COUNT(*) FROM fams_inventory_review_decision WHERE task_id=?",
                            task));
            assertEquals(
                    0,
                    count("SELECT COUNT(*) FROM fams_todo WHERE business_type='TRANSFER'", null));
        } finally {
            db.execute("DROP TRIGGER owned_inventory_link_failure");
        }
        var linked = ok(difference(admin, task, version, operation, body.toString()));
        String transfer = linked.path("items").get(0).path("linkedRequestId").asText();
        var row = ok(request(admin, "GET", "/api/v1/transfers/" + transfer, null, null, null));
        transferAction(
                admin,
                row,
                "/decision",
                "{\"approved\":false,\"reason\":\"reject proposed correction\"}");
        var refreshed =
                ok(
                        difference(
                                admin,
                                task,
                                linked.path("version").asLong(),
                                key(),
                                differenceBody(differences(task), "REFRESH_TRANSFER", "LOCATION")));
        assertEquals("OPEN", refreshed.path("items").get(0).path("status").asText());
        var closed =
                ok(
                        difference(
                                other,
                                task,
                                refreshed.path("version").asLong(),
                                key(),
                                differenceBody(differences(task), "BOOK_CORRECT", "LOCATION")));
        assertEquals("CLOSED", closed.path("items").get(0).path("status").asText());
        assertEquals(
                3,
                count("SELECT COUNT(*) FROM fams_inventory_review_decision WHERE task_id=?", task));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_inventory_review_transfer WHERE observation_id"
                                + " IN (SELECT id FROM fams_inventory_observation WHERE task_id=?)",
                        task));
    }

    @Test
    void differenceReceiptsKeepCurrentAuthorizationAndAuditChangedPayloadBeforeTerminalReplay()
            throws Exception {
        String task = differenceTask(false);
        String body = differenceBody(differences(task), "BOOK_CORRECT", "LOCATION");
        long version = view(task).path("task").path("version").asLong();
        String operation = key();
        assertEquals(403, difference(employee, task, version, operation, body).statusCode());
        assertEquals(
                403,
                request(
                                system,
                                "GET",
                                "/api/v1/inventory-reviews/tasks/" + task + "/differences",
                                null,
                                null,
                                null)
                        .statusCode());
        var accepted = ok(difference(admin, task, version, operation, body));
        assertEquals(
                409,
                difference(
                                admin,
                                task,
                                version,
                                operation,
                                body.replace(
                                        "checked independent evidence",
                                        "changed independent evidence"))
                        .statusCode());
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_inventory_task_event WHERE task_id=? AND"
                                + " action='INVENTORY_DIFFERENCE_CONFLICT_REJECTED'",
                        task));
        ok(
                request(
                        admin,
                        "POST",
                        "/api/v1/inventory-tasks/" + task + "/terminate",
                        "{\"reason\":\"retain all review evidence\"}",
                        key(),
                        accepted.path("version").asLong()));
        assertEquals(accepted, ok(difference(admin, task, version, operation, body)));
        assertEquals(
                409,
                difference(admin, task, accepted.path("version").asLong() + 1, key(), body)
                        .statusCode());
        db.update("UPDATE fams_system_account SET auth_epoch=auth_epoch+1 WHERE id=943001");
        assertEquals(401, difference(admin, task, version, operation, body).statusCode());
        assertEquals(
                1,
                count("SELECT COUNT(*) FROM fams_inventory_review_decision WHERE task_id=?", task));
    }

    @Test
    void preparationPreservesTimeOldDuplicateAndExpiredEvidenceWithoutAutomaticAcceptance()
            throws Exception {
        String task = prepared();
        var old = observation(task, 943010).put("assignmentVersion", 0);
        assertEquals("OLD_ASSIGNMENT_REVIEW", ok(observe(task, old)).path("outcome").asText());
        var time = observation(task, 943010).put("clockAnomaly", true);
        assertEquals("TIME_REVIEW", ok(observe(task, time)).path("outcome").asText());
        var duplicate = observation(task, 943010);
        assertEquals("DUPLICATE", ok(observe(task, duplicate)).path("outcome").asText());
        var expired = observation(task, 943010);
        Instant earlier =
                Instant.now()
                        .minusSeconds(73 * 3600)
                        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        expired.put("rawScannedAt", earlier.toString())
                .put("rawObservedAt", earlier.toString())
                .put("firstPersistedAt", earlier.toString())
                .put("expiresAt", earlier.plusSeconds(72 * 3600).toString());
        // Controlled historical calibration fixture: expiry is not a fresh-clock anomaly.
        db.update(
                "UPDATE fams_inventory_clock_calibration SET client_time=?,server_time=? WHERE"
                        + " id=?",
                java.sql.Timestamp.from(earlier),
                java.sql.Timestamp.from(earlier),
                expired.path("calibrationId").asText());
        assertEquals("EXPIRED", ok(observe(task, expired)).path("outcome").asText());
        var ids =
                db
                        .queryForList(
                                "SELECT CAST(id AS CHAR) AS id FROM fams_inventory_observation"
                                        + " WHERE task_id=? ORDER BY id",
                                task)
                        .stream()
                        .map(row -> row.get("id"))
                        .toList();
        long version = view(task).path("task").path("version").asLong();
        String preparation = JSON.writeValueAsString(java.util.Map.of("observationIds", ids));
        String operation = key();
        String path = "/api/v1/inventory-reviews/tasks/" + task + "/differences/preparation";
        assertEquals(
                400,
                request(
                                admin,
                                "POST",
                                path,
                                JSON.writeValueAsString(
                                        java.util.Map.of(
                                                "observationIds",
                                                java.util.List.of(ids.getFirst(), ids.getFirst()))),
                                key(),
                                version)
                        .statusCode());
        var prepared = ok(request(admin, "POST", path, preparation, operation, version));
        assertEquals(2, prepared.path("created").asInt());
        assertEquals(prepared, ok(request(admin, "POST", path, preparation, operation, version)));
        var subjects = differences(task);
        String search = differenceBody(subjects, "CONTINUE_SEARCH", "TIME", "OLD_ASSIGNMENT");
        var pending = ok(difference(admin, task, prepared.path("version").asLong(), key(), search));
        assertEquals(2, pending.path("items").size());
        assertEquals(
                409,
                difference(
                                admin,
                                task,
                                pending.path("version").asLong(),
                                key(),
                                differenceBody(differences(task), "BOOK_CORRECT", "TIME"))
                        .statusCode());
        assertEquals(
                0, count("SELECT COUNT(*) FROM fams_inventory_item_review WHERE task_id=?", task));
        assertEquals(
                6, count("SELECT COUNT(*) FROM fams_inventory_observation WHERE task_id=?", task));
    }

    private String differenceTask(boolean multiple) throws Exception {
        String task = prepared();
        if (multiple) {
            transfer(943010, 0, 943099);
            transfer(943010, 1, 9007199254740993L);
        }
        var observed = observation(task, 943010);
        var actual = (tools.jackson.databind.node.ObjectNode) observed.path("actual");
        actual.put("locationId", "943099");

        assertEquals("CONFLICT_REVIEW", ok(observe(task, observed)).path("outcome").asText());
        long id =
                db.queryForObject(
                        "SELECT id FROM fams_inventory_observation WHERE task_id=? AND"
                                + " client_event_id=?",
                        Long.class,
                        task,
                        observed.path("eventId").asText());
        long version = view(task).path("task").path("version").asLong();
        ok(
                request(
                        admin,
                        "POST",
                        "/api/v1/inventory-reviews/tasks/" + task + "/differences/preparation",
                        JSON.writeValueAsString(
                                java.util.Map.of(
                                        "observationIds", java.util.List.of(Long.toString(id)))),
                        key(),
                        version));
        return task;
    }

    private JsonNode differences(String task) throws Exception {
        return ok(
                request(
                        admin,
                        "GET",
                        "/api/v1/inventory-reviews/tasks/" + task + "/differences",
                        null,
                        null,
                        null));
    }

    private String differenceBody(JsonNode board, String method, String... dimensions) {
        var body =
                JSON.createObjectNode()
                        .put("method", method)
                        .put("assetVersion", 0)
                        .put("reason", "checked independent evidence")
                        .put(
                                "evidence",
                                "Signed physical register and independent location verification");
        var rows = body.putArray("subjects");
        for (String dimension : dimensions) {
            for (var row : board.path("items")) {
                if (dimension.equals(row.path("dimension").asText())) {
                    rows.addObject()
                            .put("observationId", row.path("observation_id").asText())
                            .put("dimension", dimension)
                            .put("version", row.path("version").asLong());
                }
            }
        }
        assertEquals(dimensions.length, rows.size());
        return body.toString();
    }

    private HttpResponse<String> difference(
            Session actor, String task, long version, String operation, String body)
            throws Exception {
        return request(
                actor,
                "POST",
                "/api/v1/inventory-reviews/tasks/" + task + "/differences/decisions",
                body,
                operation,
                version);
    }

    @Test
    void explicitSelectionPreservesRawEvidenceAndHistoryWithoutAcceptingDifferences()
            throws Exception {
        String task = prepared();
        var fact = observation(task, 943010);
        ((tools.jackson.databind.node.ObjectNode) fact.path("actual")).put("locationId", "943099");
        assertEquals("CONFLICT_REVIEW", ok(observe(task, fact)).path("outcome").asText());
        long observation =
                db.queryForObject(
                        "SELECT id FROM fams_inventory_observation WHERE task_id=? AND"
                                + " client_event_id=?",
                        Long.class,
                        task,
                        fact.path("eventId").asText());
        String item = fact.path("itemId").asText();
        String path = "/api/v1/inventory-reviews/tasks/" + task + "/items/" + item + "/selection";
        var raw =
                db.queryForList(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=? ORDER BY id",
                        task);
        var context = ok(request(admin, "GET", path, null, null, null));
        var input =
                JSON.createObjectNode()
                        .put("observationId", Long.toString(observation))
                        .put("recheckGeneration", 0)
                        .put("reviewVersion", 0)
                        .put("assetVersion", 0)
                        .put("contextDigest", context.path("contextDigest").asText())
                        .put("reason", "明确选择现场证据")
                        .put("evidence", "独立现场签字记录与原始照片编号");
        String key = key();
        var selected =
                ok(
                        request(
                                admin,
                                "POST",
                                path,
                                input.toString(),
                                key,
                                context.path("taskVersion").asLong()));
        assertEquals("SELECTED", selected.path("review").path("review_status").asText());
        assertEquals(
                Long.toString(observation),
                selected.path("review").path("observation_id").asText());
        assertEquals(0, view(task).path("reviewedCount").asInt());
        assertEquals(
                1,
                count("SELECT COUNT(*) FROM fams_inventory_selection_event WHERE task_id=?", task));
        assertEquals(
                selected,
                ok(
                        request(
                                admin,
                                "POST",
                                path,
                                input.toString(),
                                key,
                                context.path("taskVersion").asLong())));
        var changed = input.deepCopy().put("reason", "改变原载荷");
        assertEquals(
                409,
                request(
                                admin,
                                "POST",
                                path,
                                changed.toString(),
                                key,
                                context.path("taskVersion").asLong())
                        .statusCode());
        var loser =
                request(
                        other,
                        "POST",
                        path,
                        input.toString(),
                        key(),
                        selected.path("version").asLong());
        assertEquals(409, loser.statusCode());
        assertEquals(
                "review1",
                JSON.readTree(loser.body()).path("fieldErrors").path("processedBy").asText());
        assertEquals(403, request(employee, "GET", path, null, null, null).statusCode());
        var second = input.deepCopy().put("reviewVersion", 1).put("reason", "补充复核后的明确选择");
        var again =
                ok(
                        request(
                                other,
                                "POST",
                                path,
                                second.toString(),
                                key(),
                                selected.path("version").asLong()));
        assertEquals(2, again.path("review").path("version").asInt());
        var history = ok(request(admin, "GET", path + "?pageSize=1&page=2", null, null, null));
        assertEquals(2, history.path("total").asInt());
        assertEquals(1, history.path("history").size());
        assertEquals("review3", history.path("history").get(0).path("actor_username").asText());
        assertEquals(
                raw,
                db.queryForList(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=? ORDER BY id",
                        task));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_asset WHERE id IN (943010,943011) AND"
                                + " last_inventory_business_date IS NOT NULL",
                        null));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id IN"
                                + " (943010,943011)",
                        null));
    }

    @Test
    void timeSelectionRequiresSeparateVerifiedTimeAndStaleContextCannotWin() throws Exception {
        String task = prepared();
        var fact = observation(task, 943010).put("clockAnomaly", true);
        assertEquals("TIME_REVIEW", ok(observe(task, fact)).path("outcome").asText());
        String item = fact.path("itemId").asText();
        long observation =
                db.queryForObject(
                        "SELECT id FROM fams_inventory_observation WHERE task_id=? AND"
                                + " client_event_id=?",
                        Long.class,
                        task,
                        fact.path("eventId").asText());
        String path = "/api/v1/inventory-reviews/tasks/" + task + "/items/" + item + "/selection";
        var context = ok(request(admin, "GET", path, null, null, null));
        var input =
                JSON.createObjectNode()
                        .put("observationId", Long.toString(observation))
                        .put("recheckGeneration", 0)
                        .put("reviewVersion", 0)
                        .put("assetVersion", 0)
                        .put("contextDigest", context.path("contextDigest").asText())
                        .put("reason", "核实异常设备时间")
                        .put("evidence", "核对签字表所载现场观察时间和编号");
        long version = context.path("taskVersion").asLong();
        assertEquals(
                400, request(admin, "POST", path, input.toString(), key(), version).statusCode());
        input.put("verifiedObservedAt", Instant.now().plusSeconds(1).toString());
        assertEquals(
                400, request(admin, "POST", path, input.toString(), key(), version).statusCode());
        input.put("verifiedObservedAt", fact.path("rawObservedAt").asText());
        transfer(943010, 0, 943099);
        input.put("assetVersion", 1);
        assertEquals(
                409, request(admin, "POST", path, input.toString(), key(), version).statusCode());
        context = ok(request(admin, "GET", path, null, null, null));
        input.put("contextDigest", context.path("contextDigest").asText());
        var selected = ok(request(admin, "POST", path, input.toString(), key(), version));
        assertEquals("SELECTED", selected.path("review").path("review_status").asText());
        var history = ok(request(admin, "GET", path, null, null, null)).path("history").get(0);
        assertEquals("ADMIN_VERIFIED", history.path("time_source").asText());
        assertTrue(history.path("movement_count").asInt() > 0);
        assertEquals(
                "TIME_REVIEW",
                db.queryForObject(
                        "SELECT outcome FROM fams_inventory_observation WHERE id=?",
                        String.class,
                        observation));
        assertEquals(0, view(task).path("reviewedCount").asInt());
    }

    @Test
    void failedSelectionHistoryWriteRollsBackBeforeOriginalRetryAndAcceptedReviewCannotBeReplaced()
            throws Exception {
        String task = prepared();
        var board = view(task);
        var row = board.path("items").get(0);
        String item = row.path("item").path("id").asText();
        String path = "/api/v1/inventory-reviews/tasks/" + task + "/items/" + item + "/selection";
        var context = ok(request(admin, "GET", path, null, null, null));
        var input =
                JSON.createObjectNode()
                        .put("observationId", row.path("candidate").path("id").asText())
                        .put("recheckGeneration", 0)
                        .put("reviewVersion", 0)
                        .put("assetVersion", 0)
                        .put("contextDigest", context.path("contextDigest").asText())
                        .put("reason", "核对正常证据选择")
                        .put("evidence", "签字原件与现场照片编号明确可追溯");
        var retimed = input.deepCopy().put("verifiedObservedAt", Instant.now().toString());
        assertEquals(
                400,
                request(
                                admin,
                                "POST",
                                path,
                                retimed.toString(),
                                key(),
                                board.path("task").path("version").asLong())
                        .statusCode());
        String originalKey = key();
        db.execute(
                "CREATE TRIGGER owned_selection_failure BEFORE INSERT ON"
                        + " fams_inventory_selection_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET"
                        + " MESSAGE_TEXT='Owned selection rollback probe'");
        try {
            assertEquals(
                    500,
                    request(
                                    admin,
                                    "POST",
                                    path,
                                    input.toString(),
                                    originalKey,
                                    context.path("taskVersion").asLong())
                            .statusCode());
        } finally {
            db.execute("DROP TRIGGER owned_selection_failure");
        }
        assertEquals(
                0, count("SELECT COUNT(*) FROM fams_inventory_item_review WHERE task_id=?", task));
        assertEquals(
                0,
                count("SELECT COUNT(*) FROM fams_inventory_selection_event WHERE task_id=?", task));
        assertEquals(
                0,
                count("SELECT COUNT(*) FROM fams_inventory_review_receipt WHERE task_id=?", task));
        assertEquals(
                context.path("taskVersion").asLong(),
                view(task).path("task").path("version").asLong());
        var selected =
                ok(
                        request(
                                admin,
                                "POST",
                                path,
                                input.toString(),
                                originalKey,
                                context.path("taskVersion").asLong()));
        var before = view(task);
        var accepted =
                ok(
                        review(
                                admin,
                                task,
                                before.path("task").path("version").asLong(),
                                key(),
                                batch(before, 1)));
        assertEquals(1, accepted.path("accepted").asInt());
        String acceptedItem = before.path("items").get(1).path("item").path("id").asText();
        String acceptedPath =
                "/api/v1/inventory-reviews/tasks/" + task + "/items/" + acceptedItem + "/selection";
        var acceptedContext = ok(request(admin, "GET", acceptedPath, null, null, null));
        var replacement =
                input.deepCopy()
                        .put(
                                "observationId",
                                before.path("items").get(1).path("candidate").path("id").asText())
                        .put("reviewVersion", 1)
                        .put("contextDigest", acceptedContext.path("contextDigest").asText());
        assertEquals(
                409,
                request(
                                admin,
                                "POST",
                                acceptedPath,
                                replacement.toString(),
                                key(),
                                acceptedContext.path("taskVersion").asLong())
                        .statusCode());
        ok(
                request(
                        admin,
                        "POST",
                        "/api/v1/inventory-tasks/" + task + "/terminate",
                        "{\"reason\":\"selection terminal replay\"}",
                        key(),
                        view(task).path("task").path("version").asLong()));
        assertEquals(
                selected,
                ok(
                        request(
                                admin,
                                "POST",
                                path,
                                input.toString(),
                                originalKey,
                                context.path("taskVersion").asLong())));
        db.update(
                "DELETE FROM fams_account_role WHERE account_id=943001 AND"
                        + " role_code='ASSET_ADMIN'");
        assertEquals(
                403,
                request(
                                admin,
                                "POST",
                                path,
                                input.toString(),
                                originalKey,
                                context.path("taskVersion").asLong())
                        .statusCode());
    }

    @Test
    void counterevidenceClosesEveryRejectedDimensionBeforeConcurrentFinalAcceptance()
            throws Exception {
        String task = differenceTask(false);
        var source = view(task).path("items").get(0);
        String item = source.path("item").path("id").asText();
        String originalObservation = source.path("candidate").path("id").asText();
        String bad =
                db.queryForObject(
                        "SELECT CAST(id AS CHAR) FROM fams_inventory_observation WHERE task_id=?"
                                + " AND outcome='CONFLICT_REVIEW'",
                        String.class,
                        task);
        var raw =
                db.queryForList(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=? ORDER BY id",
                        task);
        chooseEvidence(task, item, originalObservation, null);
        prepareAll(task);
        String base = "/api/v1/inventory-reviews/tasks/" + task + "/items/" + item;
        var acceptance = evidenceAcceptance(task, item);
        assertEquals(
                409,
                request(
                                admin,
                                "POST",
                                base + "/acceptance",
                                acceptance.toString(),
                                key(),
                                taskVersion(task))
                        .statusCode());
        var verify = evidenceDecision(task, item, originalObservation, "VERIFY_SELECTED");
        long verifyVersion = taskVersion(task);
        ok(
                request(
                        other,
                        "POST",
                        base + "/evidence-decisions",
                        verify.toString(),
                        key(),
                        taskVersion(task)));
        var staleVerification =
                request(
                        admin,
                        "POST",
                        base + "/evidence-decisions",
                        verify.toString(),
                        key(),
                        verifyVersion);
        assertEquals(409, staleVerification.statusCode());
        assertEquals(
                "review3",
                JSON.readTree(staleVerification.body())
                        .path("fieldErrors")
                        .path("processedBy")
                        .asText());
        assertEquals(
                "VERIFY_SELECTED",
                JSON.readTree(staleVerification.body())
                        .path("fieldErrors")
                        .path("method")
                        .asText());
        var rejection = evidenceDecision(task, item, bad, "REJECT_EVIDENCE");
        var incomplete = rejection.deepCopy();
        incomplete.putArray("subjects").add(rejection.path("subjects").get(0));
        assertEquals(
                409,
                request(
                                admin,
                                "POST",
                                base + "/evidence-decisions",
                                incomplete.toString(),
                                key(),
                                taskVersion(task))
                        .statusCode());
        String originalKey = key();
        long version = taskVersion(task);
        db.execute(
                "CREATE TRIGGER owned_disposition_failure BEFORE INSERT ON"
                    + " fams_inventory_evidence_disposition FOR EACH ROW SIGNAL SQLSTATE '45000'"
                    + " SET MESSAGE_TEXT='Owned disposition rollback probe'");
        try {
            assertEquals(
                    500,
                    request(
                                    admin,
                                    "POST",
                                    base + "/evidence-decisions",
                                    rejection.toString(),
                                    originalKey,
                                    version)
                            .statusCode());
        } finally {
            db.execute("DROP TRIGGER owned_disposition_failure");
        }
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_inventory_evidence_disposition WHERE task_id=?",
                        task));
        assertEquals(
                1,
                count("SELECT COUNT(*) FROM fams_inventory_review_decision WHERE task_id=?", task));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_review_subject WHERE observation_id=?"
                                + " AND subject_status='OPEN'",
                        Integer.class,
                        bad));
        var rejected =
                ok(
                        request(
                                admin,
                                "POST",
                                base + "/evidence-decisions",
                                rejection.toString(),
                                originalKey,
                                version));
        assertEquals(
                rejected,
                ok(
                        request(
                                admin,
                                "POST",
                                base + "/evidence-decisions",
                                rejection.toString(),
                                originalKey,
                                version)));
        var selection = selectionView(task, item);
        var change =
                JSON.createObjectNode()
                        .put("observationId", bad)
                        .put("recheckGeneration", 0)
                        .put("reviewVersion", 1)
                        .put("assetVersion", 0)
                        .put("contextDigest", selection.path("contextDigest").asText())
                        .put("reason", "试图覆盖已生效反证")
                        .put("evidence", "原始选择已经作为最终反证依据使用");
        assertEquals(
                409,
                request(
                                admin,
                                "POST",
                                base + "/selection",
                                change.toString(),
                                key(),
                                taskVersion(task))
                        .statusCode());
        acceptance = evidenceAcceptance(task, item);
        final String body = acceptance.toString();
        final long expected = taskVersion(task);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first =
                    executor.submit(
                            () ->
                                    request(
                                            admin,
                                            "POST",
                                            base + "/acceptance",
                                            body,
                                            key(),
                                            expected));
            var second =
                    executor.submit(
                            () ->
                                    request(
                                            other,
                                            "POST",
                                            base + "/acceptance",
                                            body,
                                            key(),
                                            expected));
            var replies = java.util.List.of(first.get(), second.get());
            assertEquals(1, replies.stream().filter(reply -> reply.statusCode() == 200).count());
            assertEquals(1, replies.stream().filter(reply -> reply.statusCode() == 409).count());
            var lost =
                    JSON.readTree(
                            replies.stream()
                                    .filter(reply -> reply.statusCode() == 409)
                                    .findFirst()
                                    .orElseThrow()
                                    .body());
            assertTrue(
                    java.util.List.of("review1", "review3")
                            .contains(lost.path("fieldErrors").path("processedBy").asText()));
        }
        assertEquals(1, view(task).path("reviewedCount").asInt());
        assertEquals(
                2,
                count("SELECT COUNT(*) FROM fams_inventory_selection_event WHERE task_id=?", task));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_inventory_evidence_disposition WHERE task_id=?",
                        task));
        assertEquals(
                3,
                count("SELECT COUNT(*) FROM fams_inventory_review_decision WHERE task_id=?", task));
        assertEquals(
                raw,
                db.queryForList(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=? ORDER BY id",
                        task));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_asset WHERE id IN (943010,943011) AND"
                                + " last_inventory_business_date IS NOT NULL",
                        null));
        var dispositions =
                ok(request(admin, "GET", base + "/evidence-dispositions", null, null, null));
        assertEquals("REJECTED", dispositions.path("items").get(0).path("disposition").asText());
    }

    @Test
    void supplementalEvidenceCannotEraseProcessingFormalBusinessOrBypassItsPhysicalDimension()
            throws Exception {
        String task = differenceTask(false);
        var source = view(task).path("items").get(0);
        String item = source.path("item").path("id").asText();
        String chosen = source.path("candidate").path("id").asText();
        String bad =
                db.queryForObject(
                        "SELECT CAST(id AS CHAR) FROM fams_inventory_observation WHERE task_id=?"
                                + " AND outcome='CONFLICT_REVIEW'",
                        String.class,
                        task);
        chooseEvidence(task, item, chosen, null);
        prepareAll(task);
        String transferBody = differenceBody(differences(task), "FORMAL_TRANSFER", "LOCATION");
        var transferInput = (tools.jackson.databind.node.ObjectNode) JSON.readTree(transferBody);
        transferInput.set(
                "transferTarget",
                JSON.readTree(
                        "{\"owningOrganizationId\":null,\"custodyType\":\"STORAGE\",\"usingOrganizationId\":null,\"employeeId\":null,\"warehouseId\":\"943000\",\"locationId\":\"943099\",\"referenceVersions\":{\"warehouse\":0,\"location\":0}}"));
        ok(difference(admin, task, taskVersion(task), key(), transferInput.toString()));
        String base = "/api/v1/inventory-reviews/tasks/" + task + "/items/" + item;
        var rejected = evidenceDecision(task, item, bad, "REJECT_EVIDENCE");
        assertEquals(
                409,
                request(
                                admin,
                                "POST",
                                base + "/evidence-decisions",
                                rejected.toString(),
                                key(),
                                taskVersion(task))
                        .statusCode());
        var supplemental = evidenceDecision(task, item, bad, "SUPPLEMENTAL");
        ok(
                request(
                        admin,
                        "POST",
                        base + "/evidence-decisions",
                        supplemental.toString(),
                        key(),
                        taskVersion(task)));
        assertEquals(
                "PROCESSING",
                db.queryForObject(
                        "SELECT subject_status FROM fams_inventory_review_subject WHERE"
                                + " observation_id=? AND dimension='LOCATION'",
                        String.class,
                        bad));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_inventory_review_transfer", null));
        var verify = evidenceDecision(task, item, chosen, "VERIFY_SELECTED");
        ok(
                request(
                        admin,
                        "POST",
                        base + "/evidence-decisions",
                        verify.toString(),
                        key(),
                        taskVersion(task)));
        assertEquals(
                409,
                request(
                                admin,
                                "POST",
                                base + "/acceptance",
                                evidenceAcceptance(task, item).toString(),
                                key(),
                                taskVersion(task))
                        .statusCode());
        assertEquals(0, view(task).path("reviewedCount").asInt());
    }

    @Test
    void verifiedObservationTimeCannotInventFirstPersistenceOrResetItsOriginalRetention()
            throws Exception {
        String task = prepared();
        var timed = observation(task, 943010).put("clockAnomaly", true);
        assertEquals("TIME_REVIEW", ok(observe(task, timed)).path("outcome").asText());
        String item = timed.path("itemId").asText();
        String raw =
                db.queryForObject(
                        "SELECT CAST(id AS CHAR) FROM fams_inventory_observation WHERE task_id=?"
                                + " AND client_event_id=?",
                        String.class,
                        task,
                        timed.path("eventId").asText());
        chooseEvidence(task, item, raw, timed.path("rawObservedAt").asText());
        prepareAll(task);
        var verification = evidenceDecision(task, item, raw, "VERIFY_SELECTED");
        String base = "/api/v1/inventory-reviews/tasks/" + task + "/items/" + item;
        var failed =
                request(
                        admin,
                        "POST",
                        base + "/evidence-decisions",
                        verification.toString(),
                        key(),
                        taskVersion(task));
        assertEquals(409, failed.statusCode());
        assertEquals(
                "INVENTORY_REVIEW_RECOLLECTION_REQUIRED",
                JSON.readTree(failed.body()).path("code").asText());
        assertEquals(0, view(task).path("reviewedCount").asInt());
        assertEquals(
                0,
                count("SELECT COUNT(*) FROM fams_inventory_review_decision WHERE task_id=?", task));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_inventory_evidence_disposition WHERE task_id=?",
                        task));
    }

    @Test
    void finalAcceptanceFailureRollsBackProjectionHistoryAndReceiptBeforeTerminalOriginalReplay()
            throws Exception {
        String task = prepared();
        var source = view(task).path("items").get(1);
        String item = source.path("item").path("id").asText();
        chooseEvidence(task, item, source.path("candidate").path("id").asText(), null);
        String path = "/api/v1/inventory-reviews/tasks/" + task + "/items/" + item + "/acceptance";
        var input = evidenceAcceptance(task, item);
        long version = taskVersion(task);
        String originalKey = key();
        assertEquals(
                403,
                request(system, "POST", path, input.toString(), originalKey, version).statusCode());
        assertEquals(
                409,
                request(
                                admin,
                                "POST",
                                path,
                                input.deepCopy().put("assetVersion", 99).toString(),
                                key(),
                                version)
                        .statusCode());
        assertEquals(
                409,
                request(
                                admin,
                                "POST",
                                path,
                                input.deepCopy().put("recheckGeneration", 1).toString(),
                                key(),
                                version)
                        .statusCode());
        assertEquals(
                409,
                request(
                                admin,
                                "POST",
                                path,
                                input.deepCopy().put("contextDigest", "0".repeat(64)).toString(),
                                key(),
                                version)
                        .statusCode());
        db.execute(
                "CREATE TRIGGER owned_acceptance_failure BEFORE INSERT ON"
                        + " fams_inventory_selection_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET"
                        + " MESSAGE_TEXT='Owned final acceptance rollback probe'");
        try {
            assertEquals(
                    500,
                    request(admin, "POST", path, input.toString(), originalKey, version)
                            .statusCode());
        } finally {
            db.execute("DROP TRIGGER owned_acceptance_failure");
        }
        assertEquals(
                "SELECTED",
                selectionView(task, item).path("review").path("review_status").asText());
        assertEquals(
                1,
                count("SELECT COUNT(*) FROM fams_inventory_selection_event WHERE task_id=?", task));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_inventory_review_receipt WHERE task_id=? AND"
                                + " operation='ACCEPT_SELECTED'",
                        task));
        assertEquals(version, taskVersion(task));
        var accepted = ok(request(admin, "POST", path, input.toString(), originalKey, version));
        assertEquals("ACCEPTED", accepted.path("review").path("review_status").asText());
        assertEquals(
                accepted, ok(request(admin, "POST", path, input.toString(), originalKey, version)));
        assertEquals(
                409,
                request(
                                admin,
                                "POST",
                                path,
                                input.deepCopy().put("reason", "不同载荷不得借用原请求").toString(),
                                originalKey,
                                version)
                        .statusCode());
        ok(
                request(
                        admin,
                        "POST",
                        "/api/v1/inventory-tasks/" + task + "/terminate",
                        "{\"reason\":\"retained final acceptance receipt\"}",
                        key(),
                        taskVersion(task)));
        assertEquals(
                accepted, ok(request(admin, "POST", path, input.toString(), originalKey, version)));
        db.update(
                "DELETE FROM fams_account_role WHERE account_id=943001 AND"
                        + " role_code='ASSET_ADMIN'");
        assertEquals(
                403,
                request(admin, "POST", path, input.toString(), originalKey, version).statusCode());
    }

    @Test
    void priorDispositionReturnsItsActualAdministratorBeforeStaleTaskAndDimensionVersions()
            throws Exception {
        String task = differenceTask(false);
        var row = view(task).path("items").get(0);
        String item = row.path("item").path("id").asText();
        chooseEvidence(task, item, row.path("candidate").path("id").asText(), null);
        prepareAll(task);
        String observation =
                db.queryForObject(
                        "SELECT CAST(id AS CHAR) FROM fams_inventory_observation WHERE task_id=?"
                                + " AND outcome='CONFLICT_REVIEW'",
                        String.class,
                        task);
        String path =
                "/api/v1/inventory-reviews/tasks/"
                        + task
                        + "/items/"
                        + item
                        + "/evidence-decisions";
        var input = evidenceDecision(task, item, observation, "REJECT_EVIDENCE");
        long before = taskVersion(task);
        ok(request(other, "POST", path, input.toString(), key(), before));
        var lost = request(admin, "POST", path, input.toString(), key(), before);
        assertEquals(409, lost.statusCode());
        var fields = JSON.readTree(lost.body()).path("fieldErrors");
        assertEquals("review3", fields.path("processedBy").asText());
        assertEquals("REJECT_EVIDENCE", fields.path("method").asText());
        assertEquals("REJECTED", fields.path("result").asText());
        assertTrue(fields.path("readOnlyUrl").asText().endsWith("/evidence-dispositions"));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_inventory_evidence_disposition WHERE task_id=?",
                        task));
        assertEquals(
                2,
                count("SELECT COUNT(*) FROM fams_inventory_review_decision WHERE task_id=?", task));
    }

    private long taskVersion(String task) throws Exception {
        return view(task).path("task").path("version").asLong();
    }

    private JsonNode selectionView(String task, String item) throws Exception {
        return ok(
                request(
                        admin,
                        "GET",
                        "/api/v1/inventory-reviews/tasks/" + task + "/items/" + item + "/selection",
                        null,
                        null,
                        null));
    }

    private void chooseEvidence(String task, String item, String observation, String verifiedTime)
            throws Exception {
        var selection = selectionView(task, item);
        var input =
                JSON.createObjectNode()
                        .put("observationId", observation)
                        .put("recheckGeneration", selection.path("recheckGeneration").asLong())
                        .put("reviewVersion", selection.path("review").path("version").asLong())
                        .put("assetVersion", 0)
                        .put("contextDigest", selection.path("contextDigest").asText())
                        .put("reason", "明确核对后选择本轮证据")
                        .put("evidence", "现场独立签字原件及实物标识逐项核对记录");
        if (verifiedTime != null) {
            input.put("verifiedObservedAt", verifiedTime);
        }
        ok(
                request(
                        admin,
                        "POST",
                        "/api/v1/inventory-reviews/tasks/" + task + "/items/" + item + "/selection",
                        input.toString(),
                        key(),
                        selection.path("taskVersion").asLong()));
    }

    private void prepareAll(String task) throws Exception {
        var input = JSON.createObjectNode();
        var ids = input.putArray("observationIds");
        for (var row :
                db.queryForList(
                        "SELECT CAST(id AS CHAR) AS id FROM fams_inventory_observation WHERE"
                                + " task_id=? ORDER BY id",
                        task)) {
            ids.add(row.get("id").toString());
        }
        ok(
                request(
                        admin,
                        "POST",
                        "/api/v1/inventory-reviews/tasks/" + task + "/differences/preparation",
                        input.toString(),
                        key(),
                        taskVersion(task)));
    }

    private tools.jackson.databind.node.ObjectNode evidenceAcceptance(String task, String item)
            throws Exception {
        var selection = selectionView(task, item);
        return JSON.createObjectNode()
                .put("recheckGeneration", selection.path("recheckGeneration").asLong())
                .put("reviewVersion", selection.path("review").path("version").asLong())
                .put("assetVersion", 0)
                .put("contextDigest", selection.path("contextDigest").asText())
                .put("reason", "所有独立差异已经逐项核对")
                .put("evidence", "最终核对原始观察和每项决定及反证签字记录");
    }

    private tools.jackson.databind.node.ObjectNode evidenceDecision(
            String task, String item, String observation, String method) throws Exception {
        var input =
                evidenceAcceptance(task, item)
                        .put("observationId", observation)
                        .put("method", method);
        var subjects = input.putArray("subjects");
        for (var row :
                db.queryForList(
                        "SELECT dimension,version FROM fams_inventory_review_subject WHERE"
                                + " observation_id=? ORDER BY dimension",
                        observation)) {
            if (!method.equals("VERIFY_SELECTED")
                    || java.util.List.of("CONFLICT", "OLD_ASSIGNMENT", "TIME")
                            .contains(row.get("dimension"))) {
                subjects.addObject()
                        .put("dimension", row.get("dimension").toString())
                        .put("version", ((Number) row.get("version")).longValue());
            }
        }
        return input;
    }

    private JsonNode view(String task) throws Exception {
        return ok(
                request(admin, "GET", "/api/v1/inventory-reviews/tasks/" + task, null, null, null));
    }

    private int count(String sql, String task) {
        return task == null
                ? db.queryForObject(sql, Integer.class)
                : db.queryForObject(sql, Integer.class, task);
    }

    private String prepared() throws Exception {
        Instant start =
                Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String task =
                ok(request(
                                admin,
                                "POST",
                                "/api/v1/inventory-tasks",
                                JSON.writeValueAsString(
                                        java.util.Map.of(
                                                "name",
                                                "Review source",
                                                "range",
                                                java.util.Map.of("categoryId", "943000"),
                                                "startsAt",
                                                start,
                                                "dueAt",
                                                start.plusSeconds(86400))),
                                key(),
                                0L))
                        .path("id")
                        .asText();
        var preview =
                ok(
                        request(
                                admin,
                                "POST",
                                "/api/v1/inventory-tasks/preview",
                                "{\"categoryId\":\"943000\"}",
                                key(),
                                0L));
        ok(
                request(
                        admin,
                        "POST",
                        "/api/v1/inventory-tasks/" + task + "/publication",
                        JSON.writeValueAsString(
                                java.util.Map.of(
                                        "expectedCount",
                                        2,
                                        "previewDigest",
                                        preview.path("digest").asText())),
                        key(),
                        0L));
        var items =
                db.queryForList(
                        "SELECT CAST(id AS CHAR) AS id FROM fams_inventory_item WHERE task_id=?"
                                + " ORDER BY id",
                        task);
        String assignment =
                JSON.writeValueAsString(
                        java.util.Map.of(
                                "itemIds",
                                items.stream().map(row -> row.get("id")).toList(),
                                "executorAccountId",
                                "943002",
                                "recheck",
                                false,
                                "reason",
                                "initial assignment"));
        ok(
                request(
                        admin,
                        "POST",
                        "/api/v1/inventory-tasks/" + task + "/assignments",
                        assignment,
                        key(),
                        1L));
        ok(request(admin, "POST", "/api/v1/inventory-tasks/" + task + "/start", null, key(), 2L));
        assertEquals(
                "ACCEPTED", ok(observe(task, observation(task, 943010))).path("outcome").asText());
        assertEquals(
                "ACCEPTED", ok(observe(task, observation(task, 943011))).path("outcome").asText());
        return task;
    }

    private tools.jackson.databind.node.ObjectNode observation(String task, long asset)
            throws Exception {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String device = key();
        var calibration =
                ok(
                        request(
                                employee,
                                "POST",
                                "/api/v1/inventory-observations/calibrations",
                                JSON.writeValueAsString(
                                        java.util.Map.of("deviceId", device, "clientTime", now)),
                                null,
                                null));
        long item =
                db.queryForObject(
                        "SELECT id FROM fams_inventory_item WHERE task_id=? AND asset_id=?",
                        Long.class,
                        task,
                        asset);
        var value =
                JSON.createObjectNode()
                        .put("eventId", key())
                        .put("itemId", Long.toString(item))
                        .put("assignmentVersion", 1)
                        .put("recheckGeneration", 0)
                        .put("assetVersion", 0)
                        .put("deviceId", device)
                        .put("source", "SCAN")
                        .put("assetCode", "INV-ASSET-" + asset)
                        .put("rawScannedAt", now.toString())
                        .put("rawObservedAt", now.toString())
                        .put("firstPersistedAt", now.toString())
                        .put("expiresAt", now.plusSeconds(72 * 3600).toString())
                        .put("clockAnomaly", false)
                        .put("calibrationId", calibration.path("calibrationId").asText());
        value.putObject("actual")
                .put("locationId", "9007199254740993")
                .put("custodyType", "STORAGE")
                .put("condition", "GOOD");
        value.putArray("photoIds");
        return value;
    }

    private HttpResponse<String> observe(String task, JsonNode input) throws Exception {
        return request(
                employee,
                "POST",
                "/api/v1/inventory-observations/tasks/" + task + "/events",
                input.toString(),
                input.path("eventId").asText(),
                input.path("assignmentVersion").asLong());
    }

    private String batch(JsonNode view, int... indexes) {
        var body = JSON.createObjectNode().put("reason", "normal checked");
        var items = body.putArray("items");
        for (int index : indexes) {
            var row = view.path("items").get(index);
            items.addObject()
                    .put("itemId", row.path("item").path("id").asText())
                    .put("recheckGeneration", row.path("item").path("recheck_generation").asLong())
                    .put("reviewVersion", row.path("review").path("version").asLong())
                    .put("assetVersion", row.path("current").path("version").asLong());
        }
        return body.toString();
    }

    private HttpResponse<String> review(
            Session actor, String task, long version, String key, String body) throws Exception {
        return request(
                actor,
                "POST",
                "/api/v1/inventory-reviews/tasks/" + task + "/normal-acceptance",
                body,
                key,
                version);
    }

    private void transfer(long asset, long version, long location) throws Exception {
        var target =
                java.util.Map.of(
                        "custodyType",
                        "STORAGE",
                        "warehouseId",
                        "943000",
                        "locationId",
                        Long.toString(location),
                        "referenceVersions",
                        java.util.Map.of("warehouse", 0, "location", 0));
        var draft =
                java.util.Map.of(
                        "reason",
                        "real stock movement",
                        "items",
                        java.util.List.of(
                                java.util.Map.of(
                                        "assetId",
                                        Long.toString(asset),
                                        "assetVersion",
                                        version,
                                        "target",
                                        target)));
        var row =
                ok(
                        request(
                                admin,
                                "POST",
                                "/api/v1/transfers",
                                JSON.writeValueAsString(draft),
                                key(),
                                null));
        row = transferAction(admin, row, "/submit", null);
        row =
                transferAction(
                        admin,
                        row,
                        "/decision",
                        "{\"approved\":true,\"reason\":\"approved real move\"}");
        row = transferAction(warehouse, row, "/claim", null);
        var item = row.path("items").get(0);
        String input =
                JSON.writeValueAsString(
                        java.util.Map.of(
                                "itemVersion",
                                item.path("version").asLong(),
                                "observedCode",
                                "INV-ASSET-" + asset,
                                "destination",
                                "checked bin " + location,
                                "evidence",
                                "actual stock checked"));
        var result =
                transferAction(
                        warehouse, row, "/items/" + item.path("id").asText() + "/verify", input);
        assertEquals("COMPLETED", result.path("requestStatus").asText());
    }

    private JsonNode transferAction(Session actor, JsonNode row, String suffix, String body)
            throws Exception {
        return ok(
                request(
                        actor,
                        "POST",
                        "/api/v1/transfers/" + row.path("id").asText() + suffix,
                        body,
                        key(),
                        row.path("version").asLong()));
    }

    private HttpResponse<String> request(
            Session actor, String method, String path, String body, String key, Long version)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Cookie", actor.cookie())
                        .header("X-CSRF-Token", actor.csrf());
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (version != null) {
            request.header("If-Match-Version", version.toString());
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return http.send(
                request.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> mini(
            String token, String path, String body, String key, long version) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("X-FAMS-Mini-Session", token)
                        .header("Idempotency-Key", key)
                        .header("If-Match-Version", Long.toString(version))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private Session login(int number) throws Exception {
        var response =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:" + port + "/api/v1/auth/login"))
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                JSON.writeValueAsString(
                                                        java.util.Map.of(
                                                                "username",
                                                                "review" + number,
                                                                "password",
                                                                PASSWORD))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        var data = ok(response);
        return new Session(
                response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                data.path("csrfToken").asText());
    }
}
