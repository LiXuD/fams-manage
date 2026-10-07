package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@SpringBootTest(
        classes = {
            RequisitionHttpTest.TestApplication.class,
            RequisitionHttpTest.MiniConfiguration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=fams-product",
            "spring.flyway.locations=classpath:db/migration/d6_04",
            "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
            "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra"
        })
@EnabledIfSystemProperty(named = "d6.11.requisition.runtime", matches = "true")
class RequisitionHttpTest {
    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final AtomicLong FIXTURE = new AtomicLong(912000);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcTemplate jdbc;
    @Autowired MiniSessionService miniSessions;
    private long base;
    private Session employee;
    private Session warehouse;
    private Session other;
    private Session admin;
    private Session department;

    @org.springframework.boot.test.context.TestConfiguration
    static class MiniConfiguration {
        @org.springframework.context.annotation.Bean
        MiniWechatIdentityPort requisitionWechatFixture() {
            return code -> new MiniWechatIdentityPort.Identity("requisition-test-app", code);
        }
    }

    @Test
    void miniOpaqueSessionUsesSameTransactionsAndCookieCannotReplaceCredential() throws Exception {
        String code = "req-employee-" + base;
        jdbc.update(
                "INSERT INTO fams_mini_binding (account_id,identity_hash,created_at) VALUES"
                        + " (?,?,?)",
                base + 10,
                miniSessions.identityHash(
                        new MiniWechatIdentityPort.Identity("requisition-test-app", code)),
                Timestamp.from(Instant.now()));
        var login =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + port
                                                        + "/api/v1/mini/auth/login"))
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"code\":\"" + code + "\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode());
        assertTrue(login.headers().firstValue("Set-Cookie").isEmpty());
        String token = JSON.readTree(login.body()).path("sessionToken").asText();
        String endpoint = "http://127.0.0.1:" + port + "/api/v1/mini/requisitions";
        var write =
                HttpRequest.newBuilder(URI.create(endpoint))
                        .header("Content-Type", "application/json")
                        .header("X-FAMS-Mini-Session", token)
                        .header("Idempotency-Key", key())
                        .POST(HttpRequest.BodyPublishers.ofString(draft(2, base + 10, base)))
                        .build();
        var first = http.send(write, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, first.statusCode(), first.body());
        assertEquals(first.body(), http.send(write, HttpResponse.BodyHandlers.ofString()).body());
        String id = JSON.readTree(first.body()).path("id").asText();
        var submit =
                HttpRequest.newBuilder(URI.create(endpoint + "/" + id + "/submit"))
                        .header("X-FAMS-Mini-Session", token)
                        .header("Idempotency-Key", key())
                        .header("If-Match-Version", "0")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
        assertEquals(200, http.send(submit, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(
                "PENDING_ADMIN_DECISION",
                jdbc.queryForObject(
                        "SELECT request_status FROM fams_requisition WHERE id=?",
                        String.class,
                        id));
        assertEquals(
                401,
                http.send(
                                HttpRequest.newBuilder(URI.create(endpoint))
                                        .header("Cookie", employee.cookie())
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString())
                        .statusCode());
        jdbc.update("UPDATE fams_mini_binding SET version=version+1 WHERE account_id=?", base + 10);
        assertEquals(401, http.send(write, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_requisition WHERE creator_account_id=?",
                        base + 10));
    }

    @Test
    void nativePageHandlersUseRealHttpAndPersistOneDraftAfterLostResponse() throws Exception {
        String code = "req-native-" + base;
        jdbc.update(
                "INSERT INTO fams_mini_binding (account_id,identity_hash,created_at) VALUES"
                        + " (?,?,?)",
                base + 10,
                miniSessions.identityHash(
                        new MiniWechatIdentityPort.Identity("requisition-test-app", code)),
                Timestamp.from(Instant.now()));
        var script =
                java.nio.file.Path.of(System.getProperty("user.dir"))
                        .getParent()
                        .resolve("apps/miniprogram/test/runtime-requisition-http.mjs");
        String node = System.getenv("FAMS_TEST_NODE_EXECUTABLE");
        var builder =
                new ProcessBuilder("rtk", "proxy", node == null ? "node" : node, script.toString());
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_WECHAT_CODE", code);
        builder.redirectErrorStream(true);
        var process = builder.start();
        assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Native request journey timed out");
        String output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_requisition WHERE creator_account_id=? AND"
                                + " request_status='PENDING_ADMIN_DECISION'",
                        base + 10));
        assertEquals(
                2,
                count(
                        "SELECT SUM(requested_quantity) FROM fams_requisition_demand d JOIN"
                                + " fams_requisition r ON r.id=d.request_id WHERE"
                                + " r.creator_account_id=?",
                        base + 10));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_audit_event WHERE actor_account_id=? AND"
                                + " action='REQUISITION_CREATED'",
                        base + 10));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock l JOIN fams_requisition r ON"
                                + " r.id=l.business_id WHERE l.business_type='REQUISITION' AND"
                                + " r.creator_account_id=?",
                        base + 10));
    }

    @Test
    void disabledOrganizationRejectsBeforeWritesAndFollowupRebindsCurrentReferenceVersions()
            throws Exception {
        JsonNode original = submitApprove(create(employee, draft(1, base + 10, base)));
        String line = original.path("demands").get(0).path("id").asText();
        original =
                action(
                        warehouse,
                        original,
                        "/demands/" + line + "/unfulfillable",
                        "{\"quantity\":1,\"reason\":\"STOCK_SHORTAGE\"}");
        jdbc.update("UPDATE fams_asset_category SET version=version+1 WHERE id=?", base);
        var followup = action(employee, original, "/demands/" + line + "/followup", null);
        assertEquals(1, followup.path("demands").get(0).path("categoryVersionSnapshot").asInt());
        assertEquals(0, original.path("demands").get(0).path("categoryVersionSnapshot").asInt());
        jdbc.update("UPDATE fams_organization SET status='DISABLED' WHERE id=?", base);
        int before =
                count(
                        "SELECT COUNT(*) FROM fams_requisition WHERE creator_account_id=?",
                        base + 10);
        assertEquals(
                401, call(employee, "POST", "", draft(1, base + 10, base), key(), 0L).statusCode());
        assertEquals(
                before,
                count(
                        "SELECT COUNT(*) FROM fams_requisition WHERE creator_account_id=?",
                        base + 10));
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        registry.add("spring.datasource.url", settings::url);
        registry.add("spring.datasource.username", settings::username);
        registry.add("spring.datasource.password", settings::password);
    }

    @BeforeEach
    void seedOwnedFixturesAndAuthenticate() throws Exception {
        base = FIXTURE.addAndGet(100);
        Timestamp now = Timestamp.from(Instant.now());
        for (long id : new long[] {base, base + 1}) {
            jdbc.update(
                    "INSERT INTO fams_organization"
                            + " (id,code,code_normalized,name,status,created_at,updated_at) VALUES"
                            + " (?,?,?,?,'ACTIVE',?,?)",
                    id,
                    "RQ" + id,
                    "RQ" + id,
                    "Organization " + id,
                    now,
                    now);
        }
        jdbc.update(
                "INSERT INTO fams_asset_category"
                        + " (id,code,code_normalized,name,status,created_at,updated_at) VALUES"
                        + " (?,?,?,'Computer','ACTIVE',?,?)",
                base,
                "RQ" + base,
                "RQ" + base,
                now,
                now);
        jdbc.update(
                "INSERT INTO fams_warehouse"
                        + " (id,code,code_normalized,name,status,created_at,updated_at) VALUES"
                        + " (?,?,?,'Warehouse','ACTIVE',?,?)",
                base,
                "RQ" + base,
                "RQ" + base,
                now,
                now);
        jdbc.update(
                "INSERT INTO fams_location"
                    + " (id,code,code_normalized,name,location_type,warehouse_id,status,created_at,updated_at)"
                    + " VALUES (?,?,?,'Office','ROOM',?,'ACTIVE',?,?)",
                base,
                "RQ" + base,
                "RQ" + base,
                base,
                now,
                now);
        String[] roles = {
            "EMPLOYEE",
            "WAREHOUSE_OPERATOR",
            "WAREHOUSE_OPERATOR",
            "ASSET_ADMIN",
            "DEPARTMENT_ASSET_MANAGER",
            "EMPLOYEE",
            "SYSTEM_ADMIN",
            "TECH_ASSISTANT"
        };
        for (int i = 0; i < roles.length; i++) {
            long id = base + 10 + i;
            jdbc.update(
                    "INSERT INTO fams_employee"
                        + " (id,employee_no,employee_no_normalized,name,organization_id,status,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,'ACTIVE',?,?)",
                    id,
                    "RQ" + id,
                    "RQ" + id,
                    "Person " + i,
                    i == 5 ? base + 1 : base,
                    now,
                    now);
            jdbc.update(
                    "INSERT INTO fams_system_account"
                        + " (id,employee_id,username,username_normalized,password_hash,status,initial_password_required,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,'ENABLED',FALSE,?,?)",
                    id,
                    id,
                    "rq" + id,
                    "RQ" + id,
                    new BCryptPasswordEncoder(4).encode(PASSWORD),
                    now,
                    now);
            jdbc.update(
                    "INSERT INTO fams_account_role (account_id,role_code,granted_at) VALUES"
                            + " (?,?,?)",
                    id,
                    roles[i],
                    now);
        }
        jdbc.update(
                "INSERT INTO fams_business_parameter_version"
                    + " (id,version,status,effective_at,actor_account_id,actor_username,snapshot_json,created_at)"
                    + " VALUES (?,?,'ACTIVE',?,?,'fixture','{\"timezone\":\"Asia/Shanghai\"}',?)",
                base,
                base,
                now,
                base + 13,
                now);
        jdbc.update(
                "UPDATE fams_business_parameter_state SET current_version=?,current_version_id=?"
                        + " WHERE singleton_id=1",
                base,
                base);
        for (int i = 0; i < 4; i++) {
            jdbc.update(
                    "INSERT INTO fams_asset"
                        + " (id,asset_code,name,category_id,lifecycle_status,exception_status,card_stage,custody_type,"
                        + "storage_warehouse_id,current_location_id,created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by)"
                        + " VALUES (?,?,'Test"
                        + " Computer',?,'IN_STOCK','NORMAL','ACTIVE','STORAGE',?,?,'2026-10-07',?,?,'fixture',?,?,?,?)",
                    base + 50 + i,
                    "RQ-ASSET-" + (base + i),
                    base,
                    base,
                    base,
                    base,
                    base + 11,
                    now,
                    now,
                    base + 11,
                    base + 11);
        }
        employee = login(base + 10);
        warehouse = login(base + 11);
        other = login(base + 12);
        admin = login(base + 13);
        department = login(base + 14);
    }

    @AfterEach
    void removeOnlyOwnedExactFixtureRange() {
        if (base == 0) {
            return;
        }
        var ids =
                jdbc.queryForList(
                        "SELECT id FROM fams_requisition WHERE creator_account_id BETWEEN ? AND ?",
                        Long.class,
                        base + 10,
                        base + 17);
        for (long id : ids) {
            jdbc.update(
                    "DELETE FROM fams_requisition_followup WHERE source_request_id=? OR"
                            + " latest_request_id=?",
                    id,
                    id);
        }
        for (long id : ids) {
            jdbc.update(
                    "UPDATE fams_requisition SET"
                        + " source_request_id=NULL,source_demand_id=NULL,source_reason_snapshot_json=NULL"
                        + " WHERE id=?",
                    id);
        }
        for (long id : ids) {
            jdbc.update(
                    "UPDATE fams_requisition_allocation SET predecessor_allocation_id=NULL WHERE"
                            + " request_id=?",
                    id);
            for (String table :
                    List.of(
                            "fams_requisition_allocation",
                            "fams_requisition_unfulfillable",
                            "fams_requisition_change_proposal",
                            "fams_requisition_decision",
                            "fams_requisition_ownership",
                            "fams_requisition_demand")) {
                jdbc.update("DELETE FROM " + table + " WHERE request_id=?", id);
            }
            jdbc.update(
                    "DELETE FROM fams_asset_business_lock WHERE business_type='REQUISITION' AND"
                            + " business_id=?",
                    id);
            jdbc.update(
                    "DELETE FROM fams_todo WHERE business_type='REQUISITION' AND business_id=?",
                    id);
            jdbc.update("DELETE FROM fams_requisition WHERE id=?", id);
        }
        jdbc.update("DELETE FROM fams_asset WHERE id BETWEEN ? AND ?", base + 50, base + 53);
        jdbc.update(
                "DELETE FROM fams_mini_session WHERE account_id BETWEEN ? AND ?",
                base + 10,
                base + 17);
        jdbc.update(
                "DELETE FROM fams_mini_binding WHERE account_id BETWEEN ? AND ?",
                base + 10,
                base + 17);
        jdbc.update(
                "DELETE FROM fams_idempotency_record WHERE actor_account_id BETWEEN ? AND ?",
                base + 10,
                base + 17);
        jdbc.update(
                "DELETE FROM fams_audit_event WHERE actor_account_id BETWEEN ? AND ?",
                base + 10,
                base + 17);
        jdbc.update(
                "DELETE FROM fams_security_event WHERE account_id BETWEEN ? AND ?",
                base + 10,
                base + 17);
        jdbc.update(
                "UPDATE fams_business_parameter_state SET current_version_id=NULL,current_version=0"
                        + " WHERE current_version_id=?",
                base);
        jdbc.update("DELETE FROM fams_business_parameter_version WHERE id=?", base);
        jdbc.update(
                "DELETE FROM fams_account_role WHERE account_id BETWEEN ? AND ?",
                base + 10,
                base + 17);
        jdbc.update(
                "DELETE FROM fams_system_account WHERE id BETWEEN ? AND ?", base + 10, base + 17);
        jdbc.update("DELETE FROM fams_employee WHERE id BETWEEN ? AND ?", base + 10, base + 17);
        jdbc.update("DELETE FROM fams_location WHERE id=?", base);
        jdbc.update("DELETE FROM fams_warehouse WHERE id=?", base);
        jdbc.update("DELETE FROM fams_asset_category WHERE id=?", base);
        jdbc.update("DELETE FROM fams_organization WHERE id IN (?,?)", base, base + 1);
    }

    @Test
    void quantityApprovalPartialAllocationReplacementAndLinkedRetryPersistAllFacts()
            throws Exception {
        JsonNode request = create(employee, draft(3, base + 10, base));
        String id = request.path("id").asText();
        String demand = request.path("demands").get(0).path("id").asText();
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE business_id=?",
                        Long.parseLong(id)));
        request = submitApprove(request);
        request = action(warehouse, request, "/claim", null);
        request = allocate(request, warehouse, demand, base + 50, null);
        JsonNode original = request.path("allocations").get(0);
        request = allocate(request, warehouse, demand, base + 51, original.path("id").asText());
        assertEquals(2, request.path("allocations").size());
        assertEquals(
                "CANCELLED", request.path("allocations").get(0).path("allocationStatus").asText());
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE business_id=? AND"
                                + " released_at IS NULL",
                        Long.parseLong(id)));
        request = allocate(request, warehouse, demand, base + 52, null);
        request =
                action(
                        warehouse,
                        request,
                        "/demands/" + demand + "/unfulfillable",
                        "{\"quantity\":1,\"reason\":\"STOCK_SHORTAGE\"}");
        assertEquals("WAITING_HANDOVER", request.path("requestStatus").asText());
        assertEquals(2, request.path("demands").get(0).path("pendingHandoverQuantity").asInt());
        assertEquals(0, request.path("demands").get(0).path("completedQuantity").asInt());
        assertEquals(
                2,
                request.path("unfulfillable")
                        .get(0)
                        .path("inventoryQuerySummaryJson")
                        .path("availableCount")
                        .asInt());
        request = action(employee, request, "/dispute", "{\"reason\":\"请核查库存\"}");
        request = action(employee, request, "/dispute", "{\"reason\":\"请再次核查\"}");
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_todo WHERE business_id=? AND"
                                + " todo_type='UNFULFILLABLE_REVIEW'",
                        Long.parseLong(id)));
        JsonNode followup = action(employee, request, "/demands/" + demand + "/followup", null);
        assertEquals(id, followup.path("sourceRequestId").asText());
        assertEquals(1, followup.path("demands").get(0).path("requestedQuantity").asInt());
        assertEquals(
                followup.path("id").asText(),
                action(employee, request, "/demands/" + demand + "/followup", null)
                        .path("id")
                        .asText());
        HttpResponse<String> tooMany =
                call(
                        employee,
                        "PATCH",
                        "/" + followup.path("id").asText(),
                        draft(2, base + 10, base),
                        key(),
                        0L);
        assertEquals(409, tooMany.statusCode(), tooMany.body());
        JsonNode sent = action(employee, followup, "/submit", null);
        JsonNode rejected =
                action(admin, sent, "/decision", "{\"result\":\"REJECTED\",\"comment\":\"暂缓\"}");
        assertEquals("REJECTED", rejected.path("requestStatus").asText());
        JsonNode retry = action(employee, request, "/demands/" + demand + "/followup", null);
        assertTrue(!retry.path("id").asText().equals(followup.path("id").asText()));
        assertEquals(
                1,
                count(
                        "SELECT unfulfillable_quantity FROM fams_requisition_demand WHERE id=?",
                        Long.parseLong(demand)));
        assertEquals(
                2,
                count(
                        "SELECT COUNT(*) FROM fams_asset WHERE id IN (?,?) AND"
                                + " lifecycle_status='IN_STOCK' AND custody_type='STORAGE'",
                        base + 51,
                        base + 52));
    }

    @Test
    void rolesCurrentDepartmentAndGlobalPoolsAreEnforcedThroughPublicAuthentication()
            throws Exception {
        assertEquals(
                403,
                call(employee, "POST", "", draft(1, base + 15, base + 1), key(), null)
                        .statusCode());
        assertEquals(
                403,
                call(department, "POST", "", draft(1, base + 15, base + 1), key(), null)
                        .statusCode());
        JsonNode delegated =
                create(department, draft(1, base + 10, base).replace("PERSONAL", "SHARED"));
        assertEquals("SHARED", delegated.path("demands").get(0).path("custodyType").asText());
        for (Session prohibited : List.of(login(base + 16), login(base + 17))) {
            assertEquals(403, call(prohibited, "GET", "", null, null, null).statusCode());
        }
        JsonNode owned = create(employee, draft(1, base + 10, base));
        assertEquals(
                404,
                call(other, "GET", "/" + owned.path("id").asText(), null, null, null).statusCode());
        assertEquals(
                404,
                call(admin, "GET", "/" + owned.path("id").asText(), null, null, null).statusCode());
        JsonNode sent = action(employee, owned, "/submit", null);
        assertEquals(
                200,
                call(admin, "GET", "/" + owned.path("id").asText(), null, null, null).statusCode());
        assertEquals(
                403,
                call(
                                employee,
                                "POST",
                                "/" + sent.path("id").asText() + "/decision",
                                "{\"result\":\"APPROVED\"}",
                                key(),
                                1L)
                        .statusCode());
        action(admin, sent, "/decision", "{\"result\":\"APPROVED\"}");
        assertEquals(
                200,
                call(other, "GET", "/" + sent.path("id").asText(), null, null, null).statusCode());
        JsonNode personalCatalog =
                JSON.readTree(call(employee, "GET", "/catalog/employee", null, null, null).body());
        assertEquals(1, personalCatalog.path("items").size());
        assertEquals(400, call(employee, "GET", "?status=UNKNOWN", null, null, null).statusCode());
        assertEquals(400, call(employee, "GET", "/catalog/secret", null, null, null).statusCode());
    }

    @Test
    void oneApprovalAndOneClaimWinConcurrentRequestsAndCompetingAssetsRollBack() throws Exception {
        JsonNode draft = create(employee, draft(2, base + 10, base));
        JsonNode sent = action(employee, draft, "/submit", null);
        String suffix = "/" + sent.path("id").asText();
        var first =
                CompletableFuture.supplyAsync(
                        () ->
                                unchecked(
                                        admin,
                                        suffix + "/decision",
                                        "{\"result\":\"APPROVED\"}",
                                        1L));
        var second =
                CompletableFuture.supplyAsync(
                        () ->
                                unchecked(
                                        admin,
                                        suffix + "/decision",
                                        "{\"result\":\"REJECTED\",\"comment\":\"竞争决定\"}",
                                        1L));
        var a = first.get(15, TimeUnit.SECONDS);
        var b = second.get(15, TimeUnit.SECONDS);
        assertEquals(
                List.of(200, 409),
                java.util.stream.Stream.of(a.statusCode(), b.statusCode()).sorted().toList());
        JsonNode decided = get(employee, sent.path("id").asText());
        assertEquals(1, decided.path("decisions").size());
        if ("REJECTED".equals(decided.path("requestStatus").asText())) {
            decided = revise(employee, decided, draft(2, base + 10, base));
            decided = submitApprove(decided);
        }
        long revision = decided.path("version").asLong();
        first =
                CompletableFuture.supplyAsync(
                        () -> unchecked(warehouse, suffix + "/claim", null, revision));
        second =
                CompletableFuture.supplyAsync(
                        () -> unchecked(other, suffix + "/claim", null, revision));
        a = first.get(15, TimeUnit.SECONDS);
        b = second.get(15, TimeUnit.SECONDS);
        assertEquals(
                List.of(200, 409),
                java.util.stream.Stream.of(a.statusCode(), b.statusCode()).sorted().toList());
        JsonNode claimed = get(employee, sent.path("id").asText());
        assertEquals(1, claimed.path("ownership").size());
        Session owner =
                claimed.path("ownership").get(0).path("processorAccountId").asLong() == base + 11
                        ? warehouse
                        : other;
        String line = claimed.path("demands").get(0).path("id").asText();
        JsonNode allocated = allocate(claimed, owner, line, base + 50, null);
        JsonNode competing = submitApprove(create(employee, draft(1, base + 10, base)));
        String otherLine = competing.path("demands").get(0).path("id").asText();
        var rejected =
                call(
                        warehouse,
                        "POST",
                        "/"
                                + competing.path("id").asText()
                                + "/demands/"
                                + otherLine
                                + "/allocations",
                        allocation(base + 50, null),
                        key(),
                        competing.path("version").asLong());
        assertEquals(409, rejected.statusCode(), rejected.body());
        JsonNode after = get(employee, competing.path("id").asText());
        assertEquals(0, after.path("ownership").size());
        assertEquals(0, after.path("allocations").size());
        assertEquals(1, after.path("demands").get(0).path("pendingAllocationQuantity").asInt());
        assertEquals("WAITING_HANDOVER", allocated.path("requestStatus").asText());
    }

    @Test
    void staleVersionsIdempotencyDifferentPayloadExpiredKeysAndRevokedSessionsNeverWrite()
            throws Exception {
        String key = key();
        String input = draft(1, base + 10, base);
        HttpResponse<String> created = call(employee, "POST", "", input, key, null);
        assertEquals(201, created.statusCode(), created.body());
        assertEquals(created.body(), call(employee, "POST", "", input, key, null).body());
        assertEquals(
                409, call(employee, "POST", "", draft(2, base + 10, base), key, null).statusCode());
        JsonNode value = JSON.readTree(created.body());
        assertEquals(
                409,
                call(employee, "POST", "/" + value.path("id").asText() + "/submit", null, key(), 7L)
                        .statusCode());
        jdbc.update(
                "UPDATE fams_system_account SET authorization_version=authorization_version+1 WHERE"
                        + " id=?",
                base + 10);
        assertEquals(401, call(employee, "POST", "", input, key, null).statusCode());
        employee = login(base + 10);
        jdbc.update(
                "UPDATE fams_idempotency_record SET expires_at=? WHERE actor_account_id=?",
                Timestamp.from(Instant.now().minusSeconds(5)),
                base + 10);
        assertEquals(201, call(employee, "POST", "", input, key, null).statusCode());
        assertEquals(
                2,
                count(
                        "SELECT COUNT(*) FROM fams_requisition WHERE creator_account_id=?",
                        base + 10));
        jdbc.update("UPDATE fams_employee SET status='DISABLED' WHERE id=?", base + 10);
        assertEquals(401, call(employee, "GET", "", null, null, null).statusCode());
    }

    @Test
    void rejectedDraftCanBeRevisedAndCancelledWithoutAnyInventoryLock() throws Exception {
        JsonNode value = create(employee, draft(2, base + 10, base));
        value = action(employee, value, "/submit", null);
        value = action(admin, value, "/decision", "{\"result\":\"REJECTED\",\"comment\":\"修改规格\"}");
        value = revise(employee, value, draft(1, base + 10, base).replace("16GB", "32GB"));
        assertEquals(1, value.path("requestVersion").asInt());
        assertEquals(1, value.path("decisions").size());
        value = action(employee, value, "/cancel", "{\"reason\":\"不再需要\"}");
        assertEquals("CANCELLED", value.path("requestStatus").asText());
        assertEquals(1, value.path("demands").get(0).path("cancelledQuantity").asInt());
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE business_id=?",
                        value.path("id").asLong()));
    }

    @Test
    void scopeChangesRequireNewDecisionAndPreserveApprovedHistoryAndAssignedQuantity()
            throws Exception {
        JsonNode value = submitApprove(create(employee, draft(3, base + 10, base)));
        String line = value.path("demands").get(0).path("id").asText();
        value = allocate(value, warehouse, line, base + 50, null);
        assertEquals(
                409,
                call(
                                employee,
                                "PATCH",
                                "/" + value.path("id").asText(),
                                draft(4, base + 10, base),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        value = action(employee, value, "/changes", draft(4, base + 10, base));
        assertEquals("PENDING_ADMIN_DECISION", value.path("requestStatus").asText());
        assertEquals(
                409,
                call(
                                warehouse,
                                "POST",
                                "/"
                                        + value.path("id").asText()
                                        + "/demands/"
                                        + line
                                        + "/allocations",
                                allocation(base + 51, null),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        value = action(admin, value, "/decision", "{\"result\":\"APPROVED\"}");
        assertEquals(4, value.path("demands").get(0).path("requestedQuantity").asInt());
        assertEquals(1, value.path("demands").get(0).path("pendingHandoverQuantity").asInt());
        assertEquals(2, value.path("decisions").size());
        value = action(employee, value, "/changes", draft(5, base + 10, base));
        value =
                action(
                        admin,
                        value,
                        "/decision",
                        "{\"result\":\"REJECTED\",\"comment\":\"保持原范围\"}");
        assertEquals(4, value.path("demands").get(0).path("requestedQuantity").asInt());
        assertEquals("WAITING_HANDOVER", value.path("requestStatus").asText());
        assertEquals(
                409,
                call(
                                warehouse,
                                "POST",
                                "/" + value.path("id").asText() + "/changes",
                                draft(4, base + 14, base),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(
                1,
                count(
                        "SELECT pending_handover_quantity FROM fams_requisition_demand WHERE id=?",
                        Long.parseLong(line)));
    }

    @Test
    void currentReferenceAndReasonValidationRejectsPartialWritesAndArchivesFullyUnfulfilledOwner()
            throws Exception {
        JsonNode value = submitApprove(create(employee, draft(1, base + 10, base)));
        String line = value.path("demands").get(0).path("id").asText();
        String endpoint = "/" + value.path("id").asText() + "/demands/" + line + "/unfulfillable";
        assertEquals(
                400,
                call(
                                warehouse,
                                "POST",
                                endpoint,
                                "{\"quantity\":1,\"reason\":\"OTHER\"}",
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(
                400,
                call(
                                warehouse,
                                "POST",
                                endpoint,
                                "{\"quantity\":2,\"reason\":\"STOCK_SHORTAGE\"}",
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(0, get(employee, value.path("id").asText()).path("ownership").size());
        jdbc.update("UPDATE fams_asset_category SET version=version+1 WHERE id=?", base);
        assertEquals(
                409,
                call(
                                warehouse,
                                "POST",
                                "/"
                                        + value.path("id").asText()
                                        + "/demands/"
                                        + line
                                        + "/allocations",
                                allocation(base + 50, null),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        value =
                action(
                        warehouse,
                        value,
                        "/demands/" + line + "/unfulfillable",
                        "{\"quantity\":1,\"reason\":\"OTHER\",\"description\":\"供应时间过晚\"}");
        assertEquals("UNFULFILLABLE", value.path("requestStatus").asText());
        assertTrue(!value.path("ownership").get(0).path("archivedAt").isNull());
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE business_id=?",
                        value.path("id").asLong()));
    }

    @Test
    void invalidTargetsAndInactiveReferencesLeaveNoDraftAndFilteredListsReturnOnlySummaries()
            throws Exception {
        long before =
                count(
                        "SELECT COUNT(*) FROM fams_requisition WHERE creator_account_id=?",
                        base + 10);
        assertEquals(
                403,
                call(
                                employee,
                                "POST",
                                "",
                                draft(1, base + 10, base).replace("PERSONAL", "SHARED"),
                                key(),
                                null)
                        .statusCode());
        assertEquals(
                409, call(admin, "POST", "", draft(1, base + 15, base), key(), null).statusCode());
        jdbc.update("UPDATE fams_location SET status='DISABLED' WHERE id=?", base);
        assertEquals(
                409,
                call(employee, "POST", "", draft(1, base + 10, base), key(), null).statusCode());
        assertEquals(
                before,
                count(
                        "SELECT COUNT(*) FROM fams_requisition WHERE creator_account_id=?",
                        base + 10));
        jdbc.update("UPDATE fams_location SET status='ACTIVE' WHERE id=?", base);
        JsonNode value = create(employee, draft(1, base + 10, base));
        long started = System.nanoTime();
        var reply = call(employee, "GET", "?status=DRAFT&page=1&pageSize=200", null, null, null);
        assertEquals(200, reply.statusCode(), reply.body());
        JsonNode listing = JSON.readTree(reply.body());
        assertEquals(1, listing.path("total").asInt());
        assertEquals(value.path("id"), listing.path("items").get(0).path("id"));
        assertTrue(!listing.path("items").get(0).has("demands"));
        System.out.println(
                "D6-11 filtered list development smoke milliseconds="
                        + (System.nanoTime() - started) / 1_000_000);
        assertEquals(1, get(employee, value.path("id").asText()).path("demands").size());
    }

    private JsonNode create(Session session, String body) throws Exception {
        var reply = call(session, "POST", "", body, key(), null);
        assertEquals(201, reply.statusCode(), reply.body());
        return JSON.readTree(reply.body());
    }

    private JsonNode revise(Session session, JsonNode value, String body) throws Exception {
        var reply =
                call(
                        session,
                        "PATCH",
                        "/" + value.path("id").asText(),
                        body,
                        key(),
                        value.path("version").asLong());
        assertEquals(200, reply.statusCode(), reply.body());
        return JSON.readTree(reply.body());
    }

    private JsonNode submitApprove(JsonNode value) throws Exception {
        return action(
                admin,
                action(employee, value, "/submit", null),
                "/decision",
                "{\"result\":\"APPROVED\"}");
    }

    private JsonNode action(Session session, JsonNode value, String action, String body)
            throws Exception {
        var reply =
                call(
                        session,
                        "POST",
                        "/" + value.path("id").asText() + action,
                        body,
                        key(),
                        value.path("version").asLong());
        assertEquals(200, reply.statusCode(), reply.body());
        return JSON.readTree(reply.body());
    }

    private JsonNode get(Session session, String id) throws Exception {
        var reply = call(session, "GET", "/" + id, null, null, null);
        assertEquals(200, reply.statusCode(), reply.body());
        return JSON.readTree(reply.body());
    }

    private JsonNode allocate(
            JsonNode value, Session owner, String line, long asset, String predecessor)
            throws Exception {
        return action(
                owner, value, "/demands/" + line + "/allocations", allocation(asset, predecessor));
    }

    private String allocation(long asset, String predecessor) {
        return "{\"asset\":{\"id\":\""
                + asset
                + "\",\"version\":0}"
                + (predecessor == null
                        ? ""
                        : ",\"predecessorAllocationId\":\""
                                + predecessor
                                + "\",\"replacementReason\":\"型号调整\"")
                + "}";
    }

    private String draft(int quantity, long target, long org) {
        return """
               {"demands":[{"category":{"id":"%d","version":0},"specification":"16GB",
                "purpose":"Office work","expectedDate":"2026-10-10","custodyType":"PERSONAL",
                "organization":{"id":"%d","version":0},"employee":{"id":"%d","version":0},
                "location":{"id":"%d","version":0},"quantity":%d}]}
               """
                .formatted(base, org, target, base, quantity);
    }

    private Session login(long id) throws Exception {
        var reply =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:" + port + "/api/v1/auth/login"))
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"username\":\"rq"
                                                        + id
                                                        + "\",\"password\":\""
                                                        + PASSWORD
                                                        + "\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, reply.statusCode(), reply.body());
        return new Session(
                reply.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                JSON.readTree(reply.body()).path("csrfToken").asText());
    }

    private HttpResponse<String> call(
            Session session, String method, String suffix, String body, String key, Long version)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/requisitions" + suffix));
        var headers = new HashMap<String, String>();
        headers.put("Cookie", session.cookie());
        if (key != null) {
            headers.put("Idempotency-Key", key);
            headers.put("X-CSRF-Token", session.csrf());
        }
        if (version != null) {
            headers.put("If-Match-Version", version.toString());
        }
        if (body != null) {
            headers.put("Content-Type", "application/json");
        }
        headers.forEach(builder::header);
        builder.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> unchecked(
            Session session, String suffix, String body, long version) {
        try {
            return call(session, "POST", suffix, body, key(), version);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private record Session(String cookie, String csrf) {}

    @SpringBootApplication(scanBasePackages = "com.fams")
    static class TestApplication {}
}
