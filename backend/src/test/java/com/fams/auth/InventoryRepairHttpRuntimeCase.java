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
@EnabledIfSystemProperty(named = "d6.19.inventory.repair.runtime", matches = "true")
class InventoryRepairHttpRuntimeCase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    private static java.nio.file.Path photoRoot;
    private final HttpClient http = HttpClient.newHttpClient();
    private final InventoryReviewHttpRuntimeCase fixture = new InventoryReviewHttpRuntimeCase();

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcTemplate db;
    @Autowired MiniSessionService miniSessions;
    private Session admin, employee, warehouse, other;

    private record Session(String cookie, String csrf) {}

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        InventoryReviewHttpRuntimeCase.database(registry);
        if (photoRoot == null) {
            photoRoot = java.nio.file.Files.createTempDirectory("fams-inventory-repair-photos-");
        }
        registry.add("fams.attachments.quarantine-root", () -> photoRoot.toString());
        registry.add("fams.test.proxy-evidence", () -> "true");
    }

    @BeforeEach
    void seed() throws Exception {
        fixture.db = db;
        fixture.port = port;
        fixture.miniSessions = miniSessions;
        fixture.seed();
        admin = login(1);
        employee = login(2);
        other = login(3);
        warehouse = login(4);
    }

    @AfterEach
    void cleanup() throws Exception {
        db.execute("DROP TRIGGER IF EXISTS owned_inventory_repair_failure");
        db.update(
                "DELETE FROM fams_inventory_review_repair WHERE observation_id IN (SELECT id FROM"
                        + " fams_inventory_observation WHERE actor_account_id=943002)");
        db.update(
                "DELETE FROM fams_repair_inventory_evidence WHERE repair_id IN (SELECT id FROM"
                        + " fams_repair_request WHERE creator_account_id IN (943001,943003))");
        for (String table :
                java.util.List.of(
                        "fams_repair_photo",
                        "fams_repair_receipt",
                        "fams_repair_event",
                        "fams_repair_decision")) {
            db.update(
                    "DELETE FROM "
                            + table
                            + " WHERE repair_id IN (SELECT id FROM fams_repair_request WHERE"
                            + " creator_account_id IN (943001,943003))");
        }
        db.update(
                "DELETE FROM fams_todo WHERE business_type='REPAIR' AND business_id IN (SELECT id"
                        + " FROM fams_repair_request WHERE creator_account_id IN (943001,943003))");
        db.update("DELETE FROM fams_asset_business_lock WHERE asset_id BETWEEN 943010 AND 943012");
        db.update("DELETE FROM fams_repair_request WHERE creator_account_id IN (943001,943003)");
        db.update(
                "DELETE FROM fams_inventory_photo_receipt WHERE scope_id IN (SELECT id FROM"
                        + " fams_inventory_upload_scope WHERE actor_account_id=943002)");
        db.update(
                "DELETE FROM fams_attachment WHERE business_type='INVENTORY_EVENT' AND business_id"
                        + " IN (SELECT id FROM fams_inventory_upload_scope WHERE"
                        + " actor_account_id=943002)");
        db.update(
                "DELETE FROM fams_attachment_scope WHERE business_type='INVENTORY_EVENT' AND"
                        + " business_id IN (SELECT id FROM fams_inventory_upload_scope WHERE"
                        + " actor_account_id=943002)");
        db.update("DELETE FROM fams_inventory_upload_scope WHERE actor_account_id=943002");
        fixture.cleanup();
        try (var files = java.nio.file.Files.walk(photoRoot)) {
            for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (!file.equals(photoRoot)) {
                    java.nio.file.Files.deleteIfExists(file);
                }
            }
        }
    }

    @Test
    void actualRepairCompletionClosesOnlyConditionAndPreservesRawTimeAndPhotos() throws Exception {
        String task = damaged();
        var original =
                db.queryForList(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=? ORDER BY id",
                        task);
        var body = decision(task, "FORMAL_REPAIR");
        String requestKey = key();
        long version = version(task);
        var response = ok(decide(admin, task, body, requestKey, version));
        assertEquals(response, ok(decide(admin, task, body, requestKey, version)));
        String id = response.path("items").get(0).path("linkedRepairId").asText();
        assertEquals("PROCESSING", status(task, "CONDITION"));
        assertEquals("OPEN", status(task, "TIME"));
        String base = "/api/v1/repairs/" + id;
        var row = ok(request(admin, "GET", base, null, null, null));
        assertEquals("PENDING_ADMIN_DECISION", row.path("status").asText());
        assertEquals(1, row.path("inventoryEvidence").size());
        assertEquals("PROCESSING", refresh(task).path("items").get(0).path("status").asText());
        row =
                ok(
                        request(
                                admin,
                                "POST",
                                base + "/decision",
                                "{\"result\":\"APPROVE\",\"allowedMethod\":\"INTERNAL_NO_COST\",\"reason\":\"Actual"
                                    + " physical fault independently checked\"}",
                                key(),
                                row.path("version").asLong()));
        assertEquals("PROCESSING", refresh(task).path("items").get(0).path("status").asText());
        row =
                ok(
                        request(
                                warehouse,
                                "POST",
                                base + "/claim",
                                null,
                                key(),
                                row.path("version").asLong()));
        row = ok(request(warehouse, "GET", base, null, null, null));
        var origin = row.path("inventoryEvidence").get(0);
        String evidencePath =
                "/api/v1/inventory-repair-evidence/"
                        + id
                        + "/observations/"
                        + origin.path("observationId").asText()
                        + "/photos/"
                        + origin.path("photoIds").get(0).asText();
        var grant =
                ok(request(warehouse, "POST", evidencePath + "/download-grant", null, null, null));
        assertEquals(
                200,
                request(warehouse, "GET", grant.path("url").asText(), null, null, null)
                        .statusCode());
        assertEquals(
                403,
                request(employee, "POST", evidencePath + "/download-grant", null, null, null)
                        .statusCode());
        db.update(
                "DELETE FROM fams_account_role WHERE account_id=943004 AND"
                        + " role_code='WAREHOUSE_OPERATOR'");
        assertEquals(
                403,
                request(warehouse, "GET", grant.path("url").asText(), null, null, null)
                        .statusCode());
        db.update(
                "INSERT INTO fams_account_role(account_id,role_code,granted_at) VALUES"
                        + " (943004,'WAREHOUSE_OPERATOR',NOW(3))");
        warehouse = login(4);
        row = ok(request(warehouse, "GET", base, null, null, null));
        row =
                ok(
                        request(
                                warehouse,
                                "POST",
                                base + "/accept-in-place",
                                physical(row),
                                key(),
                                row.path("version").asLong()));
        assertEquals("PROCESSING", refresh(task).path("items").get(0).path("status").asText());
        row = ok(request(warehouse, "GET", base, null, null, null));
        assertEquals(
                "COMPLETED",
                ok(request(
                                warehouse,
                                "POST",
                                base + "/complete-in-place",
                                physical(row),
                                key(),
                                row.path("version").asLong()))
                        .path("status")
                        .asText());
        assertEquals("CLOSED", refresh(task).path("items").get(0).path("status").asText());
        assertEquals("OPEN", status(task, "TIME"));
        assertEquals(
                original,
                db.queryForList(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=? ORDER BY id",
                        task));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_review_repair", Integer.class));
    }

    @Test
    void sourceWriteFailureRollsBackRepairLockOriginAndTaskBeforeOriginalRetry() throws Exception {
        String task = damaged();
        var body = decision(task, "FORMAL_REPAIR");
        long before = version(task);
        String requestKey = key();
        db.execute(
                "CREATE TRIGGER owned_inventory_repair_failure BEFORE INSERT ON"
                        + " fams_inventory_review_repair FOR EACH ROW SIGNAL SQLSTATE '45000' SET"
                        + " MESSAGE_TEXT='owned source rollback'");
        assertTrue(decide(admin, task, body, requestKey, before).statusCode() >= 400);
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_request WHERE creator_account_id=943001",
                        Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_inventory_evidence", Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=943010",
                        Integer.class));
        assertEquals(before, version(task));
        db.execute("DROP TRIGGER owned_inventory_repair_failure");
        ok(decide(admin, task, body, requestKey, before));
    }

    @Test
    void rejectionReopensOnlyDamageAndRequiresExplicitContinuation() throws Exception {
        String task = damaged();
        var made = ok(decide(admin, task, decision(task, "FORMAL_REPAIR"), key(), version(task)));
        String id = made.path("items").get(0).path("linkedRepairId").asText();
        ok(
                request(
                        admin,
                        "POST",
                        "/api/v1/repairs/" + id + "/decision",
                        "{\"result\":\"REJECT\",\"reason\":\"Formal repair rejection recorded\"}",
                        key(),
                        0L));
        assertEquals("OPEN", refresh(task).path("items").get(0).path("status").asText());
        assertEquals("OPEN", status(task, "TIME"));
        ok(decide(admin, task, decision(task, "FORMAL_REPAIR"), key(), version(task)));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_review_repair", Integer.class));
        assertEquals("PROCESSING", status(task, "CONDITION"));
    }

    @Test
    void currentAuthorityAndIndependentDiscoveryVerificationPrecedeFormalSubmission()
            throws Exception {
        String task = damaged();
        var body = decision(task, "FORMAL_REPAIR");
        assertEquals(403, decide(employee, task, body, key(), version(task)).statusCode());
        ((tools.jackson.databind.node.ObjectNode) body.path("repair"))
                .put("discoveryTimeVerified", false);
        assertEquals(400, decide(admin, task, body, key(), version(task)).statusCode());
        body = decision(task, "FORMAL_REPAIR").put("assetVersion", 99);
        assertEquals(409, decide(admin, task, body, key(), version(task)).statusCode());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_request WHERE creator_account_id=943001",
                        Integer.class));
    }

    @Test
    void linksOnlySameAssetAndExactActiveRepairWithoutDuplicatingTheLock() throws Exception {
        String task = damaged();
        var body = decision(task, "LINK_REPAIR");
        var submission =
                JSON.createObjectNode()
                        .put("assetId", "943010")
                        .put("assetVersion", 0)
                        .put("fault", "Actual source physical fault")
                        .put("impact", "HIGH")
                        .put(
                                "occurredAt",
                                Instant.now()
                                        .minusSeconds(5)
                                        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                                        .toString());
        var row = ok(request(admin, "POST", "/api/v1/repairs", submission.toString(), key(), null));
        var damage = (tools.jackson.databind.node.ObjectNode) body.path("repair");
        damage.put("repairId", row.path("id").asText()).put("repairVersion", 99);
        assertEquals(409, decide(admin, task, body, key(), version(task)).statusCode());
        damage.put("repairVersion", row.path("version").asLong());
        ok(decide(admin, task, body, key(), version(task)));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=943010 AND"
                                + " released_at IS NULL",
                        Integer.class));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_request WHERE asset_id=943010",
                        Integer.class));
        var bypass = decision(task, "BOOK_CORRECT");
        assertEquals(409, decide(admin, task, bypass, key(), version(task)).statusCode());
    }

    @Test
    void twoActualAdministratorsCreateOnlyOneFormalRepairAndOneSourceDecision() throws Exception {
        String task = damaged();
        var body = decision(task, "FORMAL_REPAIR");
        long before = version(task);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> decide(admin, task, body, key(), before));
            var second = pool.submit(() -> decide(other, task, body, key(), before));
            var replies =
                    java.util.List.of(
                            first.get(30, java.util.concurrent.TimeUnit.SECONDS),
                            second.get(30, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, replies.stream().filter(reply -> reply.statusCode() == 200).count());
            assertEquals(1, replies.stream().filter(reply -> reply.statusCode() == 409).count());
        }
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_request WHERE asset_id=943010",
                        Integer.class));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_review_repair", Integer.class));
        assertEquals(before + 1, version(task));
    }

    @Test
    void rejectedCounterEvidenceCannotEraseAnActiveFormalRepair() throws Exception {
        String task = damaged();
        String item =
                db.queryForObject(
                        "SELECT CAST(id AS CHAR) FROM fams_inventory_item WHERE task_id=? AND"
                            + " asset_id=943010",
                        String.class,
                        task);
        String chosen =
                db.queryForObject(
                        "SELECT CAST(id AS CHAR) FROM fams_inventory_observation WHERE task_id=?"
                            + " AND item_id=? AND outcome='ACCEPTED'",
                        String.class,
                        task,
                        item);
        String path = "/api/v1/inventory-reviews/tasks/" + task + "/items/" + item;
        var context = ok(request(admin, "GET", path + "/selection", null, null, null));
        var input =
                JSON.createObjectNode()
                        .put("observationId", chosen)
                        .put("recheckGeneration", 0)
                        .put("reviewVersion", context.path("review").path("version").asLong())
                        .put("assetVersion", 0)
                        .put("contextDigest", context.path("contextDigest").asText())
                        .put("reason", "Explicit normal evidence choice")
                        .put("evidence", "Independent original physical register checked");
        ok(request(admin, "POST", path + "/selection", input.toString(), key(), version(task)));
        var body = decision(task, "FORMAL_REPAIR");
        String observation = body.path("subjects").get(0).path("observationId").asText();
        ok(decide(admin, task, body, key(), version(task)));
        context = ok(request(admin, "GET", path + "/selection", null, null, null));
        input.put("observationId", observation)
                .put("reviewVersion", context.path("review").path("version").asLong())
                .put("contextDigest", context.path("contextDigest").asText())
                .put("method", "REJECT_EVIDENCE");
        var subjects = input.putArray("subjects");
        for (var row :
                db.queryForList(
                        "SELECT dimension,version FROM fams_inventory_review_subject WHERE"
                            + " observation_id=? ORDER BY dimension",
                        observation)) {
            subjects.addObject()
                    .put("dimension", row.get("dimension").toString())
                    .put("version", ((Number) row.get("version")).longValue());
        }
        var rejected =
                request(
                        admin,
                        "POST",
                        path + "/evidence-decisions",
                        input.toString(),
                        key(),
                        version(task));
        assertEquals(409, rejected.statusCode(), rejected.body());
        assertEquals(
                "INVENTORY_REVIEW_LINKED_EVIDENCE_REQUIRED",
                JSON.readTree(rejected.body()).path("code").asText());
        assertEquals("PROCESSING", status(task, "CONDITION"));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_evidence_disposition WHERE"
                            + " observation_id=?",
                        Integer.class,
                        observation));
    }

    private String damaged() throws Exception {
        String task = prepared();
        var event = observation(task, 943010).put("clockAnomaly", true);
        ((tools.jackson.databind.node.ObjectNode) event.path("actual")).put("condition", "DAMAGED");
        byte[] bytes =
                java.util.Base64.getDecoder()
                        .decode(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==");
        var photo = ok(uploadPhoto(employee, task, event, key(), bytes));
        ((tools.jackson.databind.node.ArrayNode) event.path("photoIds"))
                .add(photo.path("objectId").asText());
        ok(observe(task, event));
        String observation =
                db.queryForObject(
                        "SELECT CAST(id AS CHAR) FROM fams_inventory_observation WHERE task_id=?"
                                + " AND client_event_id=?",
                        String.class,
                        task,
                        event.path("eventId").asText());
        ok(
                request(
                        admin,
                        "POST",
                        "/api/v1/inventory-reviews/tasks/" + task + "/differences/preparation",
                        JSON.writeValueAsString(
                                java.util.Map.of("observationIds", java.util.List.of(observation))),
                        key(),
                        version(task)));
        return task;
    }

    private tools.jackson.databind.node.ObjectNode decision(String task, String method) {
        var row =
                db.queryForMap(
                        "SELECT * FROM fams_inventory_review_subject WHERE task_id=? AND"
                                + " dimension='CONDITION'",
                        task);
        var body =
                JSON.createObjectNode()
                        .put("method", method)
                        .put(
                                "assetVersion",
                                db.queryForObject(
                                        "SELECT version FROM fams_asset WHERE id=943010",
                                        Long.class))
                        .put("reason", "Physical damage independently checked")
                        .put(
                                "evidence",
                                "Signed source photo and independently verified discovery time");
        body.putArray("subjects")
                .addObject()
                .put("observationId", row.get("observation_id").toString())
                .put("dimension", "CONDITION")
                .put("version", ((Number) row.get("version")).longValue());
        body.putObject("repair")
                .put(
                        "verifiedDiscoveryAt",
                        Instant.now()
                                .minusSeconds(10)
                                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                                .toString())
                .put("fault", "Actual inventory damage verified")
                .put("impact", "HIGH")
                .put("physicalDamageChecked", true)
                .put("discoveryTimeVerified", true);
        return body;
    }

    private JsonNode refresh(String task) throws Exception {
        return ok(decide(admin, task, decision(task, "REFRESH_REPAIR"), key(), version(task)));
    }

    private HttpResponse<String> decide(
            Session actor, String task, JsonNode body, String requestKey, long version)
            throws Exception {
        return request(
                actor,
                "POST",
                "/api/v1/inventory-reviews/tasks/" + task + "/differences/decisions",
                body.toString(),
                requestKey,
                version);
    }

    private long version(String task) {
        return db.queryForObject(
                "SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task);
    }

    private String status(String task, String dimension) {
        return db.queryForObject(
                "SELECT subject_status FROM fams_inventory_review_subject WHERE task_id=? AND"
                        + " dimension=?",
                String.class,
                task,
                dimension);
    }

    private String physical(JsonNode row) {
        return JSON.writeValueAsString(
                java.util.Map.of(
                        "assetVersion",
                        row.path("assetVersion").asLong(),
                        "ownershipVersion",
                        row.path("ownershipVersion").asLong(),
                        "lockVersion",
                        row.path("lockVersion").asLong(),
                        "observedCode",
                        row.path("source").path("assetCode").asText(),
                        "actualLocationId",
                        row.path("source").path("locationId").asText(),
                        "neverLeftCustodian",
                        true,
                        "evidence",
                        "Actual physical check and repair evidence at original location"));
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
}
