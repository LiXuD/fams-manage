package com.fams.assetimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fams.job.DurableJobService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
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

@SpringBootTest(classes = AssetImportHttpRuntimeCase.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.profiles.active=fams-product",
                "fams.asset-import.worker.enabled=false",
                "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
                "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra"})
@EnabledIfSystemProperty(named = "d6.08.import.http.runtime", matches = "true")
class AssetImportHttpRuntimeCase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate db;
    @Autowired DurableJobService jobs;
    @Autowired AssetImportPublicationService publication;
    @Autowired AssetImportRollbackService rollbacks;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> required("D6_08_TEST_DATASOURCE_URL"));
        registry.add("spring.datasource.username", () -> required("D6_08_TEST_DATASOURCE_USERNAME"));
        registry.add("spring.datasource.password", () -> required("D6_08_TEST_DATASOURCE_PASSWORD"));
    }

    @Test
    void warehouseUploadPreviewAndErrorRowsUseRealSessionAndMysql() throws Exception {
        seedAccounts();
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
        assertEquals(200, request("GET", "/asset-import-batches/" + id + "/review-rows?status=ERROR",
                null, assetAdmin, null, null).statusCode());
        byte[] unverifiedDecision = "{\"approved\":true,\"reason\":\"unchecked\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(400, request("POST", "/asset-import-batches/" + id + "/decision",
                unverifiedDecision, assetAdmin, "application/json", null).statusCode());
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
        var worker = new AssetImportWorker(jobs, publication, rollbacks);
        worker.poll();
        assertEquals("PUBLISHED", JSON.readTree(body(request("GET", "/asset-import-batches/" + id,
                null, warehouse, null, null))).get("status").asText());
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
        assertEquals(2, JSON.readTree(body(usagePreview)).get("readyRows").asInt());
        assertEquals(200, request("POST", "/asset-import-batches/" + usageId + "/review", null,
                warehouse, null, null, 1L).statusCode());
        byte[] usageDecision = ("{\"approved\":true,\"reason\":\"verified usage sample\","
                + "\"confirmedReadyRows\":2,\"confirmedErrorRows\":0,"
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
        byte[] rollbackReason = "{\"reason\":\"duplicate initial loading\"}"
                .getBytes(StandardCharsets.UTF_8);
        var rollbackRequest = request("POST", "/asset-import-batches/" + id + "/rollback",
                rollbackReason, warehouse, "application/json", null);
        assertEquals(200, rollbackRequest.statusCode(), body(rollbackRequest));
        assertTrue(body(request("GET", "/asset-import-batches/rollbacks/pending", null,
                assetAdmin, null, null)).contains(id));
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
        worker.poll();
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_asset_import_publication "
                + "WHERE batch_id=?", Integer.class, revokedId));
        assertEquals("FAILED", db.queryForObject("SELECT batch_status FROM fams_asset_import_batch "
                + "WHERE id=?", String.class, revokedId));
        assertEquals(200, request("POST", "/asset-initialization-windows/" + windowId + "/close",
                null, administrator, null, null).statusCode());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_initialization_window "
                + "WHERE id=? AND window_status='CLOSED'", Integer.class, windowId));
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
            for (int index = 1; index <= 2; index++) {
                var row = sheet.createRow(index);
                row.createCell(0).setCellValue(index == 1 ? "HTTP-P-1" : "HTTP-S-1");
                row.createCell(1).setCellValue("Assigned asset " + index);
                row.createCell(2).setCellValue("CAT-1");
                row.createCell(3).setCellValue("ROOT");
                row.createCell(4).setCellValue("M1");
                row.createCell(5).setCellValue("piece");
                row.createCell(7).setCellValue(index == 1 ? "在用个人" : "在用公用");
                row.createCell(9).setCellValue("LOC-1");
                row.createCell(10).setCellValue("ROOT");
                row.createCell(11).setCellValue(index == 1 ? "E2" : "E3");
            }
            book.write(output);
            return output.toByteArray();
        }
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

    @SpringBootApplication(scanBasePackages = "com.fams")
    static class TestApplication {
        public static void main(String[] args) { SpringApplication.run(TestApplication.class, args); }
    }
}
