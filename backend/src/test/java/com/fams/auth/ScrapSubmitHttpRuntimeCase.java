package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

@SpringBootTest(
        classes = {HandoverHttpTest.TestApplication.class, ScrapSubmitHttpRuntimeCase.AttachmentFixtures.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=fams-product",
            "spring.flyway.locations=classpath:db/migration/d6_04",
            "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
            "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra"
        })
@EnabledIfSystemProperty(named = "d6.22.scrap.submit.runtime", matches = "true")
class ScrapSubmitHttpRuntimeCase {
    private static final String INPUT = """
            {"assetId":"951020","assetVersion":0,"reason":"不能继续使用",
             "observedCondition":"原机损坏","proposedDisposition":"等待批准并交回处置"}
            """;
    private final RepairHttpRuntimeCase fixture = new RepairHttpRuntimeCase();
    private final HttpClient http = HttpClient.newHttpClient();

    private static final java.util.concurrent.atomic.AtomicReference<com.fams.attachment.MalwareScanner.Result> SCAN =
            new java.util.concurrent.atomic.AtomicReference<>(com.fams.attachment.MalwareScanner.Result.CLEAN);
    private static final java.util.concurrent.atomic.AtomicReference<Runnable> DURING_SCAN =
            new java.util.concurrent.atomic.AtomicReference<>();

    @org.springframework.boot.test.context.TestConfiguration
    static class AttachmentFixtures {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.fams.attachment.MalwareScanner scrapScanner() {
            return input -> {
                org.junit.jupiter.api.Assertions.assertFalse(org.springframework.transaction.support
                        .TransactionSynchronizationManager.isActualTransactionActive());
                var hook = DURING_SCAN.getAndSet(null);
                if (hook != null) {
                    hook.run();
                }
                return SCAN.get();
            };
        }
    }

