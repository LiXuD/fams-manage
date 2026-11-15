package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
@EnabledIfSystemProperty(named = "d6.18.inventory.readiness.runtime", matches = "true")
class InventoryReadinessHttpRuntimeCase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path photos;
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
        registry.add("fams.attachments.quarantine-root", () -> photos.toString());
        registry.add("fams.test.proxy-evidence", () -> "true");
    }

    @BeforeEach
    void seed() throws Exception {
        db.update(
                "INSERT INTO"
                    + " fams_organization(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (946000,'READY','READY','Readiness"
                    + " organization','ACTIVE',NOW(3),NOW(3))");
        for (int i = 1; i <= 3; i++) {
            db.update(
                    "INSERT INTO"
                        + " fams_employee(id,employee_no,employee_no_normalized,name,organization_id,status,created_at,updated_at)"
                        + " VALUES (?,?,?, ?,946000,'ACTIVE',NOW(3),NOW(3))",
                    946000 + i,
                    "ready" + i,
                    "READY" + i,
                    "Readiness " + i);
            db.update(
                    "INSERT INTO"
                        + " fams_system_account(id,employee_id,username,username_normalized,password_hash,status,initial_password_required,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,'ENABLED',FALSE,NOW(3),NOW(3))",
                    946000 + i,
                    946000 + i,
                    "ready" + i,
                    "READY" + i,
                    new BCryptPasswordEncoder(4).encode(PASSWORD));
            db.update(
                    "INSERT INTO fams_account_role(account_id,role_code,granted_at) VALUES"
                            + " (?,?,NOW(3))",
                    946000 + i,
                    i == 1 ? "ASSET_ADMIN" : "EMPLOYEE");
        }
        db.update(
                "INSERT INTO"
                    + " fams_inventory_task(id,name,task_status,range_json,starts_at,due_at,creator_account_id,created_at,updated_at,published_at,assignment_version)"
                    + " VALUES (946000,'Readiness task','IN_PROGRESS','{}',DATE_SUB(NOW(3),INTERVAL"
                    + " 1 HOUR),DATE_ADD(NOW(3),INTERVAL 1"
                    + " DAY),946001,NOW(3),NOW(3),DATE_SUB(NOW(3),INTERVAL 1 HOUR),1)");
        db.update(
                "INSERT INTO fams_inventory_participant(task_id,account_id,first_assigned_at)"
                        + " VALUES (946000,946002,NOW(3)),(946000,946003,NOW(3))");
        var identity = new MiniWechatIdentityPort.Identity("ready-app", "ready-executor");
        db.update(
                "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES"
                        + " (946002,?,NOW(3))",
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
                                                                "ready1",
                                                                "password",
                                                                PASSWORD))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode(), login.body());
        cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        csrf = JSON.readTree(login.body()).path("csrfToken").asText();
        db.update(
                "INSERT INTO"
                    + " fams_asset_category(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (946000,'READY','READY','Readiness"
                    + " category','ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_warehouse(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (946000,'READY','READY','Readiness"
                    + " warehouse','ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_location(id,code,code_normalized,name,location_type,warehouse_id,status,created_at,updated_at)"
                    + " VALUES (946000,'READY','READY','Readiness"
                    + " room','BIN',946000,'ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_business_parameter_version(id,version,status,effective_at,actor_account_id,actor_username,snapshot_json,created_at)"
                    + " VALUES"
                    + " (946000,946000,'ACTIVE',NOW(3),946001,'ready1',JSON_OBJECT('timezone','Asia/Shanghai'),NOW(3))");
        for (int i = 10; i <= 11; i++) {
            db.update(
                    "INSERT INTO"
                        + " fams_asset(id,asset_code,name,category_id,lifecycle_status,exception_status,card_stage,custody_type,storage_warehouse_id,current_location_id,created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by)"
                        + " VALUES (?,?,'Readiness"
                        + " device',946000,'IN_STOCK','NORMAL','ACTIVE','STORAGE',946000,946000,CURRENT_DATE,946000,946001,'ready1',NOW(3),NOW(3),946001,946001)",
                    946000 + i,
                    "READY-" + i);
            db.update(
                    "INSERT INTO"
                        + " fams_inventory_item(id,task_id,asset_id,asset_version,snapshot_json,executor_account_id,assignment_version)"
                        + " SELECT ?,946000,asset_id,asset_version,snapshot_json,946002,1 FROM"
                        + " fams_asset_inventory_source_v1 WHERE asset_id=?",
                    946000 + i,
                    946000 + i);
        }
        db.update("UPDATE fams_inventory_task SET expected_count=2,timezone_id='Asia/Shanghai',"
                + "parameter_version_id=946000 WHERE id=946000");
    }

    @AfterEach
    void cleanup() {
        db.update("DELETE FROM fams_attachment WHERE business_type='LOST_REPORT' AND uploader_account_id=946001");
        db.update("DELETE FROM fams_attachment_scope WHERE business_type='LOST_REPORT' AND business_id IN"
                + " (SELECT id FROM fams_loss_report WHERE asset_id=946010)");
        db.update("DELETE FROM fams_todo WHERE business_type='LOST_REPORT' AND business_id IN"
                + " (SELECT id FROM fams_loss_report WHERE asset_id=946010)");
        db.update("DELETE FROM fams_asset_business_lock WHERE asset_id=946010 AND business_type='LOST_REPORT'");
        for (String table : java.util.List.of("fams_loss_receipt", "fams_loss_event", "fams_loss_inventory_source")) {
            db.update("DELETE FROM " + table + " WHERE report_id IN (SELECT id FROM fams_loss_report WHERE asset_id=946010)");
        }
        db.update("DELETE FROM fams_loss_report WHERE asset_id=946010");
        db.update("DELETE FROM fams_asset_timeline_event WHERE asset_id=946010");
        for (String table : java.util.List.of("fams_inventory_sealed_sync", "fams_inventory_sealed_evidence",
                "fams_inventory_sealed_item", "fams_inventory_sealed_report")) {
            db.update("DELETE FROM " + table + " WHERE task_id=946000");
        }
        for (String table :
                java.util.List.of(
                        "fams_inventory_unobserved_reason",
                        "fams_inventory_selection_event",
                        "fams_inventory_evidence_disposition",
                        "fams_inventory_review_decision",
                        "fams_inventory_review_subject",
                        "fams_inventory_review_receipt",
                        "fams_inventory_sync_receipt",
                        "fams_inventory_sync_declaration")) {
            db.update("DELETE FROM " + table + " WHERE task_id=946000");
        }
        db.update("DELETE FROM fams_inventory_round_result WHERE item_id IN (946010,946011)");
        db.update("DELETE FROM fams_inventory_item_review WHERE task_id=946000");
        db.update("DELETE FROM fams_inventory_observation WHERE task_id=946000");
        for (String table :
                java.util.List.of(
                        "fams_inventory_assignment",
                        "fams_inventory_participant",
                        "fams_inventory_task_event",
                        "fams_inventory_item")) {
            db.update("DELETE FROM " + table + " WHERE task_id=946000");
        }
        db.update("DELETE FROM fams_inventory_task WHERE id=946000");
        db.update(
                "DELETE FROM fams_inventory_clock_calibration WHERE actor_account_id BETWEEN 946001"
                        + " AND 946003");
        db.update(
                "DELETE FROM fams_idempotency_record WHERE actor_account_id BETWEEN 946001 AND"
                        + " 946003");
        db.update("DELETE FROM fams_audit_event WHERE actor_account_id BETWEEN 946001 AND 946003");
        db.update("DELETE FROM fams_asset WHERE id IN (946010,946011)");
        db.update("DELETE FROM fams_business_parameter_version WHERE id=946000");
        db.update("DELETE FROM fams_location WHERE id=946000");
        db.update("DELETE FROM fams_warehouse WHERE id=946000");
        db.update("DELETE FROM fams_asset_category WHERE id=946000");
        db.update("DELETE FROM fams_mini_session WHERE account_id BETWEEN 946001 AND 946003");
        db.update("DELETE FROM fams_mini_binding WHERE account_id BETWEEN 946001 AND 946003");
        db.update("DELETE FROM fams_security_event WHERE account_id BETWEEN 946001 AND 946003");
        db.update("DELETE FROM fams_account_role WHERE account_id BETWEEN 946001 AND 946003");
        db.update("DELETE FROM fams_system_account WHERE id BETWEEN 946001 AND 946003");
        db.update("DELETE FROM fams_employee WHERE id BETWEEN 946001 AND 946003");
        db.update("DELETE FROM fams_organization WHERE id=946000");
    }

    @Test
    void linksMissingReasonAtomicallyAndReopensItAfterRejection() throws Exception {
        var missing = ok(call("POST", "/items/946010/unobserved", body("ASSET_NOT_FOUND"),
                UUID.randomUUID().toString(), 0, false));
        ok(call("POST", "/items/946011/unobserved", body("UNABLE_TO_OBSERVE"),
                UUID.randomUUID().toString(), 1, false));
        ok(call("POST", "/waiting-review", java.util.Map.of("reason", "全部应盘项已有明确原因"),
                UUID.randomUUID().toString(), 2, false));
        var originalReason = db.queryForMap("SELECT * FROM fams_inventory_unobserved_reason WHERE id=?",
                missing.path("reasonId").asLong());
        String path = "/items/946010/missing/" + missing.path("reasonId").asText() + "/loss";
        String key = UUID.randomUUID().toString();
        var facts = java.util.Map.of("assetVersion", 0,
                "lastSeenAt", Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),
                "lastLocation", "原登记仓位", "circumstances", "已与保管人核对并搜索原登记位置仍未找到");
        assertEquals(403, call("POST", path, facts, UUID.randomUUID().toString(), 3, true).statusCode());
        db.execute(
                "CREATE TRIGGER d618_missing_loss_audit_failure BEFORE INSERT ON fams_audit_event FOR"
                        + " EACH ROW BEGIN IF NEW.resource_id=946000 AND NEW.action='INVENTORY_MISSING_REPORTED'"
                        + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned missing loss rollback'; END IF; END");
        try {
            assertEquals(500, call("POST", path, facts, key, 3, false).statusCode());
        } finally {
            db.execute("DROP TRIGGER d618_missing_loss_audit_failure");
        }
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_loss_report WHERE asset_id=946010",
                Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=946010",
                Integer.class));
        var first = ok(call("POST", path, facts, key, 3, false));
        assertEquals(first, ok(call("POST", path, facts, key, 3, false)));
        assertEquals(409, call("POST", path, facts, UUID.randomUUID().toString(), 4, false).statusCode());
        String report = "/loss-reports/" + first.path("reportId").asText();
        assertEquals(missing.path("reasonId").asText(),
                ok(pc("GET", report, null, null, 0)).path("source").path("inventorySource").path("reasonId").asText());
        String sealPath = "/inventory-sealing/tasks/946000";
        assertTrue(ok(pc("GET", sealPath, null, null, 0)).path("blockers").toString()
                .contains("INVENTORY_SEAL_PHYSICAL_MISSING_OPEN"));
        ok(pc("POST", report + "/decision", java.util.Map.of("result", "REJECT", "reason", "需要补充最后见到依据"),
                UUID.randomUUID().toString(), 0));
        var rejected = ok(call("GET", "", null, null, 0, false)).path("items").get(0);
        assertTrue(rejected.path("physicalMissingOpen").asBoolean());
        assertTrue(rejected.path("missingLosses").get(0).path("canSubmit").asBoolean());
        assertEquals("REJECTED", rejected.path("missingLosses").get(0).path("report").path("status").asText());
        var second = runNativeReadiness(missing.path("reasonId").asText());
        assertTrue(second.path("originalKeyPayloadVersionRetained").asBoolean());
        assertTrue(second.path("linkedReportOpens").asBoolean());
        assertNotEquals(first.path("reportId").asText(), second.path("reportId").asText());
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM fams_loss_inventory_source WHERE reason_id=?",
                Integer.class, missing.path("reasonId").asLong()));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=946010"
                + " AND business_type='LOST_REPORT' AND released_at IS NULL", Integer.class));
        assertEquals(originalReason, db.queryForMap("SELECT * FROM fams_inventory_unobserved_reason WHERE id=?",
                missing.path("reasonId").asLong()));

        String secondReport = "/loss-reports/" + second.path("reportId").asText();
        uploadMissingPhoto(secondReport);
        var approved = ok(pc("POST", secondReport + "/decision", java.util.Map.of(
                "result", "APPROVE", "reason", "批准库管按实际查找范围核实"),
                UUID.randomUUID().toString(), 1));
        String adminCookie = cookie, adminCsrf = csrf;
        db.update("INSERT INTO fams_account_role(account_id,role_code,granted_at)"
                + " VALUES (946003,'WAREHOUSE_OPERATOR',NOW(3))");
        var warehouse = http.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
                        java.util.Map.of("username", "ready3", "password", PASSWORD)))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, warehouse.statusCode(), warehouse.body());
        cookie = warehouse.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        csrf = JSON.readTree(warehouse.body()).path("csrfToken").asText();
        var claimed = ok(pc("POST", secondReport + "/claim", null, UUID.randomUUID().toString(),
                approved.path("version").asLong()));
        var parameters = db.queryForMap("SELECT current_version,current_version_id"
                + " FROM fams_business_parameter_state WHERE singleton_id=1");
        db.update("UPDATE fams_business_parameter_state SET current_version=946000,"
                + "current_version_id=946000 WHERE singleton_id=1");
        JsonNode searched;
        try {
            searched = ok(pc("POST", secondReport + "/search", java.util.Map.of(
                    "assetVersion", claimed.path("assetVersion").asLong(),
                    "ownershipVersion", claimed.path("ownershipVersion").asLong(), "lockVersion", 0,
                    "custodianVerification", "已联系实物保管人员逐项核对登记位置",
                    "searchedScope", "检查原登记位置和临时保管区仍未找到",
                    "searchedAt", Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),
                    "nextReviewDate", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(2).toString()),
                    UUID.randomUUID().toString(), claimed.path("version").asLong()));
        } finally {
            db.update("UPDATE fams_business_parameter_state SET current_version=?,current_version_id=?"
                    + " WHERE singleton_id=1", parameters.get("current_version"), parameters.get("current_version_id"));
        }
        assertEquals("SEARCHING", searched.path("status").asText());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_todo WHERE business_type='LOST_REPORT'"
                + " AND business_id=? AND todo_type='SEARCH' AND todo_status='OPEN' AND assignee_account_id=946003",
                Integer.class, second.path("reportId").asLong()));
        cookie = adminCookie;
        csrf = adminCsrf;
        var preflight = ok(pc("GET", sealPath, null, null, 0));
        assertEquals(0, preflight.path("blockerCount").asInt(), preflight.toString());
        var sealed = ok(pc("POST", sealPath + "/seal", java.util.Map.of(
                "reason", "报失已建立经办人和复核时限",
                "synchronizationDigest", preflight.path("synchronizationDigest").asText(),
                "missingSynchronizationReason", "执行人未能提供声明，保留记录并安排后续补证"),
                UUID.randomUUID().toString(), 5));
        assertEquals(2, sealed.path("notObservedCount").asInt());
        assertEquals(0, sealed.path("acceptedCount").asInt());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_asset WHERE id IN (946010,946011)"
                + " AND last_inventory_business_date IS NOT NULL", Integer.class));
    }

    private void uploadMissingPhoto(String report) throws Exception {
        String boundary = "missing-" + UUID.randomUUID();
        var bytes = new java.io.ByteArrayOutputStream();
        bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\";"
                + " filename=\"site.png\"\r\nContent-Type: image/png\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", bytes);
        bytes.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1" + report + "/photos"))
                .header("Cookie", cookie).header("X-CSRF-Token", csrf)
                .header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match-Version", "0")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
    }

    @Test
    void reasonsRemainUnobservedAndOnlyExplicitCompleteResultsEnterWaitingReview()
            throws Exception {
        var assets =
                db.queryForList("SELECT * FROM fams_asset WHERE id IN (946010,946011) ORDER BY id");
        var initial = ok(call("GET", "", null, null, 0, false));
        assertEquals(2, initial.path("missingCount").asInt());
        assertTrue(initial.path("items").get(0).path("item").path("snapshot_json").isObject());
        assertEquals("946010", initial.path("items").get(0).path("id").asText());
        assertEquals(
                409,
                call(
                                "POST",
                                "/waiting-review",
                                java.util.Map.of("reason", "尚有未盘项目不得提前复核"),
                                UUID.randomUUID().toString(),
                                0,
                                false)
                        .statusCode());
        var body = body("UNABLE_TO_OBSERVE");
        String key = UUID.randomUUID().toString();
        var first = ok(call("POST", "/items/946010/unobserved", body, key, 0, false));
        assertEquals(first, ok(call("POST", "/items/946010/unobserved", body, key, 0, false)));
        body.put("reason", "修改原请求原因不得复用原键");
        assertEquals(
                409, call("POST", "/items/946010/unobserved", body, key, 0, false).statusCode());
        var history = ok(call("GET", "/items/946010/unobserved-history", null, null, 0, false));
        assertEquals(1, history.path("total").asInt());
        assertEquals("ready1", history.path("items").get(0).path("actor_username").asText());
        var conflict =
                call(
                        "POST",
                        "/items/946010/unobserved",
                        body("UNABLE_TO_OBSERVE"),
                        UUID.randomUUID().toString(),
                        0,
                        false);
        assertEquals(409, conflict.statusCode(), conflict.body());
        var fields = JSON.readTree(conflict.body()).path("fieldErrors");
        assertEquals("ready1", fields.path("processedBy").asText());
        assertEquals("NOT_OBSERVED", fields.path("method").asText());
        Instant.parse(fields.path("processedAt").asText());
        assertTrue(
                ok(call(
                                "POST",
                                "/items/946011/unobserved",
                                body("ASSET_NOT_FOUND"),
                                UUID.randomUUID().toString(),
                                1,
                                false))
                        .path("physicalMissingOpen")
                        .asBoolean());
        var beforeWaiting = ok(call("GET", "", null, null, 0, false));
        assertEquals(0, beforeWaiting.path("missingCount").asInt());
        assertEquals(2, beforeWaiting.path("notObservedCount").asInt());
        String waitingKey = UUID.randomUUID().toString();
        var waiting = java.util.Map.of("reason", "全部项目已有结果或明确未盘原因");
        var response = ok(call("POST", "/waiting-review", waiting, waitingKey, 2, false));
        assertEquals("WAITING_REVIEW", response.path("status").asText());
        assertEquals(response, ok(call("POST", "/waiting-review", waiting, waitingKey, 2, false)));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT captured_result_count FROM fams_inventory_task WHERE id=946000",
                        Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_item_review WHERE task_id=946000 AND"
                                + " review_status='ACCEPTED'",
                        Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_observation WHERE task_id=946000",
                        Integer.class));
        assertEquals(
                assets,
                db.queryForList(
                        "SELECT * FROM fams_asset WHERE id IN (946010,946011) ORDER BY id"));
    }

    @Test
    void aReviewedRoundRequiresExplicitRecheckAndWaitingReviewCanReturnToCollection()
            throws Exception {
        ok(
                call(
                        "POST",
                        "/items/946010/unobserved",
                        body("EVIDENCE_UNAVAILABLE"),
                        UUID.randomUUID().toString(),
                        0,
                        false));
        var assignment = JSON.createObjectNode();
        assignment.putArray("itemIds").add("946010");
        assignment.put("executorAccountId", "946003");
        assignment.put("recheck", false);
        assignment.put("reason", "不得在原未盘轮次静默重新分配");
        assertEquals(
                409,
                pc(
                                "POST",
                                "/inventory-tasks/946000/assignments",
                                assignment,
                                UUID.randomUUID().toString(),
                                1)
                        .statusCode());
        ok(
                call(
                        "POST",
                        "/items/946011/unobserved",
                        body("UNABLE_TO_OBSERVE"),
                        UUID.randomUUID().toString(),
                        1,
                        false));
        ok(
                call(
                        "POST",
                        "/waiting-review",
                        java.util.Map.of("reason", "已有全部明确原因进入复核"),
                        UUID.randomUUID().toString(),
                        2,
                        false));
        assertEquals(
                409,
                pc(
                                "POST",
                                "/inventory-tasks/946000/assignments",
                                assignment,
                                UUID.randomUUID().toString(),
                                3)
                        .statusCode());
        assignment.put("recheck", true);
        var task =
                ok(
                        pc(
                                "POST",
                                "/inventory-tasks/946000/assignments",
                                assignment,
                                UUID.randomUUID().toString(),
                                3));
        assertEquals("IN_PROGRESS", task.path("task_status").asText());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT recheck_generation FROM fams_inventory_item WHERE id=946010",
                        Integer.class));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_unobserved_reason WHERE"
                                + " task_id=946000",
                        Integer.class));
        var detail = ok(call("GET", "", null, null, 0, false));
        assertEquals(1, detail.path("missingCount").asInt());
        assertEquals(
                1,
                ok(call("GET", "/items/946010/unobserved-history", null, null, 0, false))
                        .path("total")
                        .asInt());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT captured_result_count FROM fams_inventory_task WHERE id=946000",
                        Integer.class));
    }

    @Test
    void unobservedHistoryPreventsCancellationButCanBeTerminatedWithoutReopening()
            throws Exception {
        ok(
                call(
                        "POST",
                        "/items/946010/unobserved",
                        body("UNABLE_TO_OBSERVE"),
                        UUID.randomUUID().toString(),
                        0,
                        false));
        var reason = java.util.Map.of("reason", "明确终止并保留未盘历史");
        assertEquals(
                409,
                pc(
                                "POST",
                                "/inventory-tasks/946000/cancel",
                                reason,
                                UUID.randomUUID().toString(),
                                1)
                        .statusCode());
        assertEquals(
                "TERMINATED",
                ok(pc(
                                "POST",
                                "/inventory-tasks/946000/terminate",
                                reason,
                                UUID.randomUUID().toString(),
                                1))
                        .path("task_status")
                        .asText());
        assertEquals(
                409,
                call(
                                "POST",
                                "/items/946011/unobserved",
                                body("UNABLE_TO_OBSERVE"),
                                UUID.randomUUID().toString(),
                                2,
                                false)
                        .statusCode());
        assertEquals(
                "TERMINATED",
                db.queryForObject(
                        "SELECT task_status FROM fams_inventory_task WHERE id=946000",
                        String.class));
    }

    @Test
    void currentRoleAndVersionsPrecedeOriginalRepliesAndInvalidReasonsWriteNothing()
            throws Exception {
        assertEquals(403, call("GET", "", null, null, 0, true).statusCode());
        var invalid = body("OTHER");
        assertEquals(
                400,
                call(
                                "POST",
                                "/items/946010/unobserved",
                                invalid,
                                UUID.randomUUID().toString(),
                                0,
                                false)
                        .statusCode());
        invalid = body("UNABLE_TO_OBSERVE");
        invalid.put("assetVersion", 99);
        assertEquals(
                409,
                call(
                                "POST",
                                "/items/946010/unobserved",
                                invalid,
                                UUID.randomUUID().toString(),
                                0,
                                false)
                        .statusCode());
        invalid = body("UNABLE_TO_OBSERVE");
        invalid.put("evidence", "短");
        assertEquals(
                400,
                call(
                                "POST",
                                "/items/946010/unobserved",
                                invalid,
                                UUID.randomUUID().toString(),
                                0,
                                false)
                        .statusCode());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_unobserved_reason WHERE"
                                + " task_id=946000",
                        Integer.class));
        String key = UUID.randomUUID().toString();
        var body = body("UNABLE_TO_OBSERVE");
        ok(call("POST", "/items/946010/unobserved", body, key, 0, false));
        db.update(
                "DELETE FROM fams_account_role WHERE account_id=946001 AND"
                        + " role_code='ASSET_ADMIN'");
        assertTrue(
                java.util.Set.of(401, 403)
                        .contains(
                                call("POST", "/items/946010/unobserved", body, key, 0, false)
                                        .statusCode()));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_unobserved_reason WHERE"
                                + " task_id=946000",
                        Integer.class));
    }

    @Test
    void auditFailureRollsBackProjectionReasonTaskVersionAndReceipt() throws Exception {
        db.execute(
                "CREATE TRIGGER d618_readiness_audit_failure BEFORE INSERT ON fams_audit_event FOR"
                        + " EACH ROW BEGIN IF NEW.resource_id=946000 AND"
                        + " NEW.action='INVENTORY_NOT_OBSERVED' THEN SIGNAL SQLSTATE '45000' SET"
                        + " MESSAGE_TEXT='owned unobserved rollback'; END IF; END");
        try {
            assertEquals(
                    500,
                    call(
                                    "POST",
                                    "/items/946010/unobserved",
                                    body("UNABLE_TO_OBSERVE"),
                                    UUID.randomUUID().toString(),
                                    0,
                                    false)
                            .statusCode());
        } finally {
            db.execute("DROP TRIGGER d618_readiness_audit_failure");
        }
        for (String table :
                java.util.List.of(
                        "fams_inventory_unobserved_reason",
                        "fams_inventory_item_review",
                        "fams_inventory_review_receipt",
                        "fams_inventory_task_event")) {
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM " + table + " WHERE task_id=946000",
                            Integer.class));
        }
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT version FROM fams_inventory_task WHERE id=946000", Integer.class));
    }

    @Test
    void actualNormalObservationCannotBecomeUnobservedAndActualReviewerIsReturned()
            throws Exception {
        var before =
                db.queryForList("SELECT * FROM fams_asset WHERE id IN (946010,946011) ORDER BY id");
        var now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var calibration =
                ok(
                        request(
                                "POST",
                                "/mini/inventory-observations/calibrations",
                                java.util.Map.of("deviceId", device, "clientTime", now.toString()),
                                null,
                                0,
                                true));
        String eventId = UUID.randomUUID().toString();
        var event = JSON.createObjectNode();
        event.put("eventId", eventId);
        event.put("itemId", "946010");
        event.put("assignmentVersion", 1);
        event.put("recheckGeneration", 0);
        event.put("assetVersion", 0);
        event.put("deviceId", device);
        event.put("source", "SCAN");
        event.put("assetCode", "READY-10");
        event.putObject("actual")
                .put("locationId", "946000")
                .put("custodyType", "STORAGE")
                .put("condition", "GOOD");
        for (String field :
                java.util.List.of("rawScannedAt", "rawObservedAt", "firstPersistedAt")) {
            event.put(field, now.toString());
        }
        event.put("expiresAt", now.plusSeconds(72 * 3600).toString());
        event.put("calibrationId", calibration.path("calibrationId").asText());
        event.put("clockAnomaly", false);
        event.putArray("photoIds");
        assertEquals(
                "ACCEPTED",
                ok(request(
                                "POST",
                                "/mini/inventory-observations/tasks/946000/events",
                                event,
                                eventId,
                                1,
                                true))
                        .path("outcome")
                        .asText());
        var raw = db.queryForList("SELECT * FROM fams_inventory_observation WHERE task_id=946000");
        assertEquals(
                409,
                call(
                                "POST",
                                "/items/946010/unobserved",
                                body("UNABLE_TO_OBSERVE"),
                                UUID.randomUUID().toString(),
                                1,
                                false)
                        .statusCode());
        var accepted =
                java.util.Map.of(
                        "items",
                        java.util.List.of(
                                java.util.Map.of(
                                        "itemId",
                                        "946010",
                                        "recheckGeneration",
                                        0,
                                        "reviewVersion",
                                        0,
                                        "assetVersion",
                                        0)),
                        "reason",
                        "现场实盘与当前账面核对一致");
        assertEquals(
                1,
                ok(pc(
                                "POST",
                                "/inventory-reviews/tasks/946000/normal-acceptance",
                                accepted,
                                UUID.randomUUID().toString(),
                                1))
                        .path("accepted")
                        .asInt());
        var refused =
                call(
                        "POST",
                        "/items/946010/unobserved",
                        body("UNABLE_TO_OBSERVE"),
                        UUID.randomUUID().toString(),
                        2,
                        false);
        assertEquals(409, refused.statusCode(), refused.body());
        assertEquals(
                "ACCEPT_NORMAL",
                JSON.readTree(refused.body()).path("fieldErrors").path("method").asText());
        ok(
                call(
                        "POST",
                        "/items/946011/unobserved",
                        body("UNABLE_TO_OBSERVE"),
                        UUID.randomUUID().toString(),
                        2,
                        false));
        assertEquals(
                "WAITING_REVIEW",
                ok(call(
                                "POST",
                                "/waiting-review",
                                java.util.Map.of("reason", "正常观察及明确未盘分别进入复核"),
                                UUID.randomUUID().toString(),
                                3,
                                false))
                        .path("status")
                        .asText());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT captured_result_count FROM fams_inventory_task WHERE id=946000",
                        Integer.class));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_item_review WHERE task_id=946000 AND"
                                + " review_status='ACCEPTED'",
                        Integer.class));
        assertEquals(
                raw,
                db.queryForList("SELECT * FROM fams_inventory_observation WHERE task_id=946000"));
        assertEquals(
                before,
                db.queryForList(
                        "SELECT * FROM fams_asset WHERE id IN (946010,946011) ORDER BY id"));
    }

    @Test
    void nativeHandlerRecoversActualCommittedUnobservedReplyThenEntersWaitingWithoutClosingMissing()
            throws Exception {
        var proof = runNativeReadiness(null);
        assertTrue(proof.path("nativeUnobservedOriginalReplyRecovered").asBoolean());
        assertTrue(proof.path("originalKeyPayloadVersionRetained").asBoolean());
        assertTrue(proof.path("physicalMissingStillOpen").asBoolean());
        System.out.println("D6-18B2a native readiness runtime proof: " + proof);
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_unobserved_reason WHERE"
                                + " task_id=946000",
                        Integer.class));
        assertEquals(
                3,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_review_receipt WHERE task_id=946000",
                        Integer.class));
        assertEquals(
                "WAITING_REVIEW",
                db.queryForObject(
                        "SELECT task_status FROM fams_inventory_task WHERE id=946000",
                        String.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT captured_result_count FROM fams_inventory_task WHERE id=946000",
                        Integer.class));
    }

    private JsonNode runNativeReadiness(String lossReason) throws Exception {
        var identity = new MiniWechatIdentityPort.Identity("readiness-app", "readiness-admin");
        db.update(
                "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES"
                        + " (946001,?,NOW(3))",
                sessions.identityHash(identity));
        var grant = sessions.start(identity, UUID.randomUUID());
        var script =
                java.nio.file.Path.of(System.getProperty("user.dir"))
                        .getParent()
                        .resolve("apps/miniprogram/test/runtime-inventory-readiness-http.mjs");
        var builder = new ProcessBuilder("rtk", "proxy", "node", script.toString());
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_INVENTORY_TASK", "946000");
        builder.environment().put("FAMS_TEST_READINESS_FIRST", "946010");
        builder.environment().put("FAMS_TEST_READINESS_SECOND", "946011");
        if (lossReason != null) {
            builder.environment().put("FAMS_TEST_READINESS_LOSS_REASON", lossReason);
        }
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
        var process = builder.redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
            String output =
                    new String(
                            process.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            return JSON.readTree(output.strip());
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void twoAdministratorsCommitOneUnobservedConclusionAndAuditTheOther() throws Exception {
        db.update(
                "INSERT INTO fams_account_role(account_id,role_code,granted_at) VALUES"
                        + " (946003,'ASSET_ADMIN',NOW(3))");
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
                                                                "ready3",
                                                                "password",
                                                                PASSWORD))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        var other = ok(login);
        String
                otherCookie =
                        login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                otherCsrf = other.path("csrfToken").asText();
        var first =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return call(
                                        "POST",
                                        "/items/946010/unobserved",
                                        body("UNABLE_TO_OBSERVE"),
                                        UUID.randomUUID().toString(),
                                        0,
                                        false);
                            } catch (Exception failure) {
                                throw new IllegalStateException(failure);
                            }
                        });
        var second =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                var input = body("EVIDENCE_UNAVAILABLE");
                                input.put("reason", "另一管理员提交不同的未盘处理依据");
                                return http.send(
                                        HttpRequest.newBuilder(
                                                        URI.create(
                                                                "http://127.0.0.1:"
                                                                        + port
                                                                        + "/api/v1/inventory-readiness/tasks/946000/items/946010/unobserved"))
                                                .header("Cookie", otherCookie)
                                                .header("X-CSRF-Token", otherCsrf)
                                                .header(
                                                        "Idempotency-Key",
                                                        UUID.randomUUID().toString())
                                                .header("If-Match-Version", "0")
                                                .header("Content-Type", "application/json")
                                                .POST(
                                                        HttpRequest.BodyPublishers.ofString(
                                                                JSON.writeValueAsString(input)))
                                                .build(),
                                        HttpResponse.BodyHandlers.ofString());
                            } catch (Exception failure) {
                                throw new IllegalStateException(failure);
                            }
                        });
        var replies = java.util.List.of(first.get(), second.get());
        assertEquals(
                java.util.List.of(200, 409),
                replies.stream().map(HttpResponse::statusCode).sorted().toList());
        var rejected =
                replies.stream().filter(row -> row.statusCode() == 409).findFirst().orElseThrow();
        var fields = JSON.readTree(rejected.body()).path("fieldErrors");
        assertTrue(
                java.util.Set.of("ready1", "ready3").contains(fields.path("processedBy").asText()));
        java.time.Instant.parse(fields.path("processedAt").asText());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_unobserved_reason WHERE"
                                + " task_id=946000",
                        Integer.class));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_audit_event WHERE resource_id=946000 AND"
                                + " action='INVENTORY_READINESS_CONFLICT_REJECTED'",
                        Integer.class));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT version FROM fams_inventory_task WHERE id=946000", Integer.class));
    }

    @Test
    void waitingReviewAuditFailureKeepsTaskInProgressAndPreservesOnlyOriginalReasonReceipts()
            throws Exception {
        ok(
                call(
                        "POST",
                        "/items/946010/unobserved",
                        body("UNABLE_TO_OBSERVE"),
                        UUID.randomUUID().toString(),
                        0,
                        false));
        ok(
                call(
                        "POST",
                        "/items/946011/unobserved",
                        body("UNABLE_TO_OBSERVE"),
                        UUID.randomUUID().toString(),
                        1,
                        false));
        db.execute(
                "CREATE TRIGGER d618_waiting_audit_failure BEFORE INSERT ON fams_audit_event FOR"
                        + " EACH ROW BEGIN IF NEW.resource_id=946000 AND"
                        + " NEW.action='INVENTORY_WAITING_REVIEW' THEN SIGNAL SQLSTATE '45000' SET"
                        + " MESSAGE_TEXT='owned waiting rollback'; END IF; END");
        try {
            assertEquals(
                    500,
                    call(
                                    "POST",
                                    "/waiting-review",
                                    java.util.Map.of("reason", "等待复核故障必须整体回滚"),
                                    UUID.randomUUID().toString(),
                                    2,
                                    false)
                            .statusCode());
        } finally {
            db.execute("DROP TRIGGER d618_waiting_audit_failure");
        }
        assertEquals(
                "IN_PROGRESS",
                db.queryForObject(
                        "SELECT task_status FROM fams_inventory_task WHERE id=946000",
                        String.class));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT version FROM fams_inventory_task WHERE id=946000", Integer.class));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_review_receipt WHERE task_id=946000",
                        Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_task_event WHERE task_id=946000 AND"
                                + " action='INVENTORY_WAITING_REVIEW'",
                        Integer.class));
    }

    @Test
    void originalUnobservedReceiptSurvivesActualCommittingJvmForcedDeathAndFreshJvm()
            throws Exception {
        var before = db.queryForList("SELECT * FROM fams_asset WHERE id IN (946010,946011) ORDER BY id");
        replayFromFreshJvm(null, 0, JSON.writeValueAsString(body("UNABLE_TO_OBSERVE")));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_inventory_unobserved_reason WHERE task_id=946000",
                Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_inventory_item_review"
                + " WHERE task_id=946000 AND review_status='NOT_OBSERVED'", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_inventory_review_receipt WHERE task_id=946000",
                Integer.class));
        assertEquals(0, db.queryForObject("SELECT captured_result_count FROM fams_inventory_task WHERE id=946000",
                Integer.class));
        assertEquals(before, db.queryForList("SELECT * FROM fams_asset WHERE id IN (946010,946011) ORDER BY id"));
        System.out.println("D6-18B2a JVM proof: committingJvmKilled=true freshJvmOriginalReply=true"
                + " singleUnobservedReason=true collectionAndAssetsUnchanged=true");
    }

    @Test
    void inventoryLossReceiptSurvivesActualCommittingJvmForcedDeathAndFreshJvm() throws Exception {
        var missing = ok(call("POST", "/items/946010/unobserved", body("ASSET_NOT_FOUND"),
                UUID.randomUUID().toString(), 0, false));
        String reason = missing.path("reasonId").asText();
        var originalReason = db.queryForMap("SELECT * FROM fams_inventory_unobserved_reason WHERE id=?",
                Long.parseLong(reason));
        replayFromFreshJvm(reason, 1, JSON.writeValueAsString(java.util.Map.of("assetVersion", 0,
                "lastSeenAt", Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),
                "lastLocation", "原登记仓位", "circumstances", "核对原登记位置和保管人员后仍未找到实物")));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_loss_report WHERE asset_id=946010",
                Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_loss_inventory_source WHERE reason_id=?",
                Integer.class, Long.parseLong(reason)));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_asset_business_lock"
                + " WHERE asset_id=946010 AND business_type='LOST_REPORT' AND released_at IS NULL", Integer.class));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM fams_inventory_review_receipt WHERE task_id=946000",
                Integer.class));
        assertEquals(originalReason, db.queryForMap("SELECT * FROM fams_inventory_unobserved_reason WHERE id=?",
                Long.parseLong(reason)));
        System.out.println("D6-18C JVM proof: committingJvmKilled=true freshJvmOriginalReply=true"
                + " singleReportSourceAndActiveLock=true originalReasonUnchanged=true");
    }

    private void replayFromFreshJvm(String lossReason, long version, String body) throws Exception {
        String originalKey = UUID.randomUUID().toString();
        var state = java.nio.file.Files.createTempDirectory("fams-unobserved-jvm-");
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
                                "com.fams.auth.InventoryReadinessProcess");
                builder.environment().put("FAMS_TEST_READINESS_TASK", "946000");
                builder.environment().put("FAMS_TEST_READINESS_ITEM", "946010");
                if (lossReason != null) {
                    builder.environment().put("FAMS_TEST_READINESS_LOSS_REASON", lossReason);
                }
                builder.environment().put("FAMS_TEST_READINESS_COOKIE", cookie);
                builder.environment().put("FAMS_TEST_READINESS_CSRF", csrf);
                builder.environment().put("FAMS_TEST_READINESS_KEY", originalKey);
                builder.environment().put("FAMS_TEST_READINESS_VERSION", Long.toString(version));
                builder.environment().put("FAMS_TEST_READINESS_BODY", body);
                builder.environment().put("FAMS_TEST_READINESS_STAGE", stage);
                var proofPath = state.resolve(stage + ".json");
                var logPath = state.resolve(stage + ".log");
                builder.environment().put("FAMS_TEST_READINESS_PROOF", proofPath.toString());
                if (first != null) {
                    builder.environment()
                            .put("FAMS_TEST_READINESS_ORIGINAL", first.path("response").toString());
                }
                process =
                        builder.redirectErrorStream(true).redirectOutput(logPath.toFile()).start();
                long deadline =
                        System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
                while (!java.nio.file.Files.exists(proofPath)) {
                    assertTrue(process.isAlive(), java.nio.file.Files.readString(logPath));
                    assertTrue(
                            System.nanoTime() < deadline, "Owned readiness JVM startup deadline");
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

    private tools.jackson.databind.node.ObjectNode body(String kind) {
        var body = JSON.createObjectNode();
        body.put("recheckGeneration", 0);
        body.put("assetVersion", 0);
        body.put("kind", kind);
        body.put("reason", "现场条件未能取得有效实盘观察");
        body.put("evidence", "现场负责人员说明及受限环境记录，保留明确未盘依据");
        return body;
    }

    private JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> call(
            String method, String suffix, Object body, String key, long version, boolean mini)
            throws Exception {
        return request(
                method,
                (mini ? "/mini" : "") + "/inventory-readiness/tasks/946000" + suffix,
                body,
                key,
                version,
                mini);
    }

    private HttpResponse<String> pc(
            String method, String path, Object body, String key, long version) throws Exception {
        return request(method, path, body, key, version, false);
    }

    private HttpResponse<String> request(
            String method, String path, Object body, String key, long version, boolean mini)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path));
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
