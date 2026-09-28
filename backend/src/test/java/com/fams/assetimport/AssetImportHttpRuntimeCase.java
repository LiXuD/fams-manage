package com.fams.assetimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
                "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra"})
@EnabledIfSystemProperty(named = "d6.08.import.http.runtime", matches = "true")
class AssetImportHttpRuntimeCase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate db;

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
        var report = request("GET", "/asset-import-batches/" + id + "/preflight-report",
                null, warehouse, null, null);
        assertEquals(200, report.statusCode(), body(report));
        assertTrue(body(report).contains("DUPLICATE_ASSET_CODE"));
        assertEquals(403, request("GET", "/asset-import-batches/" + id + "/preflight-report",
                null, viewer, null, null).statusCode());
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM fams_asset_import_row "
                + "WHERE batch_id=?", Integer.class, id));
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

    private byte[] workbook(byte[] template) throws Exception {
        try (var input = new ByteArrayInputStream(template); var book = new XSSFWorkbook(input);
                var output = new ByteArrayOutputStream()) {
            var sheet = book.getSheet("资产");
            for (int index = 1; index <= 2; index++) {
                var row = sheet.createRow(index);
                row.createCell(0).setCellValue("HTTP-A-1");
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
