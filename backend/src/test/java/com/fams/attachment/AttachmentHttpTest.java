package com.fams.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import com.fams.attachment.MalwareScanner.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/** Real HTTP/session/MySQL chain; scanner fixture controls races, not actual malware detection. */
@SpringBootTest(classes = AttachmentHttpTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "spring.profiles.active=fams-product", "spring.flyway.locations=classpath:db/migration/d6_04",
            "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
            "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra"
        })
@Import(AttachmentHttpTest.ScannerConfiguration.class)
@EnabledIfSystemProperty(named = "d6.09.attachment.runtime", matches = "true")
class AttachmentHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final Path ROOT = temporaryRoot();
    private final HttpClient http = HttpClient.newHttpClient();
    private String lastGrant;
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate db;
    @Autowired(required = false) ControlledScanner scanner;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("D6_09_TEST_DATASOURCE_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("D6_09_TEST_DATASOURCE_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("D6_09_TEST_DATASOURCE_PASSWORD"));
        properties.add("fams.attachments.quarantine-root", () -> ROOT.toString());
        if (System.getenv("D6_09_CLAMD_PORT") != null) {
            properties.add("fams.attachments.clamd-port", () -> System.getenv("D6_09_CLAMD_PORT"));
        }
    }

    @Test
    @DisabledIfSystemProperty(named = "d6.09.clamd.http", matches = "true")
    void uploadsReplayAtomicallyAndRejectRevocationAndAuditFailure() throws Exception {
        try {
            seed();
            var session = login("attachment-creator");
            assertStatus(401, upload(null, "unauthenticated-key", 0, "%PDF-1.7", true));
            assertStatus(403, upload(session, "missing-csrf-key-00", 0, "%PDF-1.7", false));
            assertStatus(403, upload(login("attachment-system"), "system-admin-key-0", 0, "%PDF-1.7", true));
            assertStatus(409, upload(login("attachment-other"), "other-warehouse-key", 0, "%PDF-1.7", true));
            scanner.barrier = new CyclicBarrier(2);
            var first = CompletableFuture.supplyAsync(() -> sendUpload(session, "concurrent-upload-key", 0, "%PDF-1.7"));
            var second = CompletableFuture.supplyAsync(() -> sendUpload(session, "concurrent-upload-key", 0, "%PDF-1.7"));
            var one = assertStatus(201, first.get(30, TimeUnit.SECONDS));
            var two = assertStatus(201, second.get(30, TimeUnit.SECONDS));
            scanner.barrier = null;
            assertEquals(JSON.readTree(one.body()), JSON.readTree(two.body()));
            assertEquals(1, JSON.readTree(one.body()).path("businessVersion").asLong());
            assertEquals("CLEAN", JSON.readTree(one.body()).path("scanStatus").asText());
            assertCounts(1);
            exerciseDownloads(session, JSON.readTree(one.body()).path("objectId").asText());
            assertEquals(JSON.readTree(one.body()), JSON.readTree(assertStatus(201,
                    upload(session, "concurrent-upload-key", 0, "%PDF-1.7", true)).body()));
            assertStatus(409, upload(session, "concurrent-upload-key", 0, "%PDF-1.8", true));
            assertStatus(409, upload(session, "stale-version-key-0", 0, "%PDF-1.7", true));
            assertStatus(400, upload(session, "invalid-format-key", 1, "not a PDF", true));
            assertCounts(1);

            db.execute("CREATE TRIGGER attachment_test_fail_audit BEFORE INSERT ON fams_audit_event "
                    + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Injected audit write failure'");
            try { assertStatus(500, upload(session, "audit-rollback-key", 1, "%PDF-1.7", true)); }
            finally { db.execute("DROP TRIGGER attachment_test_fail_audit"); }
            assertCounts(1);

            scanner.entered = new CountDownLatch(1);
            scanner.release = new CountDownLatch(1);
            var pending = CompletableFuture.supplyAsync(() -> sendUpload(session, "revocation-during-scan", 1, "%PDF-1.7"));
            assertTrue(scanner.entered.await(10, TimeUnit.SECONDS));
            db.update("UPDATE fams_system_account SET authorization_version=authorization_version+1 WHERE id=101");
            scanner.release.countDown();
            assertStatus(401, pending.get(30, TimeUnit.SECONDS));
            assertCounts(1);
            assertStatus(403, access(login("attachment-creator"), "GET", lastGrant));
            scanner.entered = null;
            scanner.release = null;
            exerciseCorrections(login("attachment-creator"), JSON.readTree(one.body()).path("objectId").asText());
        } finally {
            scanner.barrier = null;
            if (scanner.release != null) { scanner.release.countDown(); }
            try (var files = Files.list(ROOT)) { for (Path file : files.toList()) { Files.delete(file); } }
            Files.delete(ROOT);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "d6.09.clamd.http", matches = "true")
    void realClamdAllowsCleanDownloadAndQuarantinesEicarAndDaemonOutage() throws Exception {
        try {
            seed();
            var session = login("attachment-creator");
            String clean = "%PDF-1.7\nHarmless attachment test document";
            var uploaded = JSON.readTree(assertStatus(201,
                    upload(session, "real-clamd-clean-key", 0, clean, true)).body());
            assertEquals("CLEAN", uploaded.path("scanStatus").asText());
            String path = "/api/v1/attachments/" + uploaded.path("objectId").asText();
            String url = JSON.readTree(assertStatus(200, access(session, "POST", path + "/download-grant"))
                    .body()).path("url").asText();
            assertEquals(clean, assertStatus(200, access(session, "GET", url)).body());
            // EICAR is an inert, standard antivirus test string, not a real malware sample.
            String eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$" + "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
            var realScanner = new ClamdScanner("127.0.0.1", Integer.parseInt(System.getenv("D6_09_CLAMD_PORT")), 10000);
            assertEquals(Result.INFECTED, realScanner.scan(new java.io.ByteArrayInputStream(
                    eicar.getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
            var infected = JSON.readTree(assertStatus(201, upload(session, "real-clamd-eicar-key", 1,
                    pdfWithEmbeddedFile(eicar), true)).body());
            assertEquals("INFECTED", infected.path("scanStatus").asText());
            assertStatus(409, access(session, "POST", "/api/v1/attachments/"
                    + infected.path("objectId").asText() + "/download-grant"));
            String owned = System.getenv("D6_09_CLAMD_CONTAINER");
            assertTrue(owned != null && owned.matches("fams-d6-09-clamd-[0-9a-f]{16}"));
            var stopped = new ProcessBuilder("rtk", "docker", "stop", owned)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            assertTrue(stopped.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, stopped.exitValue());
            var unavailable = JSON.readTree(assertStatus(201, upload(session, "real-clamd-outage-key", 2,
                    clean, true)).body());
            assertEquals("UNAVAILABLE", unavailable.path("scanStatus").asText());
            assertStatus(409, access(session, "POST", "/api/v1/attachments/"
                    + unavailable.path("objectId").asText() + "/download-grant"));
            assertCounts(3);
        } finally {
            try (var files = Files.list(ROOT)) { for (Path file : files.toList()) { Files.delete(file); } }
            Files.delete(ROOT);
        }
    }

    private void exerciseCorrections(Session session, String original) throws Exception {
        scanner.barrier = new CyclicBarrier(2);
        java.util.function.Supplier<HttpResponse<String>> send = () -> {
            try { return correction(session, original, "correction-http-key", 1, "Missing page"); }
            catch (Exception error) { throw new IllegalStateException(error); }
        };
        var first = CompletableFuture.supplyAsync(send);
        var second = CompletableFuture.supplyAsync(send);
        var result = JSON.readTree(assertStatus(201, first.get(30, TimeUnit.SECONDS)).body());
        assertEquals(result, JSON.readTree(assertStatus(201, second.get(30, TimeUnit.SECONDS)).body()));
        scanner.barrier = null;
        String corrected = result.path("objectId").asText();
        assertEquals(2, result.path("businessVersion").asLong());
        assertEquals(result, JSON.readTree(assertStatus(201,
                correction(session, original, "correction-http-key", 1, "Missing page")).body()));
        assertStatus(409, correction(session, original, "correction-http-key", 1, "Different reason"));
        assertStatus(409, upload(session, "correction-http-key", 1, "%PDF-1.8", true));
        assertStatus(409, correction(session, corrected, "stale-correction-key", 1, "Old version"));
        assertStatus(400, correction(session, original, "fork-correction-key", 2, "Old original"));
        assertStatus(400, correction(session, java.util.UUID.randomUUID().toString(),
                "unknown-correction-key", 2, "Unknown original"));
        db.execute("CREATE TRIGGER correction_fail_audit BEFORE INSERT ON fams_audit_event "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Injected correction audit failure'");
        try { assertStatus(500, correction(session, corrected, "rollback-correction-key", 2, "Rollback")); }
        finally { db.execute("DROP TRIGGER correction_fail_audit"); }
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM fams_attachment WHERE business_id=101", Integer.class));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM fams_attachment_upload_key WHERE uploader_account_id=101", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_attachment_revision r JOIN fams_attachment a "
                + "ON a.object_id=r.object_id WHERE a.business_id=101", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_audit_event WHERE resource_id=101 "
                + "AND action='ATTACHMENT_CORRECTED'", Integer.class));
        assertEquals(2, db.queryForObject("SELECT version FROM fams_inbound_receipt WHERE id=101", Integer.class));
        assertEquals("%PDF-1.7", Files.readString(ROOT.resolve(original)));
        assertEquals("%PDF-1.8", Files.readString(ROOT.resolve(corrected)));
        try (var files = Files.list(ROOT)) { assertEquals(2, files.count()); }
        db.update("UPDATE fams_inbound_receipt SET receipt_status='PENDING_ADMIN_DECISION',submitted_at=NOW(3) WHERE id=101");
        assertStatus(409, correction(session, corrected, "frozen-correction-key", 2, "Frozen"));
    }

    private HttpResponse<String> correction(Session session, String previous, String key, long version, String reason)
            throws Exception {
        String boundary = "fams-correction-boundary";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"reason\"\r\n\r\n" + reason
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"doc.pdf\""
                + "\r\nContent-Type: application/pdf\r\n\r\n%PDF-1.8\r\n--" + boundary + "--\r\n";
        var request = HttpRequest.newBuilder(uri("/api/v1/inbound-receipts/101/attachments/" + previous + "/corrections"))
                .header("Cookie", session.cookie()).header("X-CSRF-Token", session.csrf())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Idempotency-Key", key).header("If-Match-Version", Long.toString(version));
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String pdfWithEmbeddedFile(String content) {
        String[] objects = {
            "<< /Type /Catalog /Pages 2 0 R /Names << /EmbeddedFiles << /Names [(eicar.txt) 4 0 R] >> >> >>",
            "<< /Type /Pages /Count 1 /Kids [3 0 R] >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << >> >>",
            "<< /Type /Filespec /F (eicar.txt) /EF << /F 5 0 R >> >>",
            "<< /Type /EmbeddedFile /Subtype /text#2Fplain /Length " + content.length()
                    + " >>\nstream\n" + content + "\nendstream"
        };
        var document = new StringBuilder("%PDF-1.7\n");
        var offsets = new java.util.ArrayList<Integer>();
        for (int index = 0; index < objects.length; index++) {
            offsets.add(document.length());
            document.append(index + 1).append(" 0 obj\n").append(objects[index]).append("\nendobj\n");
        }
        int xref = document.length();
        document.append("xref\n0 6\n0000000000 65535 f \n");
        for (int offset : offsets) { document.append("%010d 00000 n \n".formatted(offset)); }
        return document.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n").append(xref)
                .append("\n%%EOF\n").toString();
    }

    private void exerciseDownloads(Session session, String objectId) throws Exception {
        String path = "/api/v1/attachments/" + objectId;
        assertEquals(1, JSON.readTree(assertStatus(200, access(session, "GET",
                "/api/v1/inbound-receipts/101/attachments")).body()).path("items").size());
        assertStatus(404, access(login("attachment-other"), "GET", "/api/v1/inbound-receipts/101/attachments"));
        assertStatus(403, access(login("attachment-system"), "POST", path + "/download-grant"));
        var issued = JSON.readTree(assertStatus(200, access(session, "POST", path + "/download-grant")).body());
        String url = issued.path("url").asText();
        lastGrant = url;
        var content = assertStatus(200, access(session, "GET", url));
        assertEquals("%PDF-1.7", content.body());
        assertEquals("nosniff", content.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertTrue(content.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertStatus(403, access(login("attachment-other"), "GET", url));
        assertStatus(403, access(session, "GET", url + "tampered"));
        Path file = ROOT.resolve(objectId);
        Files.setPosixFilePermissions(file, java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        Files.writeString(file, "%PDF-1.8");
        assertStatus(409, access(session, "GET", url));
        Files.writeString(file, "%PDF-1.7");
        Files.setPosixFilePermissions(file, java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ));
        db.update("UPDATE fams_attachment SET scan_status='UNAVAILABLE' WHERE object_id=?", objectId);
        assertStatus(409, access(session, "POST", path + "/download-grant"));
        assertStatus(409, access(session, "GET", url));
        db.update("UPDATE fams_attachment SET scan_status='CLEAN' WHERE object_id=?", objectId);
        db.update("UPDATE fams_inbound_receipt SET creator_account_id=102 WHERE id=101");
        assertStatus(404, access(session, "GET", url));
        db.update("UPDATE fams_inbound_receipt SET creator_account_id=101 WHERE id=101");
        assertStatus(404, access(login("attachment-admin"), "POST", path + "/download-grant"));
        db.update("UPDATE fams_inbound_receipt SET receipt_status='PENDING_ADMIN_DECISION',submitted_at=NOW(3) WHERE id=101");
        for (String name : java.util.List.of("attachment-admin", "attachment-dual")) {
            var admin = login(name);
            var adminGrant = JSON.readTree(assertStatus(200, access(admin, "POST", path + "/download-grant")).body());
            assertStatus(200, access(admin, "GET", adminGrant.path("url").asText()));
        }
        db.update("UPDATE fams_inbound_receipt SET receipt_status='DRAFT',submitted_at=NULL WHERE id=101");
    }

    private HttpResponse<String> access(Session session, String method, String path) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).header("Cookie", session.cookie());
        if ("POST".equals(method)) { request.header("X-CSRF-Token", session.csrf()); }
        return http.send(request.method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void assertCounts(int expected) throws Exception {
        assertEquals(expected, db.queryForObject("SELECT COUNT(*) FROM fams_attachment WHERE business_id=101",
                Integer.class));
        assertEquals(expected, db.queryForObject("SELECT COUNT(*) FROM fams_attachment_upload_key "
                + "WHERE uploader_account_id=101", Integer.class));
        assertEquals(expected, db.queryForObject("SELECT COUNT(*) FROM fams_audit_event "
                + "WHERE resource_id=101 AND action='ATTACHMENT_UPLOADED'", Integer.class));
        assertEquals(expected, db.queryForObject("SELECT version FROM fams_inbound_receipt WHERE id=101", Integer.class));
        try (var files = Files.list(ROOT)) { assertEquals(expected, files.count()); }
    }

    private void seed() {
        db.update("INSERT INTO fams_organization (id,code,code_normalized,name,status,version,created_at,updated_at) "
                + "VALUES (101,'ATT','ATT','Attachments','ACTIVE',0,NOW(3),NOW(3))");
        for (int id = 101; id <= 105; id++) {
            String name = id == 101 ? "attachment-creator" : id == 102 ? "attachment-other"
                    : id == 103 ? "attachment-system" : id == 104 ? "attachment-admin" : "attachment-dual";
            db.update("INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,organization_id,"
                    + "status,version,created_at,updated_at) VALUES (?,?,?,?,101,'ACTIVE',0,NOW(3),NOW(3))",
                    id, "ATT" + id, "ATT" + id, name);
            db.update("INSERT INTO fams_system_account (id,employee_id,username,username_normalized,password_hash,"
                    + "status,initial_password_required,failed_attempts,auth_epoch,authorization_version,"
                    + "version,created_at,updated_at) VALUES (?,?,?,?,?,'ENABLED',FALSE,0,0,0,0,NOW(3),NOW(3))",
                    id, id, name, name.toUpperCase(), new BCryptPasswordEncoder(4).encode(PASSWORD));
            db.update("INSERT INTO fams_account_role (account_id,role_code,granted_at) VALUES (?,?,NOW(3))",
                    id, id == 103 ? "SYSTEM_ADMIN" : id >= 104 ? "ASSET_ADMIN" : "WAREHOUSE_OPERATOR");
            if (id == 105) {
                db.update("INSERT INTO fams_account_role (account_id,role_code,granted_at) "
                        + "VALUES (105,'WAREHOUSE_OPERATOR',NOW(3))");
            }
        }
        db.update("INSERT INTO fams_business_parameter_version (id,version,status,effective_at,actor_account_id,"
                + "actor_username,snapshot_json,created_at) VALUES (101,101,'SUPERSEDED',NOW(3),101,"
                + "'attachment-creator','{}',NOW(3))");
        db.update("INSERT INTO fams_inbound_receipt (id,receipt_no,creator_account_id,creator_username_snapshot,"
                + "receipt_status,business_date,parameter_version_id,timezone_snapshot,admin_decision_deadline_workdays,"
                + "warehouse_claim_deadline_workdays,warehouse_first_execution_deadline_workdays,version,"
                + "created_at,updated_at,created_by,updated_by) VALUES (101,'ATT101',101,'attachment-creator',"
                + "'DRAFT','2026-09-27',101,'Asia/Shanghai',2,1,2,0,NOW(3),NOW(3),101,101)");
    }

    private Session login(String username) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/v1/auth/login")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of(
                    "username", username, "password", PASSWORD)))).build();
        var result = assertStatus(200, http.send(request, HttpResponse.BodyHandlers.ofString()));
        return new Session(result.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                JSON.readTree(result.body()).path("csrfToken").asText());
    }

    private HttpResponse<String> sendUpload(Session session, String key, long version, String content) {
        try { return upload(session, key, version, content, true); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private HttpResponse<String> upload(Session session, String key, long version, String content, boolean csrf)
            throws Exception {
        String boundary = "fams-attachment-test-boundary";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"doc.pdf\""
                + "\r\nContent-Type: application/pdf\r\n\r\n" + content + "\r\n--" + boundary + "--\r\n";
        var request = HttpRequest.newBuilder(uri("/api/v1/inbound-receipts/101/attachments"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Idempotency-Key", key).header("If-Match-Version", Long.toString(version));
        if (session != null) {
            request.header("Cookie", session.cookie());
            if (csrf) { request.header("X-CSRF-Token", session.csrf()); }
        }
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
    private HttpResponse<String> assertStatus(int status, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body()); return response;
    }
    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("fams-attachment-http-"); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    private record Session(String cookie, String csrf) { }

    static final class ControlledScanner implements MalwareScanner {
        volatile CyclicBarrier barrier;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
        @Override public Result scan(java.io.InputStream content) {
            try {
                content.readAllBytes();
                if (barrier != null) { barrier.await(10, TimeUnit.SECONDS); }
                if (entered != null) { entered.countDown(); release.await(10, TimeUnit.SECONDS); }
                return Result.CLEAN;
            } catch (Exception error) { return Result.UNAVAILABLE; }
        }
    }
    @TestConfiguration
    @ConditionalOnProperty(name = "d6.09.scanner-fixture", havingValue = "true", matchIfMissing = true)
    static class ScannerConfiguration {
        @Bean @Primary ControlledScanner controlledScanner() { return new ControlledScanner(); }
    }
    @SpringBootApplication(scanBasePackages = "com.fams")
    static class TestApplication { }
}
