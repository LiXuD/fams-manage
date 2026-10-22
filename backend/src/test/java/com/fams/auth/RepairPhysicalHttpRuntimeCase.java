package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fams.auth.RepairHttpRuntimeCase.Session;

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
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Actual HTTP, owner ports and transactional MySQL; no simulated business writes. */
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
@EnabledIfSystemProperty(named = "d6.19.repair.physical.runtime", matches = "true")
class RepairPhysicalHttpRuntimeCase {
    static final ObjectMapper JSON = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();
    final RepairHttpRuntimeCase fixture = new RepairHttpRuntimeCase();
    @Autowired JdbcTemplate db;
    @Autowired MiniSessionService miniSessions;

    @Value("${local.server.port}")
    int port;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        RepairHttpRuntimeCase.database(registry);
    }

    @BeforeEach
    void seed() throws Exception {
        fixture.db = db;
        fixture.miniSessions = miniSessions;
        fixture.port = port;
        fixture.seed();
    }

    @AfterEach
    void cleanup() throws Exception {
        db.execute("DROP TRIGGER IF EXISTS owned_repair_physical_audit_failure");
        for (String table :
                List.of(
                        "fams_loan_repair_return",
                        "fams_repair_inbound",
                        "fams_repair_physical_handling")) {
            db.update(
                    "DELETE FROM "
                            + table
                            + " WHERE repair_id IN (SELECT id FROM fams_repair_request WHERE"
                            + " asset_id BETWEEN 951020 AND 951023)");
        }
        fixture.cleanup();
    }

    @Test
    void physicalDepartureRequiresActualInboundAndPreservesOriginalTargetUntilClosure()
            throws Exception {
        var row = processing();
        String id = row.path("id").asText();
        var original =
                db.queryForMap(
                        "SELECT"
                            + " custody_type,using_organization_id,responsible_employee_id,current_location_id,created_business_date,last_inventory_business_date"
                            + " FROM fams_asset WHERE id=951020");
        String key = key(), body = away(row);
        var originalReply =
                ok(
                        post(
                                fixture.warehouse,
                                id + "/accept-away",
                                row.path("version").asLong(),
                                key,
                                body));
        assertEquals(
                originalReply,
                ok(
                        post(
                                fixture.warehouse,
                                id + "/accept-away",
                                row.path("version").asLong(),
                                key,
                                body)));
        assertEquals(
                original,
                db.queryForMap(
                        "SELECT"
                            + " custody_type,using_organization_id,responsible_employee_id,current_location_id,created_business_date,last_inventory_business_date"
                            + " FROM fams_asset WHERE id=951020"));
        row = detail(id);
        assertFalse(row.path("actions").path("completeInPlace").asBoolean());
        assertTrue(row.path("actions").path("closeInbound").asBoolean());
        assertEquals(1, row.path("physicalHandling").size());
        assertEquals(
                409,
                post(
                                fixture.warehouse,
                                id + "/complete-in-place",
                                row.path("version").asLong(),
                                key(),
                                inPlace(row))
                        .statusCode());
        ok(
                post(
                        fixture.warehouse,
                        id + "/close-inbound",
                        row.path("version").asLong(),
                        key(),
                        inbound(row)));
        assertEquals("IN_STOCK", asset().get("lifecycle_status"));
        assertEquals("STORAGE", asset().get("custody_type"));
        assertNull(asset().get("responsible_employee_id"));
        assertEquals(
                0, count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
        assertEquals(0, count("fams_loan_repair_return", "repair_id=" + id));
        assertEquals("COMPLETED", detail(id).path("status").asText());
    }

    @Test
    void expiredBorrowingClosesAtomicallyWithoutRestoringBorrowedOrRewritingDueDates()
            throws Exception {
        borrowing();
        var before = db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020");
        var row = processing();
        String id = row.path("id").asText();
        ok(
                post(
                        fixture.warehouse,
                        id + "/accept-away",
                        row.path("version").asLong(),
                        key(),
                        away(row)));
        row = detail(id);
        String request = key(), body = inbound(row);
        var reply =
                ok(
                        post(
                                fixture.warehouse,
                                id + "/close-inbound",
                                row.path("version").asLong(),
                                request,
                                body));
        assertEquals(
                reply,
                ok(
                        post(
                                fixture.warehouse,
                                id + "/close-inbound",
                                row.path("version").asLong(),
                                request,
                                body)));
        var after = db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020");
        for (String field :
                List.of(
                        "expected_return_date",
                        "original_return_date",
                        "borrowed_date",
                        "borrower_employee_id",
                        "using_organization_id")) {
            assertEquals(before.get(field), after.get(field));
        }
        assertEquals("RETURNED", after.get("relation_status"));
        assertNotNull(after.get("closed_at"));
        assertEquals(1, count("fams_loan_repair_return", "repair_id=" + id));
        assertEquals("IN_STOCK", asset().get("lifecycle_status"));
        assertEquals(
                0,
                count(
                        "fams_asset_timeline_event",
                        "asset_id=951020 AND event_type='REPAIR_RESTORED_IN_PLACE'"));
    }

    @Test
    void inPlaceAcceptanceCanBeFollowedByActualDepartureButNeverInPlaceCompletion()
            throws Exception {
        var row = processing();
        String id = row.path("id").asText();
        ok(
                post(
                        fixture.warehouse,
                        id + "/accept-in-place",
                        row.path("version").asLong(),
                        key(),
                        inPlace(row)));
        row = detail(id);
        ok(
                post(
                        fixture.warehouse,
                        id + "/accept-away",
                        row.path("version").asLong(),
                        key(),
                        away(row)));
        row = detail(id);
        assertEquals(
                409,
                post(
                                fixture.warehouse,
                                id + "/complete-in-place",
                                row.path("version").asLong(),
                                key(),
                                inPlace(row))
                        .statusCode());
        ok(
                post(
                        fixture.warehouse,
                        id + "/close-inbound",
                        row.path("version").asLong(),
                        key(),
                        inbound(row)));
        assertEquals(3, ((Number) asset().get("version")).intValue());
    }

    @Test
    void wrongOwnerStaleTargetAndChangedLoanCannotPartiallyCloseRepair() throws Exception {
        borrowing();
        var row = processing();
        String id = row.path("id").asText();
        assertEquals(
                409,
                post(
                                fixture.otherWarehouse,
                                id + "/accept-away",
                                row.path("version").asLong(),
                                key(),
                                away(row))
                        .statusCode());
        ok(
                post(
                        fixture.warehouse,
                        id + "/accept-away",
                        row.path("version").asLong(),
                        key(),
                        away(row)));
        row = detail(id);
        var body = (ObjectNode) JSON.readTree(inbound(row));
        body.put("locationVersion", 99);
        assertEquals(
                409,
                post(
                                fixture.warehouse,
                                id + "/close-inbound",
                                row.path("version").asLong(),
                                key(),
                                body.toString())
                        .statusCode());
        db.update("UPDATE fams_loan SET borrower_employee_id=951007 WHERE asset_id=951020");
        assertEquals(
                409,
                post(
                                fixture.warehouse,
                                id + "/close-inbound",
                                row.path("version").asLong(),
                                key(),
                                inbound(row))
                        .statusCode());
        assertEquals("UNDER_REPAIR", asset().get("lifecycle_status"));
        assertEquals(0, count("fams_repair_inbound", "repair_id=" + id));
        assertEquals(
                1, count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
    }

    @Test
    void lateAuditFailureRollsBackEveryOwnerWriteAndAllowsExactRetry() throws Exception {
        borrowing();
        var row = processing();
        String id = row.path("id").asText();
        ok(
                post(
                        fixture.warehouse,
                        id + "/accept-away",
                        row.path("version").asLong(),
                        key(),
                        away(row)));
        row = detail(id);
        String body = inbound(row), request = key();
        db.execute(
                "CREATE TRIGGER owned_repair_physical_audit_failure BEFORE INSERT ON"
                        + " fams_audit_event FOR EACH ROW BEGIN IF"
                        + " NEW.action='REPAIR_COMPLETED_INBOUND' THEN SIGNAL SQLSTATE '45000' SET"
                        + " MESSAGE_TEXT='owned late repair inbound audit failure'; END IF; END");
        try {
            assertEquals(
                    500,
                    post(
                                    fixture.warehouse,
                                    id + "/close-inbound",
                                    row.path("version").asLong(),
                                    request,
                                    body)
                            .statusCode());
            assertEquals("UNDER_REPAIR", asset().get("lifecycle_status"));
            assertEquals(1, count("fams_loan", "asset_id=951020 AND relation_status='OPEN'"));
            assertEquals(0, count("fams_loan_repair_return", "repair_id=" + id));
            assertEquals(0, count("fams_repair_inbound", "repair_id=" + id));
            assertEquals(0, count("fams_repair_receipt", "request_id='" + request + "'"));
            assertEquals(row.path("version").asLong(), detail(id).path("version").asLong());
        } finally {
            db.execute("DROP TRIGGER owned_repair_physical_audit_failure");
        }
        ok(
                post(
                        fixture.warehouse,
                        id + "/close-inbound",
                        row.path("version").asLong(),
                        request,
                        body));
    }

    @Test
    void simultaneousActualInboundCallsCommitOneClosureAndOneConflict() throws Exception {
        borrowing();
        var row = processing();
        String id = row.path("id").asText();
        ok(
                post(
                        fixture.warehouse,
                        id + "/accept-away",
                        row.path("version").asLong(),
                        key(),
                        away(row)));
        var current = detail(id);
        String body = inbound(current);
        long version = current.path("version").asLong();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new java.util.concurrent.CountDownLatch(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            var jobs = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 2; i++) {
                jobs.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    return post(
                                                    fixture.warehouse,
                                                    id + "/close-inbound",
                                                    version,
                                                    key(),
                                                    body)
                                            .statusCode();
                                }));
            }
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var job : jobs) {
                statuses.add(job.get());
            }
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
        }
        assertEquals(1, count("fams_repair_inbound", "repair_id=" + id));
        assertEquals(1, count("fams_loan_repair_return", "repair_id=" + id));
    }

    @Test
    void invalidPhysicalTimeCodeAndCatalogCannotMutateAsset() throws Exception {
        var row = processing();
        String id = row.path("id").asText();
        var body = (ObjectNode) JSON.readTree(away(row));
        body.put("occurredAt", Instant.now().plusSeconds(60).toString());
        assertEquals(
                400,
                post(
                                fixture.warehouse,
                                id + "/accept-away",
                                row.path("version").asLong(),
                                key(),
                                body.toString())
                        .statusCode());
        body = (ObjectNode) JSON.readTree(away(row));
        body.put("observedCode", "OTHER-PHYSICAL-DEVICE");
        assertEquals(
                409,
                post(
                                fixture.warehouse,
                                id + "/accept-away",
                                row.path("version").asLong(),
                                key(),
                                body.toString())
                        .statusCode());
        assertEquals(403, get(fixture.employee, "physical-catalog/location").statusCode());
        assertEquals(404, get(fixture.warehouse, "physical-catalog/unknown").statusCode());
        assertEquals("IN_USE", asset().get("lifecycle_status"));
        assertEquals(0, count("fams_repair_physical_handling", "repair_id=" + id));
    }

    @Test
    void actualNativeHandlersCloseExpiredBorrowingAndRetryCommittedInbound() throws Exception {
        borrowing();
        var grants = new java.util.LinkedHashMap<String, Object>();
        for (var entry :
                Map.of("employee", 951002L, "admin", 951001L, "warehouse", 951003L).entrySet()) {
            var identity =
                    new MiniWechatIdentityPort.Identity(
                            "owned-repair-inbound-native", entry.getKey());
            db.update(
                    "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES"
                        + " (?,?,NOW(3))",
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
                        .resolve("apps/miniprogram/test/runtime-repair-inbound-http.mjs");
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
        builder.environment().put("FAMS_TEST_REPAIR_GRANTS", JSON.writeValueAsString(grants));
        var process = builder.start();
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        var output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("\"nativeActualRepairInboundClosed\":true"), output);
        assertEquals("IN_STOCK", asset().get("lifecycle_status"));
        assertEquals(1, count("fams_loan", "asset_id=951020 AND relation_status='RETURNED'"));
        assertEquals(1, count("fams_loan_repair_return", "asset_id=951020"));
        assertEquals(
                0, count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
    }

    private JsonNode processing() throws Exception {
        String body =
                JSON.writeValueAsString(
                        Map.of(
                                "assetId",
                                "951020",
                                "assetVersion",
                                0,
                                "fault",
                                "Actual physical fault requires repair",
                                "impact",
                                "HIGH",
                                "occurredAt",
                                Instant.now()
                                        .minusSeconds(60)
                                        .truncatedTo(ChronoUnit.MILLIS)
                                        .toString()));
        String id = ok(post(fixture.employee, "", 0, key(), body)).path("id").asText();
        ok(
                post(
                        fixture.admin,
                        id + "/decision",
                        0,
                        key(),
                        "{\"result\":\"APPROVE\",\"allowedMethod\":\"INTERNAL_NO_COST\",\"reason\":\"Approve"
                            + " actual internal repair without cost\"}"));
        ok(post(fixture.warehouse, id + "/claim", 1, key(), null));
        return detail(id);
    }

    private ObjectNode base(JsonNode row) {
        var body = JSON.createObjectNode();
        for (String field : List.of("assetVersion", "ownershipVersion", "lockVersion")) {
            body.put(field, row.path(field).asLong());
        }
        if (row.path("loanVersion").isNumber()) {
            body.put("loanVersion", row.path("loanVersion").asLong());
        } else {
            body.putNull("loanVersion");
        }
        body.put("observedCode", row.path("source").path("assetCode").asText());
        body.put("occurredAt", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        body.put(
                "evidence",
                "Actual warehouse physical check and evidence of restoration and custody recorded");
        return body;
    }

    private String away(JsonNode row) {
        var b = base(row);
        b.put("actualLocationId", "951000");
        b.put("locationVersion", 0);
        return b.toString();
    }

    private String inbound(JsonNode row) {
        var b = base(row);
        b.put("warehouseId", "951000");
        b.put("warehouseVersion", 0);
        b.put("locationId", "951000");
        b.put("locationVersion", 0);
        return b.toString();
    }

    private String inPlace(JsonNode row) {
        var b = base(row);
        b.put("actualLocationId", row.path("source").path("locationId").asText());
        b.put("neverLeftCustodian", true);
        return b.toString();
    }

    private JsonNode detail(String id) throws Exception {
        return ok(get(fixture.warehouse, id));
    }

    private Map<String, Object> asset() {
        return db.queryForMap("SELECT * FROM fams_asset WHERE id=951020");
    }

    private int count(String table, String where) {
        return Objects.requireNonNull(
                db.queryForObject(
                        "SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class));
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private JsonNode ok(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> get(Session actor, String path) throws Exception {
        return api(actor, "GET", path, 0, null, null);
    }

    private HttpResponse<String> post(
            Session actor, String path, long version, String key, String body) throws Exception {
        return api(actor, "POST", path, version, key, body);
    }

    private HttpResponse<String> api(
            Session actor, String method, String path, long version, String key, String body)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/api/v1/repairs"
                                                + (path.isEmpty() ? "" : "/" + path)))
                        .header("Cookie", actor.cookie());
        if (!method.equals("GET")) {
            builder.header("X-CSRF-Token", actor.csrf())
                    .header("Idempotency-Key", key)
                    .header("If-Match-Version", Long.toString(version));
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return http.send(
                builder.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void borrowing() {
        db.update(
                "INSERT INTO"
                    + " fams_asset_import_batch(id,actor_account_id,upload_key,source_sha256,source_bytes,source_xlsx,created_at,updated_at)"
                    + " VALUES"
                    + " ('00000000-0000-4000-8000-000000009519',951001,'owned-physical-loan',SHA2('owned"
                    + " initial bytes',256),1,X'01',NOW(3),NOW(3))");
        db.update("UPDATE fams_asset SET lifecycle_status='BORROWED' WHERE id=951020");
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        db.update(
                "INSERT INTO"
                    + " fams_loan(asset_id,borrower_employee_id,using_organization_id,source_type,initial_batch_id,initial_sheet_row,borrowed_date,original_return_date,expected_return_date,parameter_snapshot_json,created_at,updated_at)"
                    + " VALUES"
                    + " (951020,951002,951000,'INITIAL_IMPORT','00000000-0000-4000-8000-000000009519',2,?,?,?,'{}',NOW(3),NOW(3))",
                today.minusDays(30),
                today.minusDays(1),
                today.minusDays(1));
    }
}
