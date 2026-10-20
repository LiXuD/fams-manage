package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
@EnabledIfSystemProperty(named = "d6.18.inventory.sync.runtime", matches = "true")
class InventorySynchronizationHttpRuntimeCase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    @Autowired JdbcTemplate db;
    @Autowired MiniSessionService sessions;

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private String token;
    private String cookie;
    private String csrf;
    private final String device = "11111111-1111-4111-8111-111111111111";

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
                    + " VALUES (945000,'SYNC','SYNC','Sync organization','ACTIVE',NOW(3),NOW(3))");
        for (int i = 1; i <= 3; i++) {
            db.update(
                    "INSERT INTO"
                        + " fams_employee(id,employee_no,employee_no_normalized,name,organization_id,status,created_at,updated_at)"
                        + " VALUES (?,?,?, ?,945000,'ACTIVE',NOW(3),NOW(3))",
                    945000 + i,
                    "sync" + i,
                    "SYNC" + i,
                    "Sync " + i);
            db.update(
                    "INSERT INTO"
                        + " fams_system_account(id,employee_id,username,username_normalized,password_hash,status,initial_password_required,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,'ENABLED',FALSE,NOW(3),NOW(3))",
                    945000 + i,
                    945000 + i,
                    "sync" + i,
                    "SYNC" + i,
                    new BCryptPasswordEncoder(4).encode(PASSWORD));
            db.update(
                    "INSERT INTO fams_account_role(account_id,role_code,granted_at) VALUES"
                            + " (?,?,NOW(3))",
                    945000 + i,
                    i == 1 ? "ASSET_ADMIN" : "EMPLOYEE");
        }
        db.update(
                "INSERT INTO"
                    + " fams_inventory_task(id,name,task_status,range_json,starts_at,due_at,creator_account_id,created_at,updated_at,published_at,assignment_version)"
                    + " VALUES (945000,'Sync task','IN_PROGRESS','{}',DATE_SUB(NOW(3),INTERVAL 1"
                    + " HOUR),DATE_ADD(NOW(3),INTERVAL 1"
                    + " DAY),945001,NOW(3),NOW(3),DATE_SUB(NOW(3),INTERVAL 1 HOUR),1)");
        db.update(
                "INSERT INTO fams_inventory_participant(task_id,account_id,first_assigned_at)"
                        + " VALUES (945000,945002,NOW(3)),(945000,945003,NOW(3))");
        var identity = new MiniWechatIdentityPort.Identity("sync-app", "sync-executor");
        db.update(
                "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES"
                        + " (945002,?,NOW(3))",
                sessions.identityHash(identity));
        token = sessions.start(identity, UUID.randomUUID()).sessionToken();
        var login =
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
                                                                "sync1",
                                                                "password",
                                                                PASSWORD))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode(), login.body());
        cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        csrf = JSON.readTree(login.body()).path("csrfToken").asText();
    }

    @AfterEach
    void cleanup() {
        for (String table :
                java.util.List.of(
                        "fams_inventory_sync_receipt",
                        "fams_inventory_sync_declaration",
                        "fams_inventory_task_event",
                        "fams_inventory_observation",
                        "fams_inventory_participant")) {
            db.update("DELETE FROM " + table + " WHERE task_id=945000");
        }
        db.update("DELETE FROM fams_inventory_task WHERE id=945000");
        db.update(
                "DELETE FROM fams_inventory_clock_calibration WHERE actor_account_id BETWEEN 945001"
                        + " AND 945003");
        db.update("DELETE FROM fams_mini_session WHERE account_id BETWEEN 945001 AND 945003");
        db.update("DELETE FROM fams_mini_binding WHERE account_id BETWEEN 945001 AND 945003");
        db.update("DELETE FROM fams_security_event WHERE account_id BETWEEN 945001 AND 945003");
        db.update("DELETE FROM fams_audit_event WHERE actor_account_id BETWEEN 945001 AND 945003");
        db.update("DELETE FROM fams_account_role WHERE account_id BETWEEN 945001 AND 945003");
        db.update("DELETE FROM fams_system_account WHERE id BETWEEN 945001 AND 945003");
        db.update("DELETE FROM fams_employee WHERE id BETWEEN 945001 AND 945003");
        db.update("DELETE FROM fams_organization WHERE id=945000");
    }

    @Test
    void declarationOriginalReceiptIsImmutableButNewFactsInvalidateCurrentStatus()
            throws Exception {
        var body = body();
        String key = UUID.randomUUID().toString();
        var first = call("POST", "/declarations", body, key, 0, true);
        assertEquals(200, first.statusCode(), first.body());
        assertEquals(
                JSON.readTree(first.body()),
                JSON.readTree(call("POST", "/declarations", body, key, 0, true).body()));
        assertEquals("DECLARED", own().path("device").path("reason").asText());
        assertEquals(
                2,
                JSON.readTree(call("GET", "", null, null, 0, false).body()).path("total").asInt());
        body.put("unresolvedCount", 1);
        assertEquals(409, call("POST", "/declarations", body, key, 0, true).statusCode());
        body.put("unresolvedCount", 0);
        long binding =
                db.queryForObject(
                        "SELECT id FROM fams_mini_binding WHERE account_id=945002 AND revoked_at IS"
                                + " NULL",
                        Long.class);
        db.update(
                "INSERT INTO"
                    + " fams_inventory_observation(task_id,client_event_id,actor_account_id,binding_id,device_id,assignment_version,recheck_generation,source,"
                    + "raw_observed_at,first_persisted_at,client_expires_at,received_at,facts_json,payload_sha256,fact_sha256,outcome,receipt_json)"
                    + " VALUES"
                    + " (945000,?,945002,?,?,1,0,'MANUAL',NOW(3),NOW(3),DATE_ADD(NOW(3),INTERVAL 72"
                    + " HOUR),NOW(3),'{}',SHA2('sync-observation',256),SHA2('sync-fact',256),'FINDING_REVIEW','{}')",
                UUID.randomUUID().toString(),
                binding,
                device);
        assertEquals("NEW_OBSERVATION", own().path("device").path("reason").asText());
        assertEquals(
                JSON.readTree(first.body()),
                JSON.readTree(call("POST", "/declarations", body, key, 0, true).body()));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_sync_declaration WHERE task_id=945000",
                        Integer.class));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT version FROM fams_inventory_task WHERE id=945000", Integer.class));
    }

    @Test
    void assignmentRevocationAndBindingChangesRemainVisibleAndDenyStaleReplay() throws Exception {
        var body = body();
        String key = UUID.randomUUID().toString();
        assertEquals(200, call("POST", "/declarations", body, key, 0, true).statusCode());
        db.update(
                "UPDATE fams_inventory_task SET assignment_version=2,version=version+1 WHERE"
                        + " id=945000");
        assertEquals("ASSIGNMENT_CHANGED", own().path("device").path("reason").asText());
        db.update(
                "UPDATE fams_inventory_participant SET revoked_at=NOW(3) WHERE task_id=945000 AND"
                        + " account_id=945002");
        assertEquals(403, call("POST", "/declarations", body, key, 0, true).statusCode());
        var status = JSON.readTree(call("GET", "", null, null, 0, false).body());
        assertEquals("PARTICIPATION_REVOKED", status.path("items").get(0).path("reason").asText());
        db.update(
                "UPDATE fams_inventory_participant SET revoked_at=NULL WHERE task_id=945000 AND"
                        + " account_id=945002");
        db.update(
                "UPDATE fams_mini_binding SET revoked_at=NOW(3),version=version+1 WHERE"
                        + " account_id=945002 AND revoked_at IS NULL");
        assertEquals(401, call("POST", "/declarations", body, key, 0, true).statusCode());
        status = JSON.readTree(call("GET", "", null, null, 0, false).body());
        assertEquals("BINDING_MISSING", status.path("items").get(0).path("reason").asText());
    }

    @Test
    void validationPermissionsAndMissingLocalEvidenceCannotBeReportedAsComplete() throws Exception {
        assertEquals(403, call("GET", "", null, null, 0, true).statusCode());
        var body = body();
        body.put("pendingEmpty", false);
        assertEquals(
                400,
                call("POST", "/declarations", body, UUID.randomUUID().toString(), 0, true)
                        .statusCode());
        body.put("pendingEmpty", true);
        body.put("unresolvedCount", 2);
        assertEquals(
                403,
                call("POST", "/declarations", body, UUID.randomUUID().toString(), 0, false)
                        .statusCode());
        assertEquals(
                200,
                call("POST", "/declarations", body, UUID.randomUUID().toString(), 0, true)
                        .statusCode());
        assertEquals("LOCAL_EVIDENCE_MISSING", own().path("device").path("reason").asText());
        var status = JSON.readTree(call("GET", "", null, null, 0, false).body());
        assertTrue(!status.path("items").get(0).path("current").asBoolean());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT captured_result_count FROM fams_inventory_task WHERE id=945000",
                        Integer.class));
    }

    @Test
    void nativeHandlerUsesDurableQueueAndRecoversActualCommittedReply() throws Exception {
        var script =
                java.nio.file.Path.of(System.getProperty("user.dir"))
                        .getParent()
                        .resolve(
                                "apps/miniprogram/test/runtime-inventory-synchronization-http.mjs");
        var builder = new ProcessBuilder("rtk", "proxy", "node", script.toString());
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_INVENTORY_TASK", "945000");
        builder.environment().put("FAMS_TEST_SYNC_KEY", UUID.randomUUID().toString());
        builder.environment()
                .put(
                        "FAMS_TEST_MINI_GRANT",
                        JSON.writeValueAsString(
                                java.util.Map.of(
                                        "sessionToken",
                                        token,
                                        "accountId",
                                        "945002",
                                        "bindingId",
                                        db.queryForObject(
                                                "SELECT CAST(id AS CHAR) FROM fams_mini_binding"
                                                    + " WHERE account_id=945002 AND revoked_at IS"
                                                    + " NULL",
                                                String.class))));
        var process = builder.redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
            String output =
                    new String(
                            process.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            var proof = JSON.readTree(output.strip());
            assertTrue(proof.path("nativeSynchronizationOriginalReplyRecovered").asBoolean());
            assertTrue(proof.path("originalKeyPayloadVersionRetained").asBoolean());
            assertTrue(proof.path("durableQueueRead").asBoolean());
            System.out.println("D6-18B1 native synchronization runtime proof: " + proof);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_sync_declaration WHERE task_id=945000",
                        Integer.class));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_sync_receipt WHERE task_id=945000",
                        Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT captured_result_count FROM fams_inventory_task WHERE id=945000",
                        Integer.class));
    }

    @Test
    void failedEventWriteRollsBackDeclarationTaskVersionAndOriginalReceipt() throws Exception {
        db.execute(
                "CREATE TRIGGER d618_sync_event_failure BEFORE INSERT ON fams_inventory_task_event"
                        + " FOR EACH ROW BEGIN IF NEW.task_id=945000 AND"
                        + " NEW.action='SYNCHRONIZATION_DECLARED' THEN SIGNAL SQLSTATE '45000' SET"
                        + " MESSAGE_TEXT='owned synchronization rollback'; END IF; END");
        try {
            assertEquals(
                    500,
                    call("POST", "/declarations", body(), UUID.randomUUID().toString(), 0, true)
                            .statusCode());
        } finally {
            db.execute("DROP TRIGGER d618_sync_event_failure");
        }
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_sync_declaration WHERE task_id=945000",
                        Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_sync_receipt WHERE task_id=945000",
                        Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT version FROM fams_inventory_task WHERE id=945000", Integer.class));
    }

    @Test
    void concurrentDeclarationsCommitOnlyOneTaskVersion() throws Exception {
        var first =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return call(
                                                "POST",
                                                "/declarations",
                                                body(),
                                                UUID.randomUUID().toString(),
                                                0,
                                                true)
                                        .statusCode();
                            } catch (Exception failure) {
                                throw new IllegalStateException(failure);
                            }
                        });
        var second =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return call(
                                                "POST",
                                                "/declarations",
                                                body(),
                                                UUID.randomUUID().toString(),
                                                0,
                                                true)
                                        .statusCode();
                            } catch (Exception failure) {
                                throw new IllegalStateException(failure);
                            }
                        });
        assertEquals(
                java.util.List.of(200, 409),
                java.util.stream.Stream.of(first.get(), second.get()).sorted().toList());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_sync_declaration WHERE task_id=945000",
                        Integer.class));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT version FROM fams_inventory_task WHERE id=945000", Integer.class));
    }

    @Test
    void actualCalibratedUploadInvalidatesDeclarationAndDuplicateReceiptDoesNot() throws Exception {
        db.update(
                "INSERT INTO"
                    + " fams_asset_category(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (945000,'SYNC','SYNC','Sync category','ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_warehouse(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (945000,'SYNC','SYNC','Sync warehouse','ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_location(id,code,code_normalized,name,location_type,warehouse_id,status,created_at,updated_at)"
                    + " VALUES (945000,'SYNC','SYNC','Sync"
                    + " room','BIN',945000,'ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_business_parameter_version(id,version,status,effective_at,actor_account_id,actor_username,snapshot_json,created_at)"
                    + " VALUES"
                    + " (945000,945000,'ACTIVE',NOW(3),945001,'sync1',JSON_OBJECT('timezone','Asia/Shanghai'),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_asset(id,asset_code,name,category_id,lifecycle_status,exception_status,card_stage,custody_type,storage_warehouse_id,current_location_id,created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by)"
                    + " VALUES (945010,'SYNC-A','Sync"
                    + " device',945000,'IN_STOCK','NORMAL','ACTIVE','STORAGE',945000,945000,CURRENT_DATE,945000,945001,'sync1',NOW(3),NOW(3),945001,945001)");
        db.update(
                "INSERT INTO"
                    + " fams_inventory_item(id,task_id,asset_id,asset_version,snapshot_json,executor_account_id,assignment_version)"
                    + " SELECT 945000,945000,asset_id,asset_version,snapshot_json,945002,1 FROM"
                    + " fams_asset_inventory_source_v1 WHERE asset_id=945010");
        try {
            var original = body();
            String key = UUID.randomUUID().toString();
            assertEquals(200, call("POST", "/declarations", original, key, 0, true).statusCode());
            var before = db.queryForList("SELECT * FROM fams_asset WHERE id=945010");
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            var calibration =
                    miniObservationRequest(
                            "/calibrations",
                            java.util.Map.of("deviceId", device, "clientTime", now.toString()),
                            null);
            assertEquals(200, calibration.statusCode(), calibration.body());
            var event = JSON.createObjectNode();
            String eventId = UUID.randomUUID().toString();
            event.put("eventId", eventId);
            event.put("itemId", "945000");
            event.put("assignmentVersion", 1);
            event.put("recheckGeneration", 0);
            event.put("assetVersion", 0);
            event.put("deviceId", device);
            event.put("source", "SCAN");
            event.put("assetCode", "SYNC-A");
            event.putObject("actual")
                    .put("locationId", "945000")
                    .put("custodyType", "STORAGE")
                    .put("condition", "GOOD");
            for (String field :
                    java.util.List.of("rawScannedAt", "rawObservedAt", "firstPersistedAt")) {
                event.put(field, now.toString());
            }
            event.put("expiresAt", now.plusSeconds(72 * 3600).toString());
            event.put(
                    "calibrationId",
                    JSON.readTree(calibration.body()).path("calibrationId").asText());
            event.put("clockAnomaly", false);
            event.putArray("photoIds");
            var uploaded = miniObservationRequest("/tasks/945000/events", event, eventId);
            assertEquals(200, uploaded.statusCode(), uploaded.body());
            assertEquals("ACCEPTED", JSON.readTree(uploaded.body()).path("outcome").asText());
            var changed = own();
            assertEquals("NEW_OBSERVATION", changed.path("device").path("reason").asText());
            assertTrue(changed.path("observationWatermark").isString());
            var fresh = body();
            fresh.put("observationWatermark", changed.path("observationWatermark").asText());
            assertEquals(
                    200,
                    call(
                                    "POST",
                                    "/declarations",
                                    fresh,
                                    UUID.randomUUID().toString(),
                                    changed.path("taskVersion").asLong(),
                                    true)
                            .statusCode());
            assertEquals("DECLARED", own().path("device").path("reason").asText());
            assertEquals(
                    200,
                    miniObservationRequest("/tasks/945000/events", event, eventId).statusCode());
            assertEquals("DECLARED", own().path("device").path("reason").asText());
            assertEquals(before, db.queryForList("SELECT * FROM fams_asset WHERE id=945010"));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT captured_result_count FROM fams_inventory_task WHERE id=945000",
                            Integer.class));
        } finally {
            db.update("DELETE FROM fams_inventory_round_result WHERE item_id=945000");
            db.update("DELETE FROM fams_inventory_observation WHERE task_id=945000");
            db.update("DELETE FROM fams_inventory_item WHERE id=945000");
            db.update("DELETE FROM fams_asset WHERE id=945010");
            db.update("DELETE FROM fams_business_parameter_version WHERE id=945000");
            db.update("DELETE FROM fams_location WHERE id=945000");
            db.update("DELETE FROM fams_warehouse WHERE id=945000");
            db.update("DELETE FROM fams_asset_category WHERE id=945000");
        }
    }

    private HttpResponse<String> miniObservationRequest(String suffix, Object body, String key)
            throws Exception {
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/api/v1/mini/inventory-observations"
                                                + suffix))
                        .header("X-FAMS-Mini-Session", token)
                        .header("Content-Type", "application/json");
        if (key != null) {
            request.header("Idempotency-Key", key).header("If-Match-Version", "1");
        }
        return http.send(
                request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void synchronizationOriginalReceiptSurvivesCommittingJvmForcedDeathAndFreshJvm()
            throws Exception {
        String body = JSON.writeValueAsString(body());
        String originalKey = UUID.randomUUID().toString();
        long version = 0;
        var state = java.nio.file.Files.createTempDirectory("fams-synchronization-jvm-");
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
                                "com.fams.auth.InventorySynchronizationProcess");
                builder.environment().put("FAMS_TEST_SYNC_TASK", "945000");
                builder.environment().put("FAMS_TEST_SYNC_TOKEN", token);
                builder.environment().put("FAMS_TEST_SYNC_KEY", originalKey);
                builder.environment().put("FAMS_TEST_SYNC_VERSION", Long.toString(version));
                builder.environment().put("FAMS_TEST_SYNC_BODY", body);
                builder.environment().put("FAMS_TEST_SYNC_STAGE", stage);
                var proofPath = state.resolve(stage + ".json");
                var logPath = state.resolve(stage + ".log");
                builder.environment().put("FAMS_TEST_SYNC_PROOF", proofPath.toString());
                if (first != null) {
                    builder.environment()
                            .put("FAMS_TEST_SYNC_ORIGINAL", first.path("response").toString());
                }
                process =
                        builder.redirectErrorStream(true).redirectOutput(logPath.toFile()).start();
                long deadline =
                        System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
                while (!java.nio.file.Files.exists(proofPath)) {
                    assertTrue(process.isAlive(), java.nio.file.Files.readString(logPath));
                    assertTrue(
                            System.nanoTime() < deadline,
                            "Owned synchronization JVM startup deadline");
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
                            "SELECT COUNT(*) FROM fams_inventory_sync_declaration WHERE"
                                    + " task_id=945000",
                            Integer.class));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_inventory_sync_receipt WHERE task_id=945000",
                            Integer.class));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT version FROM fams_inventory_task WHERE id=945000",
                            Integer.class));
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT captured_result_count FROM fams_inventory_task WHERE id=945000",
                            Integer.class));
            System.out.println(
                    "D6-18B1 JVM proof: committingJvmKilled=true freshJvmOriginalReply=true"
                            + " singleDeclaration=true collectionUnchanged=true");
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
    void replacingBindingCannotHideHistoricalDeviceAndDisabledIdentityRemainsMissing()
            throws Exception {
        assertEquals(
                200,
                call("POST", "/declarations", body(), UUID.randomUUID().toString(), 0, true)
                        .statusCode());
        db.update(
                "UPDATE fams_mini_binding SET revoked_at=NOW(3),version=version+1 WHERE"
                    + " account_id=945002 AND revoked_at IS NULL");
        var identity = new MiniWechatIdentityPort.Identity("sync-app", "replacement-executor");
        db.update(
                "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES"
                    + " (945002,?,NOW(3))",
                sessions.identityHash(identity));
        token = sessions.start(identity, UUID.randomUUID()).sessionToken();
        var status = JSON.readTree(call("GET", "", null, null, 0, false).body());
        assertEquals(
                "BINDING_CHANGED",
                status.path("items").get(0).path("devices").get(0).path("reason").asText());
        assertEquals(
                200,
                call("POST", "/declarations", body(), UUID.randomUUID().toString(), 1, true)
                        .statusCode());
        status = JSON.readTree(call("GET", "", null, null, 0, false).body());
        assertEquals(2, status.path("items").get(0).path("devices").size());
        assertTrue(!status.path("items").get(0).path("current").asBoolean());
        db.update("UPDATE fams_system_account SET status='DISABLED' WHERE id=945002");
        assertEquals(401, call("GET", "/devices/" + device, null, null, 0, true).statusCode());
        status = JSON.readTree(call("GET", "", null, null, 0, false).body());
        assertEquals("IDENTITY_DISABLED", status.path("items").get(0).path("reason").asText());
    }

    @Test
    void failedCentralAuditRollsBackEventDeclarationVersionAndReceipt() throws Exception {
        db.execute(
                "CREATE TRIGGER d618_sync_audit_failure BEFORE INSERT ON fams_audit_event FOR EACH"
                    + " ROW BEGIN IF NEW.resource_id=945000 AND"
                    + " NEW.action='SYNCHRONIZATION_DECLARED' THEN SIGNAL SQLSTATE '45000' SET"
                    + " MESSAGE_TEXT='owned synchronization audit rollback'; END IF; END");
        try {
            assertEquals(
                    500,
                    call("POST", "/declarations", body(), UUID.randomUUID().toString(), 0, true)
                            .statusCode());
        } finally {
            db.execute("DROP TRIGGER d618_sync_audit_failure");
        }
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_sync_declaration WHERE task_id=945000",
                        Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_sync_receipt WHERE task_id=945000",
                        Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_task_event WHERE task_id=945000 AND"
                            + " action='SYNCHRONIZATION_DECLARED'",
                        Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT version FROM fams_inventory_task WHERE id=945000", Integer.class));
    }

    private tools.jackson.databind.node.ObjectNode body() {
        var body = JSON.createObjectNode();
        body.put("deviceId", device);
        body.put("assignmentVersion", 1);
        body.put("observationWatermark", "0");
        body.put("queueSnapshot", "{\"events\":[],\"failures\":[]}");
        body.put("unresolvedCount", 0);
        body.put("pendingEmpty", true);
        body.put("cleanupEmpty", true);
        return body;
    }

    private JsonNode own() throws Exception {
        var response = call("GET", "/devices/" + device, null, null, 0, true);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> call(
            String method, String suffix, Object body, String key, long version, boolean mini)
            throws Exception {
        String channel = mini ? "/api/v1/mini" : "/api/v1";
        var request =
                HttpRequest.newBuilder(
                        URI.create(
                                "http://127.0.0.1:"
                                        + port
                                        + channel
                                        + "/inventory-synchronization/tasks/945000"
                                        + suffix));
        if (mini) {
            request.header("X-FAMS-Mini-Session", token);
        } else {
            request.header("Cookie", cookie).header("X-CSRF-Token", csrf);
        }
        if (key != null) {
            request.header("Idempotency-Key", key)
                    .header("If-Match-Version", Long.toString(version));
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(
                            method,
                            HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
