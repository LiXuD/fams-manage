package com.fams.assetimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fams.job.DurableJobService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = {AssetImportHttpRuntimeCase.TestApplication.class,
        AssetImportHttpRuntimeCase.CleanScanner.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.profiles.active=fams-product",
                "fams.asset-import.worker.enabled=false",
                "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
                "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra"})
@EnabledIfSystemProperty(named = "d6.08.import.http.runtime", matches = "true")
class AssetImportHttpRuntimeCase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path quarantine;
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate db;
    @Autowired DurableJobService jobs;
    @Autowired AssetImportBatchService batches;
    @Autowired AssetImportReviewService reviews;
    @Autowired AssetImportPublicationService publication;
    @Autowired AssetImportRollbackService rollbacks;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> required("D6_08_TEST_DATASOURCE_URL"));
        registry.add("spring.datasource.username", () -> required("D6_08_TEST_DATASOURCE_USERNAME"));
        registry.add("spring.datasource.password", () -> required("D6_08_TEST_DATASOURCE_PASSWORD"));
        registry.add("fams.attachments.quarantine-root", () -> quarantine.toString());
    }

    @Test
    void warehouseUploadPreviewAndErrorRowsUseRealSessionAndMysql() throws Exception {
        seedAccounts();
        assertInvalidImportRequestBoundaries();
        var warehouse = login("worker");
        var viewer = login("viewer");
        assertEquals(403, request("GET", "/asset-import-batches/template", null, viewer,
                null, null).statusCode());
        var template = request("GET", "/asset-import-batches/template", null, warehouse, null, null);
        assertEquals(200, template.statusCode());
        assertTrue(template.body().length > 100);
        byte[] file = workbook(template.body());
        var uploaded = request("POST", "/asset-import-batches", multipart(file), warehouse,
                "multipart/form-data; boundary=fams-test-boundary", "http-import-key-0001");
        assertEquals(201, uploaded.statusCode(), body(uploaded));
        String id = JSON.readTree(body(uploaded)).get("id").asText();
        assertEquals(id, JSON.readTree(body(request("POST", "/asset-import-batches", multipart(file),
                warehouse, "multipart/form-data; boundary=fams-test-boundary",
                "http-import-key-0001"))).get("id").asText());
        assertEquals(403, request("GET", "/asset-import-batches/" + id, null, viewer,
                null, null).statusCode());
        assertEquals(200, request("GET", "/asset-import-batches/" + id, null, warehouse,
                null, null).statusCode());
        var columns = request("GET", "/asset-import-batches/" + id + "/columns", null,
                warehouse, null, null);
        assertEquals(200, columns.statusCode(), body(columns));
        assertEquals("资产编码", JSON.readTree(body(columns)).get("columns").get(0).asText());
        var preview = request("POST", "/asset-import-batches/" + id + "/preview",
                "{}".getBytes(StandardCharsets.UTF_8), warehouse, "application/json", null, 0L);
        assertEquals(200, preview.statusCode(), body(preview));
        assertEquals(1, JSON.readTree(body(preview)).get("readyRows").asInt());
        assertEquals(1, JSON.readTree(body(preview)).get("errorRows").asInt());
        var reopened = request("GET", "/asset-import-batches/" + id, null, warehouse, null, null);
        assertEquals("资产编码", JSON.readTree(body(reopened)).get("mapping").get("assetCode").asText());
        assertEquals(409, request("POST", "/asset-import-batches/" + id + "/preview",
                "{}".getBytes(StandardCharsets.UTF_8), warehouse, "application/json", null, 0L)
                .statusCode());
        var errors = request("GET", "/asset-import-batches/" + id + "/rows?status=ERROR",
                null, warehouse, null, null);
        assertEquals(200, errors.statusCode(), body(errors));
        assertEquals("DUPLICATE_ASSET_CODE", JSON.readTree(body(errors)).get("items").get(0)
                .get("errors").get(0).get("reason").asText());
        assertEquals("资产", JSON.readTree(body(errors)).get("items").get(0)
                .get("errors").get(0).get("sheet").asText());
        var report = request("GET", "/asset-import-batches/" + id + "/preflight-report",
                null, warehouse, null, null);
        assertEquals(200, report.statusCode(), body(report));
        assertTrue(body(report).contains("DUPLICATE_ASSET_CODE"));
        assertEquals(403, request("GET", "/asset-import-batches/" + id + "/preflight-report",
                null, viewer, null, null).statusCode());
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM fams_asset_import_row "
                + "WHERE batch_id=?", Integer.class, id));
        UUID previewedBatch = UUID.fromString(id);
        assertThrows(IllegalStateException.class, () -> reviews.request(1, previewedBatch, 2));
        assertThrows(IllegalStateException.class, () -> reviews.request(1, previewedBatch, 1));
        byte[] windowRequest = ("{\"assignedOperatorId\":\"1\",\"reason\":\"initial load\","
                + "\"expiresAt\":\"" + Instant.now().plusSeconds(3600) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(403, request("POST", "/asset-initialization-windows", windowRequest,
                warehouse, "application/json", null).statusCode());
        db.update("INSERT INTO fams_account_role (account_id,role_code,granted_at) "
                + "VALUES (1,'SYSTEM_ADMIN',NOW())");
        var administrator = login("worker");
        var opened = request("POST", "/asset-initialization-windows", windowRequest,
                administrator, "application/json", null);
        assertEquals(201, opened.statusCode(), body(opened));
        long windowId = JSON.readTree(body(opened)).get("id").asLong();
        assertEquals(200, request("GET", "/asset-initialization-windows/current", null,
                administrator, null, null).statusCode());
        assertTrue(body(request("GET", "/asset-initialization-windows/operators", null,
                administrator, null, null)).contains("worker"));
        assertEquals(409, request("POST", "/asset-initialization-windows", windowRequest,
                administrator, "application/json", null).statusCode());
        var review = request("POST", "/asset-import-batches/" + id + "/review", null,
                warehouse, null, null, 1L);
        assertEquals(200, review.statusCode(), body(review));
        assertEquals("PENDING", JSON.readTree(body(review)).get("status").asText());
        assertEquals(200, request("POST", "/asset-import-batches/" + id + "/review", null,
                warehouse, null, null, 1L).statusCode());
        assertEquals(409, request("POST", "/asset-import-batches/" + id + "/preview",
                "{}".getBytes(StandardCharsets.UTF_8), warehouse, "application/json", null, 1L)
                .statusCode());
        byte[] decision = ("{\"approved\":true,\"reason\":\"verified sample\","
                + "\"confirmedReadyRows\":1,\"confirmedErrorRows\":1,"
                + "\"sampleVerified\":true}")
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(403, request("POST", "/asset-import-batches/" + id + "/decision",
                decision, viewer, "application/json", null).statusCode());
        Timestamp now = Timestamp.from(Instant.now());
        db.update("INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,organization_id,"
                + "status,created_at,updated_at) VALUES (3,'E3','E3','Administrator',1,'ACTIVE',?,?)", now, now);
        db.update("INSERT INTO fams_system_account (id,employee_id,username,username_normalized,password_hash,"
                + "status,initial_password_required,created_at,updated_at) "
                + "VALUES (3,3,'administrator','ADMINISTRATOR',?,'ENABLED',FALSE,?,?)",
                new BCryptPasswordEncoder(4).encode(PASSWORD), now, now);
        db.update("INSERT INTO fams_account_role (account_id,role_code,granted_at) "
                + "VALUES (3,'ASSET_ADMIN',?)", now);
        var assetAdmin = login("administrator");
        assertTrue(body(request("GET", "/asset-import-batches/reviews/pending", null,
                assetAdmin, null, null)).contains(id));
        byte[] rejection = "{\"approved\":false,\"reason\":\"recount needed\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(200, request("POST", "/asset-import-batches/" + id + "/decision",
                rejection, assetAdmin, "application/json", null).statusCode());
        assertEquals(200, request("POST", "/asset-import-batches/" + id + "/review", null,
                warehouse, null, null, 1L).statusCode());
        assertEquals(200, request("GET", "/asset-import-batches/" + id + "/review-rows?status=ERROR",
                null, assetAdmin, null, null).statusCode());
        byte[] unverifiedDecision = "{\"approved\":true,\"reason\":\"unchecked\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(400, request("POST", "/asset-import-batches/" + id + "/decision",
                unverifiedDecision, assetAdmin, "application/json", null).statusCode());
        byte[] wrongCounts = "{\"approved\":true,\"reason\":\"wrong count\","
                .concat("\"confirmedReadyRows\":2,\"confirmedErrorRows\":1,\"sampleVerified\":true}")
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(400, request("POST", "/asset-import-batches/" + id + "/decision",
                wrongCounts, assetAdmin, "application/json", null).statusCode());
        UUID reviewedBatch = UUID.fromString(id);
        assertThrows(IllegalStateException.class,
                () -> reviews.decide(1, reviewedBatch, true, "self review", 1, 1, true));
        assertThrows(IllegalArgumentException.class,
                () -> reviews.decide(3, reviewedBatch, true, "missing ready", null, 1, true));
        assertThrows(IllegalArgumentException.class,
                () -> reviews.decide(3, reviewedBatch, true, "missing errors", 1, null, true));
        assertThrows(IllegalArgumentException.class,
                () -> reviews.decide(3, reviewedBatch, true, "wrong errors", 1, 2, true));
        var approved = request("POST", "/asset-import-batches/" + id + "/decision",
                decision, assetAdmin, "application/json", null);
        assertEquals(200, approved.statusCode(), body(approved));
        assertEquals("APPROVED", JSON.readTree(body(approved)).get("status").asText());
        assertEquals("QUEUED", JSON.readTree(body(request("GET", "/asset-import-batches/" + id,
                null, warehouse, null, null))).get("status").asText());
        assertEquals(200, request("GET", "/asset-import-batches/" + id + "/preflight-report",
                null, warehouse, null, null).statusCode());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_durable_job "
                + "WHERE job_kind='ASSET_IMPORT' AND business_reference=?", Integer.class, id));
        assertEquals(409, request("POST", "/asset-import-batches/" + id + "/decision",
                decision, assetAdmin, "application/json", null).statusCode());
        seedBusinessParameters();
        String restartMarker = System.getenv("D6_08_IMPORT_PHASE1_MARKER");
        if (restartMarker != null) {
            var claim = jobs.claim("ASSET_IMPORT", "import-process-one", Duration.ofSeconds(3))
                    .orElseThrow();
            assertEquals(id, claim.businessReference());
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_asset_import_publication "
                    + "WHERE batch_id=?", Integer.class, id));
            Files.writeString(Path.of(restartMarker), id);
            TimeUnit.MINUTES.sleep(5);
        }
        var worker = new AssetImportWorker(jobs, publication, rollbacks);
        if (System.getenv("D6_08_INJECT_PUBLICATION_FAILURE") != null) {
            db.execute("CREATE TRIGGER d6_08_fail_publication BEFORE INSERT "
                    + "ON fams_asset_import_publication FOR EACH ROW "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='D6_08_INJECTED'");
            long failedStarted = System.nanoTime();
            worker.poll();
            long failedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - failedStarted);
            assertEquals("FAILED", db.queryForObject("SELECT batch_status FROM fams_asset_import_batch "
                    + "WHERE id=?", String.class, id));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_asset_import_publication "
                    + "WHERE batch_id=?", Integer.class, id));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_asset "
                    + "WHERE asset_code='HTTP-A-1'", Integer.class));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_asset_tag t "
                    + "JOIN fams_asset a ON a.id=t.asset_id WHERE a.asset_code='HTTP-A-1'", Integer.class));
            worker.poll();
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_asset_import_publication "
                    + "WHERE batch_id=?", Integer.class, id));
            System.out.println("D6_08_PUBLICATION_FAILURE failed_ms=" + failedMs
                    + " asset=0 tag=0 publication=0 duplicate=0");
            return;
        }
        long publishedStarted = System.nanoTime();
        worker.poll();
        long publishedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - publishedStarted);
        assertEquals("PUBLISHED", JSON.readTree(body(request("GET", "/asset-import-batches/" + id,
                null, warehouse, null, null))).get("status").asText());
        openingPendingScrapFollowsRealApprovalAndRollback(template.body(), warehouse, assetAdmin, worker);
        openingRepairAndFactoryFollowExistingProcessing(template.body(), warehouse, assetAdmin, worker);
        var ledgerPage = request("GET", "/assets?page=1&pageSize=50", null,
                warehouse, null, null);
        assertEquals(200, ledgerPage.statusCode(), body(ledgerPage));
        assertTrue(body(ledgerPage).contains("HTTP-A-1"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_asset_import_publication "
                + "WHERE batch_id=?", Integer.class, id));
        var finalReport = request("GET", "/asset-import-batches/" + id + "/result",
                null, warehouse, null, null);
        assertEquals(200, finalReport.statusCode(), body(finalReport));
        assertTrue(body(finalReport).contains("HTTP-A-1"));
        assertTrue(body(finalReport).contains("DUPLICATE_ASSET_CODE"));
        assertEquals(403, request("GET", "/asset-import-batches/" + id + "/result",
                null, viewer, null, null).statusCode());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_asset a "
                + "JOIN fams_asset_import_publication p ON p.asset_id=a.id "
                + "WHERE p.batch_id=? AND a.lifecycle_status='IN_STOCK' AND a.card_stage='ACTIVE' "
                + "AND a.custody_type='STORAGE'", Integer.class, id));
        worker.poll();
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_asset_import_publication "
                + "WHERE batch_id=?", Integer.class, id));
        byte[] usageFile = usageWorkbook(template.body());
        var usageUpload = request("POST", "/asset-import-batches", multipart(usageFile), warehouse,
                "multipart/form-data; boundary=fams-test-boundary", "http-import-usage-0002");
        assertEquals(201, usageUpload.statusCode(), body(usageUpload));
        String usageId = JSON.readTree(body(usageUpload)).get("id").asText();
        var usagePreview = request("POST", "/asset-import-batches/" + usageId + "/preview",
                "{}".getBytes(StandardCharsets.UTF_8), warehouse, "application/json", null, 0L);
        assertEquals(200, usagePreview.statusCode(), body(usagePreview));
        assertEquals(3, JSON.readTree(body(usagePreview)).get("readyRows").asInt());
        assertEquals(200, request("POST", "/asset-import-batches/" + usageId + "/review", null,
                warehouse, null, null, 1L).statusCode());
        byte[] usageDecision = ("{\"approved\":true,\"reason\":\"verified usage sample\","
                + "\"confirmedReadyRows\":3,\"confirmedErrorRows\":0,"
                + "\"sampleVerified\":true}").getBytes(StandardCharsets.UTF_8);
        assertEquals(200, request("POST", "/asset-import-batches/" + usageId + "/decision",
                usageDecision, assetAdmin, "application/json", null).statusCode());
        worker.poll();
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_asset "
                + "WHERE asset_code='HTTP-P-1' AND lifecycle_status='IN_USE' "
                + "AND custody_type='PERSONAL' AND responsible_employee_id=2", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_asset "
                + "WHERE asset_code='HTTP-S-1' AND lifecycle_status='IN_USE' "
                + "AND custody_type='SHARED' AND department_responsible_employee_id=3", Integer.class));
        var initialLoan = db.queryForMap("SELECT l.* FROM fams_loan l JOIN fams_asset a ON a.id=l.asset_id"
                + " WHERE a.asset_code='HTTP-B-1'");
        assertEquals("INITIAL_IMPORT", initialLoan.get("source_type"));
        assertEquals(usageId, initialLoan.get("initial_batch_id"));
        assertEquals("2020-02-01", initialLoan.get("expected_return_date").toString());
        assertEquals("OPEN", initialLoan.get("relation_status"));
        assertEquals(null, initialLoan.get("source_request_id"));
        verifyBorrowRollback(template.body(), warehouse, assetAdmin, worker);
        byte[] rollbackReason = "{\"reason\":\"duplicate initial loading\"}"
                .getBytes(StandardCharsets.UTF_8);
        var extension = request("POST", "/loans/" + initialLoan.get("id") + "/extensions",
                "{\"expectedReturnDate\":\"2099-01-01\",\"reason\":\"retain initial borrowing\"}"
                        .getBytes(StandardCharsets.UTF_8), assetAdmin, "application/json",
                "initial-borrow-extension-0001", 0L);
        assertEquals(200, extension.statusCode(), body(extension));
        String extensionId = JSON.readTree(body(extension)).get("id").asText();
        var extensionDecision = request("POST", "/loans/extensions/" + extensionId + "/decision",
                "{\"approved\":true,\"reason\":\"confirmed extension\"}".getBytes(StandardCharsets.UTF_8),
                assetAdmin, "application/json", "initial-borrow-decision-0001", 0L);
        assertEquals(200, extensionDecision.statusCode(), body(extensionDecision));
        assertEquals(0L, db.queryForObject("SELECT version FROM fams_asset WHERE id=?", Long.class,
                initialLoan.get("asset_id")));
        assertEquals(1L, db.queryForObject("SELECT version FROM fams_loan WHERE id=?", Long.class,
                initialLoan.get("id")));
        assertEquals(409, request("POST", "/asset-import-batches/" + usageId + "/rollback",
                rollbackReason, warehouse, "application/json", null).statusCode());
        var rollbackRequest = request("POST", "/asset-import-batches/" + id + "/rollback",
                rollbackReason, warehouse, "application/json", null);
        assertEquals(200, rollbackRequest.statusCode(), body(rollbackRequest));
        assertEquals(200, request("POST", "/asset-import-batches/" + id + "/rollback",
                rollbackReason, warehouse, "application/json", null).statusCode());
        assertTrue(body(request("GET", "/asset-import-batches/rollbacks/pending", null,
                assetAdmin, null, null)).contains(id));
        assertEquals(200, request("POST", "/asset-import-batches/" + id + "/rollback-decision",
                rejection, assetAdmin, "application/json", null).statusCode());
        assertEquals(200, request("POST", "/asset-import-batches/" + id + "/rollback",
                rollbackReason, warehouse, "application/json", null).statusCode());
        assertEquals(403, request("POST", "/asset-import-batches/" + id + "/rollback-decision",
                decision, viewer, "application/json", null).statusCode());
        assertEquals(200, request("POST", "/asset-import-batches/" + id + "/rollback-decision",
                decision, assetAdmin, "application/json", null).statusCode());
        assertEquals(200, request("POST", "/asset-import-batches/" + id + "/rollback-execute",
                null, warehouse, null, null).statusCode());
        worker.poll();
        assertEquals("ROLLED_BACK", JSON.readTree(body(request("GET", "/asset-import-batches/" + id,
                null, warehouse, null, null))).get("status").asText());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_asset a "
                + "JOIN fams_asset_import_publication p ON p.asset_id=a.id "
                + "WHERE p.batch_id=? AND a.card_stage='CANCELLED' AND a.custody_type IS NULL "
                + "AND p.retired_at IS NOT NULL", Integer.class, id));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_asset_tag t "
                + "JOIN fams_asset_import_publication p ON p.asset_id=t.asset_id "
                + "WHERE p.batch_id=?", Integer.class, id));
        assertEquals(200, request("GET", "/asset-import-batches/" + id + "/result",
                null, warehouse, null, null).statusCode());
        Long usageAsset = db.queryForObject("SELECT asset_id FROM fams_asset_import_publication "
                + "WHERE batch_id=? ORDER BY sheet_row LIMIT 1", Long.class, usageId);
        db.update("INSERT INTO fams_asset_timeline_event (asset_id,event_type,business_type,business_id,"
                + "occurred_at,summary_json,actor_account_id) VALUES (?,'TEST_DOWNSTREAM','ASSET',?,NOW(),"
                + "'{}',1)", usageAsset, usageAsset);
        assertEquals(409, request("POST", "/asset-import-batches/" + usageId + "/rollback",
                rollbackReason, warehouse, "application/json", null).statusCode());
        var revokedUpload = request("POST", "/asset-import-batches",
                multipart(workbook(template.body(), "HTTP-REVOKED-1")), warehouse,
                "multipart/form-data; boundary=fams-test-boundary", "http-import-revoked-0003");
        assertEquals(201, revokedUpload.statusCode(), body(revokedUpload));
        String revokedId = JSON.readTree(body(revokedUpload)).get("id").asText();
        assertEquals(200, request("POST", "/asset-import-batches/" + revokedId + "/preview",
                "{}".getBytes(StandardCharsets.UTF_8), warehouse, "application/json", null, 0L)
                .statusCode());
        assertEquals(200, request("POST", "/asset-import-batches/" + revokedId + "/review",
                null, warehouse, null, null, 1L).statusCode());
        assertEquals(200, request("POST", "/asset-import-batches/" + revokedId + "/decision",
                decision, assetAdmin, "application/json", null).statusCode());
        db.update("DELETE FROM fams_account_role WHERE account_id=1 AND role_code='WAREHOUSE_OPERATOR'");
        long revokedStarted = System.nanoTime();
        worker.poll();
        long revokedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - revokedStarted);
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_asset_import_publication "
                + "WHERE batch_id=?", Integer.class, revokedId));
        assertEquals("FAILED", db.queryForObject("SELECT batch_status FROM fams_asset_import_batch "
                + "WHERE id=?", String.class, revokedId));
        System.out.println("D6_08_PUBLICATION published_ms=" + publishedMs + " revoked_ms=" + revokedMs
                + " published_rows=1 revoked_rows=0");
        assertEquals(200, request("POST", "/asset-initialization-windows/" + windowId + "/close",
                null, administrator, null, null).statusCode());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_initialization_window "
                + "WHERE id=? AND window_status='CLOSED'", Integer.class, windowId));
    }

    private void openingPendingScrapFollowsRealApprovalAndRollback(byte[] template,
            Session warehouse, Session assetAdmin, AssetImportWorker worker) throws Exception {
        long bin = db.queryForObject("SELECT id FROM fams_location WHERE code='LOC-1'", Long.class);
        long binVersion = db.queryForObject("SELECT version FROM fams_location WHERE id=?", Long.class, bin);
        long custodian = db.queryForObject("SELECT id FROM fams_employee WHERE employee_no_normalized='E3'",
                Long.class);
        long custodianVersion = db.queryForObject("SELECT version FROM fams_employee WHERE id=?",
                Long.class, custodian);
        var stocked = Map.ofEntries(Map.entry(0, "HTTP-SCRAP-A"), Map.entry(1, "Opening scrap A"),
                Map.entry(2, "CAT-1"), Map.entry(3, "ROOT"), Map.entry(4, "M1"), Map.entry(5, "piece"),
                Map.entry(7, "待报废"), Map.entry(8, "WH-1"), Map.entry(9, "LOC-1"), Map.entry(14, "在库"),
                Map.entry(15, "上线前已确认无法修复"), Map.entry(16, "实物已放置在待报废区"),
                Map.entry(17, "待处置保管"));
        var personal = Map.ofEntries(Map.entry(0, "HTTP-SCRAP-B"), Map.entry(1, "Opening scrap B"),
                Map.entry(2, "CAT-1"), Map.entry(3, "ROOT"), Map.entry(4, "M1"), Map.entry(5, "piece"),
                Map.entry(7, "待报废"), Map.entry(9, "LOC-1"), Map.entry(10, "ROOT"), Map.entry(11, "E3"),
                Map.entry(14, "在用个人"), Map.entry(15, "上线前已确认无法修复"),
                Map.entry(16, "使用人仍持有实物"), Map.entry(17, "待处置保管"));
        String batch = publishScrapBatch(template, warehouse, assetAdmin, worker,
                "http-import-scrap-open", List.of(stocked, personal), 2, 0);
        for (int row = 1; row <= 2; row++) {
            var asset = db.queryForMap("SELECT a.* FROM fams_asset a JOIN fams_asset_import_publication p"
                    + " ON p.asset_id=a.id WHERE p.batch_id=? AND p.sheet_row=?", batch, row + 1);
            assertEquals("HTTP-SCRAP-" + (char) ('A' + row - 1), asset.get("asset_code"));
            assertEquals("PENDING_SCRAP", asset.get("lifecycle_status"));
            assertEquals(row == 1 ? "STORAGE" : "PERSONAL", asset.get("custody_type"));
            assertEquals(0L, ((Number) asset.get("version")).longValue());
            var document = db.queryForMap("SELECT * FROM fams_scrap_request WHERE initial_batch_id=?"
                    + " AND initial_sheet_row=?", batch, row + 1);
            long requestId = ((Number) document.get("id")).longValue();
            assertEquals("STANDALONE", document.get("source_type"));
            assertEquals(null, document.get("source_business_id"));
            assertEquals("PENDING_ADMIN_DECISION", document.get("request_status"));
            assertEquals(0L, ((Number) document.get("version")).longValue());
            assertEquals(0L, ((Number) document.get("asset_version")).longValue());
            assertEquals(1L, ((Number) document.get("creator_account_id")).longValue());
            assertEquals("上线前已确认无法修复", document.get("reason"));
            assertEquals("待处置保管", document.get("proposed_disposition"));
            var snapshot = JSON.readTree(document.get("source_snapshot_json").toString());
            assertEquals(row == 1 ? "IN_STOCK" : "IN_USE", snapshot.path("lifecycle_status").asText());
            assertEquals(batch, snapshot.path("initialSource").path("batchId").asText());
            assertEquals(Integer.toString(row + 1), snapshot.path("initialSource").path("sheetRow").asText());
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=?"
                    + " AND business_type='SCRAP' AND business_id=? AND business_item_id=?"
                    + " AND released_at IS NULL", Integer.class, asset.get("id"), requestId, requestId));
            assertEquals(List.of("APPROVAL"), db.queryForList("SELECT todo_type FROM fams_todo"
                    + " WHERE business_type='SCRAP' AND business_id=? AND todo_status='OPEN'",
                    String.class, requestId));
            assertEquals("ASSET_ADMIN", db.queryForObject("SELECT pool_type FROM fams_todo"
                    + " WHERE business_type='SCRAP' AND business_id=?", String.class, requestId));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_scrap_event WHERE request_id=?"
                    + " AND action='SCRAP_INITIAL_OPENED'", Integer.class, requestId));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_scrap_receipt WHERE scrap_request_id=?",
                    Integer.class, requestId));
        }
        long asset = db.queryForObject("SELECT a.id FROM fams_asset a JOIN fams_asset_import_publication p"
                + " ON p.asset_id=a.id WHERE p.batch_id=? AND p.sheet_row=2", Long.class, batch);
        long opening = db.queryForObject("SELECT id FROM fams_scrap_request WHERE initial_batch_id=?"
                + " AND initial_sheet_row=2", Long.class, batch);
        var detail = JSON.readTree(body(request("GET", "/scrap-requests/" + opening, null, assetAdmin,
                null, null)));
        assertEquals(batch, detail.path("initialSource").path("batchId").asText());
        assertEquals("2", detail.path("initialSource").path("sheetRow").asText());
        assertEquals("PENDING_ADMIN_DECISION", detail.path("status").asText());
        assertEquals(true, detail.path("actions").path("decide").asBoolean());
        assertEquals(0L, detail.path("lockVersion").asLong());
        var approved = request("POST", "/scrap-requests/" + opening + "/decision",
                ("{\"assetVersion\":0,\"lockVersion\":0,\"result\":\"APPROVE\","
                        + "\"reason\":\"核对期初承接依据后批准处置范围\",\"approvedDisposition\":\"待处置保管\"}")
                        .getBytes(StandardCharsets.UTF_8), assetAdmin, "application/json",
                UUID.randomUUID().toString(), 0L);
        assertEquals(200, approved.statusCode(), body(approved));
        assertEquals("WAITING_WAREHOUSE_CLAIM", JSON.readTree(body(approved)).get("status").asText());
        assertEquals(409, request("POST", "/asset-import-batches/" + batch + "/rollback",
                "{\"reason\":\"批准之后不得整批回滚\"}".getBytes(StandardCharsets.UTF_8), warehouse,
                "application/json", null).statusCode());
        var claimed = request("POST", "/scrap-requests/" + opening + "/claim",
                "{\"assetVersion\":0,\"lockVersion\":0}".getBytes(StandardCharsets.UTF_8),
                warehouse, "application/json", UUID.randomUUID().toString(), 1L);
        assertEquals(200, claimed.statusCode(), body(claimed));
        var executed = request("POST", "/scrap-requests/" + opening + "/execute",
                ("{\"assetVersion\":0,\"lockVersion\":0,\"ownershipVersion\":1,"
                        + "\"observedCode\":\"HTTP-SCRAP-A\",\"locationId\":\"" + bin + "\","
                        + "\"locationVersion\":" + binVersion + ",\"custodianEmployeeId\":\"" + custodian + "\","
                        + "\"custodianVersion\":" + custodianVersion + ",\"occurredAt\":\""
                        + Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS) + "\","
                        + "\"physicalIdentityVerified\":true,"
                        + "\"evidence\":\"现场核对期初承接实物与原机编码一致后按批准范围执行报废\","
                        + "\"dispositionMethod\":\"待处置保管\"}").getBytes(StandardCharsets.UTF_8),
                warehouse, "application/json", UUID.randomUUID().toString(), 2L);
        assertEquals(200, executed.statusCode(), body(executed));
        assertEquals("EXECUTED", JSON.readTree(body(executed)).get("status").asText());
        assertEquals("SCRAPPED", db.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=?",
                String.class, asset));
        assertEquals("PENDING_DISPOSITION", db.queryForObject("SELECT disposition_status"
                + " FROM fams_scrap_disposition WHERE request_id=?", String.class, opening));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=?"
                + " AND released_at IS NULL", Integer.class, asset));
        assertEquals(List.of("DISPOSITION"), db.queryForList("SELECT todo_type FROM fams_todo"
                + " WHERE business_type='SCRAP' AND business_id=? AND todo_status='OPEN'", String.class, opening));
        long held = db.queryForObject("SELECT a.id FROM fams_asset a JOIN fams_asset_import_publication p"
                + " ON p.asset_id=a.id WHERE p.batch_id=? AND p.sheet_row=3", Long.class, batch);
        long heldRequest = db.queryForObject("SELECT id FROM fams_scrap_request WHERE initial_batch_id=?"
                + " AND initial_sheet_row=3", Long.class, batch);
        var rejected = request("POST", "/scrap-requests/" + heldRequest + "/decision",
                ("{\"assetVersion\":0,\"lockVersion\":0,\"result\":\"REJECT\","
                        + "\"reason\":\"报废前现状不足以支持核销，退回原保管关系\"}").getBytes(StandardCharsets.UTF_8),
                assetAdmin, "application/json", UUID.randomUUID().toString(), 0L);
        assertEquals(200, rejected.statusCode(), body(rejected));
        assertEquals("REJECTED", JSON.readTree(body(rejected)).get("status").asText());
        var restored = db.queryForMap("SELECT lifecycle_status,custody_type,exception_status,responsible_employee_id,"
                + "using_organization_id,version FROM fams_asset WHERE id=?", held);
        assertEquals("IN_USE", restored.get("lifecycle_status"));
        assertEquals("PERSONAL", restored.get("custody_type"));
        assertEquals("NORMAL", restored.get("exception_status"));
        assertEquals(custodian, ((Number) restored.get("responsible_employee_id")).longValue());
        assertEquals(1L, ((Number) restored.get("version")).longValue());
        assertEquals("SCRAP_REJECTED", db.queryForObject("SELECT release_reason FROM fams_asset_business_lock"
                + " WHERE asset_id=? AND business_type='SCRAP' AND business_id=?", String.class, held, heldRequest));
        assertEquals(1L, db.queryForObject("SELECT version FROM fams_scrap_request WHERE id=?", Long.class,
                heldRequest));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_todo WHERE business_type='SCRAP'"
                + " AND business_id=? AND todo_status='OPEN'", Integer.class, heldRequest));
        var unapproved = Map.ofEntries(Map.entry(0, "HTTP-SCRAP-UNDO"), Map.entry(1, "Opening scrap undo"),
                Map.entry(2, "CAT-1"), Map.entry(3, "ROOT"), Map.entry(4, "M1"), Map.entry(5, "piece"),
                Map.entry(7, "待报废"), Map.entry(8, "WH-1"), Map.entry(9, "LOC-1"), Map.entry(14, "在库"),
                Map.entry(15, "上线前重复录入"), Map.entry(16, "实物仍在待报废区"), Map.entry(17, "待处置保管"));
        String undo = publishScrapBatch(template, warehouse, assetAdmin, worker,
                "http-import-scrap-undo", List.of(unapproved), 1, 0);
        var undoAsset = db.queryForMap("SELECT a.* FROM fams_asset a JOIN fams_asset_import_publication p"
                + " ON p.asset_id=a.id WHERE p.batch_id=? AND p.sheet_row=2", undo);
        long undoRequest = db.queryForObject("SELECT id FROM fams_scrap_request WHERE initial_batch_id=?",
                Long.class, undo);
        assertEquals(200, request("POST", "/asset-import-batches/" + undo + "/rollback",
                "{\"reason\":\"整批录入错误需撤回\"}".getBytes(StandardCharsets.UTF_8), warehouse,
                "application/json", null).statusCode());
        assertEquals(200, request("POST", "/asset-import-batches/" + undo + "/rollback-decision",
                "{\"approved\":true,\"reason\":\"确认尚无后续业务\"}".getBytes(StandardCharsets.UTF_8),
                assetAdmin, "application/json", null).statusCode());
        assertEquals(200, request("POST", "/asset-import-batches/" + undo + "/rollback-execute",
                null, warehouse, null, null).statusCode());
        worker.poll();
        assertEquals("ROLLED_BACK", db.queryForObject("SELECT batch_status FROM fams_asset_import_batch "
                + "WHERE id=?", String.class, undo));
        assertEquals("CANCELLED", db.queryForObject("SELECT request_status FROM fams_scrap_request WHERE id=?",
                String.class, undoRequest));
        assertEquals(1L, db.queryForObject("SELECT version FROM fams_scrap_request WHERE id=?", Long.class,
                undoRequest));
        assertEquals("SCRAP_INITIAL_ROLLED_BACK", db.queryForObject("SELECT release_reason"
                + " FROM fams_asset_business_lock WHERE asset_id=? AND business_type='SCRAP'", String.class,
                undoAsset.get("id")));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_todo WHERE business_type='SCRAP'"
                + " AND business_id=? AND todo_status='OPEN'", Integer.class, undoRequest));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_scrap_event WHERE request_id=?"
                + " AND action='SCRAP_INITIAL_ROLLED_BACK'", Integer.class, undoRequest));
        assertEquals("CANCELLED", db.queryForObject("SELECT card_stage FROM fams_asset WHERE id=?",
                String.class, undoAsset.get("id")));
        assertEquals(null, db.queryForObject("SELECT custody_type FROM fams_asset WHERE id=?", String.class,
                undoAsset.get("id")));
        var wrongOpening = Map.ofEntries(Map.entry(0, "HTTP-SCRAP-BAD-1"), Map.entry(1, "Bad opening 1"),
                Map.entry(2, "CAT-1"), Map.entry(3, "ROOT"), Map.entry(4, "M1"), Map.entry(5, "piece"),
                Map.entry(7, "待报废"), Map.entry(8, "WH-1"), Map.entry(9, "LOC-1"), Map.entry(14, "借出"),
                Map.entry(15, "上线前已遗失"), Map.entry(16, "实物无法核对"), Map.entry(17, "遗失核销"));
        var missingReason = Map.ofEntries(Map.entry(0, "HTTP-SCRAP-BAD-2"), Map.entry(1, "Bad opening 2"),
                Map.entry(2, "CAT-1"), Map.entry(3, "ROOT"), Map.entry(4, "M1"), Map.entry(5, "piece"),
                Map.entry(7, "待报废"), Map.entry(8, "WH-1"), Map.entry(9, "LOC-1"), Map.entry(14, "在库"),
                Map.entry(16, "实物已放置在待报废区"), Map.entry(17, "待处置保管"));
        var repairOpening = Map.ofEntries(Map.entry(0, "HTTP-SCRAP-BAD-3"), Map.entry(1, "Bad opening 3"),
                Map.entry(2, "CAT-1"), Map.entry(3, "ROOT"), Map.entry(4, "M1"), Map.entry(5, "piece"),
                Map.entry(7, "维修中"), Map.entry(8, "WH-1"), Map.entry(9, "LOC-1"));
        String invalid = publishScrapBatch(template, warehouse, assetAdmin, worker,
                "http-import-scrap-invalid", List.of(wrongOpening, missingReason, repairOpening), 0, 3);
        String report = body(request("GET", "/asset-import-batches/" + invalid + "/rows?status=ERROR"
                + "&page=1&pageSize=20", null, warehouse, null, null));
        assertTrue(report.contains("UNSUPPORTED_PRE_SCRAP_STATE"), report);
        assertTrue(report.contains("维修前状态"), report);
        assertTrue(report.contains("报废原因"), report);
        assertTrue(report.contains("REQUIRED"), report);
        System.out.println("D6_23_INITIAL_SCRAP published=2 approved=1 executed=1 rolled_back=1 rejected=3");
    }

    private void openingRepairAndFactoryFollowExistingProcessing(byte[] template, Session warehouse,
            Session administrator, AssetImportWorker worker) throws Exception {
        var internal = openingRepairRow("HTTP-INITIAL-REPAIR", false);
        var factory = openingRepairRow("HTTP-INITIAL-FACTORY", true);
        String batch = publishScrapBatch(template, warehouse, administrator, worker, "http-import-initial-repairs",
                List.of(internal, factory), 2, 0);
        var repair = db.queryForMap("SELECT * FROM fams_repair_request WHERE initial_batch_id=? AND initial_sheet_row=2", batch);
        var sent = db.queryForMap("SELECT * FROM fams_repair_request WHERE initial_batch_id=? AND initial_sheet_row=3", batch);
        long repairId = ((Number) repair.get("id")).longValue();
        long factoryId = ((Number) sent.get("id")).longValue();
        long repairAsset = ((Number) repair.get("asset_id")).longValue();
        long factoryAsset = ((Number) sent.get("asset_id")).longValue();
        assertEquals("UNDER_REPAIR", db.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=?", String.class, repairAsset));
        assertEquals("FACTORY_REPAIR", db.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=?", String.class, factoryAsset));
        assertEquals("WAREHOUSE_PROCESSING", repair.get("repair_status"));
        assertEquals("WAREHOUSE_PROCESSING", sent.get("repair_status"));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_repair_decision WHERE repair_id IN (?,?)",
                Integer.class, repairId, factoryId));
        var loan = db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=?", repairAsset);
        assertEquals("OPEN", loan.get("relation_status"));
        assertEquals("2099-12-31", loan.get("expected_return_date").toString());
        var source = JSON.readTree(repair.get("source_snapshot_json").toString());
        assertEquals("BORROWED", source.path("lifecycle").asText());
        assertEquals(batch, source.path("initialSource").path("batchId").asText());
        var round = db.queryForMap("SELECT * FROM fams_repair_factory_round WHERE repair_id=?", factoryId);
        long roundId = ((Number) round.get("id")).longValue();
        assertEquals("DISPATCHED", round.get("round_status"));
        assertEquals(2, ((Number) round.get("round_number")).intValue());
        assertEquals("原寄出凭证单号 TEST-FACTORY-01", round.get("dispatch_evidence"));
        assertEquals(null, round.get("received_at"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_todo WHERE business_type='REPAIR' AND business_id=?"
                + " AND todo_type='FACTORY_RETURN' AND todo_status='OPEN'", Integer.class, factoryId));
        var detail = request("GET", "/repairs/" + repairId, null, warehouse, null, null);
        assertEquals(200, detail.statusCode(), body(detail));
        assertTrue(JSON.readTree(body(detail)).path("actions").path("completeInPlace").asBoolean());
        long place = db.queryForObject("SELECT id FROM fams_location WHERE code='LOC-1'", Long.class);
        long placeVersion = db.queryForObject("SELECT version FROM fams_location WHERE id=?", Long.class, place);
        var complete = request("POST", "/repairs/" + repairId + "/complete-in-place", JSON.writeValueAsBytes(Map.of(
                "assetVersion", 0, "ownershipVersion", 0, "lockVersion", 0, "observedCode", internal.get(0),
                "actualLocationId", Long.toString(place), "neverLeftCustodian", true,
                "evidence", "现场核对实物与原保管关系一致并验证故障修复完成")),
                warehouse, "application/json", UUID.randomUUID().toString(), 0L);
        assertEquals(200, complete.statusCode(), body(complete));
        assertEquals("BORROWED", db.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=?", String.class, repairAsset));
        assertEquals(loan, db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=?", repairAsset));

        String factoryPath = "/repairs/" + factoryId + "/factory/rounds/" + roundId;
        String boundary = "initial-factory-evidence";
        var proof = new ByteArrayOutputStream();
        proof.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"actual.png\""
                + "\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
                "png", proof);
        proof.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var uploaded = request("POST", factoryPath + "/evidence?purpose=RECEIPT&roundVersion=0&assetVersion=0"
                + "&ownershipVersion=0&lockVersion=0", proof.toByteArray(), warehouse,
                "multipart/form-data; boundary=" + boundary, UUID.randomUUID().toString(), 0L);
        assertEquals(200, uploaded.statusCode(), body(uploaded));
        String object = JSON.readTree(body(uploaded)).path("objectId").asText();
        long version = db.queryForObject("SELECT version FROM fams_repair_request WHERE id=?", Long.class, factoryId);
        long roundVersion = db.queryForObject("SELECT version FROM fams_repair_factory_round WHERE id=?", Long.class, roundId);
        var receipt = request("POST", factoryPath + "/receive-original", JSON.writeValueAsBytes(Map.ofEntries(
                Map.entry("roundVersion", roundVersion), Map.entry("assetVersion", 0), Map.entry("ownershipVersion", 0),
                Map.entry("lockVersion", 0), Map.entry("observedCode", factory.get(0)), Map.entry("observedSerial", factory.get(6)),
                Map.entry("originalPhysicalIdentityChecked", true), Map.entry("actualLocationId", Long.toString(place)),
                Map.entry("locationVersion", placeVersion), Map.entry("occurredAt", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString()),
                Map.entry("conditionResult", "RESTORED"), Map.entry("actualCost", "0.00"), Map.entry("evidenceIds", List.of(object)),
                Map.entry("treatmentResult", "已核对期初原机和厂家实物收回证明"))),
                warehouse, "application/json", UUID.randomUUID().toString(), version);
        assertEquals(200, receipt.statusCode(), body(receipt));
        assertEquals("RECEIVED", db.queryForObject("SELECT round_status FROM fams_repair_factory_round WHERE id=?", String.class, roundId));
        assertEquals(round.get("dispatched_at"), db.queryForObject("SELECT dispatched_at FROM fams_repair_factory_round WHERE id=?",
                java.time.LocalDateTime.class, roundId));
        assertEquals("UNDER_REPAIR", db.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=?", String.class, factoryAsset));
        assertEquals(409, request("POST", "/asset-import-batches/" + batch + "/rollback",
                "{\"reason\":\"已办理的期初单据不能撤销\"}".getBytes(StandardCharsets.UTF_8), warehouse,
                "application/json", null).statusCode());

        var undo = openingRepairRow("HTTP-INITIAL-FACTORY-UNDO", true);
        String reversible = publishScrapBatch(template, warehouse, administrator, worker, "http-import-initial-repair-undo",
                List.of(undo), 1, 0);
        assertEquals(200, request("POST", "/asset-import-batches/" + reversible + "/rollback",
                "{\"reason\":\"期初未办理单据整体撤回\"}".getBytes(StandardCharsets.UTF_8), warehouse,
                "application/json", null).statusCode());
        assertEquals(200, request("POST", "/asset-import-batches/" + reversible + "/rollback-decision",
                "{\"approved\":true,\"reason\":\"未发生后续办理，核验后撤回\"}".getBytes(StandardCharsets.UTF_8),
                administrator, "application/json", null).statusCode());
        assertEquals(200, request("POST", "/asset-import-batches/" + reversible + "/rollback-execute", null,
                warehouse, null, null).statusCode());
        worker.poll();
        assertEquals("ROLLED_BACK", db.queryForObject("SELECT batch_status FROM fams_asset_import_batch WHERE id=?", String.class, reversible));
        assertEquals("CANCELLED", db.queryForObject("SELECT repair_status FROM fams_repair_request WHERE initial_batch_id=?",
                String.class, reversible));
        assertEquals("INITIAL_RETIRED", db.queryForObject("SELECT f.round_status FROM fams_repair_factory_round f"
                + " JOIN fams_repair_request r ON r.id=f.repair_id WHERE r.initial_batch_id=?", String.class, reversible));
        System.out.println("D6_23_INITIAL_REPAIRS published=2 completed_in_place=1 factory_received=1 rolled_back=1");
    }

    private Map<Integer, String> openingRepairRow(String code, boolean factory) {
        var values = new java.util.HashMap<Integer, String>(Map.ofEntries(
                Map.entry(0, code), Map.entry(1, "Opening repair"), Map.entry(2, "CAT-1"), Map.entry(3, "ROOT"),
                Map.entry(4, "M1"), Map.entry(5, "piece"), Map.entry(6, code + "-SN"), Map.entry(9, "LOC-1"),
                Map.entry(20, "期初已有故障和维修事实"), Map.entry(21, "MEDIUM"),
                Map.entry(22, "2026-09-11T09:00:00+08:00"), Map.entry(23, "2026-09-11T10:00:00+08:00"),
                Map.entry(25, "原纸质维修登记单已核验")));
        if (factory) {
            values.putAll(Map.ofEntries(Map.entry(7, "返厂维修"), Map.entry(8, "WH-1"), Map.entry(18, "在库"),
                    Map.entry(19, "已寄出"), Map.entry(26, "期初记录厂家"), Map.entry(27, "厂家接收人"),
                    Map.entry(28, "010-12345678"), Map.entry(29, "厂家维修收件库"), Map.entry(30, "2026-09-12T11:00:00+08:00"),
                    Map.entry(31, "原寄出凭证单号 TEST-FACTORY-01"), Map.entry(32, "2026-12-31"), Map.entry(33, "NO_COST"), Map.entry(35, "2")));
        } else {
            values.putAll(Map.of(7, "维修中", 10, "ROOT", 11, "E3", 12, "2026-09-01", 13, "2099-12-31",
                    18, "借出", 19, "原地维修"));
        }
        return values;
    }

    private String publishScrapBatch(byte[] template, Session warehouse, Session assetAdmin,
            AssetImportWorker worker, String uploadKey, List<Map<Integer, String>> rows,
            int ready, int errors) throws Exception {
        var upload = request("POST", "/asset-import-batches", multipart(scrapWorkbook(template, rows)),
                warehouse, "multipart/form-data; boundary=fams-test-boundary", uploadKey);
        assertEquals(201, upload.statusCode(), body(upload));
        String batch = JSON.readTree(body(upload)).get("id").asText();
        var preview = JSON.readTree(body(request("POST", "/asset-import-batches/" + batch + "/preview",
                "{}".getBytes(StandardCharsets.UTF_8), warehouse, "application/json", null, 0L)));
        assertEquals(ready, preview.get("readyRows").asInt());
        assertEquals(errors, preview.get("errorRows").asInt());
        if (ready == 0) {
            return batch;
        }
        assertEquals(200, request("POST", "/asset-import-batches/" + batch + "/review", null,
                warehouse, null, null, 1L).statusCode());
        assertEquals(200, request("POST", "/asset-import-batches/" + batch + "/decision",
                ("{\"approved\":true,\"reason\":\"verified opening scrap\",\"confirmedReadyRows\":" + ready
                        + ",\"confirmedErrorRows\":" + errors + ",\"sampleVerified\":true}")
                        .getBytes(StandardCharsets.UTF_8), assetAdmin, "application/json", null).statusCode());
        worker.poll();
        assertEquals("PUBLISHED", db.queryForObject("SELECT batch_status FROM fams_asset_import_batch "
                + "WHERE id=?", String.class, batch), () -> "job error=" + db.queryForObject(
                "SELECT MIN(last_error_code) FROM fams_durable_job WHERE job_kind='ASSET_IMPORT'"
                + " AND business_reference=?", String.class, batch));
        return batch;
    }

    private byte[] scrapWorkbook(byte[] template, List<Map<Integer, String>> rows) throws Exception {
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(template));
                var output = new ByteArrayOutputStream()) {
            var sheet = book.getSheet("资产");
            for (int index = 0; index < rows.size(); index++) {
                var row = sheet.createRow(index + 1);
                for (var cell : rows.get(index).entrySet()) {
                    row.createCell(cell.getKey()).setCellValue(cell.getValue());
                }
            }
            book.write(output);
            return output.toByteArray();
        }
    }

    private void assertInvalidImportRequestBoundaries() {
        UUID unknown = UUID.randomUUID();
        byte[] empty = new byte[0];
        assertThrows(IllegalArgumentException.class, () -> batches.upload(0, "valid-upload-key-01", empty));
        assertThrows(IllegalArgumentException.class, () -> batches.upload(1, null, empty));
        assertThrows(IllegalArgumentException.class, () -> batches.upload(1, "short", empty));
        assertThrows(IllegalArgumentException.class, () -> batches.upload(1, "x".repeat(129), empty));
        assertThrows(IllegalArgumentException.class, () -> batches.upload(1, "invalid key with space", empty));
        assertThrows(IllegalArgumentException.class, () -> batches.get(0, unknown));
        assertThrows(IllegalArgumentException.class, () -> batches.get(1, null));
        assertThrows(IllegalStateException.class, () -> batches.get(1, unknown));
        assertThrows(IllegalArgumentException.class, () -> batches.columns(0, unknown));
        assertThrows(IllegalArgumentException.class, () -> batches.columns(1, null));
        assertThrows(IllegalStateException.class, () -> batches.columns(1, unknown));
        assertThrows(IllegalArgumentException.class, () -> batches.preview(0, unknown, 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> batches.preview(1, null, 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> batches.preview(1, unknown, -1, Map.of()));
        assertThrows(IllegalStateException.class, () -> batches.preview(1, unknown, 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> batches.rows(0, unknown, null, 1, 50));
        assertThrows(IllegalArgumentException.class, () -> batches.rows(1, null, null, 1, 50));
        assertThrows(IllegalArgumentException.class, () -> batches.rows(1, unknown, null, 0, 50));
        assertThrows(IllegalArgumentException.class, () -> batches.rows(1, unknown, null, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> batches.rows(1, unknown, null, 1, 201));
        assertThrows(IllegalArgumentException.class,
                () -> batches.rows(1, unknown, null, Integer.MAX_VALUE, 200));
        assertThrows(IllegalArgumentException.class, () -> batches.rows(1, unknown, "INVALID", 1, 50));
        assertThrows(IllegalStateException.class, () -> batches.rows(1, unknown, null, 1, 50));
        assertThrows(IllegalArgumentException.class, () -> reviews.pending(0));
        assertThrows(IllegalArgumentException.class, () -> reviews.pending(1_000_001));
        assertThrows(IllegalArgumentException.class, () -> reviews.rowsForAdmin(null, "READY", 1));
        assertThrows(IllegalArgumentException.class, () -> reviews.rowsForAdmin(unknown, null, 1));
        assertThrows(IllegalArgumentException.class, () -> reviews.rowsForAdmin(unknown, "READY", 0));
        assertThrows(IllegalArgumentException.class,
                () -> reviews.rowsForAdmin(unknown, "READY", 1_000_001));
        assertThrows(IllegalArgumentException.class,
                () -> reviews.rowsForAdmin(unknown, "INVALID", 1));
        assertThrows(IllegalStateException.class, () -> reviews.rowsForAdmin(unknown, "READY", 1));
        assertThrows(IllegalArgumentException.class, () -> reviews.request(0, unknown, 1));
        assertThrows(IllegalArgumentException.class, () -> reviews.request(1, null, 1));
        assertThrows(IllegalArgumentException.class, () -> reviews.request(1, unknown, 0));
        assertThrows(IllegalStateException.class, () -> reviews.request(1, unknown, 1));
        assertThrows(IllegalArgumentException.class,
                () -> reviews.decide(0, unknown, true, "reason", 1, 0, true));
        assertThrows(IllegalArgumentException.class,
                () -> reviews.decide(1, null, true, "reason", 1, 0, true));
        assertThrows(IllegalArgumentException.class,
                () -> reviews.decide(1, unknown, true, null, 1, 0, true));
        assertThrows(IllegalArgumentException.class,
                () -> reviews.decide(1, unknown, true, " ", 1, 0, true));
        assertThrows(IllegalArgumentException.class,
                () -> reviews.decide(1, unknown, true, "x".repeat(501), 1, 0, true));
        assertThrows(IllegalStateException.class,
                () -> reviews.decide(1, unknown, true, "reason", 1, 0, true));
        assertThrows(IllegalStateException.class, () -> reviews.getOwned(1, unknown));
        assertThrows(IllegalArgumentException.class, () -> rollbacks.pending(0));
        assertThrows(IllegalArgumentException.class, () -> rollbacks.pending(1_000_001));
        assertThrows(IllegalStateException.class, () -> rollbacks.getOwned(1, unknown));
        assertThrows(IllegalArgumentException.class, () -> rollbacks.request(1, unknown, null));
        assertThrows(IllegalArgumentException.class, () -> rollbacks.request(1, unknown, " "));
        assertThrows(IllegalArgumentException.class, () -> rollbacks.request(1, unknown, "x".repeat(501)));
        assertThrows(IllegalStateException.class, () -> rollbacks.request(1, unknown, "reason"));
        assertThrows(IllegalArgumentException.class,
                () -> rollbacks.decide(3, unknown, true, null));
        assertThrows(IllegalStateException.class,
                () -> rollbacks.decide(3, unknown, true, "reason"));
        assertThrows(IllegalStateException.class, () -> rollbacks.queue(1, unknown));
    }

    @Test
    void processTwoPublishesClaimedImportAfterPreviousJvmExited() throws Exception {
        String id = Files.readString(Path.of(required("D6_08_IMPORT_PHASE2_MARKER"))).trim();
        assertEquals("RUNNING", db.queryForObject("SELECT job_status FROM fams_durable_job "
                + "WHERE job_kind='ASSET_IMPORT' AND business_reference=?", String.class, id));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_asset_import_publication "
                + "WHERE batch_id=?", Integer.class, id));
        long started = System.nanoTime();
        var worker = new AssetImportWorker(jobs, publication, rollbacks);
        worker.poll();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals("PUBLISHED", db.queryForObject("SELECT batch_status FROM fams_asset_import_batch "
                + "WHERE id=?", String.class, id));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_asset_import_publication "
                + "WHERE batch_id=?", Integer.class, id));
        assertEquals(2, db.queryForObject("SELECT execution_generation FROM fams_durable_job "
                + "WHERE job_kind='ASSET_IMPORT' AND business_reference=?", Integer.class, id));
        worker.poll();
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_asset_import_publication "
                + "WHERE batch_id=?", Integer.class, id));
        System.out.println("D6_08_IMPORT_RESTART recovered_ms=" + elapsedMs
                + " generation=2 published=1 duplicate=0");
    }

    private void seedAccounts() {
        Timestamp now = Timestamp.from(Instant.parse("2026-09-28T00:00:00Z"));
        db.update("UPDATE fams_system_account SET password_hash=?,initial_password_required=FALSE "
                + "WHERE id=1", new BCryptPasswordEncoder(4).encode(PASSWORD));
        db.update("INSERT INTO fams_account_role (account_id,role_code,granted_at) "
                + "VALUES (1,'WAREHOUSE_OPERATOR',?)", now);
        db.update("INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,organization_id,"
                + "status,created_at,updated_at) VALUES (2,'E2','E2','Viewer',1,'ACTIVE',?,?)", now, now);
        db.update("INSERT INTO fams_system_account (id,employee_id,username,username_normalized,password_hash,"
                + "status,initial_password_required,created_at,updated_at) "
                + "VALUES (2,2,'viewer','VIEWER',?,'ENABLED',FALSE,?,?)",
                new BCryptPasswordEncoder(4).encode(PASSWORD), now, now);
        db.update("INSERT INTO fams_account_role (account_id,role_code,granted_at) "
                + "VALUES (2,'EMPLOYEE',?)", now);
    }

    private void seedBusinessParameters() {
        String snapshot = """
                {"timezone":"Asia/Shanghai","workWeek":["MONDAY","TUESDAY","WEDNESDAY",
                "THURSDAY","FRIDAY"],"holidays":[],"adjustedWorkdays":[],
                "adminDecisionDeadlineWorkdays":2,"warehouseClaimDeadlineWorkdays":1,
                "warehouseFirstExecutionDeadlineWorkdays":2,
                "handoverAcceptanceDeadlineNaturalDays":3,
                "notificationEscalationThresholds":{"administratorUnreadHours":4,
                "onDutyWarehouseOperatorUnreadHours":4,"firstExecutionOverdueNaturalDays":1,
                "receiptDueUnreadHours":24}}
                """;
        Timestamp now = Timestamp.from(Instant.now());
        db.update("INSERT INTO fams_business_parameter_version (id,version,status,effective_at,"
                + "actor_account_id,actor_username,snapshot_json,created_at) "
                + "VALUES (1,1,'ACTIVE',?,3,'administrator',?,?)", now, snapshot, now);
        db.update("UPDATE fams_business_parameter_state SET current_version=1,current_version_id=1 "
                + "WHERE singleton_id=1");
    }

    private byte[] workbook(byte[] template) throws Exception {
        return workbook(template, "HTTP-A-1");
    }

    private byte[] workbook(byte[] template, String code) throws Exception {
        try (var input = new ByteArrayInputStream(template); var book = new XSSFWorkbook(input);
                var output = new ByteArrayOutputStream()) {
            var sheet = book.getSheet("资产");
            for (int index = 1; index <= 2; index++) {
                var row = sheet.createRow(index);
                row.createCell(0).setCellValue(code);
                row.createCell(1).setCellValue("Desk " + index);
                row.createCell(2).setCellValue("CAT-1");
                row.createCell(3).setCellValue("ROOT");
                row.createCell(4).setCellValue("M1");
                row.createCell(5).setCellValue("piece");
                row.createCell(7).setCellValue("在库");
                row.createCell(8).setCellValue("WH-1");
                row.createCell(9).setCellValue("LOC-1");
            }
            book.write(output);
            return output.toByteArray();
        }
    }

    private byte[] usageWorkbook(byte[] template) throws Exception {
        try (var input = new ByteArrayInputStream(template); var book = new XSSFWorkbook(input);
                var output = new ByteArrayOutputStream()) {
            var sheet = book.getSheet("资产");
            for (int index = 1; index <= 3; index++) {
                var row = sheet.createRow(index);
                row.createCell(0).setCellValue(index == 1 ? "HTTP-P-1" : index == 2 ? "HTTP-S-1" : "HTTP-B-1");
                row.createCell(1).setCellValue("Assigned asset " + index);
                row.createCell(2).setCellValue("CAT-1");
                row.createCell(3).setCellValue("ROOT");
                row.createCell(4).setCellValue("M1");
                row.createCell(5).setCellValue("piece");
                row.createCell(7).setCellValue(index == 1 ? "在用个人" : index == 2 ? "在用公用" : "借出");
                row.createCell(9).setCellValue("LOC-1");
                row.createCell(10).setCellValue("ROOT");
                row.createCell(11).setCellValue(index == 2 ? "E3" : "E2");
                if (index == 3) {
                    row.createCell(12).setCellValue("2020-01-01");
                    row.createCell(13).setCellValue("2020-02-01");
                }
            }
            book.write(output);
            return output.toByteArray();
        }
    }

    private void verifyBorrowRollback(byte[] template, Session warehouse, Session administrator,
            AssetImportWorker worker) throws Exception {
        byte[] file;
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(usageWorkbook(template)));
                var output = new ByteArrayOutputStream()) {
            var sheet = book.getSheet("资产");
            sheet.removeRow(sheet.getRow(1));
            sheet.removeRow(sheet.getRow(2));
            sheet.getRow(3).getCell(0).setCellValue("HTTP-B-UNDO");
            book.write(output);
            file = output.toByteArray();
        }
        var upload = request("POST", "/asset-import-batches", multipart(file), warehouse,
                "multipart/form-data; boundary=fams-test-boundary", "http-borrow-undo-key-0001");
        assertEquals(201, upload.statusCode(), body(upload));
        String id = JSON.readTree(body(upload)).path("id").asText();
        assertEquals(200, request("POST", "/asset-import-batches/" + id + "/preview", "{}".getBytes(StandardCharsets.UTF_8),
                warehouse, "application/json", null, 0L).statusCode());
        assertEquals(200, request("POST", "/asset-import-batches/" + id + "/review", null, warehouse, null, null, 1L).statusCode());
        byte[] decision = "{\"approved\":true,\"reason\":\"checked borrowed opening\",\"confirmedReadyRows\":1,\"confirmedErrorRows\":0,\"sampleVerified\":true}"
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(200, request("POST", "/asset-import-batches/" + id + "/decision", decision, administrator, "application/json", null).statusCode());
        worker.poll();
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_loan WHERE initial_batch_id=? AND relation_status='OPEN'", Integer.class, id));
        assertEquals(200, request("POST", "/asset-import-batches/" + id + "/rollback", "{\"reason\":\"correct opening\"}"
                .getBytes(StandardCharsets.UTF_8), warehouse, "application/json", null).statusCode());
        assertEquals(200, request("POST", "/asset-import-batches/" + id + "/rollback-decision", decision, administrator, "application/json", null).statusCode());
        assertEquals(200, request("POST", "/asset-import-batches/" + id + "/rollback-execute", null, warehouse, null, null).statusCode());
        worker.poll();
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_loan WHERE initial_batch_id=? AND relation_status='VOID'", Integer.class, id));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_asset WHERE asset_code='HTTP-B-UNDO' AND card_stage='CANCELLED'", Integer.class));
    }

    private byte[] multipart(byte[] file) throws Exception {
        var output = new ByteArrayOutputStream();
        output.write(("--fams-test-boundary\r\nContent-Disposition: form-data; name=\"file\"; "
                + "filename=\"assets.xlsx\"\r\nContent-Type: application/vnd.openxmlformats-officedocument."
                + "spreadsheetml.sheet\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(file);
        output.write("\r\n--fams-test-boundary--\r\n".getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }

    private Session login(String username) throws Exception {
        var response = request("POST", "/auth/login",
                ("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}")
                        .getBytes(StandardCharsets.UTF_8), null, "application/json", null);
        assertEquals(200, response.statusCode(), body(response));
        return new Session(response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                JSON.readTree(body(response)).get("csrfToken").asText());
    }

    private HttpResponse<byte[]> request(String method, String path, byte[] body, Session session,
            String contentType, String key) throws Exception {
        return request(method, path, body, session, contentType, key, null);
    }

    private HttpResponse<byte[]> request(String method, String path, byte[] body, Session session,
            String contentType, String key, Long version) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (session != null) {
            builder.header("Cookie", session.cookie());
            builder.header("X-CSRF-Token", session.csrf());
        }
        if (contentType != null) { builder.header("Content-Type", contentType); }
        if (key != null) { builder.header("Idempotency-Key", key); }
        if (version != null) { builder.header("If-Match-Version", version.toString()); }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private String body(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) { throw new IllegalStateException(name + " required"); }
        return value;
    }

    private record Session(String cookie, String csrf) { }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class CleanScanner {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.fams.attachment.MalwareScanner testImportEvidenceScanner() {
            return file -> com.fams.attachment.MalwareScanner.Result.CLEAN;
        }
    }

    @SpringBootApplication(scanBasePackages = "com.fams")
    static class TestApplication {
        public static void main(String[] args) { SpringApplication.run(TestApplication.class, args); }
    }
}
