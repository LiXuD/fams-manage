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
import java.util.UUID;

@SpringBootTest(
        classes = HandoverHttpTest.TestApplication.class,
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
    }

    @AfterEach
    void cleanup() throws Exception {
        db.execute("DROP TRIGGER IF EXISTS owned_scrap_audit_failure");
        String requests = " IN (SELECT id FROM fams_scrap_request WHERE asset_id=951020)";
        db.update("DELETE FROM fams_todo WHERE business_type='SCRAP' AND business_id" + requests);
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
