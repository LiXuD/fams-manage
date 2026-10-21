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
            HandoverHttpTest.MiniConfiguration.class,
            InventoryLateHttpRuntimeCase.TimeConfiguration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=fams-product",
            "spring.flyway.locations=classpath:db/migration/d6_04",
            "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
            "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra",
            "fams.inventory.start-poll-ms=3600000"
        })
@EnabledIfSystemProperty(named = "d6.18.inventory.late.runtime", matches = "true")
class InventoryLateHttpRuntimeCase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    @Autowired JdbcTemplate db;
    @Autowired MiniSessionService sessions;
    @Autowired TestClock testClock;
    private long taskUnderTest = 948000;
    private java.util.Map<String, Object> originalParameterState;

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
        testClock.elapsed.set(java.time.Duration.ZERO);
        originalParameterState =
                db.queryForMap(
                        "SELECT current_version,current_version_id FROM"
                                + " fams_business_parameter_state WHERE singleton_id=1");
        db.update(
                "INSERT INTO"
                    + " fams_organization(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (948000,'SEAL','SEAL','Sealing"
                    + " organization','ACTIVE',NOW(3),NOW(3))");
        for (int i = 1; i <= 3; i++) {
            db.update(
                    "INSERT INTO"
                        + " fams_employee(id,employee_no,employee_no_normalized,name,organization_id,status,created_at,updated_at)"
                        + " VALUES (?,?,?, ?,948000,'ACTIVE',NOW(3),NOW(3))",
                    948000 + i,
                    "seal" + i,
                    "SEAL" + i,
                    "Sealing " + i);
            db.update(
                    "INSERT INTO"
                        + " fams_system_account(id,employee_id,username,username_normalized,password_hash,status,initial_password_required,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,'ENABLED',FALSE,NOW(3),NOW(3))",
                    948000 + i,
                    948000 + i,
                    "seal" + i,
                    "SEAL" + i,
                    new BCryptPasswordEncoder(4).encode(PASSWORD));
            db.update(
                    "INSERT INTO fams_account_role(account_id,role_code,granted_at) VALUES"
                            + " (?,?,NOW(3))",
                    948000 + i,
                    i == 2 ? "EMPLOYEE" : "ASSET_ADMIN");
        }
        db.update(
                "INSERT INTO"
                    + " fams_inventory_task(id,name,task_status,range_json,starts_at,due_at,creator_account_id,created_at,updated_at,published_at,assignment_version)"
                    + " VALUES (948000,'Sealing task','IN_PROGRESS','{}',DATE_SUB(NOW(3),INTERVAL 1"
                    + " HOUR),DATE_ADD(NOW(3),INTERVAL 1"
                    + " DAY),948001,NOW(3),NOW(3),DATE_SUB(NOW(3),INTERVAL 1 HOUR),1)");
        db.update(
                "INSERT INTO fams_inventory_participant(task_id,account_id,first_assigned_at)"
                        + " VALUES (948000,948002,NOW(3)),(948000,948003,NOW(3))");
        var identity = new MiniWechatIdentityPort.Identity("seal-app", "seal-executor");
        db.update(
                "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES"
                        + " (948002,?,NOW(3))",
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
                                                                "seal1",
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
                    + " VALUES (948000,'SEAL','SEAL','Sealing category','ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_warehouse(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (948000,'SEAL','SEAL','Sealing warehouse','ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_location(id,code,code_normalized,name,location_type,warehouse_id,status,created_at,updated_at)"
                    + " VALUES (948000,'SEAL','SEAL','Sealing"
                    + " room','BIN',948000,'ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_business_parameter_version(id,version,status,effective_at,actor_account_id,actor_username,snapshot_json,created_at)"
                    + " VALUES"
                    + " (948000,948000,'ACTIVE',NOW(3),948001,'seal1',JSON_OBJECT('timezone','Asia/Shanghai'),NOW(3))");
        db.update(
                "UPDATE fams_business_parameter_state SET"
                        + " current_version=948000,current_version_id=948000 WHERE singleton_id=1");
        for (int i = 10; i <= 11; i++) {
            db.update(
                    "INSERT INTO"
                        + " fams_asset(id,asset_code,name,category_id,lifecycle_status,exception_status,card_stage,custody_type,storage_warehouse_id,current_location_id,created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by)"
                        + " VALUES (?,?,'Sealing"
                        + " device',948000,'IN_STOCK','NORMAL','ACTIVE','STORAGE',948000,948000,CURRENT_DATE,948000,948001,'seal1',NOW(3),NOW(3),948001,948001)",
                    948000 + i,
                    "SEAL-" + i);
            db.update(
                    "INSERT INTO"
                        + " fams_inventory_item(id,task_id,asset_id,asset_version,snapshot_json,executor_account_id,assignment_version)"
                        + " SELECT ?,948000,asset_id,asset_version,snapshot_json,948002,1 FROM"
                        + " fams_asset_inventory_source_v1 WHERE asset_id=?",
                    948000 + i,
                    948000 + i);
        }
        db.update(
                "UPDATE fams_inventory_task SET"
                    + " expected_count=2,timezone_id='Asia/Shanghai',parameter_version_id=948000"
                    + " WHERE id=948000");
    }

    @AfterEach
    void cleanup() {
        String tasks = " IN (SELECT id FROM fams_inventory_task WHERE creator_account_id=948001)";
        db.update(
                "DELETE FROM fams_todo WHERE business_type='INVENTORY_LATE' AND business_id IN"
                        + " (SELECT observation_id FROM fams_inventory_late_case WHERE task_id"
                        + tasks
                        + ")");
        db.update(
                "DELETE FROM fams_inventory_late_decision WHERE observation_id IN (SELECT"
                        + " observation_id FROM fams_inventory_late_case WHERE task_id"
                        + tasks
                        + ")");
        for (String table :
                java.util.List.of(
                        "fams_inventory_late_case",
                        "fams_inventory_sealed_item",
                        "fams_inventory_sealed_evidence",
                        "fams_inventory_sealed_sync",
                        "fams_inventory_sealed_report",
                        "fams_inventory_unobserved_reason",
                        "fams_inventory_selection_event",
                        "fams_inventory_evidence_disposition",
                        "fams_inventory_review_decision",
                        "fams_inventory_review_subject",
                        "fams_inventory_review_receipt",
                        "fams_inventory_sync_receipt",
                        "fams_inventory_sync_declaration")) {
            db.update("DELETE FROM " + table + " WHERE task_id" + tasks);
        }
        db.update(
                "DELETE FROM fams_inventory_round_result WHERE item_id IN (SELECT id FROM"
                        + " fams_inventory_item WHERE task_id"
                        + tasks
                        + ")");
        db.update("DELETE FROM fams_inventory_item_review WHERE task_id" + tasks);
        db.update("DELETE FROM fams_inventory_observation WHERE task_id" + tasks);
        for (String table :
                java.util.List.of(
                        "fams_inventory_assignment",
                        "fams_inventory_participant",
                        "fams_inventory_task_event",
                        "fams_inventory_item")) {
            db.update("DELETE FROM " + table + " WHERE task_id" + tasks);
        }
        db.update(
                "DELETE FROM fams_inventory_task WHERE creator_account_id=948001 AND"
                        + " copied_from_task_id=948000");
        db.update("DELETE FROM fams_inventory_task WHERE creator_account_id=948001");
        db.update(
                "DELETE FROM fams_inventory_clock_calibration WHERE actor_account_id BETWEEN 948001"
                        + " AND 948003");
        db.update(
                "DELETE FROM fams_idempotency_record WHERE actor_account_id BETWEEN 948001 AND"
                        + " 948003");
        db.update("DELETE FROM fams_audit_event WHERE actor_account_id BETWEEN 948001 AND 948003");
        db.update("DELETE FROM fams_asset WHERE id IN (948010,948011)");
        db.update(
                "UPDATE fams_business_parameter_state SET current_version=?,current_version_id=?"
                        + " WHERE singleton_id=1",
                originalParameterState.get("current_version"),
                originalParameterState.get("current_version_id"));
        db.update("DELETE FROM fams_business_parameter_version WHERE id=948000");
        db.update("DELETE FROM fams_location WHERE id=948000");
        db.update("DELETE FROM fams_warehouse WHERE id=948000");
        db.update("DELETE FROM fams_asset_category WHERE id=948000");
        for (String table :
                java.util.List.of(
                        "fams_mini_session",
                        "fams_mini_binding",
                        "fams_security_event",
                        "fams_account_role")) {
            db.update("DELETE FROM " + table + " WHERE account_id BETWEEN 948001 AND 948003");
        }
        db.update("DELETE FROM fams_system_account WHERE id BETWEEN 948001 AND 948003");
        db.update("DELETE FROM fams_employee WHERE id BETWEEN 948001 AND 948003");
        db.update("DELETE FROM fams_organization WHERE id=948000");
    }

    @Test
    void conflictingLateEvidenceCreatesOneTodoAndSupplementPreservesFrozenReport()
            throws Exception {
        acceptedNormal();
        waiting();
        sealCurrent();
        var report =
                db.queryForMap("SELECT * FROM fams_inventory_sealed_report WHERE task_id=948000");
        var dates =
                db.queryForList(
                        "SELECT id,version,last_inventory_business_date FROM fams_asset WHERE id IN"
                                + " (948010,948011) ORDER BY id");
        long frozenVersion = version();
        var late = observe("948010", "UNKNOWN");
        assertEquals("LATE_REVIEW", late.path("outcome").asText());
        long observation = latestObservation();
        var list = ok(lateCall("GET", "", null, null, 0));
        assertEquals(1, list.path("total").asInt());
        assertEquals(
                "FROZEN_CONFLICT",
                list.path("items").get(0).path("evidence").path("comparison_kind").asText());
        assertTrue(list.path("items").get(0).path("todoOpen").asBoolean());
        String key = UUID.randomUUID().toString();
        var body = note("已核对后续观察时点不同，作为补充依据保留而不覆盖原结果");
        var first =
                ok(lateCall("POST", "/observations/" + observation + "/decisions", body, key, 0));
        assertEquals("REVIEWED", first.path("result").asText());
        assertEquals(
                first,
                ok(lateCall("POST", "/observations/" + observation + "/decisions", body, key, 0)));
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_late_decision WHERE observation_id=?",
                        Long.class,
                        observation));
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_todo WHERE business_type='INVENTORY_LATE' AND"
                                + " business_id=? AND closed_at IS NULL",
                        Long.class,
                        observation));
        assertEquals(frozenVersion, version());
        assertEquals(
                report,
                db.queryForMap("SELECT * FROM fams_inventory_sealed_report WHERE task_id=948000"));
        assertEquals(
                dates,
                db.queryForList(
                        "SELECT id,version,last_inventory_business_date FROM fams_asset WHERE id IN"
                                + " (948010,948011) ORDER BY id"));
        var changed = note("改变载荷不能复用相同请求键");
        assertEquals(
                409,
                lateCall("POST", "/observations/" + observation + "/decisions", changed, key, 0)
                        .statusCode());
    }

    @Test
    void matchingLateFactsRemainInformationalWithoutInventingAConflictTodo() throws Exception {
        acceptedNormal();
        waiting();
        sealCurrent();
        observe("948010");
        long observation = latestObservation();
        var detail = ok(lateCall("GET", "/observations/" + observation, null, null, 0));
        assertEquals(
                "FACTS_MATCH",
                detail.path("case").path("evidence").path("comparison_kind").asText());
        assertEquals("INFORMATION", detail.path("case").path("review").path("result").asText());
        assertTrue(!detail.path("case").path("todoOpen").asBoolean());
        assertTrue(
                !detail.path("case")
                        .path("evidence")
                        .path("snapshot_json")
                        .path("provesOriginalSealCondition")
                        .asBoolean());
    }

    @Test
    void legacyPreparationIsExplicitBoundedAndDeduplicatesWithoutChangingOriginalReceipt()
            throws Exception {
        acceptedNormal();
        waiting();
        sealCurrent();
        observe("948010", "UNKNOWN");
        long observation = latestObservation();
        String raw =
                db.queryForObject(
                        "SELECT CAST(receipt_json AS CHAR) FROM fams_inventory_observation WHERE"
                                + " id=?",
                        String.class,
                        observation);
        db.update(
                "DELETE FROM fams_todo WHERE business_type='INVENTORY_LATE' AND business_id=?",
                observation);
        db.update("DELETE FROM fams_inventory_late_case WHERE observation_id=?", observation);
        assertEquals(0, ok(lateCall("GET", "", null, null, 0)).path("total").asInt());
        String key = UUID.randomUUID().toString();
        var first = ok(lateCall("POST", "/prepare", null, key, version()));
        assertEquals(1, first.path("prepared").asInt());
        assertEquals(first, ok(lateCall("POST", "/prepare", null, key, version())));
        assertEquals(
                0,
                ok(lateCall("POST", "/prepare", null, UUID.randomUUID().toString(), version()))
                        .path("prepared")
                        .asInt());
        assertEquals(
                raw,
                db.queryForObject(
                        "SELECT CAST(receipt_json AS CHAR) FROM fams_inventory_observation WHERE"
                                + " id=?",
                        String.class,
                        observation));
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_todo WHERE business_type='INVENTORY_LATE' AND"
                                + " business_id=? AND closed_at IS NULL",
                        Long.class,
                        observation));
    }

    @Test
    void copyCreatesOnlyDraftAndDurableOriginalDecisionPreventsDuplicateRecheck() throws Exception {
        acceptedNormal();
        waiting();
        sealCurrent();
        observe("948010", "UNKNOWN");
        long observation = latestObservation(), frozenVersion = version();
        var draft = note("创建关联复盘，原冻结报告及后续义务保持");
        draft.put("method", "LINK_RECHECK");
        var currentTask = ok(lateCall("GET", "", null, null, 0)).path("task");
        var recheck = draft.putObject("recheck");
        recheck.put("name", "迟到证据关联复盘");
        recheck.set("range", currentTask.path("range_json"));
        var now = testClock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        recheck.put("startsAt", now.plusSeconds(3600).toString());
        recheck.put("dueAt", now.plusSeconds(86400).toString());
        String key = UUID.randomUUID().toString();
        var first =
                ok(lateCall("POST", "/observations/" + observation + "/decisions", draft, key, 0));
        long linked = Long.parseLong(first.path("linkedTaskId").asText());
        assertEquals(
                "DRAFT",
                db.queryForObject(
                        "SELECT task_status FROM fams_inventory_task WHERE id=?",
                        String.class,
                        linked));
        assertEquals(
                948000L,
                db.queryForObject(
                        "SELECT copied_from_task_id FROM fams_inventory_task WHERE id=?",
                        Long.class,
                        linked));
        assertEquals("FOLLOWUP_OPEN", first.path("result").asText());
        assertTrue(
                ok(lateCall("GET", "/observations/" + observation, null, null, 0))
                        .path("case")
                        .path("todoOpen")
                        .asBoolean());
        assertEquals(frozenVersion, version());
        testClock.elapsed.set(java.time.Duration.ofHours(25));
        relogin();
        assertEquals(
                first,
                ok(lateCall("POST", "/observations/" + observation + "/decisions", draft, key, 0)));
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_task WHERE copied_from_task_id=948000",
                        Long.class));
        var confirm = note("草稿尚未实际完成不能凭关联关闭");
        confirm.put("method", "CONFIRM_RECHECK");
        assertEquals(
                409,
                lateCall(
                                "POST",
                                "/observations/" + observation + "/decisions",
                                confirm,
                                UUID.randomUUID().toString(),
                                1)
                        .statusCode());
    }

    @Test
    void currentRoleIsCheckedBeforeOriginalSupplementReplayAndMiniOrdinaryUserIsRefused()
            throws Exception {
        acceptedNormal();
        waiting();
        sealCurrent();
        observe("948010", "UNKNOWN");
        long observation = latestObservation();
        String key = UUID.randomUUID().toString();
        var body = note("管理员补充核实并保留原报告");
        ok(lateCall("POST", "/observations/" + observation + "/decisions", body, key, 0));
        assertEquals(
                403,
                request("GET", "/mini/inventory-late/tasks/948000", null, null, 0, true)
                        .statusCode());
        db.update(
                "DELETE FROM fams_account_role WHERE account_id=948001 AND"
                        + " role_code='ASSET_ADMIN'");
        assertEquals(
                403,
                lateCall("POST", "/observations/" + observation + "/decisions", body, key, 0)
                        .statusCode());
    }

    @Test
    void timeUncertaintyCannotBecomeAcceptedBySupplementAndExpiredRawCreatesNoLateBody()
            throws Exception {
        acceptedNormal();
        waiting();
        sealCurrent();
        var uncertain = copiedEvent();
        uncertain.put("clockAnomaly", true);
        assertEquals(
                "TIME_REVIEW",
                ok(request(
                                "POST",
                                "/mini/inventory-observations/tasks/948000/events",
                                uncertain,
                                uncertain.path("eventId").asText(),
                                1,
                                true))
                        .path("outcome")
                        .asText());
        long observation = latestObservation();
        assertEquals(
                "TIME_UNVERIFIED",
                ok(lateCall("GET", "/observations/" + observation, null, null, 0))
                        .path("case")
                        .path("evidence")
                        .path("comparison_kind")
                        .asText());
        assertEquals(
                409,
                lateCall(
                                "POST",
                                "/observations/" + observation + "/decisions",
                                note("文字不能修复时间或改变原期限"),
                                UUID.randomUUID().toString(),
                                0)
                        .statusCode());
        var expired = copiedEvent();
        expired.put("clockAnomaly", false);
        testClock.elapsed.set(java.time.Duration.ofHours(73));
        token =
                sessions.start(
                                new MiniWechatIdentityPort.Identity("seal-app", "seal-executor"),
                                UUID.randomUUID())
                        .sessionToken();
        assertEquals(
                "EXPIRED",
                ok(request(
                                "POST",
                                "/mini/inventory-observations/tasks/948000/events",
                                expired,
                                expired.path("eventId").asText(),
                                1,
                                true))
                        .path("outcome")
                        .asText());
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_late_case WHERE task_id=948000",
                        Long.class));
        assertEquals(
                "{}",
                db.queryForObject(
                        "SELECT CAST(facts_json AS CHAR) FROM fams_inventory_observation WHERE"
                                + " id=?",
                        String.class,
                        latestObservation()));
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_todo WHERE business_type='INVENTORY_LATE' AND"
                                + " closed_at IS NULL",
                        Long.class));
    }

    @Test
    void failedSupplementAuditRollsBackCopiedDraftHistoryTodoAndOriginalReceipt() throws Exception {
        acceptedNormal();
        waiting();
        sealCurrent();
        observe("948010", "UNKNOWN");
        long observation = latestObservation();
        var body = note("关联复盘不能在审计失败时留下半完成草稿");
        body.put("method", "LINK_RECHECK");
        var recheck = body.putObject("recheck");
        recheck.put("name", "回滚验证复盘");
        recheck.set(
                "range", ok(lateCall("GET", "", null, null, 0)).path("task").path("range_json"));
        var now = testClock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        recheck.put("startsAt", now.plusSeconds(3600).toString());
        recheck.put("dueAt", now.plusSeconds(86400).toString());
        var report =
                db.queryForMap("SELECT * FROM fams_inventory_sealed_report WHERE task_id=948000");
        db.execute(
                "CREATE TRIGGER owned_late_audit_guard BEFORE INSERT ON fams_audit_event FOR EACH"
                    + " ROW BEGIN IF NEW.action='INVENTORY_LATE_DECIDED' AND NEW.resource_id=948000"
                    + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned late audit rollback';"
                    + " END IF; END");
        try {
            assertEquals(
                    500,
                    lateCall(
                                    "POST",
                                    "/observations/" + observation + "/decisions",
                                    body,
                                    UUID.randomUUID().toString(),
                                    0)
                            .statusCode());
            assertEquals(
                    0L,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_inventory_task WHERE"
                                    + " copied_from_task_id=948000",
                            Long.class));
            assertEquals(
                    0L,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_inventory_late_decision WHERE"
                                    + " observation_id=?",
                            Long.class,
                            observation));
            assertEquals(
                    0L,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_inventory_review_receipt WHERE"
                                    + " task_id=948000 AND operation='LATE_DECISION'",
                            Long.class));
            assertEquals(
                    1L,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_todo WHERE business_type='INVENTORY_LATE'"
                                    + " AND business_id=? AND closed_at IS NULL",
                            Long.class,
                            observation));
            assertEquals(
                    report,
                    db.queryForMap(
                            "SELECT * FROM fams_inventory_sealed_report WHERE task_id=948000"));
        } finally {
            db.execute("DROP TRIGGER owned_late_audit_guard");
        }
        assertEquals(
                "FOLLOWUP_OPEN",
                ok(lateCall(
                                "POST",
                                "/observations/" + observation + "/decisions",
                                body,
                                UUID.randomUUID().toString(),
                                0))
                        .path("result")
                        .asText());
    }

    @Test
    void failedTerminalUploadAuditRollsBackObservationCaseAndTodoTogether() throws Exception {
        acceptedNormal();
        waiting();
        sealCurrent();
        var raw = copiedEvent();
        ((tools.jackson.databind.node.ObjectNode) raw.path("actual"))
                .put("condition", "UNKNOWN")
                .put("unknownReason", "新的现场线索仍须独立核实");
        long before =
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_observation WHERE task_id=948000",
                        Long.class);
        db.execute(
                "CREATE TRIGGER owned_late_upload_guard BEFORE INSERT ON fams_audit_event FOR EACH"
                    + " ROW BEGIN IF NEW.action='INVENTORY_OBSERVED' AND NEW.resource_id=948000"
                    + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned late upload rollback';"
                    + " END IF; END");
        try {
            assertEquals(
                    500,
                    request(
                                    "POST",
                                    "/mini/inventory-observations/tasks/948000/events",
                                    raw,
                                    raw.path("eventId").asText(),
                                    1,
                                    true)
                            .statusCode());
            assertEquals(
                    before,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_inventory_observation WHERE task_id=948000",
                            Long.class));
            assertEquals(
                    0L,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_inventory_late_case WHERE task_id=948000",
                            Long.class));
            assertEquals(
                    0L,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_todo WHERE business_type='INVENTORY_LATE'",
                            Long.class));
        } finally {
            db.execute("DROP TRIGGER owned_late_upload_guard");
        }
        assertEquals(
                "LATE_REVIEW",
                ok(request(
                                "POST",
                                "/mini/inventory-observations/tasks/948000/events",
                                raw,
                                raw.path("eventId").asText(),
                                1,
                                true))
                        .path("outcome")
                        .asText());
    }

    @Test
    void actualTwoAdministratorsProduceOneVersionAndAuditedProcessingContext() throws Exception {
        acceptedNormal();
        waiting();
        sealCurrent();
        observe("948010", "UNKNOWN");
        long observation = latestObservation();
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
                                                                "seal3",
                                                                "password",
                                                                PASSWORD))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode(), login.body());
        String secondCookie =
                login.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        String secondCsrf = JSON.readTree(login.body()).path("csrfToken").asText();
        var body = note("两个管理员同时逐项补充核实");
        var first =
                http.sendAsync(
                        decisionRequest(observation, body, cookie, csrf),
                        HttpResponse.BodyHandlers.ofString());
        var second =
                http.sendAsync(
                        decisionRequest(observation, body, secondCookie, secondCsrf),
                        HttpResponse.BodyHandlers.ofString());
        var a = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
        var b = second.get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(
                java.util.Set.of(200, 409),
                java.util.Set.of(a.statusCode(), b.statusCode()),
                a.body() + b.body());
        var winner = JSON.readTree(a.statusCode() == 200 ? a.body() : b.body());
        var loser = JSON.readTree(a.statusCode() == 409 ? a.body() : b.body());
        assertEquals(
                winner.path("processedBy").asText(),
                loser.path("fieldErrors").path("processedBy").asText());
        assertEquals("NOTE", loser.path("fieldErrors").path("method").asText());
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_late_decision WHERE observation_id=?",
                        Long.class,
                        observation));
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_audit_event WHERE resource_id=948000 AND"
                                + " action='INVENTORY_LATE_CONFLICT_REJECTED'",
                        Long.class));
    }

    private tools.jackson.databind.node.ObjectNode copiedEvent() {
        var event =
                (tools.jackson.databind.node.ObjectNode)
                        JSON.readTree(
                                db.queryForObject(
                                        "SELECT CAST(facts_json AS CHAR) FROM"
                                            + " fams_inventory_observation WHERE task_id=948000 AND"
                                            + " outcome<>'EXPIRED' ORDER BY id DESC LIMIT 1",
                                        String.class));
        event.put("eventId", UUID.randomUUID().toString());
        return event;
    }

    private HttpRequest decisionRequest(
            long observation, Object body, String sessionCookie, String sessionCsrf) {
        return HttpRequest.newBuilder(
                        URI.create(
                                "http://127.0.0.1:"
                                        + port
                                        + "/api/v1/inventory-late/tasks/948000/observations/"
                                        + observation
                                        + "/decisions"))
                .header("Cookie", sessionCookie)
                .header("X-CSRF-Token", sessionCsrf)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match-Version", "0")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
    }

    @Test
    void originalSupplementSurvivesActualCommittingJvmForcedDeathAndFreshJvm() throws Exception {
        acceptedNormal();
        waiting();
        sealCurrent();
        observe("948010", "UNKNOWN");
        long observation = latestObservation();
        var frozenReport =
                db.queryForMap("SELECT * FROM fams_inventory_sealed_report WHERE task_id=948000");
        String body = JSON.writeValueAsString(note("实际提交进程退出后原补充结论仍只追加一次"));
        String originalKey = UUID.randomUUID().toString();
        long version = 0;
        var before =
                db.queryForList("SELECT * FROM fams_asset WHERE id IN (948010,948011) ORDER BY id");
        var state = java.nio.file.Files.createTempDirectory("fams-sealed-report-jvm-");
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
                                "com.fams.auth.InventoryLateProcess");
                builder.environment().put("FAMS_TEST_LATE_TASK", "948000");
                builder.environment().put("FAMS_TEST_LATE_OBSERVATION", Long.toString(observation));
                builder.environment().put("FAMS_TEST_LATE_COOKIE", cookie);
                builder.environment().put("FAMS_TEST_LATE_CSRF", csrf);
                builder.environment().put("FAMS_TEST_LATE_KEY", originalKey);
                builder.environment().put("FAMS_TEST_LATE_VERSION", Long.toString(version));
                builder.environment().put("FAMS_TEST_LATE_BODY", body);
                builder.environment().put("FAMS_TEST_LATE_STAGE", stage);
                var proofPath = state.resolve(stage + ".json");
                var logPath = state.resolve(stage + ".log");
                builder.environment().put("FAMS_TEST_LATE_PROOF", proofPath.toString());
                if (first != null) {
                    builder.environment()
                            .put("FAMS_TEST_LATE_ORIGINAL", first.path("response").toString());
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
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_inventory_sealed_report WHERE"
                                    + " task_id=948000",
                            Integer.class));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_inventory_review_receipt WHERE"
                                    + " task_id=948000 AND operation='LATE_DECISION'",
                            Integer.class));
            assertEquals(
                    2,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_inventory_sealed_item WHERE task_id=948000",
                            Integer.class));
            assertEquals(
                    2,
                    db.queryForObject(
                            "SELECT captured_result_count FROM fams_inventory_task WHERE id=948000",
                            Integer.class));
            assertEquals(
                    before,
                    db.queryForList(
                            "SELECT * FROM fams_asset WHERE id IN (948010,948011) ORDER BY id"));
            assertEquals(
                    frozenReport,
                    db.queryForMap(
                            "SELECT * FROM fams_inventory_sealed_report WHERE task_id=948000"));
            assertEquals(
                    1L,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_inventory_late_decision WHERE"
                                    + " observation_id=?",
                            Long.class,
                            observation));
            System.out.println(
                    "D6-18B3 JVM proof: committingJvmKilled=true freshJvmOriginalReply=true"
                        + " singleSupplement=true frozenReportDatesAndCollectionUnchanged=true");
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
    void actualNativeOpaqueHandlerRecoversCommittedSupplementAndReadsImmutableHistory()
            throws Exception {
        acceptedNormal();
        waiting();
        sealCurrent();
        observe("948010", "UNKNOWN");
        var before =
                db.queryForMap("SELECT * FROM fams_inventory_sealed_report WHERE task_id=948000");
        var identity = new MiniWechatIdentityPort.Identity("late-app", "late-admin");
        db.update(
                "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES"
                        + " (948001,?,NOW(3))",
                sessions.identityHash(identity));
        var grant = sessions.start(identity, UUID.randomUUID());
        var script =
                java.nio.file.Path.of(System.getProperty("user.dir"))
                        .getParent()
                        .resolve("apps/miniprogram/test/runtime-inventory-late-http.mjs");
        var builder = new ProcessBuilder("rtk", "proxy", "node", script.toString());
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_INVENTORY_TASK", "948000");
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
            var proof = JSON.readTree(output.strip());
            assertTrue(proof.path("nativeSupplementOriginalReplyRecovered").asBoolean());
            assertTrue(proof.path("exactOriginalKeyPayloadAndVersion").asBoolean());
            assertTrue(proof.path("immutableHistoryRead").asBoolean());
            System.out.println("D6-18B3 native supplemental proof: " + proof);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        assertEquals(
                before,
                db.queryForMap("SELECT * FROM fams_inventory_sealed_report WHERE task_id=948000"));
    }

    @Test
    void actualCompletedLinkedRecheckClosesOnlySupplementTodoAndKeepsOriginalFrozenBytes()
            throws Exception {
        acceptedNormal();
        waiting();
        sealCurrent();
        observe("948010", "UNKNOWN");
        long observation = latestObservation(), originalVersion = version();
        var originalReport =
                db.queryForMap("SELECT * FROM fams_inventory_sealed_report WHERE task_id=948000");
        var input = recheckInput("LINK_RECHECK");
        long linked =
                Long.parseLong(
                        ok(lateCall(
                                        "POST",
                                        "/observations/" + observation + "/decisions",
                                        input,
                                        UUID.randomUUID().toString(),
                                        0))
                                .path("linkedTaskId")
                                .asText());
        var preview =
                ok(
                        pc(
                                "POST",
                                "/inventory-tasks/preview",
                                java.util.Map.of(),
                                UUID.randomUUID().toString(),
                                0));
        ok(
                pc(
                        "POST",
                        "/inventory-tasks/" + linked + "/publication",
                        java.util.Map.of(
                                "previewDigest",
                                preview.path("digest").asText(),
                                "expectedCount",
                                preview.path("count").asLong()),
                        UUID.randomUUID().toString(),
                        0));
        var detail = ok(pc("GET", "/inventory-tasks/" + linked, null, null, 0));
        var ids = new java.util.ArrayList<String>();
        for (var row : detail.path("items")) {
            ids.add(row.path("id").asText());
        }
        assertEquals(2, ids.size());
        ok(
                pc(
                        "POST",
                        "/inventory-tasks/" + linked + "/assignments",
                        java.util.Map.of(
                                "itemIds",
                                ids,
                                "executorAccountId",
                                "948002",
                                "recheck",
                                false,
                                "reason",
                                "按关联复盘重新分配并实际采集"),
                        UUID.randomUUID().toString(),
                        detail.path("task").path("version").asLong()));
        detail = ok(pc("GET", "/inventory-tasks/" + linked, null, null, 0));
        if ("READY".equals(detail.path("task").path("task_status").asText())) {
            ok(
                    pc(
                            "POST",
                            "/inventory-tasks/" + linked + "/start",
                            null,
                            UUID.randomUUID().toString(),
                            detail.path("task").path("version").asLong()));
        }
        taskUnderTest = linked;
        detail = ok(pc("GET", "/inventory-tasks/" + linked, null, null, 0));
        var accepted = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (var row : detail.path("items")) {
            var now = testClock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            var calibration =
                    ok(
                            request(
                                    "POST",
                                    "/mini/inventory-observations/calibrations",
                                    java.util.Map.of(
                                            "deviceId", device, "clientTime", now.toString()),
                                    null,
                                    0,
                                    true));
            var event = JSON.createObjectNode();
            event.put("eventId", UUID.randomUUID().toString());
            event.put("itemId", row.path("id").asText());
            event.put("assignmentVersion", row.path("assignment_version").asLong());
            event.put("recheckGeneration", row.path("recheck_generation").asLong());
            event.put("assetVersion", row.path("asset_version").asLong());
            event.put("deviceId", device);
            event.put("source", "SCAN");
            event.put("assetCode", row.path("snapshot_json").path("assetCode").asText());
            event.putObject("actual")
                    .put("locationId", row.path("snapshot_json").path("locationId").asText())
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
                                    "/mini/inventory-observations/tasks/" + linked + "/events",
                                    event,
                                    event.path("eventId").asText(),
                                    row.path("assignment_version").asLong(),
                                    true))
                            .path("outcome")
                            .asText());
            accepted.add(
                    java.util.Map.of(
                            "itemId",
                            row.path("id").asText(),
                            "recheckGeneration",
                            row.path("recheck_generation").asLong(),
                            "reviewVersion",
                            0,
                            "assetVersion",
                            row.path("asset_version").asLong()));
        }
        assertEquals(
                2,
                ok(pc(
                                "POST",
                                "/inventory-reviews/tasks/" + linked + "/normal-acceptance",
                                java.util.Map.of("items", accepted, "reason", "真实关联复盘逐项采信"),
                                UUID.randomUUID().toString(),
                                version()))
                        .path("accepted")
                        .asInt());
        waiting();
        sealCurrent();
        taskUnderTest = 948000;
        var confirm = note("核对关联复盘已实际封存且同一资产已采信，关闭补充待办");
        confirm.put("method", "CONFIRM_RECHECK");
        assertEquals(
                "REVIEWED",
                ok(lateCall(
                                "POST",
                                "/observations/" + observation + "/decisions",
                                confirm,
                                UUID.randomUUID().toString(),
                                1))
                        .path("result")
                        .asText());
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_todo WHERE business_type='INVENTORY_LATE' AND"
                                + " business_id=? AND closed_at IS NULL",
                        Long.class,
                        observation));
        assertEquals(originalVersion, version());
        assertEquals(
                originalReport,
                db.queryForMap("SELECT * FROM fams_inventory_sealed_report WHERE task_id=948000"));
        assertEquals(
                2L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_late_decision WHERE observation_id=?",
                        Long.class,
                        observation));
    }

    @Test
    void cancelledLinkedDraftMustBeExplicitlyReplacedAndKeepsOneOpenTodo() throws Exception {
        acceptedNormal();
        waiting();
        sealCurrent();
        observe("948010", "UNKNOWN");
        long observation = latestObservation();
        var body = recheckInput("LINK_RECHECK");
        long first =
                Long.parseLong(
                        ok(lateCall(
                                        "POST",
                                        "/observations/" + observation + "/decisions",
                                        body,
                                        UUID.randomUUID().toString(),
                                        0))
                                .path("linkedTaskId")
                                .asText());
        var replace = recheckInput("REPLACE_RECHECK");
        assertEquals(
                409,
                lateCall(
                                "POST",
                                "/observations/" + observation + "/decisions",
                                replace,
                                UUID.randomUUID().toString(),
                                1)
                        .statusCode());
        ok(
                pc(
                        "POST",
                        "/inventory-tasks/" + first + "/delete",
                        java.util.Map.of("reason", "原关联复盘草稿不再使用，保留历史并另建"),
                        UUID.randomUUID().toString(),
                        0));
        var second =
                ok(
                        lateCall(
                                "POST",
                                "/observations/" + observation + "/decisions",
                                replace,
                                UUID.randomUUID().toString(),
                                1));
        assertTrue(!Long.toString(first).equals(second.path("linkedTaskId").asText()));
        assertEquals(
                2L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_late_decision WHERE observation_id=?",
                        Long.class,
                        observation));
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_todo WHERE business_type='INVENTORY_LATE' AND"
                                + " business_id=? AND closed_at IS NULL",
                        Long.class,
                        observation));
    }

    @Test
    void validOriginalLateEventReplaysAfter72HoursWithoutSecondCaseOrTodo() throws Exception {
        acceptedNormal();
        waiting();
        sealCurrent();
        var first = observe("948010", "UNKNOWN");
        var event = copiedEvent();
        event.put("eventId", first.path("eventId").asText());
        long observation = latestObservation();
        var originalCase =
                db.queryForMap(
                        "SELECT * FROM fams_inventory_late_case WHERE observation_id=?",
                        observation);
        testClock.elapsed.set(java.time.Duration.ofHours(73));
        token =
                sessions.start(
                                new MiniWechatIdentityPort.Identity("seal-app", "seal-executor"),
                                UUID.randomUUID())
                        .sessionToken();
        assertEquals(
                first,
                ok(
                        request(
                                "POST",
                                "/mini/inventory-observations/tasks/948000/events",
                                event,
                                event.path("eventId").asText(),
                                event.path("assignmentVersion").asLong(),
                                true)));
        assertEquals(
                originalCase,
                db.queryForMap(
                        "SELECT * FROM fams_inventory_late_case WHERE observation_id=?",
                        observation));
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_late_case WHERE task_id=948000",
                        Long.class));
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_todo WHERE business_type='INVENTORY_LATE' AND"
                                + " business_id=? AND closed_at IS NULL",
                        Long.class,
                        observation));
    }

    @Test
    void cancelledSourceOnlyAppendsSupplementWithoutReportOrCounterMutation() throws Exception {
        terminalSupplement("cancel", false);
    }

    @Test
    void terminatedSourceOnlyAppendsSupplementWithoutReportOrCounterMutation() throws Exception {
        terminalSupplement("terminate", true);
    }

    private void terminalSupplement(String action, boolean hasResult) throws Exception {
        if (hasResult) {
            observe("948010");
        }
        ok(
                pc(
                        "POST",
                        "/inventory-tasks/948000/" + action,
                        java.util.Map.of("reason", "原任务明确结束后只保留合法补充线索"),
                        UUID.randomUUID().toString(),
                        version()));
        var task = db.queryForMap("SELECT * FROM fams_inventory_task WHERE id=948000");
        assertEquals("LATE_REVIEW", observe("948010", "UNKNOWN").path("outcome").asText());
        assertEquals(task, db.queryForMap("SELECT * FROM fams_inventory_task WHERE id=948000"));
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_sealed_report WHERE task_id=948000",
                        Long.class));
        assertEquals(1, ok(lateCall("GET", "", null, null, 0)).path("total").asInt());
    }

    private tools.jackson.databind.node.ObjectNode recheckInput(String method) throws Exception {
        var body = note("保留原冻结结果，关联实际复盘并独立核实");
        body.put("method", method);
        var draft = body.putObject("recheck");
        draft.put("name", "实际关联复盘");
        draft.set("range", ok(lateCall("GET", "", null, null, 0)).path("task").path("range_json"));
        var now = testClock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        draft.put("startsAt", now.minusSeconds(3600).toString());
        draft.put("dueAt", now.plusSeconds(86400).toString());
        return body;
    }

    @Test
    void completedUnobservedRecheckCannotCloseSupplementAndMayStartAnotherExplicitRound()
            throws Exception {
        acceptedNormal();
        waiting();
        sealCurrent();
        observe("948010", "UNKNOWN");
        long observation = latestObservation();
        var originalReport =
                db.queryForMap("SELECT * FROM fams_inventory_sealed_report WHERE task_id=948000");
        long linked =
                Long.parseLong(
                        ok(lateCall(
                                        "POST",
                                        "/observations/" + observation + "/decisions",
                                        recheckInput("LINK_RECHECK"),
                                        UUID.randomUUID().toString(),
                                        0))
                                .path("linkedTaskId")
                                .asText());
        var preview =
                ok(
                        pc(
                                "POST",
                                "/inventory-tasks/preview",
                                java.util.Map.of(),
                                UUID.randomUUID().toString(),
                                0));
        ok(
                pc(
                        "POST",
                        "/inventory-tasks/" + linked + "/publication",
                        java.util.Map.of(
                                "expectedCount",
                                preview.path("count").asLong(),
                                "previewDigest",
                                preview.path("digest").asText()),
                        UUID.randomUUID().toString(),
                        0));
        var detail = ok(pc("GET", "/inventory-tasks/" + linked, null, null, 0));
        var ids = new java.util.ArrayList<String>();
        for (var row : detail.path("items")) {
            ids.add(row.path("id").asText());
        }
        ok(
                pc(
                        "POST",
                        "/inventory-tasks/" + linked + "/assignments",
                        java.util.Map.of(
                                "itemIds",
                                ids,
                                "executorAccountId",
                                "948002",
                                "recheck",
                                false,
                                "reason",
                                "明确复盘分配，保留未盘事实"),
                        UUID.randomUUID().toString(),
                        detail.path("task").path("version").asLong()));
        detail = ok(pc("GET", "/inventory-tasks/" + linked, null, null, 0));
        if ("READY".equals(detail.path("task").path("task_status").asText())) {
            ok(
                    pc(
                            "POST",
                            "/inventory-tasks/" + linked + "/start",
                            null,
                            UUID.randomUUID().toString(),
                            detail.path("task").path("version").asLong()));
        }
        taskUnderTest = linked;
        for (String id : ids) {
            ok(
                    pc(
                            "POST",
                            "/inventory-readiness/tasks/" + linked + "/items/" + id + "/unobserved",
                            java.util.Map.of(
                                    "kind",
                                    "UNABLE_TO_OBSERVE",
                                    "recheckGeneration",
                                    0,
                                    "assetVersion",
                                    0,
                                    "reason",
                                    "本轮仍未取得有效实盘观察",
                                    "evidence",
                                    "只保留明确未盘依据，不能据此完成采信"),
                            UUID.randomUUID().toString(),
                            version()));
        }
        waiting();
        sealCurrent();
        taskUnderTest = 948000;
        var confirm = note("新复盘虽已封存，未盘不能冒充有效采信");
        confirm.put("method", "CONFIRM_RECHECK");
        var rejected =
                lateCall(
                        "POST",
                        "/observations/" + observation + "/decisions",
                        confirm,
                        UUID.randomUUID().toString(),
                        1);
        assertEquals(409, rejected.statusCode());
        assertEquals(
                "INVENTORY_LATE_RECHECK_ACCEPTED_ASSET_REQUIRED",
                JSON.readTree(rejected.body()).path("code").asText());
        var another =
                ok(
                        lateCall(
                                "POST",
                                "/observations/" + observation + "/decisions",
                                recheckInput("REPLACE_RECHECK"),
                                UUID.randomUUID().toString(),
                                1));
        assertEquals("FOLLOWUP_OPEN", another.path("result").asText());
        assertTrue(!Long.toString(linked).equals(another.path("linkedTaskId").asText()));
        assertEquals(
                "COMPLETED",
                db.queryForObject(
                        "SELECT task_status FROM fams_inventory_task WHERE id=?",
                        String.class,
                        linked));
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_todo WHERE business_type='INVENTORY_LATE' AND"
                                + " business_id=? AND closed_at IS NULL",
                        Long.class,
                        observation));
        assertEquals(
                originalReport,
                db.queryForMap("SELECT * FROM fams_inventory_sealed_report WHERE task_id=948000"));
    }

    @Test
    void supplementalHistoryUsesStableBoundedPagesWithoutChangingCurrentDecisionOrSource()
            throws Exception {
        acceptedNormal();
        waiting();
        sealCurrent();
        observe("948010", "UNKNOWN");
        long observation = latestObservation();
        long originalVersion = version();
        ok(
                lateCall(
                        "POST",
                        "/observations/" + observation + "/decisions",
                        note("首条补充结论与独立依据"),
                        UUID.randomUUID().toString(),
                        0));
        ok(
                lateCall(
                        "POST",
                        "/observations/" + observation + "/decisions",
                        note("追加第二条依据，原结论不覆盖"),
                        UUID.randomUUID().toString(),
                        1));
        var first =
                ok(
                        lateCall(
                                "GET",
                                "/observations/" + observation + "?page=1&pageSize=1",
                                null,
                                null,
                                0));
        var second =
                ok(
                        lateCall(
                                "GET",
                                "/observations/" + observation + "?page=2&pageSize=1",
                                null,
                                null,
                                0));
        assertEquals(2, first.path("history").path("total").asInt());
        assertEquals(1, first.path("history").path("items").size());
        assertEquals(
                1, first.path("history").path("items").get(0).path("decision_version").asInt());
        assertEquals(
                2, second.path("history").path("items").get(0).path("decision_version").asInt());
        assertEquals(2, first.path("case").path("review").path("decision_version").asInt());
        assertEquals(
                400,
                lateCall("GET", "/observations/" + observation + "?pageSize=101", null, null, 0)
                        .statusCode());
        assertEquals(
                400,
                lateCall("GET", "/observations/" + observation + "?page=1000001", null, null, 0)
                        .statusCode());
        assertEquals(originalVersion, version());
    }

    private void sealCurrent() throws Exception {
        var preflight = ok(call("GET", "", null, null, 0, false));
        ok(
                call(
                        "POST",
                        "/seal",
                        sealBody(preflight, "执行人声明暂缺，保留名单及明确补证安排"),
                        UUID.randomUUID().toString(),
                        version(),
                        false));
    }

    private long latestObservation() {
        return java.util.Objects.requireNonNull(
                db.queryForObject(
                        "SELECT MAX(id) FROM fams_inventory_observation WHERE task_id=948000",
                        Long.class));
    }

    private tools.jackson.databind.node.ObjectNode note(String reason) {
        var body = JSON.createObjectNode();
        body.put("method", "NOTE");
        body.put("reason", reason);
        body.put("evidence", "逐项核对原始观察及冻结采信谱系，补充结论不代替原结果或真实所有者处理");
        return body;
    }

    private HttpResponse<String> lateCall(
            String method, String suffix, Object body, String key, long version) throws Exception {
        return pc(method, "/inventory-late/tasks/948000" + suffix, body, key, version);
    }

    private HttpRequest sealRequest(
            String actorCookie, String actorCsrf, Object body, String key, long version) {
        return HttpRequest.newBuilder(
                        URI.create(
                                "http://127.0.0.1:"
                                        + port
                                        + "/api/v1/inventory-sealing/tasks/948000/seal"))
                .header("Cookie", actorCookie)
                .header("X-CSRF-Token", actorCsrf)
                .header("Idempotency-Key", key)
                .header("If-Match-Version", Long.toString(version))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
    }

    private void relogin() throws Exception {
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
                                                                "seal1",
                                                                "password",
                                                                PASSWORD))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        cookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        csrf = JSON.readTree(response.body()).path("csrfToken").asText();
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class TimeConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        TestClock sealTestClock() {
            return new TestClock(
                    java.time.ZoneOffset.UTC,
                    new java.util.concurrent.atomic.AtomicReference<>(java.time.Duration.ZERO));
        }
    }

    static class TestClock extends java.time.Clock {
        private final java.time.ZoneId zone;
        private final java.util.concurrent.atomic.AtomicReference<java.time.Duration> elapsed;

        TestClock(
                java.time.ZoneId zone,
                java.util.concurrent.atomic.AtomicReference<java.time.Duration> elapsed) {
            this.zone = zone;
            this.elapsed = elapsed;
        }

        @Override
        public java.time.ZoneId getZone() {
            return zone;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId next) {
            return new TestClock(next, elapsed);
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(elapsed.get());
        }
    }

    private long version() {
        return java.util.Objects.requireNonNull(
                db.queryForObject(
                        "SELECT version FROM fams_inventory_task WHERE id=?",
                        Long.class,
                        taskUnderTest));
    }

    private void unobserved(String first, String second) throws Exception {
        ok(
                pc(
                        "POST",
                        "/inventory-readiness/tasks/948000/items/948010/unobserved",
                        body(first),
                        UUID.randomUUID().toString(),
                        version()));
        ok(
                pc(
                        "POST",
                        "/inventory-readiness/tasks/948000/items/948011/unobserved",
                        body(second),
                        UUID.randomUUID().toString(),
                        version()));
    }

    private void waiting() throws Exception {
        ok(
                pc(
                        "POST",
                        "/inventory-readiness/tasks/" + taskUnderTest + "/waiting-review",
                        java.util.Map.of("reason", "所有应盘项均已有实盘结果或明确未盘原因"),
                        UUID.randomUUID().toString(),
                        version()));
    }

    private tools.jackson.databind.node.ObjectNode sealBody(JsonNode preflight, String waiver) {
        var body = JSON.createObjectNode();
        body.put("reason", "逐项核对采信与未盘原因并冻结原报告");
        body.put("synchronizationDigest", preflight.path("synchronizationDigest").asText());
        if (waiver == null) {
            body.putNull("missingSynchronizationReason");
        } else {
            body.put("missingSynchronizationReason", waiver);
        }
        return body;
    }

    private JsonNode observe(String item) throws Exception {
        return observe(item, "GOOD");
    }

    private JsonNode observe(String item, String condition) throws Exception {
        var now = testClock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
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
        event.put("itemId", item);
        event.put("assignmentVersion", 1);
        event.put("recheckGeneration", 0);
        event.put("assetVersion", 0);
        event.put("deviceId", device);
        event.put("source", "SCAN");
        event.put("assetCode", item.endsWith("10") ? "SEAL-10" : "SEAL-11");
        event.putObject("actual")
                .put("locationId", "948000")
                .put("custodyType", "STORAGE")
                .put("condition", condition);
        if ("UNKNOWN".equals(condition)) {
            ((tools.jackson.databind.node.ObjectNode) event.path("actual"))
                    .put("unknownReason", "现场状态无法确定，保留独立核实义务");
        }
        for (String field :
                java.util.List.of("rawScannedAt", "rawObservedAt", "firstPersistedAt")) {
            event.put(field, now.toString());
        }
        event.put("expiresAt", now.plusSeconds(72 * 3600).toString());
        event.put("calibrationId", calibration.path("calibrationId").asText());
        event.put("clockAnomaly", false);
        event.putArray("photoIds");
        return ok(
                request(
                        "POST",
                        "/mini/inventory-observations/tasks/" + taskUnderTest + "/events",
                        event,
                        eventId,
                        1,
                        true));
    }

    private void acceptedNormal() throws Exception {
        assertEquals(
                "ACCEPTED", observe(Long.toString(taskUnderTest + 10)).path("outcome").asText());
        assertEquals(
                "ACCEPTED", observe(Long.toString(taskUnderTest + 11)).path("outcome").asText());
        var items =
                java.util.List.of(
                        java.util.Map.of(
                                "itemId",
                                Long.toString(taskUnderTest + 10),
                                "recheckGeneration",
                                0,
                                "reviewVersion",
                                0,
                                "assetVersion",
                                0),
                        java.util.Map.of(
                                "itemId",
                                Long.toString(taskUnderTest + 11),
                                "recheckGeneration",
                                0,
                                "reviewVersion",
                                0,
                                "assetVersion",
                                0));
        assertEquals(
                2,
                ok(pc(
                                "POST",
                                "/inventory-reviews/tasks/" + taskUnderTest + "/normal-acceptance",
                                java.util.Map.of("items", items, "reason", "现场逐项核实正常与当前责任一致"),
                                UUID.randomUUID().toString(),
                                version()))
                        .path("accepted")
                        .asInt());
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
                (mini ? "/mini" : "") + "/inventory-sealing/tasks/" + taskUnderTest + suffix,
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
