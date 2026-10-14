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
@EnabledIfSystemProperty(named = "d6.16.inventory.runtime", matches = "true")
class InventoryTaskHttpRuntimeCase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcTemplate db;
    @Autowired InventoryService service;
    @Autowired MiniSessionService miniSessions;
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
    void publishedSnapshotAssignmentIsolationReplayAndCurrentAssetIndependence() throws Exception {
        JsonNode task = create(Instant.now().plusSeconds(3600));
        String id = task.path("id").asText();
        JsonNode preview = ok(call(admin, "/preview", "{\"categoryId\":933000}", key(), 0));
        assertEquals(2, preview.path("count").asInt());
        String publish =
                "{\"expectedCount\":2,\"previewDigest\":\""
                        + preview.path("digest").asText()
                        + "\"}";
        String identity = key();
        assertEquals(
                "READY",
                ok(call(admin, "/" + id + "/publication", publish, identity, 0))
                        .path("task_status")
                        .asText());
        assertEquals(
                "READY",
                ok(call(admin, "/" + id + "/publication", publish, identity, 0))
                        .path("task_status")
                        .asText());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_task_event WHERE task_id=? AND"
                                + " action='INVENTORY_PUBLISHED'",
                        Integer.class,
                        id));
        var blocked =
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class,
                        () -> initialAssets.assertReversible(933010, UUID.randomUUID()));
        assertEquals("ASSET_IMPORT_ROLLBACK_DOWNSTREAM", blocked.getMessage());
        JsonNode detail = ok(call(admin, "/" + id, null, null, 0));
        String item = detail.path("items").get(0).path("id").asText();
        String frozen =
                db.queryForObject(
                        "SELECT CAST(snapshot_json AS CHAR) FROM fams_inventory_item WHERE id=?",
                        String.class,
                        item);
        db.update(
                "UPDATE fams_asset SET name='Changed after publication',version=version+1 WHERE"
                        + " id=933010");
        assertEquals(
                frozen,
                db.queryForObject(
                        "SELECT CAST(snapshot_json AS CHAR) FROM fams_inventory_item WHERE id=?",
                        String.class,
                        item));
        String assignment =
                "{\"itemIds\":["
                        + item
                        + "],\"executorAccountId\":933002,\"recheck\":false,\"reason\":\"By"
                        + " location\"}";
        assertEquals(
                2,
                ok(call(admin, "/" + id + "/assignments", assignment, key(), 1))
                        .path("version")
                        .asInt());
        JsonNode own = ok(call(employee, "/" + id, null, null, 0));
        assertEquals(1, own.path("total").asInt());
        assertTrue(own.path("task").path("range_json").isMissingNode());
        assertEquals(404, call(other, "/" + id, null, null, 0).statusCode());
        assertEquals(403, call(employee, "/executors", null, null, 0).statusCode());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id IN"
                                + " (933010,933011)",
                        Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset WHERE id IN (933010,933011) AND"
                                + " last_inventory_business_date IS NOT NULL",
                        Integer.class));
        assertEquals(
                "IN_PROGRESS",
                ok(call(admin, "/" + id + "/start", null, key(), 2)).path("task_status").asText());
        assertEquals(
                "CANCELLED",
                ok(call(admin, "/" + id + "/cancel", "{\"reason\":\"No observations\"}", key(), 3))
                        .path("task_status")
                        .asText());
        assertEquals(409, call(admin, "/" + id + "/start", null, key(), 4).statusCode());
        var source = ok(call(admin, "/" + id, null, null, 0)).path("task");
        var copyInput =
                JSON.createObjectNode()
                        .put("name", "Copied range")
                        .put("startsAt", "2026-12-01T00:00:00Z")
                        .put("dueAt", "2026-12-02T00:00:00Z");
        copyInput.set("range", source.path("range_json"));
        var copied = ok(call(admin, "/" + id + "/copy", copyInput.toString(), key(), 4));
        assertEquals("DRAFT", copied.path("task_status").asText());
        assertEquals(id, copied.path("copied_from_task_id").asText());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_item WHERE task_id=?",
                        Integer.class,
                        copied.path("id").asText()));
    }

    @Test
    void previewDriftRollsBackEntirePublicationAndReassignmentKeepsHistory() throws Exception {
        String id = create(Instant.now().plusSeconds(3600)).path("id").asText();
        JsonNode preview = ok(call(admin, "/preview", "{\"categoryId\":933000}", key(), 0));
        db.update("UPDATE fams_asset SET version=version+1 WHERE id=933010");
        String stale =
                "{\"expectedCount\":2,\"previewDigest\":\""
                        + preview.path("digest").asText()
                        + "\"}";
        assertEquals(409, call(admin, "/" + id + "/publication", stale, key(), 0).statusCode());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_item WHERE task_id=?",
                        Integer.class,
                        id));
        assertEquals(
                "DRAFT",
                db.queryForObject(
                        "SELECT task_status FROM fams_inventory_task WHERE id=?",
                        String.class,
                        id));
        publish(id);
        String item =
                ok(call(admin, "/" + id, null, null, 0)).path("items").get(0).path("id").asText();
        String body =
                "{\"itemIds\":["
                        + item
                        + "],\"executorAccountId\":933002,\"recheck\":false,\"reason\":\"Initial"
                        + " assignment\"}";
        ok(call(admin, "/" + id + "/assignments", body, key(), 1));
        ok(
                call(
                        admin,
                        "/" + id + "/assignments",
                        body.replace("933002", "933003")
                                .replace("Initial assignment", "Reassign unscanned"),
                        key(),
                        2));
        assertEquals(404, call(employee, "/" + id, null, null, 0).statusCode());
        assertEquals(1, ok(call(other, "/" + id, null, null, 0)).path("total").asInt());
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_assignment WHERE item_id=?",
                        Integer.class,
                        item));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_participant WHERE task_id=?",
                        Integer.class,
                        id));
        assertEquals(409, call(admin, "/" + id + "/assignments", body, key(), 1).statusCode());
    }

    @Test
    void dueStateRecoversExactlyOnceAndResultsRequireTermination() throws Exception {
        String id = create(Instant.now().minusSeconds(60)).path("id").asText();
        publish(id);
        assertTrue(service.startDue());
        assertEquals(false, service.startDue());
        assertEquals(
                "IN_PROGRESS",
                db.queryForObject(
                        "SELECT task_status FROM fams_inventory_task WHERE id=?",
                        String.class,
                        id));
        // D6-17 owns observations: seed only the durable result boundary to prove lifecycle
        // protection.
        db.update("UPDATE fams_inventory_item SET result_count=1 WHERE task_id=?", id);
        assertEquals(
                409,
                call(
                                admin,
                                "/" + id + "/cancel",
                                "{\"reason\":\"Must retain observations\"}",
                                key(),
                                2)
                        .statusCode());
        assertEquals(
                "TERMINATED",
                ok(call(
                                admin,
                                "/" + id + "/terminate",
                                "{\"reason\":\"Retain all evidence\"}",
                                key(),
                                2))
                        .path("task_status")
                        .asText());
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT SUM(result_count) FROM fams_inventory_item WHERE task_id=?",
                        Integer.class,
                        id));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_task_event WHERE task_id=? AND"
                                + " action='INVENTORY_STARTED'",
                        Integer.class,
                        id));
    }

    @Test
    void revokedCurrentAdministratorCannotReplayOrCreateAndDifferentPayloadNeverDuplicates()
            throws Exception {
        String identity = key();
        String body =
                "{\"name\":\"Credential"
                    + " boundary\",\"range\":{\"categoryId\":933000},\"startsAt\":\"2026-12-01T00:00:00Z\",\"dueAt\":\"2026-12-02T00:00:00Z\"}";
        var created = ok(call(admin, "", body, identity, 0));
        assertEquals(403, call(employee, "", body, key(), 0).statusCode());
        assertEquals(
                409,
                call(admin, "", body.replace("Credential boundary", "Changed payload"), identity, 0)
                        .statusCode());
        db.update(
                "UPDATE fams_system_account SET authorization_version=authorization_version+1 WHERE"
                        + " id=933001");
        assertEquals(401, call(admin, "", body, identity, 0).statusCode());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_task WHERE creator_account_id=933001",
                        Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT version FROM fams_inventory_task WHERE id=?",
                        Integer.class,
                        created.path("id").asText()));
    }

    @Test
    void simultaneousStartsCommitOnlyOneTransition() throws Exception {
        String id = create(Instant.now().plusSeconds(3600)).path("id").asText();
        publish(id);
        var latch = new java.util.concurrent.CountDownLatch(1);
        var replies = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Integer>>();
        for (int i = 0; i < 2; i++) {
            replies.add(
                    java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    latch.await();
                                    return call(admin, "/" + id + "/start", null, key(), 1)
                                            .statusCode();
                                } catch (Exception error) {
                                    throw new IllegalStateException(error);
                                }
                            }));
        }
        latch.countDown();
        var statuses = new java.util.ArrayList<Integer>();
        for (var reply : replies) {
            statuses.add(reply.get(30, java.util.concurrent.TimeUnit.SECONDS));
        }
        statuses.sort(Integer::compareTo);
        assertEquals(java.util.List.of(200, 409), statuses);
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_task_event WHERE task_id=? AND"
                                + " action='INVENTORY_STARTED'",
                        Integer.class,
                        id));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT version FROM fams_inventory_task WHERE id=?", Integer.class, id));
    }

    @Test
    void nativeOpaqueSessionReadsOnlyAssignedSnapshot() throws Exception {
        String id = create(Instant.now().plusSeconds(3600)).path("id").asText();
        publish(id);
        String item =
                ok(call(admin, "/" + id, null, null, 0)).path("items").get(0).path("id").asText();
        String assignment =
                "{\"itemIds\":["
                        + item
                        + "],\"executorAccountId\":933002,\"recheck\":false,\"reason\":\"Native"
                        + " task authorization\"}";
        ok(call(admin, "/" + id + "/assignments", assignment, key(), 1));
        String code = "inventory-native-933002";
        db.update(
                "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES"
                        + " (933002,?,NOW(3))",
                miniSessions.identityHash(
                        new MiniWechatIdentityPort.Identity("requisition-test-app", code)));
        var script =
                java.nio.file.Path.of(System.getProperty("user.dir"))
                        .getParent()
                        .resolve("apps/miniprogram/test/runtime-inventory-http.mjs");
        var builder = new ProcessBuilder("rtk", "proxy", "node", script.toString());
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_INVENTORY_TASK", id);
        builder.environment().put("FAMS_TEST_INVENTORY_CODE", code);
        var log = java.nio.file.Files.createTempFile("fams-inventory-native-", ".log");
        try {
            var process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Native inventory HTTP timed out");
            }
            assertEquals(0, process.exitValue(), java.nio.file.Files.readString(log));
        } finally {
            java.nio.file.Files.deleteIfExists(log);
        }
    }

    @Test
    void manualTaskOutsideResultPreventsCancellationWithoutPretendingCollectionExists()
            throws Exception {
        String id = create(Instant.now().plusSeconds(3600)).path("id").asText();
        publish(id);
        // D6-17 will own this count. No item is marked observed by this boundary fixture.
        db.update("UPDATE fams_inventory_task SET captured_result_count=1 WHERE id=?", id);
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT SUM(result_count) FROM fams_inventory_item WHERE task_id=?",
                        Integer.class,
                        id));
        assertEquals(
                409,
                call(
                                admin,
                                "/" + id + "/cancel",
                                "{\"reason\":\"Manual finding retained\"}",
                                key(),
                                1)
                        .statusCode());
        assertEquals(
                "TERMINATED",
                ok(call(
                                admin,
                                "/" + id + "/terminate",
                                "{\"reason\":\"Retain manual finding\"}",
                                key(),
                                1))
                        .path("task_status")
                        .asText());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT captured_result_count FROM fams_inventory_task WHERE id=?",
                        Integer.class,
                        id));
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
