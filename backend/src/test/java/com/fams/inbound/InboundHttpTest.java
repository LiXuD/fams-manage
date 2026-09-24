package com.fams.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = InboundHttpTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=fams-product",
            "spring.flyway.locations=classpath:db/migration/d6_04",
            "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
            "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra"
        })
@EnabledIfSystemProperty(named = "d6.07.inbound.runtime", matches = "true")
class InboundHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> required("D6_07_TEST_DATASOURCE_URL"));
        registry.add("spring.datasource.username", () -> required("D6_07_TEST_DATASOURCE_USERNAME"));
        registry.add("spring.datasource.password", () -> required("D6_07_TEST_DATASOURCE_PASSWORD"));
    }

    @Test
    void inboundRequiresAdminDecisionAndExclusiveWarehouseAcceptance() throws Exception {
        seed();
        Session creator = login("creator");
        Session other = login("other");
        Session admin = login("admin");
        String assetBody = """
                {"name":"Desk","category":{"id":"1","version":0},
                 "organization":{"id":"1","version":0},"model":"M1","unit":"piece"}
                """;
        JsonNode asset = JSON.readTree(assertStatus(201, call("POST", "/api/v1/assets",
                assetBody, creator, headers(creator, 1, null))).body());
        String assetId = asset.path("id").asText();
        String draftBody = """
                {"businessDate":"2026-09-24","items":[{"asset":{"id":"%s","version":0},
                 "targetWarehouse":{"id":"1","version":0},
                 "targetLocation":{"id":"1","version":0}}]}
                """.formatted(assetId);
        HttpResponse<String> created = assertStatus(201, call("POST", "/api/v1/inbound-receipts",
                draftBody, creator, headers(creator, 2, null)));
        JsonNode receipt = JSON.readTree(created.body());
        String receiptId = receipt.path("id").asText();
        String itemId = receipt.path("items").get(0).path("id").asText();
        assertEquals("DRAFT", receipt.path("status").asText());
        assertEquals(created.body(), call("POST", "/api/v1/inbound-receipts", draftBody,
                creator, headers(creator, 2, null)).body());
        assertStatus(404, call("GET", "/api/v1/inbound-receipts/" + receiptId,
                null, other, Map.of()));
        assertStatus(403, call("POST", "/api/v1/inbound-receipts/" + receiptId + "/decision",
                "{\"decision\":\"APPROVED\"}", creator, headers(creator, 3, 0L)));

        receipt = JSON.readTree(assertStatus(200, call("POST", "/api/v1/inbound-receipts/"
                + receiptId + "/submit", null, creator, headers(creator, 4, 0L))).body());
        assertEquals("PENDING_ADMIN_DECISION", receipt.path("status").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE released_at IS NULL"));
        assertStatus(409, call("POST", "/api/v1/inbound-receipts/" + receiptId + "/claim",
                null, other, headers(other, 5, 1L)));
        receipt = JSON.readTree(assertStatus(200, call("POST", "/api/v1/inbound-receipts/"
                + receiptId + "/decision", "{\"decision\":\"APPROVED\"}", admin,
                headers(admin, 6, 1L))).body());
        assertEquals("WAITING_WAREHOUSE_CLAIM", receipt.path("status").asText());
        assertStatus(409, call("POST", "/api/v1/inbound-receipts/" + receiptId + "/decision",
                "{\"decision\":\"REJECTED\",\"reason\":\"No\"}", admin,
                headers(admin, 7, 1L)));
        receipt = JSON.readTree(assertStatus(200, call("POST", "/api/v1/inbound-receipts/"
                + receiptId + "/claim", null, creator, headers(creator, 8, 2L))).body());
        assertEquals("WAREHOUSE_PROCESSING", receipt.path("status").asText());
        assertStatus(409, call("POST", "/api/v1/inbound-receipts/" + receiptId
                + "/items/" + itemId + "/accept",
                "{\"assetVersion\":1,\"lockVersion\":0,\"observedAssetCode\":\"WRONG\"}",
                other, headers(other, 9, 3L)));
        String acceptBody = "{\"assetVersion\":1,\"lockVersion\":0,\"observedAssetCode\":\""
                + asset.path("assetCode").asText() + "\"}";
        JsonNode accepted = JSON.readTree(assertStatus(200, call("POST", "/api/v1/inbound-receipts/"
                + receiptId + "/items/" + itemId + "/accept", acceptBody,
                creator, headers(creator, 10, 3L))).body());
        assertEquals("COMPLETED", accepted.path("receipt").path("status").asText());
        assertEquals("IN_STOCK", accepted.path("asset").path("lifecycle").asText());
        assertEquals("STORAGE", accepted.path("asset").path("currentCustody")
                .path("custodyType").asText());
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE released_at IS NULL"));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_inbound_acceptance"));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_todo WHERE closed_at IS NULL"));
        assertTrue(count("SELECT COUNT(*) FROM fams_audit_event") >= 5);
    }

    private int count(String sql) { return jdbc.queryForObject(sql, Integer.class); }

    private void seed() {
        Timestamp now = Timestamp.from(Instant.parse("2026-09-24T01:00:00Z"));
        jdbc.update("INSERT INTO fams_organization (id,code,code_normalized,name,status,version,"
                + "created_at,updated_at) VALUES (1,'ROOT','ROOT','Root','ACTIVE',0,?,?)", now, now);
        jdbc.update("INSERT INTO fams_asset_category (id,code,code_normalized,name,"
                + "extension_template_json,status,version,created_at,updated_at) "
                + "VALUES (1,'DESK','DESK','Desk','[]','ACTIVE',0,?,?)", now, now);
        jdbc.update("INSERT INTO fams_warehouse (id,code,code_normalized,name,status,version,"
                + "created_at,updated_at) VALUES (1,'WH','WH','Warehouse','ACTIVE',0,?,?)", now, now);
        jdbc.update("INSERT INTO fams_location (id,code,code_normalized,name,location_type,"
                + "warehouse_id,status,version,created_at,updated_at) "
                + "VALUES (1,'BIN','BIN','Bin','BIN',1,'ACTIVE',0,?,?)", now, now);
        for (int id = 1; id <= 3; id++) {
            String name = id == 1 ? "creator" : id == 2 ? "other" : "admin";
            jdbc.update("INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,"
                    + "organization_id,status,version,created_at,updated_at) "
                    + "VALUES (?,?,?,?,1,'ACTIVE',0,?,?)", id, "E" + id, "E" + id, name, now, now);
            jdbc.update("INSERT INTO fams_system_account (id,employee_id,username,username_normalized,"
                    + "password_hash,status,initial_password_required,failed_attempts,auth_epoch,"
                    + "authorization_version,version,created_at,updated_at) "
                    + "VALUES (?,?,?,?,?,'ENABLED',FALSE,0,0,0,0,?,?)", id, id, name,
                    name.toUpperCase(), new BCryptPasswordEncoder(4).encode(PASSWORD), now, now);
            jdbc.update("INSERT INTO fams_account_role (account_id,role_code,granted_at) "
                    + "VALUES (?,?,?)", id, id == 3 ? "ASSET_ADMIN" : "WAREHOUSE_OPERATOR", now);
        }
        jdbc.update("INSERT INTO fams_numbering_rule (code,code_normalized,target,mode,"
                + "fixed_prefix,date_part,sequence_length,status,version,created_at,updated_at) "
                + "VALUES ('ASSET','ASSET','ASSET','ENTERPRISE','FA','NONE',8,'ACTIVE',0,?,?)", now, now);
        jdbc.update("INSERT INTO fams_numbering_rule (code,code_normalized,target,mode,"
                + "fixed_prefix,date_part,sequence_length,status,version,created_at,updated_at) "
                + "VALUES ('INBOUND','INBOUND','INBOUND_RECEIPT','ENTERPRISE','IN','NONE',8,'ACTIVE',0,?,?)",
                now, now);
        String snapshot = """
                {"timezone":"Asia/Shanghai","workWeek":["MONDAY","TUESDAY","WEDNESDAY",
                "THURSDAY","FRIDAY"],"holidays":[],"adjustedWorkdays":[],
                "adminDecisionDeadlineWorkdays":2,"warehouseClaimDeadlineWorkdays":1,
                "warehouseFirstExecutionDeadlineWorkdays":2,
                "handoverAcceptanceDeadlineNaturalDays":3,"notificationEscalationThresholds":{}}
                """;
        jdbc.update("INSERT INTO fams_business_parameter_version (id,version,status,effective_at,"
                + "actor_account_id,actor_username,snapshot_json,created_at) "
                + "VALUES (1,1,'ACTIVE',?,3,'admin',?,?)", now, snapshot, now);
        jdbc.update("UPDATE fams_business_parameter_state SET current_version=1,"
                + "current_version_id=1 WHERE singleton_id=1");
    }

    private Session login(String name) throws Exception {
        HttpResponse<String> response = assertStatus(200, call("POST", "/api/v1/auth/login",
                "{\"username\":\"" + name + "\",\"password\":\"" + PASSWORD + "\"}",
                null, Map.of("Content-Type", "application/json")));
        return new Session(response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                JSON.readTree(response.body()).path("csrfToken").asText());
    }

    private Map<String, String> headers(Session session, int serial, Long version) {
        Map<String, String> result = new HashMap<>(Map.of("Content-Type", "application/json",
                "X-CSRF-Token", session.csrf(), "Idempotency-Key",
                "a0000000-0000-4000-8000-%012d".formatted(serial)));
        if (version != null) { result.put("If-Match-Version", version.toString()); }
        return result;
    }

    private HttpResponse<String> call(String method, String path, String body,
            Session session, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        headers.forEach(builder::header);
        if (session != null) { builder.header("Cookie", session.cookie()); }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> assertStatus(int expected, HttpResponse<String> result) {
        assertEquals(expected, result.statusCode(), result.body());
        return result;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) { throw new IllegalStateException(name + " is required"); }
        return value;
    }

    private record Session(String cookie, String csrf) { }

    @SpringBootApplication(scanBasePackages = "com.fams")
    static class TestApplication {
        public static void main(String[] args) { SpringApplication.run(TestApplication.class, args); }
    }
}