    @Autowired JdbcTemplate db;
    @Autowired MiniSessionService miniSessions;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        RepairHttpRuntimeCase.database(registry);
    }

    @BeforeEach
    void seed() throws Exception {
        fixture.db = db;
        fixture.port = port;
        fixture.miniSessions = miniSessions;
        fixture.seed();
        SCAN.set(com.fams.attachment.MalwareScanner.Result.CLEAN);
        DURING_SCAN.set(null);
    }

    @AfterEach
    void cleanup() throws Exception {
        SCAN.set(com.fams.attachment.MalwareScanner.Result.CLEAN);
        DURING_SCAN.set(null);
        db.execute("DROP TRIGGER IF EXISTS owned_scrap_audit_failure");
        db.update("DELETE FROM fams_attachment WHERE business_type IN ('SCRAP','SCRAP_DISPOSITION')"
                + " AND uploader_account_id BETWEEN 951001 AND 951010");
        db.update("DELETE FROM fams_attachment_scope WHERE business_type IN ('SCRAP','SCRAP_DISPOSITION')"
                + " AND business_id IN (SELECT id FROM fams_scrap_request WHERE asset_id=951020)");
        db.update("DELETE FROM fams_account_authorization WHERE account_id BETWEEN 951001 AND 951010");
        String requests = " IN (SELECT id FROM fams_scrap_request WHERE asset_id=951020)";
        db.update("DELETE FROM fams_todo WHERE business_type='SCRAP' AND business_id" + requests);
        db.update("DELETE FROM fams_scrap_disposition WHERE request_id" + requests);
        db.update("DELETE FROM fams_scrap_event WHERE request_id" + requests);
        db.update("DELETE FROM fams_scrap_receipt WHERE scrap_request_id" + requests);
        db.update("DELETE FROM fams_scrap_request WHERE asset_id=951020");
        fixture.cleanup();
    }

    @Test
    void employeeSubmissionIsAtomicAndOriginalReceiptSurvivesAssetFreeze() throws Exception {
        String key = UUID.randomUUID().toString();
        var before = db.queryForMap("SELECT * FROM fams_asset WHERE id=951020");
        assertEquals(403, submit(fixture.other, key, INPUT).statusCode());
        assertEquals(400, submit(fixture.employee, key, INPUT.replace("\"assetVersion\":0,", "")).statusCode());
        db.execute("CREATE TRIGGER owned_scrap_audit_failure BEFORE INSERT ON fams_audit_event"
                + " FOR EACH ROW BEGIN IF NEW.action='SCRAP_SUBMITTED' THEN SIGNAL"
                + " SQLSTATE '45000' SET MESSAGE_TEXT='owned scrap audit failure'; END IF; END");
        try {
            assertEquals(500, submit(fixture.employee, key, INPUT).statusCode());
            assertEquals(before, db.queryForMap("SELECT * FROM fams_asset WHERE id=951020"));
            assertEquals(0L, db.queryForObject(
                    "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=951020", Long.class));
            assertEquals(0L, db.queryForObject(
                    "SELECT COUNT(*) FROM fams_scrap_request WHERE asset_id=951020", Long.class));
        } finally {
            db.execute("DROP TRIGGER owned_scrap_audit_failure");
        }
        var first = submit(fixture.employee, key, INPUT);
        assertEquals(200, first.statusCode(), first.body());
        assertEquals(first.body(), submit(fixture.employee, key, INPUT).body());
        assertEquals(409, submit(fixture.employee, key, INPUT.replace("原机损坏", "情况有变化")).statusCode());
        assertEquals("PENDING_SCRAP", db.queryForObject(
                "SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class));
        assertEquals(before.get("responsible_employee_id"), db.queryForObject(
                "SELECT responsible_employee_id FROM fams_asset WHERE id=951020", Long.class));
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM fams_asset_business_lock"
                + " WHERE asset_id=951020 AND business_type='SCRAP' AND released_at IS NULL", Long.class));
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM fams_todo WHERE business_type='SCRAP'"
                + " AND todo_type='APPROVAL' AND todo_status='OPEN'", Long.class));
    }

    @Test
    void approvalKeepsFrozenStateAndOnlyOneWarehouseCanClaim() throws Exception {
        var json = new tools.jackson.databind.ObjectMapper();
        var submitted = json.readTree(submit(fixture.employee, UUID.randomUUID().toString(), INPUT).body());
        String id = submitted.path("id").asText();
        String decision = "{\"assetVersion\":1,\"lockVersion\":0,\"result\":\"APPROVE\","
                + "\"reason\":\"已核对实物状态与报废依据\",\"approvedDisposition\":\"待处置保管\"}";
        String key = UUID.randomUUID().toString();
        assertEquals(403, write(fixture.employee, id, "decision", 0, key, decision).statusCode());
        var approved = write(fixture.admin, id, "decision", 0, key, decision);
        assertEquals(200, approved.statusCode(), approved.body());
        assertEquals(approved.body(), write(fixture.admin, id, "decision", 0, key, decision).body());
        assertEquals("PENDING_SCRAP", db.queryForObject(
                "SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class));
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_scrap_disposition", Long.class));
        String claim = "{\"assetVersion\":1,\"lockVersion\":0}";
        String claimKey = UUID.randomUUID().toString();
        var claimed = write(fixture.warehouse, id, "claim", 1, claimKey, claim);
        assertEquals(200, claimed.statusCode(), claimed.body());
        assertEquals(claimed.body(), write(fixture.warehouse, id, "claim", 1, claimKey, claim).body());
        assertEquals(409, write(fixture.otherWarehouse, id, "claim", 1,
                UUID.randomUUID().toString(), claim).statusCode());
        assertEquals(1L, db.queryForObject("SELECT ownership_version FROM fams_scrap_request"
                + " WHERE id=?", Long.class, Long.parseLong(id)));

        var execution = json.createObjectNode().put("assetVersion", 1).put("lockVersion", 0)
                .put("ownershipVersion", 1).put("observedCode", "REP-ASSET-951020")
                .put("locationId", "951001").put("locationVersion", 0)
                .put("custodianEmployeeId", "951003").put("custodianVersion", 0)
                .put("occurredAt", java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString())
                .put("physicalIdentityVerified", true).put("evidence", "原处理库管已现场核对实物身份及待处置保管依据")
                .put("dispositionMethod", "待处置保管");
        String executeKey = UUID.randomUUID().toString();
        String input = json.writeValueAsString(execution);
        assertEquals(403, write(fixture.otherWarehouse, id, "execute", 2, executeKey, input).statusCode());
        assertEquals(409, write(fixture.warehouse, id, "execute", 2, executeKey,
                input.replace("REP-ASSET-951020", "WRONG-ORIGINAL")).statusCode());
        db.execute("CREATE TRIGGER owned_scrap_audit_failure BEFORE INSERT ON fams_audit_event"
                + " FOR EACH ROW BEGIN IF NEW.action='SCRAP_EXECUTED' THEN SIGNAL"
                + " SQLSTATE '45000' SET MESSAGE_TEXT='owned scrap execution audit failure'; END IF; END");
        try {
            assertEquals(500, write(fixture.warehouse, id, "execute", 2, executeKey, input).statusCode());
            assertEquals("PENDING_SCRAP", db.queryForObject(
                    "SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class));
            assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_scrap_disposition", Long.class));
        } finally {
            db.execute("DROP TRIGGER owned_scrap_audit_failure");
        }
        var executed = write(fixture.warehouse, id, "execute", 2, executeKey, input);
        assertEquals(200, executed.statusCode(), executed.body());
        assertEquals(executed.body(), write(fixture.warehouse, id, "execute", 2, executeKey, input).body());
        assertEquals("SCRAPPED", db.queryForObject(
                "SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class));
        assertEquals("PENDING_DISPOSITION", db.queryForObject(
                "SELECT disposition_status FROM fams_scrap_disposition WHERE request_id=?", String.class, Long.parseLong(id)));
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_asset_business_lock"
                + " WHERE asset_id=951020 AND released_at IS NULL", Long.class));
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM fams_todo WHERE business_type='SCRAP'"
                + " AND business_id=? AND todo_type='DISPOSITION' AND todo_status='OPEN'", Long.class, Long.parseLong(id)));
    }

    @Test
    void rejectionRestoresValidCustodyAndKeepsInvalidCustodyFrozen() throws Exception {
        var json = new tools.jackson.databind.ObjectMapper();
        var first = json.readTree(submit(fixture.employee, UUID.randomUUID().toString(), INPUT).body());
        String body = "{\"assetVersion\":1,\"lockVersion\":0,\"result\":\"REJECT\","
                + "\"reason\":\"经核对无需报废\"}";
        var rejected = write(fixture.admin, first.path("id").asText(), "decision", 0,
                UUID.randomUUID().toString(), body);
        assertEquals(200, rejected.statusCode(), rejected.body());
        assertEquals("REJECTED", json.readTree(rejected.body()).path("status").asText());
        assertEquals("IN_USE", db.queryForObject(
                "SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class));
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_asset_business_lock"
                + " WHERE asset_id=951020 AND released_at IS NULL", Long.class));
        var next = json.readTree(submit(fixture.employee, UUID.randomUUID().toString(),
                INPUT.replace("\"assetVersion\":0", "\"assetVersion\":2")).body());
        db.update("UPDATE fams_employee SET status='DISABLED' WHERE id=951002");
        var frozen = write(fixture.admin, next.path("id").asText(), "decision", 0,
                UUID.randomUUID().toString(), body.replace("\"assetVersion\":1", "\"assetVersion\":3"));
        assertEquals(200, frozen.statusCode(), frozen.body());
        assertEquals("RESTORE_REQUIRED", json.readTree(frozen.body()).path("status").asText());
        assertEquals("PENDING_SCRAP", db.queryForObject(
                "SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class));
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM fams_asset_business_lock"
                + " WHERE asset_id=951020 AND released_at IS NULL", Long.class));
    }

    @Test
    void cancellationRestoresValidCustodyAndRetainsInvalidCustodyForCorrection() throws Exception {
        var json = new tools.jackson.databind.ObjectMapper();
        var submitted = submit(fixture.employee, UUID.randomUUID().toString(), INPUT);
        assertEquals(200, submitted.statusCode(), submitted.body());
        String id = json.readTree(submitted.body()).path("id").asText();
        String body = "{\"assetVersion\":1,\"lockVersion\":0,\"reason\":\"撤回报废申请\"}";
        assertEquals(403, write(fixture.other, id, "cancel", 0, UUID.randomUUID().toString(), body).statusCode());
        assertEquals(409, write(fixture.employee, id, "cancel", 1, UUID.randomUUID().toString(), body).statusCode());
        String key = UUID.randomUUID().toString();
        var cancelled = write(fixture.employee, id, "cancel", 0, key, body);
        assertEquals(200, cancelled.statusCode(), cancelled.body());
        assertEquals("CANCELLED", json.readTree(cancelled.body()).path("status").asText());
        assertEquals(cancelled.body(), write(fixture.employee, id, "cancel", 0, key, body).body());
        assertEquals("IN_USE", db.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class));
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_asset_business_lock"
                + " WHERE asset_id=951020 AND released_at IS NULL", Long.class));
        submitted = submit(fixture.employee, UUID.randomUUID().toString(), INPUT.replace("\"assetVersion\":0", "\"assetVersion\":2"));
        assertEquals(200, submitted.statusCode(), submitted.body());
        id = json.readTree(submitted.body()).path("id").asText();
        var approval = write(fixture.admin, id, "decision", 0, UUID.randomUUID().toString(),
                "{\"assetVersion\":3,\"lockVersion\":0,\"result\":\"APPROVE\",\"reason\":\"核对完毕\",\"approvedDisposition\":\"销毁\"}");
        assertEquals(200, approval.statusCode(), approval.body());
        body = body.replace("\"assetVersion\":1", "\"assetVersion\":3");
        assertEquals(409, write(fixture.employee, id, "cancel", 1, UUID.randomUUID().toString(), body).statusCode());
        db.update("UPDATE fams_employee SET status='DISABLED' WHERE id=951002");
        var frozen = write(fixture.admin, id, "cancel", 1, UUID.randomUUID().toString(), body);
        assertEquals(200, frozen.statusCode(), frozen.body());
        assertEquals("RESTORE_REQUIRED", json.readTree(frozen.body()).path("status").asText());
        assertEquals("PENDING_SCRAP", db.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class));
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM fams_asset_business_lock"
                + " WHERE asset_id=951020 AND released_at IS NULL", Long.class));
        assertEquals(List.of("RESTORE_REQUIRED"), db.queryForList("SELECT todo_type FROM fams_todo"
                + " WHERE business_type='SCRAP' AND business_id=? AND todo_status='OPEN'", String.class, id));
    }

    @Test
    void queryScopeHidesPendingRequestsAndExecutionCatalogFromOtherActors() throws Exception {
        var json = new tools.jackson.databind.ObjectMapper();
        var submitted = json.readTree(submit(fixture.employee, UUID.randomUUID().toString(), INPUT).body());
        String id = submitted.path("id").asText();
        assertEquals(200, get(fixture.employee, "/" + id).statusCode());
        assertEquals(200, get(fixture.admin, "/" + id).statusCode());
        assertEquals(404, get(fixture.other, "/" + id).statusCode());
        assertEquals(404, get(fixture.warehouse, "/" + id).statusCode());
        assertEquals(403, get(fixture.system, "/" + id).statusCode());
        assertEquals(0, json.readTree(get(fixture.other, "?page=1&pageSize=20").body()).path("total").asInt());
        var employee = json.readTree(get(fixture.employee, "/" + id).body());
        assertEquals(id, employee.path("id").asText());
        assertEquals("REP-ASSET-951020", employee.path("source").path("assetCode").asText());
        assertEquals(0, json.readTree(get(fixture.employee, "/eligible").body()).path("items").size());
        String approve = "{\"assetVersion\":1,\"lockVersion\":0,\"result\":\"APPROVE\","
                + "\"reason\":\"核对后批准报废\",\"approvedDisposition\":\"待处置保管\"}";
        var approved = write(fixture.admin, id, "decision", 0, UUID.randomUUID().toString(), approve);
        assertEquals(200, approved.statusCode(), approved.body());
        assertEquals(200, get(fixture.warehouse, "/" + id).statusCode());
        assertEquals(403, get(fixture.warehouse, "/" + id + "/execution-catalog/employee").statusCode());
        assertEquals(200, write(fixture.warehouse, id, "claim", 1, UUID.randomUUID().toString(),
                "{\"assetVersion\":1,\"lockVersion\":0}").statusCode());
        assertEquals(200, get(fixture.warehouse, "/" + id + "/execution-catalog/employee").statusCode());
        assertEquals(403, get(fixture.otherWarehouse, "/" + id + "/execution-catalog/location").statusCode());
    }

    @Test
    void nativePageUsesOpaqueSessionsAndReplaysCommittedExecution() throws Exception {
        var json = new tools.jackson.databind.ObjectMapper();
        var grants = new java.util.LinkedHashMap<String, Object>();
        grantField(951001, "ASSET_ADMIN");
        grantField(951002, "EMPLOYEE");
        grantField(951003, "WAREHOUSE_OPERATOR");
        for (var entry : java.util.Map.of("employee", 951002L, "admin", 951001L, "warehouse", 951003L).entrySet()) {
            var identity = new MiniWechatIdentityPort.Identity("owned-scrap-native", entry.getKey());
            db.update("INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES (?,?,NOW(3))",
                    entry.getValue(), miniSessions.identityHash(identity));
            var grant = miniSessions.start(identity, UUID.randomUUID());
            grants.put(entry.getKey(), java.util.Map.of("sessionToken", grant.sessionToken(),
                    "accountId", entry.getValue().toString(), "bindingId", grant.bindingId()));
        }
        var script = java.nio.file.Path.of(System.getProperty("user.dir")).getParent()
                .resolve("apps/miniprogram/test/runtime-scrap-http.mjs");
        var builder = new ProcessBuilder("rtk", "proxy", System.getProperty("fams.test.node",
                System.getenv().getOrDefault("FAMS_TEST_NODE_EXECUTABLE", "node")), script.toString())
                .redirectErrorStream(true);
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_SCRAP_GRANTS", json.writeValueAsString(grants));
        var process = builder.start();
        try {
            org.junit.jupiter.api.Assertions.assertTrue(process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            org.junit.jupiter.api.Assertions.assertTrue(output.contains("\"nativeScrapExecuted\":true"), output);
            org.junit.jupiter.api.Assertions.assertTrue(output.contains("\"nativeScrapAttachments\":true"), output);
            org.junit.jupiter.api.Assertions.assertTrue(output.contains("\"nativeDispositionClosed\":true"), output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        assertEquals("SCRAPPED", db.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class));
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM fams_scrap_disposition WHERE request_id"
                + " IN (SELECT id FROM fams_scrap_request WHERE asset_id=951020)", Long.class));
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM fams_scrap_receipt WHERE operation LIKE 'EXECUTE:%'", Long.class));
        assertEquals(2L, db.queryForObject("SELECT COUNT(*) FROM fams_attachment WHERE business_type IN ('SCRAP','SCRAP_DISPOSITION')", Long.class));
        assertEquals(2L, db.queryForObject("SELECT COUNT(*) FROM fams_scrap_receipt WHERE operation LIKE 'ATTACHMENT:%'", Long.class));
        assertEquals("DISPOSED", db.queryForObject("SELECT disposition_status FROM fams_scrap_disposition", String.class));
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM fams_scrap_receipt"
                + " WHERE operation LIKE 'COMPLETE_DISPOSITION:%'", Long.class));

    }


    @Test
    void requestAttachmentsAreScannedAtomicVersionedAndRetriedAfterApproval() throws Exception {
        String id = scrapId();
        String key = UUID.randomUUID().toString();
        assertEquals(403, upload(fixture.other, id, "request", 0, key).statusCode());
        SCAN.set(com.fams.attachment.MalwareScanner.Result.INFECTED);
        assertEquals(422, upload(fixture.employee, id, "request", 0, key).statusCode());
        SCAN.set(com.fams.attachment.MalwareScanner.Result.UNAVAILABLE);
        assertEquals(503, upload(fixture.employee, id, "request", 0, key).statusCode());
        SCAN.set(com.fams.attachment.MalwareScanner.Result.CLEAN);
        db.execute("CREATE TRIGGER owned_scrap_audit_failure BEFORE INSERT ON fams_audit_event"
                + " FOR EACH ROW BEGIN IF NEW.action='SCRAP_ATTACHMENT_ADDED' THEN SIGNAL"
                + " SQLSTATE '45000' SET MESSAGE_TEXT='owned attachment audit failure'; END IF; END");
        assertEquals(500, upload(fixture.employee, id, "request", 0, key).statusCode());
        db.execute("DROP TRIGGER owned_scrap_audit_failure");
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_attachment WHERE business_type='SCRAP'", Long.class));
        assertEquals(0L, db.queryForObject("SELECT version FROM fams_scrap_request WHERE id=?", Long.class, Long.parseLong(id)));
        var uploaded = upload(fixture.employee, id, "request", 0, key);
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        try (var files = java.nio.file.Files.list(RepairHttpRuntimeCase.photoRoot)) {
            assertEquals(1L, files.count());
        }
        assertEquals(uploaded.body(), upload(fixture.employee, id, "request", 0, key).body());
        try (var files = java.nio.file.Files.list(RepairHttpRuntimeCase.photoRoot)) {
            assertEquals(1L, files.count());
        }
        String approval = approval();
        assertEquals(403, write(fixture.admin, id, "decision", 1, UUID.randomUUID().toString(), approval).statusCode());
        grantField(951001, "ASSET_ADMIN");
        var approved = write(fixture.admin, id, "decision", 1, UUID.randomUUID().toString(), approval);
        assertEquals(200, approved.statusCode(), approved.body());
        var json = new tools.jackson.databind.ObjectMapper();
        var snapshot = json.readTree(db.queryForObject("SELECT decision_snapshot_json FROM fams_scrap_request WHERE id=?",
                String.class, Long.parseLong(id)));
        assertEquals(1, snapshot.path("attachmentEvidence").size());
        String object = json.readTree(uploaded.body()).path("objectId").asText();
        var frozen = snapshot.path("attachmentEvidence").get(0);
        assertEquals(object, frozen.path("objectId").asText());
        assertEquals(db.queryForObject("SELECT content_sha256 FROM fams_attachment WHERE object_id=?", String.class, object),
                frozen.path("sha256").asText());
        org.junit.jupiter.api.Assertions.assertFalse(get(fixture.employee, "/" + id).body().contains(object));
        org.junit.jupiter.api.Assertions.assertFalse(get(fixture.admin, "").body().contains(object));
        assertEquals(uploaded.body(), upload(fixture.employee, id, "request", 0, key).body());
        assertEquals(409, upload(fixture.employee, id, "request", 2, UUID.randomUUID().toString()).statusCode());
        assertEquals(409, upload(fixture.employee, id, "request", 1, key).statusCode());
    }

    @Test
    void attachmentReadsRequireBothCurrentRecordScopeAndSensitiveGrant() throws Exception {
        String id = scrapId();
        var uploaded = upload(fixture.employee, id, "request", 0, UUID.randomUUID().toString());
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        var json = new tools.jackson.databind.ObjectMapper();
        String object = json.readTree(uploaded.body()).path("objectId").asText();
        String path = "/" + id + "/attachments/request";
        assertEquals(403, get(fixture.employee, path).statusCode());
        assertEquals(403, get(fixture.admin, path).statusCode());
        grantField(951002, "EMPLOYEE");
        grantField(951007, "EMPLOYEE");
        assertEquals(404, get(fixture.other, path).statusCode());
        assertEquals(200, get(fixture.employee, path).statusCode());
        var grant = write(fixture.employee, id, "attachments/request/" + object + "/download-grant",
                1, UUID.randomUUID().toString(), "{}");
        assertEquals(200, grant.statusCode(), grant.body());
        String url = json.readTree(grant.body()).path("url").asText();
        var content = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + url))
                .header("Cookie", fixture.employee.cookie()).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, content.statusCode());
        org.junit.jupiter.api.Assertions.assertArrayEquals(png(), content.body());
        assertEquals("no-store", content.headers().firstValue("cache-control").orElseThrow());
        assertEquals(404, get(fixture.employee, url.substring("/api/v1/scrap-requests".length())
                .replace("request/", "disposition/")).statusCode());
        db.update("DELETE FROM fams_account_field_permission WHERE authorization_id=?", 951002L);
        assertEquals(403, get(fixture.employee, path).statusCode());
        assertEquals(403, get(fixture.employee, url.substring("/api/v1/scrap-requests".length())).statusCode());
    }

    @Test
    void scanRaceRejectsStaleVersionAndDispositionEvidenceKeepsTerminalRequestImmutable() throws Exception {
        String id = scrapId();
        DURING_SCAN.set(() -> db.update("UPDATE fams_scrap_request SET version=version+1 WHERE id=?", Long.parseLong(id)));
        assertEquals(409, upload(fixture.employee, id, "request", 0, UUID.randomUUID().toString()).statusCode());
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_attachment WHERE business_type='SCRAP'", Long.class));
        var approved = write(fixture.admin, id, "decision", 1, UUID.randomUUID().toString(), approval());
        assertEquals(200, approved.statusCode(), approved.body());
        assertEquals(200, write(fixture.warehouse, id, "claim", 2, UUID.randomUUID().toString(),
                "{\"assetVersion\":1,\"lockVersion\":0}").statusCode());
        var json = new tools.jackson.databind.ObjectMapper();
        var execution = json.createObjectNode().put("assetVersion", 1).put("lockVersion", 0)
                .put("ownershipVersion", 1).put("observedCode", "REP-ASSET-951020")
                .put("locationId", "951001").put("locationVersion", 0)
                .put("custodianEmployeeId", "951003").put("custodianVersion", 0)
                .put("occurredAt", java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString())
                .put("physicalIdentityVerified", true).put("evidence", "核验原机并实际接收保管后执行报废等待处置")
                .put("dispositionMethod", "待处置保管");
        var executed = write(fixture.warehouse, id, "execute", 3, UUID.randomUUID().toString(), json.writeValueAsString(execution));
        assertEquals(200, executed.statusCode(), executed.body());
        String overdue = "/dispositions/overdue";
        var empty = get(fixture.admin, overdue);
        assertEquals(200, empty.statusCode(), empty.body());
        var date = java.time.LocalDate.parse(json.readTree(empty.body()).path("businessDate").asText());
        assertEquals(403, get(fixture.employee, overdue).statusCode());
        assertEquals(0, json.readTree(empty.body()).path("total").asInt());
        db.update("UPDATE fams_scrap_disposition SET due_date=? WHERE request_id=?", date, Long.parseLong(id));
        assertEquals(0, json.readTree(get(fixture.admin, overdue).body()).path("total").asInt());
        db.update("UPDATE fams_scrap_disposition SET due_date=? WHERE request_id=?", date.minusDays(1), Long.parseLong(id));
        var overduePage = json.readTree(get(fixture.admin, overdue + "?page=1&pageSize=1").body());
        assertEquals(1, overduePage.path("total").asInt());
        assertEquals(id, overduePage.path("items").get(0).path("id").asText());
        assertEquals(date.minusDays(1).toString(), overduePage.path("items").get(0).path("disposition").path("due_date").asText());
        assertEquals(0, json.readTree(get(fixture.admin, overdue + "?page=2&pageSize=1").body()).path("items").size());
        assertEquals(1, json.readTree(get(fixture.warehouse, overdue).body()).path("total").asInt());
        assertEquals(0, json.readTree(get(fixture.otherWarehouse, overdue).body()).path("total").asInt());

        var before = db.queryForMap("SELECT * FROM fams_scrap_request WHERE id=?", Long.parseLong(id));
        String key = UUID.randomUUID().toString();
        assertEquals(403, upload(fixture.otherWarehouse, id, "disposition", 0, key).statusCode());
        var uploaded = upload(fixture.warehouse, id, "disposition", 0, key);
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        assertEquals(uploaded.body(), upload(fixture.warehouse, id, "disposition", 0, key).body());
        assertEquals(before, db.queryForMap("SELECT * FROM fams_scrap_request WHERE id=?", Long.parseLong(id)));
        assertEquals(1L, db.queryForObject("SELECT version FROM fams_scrap_disposition WHERE request_id=?", Long.class, Long.parseLong(id)));
        var capabilities = json.readTree(get(fixture.warehouse, "/" + id + "/attachment-capabilities").body());
        org.junit.jupiter.api.Assertions.assertTrue(capabilities.path("canUploadDisposition").asBoolean());
        org.junit.jupiter.api.Assertions.assertFalse(capabilities.path("canRead").asBoolean());
        var complete = json.createObjectNode().put("requestVersion", 4).put("ownershipVersion", 1)
                .put("observedCode", "REP-ASSET-951020").put("method", "待处置保管")
                .put("recipient", "现场处置对象").put("operatorEmployeeId", "951003").put("operatorVersion", 0)
                .put("occurredAt", java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString());
        String object = json.readTree(uploaded.body()).path("objectId").asText();
        complete.putArray("evidenceObjectIds").add(object);
        String action = "disposition/complete", completeKey = UUID.randomUUID().toString();
        String body = json.writeValueAsString(complete);
        assertEquals(403, write(fixture.otherWarehouse, id, action, 1, completeKey, body).statusCode());
        assertEquals(403, write(fixture.warehouse, id, action, 1, completeKey, body).statusCode());
        grantField(951003, "WAREHOUSE_OPERATOR");
        assertEquals(409, write(fixture.warehouse, id, action, 0, completeKey, body).statusCode());
        assertEquals(409, write(fixture.warehouse, id, action, 1, completeKey,
                body.replace(object, UUID.randomUUID().toString())).statusCode());
        assertEquals(409, write(fixture.warehouse, id, action, 1, completeKey,
                body.replace("REP-ASSET-951020", "WRONG-ORIGINAL")).statusCode());
        assertEquals(200, get(fixture.warehouse, "/" + id + "/execution-catalog/employee").statusCode());
        assertEquals(403, get(fixture.otherWarehouse, "/" + id + "/execution-catalog/employee").statusCode());
        var asset = db.queryForMap("SELECT * FROM fams_asset WHERE id=951020");
        var pending = db.queryForMap("SELECT * FROM fams_scrap_disposition WHERE request_id=?", Long.parseLong(id));
        db.execute("CREATE TRIGGER owned_scrap_audit_failure BEFORE INSERT ON fams_audit_event"
                + " FOR EACH ROW BEGIN IF NEW.action='SCRAP_DISPOSITION_COMPLETED' THEN SIGNAL"
                + " SQLSTATE '45000' SET MESSAGE_TEXT='owned disposition audit failure'; END IF; END");
        assertEquals(500, write(fixture.warehouse, id, action, 1, completeKey, body).statusCode());
        db.execute("DROP TRIGGER owned_scrap_audit_failure");
        assertEquals(pending, db.queryForMap("SELECT * FROM fams_scrap_disposition WHERE request_id=?", Long.parseLong(id)));
        assertEquals("OPEN", db.queryForObject("SELECT todo_status FROM fams_todo WHERE business_type='SCRAP'"
                + " AND business_id=? AND todo_type='DISPOSITION'", String.class, Long.parseLong(id)));
        var closed = write(fixture.warehouse, id, action, 1, completeKey, body);
        assertEquals(200, closed.statusCode(), closed.body());
        assertEquals("DISPOSED", json.readTree(closed.body()).path("status").asText());
        assertEquals(0, json.readTree(get(fixture.admin, overdue).body()).path("total").asInt());
        assertEquals(2, json.readTree(closed.body()).path("version").asInt());
        assertEquals(closed.body(), write(fixture.warehouse, id, action, 1, completeKey, body).body());
        assertEquals(409, write(fixture.warehouse, id, action, 1, completeKey,
                body.replace("现场处置对象", "另一处置对象")).statusCode());
        assertEquals(409, write(fixture.warehouse, id, action, 2, UUID.randomUUID().toString(), body).statusCode());
        assertEquals(before, db.queryForMap("SELECT * FROM fams_scrap_request WHERE id=?", Long.parseLong(id)));
        assertEquals(asset, db.queryForMap("SELECT * FROM fams_asset WHERE id=951020"));
        assertEquals("CLOSED", db.queryForObject("SELECT todo_status FROM fams_todo WHERE business_type='SCRAP'"
                + " AND business_id=? AND todo_type='DISPOSITION'", String.class, Long.parseLong(id)));
        String publicDetail = get(fixture.employee, "/" + id).body();
        assertEquals("现场处置对象", json.readTree(publicDetail).path("disposition").path("completion").path("recipient").asText());
        org.junit.jupiter.api.Assertions.assertFalse(publicDetail.contains(object));
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM fams_audit_event WHERE resource_type='SCRAP_DISPOSITION'"
                + " AND resource_id=? AND action='SCRAP_DISPOSITION_COMPLETED'", Long.class, Long.parseLong(id)));

    }

    private void grantField(long account, String role) {
        db.update("INSERT INTO fams_account_authorization(id,account_id,role_code,generation_no,authorization_status,"
                + "change_reason,created_at) VALUES (?,?,?,1,'ACTIVE','owned scrap field grant',NOW(3))", account, account, role);
        db.update("INSERT INTO fams_account_field_permission(authorization_id,permission_code,granted_at)"
                + " VALUES (?,'SCRAP_ATTACHMENT',NOW(3))", account);
    }

    private String scrapId() throws Exception {
        var submitted = submit(fixture.employee, UUID.randomUUID().toString(), INPUT);
        assertEquals(200, submitted.statusCode(), submitted.body());
        return new tools.jackson.databind.ObjectMapper().readTree(submitted.body()).path("id").asText();
    }

    private String approval() {
        return "{\"assetVersion\":1,\"lockVersion\":0,\"result\":\"APPROVE\","
                + "\"reason\":\"已核对原机和报废依据\",\"approvedDisposition\":\"待处置保管\"}";
    }

    private byte[] png() {
        return java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a0WQAAAAASUVORK5CYII=");
    }

    private HttpResponse<String> upload(Session session, String id, String kind, long version, String key) throws Exception {
        String boundary = "owned-scrap-" + UUID.randomUUID();
        var bytes = new java.io.ByteArrayOutputStream();
        bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"evidence.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        bytes.write(png());
        bytes.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/scrap-requests/"
                + id + "/attachments/" + kind)).header("Cookie", session.cookie()).header("X-CSRF-Token", session.csrf())
                .header("If-Match-Version", Long.toString(version)).header("Idempotency-Key", key)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(Session session, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + port + "/api/v1/scrap-requests" + path))
                .header("Cookie", session.cookie()).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> write(Session session, String id, String action, long version,
            String key, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/scrap-requests/" + id + "/" + action))
                .header("Content-Type", "application/json").header("Cookie", session.cookie())
                .header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", key)
                .header("If-Match-Version", Long.toString(version))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> submit(Session session, String key, String input) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/scrap-requests"))
                .header("Content-Type", "application/json")
                .header("Cookie", session.cookie())
                .header("X-CSRF-Token", session.csrf())
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(input))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
