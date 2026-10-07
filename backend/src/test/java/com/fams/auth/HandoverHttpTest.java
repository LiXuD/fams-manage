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
            HandoverHttpTest.TestApplication.class,
            HandoverHttpTest.MiniConfiguration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=fams-product",
            "spring.flyway.locations=classpath:db/migration/d6_04",
            "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
            "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra"
        })
@EnabledIfSystemProperty(named = "d6.12.handover.runtime", matches = "true")
class HandoverHttpTest {
    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final AtomicLong FIXTURE = new AtomicLong(924000);
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
                    + " VALUES"
                    + " (?,?,'ACTIVE',?,?,'fixture','{\"timezone\":\"Asia/Shanghai\",\"handoverAcceptanceDeadlineNaturalDays\":3}',?)",
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
                    "DELETE g FROM fams_requisition_receipt_grant g JOIN"
                            + " fams_requisition_allocation a ON a.id=g.allocation_id WHERE"
                            + " a.request_id=?",
                    id);
            jdbc.update(
                    "DELETE e FROM fams_requisition_handover_event e JOIN"
                            + " fams_requisition_allocation a ON a.id=e.allocation_id WHERE"
                            + " a.request_id=?",
                    id);
            jdbc.update(
                    "DELETE h FROM fams_requisition_handover h JOIN fams_requisition_allocation a"
                            + " ON a.id=h.allocation_id WHERE a.request_id=?",
                    id);
            jdbc.update(
                    "DELETE t FROM fams_todo t JOIN fams_requisition_allocation a ON"
                        + " t.business_id=a.id WHERE t.business_type='REQUISITION_ALLOCATION' AND"
                        + " a.request_id=?",
                    id);
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
        jdbc.update(
                "DELETE FROM fams_asset_timeline_event WHERE asset_id BETWEEN ? AND ?",
                base + 50,
                base + 53);
        jdbc.update(
                "DELETE FROM fams_asset_tag WHERE asset_id BETWEEN ? AND ?", base + 50, base + 53);
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

    private JsonNode allocated(int quantity) throws Exception {
        var value = submitApprove(create(employee, draft(quantity, base + 10, base)));
        value = action(warehouse, value, "/claim", null);
        String line = value.path("demands").get(0).path("id").asText();
        for (int i = 0; i < quantity; i++) {
            value = allocate(value, warehouse, line, base + 50 + i, null);
        }
        return value;
    }

    private String path(JsonNode value, int index, String action) {
        return "/"
                + value.path("id").asText()
                + "/allocations/"
                + value.path("allocations").get(index).path("id").asText()
                + "/"
                + action;
    }

    private String delivery(JsonNode value, int index) {
        var a = value.path("allocations").get(index);
        return "{\"allocationVersion\":"
                + a.path("version").asLong()
                + ",\"lockVersion\":"
                + a.path("lockVersion").asLong()
                + ",\"assetVersion\":0,\"observedAssetCode\":\"RQ-ASSET-"
                + (base + index)
                + "\",\"destination\":\"Room 1\",\"evidence\":\"现场交付核对编码\"}";
    }

    private String warehouseBody(JsonNode value, int index, boolean returned) {
        var a = value.path("allocations").get(index);
        return "{\"allocationVersion\":"
                + a.path("version").asLong()
                + ",\"lockVersion\":"
                + a.path("lockVersion").asLong()
                + ",\"assetVersion\":0,\"reason\":\"不再需要\",\"observedAssetCode\":\"RQ-ASSET-"
                + (base + index)
                + "\",\"inspectionEvidence\":\"实物、编码及附件均正常，已回原仓位\",\"physicalReturned\":"
                + returned
                + "}";
    }

    private JsonNode warehouseWrite(JsonNode value, int index, String action, String body)
            throws Exception {
        var reply =
                call(
                        warehouse,
                        "POST",
                        path(value, index, action),
                        body,
                        key(),
                        value.path("version").asLong());
        assertEquals(200, reply.statusCode(), reply.body());
        return JSON.readTree(reply.body());
    }

    private String receiptBody(JsonNode value, int index, String code) throws Exception {
        var grant = call(employee, "POST", path(value, index, "grant"), null, key(), null);
        assertEquals(200, grant.statusCode(), grant.body());
        return "{\"grant\":\""
                + JSON.readTree(grant.body()).path("grant").asText()
                + "\",\"observedAssetCode\":\""
                + code
                + "\",\"physicalChecked\":true}";
    }

    @Test
    void deliveryReceiptAndLostResponseReplayAtomicallyUpdateResponsibility() throws Exception {
        var value = allocated(1);
        String id = value.path("id").asText();
        assertEquals(
                409,
                call(employee, "POST", path(value, 0, "grant"), null, key(), null).statusCode());
        assertEquals(
                0,
                JSON.readTree(call(employee, "GET", "/receipts/pending", null, null, null).body())
                        .path("total")
                        .asInt());
        String key = key(), body = delivery(value, 0), endpoint = path(value, 0, "deliver");
        long before = value.path("version").asLong();
        var first = call(warehouse, "POST", endpoint, body, key, before);
        assertEquals(200, first.statusCode(), first.body());
        assertEquals(first.body(), call(warehouse, "POST", endpoint, body, key, before).body());
        value = JSON.readTree(first.body());
        Instant.parse(value.path("handovers").get(0).path("deliveredAt").asText());
        Instant.parse(value.path("handovers").get(0).path("receiptDeadlineAt").asText());
        assertEquals(
                "IN_STOCK",
                jdbc.queryForObject(
                        "SELECT lifecycle_status FROM fams_asset WHERE id=?",
                        String.class,
                        base + 50));
        var pending =
                JSON.readTree(call(employee, "GET", "/receipts/pending", null, null, null).body());
        assertEquals(1, pending.path("total").asInt());
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_requisition_handover WHERE allocation_id=?",
                        value.path("allocations").get(0).path("id").asText()));
        var assetReply =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + port
                                                        + "/api/v1/assets/"
                                                        + (base + 50)))
                                .header("Cookie", warehouse.cookie())
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, assetReply.statusCode(), assetReply.body());
        assertEquals(
                "Room 1",
                JSON.readTree(assetReply.body())
                        .path("physicalDestination")
                        .path("destination")
                        .asText());
        var deliveryTime =
                jdbc.queryForObject(
                        "SELECT delivered_at FROM fams_requisition_handover WHERE allocation_id=?",
                        Timestamp.class,
                        value.path("allocations").get(0).path("id").asText());
        var deadline =
                jdbc.queryForObject(
                        "SELECT receipt_deadline_at FROM fams_requisition_handover WHERE"
                                + " allocation_id=?",
                        Timestamp.class,
                        value.path("allocations").get(0).path("id").asText());
        assertEquals(
                259200,
                java.time.Duration.between(deliveryTime.toInstant(), deadline.toInstant())
                        .getSeconds());
        assertEquals(
                404, call(admin, "POST", path(value, 0, "grant"), null, key(), null).statusCode());
        String receive = receiptBody(value, 0, "RQ-ASSET-" + base);
        key = key();
        endpoint = path(value, 0, "receive");
        before = value.path("version").asLong();
        var done = call(employee, "POST", endpoint, receive, key, before);
        assertEquals(200, done.statusCode(), done.body());
        assertEquals(done.body(), call(employee, "POST", endpoint, receive, key, before).body());
        assertEquals(409, call(employee, "POST", endpoint, receive, key(), before).statusCode());
        assertEquals(
                "IN_USE",
                jdbc.queryForObject(
                        "SELECT lifecycle_status FROM fams_asset WHERE id=?",
                        String.class,
                        base + 50));
        assertEquals(
                base + 10,
                jdbc.queryForObject(
                        "SELECT responsible_employee_id FROM fams_asset WHERE id=?",
                        Long.class,
                        base + 50));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND"
                                + " released_at IS NULL",
                        base + 50));
        value = get(employee, id);
        assertEquals("COMPLETED", value.path("requestStatus").asText());
        assertEquals(1, value.path("demands").get(0).path("completedQuantity").asInt());
        assertEquals(2, value.path("handoverEvents").size());
        jdbc.update("UPDATE fams_system_account SET auth_epoch=auth_epoch+1 WHERE id=?", base + 10);
        assertEquals(401, call(employee, "POST", endpoint, receive, key, before).statusCode());
    }

    @Test
    void unhandedCancelAndRetrievedCancelPreserveQuantitiesAndPhysicalLocks() throws Exception {
        var value = allocated(3);
        String id = value.path("id").asText();
        String cancelBody = warehouseBody(value, 0, false),
                cancelPath = path(value, 0, "cancel"),
                cancelKey = key();
        long version = value.path("version").asLong();
        var cancelled = call(warehouse, "POST", cancelPath, cancelBody, cancelKey, version);
        assertEquals(200, cancelled.statusCode(), cancelled.body());
        value = JSON.readTree(cancelled.body());
        value = warehouseWrite(value, 1, "deliver", delivery(value, 1));
        assertEquals(
                409,
                call(
                                warehouse,
                                "POST",
                                path(value, 1, "cancel"),
                                warehouseBody(value, 1, false),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        String receive =
                receiptBody(value, 1, "wrong-observed-code").replace("}", ",\"reason\":\"实物不符\"}");
        var refused =
                call(
                        employee,
                        "POST",
                        path(value, 1, "refuse"),
                        receive,
                        key(),
                        value.path("version").asLong());
        assertEquals(200, refused.statusCode(), refused.body());
        value = get(employee, id);
        assertEquals(2, value.path("demands").get(0).path("pendingHandoverQuantity").asInt());
        assertEquals(
                2,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE business_id=? AND"
                                + " released_at IS NULL",
                        id));
        value = warehouseWrite(value, 1, "recovery", warehouseBody(value, 1, false));
        assertEquals(
                409,
                call(employee, "POST", path(value, 1, "grant"), null, key(), null).statusCode());
        assertEquals(
                400,
                call(
                                warehouse,
                                "POST",
                                path(value, 1, "recover"),
                                warehouseBody(value, 1, false),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(
                2,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE business_id=? AND"
                                + " released_at IS NULL",
                        id));
        value = warehouseWrite(value, 1, "recover", warehouseBody(value, 1, true));
        String lastBody = warehouseBody(value, 2, false),
                lastPath = path(value, 2, "cancel"),
                lastKey = key();
        version = value.path("version").asLong();
        var last = call(warehouse, "POST", lastPath, lastBody, lastKey, version);
        assertEquals(200, last.statusCode(), last.body());
        assertEquals(
                last.body(), call(warehouse, "POST", lastPath, lastBody, lastKey, version).body());
        value = get(employee, id);
        assertEquals("CANCELLED", value.path("requestStatus").asText());
        assertEquals(3, value.path("demands").get(0).path("cancelledQuantity").asInt());
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE business_id=? AND"
                                + " released_at IS NULL",
                        id));
        assertEquals(
                3,
                count(
                        "SELECT COUNT(*) FROM fams_asset WHERE id BETWEEN ? AND ? AND"
                                + " lifecycle_status='IN_STOCK'",
                        base + 50,
                        base + 52));
        assertEquals(6, value.path("handoverEvents").size());
    }

    @Test
    void grantExpiryWrongAssetVersionsAndCurrentTargetAreRejectedWithoutWrites() throws Exception {
        var value = allocated(2);
        String id = value.path("id").asText();
        assertEquals(
                409,
                call(
                                other,
                                "POST",
                                path(value, 0, "deliver"),
                                delivery(value, 0),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(
                403,
                call(
                                employee,
                                "POST",
                                path(value, 0, "deliver"),
                                delivery(value, 0),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(
                409,
                call(
                                warehouse,
                                "POST",
                                path(value, 0, "deliver"),
                                delivery(value, 0)
                                        .replace("\"lockVersion\":0", "\"lockVersion\":9"),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(
                409,
                call(
                                warehouse,
                                "POST",
                                path(value, 0, "deliver"),
                                delivery(value, 0).replace("RQ-ASSET-" + base, "wrong"),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        String receive = receiptBody(value, 0, "wrong");
        assertEquals(
                409,
                call(
                                employee,
                                "POST",
                                path(value, 0, "receive"),
                                receive,
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        receive = receiptBody(value, 0, "RQ-ASSET-" + base);
        assertEquals(
                409,
                call(
                                employee,
                                "POST",
                                path(value, 1, "receive"),
                                receive,
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        jdbc.update(
                "UPDATE fams_requisition_receipt_grant SET issued_at=DATE_SUB(NOW(3),INTERVAL 10"
                        + " MINUTE), expires_at=DATE_SUB(NOW(3),INTERVAL 5 MINUTE) WHERE"
                        + " allocation_id=?",
                value.path("allocations").get(0).path("id").asText());
        assertEquals(
                409,
                call(
                                employee,
                                "POST",
                                path(value, 0, "receive"),
                                receive,
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        receive = receiptBody(value, 0, "RQ-ASSET-" + base);
        jdbc.update("UPDATE fams_location SET version=version+1 WHERE id=?", base);
        assertEquals(
                409,
                call(
                                employee,
                                "POST",
                                path(value, 0, "receive"),
                                receive,
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_requisition_handover_event WHERE"
                                + " event_type='RECEIVED' AND actor_account_id=?",
                        base + 10));
        assertEquals(0, get(employee, id).path("demands").get(0).path("completedQuantity").asInt());
        assertEquals(
                2,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE business_id=? AND"
                                + " released_at IS NULL",
                        id));
    }

    @Test
    void receivingAndRecoveryRaceAllowsOnlyOneOutcomeAndSharedTargetIsExact() throws Exception {
        var draft = create(department, draft(1, base + 10, base).replace("PERSONAL", "SHARED"));
        var submitted = action(department, draft, "/submit", null);
        var value = action(admin, submitted, "/decision", "{\"result\":\"APPROVED\"}");
        value = action(warehouse, value, "/claim", null);
        value =
                allocate(
                        value,
                        warehouse,
                        value.path("demands").get(0).path("id").asText(),
                        base + 50,
                        null);
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        assertEquals(
                404,
                call(department, "POST", path(value, 0, "grant"), null, key(), null).statusCode());
        String receive = receiptBody(value, 0, "RQ-ASSET-" + base),
                endpoint = path(value, 0, "receive"),
                recovery = path(value, 0, "recovery");
        long version = value.path("version").asLong();
        String body = warehouseBody(value, 0, false);
        var a =
                CompletableFuture.supplyAsync(
                        () -> unchecked(employee, endpoint, receive, version));
        var b = CompletableFuture.supplyAsync(() -> unchecked(warehouse, recovery, body, version));
        var first = a.get(20, TimeUnit.SECONDS);
        var second = b.get(20, TimeUnit.SECONDS);
        assertEquals(
                List.of(200, 409),
                java.util.stream.Stream.of(first.statusCode(), second.statusCode())
                        .sorted()
                        .toList());
        String status =
                jdbc.queryForObject(
                        "SELECT allocation_status FROM fams_requisition_allocation WHERE"
                                + " request_id=?",
                        String.class,
                        value.path("id").asText());
        if ("RECEIVED".equals(status)) {
            assertEquals(
                    "SHARED",
                    jdbc.queryForObject(
                            "SELECT custody_type FROM fams_asset WHERE id=?",
                            String.class,
                            base + 50));
            assertEquals(
                    base + 10,
                    jdbc.queryForObject(
                            "SELECT department_responsible_employee_id FROM fams_asset WHERE id=?",
                            Long.class,
                            base + 50));
            assertEquals(
                    0,
                    count(
                            "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND"
                                    + " released_at IS NULL",
                            base + 50));
        } else {
            assertEquals("RECOVERY_PENDING_CANCEL", status);
            assertEquals(
                    "IN_STOCK",
                    jdbc.queryForObject(
                            "SELECT lifecycle_status FROM fams_asset WHERE id=?",
                            String.class,
                            base + 50));
            assertEquals(
                    1,
                    count(
                            "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND"
                                    + " released_at IS NULL",
                            base + 50));
        }
    }

    @Test
    void nativeReceiptHandlersReachActualHttpAndPersistOneReceiptAfterLostResponse()
            throws Exception {
        var value = allocated(1);
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        String code = "receipt-native-" + base;
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
                        .resolve("apps/miniprogram/test/runtime-receipt-http.mjs");
        String node = System.getenv("FAMS_TEST_NODE_EXECUTABLE");
        var builder =
                new ProcessBuilder("rtk", "proxy", node == null ? "node" : node, script.toString());
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_WECHAT_CODE", code);
        builder.redirectErrorStream(true);
        var process = builder.start();
        assertTrue(process.waitFor(45, TimeUnit.SECONDS));
        String output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertEquals(
                "IN_USE",
                jdbc.queryForObject(
                        "SELECT lifecycle_status FROM fams_asset WHERE id=?",
                        String.class,
                        base + 50));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_requisition_handover_event WHERE"
                                + " event_type='RECEIVED' AND actor_account_id=?",
                        base + 10));
    }

    @Test
    void staleApprovalTargetsKeysAndPhysicalStateCannotCreateHandoverFacts() throws Exception {
        var value = allocated(2);
        String endpoint = path(value, 0, "deliver"), body = delivery(value, 0);
        long version = value.path("version").asLong();
        assertEquals(
                404,
                call(employee, "GET", path(value, 0, "receipt"), null, null, null).statusCode());
        assertEquals(409, call(warehouse, "POST", endpoint, body, key(), version - 1).statusCode());
        assertEquals(
                409,
                call(
                                warehouse,
                                "POST",
                                endpoint,
                                body.replace("\"allocationVersion\":0", "\"allocationVersion\":9"),
                                key(),
                                version)
                        .statusCode());
        assertEquals(
                409,
                call(
                                warehouse,
                                "POST",
                                endpoint,
                                body.replace("\"assetVersion\":0", "\"assetVersion\":9"),
                                key(),
                                version)
                        .statusCode());
        jdbc.update(
                "UPDATE fams_requisition SET request_status='PENDING_ADMIN_DECISION' WHERE id=?",
                value.path("id").asText());
        assertEquals(409, call(warehouse, "POST", endpoint, body, key(), version).statusCode());
        jdbc.update(
                "UPDATE fams_requisition SET request_status='WAITING_HANDOVER' WHERE id=?",
                value.path("id").asText());
        jdbc.update("UPDATE fams_employee SET organization_id=? WHERE id=?", base + 1, base + 10);
        assertEquals(409, call(warehouse, "POST", endpoint, body, key(), version).statusCode());
        jdbc.update("UPDATE fams_employee SET organization_id=? WHERE id=?", base, base + 10);
        jdbc.update("UPDATE fams_asset_category SET status='DISABLED' WHERE id=?", base);
        assertEquals(409, call(warehouse, "POST", endpoint, body, key(), version).statusCode());
        jdbc.update("UPDATE fams_asset_category SET status='ACTIVE' WHERE id=?", base);
        jdbc.update(
                "UPDATE fams_asset SET exception_status='SEARCHING' WHERE id=?", base + 50);
        assertEquals(409, call(warehouse, "POST", endpoint, body, key(), version).statusCode());
        jdbc.update("UPDATE fams_asset SET exception_status='NORMAL' WHERE id=?", base + 50);
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_requisition_handover WHERE allocation_id=?",
                        value.path("allocations").get(0).path("id").asText()));
        String identity = key();
        var delivered = call(warehouse, "POST", endpoint, body, identity, version);
        assertEquals(200, delivered.statusCode(), delivered.body());
        assertEquals(
                409,
                call(
                                warehouse,
                                "POST",
                                endpoint,
                                body.replace("Room 1", "Room 2"),
                                identity,
                                version)
                        .statusCode());
        value = JSON.readTree(delivered.body());
        String receive = receiptBody(value, 0, "RQ-ASSET-" + base);
        assertEquals(
                400,
                call(
                                employee,
                                "POST",
                                path(value, 0, "receive"),
                                receive.replace("true", "false"),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(
                400,
                call(
                                employee,
                                "POST",
                                path(value, 0, "refuse"),
                                receive,
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(
                404,
                call(
                                warehouse,
                                "POST",
                                path(value, 0, "receive"),
                                receive,
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(
                409,
                call(
                                employee,
                                "POST",
                                path(value, 0, "receive"),
                                receive.replace(
                                        JSON.readTree(receive).path("grant").asText(),
                                        "z".repeat(43)),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        jdbc.update(
                "UPDATE fams_requisition_allocation SET version=version+1 WHERE id=?",
                value.path("allocations").get(0).path("id").asText());
        assertEquals(
                409,
                call(
                                employee,
                                "POST",
                                path(value, 0, "receive"),
                                receive,
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_requisition_handover_event WHERE"
                            + " event_type='RECEIVED' AND actor_account_id=?",
                        base + 10));
    }

    @Test
    void receiptFailureRollsBackCustodyQuantitiesLockAndGrantBeforeRetry() throws Exception {
        var value = allocated(1);
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        String body = receiptBody(value, 0, "RQ-ASSET-" + base),
                endpoint = path(value, 0, "receive"),
                identity = key();
        String trigger = "d612_receipt_fault_" + base;
        jdbc.execute(
                "CREATE TRIGGER "
                        + trigger
                        + " BEFORE INSERT ON fams_requisition_handover_event FOR EACH ROW BEGIN IF"
                        + " NEW.actor_account_id="
                        + (base + 10)
                        + " AND NEW.event_type='RECEIVED' THEN SIGNAL SQLSTATE '45000' SET"
                        + " MESSAGE_TEXT='owned receipt fault'; END IF; END");
        try {
            var failed =
                    call(
                            employee,
                            "POST",
                            endpoint,
                            body,
                            identity,
                            value.path("version").asLong());
            assertEquals(500, failed.statusCode(), failed.body());
            assertEquals(
                    "IN_STOCK",
                    jdbc.queryForObject(
                            "SELECT lifecycle_status FROM fams_asset WHERE id=?",
                            String.class,
                            base + 50));
            assertEquals(
                    1,
                    count(
                            "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND"
                                + " released_at IS NULL",
                            base + 50));
            assertEquals(
                    0,
                    get(employee, value.path("id").asText())
                            .path("demands")
                            .get(0)
                            .path("completedQuantity")
                            .asInt());
            assertEquals(
                    1,
                    count(
                            "SELECT COUNT(*) FROM fams_requisition_receipt_grant WHERE"
                                + " allocation_id=? AND consumed_at IS NULL",
                            value.path("allocations").get(0).path("id").asText()));
            assertEquals(
                    0,
                    count(
                            "SELECT COUNT(*) FROM fams_idempotency_record WHERE idempotency_key=?",
                            identity));
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger);
        }
        var result =
                call(employee, "POST", endpoint, body, identity, value.path("version").asLong());
        assertEquals(200, result.statusCode(), result.body());
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_requisition_handover_event WHERE"
                            + " event_type='RECEIVED' AND actor_account_id=?",
                        base + 10));
    }

    @Test
    void mixedReceivedAndCancelledUnitsFinishPartiallyWithoutChangingCompletedResponsibility()
            throws Exception {
        var value = allocated(2);
        String id = value.path("id").asText();
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        var received =
                call(
                        employee,
                        "POST",
                        path(value, 0, "receive"),
                        receiptBody(value, 0, "RQ-ASSET-" + base),
                        key(),
                        value.path("version").asLong());
        assertEquals(200, received.statusCode(), received.body());
        value = get(employee, id);
        assertEquals(
                409,
                call(
                                warehouse,
                                "POST",
                                path(value, 0, "cancel"),
                                warehouseBody(value, 0, false),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(
                403,
                call(
                                employee,
                                "POST",
                                path(value, 1, "cancel"),
                                warehouseBody(value, 1, false),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        value = warehouseWrite(value, 1, "cancel", warehouseBody(value, 1, false));
        assertEquals("PARTIALLY_COMPLETED", value.path("requestStatus").asText());
        assertEquals(1, value.path("demands").get(0).path("completedQuantity").asInt());
        assertEquals(1, value.path("demands").get(0).path("cancelledQuantity").asInt());
        assertEquals(0, value.path("demands").get(0).path("pendingHandoverQuantity").asInt());
        assertEquals(
                "IN_USE",
                jdbc.queryForObject(
                        "SELECT lifecycle_status FROM fams_asset WHERE id=?",
                        String.class,
                        base + 50));
        assertEquals(
                "IN_STOCK",
                jdbc.queryForObject(
                        "SELECT lifecycle_status FROM fams_asset WHERE id=?",
                        String.class,
                        base + 51));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE business_id=? AND"
                            + " released_at IS NULL",
                        id));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_requisition_ownership WHERE request_id=? AND"
                            + " archived_at IS NULL",
                        id));
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
