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
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
@EnabledIfSystemProperty(named = "d6.19.inventory.late.damage.runtime", matches = "true")
class InventoryLateDamageHttpRuntimeCase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    private static Path photoRoot;
    private final HttpClient http = HttpClient.newHttpClient();
    private final InventoryLateHttpRuntimeCase fixture = new InventoryLateHttpRuntimeCase();
    @Autowired JdbcTemplate db;
    @Autowired MiniSessionService sessions;
    @Autowired InventoryLateHttpRuntimeCase.TestClock testClock;

    @Value("${local.server.port}")
    int port;

    private Session admin, employee, other, warehouse;

    private record Session(String cookie, String csrf) {}

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        InventoryLateHttpRuntimeCase.database(registry);
        if (photoRoot == null) {
            photoRoot = Files.createTempDirectory("fams-late-damage-photos-");
        }
        registry.add("fams.attachments.quarantine-root", () -> photoRoot.toString());
        registry.add("fams.test.proxy-evidence", () -> "true");
    }

    @BeforeEach
    void seed() throws Exception {
        fixture.db = db;
        fixture.sessions = sessions;
        fixture.testClock = testClock;
        fixture.port = port;
        fixture.seed();
        db.update(
                "INSERT INTO"
                    + " fams_employee(id,employee_no,employee_no_normalized,name,organization_id,status,created_at,updated_at)"
                    + " VALUES (948004,'SEAL4','SEAL4','Damage"
                    + " warehouse',948000,'ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_system_account(id,employee_id,username,username_normalized,password_hash,status,initial_password_required,created_at,updated_at)"
                    + " VALUES (948004,948004,'seal4','SEAL4',?,'ENABLED',FALSE,NOW(3),NOW(3))",
                new BCryptPasswordEncoder(4).encode(PASSWORD));
        db.update(
                "INSERT INTO fams_account_role(account_id,role_code,granted_at) VALUES"
                    + " (948004,'WAREHOUSE_OPERATOR',NOW(3))");
        admin = login(1);
        employee = login(2);
        other = login(3);
        warehouse = login(4);
    }

    @AfterEach
    void cleanup() throws Exception {
        db.execute("DROP TRIGGER IF EXISTS owned_late_damage_audit_failure");
        String observations =
                " IN (SELECT observation_id FROM fams_inventory_late_case WHERE task_id IN (SELECT"
                    + " id FROM fams_inventory_task WHERE creator_account_id=948001))";
        db.update(
                "DELETE FROM fams_inventory_late_damage_decision WHERE observation_id"
                        + observations);
        db.update("DELETE FROM fams_inventory_late_damage WHERE observation_id" + observations);
        String repairs =
                " IN (SELECT id FROM fams_repair_request WHERE asset_id IN (948010,948011))";
        for (String table :
                List.of(
                        "fams_repair_inventory_evidence",
                        "fams_repair_receipt",
                        "fams_repair_event",
                        "fams_repair_decision",
                        "fams_repair_photo")) {
            db.update("DELETE FROM " + table + " WHERE repair_id" + repairs);
        }
        db.update("DELETE FROM fams_todo WHERE business_type='REPAIR' AND business_id" + repairs);
        db.update("DELETE FROM fams_asset_business_lock WHERE asset_id IN (948010,948011)");
        db.update("DELETE FROM fams_repair_request WHERE asset_id IN (948010,948011)");
        for (String table :
                List.of("fams_attachment_deletion_job", "fams_attachment_cleanup_audit")) {
            db.update(
                    "DELETE FROM "
                            + table
                            + " WHERE object_id IN (SELECT object_id FROM fams_attachment WHERE"
                            + " business_type='INVENTORY_EVENT' AND business_id IN (SELECT id FROM"
                            + " fams_inventory_upload_scope WHERE actor_account_id=948002))");
        }
        db.update(
                "DELETE FROM fams_inventory_photo_receipt WHERE scope_id IN (SELECT id FROM"
                    + " fams_inventory_upload_scope WHERE actor_account_id=948002)");
        db.update(
                "DELETE FROM fams_attachment WHERE business_type='INVENTORY_EVENT' AND business_id"
                    + " IN (SELECT id FROM fams_inventory_upload_scope WHERE"
                    + " actor_account_id=948002)");
        db.update(
                "DELETE FROM fams_attachment_scope WHERE business_type='INVENTORY_EVENT' AND"
                    + " business_id IN (SELECT id FROM fams_inventory_upload_scope WHERE"
                    + " actor_account_id=948002)");
        db.update("DELETE FROM fams_inventory_upload_scope WHERE actor_account_id=948002");
        db.update("DELETE FROM fams_asset_timeline_event WHERE asset_id IN (948010,948011)");
        db.update("DELETE FROM fams_audit_event WHERE actor_account_id=948004");
        db.update("DELETE FROM fams_idempotency_record WHERE actor_account_id=948004");
        for (String table :
                List.of(
                        "fams_mini_session",
                        "fams_mini_binding",
                        "fams_security_event",
                        "fams_account_role")) {
            db.update("DELETE FROM " + table + " WHERE account_id=948004");
        }
        db.update("DELETE FROM fams_system_account WHERE id=948004");
        db.update("DELETE FROM fams_employee WHERE id=948004");
        fixture.cleanup();
        try (var files = Files.walk(photoRoot)) {
            for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (!file.equals(photoRoot)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    @Test
    void repairCompletionClosesOnlyDamageWhileActualRecheckRemainsRequired() throws Exception {
        long observation = lateDamage();
        var task = task();
        var report = report();
        var original = raw(observation);
        var packet = lateCase(observation);
        assertEquals(2, todos(observation));
        var body = body("FORMAL_REPAIR");
        String request = key();
        var created = ok(decide(admin, observation, 0, request, body));
        assertEquals(created, ok(decide(admin, observation, 0, request, body)));
        assertEquals("PROCESSING", created.path("damageStatus").asText());
        String repair = created.path("repairId").asText();
        assertEquals(
                "PROCESSING",
                ok(decide(admin, observation, 1, key(), body("REFRESH_REPAIR")))
                        .path("damageStatus")
                        .asText());
        completeRepair(repair);
        var closed = ok(decide(admin, observation, 2, key(), body("REFRESH_REPAIR")));
        assertEquals("CLOSED", closed.path("damageStatus").asText());
        assertEquals("OPEN", closed.path("recheckResult").asText());
        assertTrue(closed.path("todoOpen").asBoolean());
        assertEquals(1, todos(observation));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_todo WHERE business_type='INVENTORY_LATE' AND"
                            + " business_id=? AND todo_type='LATE_EVIDENCE_REVIEW' AND closed_at IS"
                            + " NULL",
                        Integer.class,
                        observation));
        completeRecheck(observation);
        assertEquals(0, todos(observation));
        assertEquals(false, detail(observation).path("case").path("todoOpen").asBoolean());
        assertEquals(task, task());
        assertEquals(report, report());
        assertEquals(original, raw(observation));
        assertEquals(packet, lateCase(observation));
    }

    @Test
    void completedRecheckCannotDischargeIndependentRealDamageRepair() throws Exception {
        long observation = lateDamage();
        var report = report();
        completeRecheck(observation);
        var view = detail(observation).path("case");
        assertEquals("REVIEWED", view.path("review").path("result").asText());
        assertEquals("OPEN", view.path("damageFollowup").path("damage_status").asText());
        assertTrue(view.path("todoOpen").asBoolean());
        assertEquals(1, todos(observation));
        var made = ok(decide(admin, observation, 0, key(), body("FORMAL_REPAIR")));
        completeRepair(made.path("repairId").asText());
        var closed = ok(decide(admin, observation, 1, key(), body("REFRESH_REPAIR")));
        assertEquals("CLOSED", closed.path("damageStatus").asText());
        assertEquals("REVIEWED", closed.path("recheckResult").asText());
        assertEquals(false, closed.path("todoOpen").asBoolean());
        assertEquals(report, report());
    }

    @Test
    void sourceAuditFailureRollsBackRepairEvidenceLockDecisionAndOriginalReceipt()
            throws Exception {
        long observation = lateDamage();
        var before = lateCase(observation);
        var input = body("FORMAL_REPAIR");
        String request = key();
        db.execute(
                "CREATE TRIGGER owned_late_damage_audit_failure BEFORE INSERT ON fams_audit_event"
                    + " FOR EACH ROW BEGIN IF NEW.action='INVENTORY_LATE_DAMAGE_DECIDED' THEN"
                    + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned late damage audit"
                    + " rollback'; END IF; END");
        assertTrue(decide(admin, observation, 0, request, input).statusCode() >= 400);
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_request WHERE asset_id=948010",
                        Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_inventory_evidence", Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=948010",
                        Integer.class));
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT version FROM fams_inventory_late_damage WHERE observation_id=?",
                        Long.class,
                        observation));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_late_damage_decision WHERE"
                            + " observation_id=?",
                        Integer.class,
                        observation));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_review_receipt WHERE task_id=948000"
                            + " AND operation='LATE_DAMAGE_REPAIR'",
                        Integer.class));
        assertEquals(before, lateCase(observation));
        assertEquals(2, todos(observation));
        db.execute("DROP TRIGGER owned_late_damage_audit_failure");
        var made = ok(decide(admin, observation, 0, request, input));
        assertEquals(made, ok(decide(admin, observation, 0, request, input)));
    }

    @Test
    void sameAssetLinkAndRejectedRepairKeepDamageOpenForExplicitContinuation() throws Exception {
        long observation = lateDamage();
        long assetVersion = assetVersion();
        var submission =
                JSON.createObjectNode()
                        .put("assetId", "948010")
                        .put("assetVersion", assetVersion)
                        .put("fault", "Actual existing damaged device")
                        .put("impact", "HIGH")
                        .put(
                                "occurredAt",
                                testClock
                                        .instant()
                                        .minusSeconds(5)
                                        .truncatedTo(ChronoUnit.MILLIS)
                                        .toString());
        var repair =
                ok(request(admin, "POST", "/api/v1/repairs", submission.toString(), key(), null));
        var input = body("LINK_REPAIR");
        ((ObjectNode) input.path("repair"))
                .put("repairId", repair.path("id").asText())
                .put("repairVersion", 99);
        assertEquals(409, decide(admin, observation, 0, key(), input).statusCode());
        ((ObjectNode) input.path("repair")).put("repairVersion", 0);
        ok(decide(admin, observation, 0, key(), input));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=948010 AND"
                            + " released_at IS NULL",
                        Integer.class));
        ok(
                request(
                        admin,
                        "POST",
                        "/api/v1/repairs/" + repair.path("id").asText() + "/decision",
                        "{\"result\":\"REJECT\",\"reason\":\"正式维修明确驳回，保留源损坏义务\"}",
                        key(),
                        0L));
        assertEquals(
                "OPEN",
                ok(decide(admin, observation, 1, key(), body("REFRESH_REPAIR")))
                        .path("damageStatus")
                        .asText());
        assertEquals(2, todos(observation));
        ok(decide(admin, observation, 2, key(), body("FORMAL_REPAIR")));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_inventory_evidence WHERE"
                            + " observation_id=?",
                        Integer.class,
                        observation));
    }

    @Test
    void currentRoleIndependentTimeAndAssetVersionPrecedeCreatingFormalWork() throws Exception {
        long observation = lateDamage();
        var input = body("FORMAL_REPAIR");
        assertEquals(403, decide(employee, observation, 0, key(), input).statusCode());
        assertEquals(403, decide(warehouse, observation, 0, key(), input).statusCode());
        ((ObjectNode) input.path("repair")).put("discoveryTimeVerified", false);
        assertEquals(400, decide(admin, observation, 0, key(), input).statusCode());
        input = body("FORMAL_REPAIR").put("assetVersion", 999);
        assertEquals(409, decide(admin, observation, 0, key(), input).statusCode());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_request WHERE asset_id=948010",
                        Integer.class));
        var context = ok(request(admin, "GET", damagePath(observation), null, null, null));
        assertEquals("OPEN", context.path("damage").path("damage_status").asText());
        assertEquals(0, context.path("damage").path("version").asInt());
    }

    @Test
    void twoActualAdministratorsCreateOneRepairAndReportTheCommittedReviewer() throws Exception {
        long observation = lateDamage();
        var input = body("FORMAL_REPAIR");
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> decide(admin, observation, 0, key(), input));
            var two = pool.submit(() -> decide(other, observation, 0, key(), input));
            var replies =
                    List.of(
                            one.get(30, java.util.concurrent.TimeUnit.SECONDS),
                            two.get(30, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, replies.stream().filter(reply -> reply.statusCode() == 200).count());
            assertEquals(1, replies.stream().filter(reply -> reply.statusCode() == 409).count());
            var conflict =
                    JSON.readTree(
                            replies.stream()
                                    .filter(reply -> reply.statusCode() == 409)
                                    .findFirst()
                                    .orElseThrow()
                                    .body());
            assertTrue(
                    List.of("seal1", "seal3")
                            .contains(conflict.path("fieldErrors").path("processedBy").asText()),
                    conflict.toString());
        }
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_request WHERE asset_id=948010",
                        Integer.class));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_late_damage_decision WHERE"
                            + " observation_id=?",
                        Integer.class,
                        observation));
    }

    private long lateDamage() throws Exception {
        var event = event(948000, 948010, "DAMAGED");
        byte[] bytes =
                java.util.Base64.getDecoder()
                        .decode(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==");
        event.putArray("photoIds")
                .add(
                        ok(uploadPhoto(employee, "948000", event, key(), bytes))
                                .path("objectId")
                                .asText());
        collectAndSeal(948000);
        assertEquals("LATE_REVIEW", ok(observe(948000, event)).path("outcome").asText());
        return db.queryForObject(
                "SELECT id FROM fams_inventory_observation WHERE task_id=948000 AND"
                    + " client_event_id=?",
                Long.class,
                event.path("eventId").asText());
    }

    private ObjectNode event(long task, long item, String condition) throws Exception {
        var row =
                db.queryForMap(
                        "SELECT * FROM fams_inventory_item WHERE task_id=? AND id=?", task, item);
        var snapshot = JSON.readTree(row.get("snapshot_json").toString());
        var now = testClock.instant().truncatedTo(ChronoUnit.MILLIS);
        String device = key();
        var calibration =
                ok(
                        request(
                                employee,
                                "POST",
                                "/api/v1/inventory-observations/calibrations",
                                JSON.writeValueAsString(
                                        Map.of("deviceId", device, "clientTime", now.toString())),
                                null,
                                null));
        var event =
                JSON.createObjectNode()
                        .put("eventId", key())
                        .put("itemId", Long.toString(item))
                        .put(
                                "assignmentVersion",
                                ((Number) row.get("assignment_version")).longValue())
                        .put(
                                "recheckGeneration",
                                ((Number) row.get("recheck_generation")).longValue())
                        .put("assetVersion", ((Number) row.get("asset_version")).longValue())
                        .put("deviceId", device)
                        .put("source", "SCAN")
                        .put("assetCode", snapshot.path("assetCode").asText())
                        .put("rawScannedAt", now.toString())
                        .put("rawObservedAt", now.toString())
                        .put("firstPersistedAt", now.toString())
                        .put("expiresAt", now.plusSeconds(72 * 3600).toString())
                        .put("clockAnomaly", false)
                        .put("calibrationId", calibration.path("calibrationId").asText());
        event.putObject("actual")
                .put("locationId", snapshot.path("locationId").asText())
                .put("custodyType", "STORAGE")
                .put("condition", condition);
        event.putArray("photoIds");
        return event;
    }

    private HttpResponse<String> observe(long task, ObjectNode event) throws Exception {
        return request(
                employee,
                "POST",
                "/api/v1/inventory-observations/tasks/" + task + "/events",
                event.toString(),
                event.path("eventId").asText(),
                event.path("assignmentVersion").asLong());
    }

    private void collectAndSeal(long task) throws Exception {
        var items =
                db.queryForList(
                        "SELECT * FROM fams_inventory_item WHERE task_id=? ORDER BY id", task);
        var accepted = new java.util.ArrayList<Object>();
        for (var row : items) {
            long item = ((Number) row.get("id")).longValue();
            assertEquals(
                    "ACCEPTED",
                    ok(observe(task, event(task, item, "GOOD"))).path("outcome").asText());
            accepted.add(
                    Map.of(
                            "itemId",
                            Long.toString(item),
                            "recheckGeneration",
                            ((Number) row.get("recheck_generation")).longValue(),
                            "reviewVersion",
                            0,
                            "assetVersion",
                            ((Number) row.get("asset_version")).longValue()));
        }
        assertEquals(
                items.size(),
                ok(request(
                                admin,
                                "POST",
                                "/api/v1/inventory-reviews/tasks/" + task + "/normal-acceptance",
                                JSON.writeValueAsString(
                                        Map.of("items", accepted, "reason", "实际逐项采集并核对正常实物")),
                                key(),
                                version(task)))
                        .path("accepted")
                        .asInt());
        ok(
                request(
                        admin,
                        "POST",
                        "/api/v1/inventory-readiness/tasks/" + task + "/waiting-review",
                        "{\"reason\":\"本次独立实盘已完成且全部项目逐项采信\"}",
                        key(),
                        version(task)));
        var preflight =
                ok(
                        request(
                                admin,
                                "GET",
                                "/api/v1/inventory-sealing/tasks/" + task,
                                null,
                                null,
                                null));
        var input =
                JSON.createObjectNode()
                        .put("reason", "逐项核实后实际封存本次独立盘点")
                        .put(
                                "synchronizationDigest",
                                preflight.path("synchronizationDigest").asText())
                        .put("missingSynchronizationReason", "明确记录未取得队列声明的执行人及补证安排，不以声明缺失推断离线队列为空");
        ok(
                request(
                        admin,
                        "POST",
                        "/api/v1/inventory-sealing/tasks/" + task + "/seal",
                        input.toString(),
                        key(),
                        version(task)));
    }

    private void completeRecheck(long observation) throws Exception {
        var now = testClock.instant().truncatedTo(ChronoUnit.MILLIS);
        var input =
                JSON.createObjectNode()
                        .put("method", "LINK_RECHECK")
                        .put("reason", "独立复盘继续核实原补充线索")
                        .put("evidence", "新任务独立发布分配采集并封存，不修改原报告");
        input.putObject("recheck")
                .put("name", "迟到损坏的独立真实复盘")
                .put("startsAt", now.minusSeconds(3600).toString())
                .put("dueAt", now.plusSeconds(86400).toString())
                .putObject("range");
        var linked =
                ok(request(
                                admin,
                                "POST",
                                "/api/v1/inventory-late/tasks/948000/observations/"
                                        + observation
                                        + "/decisions",
                                input.toString(),
                                key(),
                                0L))
                        .path("linkedTaskId")
                        .asLong();
        var preview =
                ok(request(admin, "POST", "/api/v1/inventory-tasks/preview", "{}", key(), 0L));
        ok(
                request(
                        admin,
                        "POST",
                        "/api/v1/inventory-tasks/" + linked + "/publication",
                        JSON.writeValueAsString(
                                Map.of(
                                        "expectedCount",
                                        preview.path("count").asLong(),
                                        "previewDigest",
                                        preview.path("digest").asText())),
                        key(),
                        0L));
        var rows =
                db.queryForList(
                        "SELECT id FROM fams_inventory_item WHERE task_id=? ORDER BY id", linked);
        ok(
                request(
                        admin,
                        "POST",
                        "/api/v1/inventory-tasks/" + linked + "/assignments",
                        JSON.writeValueAsString(
                                Map.of(
                                        "itemIds",
                                        rows.stream().map(row -> row.get("id").toString()).toList(),
                                        "executorAccountId",
                                        "948002",
                                        "recheck",
                                        false,
                                        "reason",
                                        "重新分配并独立执行复盘")),
                        key(),
                        version(linked)));
        if ("READY"
                .equals(
                        db.queryForObject(
                                "SELECT task_status FROM fams_inventory_task WHERE id=?",
                                String.class,
                                linked))) {
            ok(
                    request(
                            admin,
                            "POST",
                            "/api/v1/inventory-tasks/" + linked + "/start",
                            null,
                            key(),
                            version(linked)));
        }
        collectAndSeal(linked);
        var confirm =
                JSON.createObjectNode()
                        .put("method", "CONFIRM_RECHECK")
                        .put("reason", "关联独立复盘已实际完成")
                        .put("evidence", "新任务真实封存且对应资产已正式采信，损坏维修仍独立处理");
        assertEquals(
                "REVIEWED",
                ok(request(
                                admin,
                                "POST",
                                "/api/v1/inventory-late/tasks/948000/observations/"
                                        + observation
                                        + "/decisions",
                                confirm.toString(),
                                key(),
                                1L))
                        .path("result")
                        .asText());
    }

    private void completeRepair(String id) throws Exception {
        String base = "/api/v1/repairs/" + id;
        var row = ok(request(admin, "GET", base, null, null, null));
        row =
                ok(
                        request(
                                admin,
                                "POST",
                                base + "/decision",
                                "{\"result\":\"APPROVE\",\"allowedMethod\":\"INTERNAL_NO_COST\",\"reason\":\"已核实迟到损坏和内部无费用维修范围\"}",
                                key(),
                                row.path("version").asLong()));
        ok(request(warehouse, "POST", base + "/claim", null, key(), row.path("version").asLong()));
        for (String action : List.of("accept-in-place", "complete-in-place")) {
            row = ok(request(warehouse, "GET", base, null, null, null));
            var input =
                    Map.of(
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
                            "按原始损坏照片核对现场实物并实际办理维修");
            ok(
                    request(
                            warehouse,
                            "POST",
                            base + "/" + action,
                            JSON.writeValueAsString(input),
                            key(),
                            row.path("version").asLong()));
        }
    }

    private ObjectNode body(String method) {
        var input =
                JSON.createObjectNode()
                        .put("method", method)
                        .put("assetVersion", assetVersion())
                        .put("reason", "迟到线索的真实损坏独立维修")
                        .put("evidence", "核对原始盘点照片、实际损坏及另行核实的发现时间");
        input.putObject("repair")
                .put(
                        "verifiedDiscoveryAt",
                        testClock
                                .instant()
                                .minusSeconds(5)
                                .truncatedTo(ChronoUnit.MILLIS)
                                .toString())
                .put("fault", "迟到盘点证据中的实际设备损坏")
                .put("impact", "HIGH")
                .put("physicalDamageChecked", true)
                .put("discoveryTimeVerified", true);
        return input;
    }

    private String damagePath(long observation) {
        return "/api/v1/inventory-late/tasks/948000/observations/" + observation + "/damage-repair";
    }

    private HttpResponse<String> decide(
            Session actor, long observation, long version, String key, ObjectNode body)
            throws Exception {
        return request(actor, "POST", damagePath(observation), body.toString(), key, version);
    }

    private JsonNode detail(long observation) throws Exception {
        return ok(
                request(
                        admin,
                        "GET",
                        "/api/v1/inventory-late/tasks/948000/observations/" + observation,
                        null,
                        null,
                        null));
    }

    private int todos(long observation) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM fams_todo WHERE business_type='INVENTORY_LATE' AND"
                    + " business_id=? AND closed_at IS NULL",
                Integer.class,
                observation);
    }

    private long version(long task) {
        return db.queryForObject(
                "SELECT version FROM fams_inventory_task WHERE id=?", Long.class, task);
    }

    private long assetVersion() {
        return db.queryForObject("SELECT version FROM fams_asset WHERE id=948010", Long.class);
    }

    private Map<String, Object> task() {
        return db.queryForMap("SELECT * FROM fams_inventory_task WHERE id=948000");
    }

    private Map<String, Object> report() {
        return db.queryForMap("SELECT * FROM fams_inventory_sealed_report WHERE task_id=948000");
    }

    private Map<String, Object> raw(long observation) {
        return db.queryForMap("SELECT * FROM fams_inventory_observation WHERE id=?", observation);
    }

    private Map<String, Object> lateCase(long observation) {
        return db.queryForMap(
                "SELECT * FROM fams_inventory_late_case WHERE observation_id=?", observation);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private JsonNode ok(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
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
                                                        Map.of(
                                                                "username",
                                                                "seal" + number,
                                                                "password",
                                                                PASSWORD))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        var result = ok(response);
        return new Session(
                response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                result.path("csrfToken").asText());
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

    private HttpResponse<String> uploadPhoto(
            Session actor, String task, JsonNode event, String key, byte[] bytes) throws Exception {
        String boundary = "famsLateDamagePhoto";
        var body = new java.io.ByteArrayOutputStream();
        body.write(
                ("--"
                                + boundary
                                + "\r\n"
                                + "Content-Disposition: form-data; name=\"file\";"
                                + " filename=\"damage.png\"\r\n"
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
