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
@EnabledIfSystemProperty(named = "d6.19.repair.return.runtime", matches = "true")
class RepairReturnHttpRuntimeCase {
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
        db.execute("DROP TRIGGER IF EXISTS owned_repair_return_audit_failure");
        db.update(
                "DELETE FROM fams_todo WHERE business_type='LOAN_EXTENSION' AND business_id IN"
                        + " (SELECT id FROM fams_loan_extension WHERE loan_id IN (SELECT id FROM"
                        + " fams_loan WHERE asset_id BETWEEN 951020 AND 951023))");
        db.update(
                "DELETE FROM fams_loan_extension WHERE loan_id IN (SELECT id FROM fams_loan WHERE"
                        + " asset_id BETWEEN 951020 AND 951023)");
        db.update(
                "DELETE FROM fams_idempotency_record WHERE actor_account_id BETWEEN 951001 AND"
                        + " 951010 AND operation_id IN ('loan:propose','loan:decision')");
        db.update(
                "DELETE FROM fams_repair_return_recollection WHERE handover_id IN (SELECT id FROM"
                        + " fams_repair_return_handover WHERE repair_id IN (SELECT id FROM"
                        + " fams_repair_request WHERE asset_id BETWEEN 951020 AND 951023))");
        for (String table :
                List.of(
                        "fams_repair_return_handover",
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
    void deliveredPhysicalRepairStaysLockedUntilActualOriginalReceiverAccepts() throws Exception {
        var row = accepted();
        String id = row.path("id").asText();
        var original = asset();
        row = delivered(row);
        assertEquals("WAREHOUSE_PROCESSING", row.path("status").asText());
        assertEquals("UNDER_REPAIR", asset().get("lifecycle_status"));
        assertEquals(
                1, count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
        assertFalse(row.path("actions").path("closeInbound").asBoolean());
        assertEquals(
                409,
                post(
                                fixture.warehouse,
                                id + "/close-inbound",
                                row.path("version").asLong(),
                                key(),
                                inbound(row))
                        .statusCode());
        assertEquals(
                403,
                post(
                                fixture.other,
                                id + "/return-receipt",
                                row.path("version").asLong(),
                                key(),
                                receipt(row))
                        .statusCode());
        long version = row.path("version").asLong();
        String request = key(), body = receipt(row);
        var result = ok(post(fixture.employee, id + "/return-receipt", version, request, body));
        assertEquals("COMPLETED", result.path("status").asText());
        assertEquals(
                result, ok(post(fixture.employee, id + "/return-receipt", version, request, body)));
        assertEquals("IN_USE", asset().get("lifecycle_status"));
        for (String field :
                List.of(
                        "responsible_employee_id",
                        "using_organization_id",
                        "current_location_id",
                        "created_business_date",
                        "last_inventory_business_date")) {
            assertEquals(original.get(field), asset().get(field));
        }
        assertEquals(
                0, count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
        assertEquals(
                1,
                count(
                        "fams_asset_timeline_event",
                        "asset_id=951020 AND event_type='REPAIR_RETURN_RECEIVED'"));
        assertEquals(0, count("fams_repair_inbound", "repair_id=" + id));
    }

    @Test
    void refusalRequiresActualRecollectionBeforeWarehouseInbound() throws Exception {
        var row = delivered(accepted());
        String id = row.path("id").asText();
        ok(
                post(
                        fixture.employee,
                        id + "/return-refusal",
                        row.path("version").asLong(),
                        key(),
                        receipt(row)));
        row = detail(id);
        assertEquals(
                "REFUSED", row.path("returnHandovers").get(0).path("handover_status").asText());
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
        ok(
                post(
                        fixture.warehouse,
                        id + "/return-recollection",
                        row.path("version").asLong(),
                        key(),
                        recollection(row)));
        row = detail(id);
        assertEquals(1, row.path("returnRecollections").size());
        assertTrue(row.path("actions").path("closeInbound").asBoolean());
        ok(
                post(
                        fixture.warehouse,
                        id + "/close-inbound",
                        row.path("version").asLong(),
                        key(),
                        inbound(row)));
        assertEquals("IN_STOCK", asset().get("lifecycle_status"));
        assertEquals(
                "RECOLLECTED",
                detail(id).path("returnHandovers").get(0).path("handover_status").asText());
    }

    @Test
    void sharedAssetNeedsItsActualDepartmentResponsiblePersonAndCurrentRole() throws Exception {
        db.update(
                "UPDATE fams_asset SET"
                    + " custody_type='SHARED',responsible_employee_id=NULL,department_responsible_employee_id=951005"
                    + " WHERE id=951020");
        var row = delivered(accepted());
        String id = row.path("id").asText();
        assertEquals(
                403,
                post(
                                fixture.employee,
                                id + "/return-receipt",
                                row.path("version").asLong(),
                                key(),
                                receipt(row))
                        .statusCode());
        var department = login(5);
        assertTrue(ok(get(department, id)).path("actions").path("receiveReturn").asBoolean());
        ok(
                post(
                        department,
                        id + "/return-receipt",
                        row.path("version").asLong(),
                        key(),
                        receipt(row)));
        assertEquals("SHARED", asset().get("custody_type"));
        assertEquals(
                951005L, ((Number) asset().get("department_responsible_employee_id")).longValue());
    }

    @Test
    void approvedExtensionPreservesOverdueHistoryAndContinuesSameOriginalBorrowing()
            throws Exception {
        borrowing(true);
        var original = db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020");
        var row = accepted();
        String id = row.path("id").asText();
        assertEquals(
                409,
                post(
                                fixture.warehouse,
                                id + "/return-delivery",
                                row.path("version").asLong(),
                                key(),
                                delivery(row))
                        .statusCode());
        String loan = original.get("id").toString();
        var proposal =
                ok(
                        loanPost(
                                fixture.employee,
                                "/" + loan + "/extensions",
                                0,
                                "{\"expectedReturnDate\":\"2099-12-31\",\"reason\":\"Actual"
                                    + " continued borrowing requires an approved extension\"}"));
        assertEquals(
                409,
                post(
                                fixture.warehouse,
                                id + "/return-delivery",
                                row.path("version").asLong(),
                                key(),
                                delivery(row))
                        .statusCode());
        ok(
                loanPost(
                        fixture.admin,
                        "/extensions/" + proposal.path("id").asText() + "/decision",
                        0,
                        "{\"approved\":true,\"reason\":\"Administrator explicitly approves"
                                + " continued original borrowing\"}"));
        row = delivered(detail(id));
        ok(
                post(
                        fixture.employee,
                        id + "/return-receipt",
                        row.path("version").asLong(),
                        key(),
                        receipt(row)));
        var after = db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020");
        assertEquals("OPEN", after.get("relation_status"));
        assertEquals(original.get("id"), after.get("id"));
        assertEquals(original.get("original_return_date"), after.get("original_return_date"));
        assertEquals(original.get("borrowed_date"), after.get("borrowed_date"));
        assertEquals("2099-12-31", after.get("expected_return_date").toString());
        assertNull(after.get("closed_at"));
        var extension =
                db.queryForMap(
                        "SELECT * FROM fams_loan_extension WHERE loan_id=?", original.get("id"));
        assertEquals("APPROVED", extension.get("extension_status"));
        assertNotNull(extension.get("overdue_from"));
        assertNotNull(extension.get("overdue_through"));
        assertEquals("BORROWED", asset().get("lifecycle_status"));
        assertEquals(0, count("fams_loan_repair_return", "asset_id=951020"));
    }

    @Test
    void targetInactiveAfterDeliveryDoesNotSilentlyRestoreAndCanBeActuallyRecollected()
            throws Exception {
        var row = delivered(accepted());
        String id = row.path("id").asText();
        db.update("UPDATE fams_location SET status='DISABLED',version=version+1 WHERE id=951001");
        assertEquals(
                409,
                post(
                                fixture.employee,
                                id + "/return-receipt",
                                row.path("version").asLong(),
                                key(),
                                receipt(row))
                        .statusCode());
        assertEquals("UNDER_REPAIR", asset().get("lifecycle_status"));
        assertEquals(0, count("fams_repair_return_recollection", "1=1"));
        ok(
                post(
                        fixture.warehouse,
                        id + "/return-recollection",
                        row.path("version").asLong(),
                        key(),
                        recollection(row)));
        row = detail(id);
        ok(
                post(
                        fixture.warehouse,
                        id + "/close-inbound",
                        row.path("version").asLong(),
                        key(),
                        inbound(row)));
        assertEquals("IN_STOCK", asset().get("lifecycle_status"));
    }

    @Test
    void lateReceiptAuditFailureRollsBackEveryFactAndExactRetryWorks() throws Exception {
        var row = delivered(accepted());
        String id = row.path("id").asText(), request = key(), body = receipt(row);
        db.execute(
                "CREATE TRIGGER owned_repair_return_audit_failure BEFORE INSERT ON fams_audit_event"
                        + " FOR EACH ROW BEGIN IF NEW.action='REPAIR_RETURN_RECEIVED' THEN SIGNAL"
                        + " SQLSTATE '45000' SET MESSAGE_TEXT='owned return audit failure'; END IF;"
                        + " END");
        try {
            assertEquals(
                    500,
                    post(
                                    fixture.employee,
                                    id + "/return-receipt",
                                    row.path("version").asLong(),
                                    request,
                                    body)
                            .statusCode());
            assertEquals("UNDER_REPAIR", asset().get("lifecycle_status"));
            assertEquals(
                    "WAITING_RECEIPT",
                    detail(id).path("returnHandovers").get(0).path("handover_status").asText());
            assertEquals(
                    1,
                    count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
            assertEquals(0, count("fams_repair_receipt", "request_id='" + request + "'"));
        } finally {
            db.execute("DROP TRIGGER owned_repair_return_audit_failure");
        }
        ok(
                post(
                        fixture.employee,
                        id + "/return-receipt",
                        row.path("version").asLong(),
                        request,
                        body));
    }

    @Test
    void concurrentReceiptAndActualRecollectionOnlyCommitOnePhysicalOutcome() throws Exception {
        var row = delivered(accepted());
        String id = row.path("id").asText(),
                receipt = receipt(row),
                recollection = recollection(row);
        long version = row.path("version").asLong();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new java.util.concurrent.CountDownLatch(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            var jobs = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (boolean receive : List.of(true, false)) {
                jobs.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    return post(
                                                    receive ? fixture.employee : fixture.warehouse,
                                                    id
                                                            + (receive
                                                                    ? "/return-receipt"
                                                                    : "/return-recollection"),
                                                    version,
                                                    key(),
                                                    receive ? receipt : recollection)
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
        String state = detail(id).path("returnHandovers").get(0).path("handover_status").asText();
        assertTrue(List.of("RECEIVED", "RECOLLECTED").contains(state));
        assertEquals(
                state.equals("RECEIVED") ? 0 : 1,
                count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
    }

    @Test
    void staleTargetUncheckedReceiptAndCurrentRevocationCannotWriteOrReplay() throws Exception {
        var row = accepted();
        String id = row.path("id").asText();
        var invalid = (ObjectNode) JSON.readTree(delivery(row));
        invalid.put("employeeVersion", 99);
        assertEquals(
                409,
                post(
                                fixture.warehouse,
                                id + "/return-delivery",
                                row.path("version").asLong(),
                                key(),
                                invalid.toString())
                        .statusCode());
        row = delivered(row);
        invalid = (ObjectNode) JSON.readTree(receipt(row));
        invalid.put("physicalChecked", false);
        assertEquals(
                409,
                post(
                                fixture.employee,
                                id + "/return-receipt",
                                row.path("version").asLong(),
                                key(),
                                invalid.toString())
                        .statusCode());
        String body = receipt(row), request = key();
        long version = row.path("version").asLong();
        ok(post(fixture.employee, id + "/return-receipt", version, request, body));
        db.update(
                "UPDATE fams_system_account SET status='DISABLED',auth_epoch=auth_epoch+1 WHERE"
                        + " id=951002");
        assertEquals(
                401,
                post(fixture.employee, id + "/return-receipt", version, request, body)
                        .statusCode());
        assertEquals(
                1,
                count(
                        "fams_repair_return_handover",
                        "repair_id=" + id + " AND handover_status='RECEIVED'"));
    }

    @Test
    void actualNativeHandlersReceiveOriginalBorrowingAndRetryCommittedReceipt() throws Exception {
        borrowing(false);
        var grants = new java.util.LinkedHashMap<String, Object>();
        for (var entry :
                Map.of("employee", 951002L, "admin", 951001L, "warehouse", 951003L).entrySet()) {
            var identity =
                    new MiniWechatIdentityPort.Identity(
                            "owned-repair-return-native", entry.getKey());
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
                        .resolve("apps/miniprogram/test/runtime-repair-return-http.mjs");
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
        assertTrue(output.contains("\"nativeActualRepairReturnReceived\":true"), output);
        assertEquals("BORROWED", asset().get("lifecycle_status"));
        assertEquals(1, count("fams_loan", "asset_id=951020 AND relation_status='OPEN'"));
        assertEquals(0, count("fams_loan_repair_return", "asset_id=951020"));
        assertEquals(1, count("fams_repair_return_handover", "handover_status='RECEIVED'"));
        assertEquals(
                0, count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
    }

    private JsonNode accepted() throws Exception {
        var row = processing();
        String id = row.path("id").asText();
        ok(
                post(
                        fixture.warehouse,
                        id + "/accept-away",
                        row.path("version").asLong(),
                        key(),
                        away(row)));
        return detail(id);
    }

    private JsonNode delivered(JsonNode row) throws Exception {
        String id = row.path("id").asText();
        ok(
                post(
                        fixture.warehouse,
                        id + "/return-delivery",
                        row.path("version").asLong(),
                        key(),
                        delivery(row)));
        return detail(id);
    }

    private String delivery(JsonNode row) throws Exception {
        var body = base(row);
        var target = ok(get(fixture.warehouse, row.path("id").asText() + "/return-target"));
        for (String field : List.of("employeeVersion", "organizationVersion", "locationVersion")) {
            body.put(field, target.path(field).asLong());
        }
        return body.toString();
    }

    private String receipt(JsonNode row) {
        var body = base(row);
        body.put("handoverId", row.path("returnHandovers").get(0).path("id").asText());
        body.put("physicalChecked", true);
        return body.toString();
    }

    private String recollection(JsonNode row) {
        var body = base(row);
        body.put("handoverId", row.path("returnHandovers").get(0).path("id").asText());
        body.put("actualLocationId", "951000");
        body.put("locationVersion", 0);
        return body.toString();
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
                                                                RepairHttpRuntimeCase.PASSWORD))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        var result = ok(response);
        return new Session(
                response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                result.path("csrfToken").asText());
    }

    private HttpResponse<String> loanPost(Session actor, String path, long version, String body)
            throws Exception {
        return http.send(
                HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + port + "/api/v1/loans" + path))
                        .header("Cookie", actor.cookie())
                        .header("X-CSRF-Token", actor.csrf())
                        .header("Idempotency-Key", key())
                        .header("If-Match-Version", Long.toString(version))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
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

    private void borrowing(boolean expired) {
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
                    + " (951020,951002,951000,'INITIAL_IMPORT','00000000-0000-4000-8000-000000009519',2,?,?,?,JSON_OBJECT('timezone','Asia/Shanghai'),NOW(3),NOW(3))",
                today.minusDays(30),
                today.minusDays(1),
                expired ? today.minusDays(1) : today.plusDays(30));
    }
}
