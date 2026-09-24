package com.fams.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
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

@SpringBootTest(classes = AssetHttpTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=fams-product",
            "spring.flyway.locations=classpath:db/migration/d6_04",
            "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
            "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra",
            "fams.asset.enterprise-name=Test Enterprise"
        })
@EnabledIfSystemProperty(named = "d6.06.asset.runtime", matches = "true")
class AssetHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> required("D6_06_TEST_DATASOURCE_URL"));
        registry.add("spring.datasource.username", () -> required("D6_06_TEST_DATASOURCE_USERNAME"));
        registry.add("spring.datasource.password", () -> required("D6_06_TEST_DATASOURCE_PASSWORD"));
    }

    @Test
    void createCopyReadCancelAndTagRequireRealIdentityAndKeepCustodyEmpty() throws Exception {
        seed();
        Session warehouse = login("warehouse");
        Session other = login("other");
        Session admin = login("admin");

        String key = "11111111-1111-4111-8111-111111111111";
        HttpResponse<String> created = request("POST", "/api/v1/assets", "{\"name\":\"Laptop\"}",
                warehouse, writeHeaders(warehouse, key, null));
        assertEquals(201, created.statusCode(), created.body());
        JsonNode asset = JSON.readTree(created.body());
        String id = asset.path("id").asText();
        assertEquals("DRAFT", asset.path("cardStage").asText());
        assertEquals("WAITING_INBOUND", asset.path("lifecycle").asText());
        assertTrue(asset.path("currentCustody").isMissingNode()
                || asset.path("currentCustody").isNull());
        assertEquals(created.body(), request("POST", "/api/v1/assets", "{\"name\":\"Laptop\"}",
                warehouse, writeHeaders(warehouse, key, null)).body());
        assertEquals(409, request("POST", "/api/v1/assets", "{\"name\":\"Other\"}",
                warehouse, writeHeaders(warehouse, key, null)).statusCode());
        assertEquals(403, request("POST", "/api/v1/assets", "{\"name\":\"Denied\"}",
                admin, writeHeaders(admin, "22222222-2222-4222-8222-222222222222", null)).statusCode());
        assertEquals(404, request("GET", "/api/v1/assets/" + id, null,
                other, Map.of()).statusCode());
        HttpResponse<String> listed = request("GET", "/api/v1/assets?name=Laptop", null,
                warehouse, Map.of());
        assertEquals(200, listed.statusCode(), listed.body());
        assertEquals(1, JSON.readTree(listed.body()).path("total").asInt(), listed.body());
        assertEquals(0, JSON.readTree(request("GET", "/api/v1/assets?name=Missing", null,
                warehouse, Map.of()).body()).path("total").asInt());
        assertEquals(400, request("GET", "/api/v1/assets?currentProcessorId=1", null,
                warehouse, Map.of()).statusCode());
        assertEquals(403, request("GET", "/api/v1/assets/" + id, null,
                admin, Map.of()).statusCode());

        JsonNode tag = JSON.readTree(request("GET", "/api/v1/assets/" + id + "/tag",
                null, warehouse, Map.of()).body());
        String token = tag.path("qrToken").asText();
        assertEquals(43, token.length());
        assertEquals("Test Enterprise", tag.path("enterpriseName").asText());
        assertFalse(tag.toString().contains(asset.path("assetCode").asText() + token));
        assertEquals(200, request("GET", "/api/v1/asset-access/" + token,
                null, warehouse, Map.of()).statusCode());

        HttpResponse<String> copied = request("POST", "/api/v1/assets/" + id + "/copy",
                "{\"quantity\":2}", warehouse,
                writeHeaders(warehouse, "33333333-3333-4333-8333-333333333333", 0L));
        assertEquals(201, copied.statusCode(), copied.body());
        assertEquals(copied.body(), request("POST", "/api/v1/assets/" + id + "/copy",
                "{\"quantity\":2}", warehouse,
                writeHeaders(warehouse, "33333333-3333-4333-8333-333333333333", 0L)).body());
        JsonNode copies = JSON.readTree(copied.body()).path("assets");
        assertEquals(2, copies.size());
        assertNotEquals(asset.path("assetCode").asText(), copies.get(0).path("assetCode").asText());
        assertTrue(copies.get(0).path("serialNumber").isMissingNode()
                || copies.get(0).path("serialNumber").isNull());
        assertTrue(copies.get(0).path("currentCustody").isMissingNode()
                || copies.get(0).path("currentCustody").isNull());

        HttpResponse<String> corrected = request("PATCH", "/api/v1/assets/" + id,
                "{\"notes\":\"checked\"}", warehouse,
                writeHeaders(warehouse, "34333333-3333-4333-8333-333333333333", 0L));
        assertEquals(200, corrected.statusCode(), corrected.body());
        assertEquals(1, JSON.readTree(corrected.body()).path("version").asInt());
        assertEquals(409, request("PATCH", "/api/v1/assets/" + id,
                "{\"notes\":\"stale\"}", warehouse,
                writeHeaders(warehouse, "35333333-3333-4333-8333-333333333333", 0L)).statusCode());
        assertEquals(409, request("POST", "/api/v1/assets/" + id + "/tag/reprint",
                "{}", warehouse,
                writeHeaders(warehouse, "36333333-3333-4333-8333-333333333333", 1L)).statusCode());

        HttpResponse<String> saved = request("POST", "/api/v1/asset-saved-filters",
                "{\"name\":\"My assets\",\"criteria\":{\"assetCode\":\"ASSET\"}}",
                warehouse, writeHeaders(warehouse, "37333333-3333-4333-8333-333333333333", null));
        assertEquals(201, saved.statusCode(), saved.body());
        String filterId = JSON.readTree(saved.body()).path("id").asText();
        assertEquals(0, JSON.readTree(request("GET", "/api/v1/asset-saved-filters", null,
                other, Map.of()).body()).path("total").asInt());
        assertEquals(1, JSON.readTree(request("GET", "/api/v1/asset-saved-filters", null,
                warehouse, Map.of()).body()).path("total").asInt());
        assertEquals(204, request("DELETE", "/api/v1/asset-saved-filters/" + filterId,
                null, warehouse, writeHeaders(warehouse,
                        "38333333-3333-4333-8333-333333333333", 0L)).statusCode());

        String publishedId = copies.get(0).path("id").asText();
        String publishedToken = JSON.readTree(request("GET", "/api/v1/assets/" + publishedId
                + "/tag", null, warehouse, Map.of()).body()).path("qrToken").asText();
        jdbc.update("UPDATE fams_asset SET card_stage = 'ACTIVE' WHERE id = ?",
                Long.parseLong(publishedId));
        HttpResponse<String> print = request("POST", "/api/v1/assets/" + publishedId + "/tag/reprint",
                "{}", warehouse, writeHeaders(warehouse,
                        "39333333-3333-4333-8333-333333333333", 0L));
        assertEquals(202, print.statusCode(), print.body());
        assertEquals(print.body(), request("POST", "/api/v1/assets/" + publishedId
                + "/tag/reprint", "{}", warehouse, writeHeaders(warehouse,
                "39333333-3333-4333-8333-333333333333", 0L)).body());
        String jobId = JSON.readTree(print.body()).path("jobId").asText();
        JsonNode printSnapshot = JSON.readTree(request("GET", "/api/v1/asset-tag-print-jobs/"
                + jobId, null, warehouse, Map.of()).body());
        assertEquals(publishedToken, printSnapshot.path("tag").path("qrToken").asText());
        assertEquals(404, request("GET", "/api/v1/asset-tag-print-jobs/" + jobId,
                null, other, Map.of()).statusCode());
        HttpResponse<String> rotated = request("POST", "/api/v1/assets/" + publishedId + "/tag/replace",
                "{\"reason\":\"Damaged\"}", warehouse,
                writeHeaders(warehouse, "3a333333-3333-4333-8333-333333333333", 0L));
        assertEquals(200, rotated.statusCode(), rotated.body());
        assertEquals(rotated.body(), request("POST", "/api/v1/assets/" + publishedId
                + "/tag/replace", "{\"reason\":\"Damaged\"}", warehouse,
                writeHeaders(warehouse, "3a333333-3333-4333-8333-333333333333", 0L)).body());
        assertEquals(1, JSON.readTree(rotated.body()).path("assetVersion").asInt());
        String replacementToken = JSON.readTree(rotated.body()).path("tag").path("qrToken").asText();
        assertNotEquals(publishedToken, replacementToken);
        assertEquals(404, request("GET", "/api/v1/asset-access/" + publishedToken,
                null, warehouse, Map.of()).statusCode());
        assertEquals(200, request("GET", "/api/v1/asset-access/" + replacementToken,
                null, warehouse, Map.of()).statusCode());
        assertEquals(publishedToken, JSON.readTree(request("GET", "/api/v1/asset-tag-print-jobs/"
                + jobId, null, warehouse, Map.of()).body()).path("tag").path("qrToken").asText());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM fams_asset_tag_history WHERE asset_id = ?",
                Integer.class, Long.parseLong(publishedId)));

        assertEquals(409, request("POST", "/api/v1/assets/" + id + "/cancel",
                "{\"reason\":\"Duplicate\"}", warehouse,
                writeHeaders(warehouse, "44444444-4444-4444-8444-444444444444", 2L)).statusCode());
        HttpResponse<String> cancelled = request("POST", "/api/v1/assets/" + id + "/cancel",
                "{\"reason\":\"Duplicate\"}", warehouse,
                writeHeaders(warehouse, "55555555-5555-4555-8555-555555555555", 1L));
        assertEquals(200, cancelled.statusCode(), cancelled.body());
        assertEquals("CANCELLED", JSON.readTree(cancelled.body()).path("cardStage").asText());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM fams_asset WHERE id = ?", Integer.class,
                Long.parseLong(id)));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM fams_asset", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM fams_security_event "
                + "WHERE event_type LIKE 'ASSET_%' AND request_id IS NOT NULL",
                Integer.class) >= 6);

        exerciseReferenceAndBatchPaths(warehouse, id, publishedId);
    }

    private void exerciseReferenceAndBatchPaths(Session warehouse, String cancelledId,
            String publishedId) throws Exception {
        String richBody = """
                {"name":"Desk","category":{"id":"1","version":0},
                 "organization":{"id":"1","version":0},
                 "plannedInboundWarehouse":{"id":"1","version":0},
                 "plannedInboundLocation":{"id":"1","version":0},
                 "brand":{"id":"1","version":0},
                 "manufacturer":{"id":"1","version":0},
                 "model":"M-1","unit":"piece","serialNumber":"s-001",
                 "serviceProvider":"Vendor","serviceContact":"Desk",
                 "servicePhone":"12345678901","source":"Purchase",
                 "extensionValues":{"color":"black"}}
                """;
        HttpResponse<String> richCreated = request("POST", "/api/v1/assets", richBody,
                warehouse, writeHeaders(warehouse, "6a333333-3333-4333-8333-333333333333", null));
        assertEquals(201, richCreated.statusCode(), richCreated.body());
        JsonNode rich = JSON.readTree(richCreated.body());
        String richId = rich.path("id").asText();
        assertEquals("1", rich.path("category").path("id").asText());
        assertEquals("s-001", rich.path("serialNumber").asText());
        assertEquals(1, JSON.readTree(request("GET", "/api/v1/assets?serialNumber=S-001",
                null, warehouse, Map.of()).body()).path("total").asInt());
        assertEquals(1, JSON.readTree(request("GET", "/api/v1/assets?assetCategoryId=1",
                null, warehouse, Map.of()).body()).path("total").asInt());
        assertEquals(400, request("GET", "/api/v1/assets?inboundDateFrom=bad", null,
                warehouse, Map.of()).statusCode());
        assertEquals(400, request("GET", "/api/v1/assets?unknown=value", null,
                warehouse, Map.of()).statusCode());
        assertEquals(400, request("GET", "/api/v1/assets?lifecycle=INVALID", null,
                warehouse, Map.of()).statusCode());
        assertEquals(409, request("POST", "/api/v1/assets", richBody.replace("\"version\":0",
                "\"version\":99"), warehouse,
                writeHeaders(warehouse, "6b333333-3333-4333-8333-333333333333", null)).statusCode());
        assertEquals(400, request("POST", "/api/v1/assets",
                richBody.replace("\"color\":\"black\"", "\"color\":123"),
                warehouse, writeHeaders(warehouse,
                        "6c333333-3333-4333-8333-333333333333", null)).statusCode());
        assertEquals(400, request("POST", "/api/v1/assets", "{\"name\":\" \"}",
                warehouse, writeHeaders(warehouse,
                        "6d333333-3333-4333-8333-333333333333", null)).statusCode());
        assertEquals(409, request("POST", "/api/v1/assets/" + cancelledId + "/copy",
                "{\"quantity\":1}", warehouse,
                writeHeaders(warehouse, "6e333333-3333-4333-8333-333333333333", 2L)).statusCode());
        assertEquals(400, request("POST", "/api/v1/assets/" + richId + "/copy",
                "{\"quantity\":0}", warehouse,
                writeHeaders(warehouse, "6f333333-3333-4333-8333-333333333333", 0L)).statusCode());
        assertEquals(400, request("PATCH", "/api/v1/assets/" + richId,
                "{\"attachments\":[{\"id\":\"1\"}]}", warehouse,
                writeHeaders(warehouse, "70333333-3333-4333-8333-333333333333", 0L)).statusCode());
        String correction = """
                {"name":"Desk v2","category":{"id":"1","version":0},
                 "organization":{"id":"1","version":0},
                 "plannedInboundWarehouse":{"id":"1","version":0},
                 "plannedInboundLocation":{"id":"1","version":0},
                 "brand":{"id":"1","version":0},
                 "manufacturer":{"id":"1","version":0},
                 "model":"M-2","unit":"set","serialNumber":"s-002",
                 "acquisitionDate":"2026-09-01","warrantyExpiryDate":"2027-09-01",
                 "serviceProvider":"Provider 2","serviceContact":"Contact 2",
                 "servicePhone":"10987654321","source":"Donation","notes":"Verified",
                 "extensionValues":{"color":"white"}}
                """;
        HttpResponse<String> updated = request("PATCH", "/api/v1/assets/" + richId,
                correction, warehouse,
                writeHeaders(warehouse, "70333333-3333-4333-8333-333333333334", 0L));
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals(1, JSON.readTree(updated.body()).path("version").asInt());
        assertEquals("s-002", JSON.readTree(updated.body()).path("serialNumber").asText());
        assertEquals(409, request("PATCH", "/api/v1/assets/" + richId,
                correction, warehouse,
                writeHeaders(warehouse, "70333333-3333-4333-8333-333333333335", 0L)).statusCode());
        assertEquals(200, request("GET", "/api/v1/assets/" + richId + "/timeline",
                null, warehouse, Map.of()).statusCode());

        HttpResponse<String> saved = request("POST", "/api/v1/asset-saved-filters",
                "{\"name\":\"Recent\",\"criteria\":{\"serialNumber\":\"S-001\"}}",
                warehouse, writeHeaders(warehouse,
                        "71333333-3333-4333-8333-333333333333", null));
        assertEquals(201, saved.statusCode(), saved.body());
        assertEquals(saved.body(), request("POST", "/api/v1/asset-saved-filters",
                "{\"name\":\"Recent\",\"criteria\":{\"serialNumber\":\"S-001\"}}",
                warehouse, writeHeaders(warehouse,
                        "71333333-3333-4333-8333-333333333333", null)).body());
        assertEquals(409, request("POST", "/api/v1/asset-saved-filters",
                "{\"name\":\"Other\",\"criteria\":{\"serialNumber\":\"S-001\"}}",
                warehouse, writeHeaders(warehouse,
                        "71333333-3333-4333-8333-333333333333", null)).statusCode());
        String savedId = JSON.readTree(saved.body()).path("id").asText();
        HttpResponse<String> renamed = request("PATCH", "/api/v1/asset-saved-filters/" + savedId,
                "{\"name\":\"Renamed\"}", warehouse,
                writeHeaders(warehouse, "72333333-3333-4333-8333-333333333333", 0L));
        assertEquals(200, renamed.statusCode(), renamed.body());
        assertEquals(1, JSON.readTree(renamed.body()).path("version").asInt());
        assertEquals(409, request("PATCH", "/api/v1/asset-saved-filters/" + savedId,
                "{\"name\":\"Stale\"}", warehouse,
                writeHeaders(warehouse, "73333333-3333-4333-8333-333333333333", 0L)).statusCode());
        assertEquals(204, request("DELETE", "/api/v1/asset-saved-filters/" + savedId,
                null, warehouse, writeHeaders(warehouse,
                        "74333333-3333-4333-8333-333333333333", 1L)).statusCode());
        assertEquals(404, request("DELETE", "/api/v1/asset-saved-filters/" + savedId,
                null, warehouse, writeHeaders(warehouse,
                        "74333333-3333-4333-8333-333333333334", 1L)).statusCode());
        assertEquals(204, request("DELETE", "/api/v1/asset-saved-filters/" + savedId,
                null, warehouse, writeHeaders(warehouse,
                        "74333333-3333-4333-8333-333333333333", 1L)).statusCode());

        jdbc.update("UPDATE fams_asset SET card_stage = 'ACTIVE' WHERE id = ?",
                Long.parseLong(richId));
        String batchBody = "{\"assets\":[{\"assetId\":\"" + publishedId
                + "\",\"version\":1},{\"assetId\":\"" + richId + "\",\"version\":1}]}";
        HttpResponse<String> batch = request("POST", "/api/v1/asset-tag-print-jobs",
                batchBody, warehouse, writeHeaders(warehouse,
                        "75333333-3333-4333-8333-333333333333", null));
        assertEquals(202, batch.statusCode(), batch.body());
        assertEquals(2, JSON.readTree(batch.body()).path("items").size());
        assertEquals(200, request("GET", "/api/v1/asset-tag-print-jobs/"
                + JSON.readTree(batch.body()).path("jobId").asText(), null,
                warehouse, Map.of()).statusCode());
        assertEquals(400, request("POST", "/api/v1/asset-tag-print-jobs",
                "{\"assets\":[]}", warehouse,
                writeHeaders(warehouse, "76333333-3333-4333-8333-333333333333", null)).statusCode());
        assertEquals(400, request("POST", "/api/v1/asset-tag-print-jobs",
                "{\"assets\":[{\"assetId\":\"" + richId + "\",\"version\":1},"
                + "{\"assetId\":\"" + richId + "\",\"version\":1}]}", warehouse,
                writeHeaders(warehouse, "77333333-3333-4333-8333-333333333333", null)).statusCode());
        assertEquals(409, request("POST", "/api/v1/asset-tag-print-jobs",
                "{\"assets\":[{\"assetId\":\"" + richId + "\",\"version\":0}]}", warehouse,
                writeHeaders(warehouse, "78333333-3333-4333-8333-333333333333", null)).statusCode());

        assertEquals(409, request("PATCH", "/api/v1/assets/" + richId,
                "{\"category\":{\"id\":\"1\",\"version\":0}}", warehouse,
                writeHeaders(warehouse, "79333333-3333-4333-8333-333333333333", 1L)).statusCode());
        assertEquals(409, request("POST", "/api/v1/assets/" + richId + "/cancel",
                "{\"reason\":\"No longer needed\"}", warehouse,
                writeHeaders(warehouse, "7a333333-3333-4333-8333-333333333333", 1L)).statusCode());
        assertEquals(409, request("POST", "/api/v1/assets/" + richId + "/tag/replace",
                "{\"reason\":\"Broken\"}", warehouse,
                writeHeaders(warehouse, "7b333333-3333-4333-8333-333333333333", 0L)).statusCode());
        assertEquals(409, request("POST", "/api/v1/assets/" + cancelledId + "/tag/replace",
                "{\"reason\":\"Broken\"}", warehouse,
                writeHeaders(warehouse, "7c333333-3333-4333-8333-333333333333", 2L)).statusCode());
        jdbc.update("UPDATE fams_asset SET custody_type='STORAGE',storage_warehouse_id=1,"
                + "current_location_id=1 WHERE id=?", Long.parseLong(richId));
        assertEquals("STORAGE", JSON.readTree(request("GET", "/api/v1/assets/" + richId,
                null, warehouse, Map.of()).body()).path("currentCustody")
                .path("custodyType").asText());
        jdbc.update("UPDATE fams_asset SET custody_type='PERSONAL',storage_warehouse_id=NULL,"
                + "using_organization_id=1,responsible_employee_id=1 WHERE id=?",
                Long.parseLong(richId));
        assertEquals("PERSONAL", JSON.readTree(request("GET", "/api/v1/assets/" + richId,
                null, warehouse, Map.of()).body()).path("currentCustody")
                .path("custodyType").asText());
        jdbc.update("UPDATE fams_asset SET custody_type='SHARED',responsible_employee_id=NULL,"
                + "department_responsible_employee_id=1 WHERE id=?", Long.parseLong(richId));
        assertEquals("SHARED", JSON.readTree(request("GET", "/api/v1/assets/" + richId,
                null, warehouse, Map.of()).body()).path("currentCustody")
                .path("custodyType").asText());
        assertEquals(1, JSON.readTree(request("GET", "/api/v1/assets?usageMode=SHARED",
                null, warehouse, Map.of()).body()).path("total").asInt());
        jdbc.update("UPDATE fams_asset SET card_stage='PENDING_INBOUND',custody_type=NULL,"
                + "storage_warehouse_id=NULL,current_location_id=NULL,"
                + "using_organization_id=NULL,responsible_employee_id=NULL,"
                + "department_responsible_employee_id=NULL WHERE id=?",
                Long.parseLong(richId));
        assertEquals(409, request("PATCH", "/api/v1/assets/" + richId,
                "{\"notes\":\"Changed\"}", warehouse,
                writeHeaders(warehouse, "7d333333-3333-4333-8333-333333333333", 1L)).statusCode());
        jdbc.update("UPDATE fams_asset SET card_stage='ACTIVE' WHERE id=?", Long.parseLong(richId));
        assertEquals(200, request("PATCH", "/api/v1/assets/" + richId,
                "{\"notes\":\"Active correction\"}", warehouse,
                writeHeaders(warehouse, "7e333333-3333-4333-8333-333333333333", 1L)).statusCode());

        HttpResponse<String> locked = request("POST", "/api/v1/assets",
                "{\"name\":\"Locked draft\"}", warehouse,
                writeHeaders(warehouse, "7f333333-3333-4333-8333-333333333333", null));
        assertEquals(201, locked.statusCode(), locked.body());
        long lockedId = JSON.readTree(locked.body()).path("id").asLong();
        jdbc.update("INSERT INTO fams_asset_business_lock "
                + "(asset_id,business_type,business_id,business_item_id,acquired_at) "
                + "VALUES (?,?,?,?,?)", lockedId, "INBOUND", 1, 1,
                Timestamp.from(Instant.parse("2026-09-24T01:00:00Z")));
        assertEquals(409, request("POST", "/api/v1/assets/" + lockedId + "/cancel",
                "{\"reason\":\"Duplicate\"}", warehouse,
                writeHeaders(warehouse, "80333333-3333-4333-8333-333333333333", 0L)).statusCode());
    }

    private void seed() {
        Timestamp now = Timestamp.from(Instant.parse("2026-09-24T01:00:00Z"));
        jdbc.update("INSERT INTO fams_organization (id,code,code_normalized,name,status,version,created_at,updated_at) VALUES (1,'ROOT','ROOT','Root','ACTIVE',0,?,?)", now, now);
        jdbc.update("INSERT INTO fams_asset_category (id,code,code_normalized,name,extension_template_json,status,version,created_at,updated_at) VALUES (1,'DESK','DESK','Desk','[{\"key\":\"color\",\"type\":\"TEXT\"}]','ACTIVE',0,?,?)", now, now);
        jdbc.update("INSERT INTO fams_manufacturer (id,code,code_normalized,name,status,version,created_at,updated_at) VALUES (1,'VENDOR','VENDOR','Vendor','ACTIVE',0,?,?)", now, now);
        jdbc.update("INSERT INTO fams_brand (id,code,code_normalized,name,status,version,created_at,updated_at) VALUES (1,'BRAND','BRAND','Brand','ACTIVE',0,?,?)", now, now);
        jdbc.update("INSERT INTO fams_warehouse (id,code,code_normalized,name,status,version,created_at,updated_at) VALUES (1,'WH','WH','Warehouse','ACTIVE',0,?,?)", now, now);
        jdbc.update("INSERT INTO fams_location (id,code,code_normalized,name,location_type,warehouse_id,status,version,created_at,updated_at) VALUES (1,'BIN','BIN','Bin','BIN',1,'ACTIVE',0,?,?)", now, now);
        for (int id = 1; id <= 3; id++) {
            String username = id == 1 ? "warehouse" : id == 2 ? "other" : "admin";
            jdbc.update("INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,organization_id,status,version,created_at,updated_at) VALUES (?,?,?,?,1,'ACTIVE',0,?,?)",
                    id, "E" + id, "E" + id, username, now, now);
            jdbc.update("INSERT INTO fams_system_account (id,employee_id,username,username_normalized,password_hash,status,initial_password_required,failed_attempts,auth_epoch,authorization_version,version,created_at,updated_at) VALUES (?,?,?,?,?,'ENABLED',FALSE,0,0,0,0,?,?)",
                    id, id, username, username.toUpperCase(), new BCryptPasswordEncoder(4).encode(PASSWORD), now, now);
            jdbc.update("INSERT INTO fams_account_role (account_id,role_code,granted_at) VALUES (?,?,?)",
                    id, id == 3 ? "SYSTEM_ADMIN" : "WAREHOUSE_OPERATOR", now);
        }
        jdbc.update("INSERT INTO fams_numbering_rule (code,code_normalized,target,mode,fixed_prefix,date_part,sequence_length,status,version,created_at,updated_at) VALUES ('ASSET','ASSET','ASSET','ENTERPRISE','FA','NONE',8,'ACTIVE',0,?,?)", now, now);
        String snapshot = "{\"timezone\":\"Asia/Shanghai\",\"workWeek\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\"],\"holidays\":[],\"adjustedWorkdays\":[],\"adminDecisionDeadlineWorkdays\":2,\"warehouseClaimDeadlineWorkdays\":1,\"warehouseFirstExecutionDeadlineWorkdays\":2,\"handoverAcceptanceDeadlineNaturalDays\":3,\"notificationEscalationThresholds\":{\"administratorUnreadHours\":4,\"onDutyWarehouseOperatorUnreadHours\":4,\"firstExecutionOverdueNaturalDays\":1,\"receiptDueUnreadHours\":24}}";
        jdbc.update("INSERT INTO fams_business_parameter_version (id,version,status,effective_at,actor_account_id,actor_username,snapshot_json,created_at) VALUES (1,1,'ACTIVE',?,3,'admin',?,?)", now, snapshot, now);
        jdbc.update("UPDATE fams_business_parameter_state SET current_version=1,current_version_id=1 WHERE singleton_id=1");
    }

    private Session login(String username) throws Exception {
        HttpResponse<String> response = request("POST", "/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}",
                null, Map.of("Content-Type", "application/json"));
        assertEquals(200, response.statusCode(), response.body());
        return new Session(response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                JSON.readTree(response.body()).path("csrfToken").asText());
    }

    private Map<String, String> writeHeaders(Session session, String key, Long version) {
        Map<String, String> headers = new java.util.HashMap<>(Map.of(
                "Content-Type", "application/json", "Idempotency-Key", key,
                "X-CSRF-Token", session.csrf()));
        if (version != null) { headers.put("If-Match-Version", version.toString()); }
        return headers;
    }

    private HttpResponse<String> request(String method, String path, String body,
            Session session, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        headers.forEach(builder::header);
        if (session != null) { builder.header("Cookie", session.cookie()); }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
