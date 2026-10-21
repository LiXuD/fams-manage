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
            InventorySealHttpRuntimeCase.TimeConfiguration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=fams-product",
            "spring.flyway.locations=classpath:db/migration/d6_04",
            "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
            "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra",
            "fams.inventory.start-poll-ms=3600000"
        })
@EnabledIfSystemProperty(named = "d6.18.inventory.seal.runtime", matches = "true")
class InventorySealHttpRuntimeCase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    @Autowired JdbcTemplate db;
    @Autowired MiniSessionService sessions;
    @Autowired TestClock testClock;
    private long taskUnderTest = 947000;

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
        db.update(
                "INSERT INTO"
                    + " fams_organization(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (947000,'SEAL','SEAL','Sealing"
                    + " organization','ACTIVE',NOW(3),NOW(3))");
        for (int i = 1; i <= 3; i++) {
            db.update(
                    "INSERT INTO"
                        + " fams_employee(id,employee_no,employee_no_normalized,name,organization_id,status,created_at,updated_at)"
                        + " VALUES (?,?,?, ?,947000,'ACTIVE',NOW(3),NOW(3))",
                    947000 + i,
                    "seal" + i,
                    "SEAL" + i,
                    "Sealing " + i);
            db.update(
                    "INSERT INTO"
                        + " fams_system_account(id,employee_id,username,username_normalized,password_hash,status,initial_password_required,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,'ENABLED',FALSE,NOW(3),NOW(3))",
                    947000 + i,
                    947000 + i,
                    "seal" + i,
                    "SEAL" + i,
                    new BCryptPasswordEncoder(4).encode(PASSWORD));
            db.update(
                    "INSERT INTO fams_account_role(account_id,role_code,granted_at) VALUES"
                            + " (?,?,NOW(3))",
                    947000 + i,
                    i == 1 ? "ASSET_ADMIN" : "EMPLOYEE");
        }
        db.update(
                "INSERT INTO"
                    + " fams_inventory_task(id,name,task_status,range_json,starts_at,due_at,creator_account_id,created_at,updated_at,published_at,assignment_version)"
                    + " VALUES (947000,'Sealing task','IN_PROGRESS','{}',DATE_SUB(NOW(3),INTERVAL 1"
                    + " HOUR),DATE_ADD(NOW(3),INTERVAL 1"
                    + " DAY),947001,NOW(3),NOW(3),DATE_SUB(NOW(3),INTERVAL 1 HOUR),1)");
        db.update(
                "INSERT INTO fams_inventory_participant(task_id,account_id,first_assigned_at)"
                        + " VALUES (947000,947002,NOW(3)),(947000,947003,NOW(3))");
        var identity = new MiniWechatIdentityPort.Identity("seal-app", "seal-executor");
        db.update(
                "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES"
                        + " (947002,?,NOW(3))",
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
                    + " VALUES (947000,'SEAL','SEAL','Sealing category','ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_warehouse(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (947000,'SEAL','SEAL','Sealing warehouse','ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_location(id,code,code_normalized,name,location_type,warehouse_id,status,created_at,updated_at)"
                    + " VALUES (947000,'SEAL','SEAL','Sealing"
                    + " room','BIN',947000,'ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_business_parameter_version(id,version,status,effective_at,actor_account_id,actor_username,snapshot_json,created_at)"
                    + " VALUES"
                    + " (947000,947000,'ACTIVE',NOW(3),947001,'seal1',JSON_OBJECT('timezone','Asia/Shanghai'),NOW(3))");
        for (int i = 10; i <= 11; i++) {
            db.update(
                    "INSERT INTO"
                        + " fams_asset(id,asset_code,name,category_id,lifecycle_status,exception_status,card_stage,custody_type,storage_warehouse_id,current_location_id,created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by)"
                        + " VALUES (?,?,'Sealing"
                        + " device',947000,'IN_STOCK','NORMAL','ACTIVE','STORAGE',947000,947000,CURRENT_DATE,947000,947001,'seal1',NOW(3),NOW(3),947001,947001)",
                    947000 + i,
                    "SEAL-" + i);
            db.update(
                    "INSERT INTO"
                        + " fams_inventory_item(id,task_id,asset_id,asset_version,snapshot_json,executor_account_id,assignment_version)"
                        + " SELECT ?,947000,asset_id,asset_version,snapshot_json,947002,1 FROM"
                        + " fams_asset_inventory_source_v1 WHERE asset_id=?",
                    947000 + i,
                    947000 + i);
        }
        db.update(
                "UPDATE fams_inventory_task SET"
                    + " expected_count=2,timezone_id='Asia/Shanghai',parameter_version_id=947000"
                    + " WHERE id=947000");
    }

    @AfterEach
    void cleanup() {
        db.update(
                "DELETE FROM fams_todo WHERE business_type='INVENTORY_LATE' AND business_id IN (SELECT observation_id FROM fams_inventory_late_case WHERE task_id IN (947000,947100))");
        db.update(
                "DELETE FROM fams_inventory_late_decision WHERE observation_id IN (SELECT observation_id FROM fams_inventory_late_case WHERE task_id IN (947000,947100))");
        db.update(
                "DELETE FROM fams_inventory_late_case WHERE task_id IN (947000,947100)");
        for (String table :
                java.util.List.of(
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
            db.update("DELETE FROM " + table + " WHERE task_id IN (947000,947100)");
        }
        db.update(
                "DELETE FROM fams_inventory_round_result WHERE item_id IN"
                        + " (947010,947011,947110,947111)");
        db.update("DELETE FROM fams_inventory_item_review WHERE task_id IN (947000,947100)");
        db.update("DELETE FROM fams_inventory_observation WHERE task_id IN (947000,947100)");
        for (String table :
                java.util.List.of(
                        "fams_inventory_assignment",
                        "fams_inventory_participant",
                        "fams_inventory_task_event",
                        "fams_inventory_item")) {
            db.update("DELETE FROM " + table + " WHERE task_id IN (947000,947100)");
        }
        db.update("DELETE FROM fams_inventory_task WHERE id IN (947000,947100)");
        db.update(
                "DELETE FROM fams_inventory_clock_calibration WHERE actor_account_id BETWEEN 947001"
                        + " AND 947003");
        db.update(
                "DELETE FROM fams_idempotency_record WHERE actor_account_id BETWEEN 947001 AND"
                        + " 947003");
        db.update("DELETE FROM fams_audit_event WHERE actor_account_id BETWEEN 947001 AND 947003");
        db.update("DELETE FROM fams_asset WHERE id IN (947010,947011)");
        db.update("DELETE FROM fams_business_parameter_version WHERE id=947000");
        db.update("DELETE FROM fams_location WHERE id=947000");
        db.update("DELETE FROM fams_warehouse WHERE id=947000");
        db.update("DELETE FROM fams_asset_category WHERE id=947000");
        db.update("DELETE FROM fams_mini_session WHERE account_id BETWEEN 947001 AND 947003");
        db.update("DELETE FROM fams_mini_binding WHERE account_id BETWEEN 947001 AND 947003");
        db.update("DELETE FROM fams_security_event WHERE account_id BETWEEN 947001 AND 947003");
        db.update("DELETE FROM fams_account_role WHERE account_id BETWEEN 947001 AND 947003");
        db.update("DELETE FROM fams_system_account WHERE id BETWEEN 947001 AND 947003");
        db.update("DELETE FROM fams_employee WHERE id BETWEEN 947001 AND 947003");
        db.update("DELETE FROM fams_organization WHERE id=947000");
    }

    @Test
    void explicitUnobservedAndExactMissingSyncWaiverFreezeOriginalReport() throws Exception {
        unobserved("UNABLE_TO_OBSERVE", "EVIDENCE_UNAVAILABLE");
        waiting();
        var preflight = ok(call("GET", "", null, null, 0, false));
        assertEquals(0, preflight.path("blockerCount").asInt(), preflight.toString());
        assertEquals(2, preflight.path("missingSynchronizationCount").asInt());
        var input = sealBody(preflight, null);
        assertEquals(
                409,
                call("POST", "/seal", input, UUID.randomUUID().toString(), version(), false)
                        .statusCode());
        input.put("missingSynchronizationReason", "现场已记录缺少声明人员，保留对应待补证名单并安排复盘");
        String key = UUID.randomUUID().toString();
        long old = version();
        var first = ok(call("POST", "/seal", input, key, old, false));
        assertEquals("COMPLETED", first.path("status").asText());
        assertEquals(2, first.path("notObservedCount").asInt());
        assertEquals(first, ok(call("POST", "/seal", input, key, old, false)));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_sealed_report WHERE task_id=947000",
                        Integer.class));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_sealed_item WHERE task_id=947000",
                        Integer.class));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_sealed_sync WHERE task_id=947000",
                        Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset WHERE id IN (947010,947011) AND"
                                + " last_inventory_business_date IS NOT NULL",
                        Integer.class));
        var frozen =
                db.queryForList("SELECT * FROM fams_inventory_sealed_report WHERE task_id=947000");
        long sealedVersion = version();
        assertEquals("LATE_REVIEW", observe("947010").path("outcome").asText());
        assertEquals(sealedVersion, version());
        assertEquals(
                frozen,
                db.queryForList("SELECT * FROM fams_inventory_sealed_report WHERE task_id=947000"));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT captured_result_count FROM fams_inventory_task WHERE id=947000",
                        Integer.class));
        assertEquals(
                0, ok(call("GET", "/report/evidence", null, null, 0, false)).path("total").asInt());
        assertEquals(
                2, ok(call("GET", "/report/items", null, null, 0, false)).path("total").asInt());
    }

    @Test
    void acceptedNormalObservationsAdvanceDateWithoutChangingAssetVersionOrLocks()
            throws Exception {
        acceptedNormal();
        waiting();
        var before =
                db.queryForList(
                        "SELECT id,version,lifecycle_status,current_location_id FROM fams_asset"
                                + " WHERE id IN (947010,947011) ORDER BY id");
        var preflight = ok(call("GET", "", null, null, 0, false));
        assertEquals(0, preflight.path("blockerCount").asInt(), preflight.toString());
        var result =
                ok(
                        call(
                                "POST",
                                "/seal",
                                sealBody(preflight, "执行人未完成设备声明，保留名单和明确补证义务"),
                                UUID.randomUUID().toString(),
                                version(),
                                false));
        assertEquals(2, result.path("acceptedCount").asInt());
        assertEquals(
                before,
                db.queryForList(
                        "SELECT id,version,lifecycle_status,current_location_id FROM fams_asset"
                                + " WHERE id IN (947010,947011) ORDER BY id"));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id IN"
                                + " (947010,947011)",
                        Integer.class));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset WHERE id IN (947010,947011) AND"
                                + " last_inventory_business_date IS NOT NULL",
                        Integer.class));
        var report = ok(call("GET", "/report/items", null, null, 0, false));
        assertTrue(
                report.path("items")
                        .get(0)
                        .path("snapshot_json")
                        .path("dateChange")
                        .path("observationId")
                        .isString());
        assertTrue(
                report.path("items")
                        .get(0)
                        .path("snapshot_json")
                        .path("dateChange")
                        .path("previousDate")
                        .isNull());
    }

    @Test
    void physicalMissingAndCurrentRoleCannotBeWaived() throws Exception {
        unobserved("ASSET_NOT_FOUND", "UNABLE_TO_OBSERVE");
        waiting();
        var preflight = ok(call("GET", "", null, null, 0, false));
        assertTrue(preflight.path("blockerCount").asInt() > 0);
        assertEquals(
                409,
                call(
                                "POST",
                                "/seal",
                                sealBody(preflight, "不能用任何同步说明豁免真实实物查找与报失后续"),
                                UUID.randomUUID().toString(),
                                version(),
                                false)
                        .statusCode());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_sealed_report WHERE task_id=947000",
                        Integer.class));
        assertEquals(403, call("GET", "", null, null, 0, true).statusCode());
    }

    @Test
    void staleRosterRefusesAndCentralAuditFailureRollsBackDatesReportTaskAndReceipt()
            throws Exception {
        acceptedNormal();
        waiting();
        var preflight = ok(call("GET", "", null, null, 0, false));
        var input = sealBody(preflight, "执行人尚缺同步声明，保留名单并安排补盘");
        db.update(
                "UPDATE fams_inventory_participant SET revoked_at=NOW(3) WHERE task_id=947000 AND"
                        + " account_id=947003");
        assertEquals(
                409,
                call("POST", "/seal", input, UUID.randomUUID().toString(), version(), false)
                        .statusCode());
        preflight = ok(call("GET", "", null, null, 0, false));
        input = sealBody(preflight, "撤销参与关系仍保留补证责任，明确记录缺失原因");
        long before = version();
        db.execute(
                "CREATE TRIGGER fams_seal_audit_failure BEFORE INSERT ON fams_audit_event FOR EACH"
                    + " ROW BEGIN IF NEW.action='INVENTORY_SEALED' THEN SIGNAL SQLSTATE '45000' SET"
                    + " MESSAGE_TEXT='owned seal audit failure'; END IF; END");
        try {
            assertEquals(
                    500,
                    call("POST", "/seal", input, UUID.randomUUID().toString(), before, false)
                            .statusCode());
        } finally {
            db.execute("DROP TRIGGER IF EXISTS fams_seal_audit_failure");
        }
        assertEquals(before, version());
        assertEquals(
                "WAITING_REVIEW",
                db.queryForObject(
                        "SELECT task_status FROM fams_inventory_task WHERE id=947000",
                        String.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_sealed_report WHERE task_id=947000",
                        Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_review_receipt WHERE task_id=947000"
                                + " AND operation='SEAL'",
                        Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset WHERE id IN (947010,947011) AND"
                                + " last_inventory_business_date IS NOT NULL",
                        Integer.class));
    }

    @Test
    void historicallyValidAcceptanceDoesNotExpireAtSealWallTime() throws Exception {
        acceptedNormal();
        waiting();
        var observed =
                db.queryForObject(
                        "SELECT MIN(verified_observed_at) FROM fams_inventory_item_review WHERE"
                                + " task_id=947000",
                        java.sql.Timestamp.class);
        testClock.elapsed.set(java.time.Duration.ofHours(96));
        relogin();
        var preflight = ok(call("GET", "", null, null, 0, false));
        assertEquals(0, preflight.path("blockerCount").asInt(), preflight.toString());
        ok(
                call(
                        "POST",
                        "/seal",
                        sealBody(preflight, "早期已有效采信，现明确记录缺少声明人员与补证义务"),
                        UUID.randomUUID().toString(),
                        version(),
                        false));
        var expected =
                java.util.Objects.requireNonNull(observed)
                        .toInstant()
                        .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .toLocalDate();
        assertEquals(
                expected,
                db.queryForObject(
                        "SELECT last_inventory_business_date FROM fams_asset WHERE id=947010",
                        java.time.LocalDate.class));
        var frozen =
                db.queryForList("SELECT * FROM fams_inventory_sealed_report WHERE task_id=947000");
        long sealedVersion = version();
        token =
                sessions.start(
                                new MiniWechatIdentityPort.Identity("seal-app", "seal-executor"),
                                UUID.randomUUID())
                        .sessionToken();
        assertEquals("LATE_REVIEW", observe("947010").path("outcome").asText());
        assertEquals(
                expected,
                db.queryForObject(
                        "SELECT last_inventory_business_date FROM fams_asset WHERE id=947010",
                        java.time.LocalDate.class));
        assertEquals(sealedVersion, version());
        assertEquals(
                frozen,
                db.queryForList("SELECT * FROM fams_inventory_sealed_report WHERE task_id=947000"));
        assertEquals(
                2, ok(call("GET", "/report/evidence", null, null, 0, false)).path("total").asInt());
    }

    @Test
    void olderTaskSealedAfterNewerTaskCannotMoveAcceptedDateBackwards() throws Exception {
        acceptedNormal();
        waiting();
        db.update(
                "INSERT INTO"
                    + " fams_inventory_task(id,name,task_status,range_json,starts_at,due_at,creator_account_id,created_at,updated_at,published_at,assignment_version,expected_count,timezone_id,parameter_version_id)"
                    + " SELECT 947100,'Later date"
                    + " task','IN_PROGRESS',range_json,starts_at,due_at,creator_account_id,created_at,updated_at,published_at,1,2,timezone_id,parameter_version_id"
                    + " FROM fams_inventory_task WHERE id=947000");
        db.update(
                "INSERT INTO"
                    + " fams_inventory_item(id,task_id,asset_id,asset_version,snapshot_json,executor_account_id,assignment_version)"
                    + " SELECT"
                    + " id+100,947100,asset_id,asset_version,snapshot_json,executor_account_id,1"
                    + " FROM fams_inventory_item WHERE task_id=947000");
        db.update(
                "INSERT INTO fams_inventory_participant(task_id,account_id,first_assigned_at)"
                    + " SELECT 947100,account_id,first_assigned_at FROM fams_inventory_participant"
                    + " WHERE task_id=947000");
        testClock.elapsed.set(java.time.Duration.ofDays(2));
        relogin();
        token =
                sessions.start(
                                new MiniWechatIdentityPort.Identity("seal-app", "seal-executor"),
                                UUID.randomUUID())
                        .sessionToken();
        taskUnderTest = 947100;
        acceptedNormal();
        waiting();
        var later = ok(call("GET", "", null, null, 0, false));
        assertEquals(0, later.path("blockerCount").asInt(), later.toString());
        ok(
                call(
                        "POST",
                        "/seal",
                        sealBody(later, "较新任务先冻结，明确保留尚缺同步确认名单"),
                        UUID.randomUUID().toString(),
                        version(),
                        false));
        var dates =
                db.queryForList(
                        "SELECT id,last_inventory_business_date FROM fams_asset WHERE id IN"
                                + " (947010,947011) ORDER BY id");
        taskUnderTest = 947000;
        var earlier = ok(call("GET", "", null, null, 0, false));
        assertEquals(0, earlier.path("blockerCount").asInt(), earlier.toString());
        ok(
                call(
                        "POST",
                        "/seal",
                        sealBody(earlier, "旧任务后冻结，明确保留尚缺同步确认名单"),
                        UUID.randomUUID().toString(),
                        version(),
                        false));
        assertEquals(
                dates,
                db.queryForList(
                        "SELECT id,last_inventory_business_date FROM fams_asset WHERE id IN"
                                + " (947010,947011) ORDER BY id"));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_sealed_report WHERE task_id IN"
                                + " (947000,947100)",
                        Integer.class));
    }

    @Test
    void unavailableAssetIsAPreflightBlockerRatherThanRollbackOnlyError() throws Exception {
        unobserved("UNABLE_TO_OBSERVE", "EVIDENCE_UNAVAILABLE");
        waiting();
        db.update(
                "UPDATE fams_asset SET"
                    + " card_stage='PENDING_INBOUND',custody_type=NULL,storage_warehouse_id=NULL,current_location_id=NULL"
                    + " WHERE id=947010");
        try {
            var result = ok(call("GET", "", null, null, 0, false));
            assertTrue(result.path("blockerCount").asInt() > 0);
            assertEquals(
                    409,
                    call(
                                    "POST",
                                    "/seal",
                                    sealBody(result, "不能豁免资产卡当前不可用，仍保留缺失声明名单"),
                                    UUID.randomUUID().toString(),
                                    version(),
                                    false)
                            .statusCode());
        } finally {
            db.update(
                    "UPDATE fams_asset SET"
                        + " card_stage='ACTIVE',custody_type='STORAGE',storage_warehouse_id=947000,current_location_id=947000"
                        + " WHERE id=947010");
        }
    }

    @Test
    void newConflictAfterAcceptanceBlocksSealAndNameOnlyVersionChangeDoesNot() throws Exception {
        acceptedNormal();
        waiting();
        db.update(
                "UPDATE fams_asset SET name='Renamed metadata only',version=version+1 WHERE"
                        + " id=947010");
        var before = ok(call("GET", "", null, null, 0, false));
        assertEquals(0, before.path("blockerCount").asInt(), before.toString());
        db.update("UPDATE fams_asset SET name='Sealing device',version=0 WHERE id=947010");
        assertEquals("CONFLICT_REVIEW", observe("947010", "UNKNOWN").path("outcome").asText());
        var changed = ok(call("GET", "", null, null, 0, false));
        assertTrue(changed.path("blockerCount").asInt() > 0);
        assertEquals(
                409,
                call(
                                "POST",
                                "/seal",
                                sealBody(changed, "新冲突不能由同步说明豁免，明确保留未同步名单"),
                                UUID.randomUUID().toString(),
                                version(),
                                false)
                        .statusCode());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_sealed_report WHERE task_id=947000",
                        Integer.class));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_item_review WHERE task_id=947000 AND"
                                + " review_status='ACCEPTED'",
                        Integer.class));
    }

    @Test
    void selectedAcceptanceFollowsCounterDispositionVersionBeforeAcceptanceIncrement()
            throws Exception {
        assertEquals("ACCEPTED", observe("947010").path("outcome").asText());
        assertEquals("CONFLICT_REVIEW", observe("947010", "UNKNOWN").path("outcome").asText());
        var original =
                db.queryForObject(
                        "SELECT MIN(id) FROM fams_inventory_observation WHERE task_id=947000",
                        Long.class);
        var counter =
                db.queryForObject(
                        "SELECT MAX(id) FROM fams_inventory_observation WHERE task_id=947000",
                        Long.class);
        ok(
                pc(
                        "POST",
                        "/inventory-reviews/tasks/947000/differences/preparation",
                        java.util.Map.of("observationIds", java.util.List.of(original, counter)),
                        UUID.randomUUID().toString(),
                        version()));
        var detail =
                ok(
                        pc(
                                "GET",
                                "/inventory-reviews/tasks/947000/items/947010/selection",
                                null,
                                null,
                                0));
        String digest = detail.path("contextDigest").asText();
        ok(
                pc(
                        "POST",
                        "/inventory-reviews/tasks/947000/items/947010/selection",
                        java.util.Map.of(
                                "observationId",
                                original,
                                "recheckGeneration",
                                0,
                                "reviewVersion",
                                0,
                                "assetVersion",
                                0,
                                "contextDigest",
                                digest,
                                "reason",
                                "核对现场正常证据作为待采信选择",
                                "evidence",
                                "独立现场依据与原始照片及责任登记已核实"),
                        UUID.randomUUID().toString(),
                        version()));
        for (var id : java.util.List.of(original, counter)) {
            var subjects =
                    ok(
                            pc(
                                    "GET",
                                    "/inventory-reviews/tasks/947000/items/947010/evidence-subjects?observationId="
                                            + id,
                                    null,
                                    null,
                                    0));
            var references = new java.util.ArrayList<java.util.Map<String, Object>>();
            for (var subject : subjects.path("items")) {
                references.add(
                        java.util.Map.of(
                                "dimension",
                                subject.path("dimension").asText(),
                                "version",
                                subject.path("version").asLong()));
            }
            ok(
                    pc(
                            "POST",
                            "/inventory-reviews/tasks/947000/items/947010/evidence-decisions",
                            java.util.Map.of(
                                    "observationId",
                                    id,
                                    "recheckGeneration",
                                    0,
                                    "reviewVersion",
                                    1,
                                    "assetVersion",
                                    0,
                                    "contextDigest",
                                    digest,
                                    "method",
                                    id.equals(original) ? "VERIFY_SELECTED" : "REJECT_EVIDENCE",
                                    "subjects",
                                    references,
                                    "reason",
                                    "核对独立冲突及其他维度依据",
                                    "evidence",
                                    "已核对具体现场反证，所选正常观察可信而冲突证据不采信"),
                            UUID.randomUUID().toString(),
                            version()));
        }
        ok(
                pc(
                        "POST",
                        "/inventory-reviews/tasks/947000/items/947010/acceptance",
                        java.util.Map.of(
                                "recheckGeneration",
                                0,
                                "reviewVersion",
                                1,
                                "assetVersion",
                                0,
                                "contextDigest",
                                digest,
                                "reason",
                                "逐维复核完成后最终采信",
                                "evidence",
                                "冲突证据已逐项记录反证且所选观察独立核实"),
                        UUID.randomUUID().toString(),
                        version()));
        ok(
                pc(
                        "POST",
                        "/inventory-readiness/tasks/947000/items/947011/unobserved",
                        body("EVIDENCE_UNAVAILABLE"),
                        UUID.randomUUID().toString(),
                        version()));
        waiting();
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT counter_review_version FROM fams_inventory_evidence_disposition"
                                + " WHERE observation_id=?",
                        Integer.class,
                        counter));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT version FROM fams_inventory_item_review WHERE item_id=947010 AND"
                                + " recheck_generation=0",
                        Integer.class));
        var preflight = ok(call("GET", "", null, null, 0, false));
        assertEquals(0, preflight.path("blockerCount").asInt(), preflight.toString());
        var result =
                ok(
                        call(
                                "POST",
                                "/seal",
                                sealBody(preflight, "逐维复核已完成，仍保留缺少同步声明人员的补证义务"),
                                UUID.randomUUID().toString(),
                                version(),
                                false));
        assertEquals(1, result.path("acceptedCount").asInt());
        assertEquals(
                2, ok(call("GET", "/report/evidence", null, null, 0, false)).path("total").asInt());
    }

    @Test
    void nativeHandlerRecoversActualCommittedSealReplyAndReadsFrozenMissingRoster()
            throws Exception {
        unobserved("UNABLE_TO_OBSERVE", "EVIDENCE_UNAVAILABLE");
        waiting();
        var identity = new MiniWechatIdentityPort.Identity("sealing-app", "sealing-admin");
        db.update(
                "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES"
                        + " (947001,?,NOW(3))",
                sessions.identityHash(identity));
        var grant = sessions.start(identity, UUID.randomUUID());
        var script =
                java.nio.file.Path.of(System.getProperty("user.dir"))
                        .getParent()
                        .resolve("apps/miniprogram/test/runtime-inventory-sealing-http.mjs");
        var builder = new ProcessBuilder("rtk", "proxy", "node", script.toString());
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_INVENTORY_TASK", "947000");
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
            assertTrue(proof.path("nativeSealedOriginalReplyRecovered").asBoolean());
            assertTrue(proof.path("exactOriginalKeyPayloadVersionAndRoster").asBoolean());
            System.out.println("D6-18B2b native sealing runtime proof: " + proof);
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
                        "SELECT COUNT(*) FROM fams_inventory_sealed_report WHERE task_id=947000",
                        Integer.class));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_review_receipt WHERE task_id=947000"
                                + " AND operation='SEAL'",
                        Integer.class));
    }

    @Test
    void aNewUnobservedRoundCannotEraseAnEarlierUnresolvedPhysicalMissingObligation()
            throws Exception {
        unobserved("ASSET_NOT_FOUND", "UNABLE_TO_OBSERVE");
        waiting();
        ok(
                pc(
                        "POST",
                        "/inventory-tasks/947000/assignments",
                        java.util.Map.of(
                                "itemIds",
                                java.util.List.of("947010", "947011"),
                                "executorAccountId",
                                "947002",
                                "recheck",
                                true,
                                "reason",
                                "明确发起新轮复盘但保留旧实物缺失义务"),
                        UUID.randomUUID().toString(),
                        version()));
        var input = body("UNABLE_TO_OBSERVE");
        input.put("recheckGeneration", 1);
        for (String id : java.util.List.of("947010", "947011")) {
            ok(
                    pc(
                            "POST",
                            "/inventory-readiness/tasks/947000/items/" + id + "/unobserved",
                            input,
                            UUID.randomUUID().toString(),
                            version()));
        }
        waiting();
        var preflight = ok(call("GET", "", null, null, 0, false));
        assertTrue(preflight.path("blockerCount").asInt() > 0);
        assertEquals(
                409,
                call(
                                "POST",
                                "/seal",
                                sealBody(preflight, "不能凭新一轮无法实盘原因覆盖旧实物缺失义务"),
                                UUID.randomUUID().toString(),
                                version(),
                                false)
                        .statusCode());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_unobserved_reason WHERE task_id=947000"
                                + " AND reason_kind='ASSET_NOT_FOUND'",
                        Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_sealed_report WHERE task_id=947000",
                        Integer.class));
    }

    @Test
    void originalSealedReportSurvivesActualCommittingJvmForcedDeathAndFreshJvm() throws Exception {
        unobserved("UNABLE_TO_OBSERVE", "EVIDENCE_UNAVAILABLE");
        waiting();
        var preflight = ok(call("GET", "", null, null, 0, false));
        String body = JSON.writeValueAsString(sealBody(preflight, "实际提交进程退出后仍保留缺失同步人员的明确补证义务"));
        String originalKey = UUID.randomUUID().toString();
        long version = version();
        var before =
                db.queryForList("SELECT * FROM fams_asset WHERE id IN (947010,947011) ORDER BY id");
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
                                "com.fams.auth.InventorySealProcess");
                builder.environment().put("FAMS_TEST_SEAL_TASK", "947000");
                builder.environment().put("FAMS_TEST_SEAL_COOKIE", cookie);
                builder.environment().put("FAMS_TEST_SEAL_CSRF", csrf);
                builder.environment().put("FAMS_TEST_SEAL_KEY", originalKey);
                builder.environment().put("FAMS_TEST_SEAL_VERSION", Long.toString(version));
                builder.environment().put("FAMS_TEST_SEAL_BODY", body);
                builder.environment().put("FAMS_TEST_SEAL_STAGE", stage);
                var proofPath = state.resolve(stage + ".json");
                var logPath = state.resolve(stage + ".log");
                builder.environment().put("FAMS_TEST_SEAL_PROOF", proofPath.toString());
                if (first != null) {
                    builder.environment()
                            .put("FAMS_TEST_SEAL_ORIGINAL", first.path("response").toString());
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
                                    + " task_id=947000",
                            Integer.class));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_inventory_review_receipt WHERE"
                                    + " task_id=947000 AND operation='SEAL'",
                            Integer.class));
            assertEquals(
                    2,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_inventory_sealed_item WHERE task_id=947000",
                            Integer.class));
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT captured_result_count FROM fams_inventory_task WHERE id=947000",
                            Integer.class));
            assertEquals(
                    before,
                    db.queryForList(
                            "SELECT * FROM fams_asset WHERE id IN (947010,947011) ORDER BY id"));
            System.out.println(
                    "D6-18B2b JVM proof: committingJvmKilled=true freshJvmOriginalReply=true"
                            + " singleSealedReport=true datesAndCollectionUnchanged=true");
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
    void twoAdministratorsSealOneReportAndAuditTheOtherWithActualReviewer() throws Exception {
        unobserved("UNABLE_TO_OBSERVE", "EVIDENCE_UNAVAILABLE");
        waiting();
        db.update(
                "INSERT INTO fams_account_role(account_id,role_code,granted_at) VALUES"
                        + " (947003,'ASSET_ADMIN',NOW(3))");
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
        String
                otherCookie =
                        login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                otherCsrf = JSON.readTree(login.body()).path("csrfToken").asText();
        var preflight = ok(call("GET", "", null, null, 0, false));
        long before = version();
        var firstBody = sealBody(preflight, "第一位管理员明确保留缺失同步人员与补证义务");
        var secondBody = sealBody(preflight, "第二位管理员独立核实并保留缺失同步补证名单");
        var first =
                http.sendAsync(
                        sealRequest(cookie, csrf, firstBody, UUID.randomUUID().toString(), before),
                        HttpResponse.BodyHandlers.ofString());
        var second =
                http.sendAsync(
                        sealRequest(
                                otherCookie,
                                otherCsrf,
                                secondBody,
                                UUID.randomUUID().toString(),
                                before),
                        HttpResponse.BodyHandlers.ofString());
        var responses =
                java.util.List.of(
                        first.get(30, java.util.concurrent.TimeUnit.SECONDS),
                        second.get(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(
                java.util.List.of(200, 409),
                responses.stream().map(HttpResponse::statusCode).sorted().toList(),
                responses.toString());
        var refused =
                responses.stream()
                        .filter(response -> response.statusCode() == 409)
                        .findFirst()
                        .orElseThrow();
        var fields = JSON.readTree(refused.body()).path("fieldErrors");
        assertEquals(
                db.queryForObject(
                        "SELECT actor_username FROM fams_inventory_sealed_report WHERE"
                                + " task_id=947000",
                        String.class),
                fields.path("processedBy").asText());
        Instant.parse(fields.path("processedAt").asText());
        assertEquals("SEAL", fields.path("method").asText());
        assertEquals(before + 1, version());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_sealed_report WHERE task_id=947000",
                        Integer.class));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_review_receipt WHERE task_id=947000"
                                + " AND operation='SEAL'",
                        Integer.class));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_audit_event WHERE resource_type='INVENTORY_TASK'"
                                + " AND resource_id=947000 AND"
                                + " action='INVENTORY_SEAL_CONFLICT_REJECTED'",
                        Integer.class));
    }

    @Test
    void uploadAndSealRaceCannotPierceFrozenReportOrAcceptedDates() throws Exception {
        acceptedNormal();
        waiting();
        var raw =
                db.queryForObject(
                        "SELECT CAST(facts_json AS CHAR) FROM fams_inventory_observation WHERE"
                                + " task_id=947000 AND item_id=947010",
                        String.class);
        var event = (tools.jackson.databind.node.ObjectNode) JSON.readTree(raw);
        String eventId = UUID.randomUUID().toString();
        event.put("eventId", eventId);
        var now = testClock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        for (String field :
                java.util.List.of("rawScannedAt", "rawObservedAt", "firstPersistedAt")) {
            event.put(field, now.toString());
        }
        event.put("expiresAt", now.plusSeconds(72 * 3600).toString());
        var preflight = ok(call("GET", "", null, null, 0, false));
        long before = version();
        var seal =
                http.sendAsync(
                        sealRequest(
                                cookie,
                                csrf,
                                sealBody(preflight, "并发补传时保留缺失声明人员与补证义务"),
                                UUID.randomUUID().toString(),
                                before),
                        HttpResponse.BodyHandlers.ofString());
        var upload =
                http.sendAsync(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + port
                                                        + "/api/v1/mini/inventory-observations/tasks/947000/events"))
                                .header("X-FAMS-Mini-Session", token)
                                .header("Content-Type", "application/json")
                                .header("Idempotency-Key", eventId)
                                .header("If-Match-Version", "1")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                JSON.writeValueAsString(event)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        var sealed = seal.get(30, java.util.concurrent.TimeUnit.SECONDS);
        var uploaded = upload.get(30, java.util.concurrent.TimeUnit.SECONDS);
        if (uploaded.statusCode() == 409) {
            assertEquals(
                    "INVENTORY_WRITE_CONFLICT",
                    JSON.readTree(uploaded.body()).path("code").asText());
            // A rolled-back upload retains its exact event identity and original lifetime.
            uploaded =
                    request(
                            "POST",
                            "/mini/inventory-observations/tasks/947000/events",
                            event,
                            eventId,
                            1,
                            true);
        }
        var received = ok(uploaded);
        if (sealed.statusCode() == 200) {
            assertEquals("LATE_REVIEW", received.path("outcome").asText());
            assertEquals(
                    2,
                    ok(call("GET", "/report/evidence", null, null, 0, false))
                            .path("total")
                            .asInt());
            assertEquals(
                    2,
                    db.queryForObject(
                            "SELECT captured_result_count FROM fams_inventory_task WHERE id=947000",
                            Integer.class));
            assertEquals(
                    2,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_asset WHERE id IN (947010,947011) AND"
                                    + " last_inventory_business_date IS NOT NULL",
                            Integer.class));
        } else {
            assertEquals(409, sealed.statusCode(), sealed.body());
            assertEquals("DUPLICATE", received.path("outcome").asText());
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_inventory_sealed_report WHERE"
                                    + " task_id=947000",
                            Integer.class));
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_asset WHERE id IN (947010,947011) AND"
                                    + " last_inventory_business_date IS NOT NULL",
                            Integer.class));
        }
        assertEquals(before + 1, version());
        assertEquals(
                3,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_observation WHERE task_id=947000",
                        Integer.class));
    }

    private HttpRequest sealRequest(
            String actorCookie, String actorCsrf, Object body, String key, long version) {
        return HttpRequest.newBuilder(
                        URI.create(
                                "http://127.0.0.1:"
                                        + port
                                        + "/api/v1/inventory-sealing/tasks/947000/seal"))
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
                        "/inventory-readiness/tasks/947000/items/947010/unobserved",
                        body(first),
                        UUID.randomUUID().toString(),
                        version()));
        ok(
                pc(
                        "POST",
                        "/inventory-readiness/tasks/947000/items/947011/unobserved",
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
                .put("locationId", "947000")
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
