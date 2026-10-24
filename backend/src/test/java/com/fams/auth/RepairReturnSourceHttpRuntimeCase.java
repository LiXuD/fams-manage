package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
@EnabledIfSystemProperty(named = "d6.19.repair.return.source.runtime", matches = "true")
class RepairReturnSourceHttpRuntimeCase {
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
        db.execute("DROP TRIGGER IF EXISTS owned_return_repair_source_failure");
        db.update(
                "DELETE FROM fams_todo WHERE (business_type='ASSET_RETURN' AND business_id IN"
                    + " (SELECT id FROM fams_asset_return WHERE asset_id BETWEEN 951020 AND"
                    + " 951023)) OR (business_type='RETURN_REPAIR' AND business_id IN (SELECT id"
                    + " FROM fams_return_followup WHERE asset_id BETWEEN 951020 AND 951023))");
        db.update(
                "UPDATE fams_return_followup SET linked_repair_id=NULL,last_repair_id=NULL WHERE"
                        + " asset_id BETWEEN 951020 AND 951023");
        db.update(
                "DELETE FROM fams_repair_return_origin WHERE repair_id IN (SELECT id FROM"
                        + " fams_repair_request WHERE asset_id BETWEEN 951020 AND 951023)");
        db.update("DELETE FROM fams_return_followup WHERE asset_id BETWEEN 951020 AND 951023");
        db.update("DELETE FROM fams_asset_return WHERE asset_id BETWEEN 951020 AND 951023");
        db.update(
                "DELETE FROM fams_idempotency_record WHERE actor_account_id BETWEEN 951001 AND"
                        + " 951010");
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
    void actualDamagedReturnAdoptsOneLockAndClosesOnlyAfterActualRepairInbound() throws Exception {
        String followup = damaged();
        var original = returned();
        var beforeAsset = asset();
        var source = ok(get(fixture.admin, "repairs/return-sources/" + followup));
        var lock =
                db.queryForMap(
                        "SELECT * FROM fams_asset_business_lock WHERE business_type='RETURN_REPAIR'"
                                + " AND business_id=? AND released_at IS NULL",
                        followup);
        String request = key(), payload = sourceBody(source);
        var submitted =
                ok(
                        post(
                                fixture.admin,
                                "repairs/return-sources/" + followup,
                                source.path("version").asLong(),
                                request,
                                payload));
        assertEquals(
                submitted,
                ok(
                        post(
                                fixture.admin,
                                "repairs/return-sources/" + followup,
                                source.path("version").asLong(),
                                request,
                                payload)));
        String id = submitted.path("id").asText();
        var adopted =
                db.queryForMap(
                        "SELECT * FROM fams_asset_business_lock WHERE asset_id=951020 AND"
                                + " released_at IS NULL");
        assertEquals(lock.get("id"), adopted.get("id"));
        assertEquals(lock.get("acquired_at"), adopted.get("acquired_at"));
        assertEquals("REPAIR", adopted.get("business_type"));
        assertEquals(1L, ((Number) adopted.get("lock_version")).longValue());
        assertEquals("ACCEPTED", followup().get("followup_status"));
        assertEquals(original, returned());
        var row = approved(submitted);
        assertEquals("951003", row.path("processorAccountId").asText());
        assertFalse(row.path("actions").path("claim").asBoolean());
        assertFalse(row.path("actions").path("acceptInPlace").asBoolean());
        assertFalse(row.path("actions").path("closeInbound").asBoolean());
        assertEquals("UNDER_REPAIR", row.path("source").path("lifecycle").asText());
        assertEquals(
                409,
                post(
                                fixture.warehouse,
                                "repairs/" + id + "/accept-in-place",
                                row.path("version").asLong(),
                                key(),
                                inPlace(row))
                        .statusCode());
        var old = JSON.readTree(away(row)).deepCopy();
        ((ObjectNode) old).put("occurredAt", source.path("inspectedAt").asText());
        assertEquals(
                400,
                post(
                                fixture.warehouse,
                                "repairs/" + id + "/accept-away",
                                row.path("version").asLong(),
                                key(),
                                old.toString())
                        .statusCode());
        row = accept(row);
        String receipt = key(), body = inbound(row);
        var result =
                ok(
                        post(
                                fixture.warehouse,
                                "repairs/" + id + "/close-inbound",
                                row.path("version").asLong(),
                                receipt,
                                body));
        assertEquals(
                result,
                ok(
                        post(
                                fixture.warehouse,
                                "repairs/" + id + "/close-inbound",
                                row.path("version").asLong(),
                                receipt,
                                body)));
        assertEquals("COMPLETED", result.path("status").asText());
        assertEquals("CLOSED", followup().get("followup_status"));
        assertEquals(original, returned());
        assertEquals("IN_STOCK", asset().get("lifecycle_status"));
        assertEquals(
                beforeAsset.get("created_business_date"), asset().get("created_business_date"));
        assertEquals(
                0, count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
        assertEquals(0, count("fams_loan_repair_return", "asset_id=951020"));
        var visible = ok(get(fixture.employee, "asset-returns/" + original.get("id")));
        assertEquals(id, visible.path("followups").get(0).path("linkedRepairId").asText());
        assertEquals("COMPLETED", visible.path("followups").get(0).path("repairStatus").asText());
    }

    @Test
    void rejectionAndCancellationRestoreSourceLockForExplicitNewProposal() throws Exception {
        String followup = damaged();
        var first = submitSource(followup);
        String id = first.path("id").asText();
        ok(
                post(
                        fixture.admin,
                        "repairs/" + id + "/decision",
                        0,
                        key(),
                        "{\"result\":\"REJECT\",\"reason\":\"Need different verified repair"
                                + " scope\"}"));
        assertEquals("PENDING", followup().get("followup_status"));
        assertNull(followup().get("linked_repair_id"));
        assertEquals(
                1,
                count(
                        "fams_asset_business_lock",
                        "asset_id=951020 AND business_type='RETURN_REPAIR' AND released_at IS"
                                + " NULL"));
        var second = submitSource(followup);
        assertFalse(id.equals(second.path("id").asText()));
        ok(
                post(
                        fixture.admin,
                        "repairs/" + second.path("id").asText() + "/cancel",
                        0,
                        key(),
                        "{\"reason\":\"Cancel unapproved source scope\"}"));
        assertEquals("PENDING", followup().get("followup_status"));
        assertEquals(2, count("fams_repair_return_origin", "followup_id=" + followup));
        assertEquals(
                1,
                count(
                        "fams_todo",
                        "business_type='RETURN_REPAIR' AND business_id="
                                + followup
                                + " AND closed_at IS NULL"));
    }

    @Test
    void sourceAuthorizationAndStaleVersionsNeverCreateAnExtraRepair() throws Exception {
        String followup = damaged();
        var source = ok(get(fixture.admin, "repairs/return-sources/" + followup));
        for (Session actor : List.of(fixture.employee, fixture.warehouse, fixture.system)) {
            assertEquals(403, get(actor, "repairs/return-sources").statusCode());
            assertEquals(
                    403,
                    post(actor, "repairs/return-sources/" + followup, 0, key(), sourceBody(source))
                            .statusCode());
        }
        assertEquals(
                409,
                post(
                                fixture.admin,
                                "repairs/return-sources/" + followup,
                                1,
                                key(),
                                sourceBody(source))
                        .statusCode());
        submitSource(followup);
        assertEquals(
                409,
                post(
                                fixture.admin,
                                "repairs/return-sources/" + followup,
                                0,
                                key(),
                                sourceBody(source))
                        .statusCode());
        assertEquals(1, count("fams_repair_return_origin", "followup_id=" + followup));
    }

    @Test
    void sourceCompletionFailureRollsBackAssetRepairAndReceiptThenOriginalRetrySucceeds()
            throws Exception {
        String followup = damaged();
        var row = accept(approved(submitSource(followup)));
        String id = row.path("id").asText();
        var before = asset();
        String body = inbound(row), request = key();
        db.execute(
                "CREATE TRIGGER owned_return_repair_source_failure BEFORE UPDATE ON"
                    + " fams_return_followup FOR EACH ROW BEGIN IF NEW.repair_status='COMPLETED'"
                    + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned source failure'; END"
                    + " IF; END");
        assertEquals(
                500,
                post(
                                fixture.warehouse,
                                "repairs/" + id + "/close-inbound",
                                row.path("version").asLong(),
                                request,
                                body)
                        .statusCode());
        assertEquals(before, asset());
        assertEquals("ACCEPTED", followup().get("followup_status"));
        assertEquals("WAREHOUSE_PROCESSING", detail(id).path("status").asText());
        assertEquals(0, count("fams_repair_inbound", "repair_id=" + id));
        db.execute("DROP TRIGGER owned_return_repair_source_failure");
        ok(
                post(
                        fixture.warehouse,
                        "repairs/" + id + "/close-inbound",
                        row.path("version").asLong(),
                        request,
                        body));
        assertEquals("CLOSED", followup().get("followup_status"));
    }

    private String damaged() throws Exception {
        var proposal =
                ok(
                        post(
                                fixture.employee,
                                "asset-returns",
                                0,
                                key(),
                                "{\"assetId\":\"951020\",\"assetVersion\":0,\"reason\":\"Return"
                                        + " physically damaged asset\"}"));
        String id = proposal.path("id").asText();
        ok(
                post(
                        fixture.admin,
                        "asset-returns/" + id + "/decision",
                        0,
                        key(),
                        "{\"approved\":true,\"reason\":\"Approve actual return inspection\"}"));
        ok(post(fixture.warehouse, "asset-returns/" + id + "/claim", 1, key(), null));
        var row = ok(get(fixture.warehouse, "asset-returns/" + id));
        var input =
                JSON.createObjectNode()
                        .put("result", "DAMAGED")
                        .put("observedCode", "REP-ASSET-951020")
                        .put("reason", "Actual physical damage needs repair")
                        .put("warehouseId", "951000")
                        .put("warehouseVersion", 0)
                        .put("locationId", "951000")
                        .put("locationVersion", 0)
                        .put("assetVersion", 0)
                        .put("lockVersion", row.path("lockVersion").asLong());
        ok(post(fixture.warehouse, "asset-returns/" + id + "/inspect", 2, key(), input.toString()));
        return followup().get("id").toString();
    }

    private JsonNode submitSource(String id) throws Exception {
        var source = ok(get(fixture.admin, "repairs/return-sources/" + id));
        return ok(
                post(
                        fixture.admin,
                        "repairs/return-sources/" + id,
                        source.path("version").asLong(),
                        key(),
                        sourceBody(source)));
    }

    private String sourceBody(JsonNode row) {
        return JSON.createObjectNode()
                .put("assetVersion", row.path("asset").path("version").asLong())
                .put("lockVersion", row.path("lockVersion").asLong())
                .put("fault", "Actual damaged return requires verified repair")
                .put("impact", "HIGH")
                .toString();
    }

    private JsonNode approved(JsonNode row) throws Exception {
        String id = row.path("id").asText();
        ok(
                post(
                        fixture.admin,
                        "repairs/" + id + "/decision",
                        row.path("version").asLong(),
                        key(),
                        "{\"result\":\"APPROVE\",\"allowedMethod\":\"INTERNAL_NO_COST\",\"reason\":\"Approve"
                            + " internal repair of actual returned damage\"}"));
        return detail(id);
    }

    private JsonNode accept(JsonNode row) throws Exception {
        String id = row.path("id").asText();
        ok(
                post(
                        fixture.warehouse,
                        "repairs/" + id + "/accept-away",
                        row.path("version").asLong(),
                        key(),
                        away(row)));
        return detail(id);
    }

    private Map<String, Object> returned() {
        return db.queryForMap("SELECT * FROM fams_asset_return WHERE asset_id=951020");
    }

    private Map<String, Object> followup() {
        return db.queryForMap("SELECT * FROM fams_return_followup WHERE asset_id=951020");
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
        return ok(get(fixture.warehouse, "repairs/" + id));
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
                                URI.create("http://127.0.0.1:" + port + "/api/v1" + "/" + path))
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

    @Test
    void originalReturnedBorrowingIsProvenanceAndNeverClosedTwiceOrReopened() throws Exception {
        borrowing();
        String followup = damaged();
        var oldLoan = db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020");
        assertEquals("RETURNED", oldLoan.get("relation_status"));
        var row = accept(approved(submitSource(followup)));
        assertTrue(row.path("loanVersion").isNull());
        assertTrue(row.path("returnOrigin").path("closedLoanId").isString());
        ok(
                post(
                        fixture.warehouse,
                        "repairs/" + row.path("id").asText() + "/close-inbound",
                        row.path("version").asLong(),
                        key(),
                        inbound(row)));
        assertEquals(oldLoan, db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020"));
        assertEquals(0, count("fams_loan_repair_return", "asset_id=951020"));
    }

    @Test
    void controlledHandoffUpdatesCurrentSourceOwnerButPreservesOriginalInspector()
            throws Exception {
        String followup = damaged();
        var row = approved(submitSource(followup));
        String id = row.path("id").asText();
        db.update("UPDATE fams_system_account SET status='DISABLED' WHERE id=951003");
        var context = ok(get(fixture.admin, "repairs/" + id + "/reassignment"));
        var input =
                JSON.createObjectNode()
                        .put("processorAccountId", "951004")
                        .put("unsynchronizedResultsChecked", true)
                        .put("reason", "Explicit handoff after original warehouse unavailable")
                        .put(
                                "evidence",
                                "Actual physical location checked and unsynchronized results"
                                        + " checked");
        input.set("snapshot", context.path("snapshot"));
        row =
                ok(
                        post(
                                fixture.admin,
                                "repairs/" + id + "/reassignment",
                                row.path("version").asLong(),
                                key(),
                                input.toString()));
        assertEquals("951004", row.path("processorAccountId").asText());
        assertEquals(951004L, ((Number) followup().get("processor_account_id")).longValue());
        assertEquals(951003L, ((Number) returned().get("processor_account_id")).longValue());
        assertEquals("951003", row.path("returnOrigin").path("processorAccountId").asText());
        row = ok(get(fixture.otherWarehouse, "repairs/" + id));
        ok(
                post(
                        fixture.otherWarehouse,
                        "repairs/" + id + "/accept-away",
                        row.path("version").asLong(),
                        key(),
                        away(row)));
        row = ok(get(fixture.otherWarehouse, "repairs/" + id));
        ok(
                post(
                        fixture.otherWarehouse,
                        "repairs/" + id + "/close-inbound",
                        row.path("version").asLong(),
                        key(),
                        inbound(row)));
        assertEquals("CLOSED", followup().get("followup_status"));
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

    @Test
    void actualNativeSourceAndWarehouseHandlersKeepOriginalRepliesAndSourceProgress()
            throws Exception {
        borrowing();
        String followup = damaged();
        var grants = new java.util.LinkedHashMap<String, Object>();
        for (var entry :
                Map.of("employee", 951002L, "admin", 951001L, "warehouse", 951003L).entrySet()) {
            var identity =
                    new MiniWechatIdentityPort.Identity(
                            "owned-return-repair-native", entry.getKey());
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
                        .resolve("apps/miniprogram/test/runtime-repair-return-source-http.mjs");
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
        builder.environment().put("FAMS_TEST_RETURN_FOLLOWUP", followup);
        var process = builder.start();
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        var output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("\"nativeActualReturnSourceClosed\":true"), output);
        assertEquals("CLOSED", followup().get("followup_status"));
        assertEquals("IN_STOCK", asset().get("lifecycle_status"));
        assertEquals(0, count("fams_loan_repair_return", "asset_id=951020"));
        assertEquals(1, count("fams_loan", "asset_id=951020 AND relation_status='RETURNED'"));
    }

    @Test
    void lateSourceCreationAuditFailureRestoresExactlyTheOriginalObligation() throws Exception {
        String followup = damaged();
        var original = followup();
        var source = ok(get(fixture.admin, "repairs/return-sources/" + followup));
        var originalLock =
                db.queryForMap(
                        "SELECT * FROM fams_asset_business_lock WHERE asset_id=951020 AND"
                            + " released_at IS NULL");
        String payload = sourceBody(source), request = key();
        db.execute(
                "CREATE TRIGGER owned_return_repair_source_failure BEFORE INSERT ON"
                    + " fams_repair_event FOR EACH ROW BEGIN IF"
                    + " NEW.action='REPAIR_RETURN_SOURCE_SUBMITTED' THEN SIGNAL SQLSTATE '45000'"
                    + " SET MESSAGE_TEXT='owned source audit failure'; END IF; END");
        assertEquals(
                500,
                post(
                                fixture.admin,
                                "repairs/return-sources/" + followup,
                                source.path("version").asLong(),
                                request,
                                payload)
                        .statusCode());
        assertEquals(original, followup());
        assertEquals(
                originalLock,
                db.queryForMap(
                        "SELECT * FROM fams_asset_business_lock WHERE asset_id=951020 AND"
                            + " released_at IS NULL"));
        assertEquals(0, count("fams_repair_return_origin", "followup_id=" + followup));
        db.execute("DROP TRIGGER owned_return_repair_source_failure");
        var result =
                ok(
                        post(
                                fixture.admin,
                                "repairs/return-sources/" + followup,
                                source.path("version").asLong(),
                                request,
                                payload));
        assertEquals("PENDING_ADMIN_DECISION", result.path("status").asText());
    }

    @Test
    void twoCurrentAdministratorsCannotAdoptOneSourceTwice() throws Exception {
        String followup = damaged();
        var context = ok(get(fixture.admin, "repairs/return-sources/" + followup));
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
                                                                "rep10",
                                                                "password",
                                                                RepairHttpRuntimeCase.PASSWORD))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        var body = ok(response);
        var second =
                new Session(
                        response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                        body.path("csrfToken").asText());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            String payload = sourceBody(context);
            var one =
                    pool.submit(
                            () -> {
                                start.await();
                                return post(
                                                fixture.admin,
                                                "repairs/return-sources/" + followup,
                                                context.path("version").asLong(),
                                                key(),
                                                payload)
                                        .statusCode();
                            });
            var two =
                    pool.submit(
                            () -> {
                                start.await();
                                return post(
                                                second,
                                                "repairs/return-sources/" + followup,
                                                context.path("version").asLong(),
                                                key(),
                                                payload)
                                        .statusCode();
                            });
            start.countDown();
            var statuses = new java.util.ArrayList<>(List.of(one.get(), two.get()));
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(200, 409), statuses);
            assertEquals(1, count("fams_repair_return_origin", "followup_id=" + followup));
            assertEquals(
                    1,
                    count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
        } finally {
            pool.shutdownNow();
        }
    }
}
