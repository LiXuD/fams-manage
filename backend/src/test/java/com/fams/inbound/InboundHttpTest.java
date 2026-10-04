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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

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
        for (Session selector : List.of(creator, admin)) {
            JsonNode organizations = JSON.readTree(assertStatus(200, call("GET",
                    "/api/v1/organizations/active-references", null, selector, Map.of())).body());
            assertEquals("1", organizations.path("items").get(0).path("id").asText());
        }
        assertStatus(403, call("GET", "/api/v1/organizations/active-references",
                null, login("employee"), Map.of()));
        assertStatus(404, call("GET", "/api/v1/inbound-receipts/99999",
                null, creator, Map.of()));
        assertStatus(404, call("POST", "/api/v1/inbound-receipts/99999/submit",
                null, creator, headers(creator, 91, 0L)));
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
        assertStatus(409, call("POST", "/api/v1/inbound-receipts",
                draftBody.replace("2026-09-24", "2026-09-25"),
                creator, headers(creator, 2, null)));
        assertEquals(1, JSON.readTree(assertStatus(200, call("GET", "/api/v1/inbound-receipts",
                null, creator, Map.of())).body()).path("total").asInt());
        assertEquals(0, JSON.readTree(assertStatus(200, call("GET", "/api/v1/inbound-receipts",
                null, admin, Map.of())).body()).path("total").asInt());
        assertStatus(400, call("GET", "/api/v1/inbound-receipts?status=UNKNOWN",
                null, creator, Map.of()));
        assertStatus(409, call("POST", "/api/v1/inbound-receipts",
                draftBody.replace("}]}", "},{\"asset\":{\"id\":\"" + assetId
                        + "\",\"version\":0}}]}"), creator, headers(creator, 30, null)));
        assertStatus(409, call("POST", "/api/v1/inbound-receipts",
                draftBody.replace("\"asset\":{\"id\":\"" + assetId + "\",\"version\":0}",
                        "\"asset\":{\"id\":\"" + assetId + "\",\"version\":99}"),
                creator, headers(creator, 31, null)));
        assertStatus(404, call("GET", "/api/v1/inbound-receipts/" + receiptId,
                null, other, Map.of()));
        assertStatus(409, call("PATCH", "/api/v1/inbound-receipts/" + receiptId,
                "{}", other, headers(other, 90, 0L)));
        assertStatus(403, call("POST", "/api/v1/inbound-receipts/" + receiptId + "/decision",
                "{\"decision\":\"APPROVED\"}", creator, headers(creator, 3, 0L)));

        receipt = JSON.readTree(assertStatus(200, call("POST", "/api/v1/inbound-receipts/"
                + receiptId + "/submit", null, creator, headers(creator, 4, 0L))).body());
        assertEquals("PENDING_ADMIN_DECISION", receipt.path("status").asText());
        assertEquals("PENDING_ADMIN_DECISION", JSON.readTree(assertStatus(200, call("POST",
                "/api/v1/inbound-receipts/" + receiptId + "/submit", null,
                creator, headers(creator, 4, 0L))).body()).path("status").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE released_at IS NULL"));
        assertStatus(409, call("POST", "/api/v1/inbound-receipts/" + receiptId + "/claim",
                null, other, headers(other, 5, 1L)));
        receipt = JSON.readTree(assertStatus(200, call("POST", "/api/v1/inbound-receipts/"
                + receiptId + "/decision", "{\"decision\":\"APPROVED\"}", admin,
                headers(admin, 6, 1L))).body());
        assertEquals("WAITING_WAREHOUSE_CLAIM", receipt.path("status").asText());
        assertEquals("WAITING_WAREHOUSE_CLAIM", JSON.readTree(assertStatus(200, call("POST",
                "/api/v1/inbound-receipts/" + receiptId + "/decision",
                "{\"decision\":\"APPROVED\"}", admin, headers(admin, 6, 1L))).body())
                .path("status").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM fams_inbound_decision"));
        assertStatus(409, call("POST", "/api/v1/inbound-receipts/" + receiptId + "/decision",
                "{\"decision\":\"REJECTED\",\"reason\":\"No\"}", admin,
                headers(admin, 7, 1L)));
        receipt = JSON.readTree(assertStatus(200, call("POST", "/api/v1/inbound-receipts/"
                + receiptId + "/claim", null, creator, headers(creator, 8, 2L))).body());
        assertEquals("WAREHOUSE_PROCESSING", receipt.path("status").asText());
        assertEquals("WAREHOUSE_PROCESSING", JSON.readTree(assertStatus(200, call("POST",
                "/api/v1/inbound-receipts/" + receiptId + "/claim", null,
                creator, headers(creator, 8, 2L))).body()).path("status").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM fams_inbound_processing_ownership"));
        assertStatus(409, call("POST", "/api/v1/inbound-receipts/" + receiptId
                + "/items/" + itemId + "/accept",
                "{\"assetVersion\":1,\"lockVersion\":0,\"observedAssetCode\":\"WRONG\"}",
                other, headers(other, 9, 3L)));
        assertStatus(409, call("POST", "/api/v1/inbound-receipts/" + receiptId
                + "/items/" + itemId + "/accept",
                "{\"assetVersion\":1,\"lockVersion\":0,\"observedAssetCode\":\"WRONG\"}",
                creator, headers(creator, 11, 3L)));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_inbound_acceptance"));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE released_at IS NULL"));
        String acceptBody = "{\"assetVersion\":1,\"lockVersion\":0,\"observedAssetCode\":\""
                + asset.path("assetCode").asText() + "\"}";
        JsonNode accepted = JSON.readTree(assertStatus(200, call("POST", "/api/v1/inbound-receipts/"
                + receiptId + "/items/" + itemId + "/accept", acceptBody,
                creator, headers(creator, 10, 3L))).body());
        assertEquals("COMPLETED", accepted.path("receipt").path("status").asText());
        assertEquals("COMPLETED", JSON.readTree(assertStatus(200, call("POST",
                "/api/v1/inbound-receipts/" + receiptId + "/items/" + itemId + "/accept",
                acceptBody, creator, headers(creator, 10, 3L))).body())
                .path("receipt").path("status").asText());
        assertEquals("IN_STOCK", accepted.path("asset").path("lifecycle").asText());
        assertEquals("STORAGE", accepted.path("asset").path("currentCustody")
                .path("custodyType").asText());
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE released_at IS NULL"));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_inbound_acceptance"));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_todo WHERE closed_at IS NULL"));

        JsonNode second = JSON.readTree(assertStatus(201, call("POST", "/api/v1/assets",
                assetBody.replace("Desk", "Chair"), creator, headers(creator, 12, null))).body());
        String secondBody = """
                {"businessDate":"2026-09-24","items":[{"asset":{"id":"%s","version":0},
                 "targetWarehouse":{"id":"1","version":0},
                 "targetLocation":{"id":"1","version":0}}]}
                """.formatted(second.path("id").asText());
        JsonNode rejected = JSON.readTree(assertStatus(201, call("POST",
                "/api/v1/inbound-receipts", secondBody, creator,
                headers(creator, 13, null))).body());
        String rejectedId = rejected.path("id").asText();
        assertStatus(200, call("POST", "/api/v1/inbound-receipts/" + rejectedId + "/submit",
                null, creator, headers(creator, 14, 0L)));
        JsonNode rejection = JSON.readTree(assertStatus(200, call("POST",
                "/api/v1/inbound-receipts/" + rejectedId + "/decision",
                "{\"decision\":\"REJECTED\",\"reason\":\"Wrong delivery\"}", admin,
                headers(admin, 15, 1L))).body());
        assertEquals("REJECTED", rejection.path("status").asText());
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE released_at IS NULL"));
        assertEquals("DRAFT", JSON.readTree(assertStatus(200, call("GET", "/api/v1/assets/"
                + second.path("id").asText(), null, creator, Map.of())).body())
                .path("cardStage").asText());
        assertEquals(0, count("SELECT COUNT(*) FROM fams_todo WHERE closed_at IS NULL"));

        JsonNode thirdAsset = JSON.readTree(assertStatus(201, call("POST", "/api/v1/assets",
                assetBody.replace("Desk", "Monitor"), creator, headers(creator, 16, null))).body());
        String thirdBody = """
                {"businessDate":"2026-09-24","items":[{"asset":{"id":"%s","version":0},
                 "targetWarehouse":{"id":"1","version":0},
                 "targetLocation":{"id":"1","version":0}}]}
                """.formatted(thirdAsset.path("id").asText());
        JsonNode third = JSON.readTree(assertStatus(201, call("POST", "/api/v1/inbound-receipts",
                thirdBody, creator, headers(creator, 17, null))).body());
        String thirdId = third.path("id").asText();
        JsonNode updated = JSON.readTree(assertStatus(200, call("PATCH",
                "/api/v1/inbound-receipts/" + thirdId,
                "{\"notes\":\"Counted\",\"businessDate\":\"2026-09-25\"}",
                creator, headers(creator, 18, 0L))).body());
        assertEquals(1, updated.path("version").asInt());
        assertEquals("2026-09-25", updated.path("snapshot").path("businessDate").asText());
        assertStatus(409, call("PATCH", "/api/v1/inbound-receipts/" + thirdId,
                "{\"notes\":\"Stale\"}", creator, headers(creator, 19, 0L)));
        JsonNode cancelled = JSON.readTree(assertStatus(200, call("POST",
                "/api/v1/inbound-receipts/" + thirdId + "/cancel",
                "{\"reason\":\"Duplicate order\"}", creator, headers(creator, 20, 1L))).body());
        assertEquals("CANCELLED", cancelled.path("status").asText());
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE released_at IS NULL"));

        JsonNode fourthAsset = JSON.readTree(assertStatus(201, call("POST", "/api/v1/assets",
                assetBody.replace("Desk", "Printer"), creator, headers(creator, 21, null))).body());
        String fourthBody = secondBody.replace("\"asset\":{\"id\":\""
                + second.path("id").asText() + "\"", "\"asset\":{\"id\":\""
                + fourthAsset.path("id").asText() + "\"");
        JsonNode fourth = JSON.readTree(assertStatus(201, call("POST", "/api/v1/inbound-receipts",
                fourthBody, creator, headers(creator, 22, null))).body());
        String fourthId = fourth.path("id").asText();
        assertStatus(200, call("POST", "/api/v1/inbound-receipts/" + fourthId + "/submit",
                null, creator, headers(creator, 23, 0L)));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE released_at IS NULL"));
        assertEquals("CANCELLED", JSON.readTree(assertStatus(200, call("POST",
                "/api/v1/inbound-receipts/" + fourthId + "/cancel",
                "{\"reason\":\"Delivery cancelled\"}", creator,
                headers(creator, 24, 1L))).body()).path("status").asText());
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE released_at IS NULL"));
        assertEquals("DRAFT", JSON.readTree(assertStatus(200, call("GET", "/api/v1/assets/"
                + fourthAsset.path("id").asText(), null, creator, Map.of())).body())
                .path("cardStage").asText());

        JsonNode fifth = JSON.readTree(assertStatus(201, call("POST", "/api/v1/assets",
                assetBody.replace("Desk", "Mouse"), creator, headers(creator, 25, null))).body());
        JsonNode sixth = JSON.readTree(assertStatus(201, call("POST", "/api/v1/assets",
                assetBody.replace("Desk", "Keyboard"), creator, headers(creator, 26, null))).body());
        String manyBody = """
                {"businessDate":"2026-09-24","defaultTargetWarehouse":{"id":"1","version":0},
                 "defaultTargetLocation":{"id":"1","version":0},"items":[
                 {"asset":{"id":"%s","version":0}},{"asset":{"id":"%s","version":0}}]}
                """.formatted(fifth.path("id").asText(), sixth.path("id").asText());
        JsonNode many = JSON.readTree(assertStatus(201, call("POST", "/api/v1/inbound-receipts",
                manyBody, creator, headers(creator, 27, null))).body());
        jdbc.update("UPDATE fams_location SET status='DISABLED' WHERE id=1");
        assertStatus(409, call("POST", "/api/v1/inbound-receipts/" + many.path("id").asText()
                + "/submit", null, creator, headers(creator, 28, 0L)));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE released_at IS NULL"));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset WHERE card_stage='PENDING_INBOUND'"));
        jdbc.update("UPDATE fams_location SET status='ACTIVE' WHERE id=1");
        assertEquals("PENDING_ADMIN_DECISION", JSON.readTree(assertStatus(200, call("POST",
                "/api/v1/inbound-receipts/" + many.path("id").asText() + "/submit",
                null, creator, headers(creator, 28, 0L))).body()).path("status").asText());
        assertEquals(2, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE released_at IS NULL"));
        assertEquals(2, count("SELECT COUNT(*) FROM fams_asset WHERE card_stage='PENDING_INBOUND'"));
        assertEquals(2, JSON.readTree(assertStatus(200, call("GET", "/api/v1/inbound-receipts?"
                + "status=PENDING_ADMIN_DECISION", null, admin, Map.of())).body())
                .path("items").get(0).path("items").size());
        assertTrue(count("SELECT COUNT(*) FROM fams_audit_event") >= 5);
        exerciseMultiExecution(many, creator, other, admin);
        exerciseDraftValidation(thirdBody, thirdAsset, asset, creator, other, admin);
        exerciseConcurrentClaim(assetBody, creator, other, admin);
        exerciseConcurrentReplay(assetBody, creator);
        exerciseConcurrentDecision(assetBody, creator, admin);


    }

    private void exerciseMultiExecution(JsonNode initial, Session creator,
            Session other, Session admin) throws Exception {
        String id = initial.path("id").asText();
        String path = "/api/v1/inbound-receipts/" + id;
        assertStatus(404, call("GET", "/api/v1/inbound-receipts/999999", null,
                creator, Map.of()));
        assertStatus(409, call("PATCH", path, "{\"notes\":\"late\"}",
                creator, headers(creator, 40, 1L)));
        assertStatus(400, call("POST", path + "/decision",
                "{\"decision\":\"REJECTED\"}", admin, headers(admin, 41, 1L)));
        assertStatus(400, call("POST", path + "/decision",
                "{\"decision\":\"UNKNOWN\"}", admin, headers(admin, 42, 1L)));
        assertStatus(409, call("POST", path + "/decision",
                "{\"decision\":\"APPROVED\"}", admin, headers(admin, 43, 0L)));
        assertStatus(409, call("POST", path + "/claim", null, other, headers(other, 44, 1L)));
        assertStatus(400, call("POST", path + "/cancel",
                "{\"reason\":\" \"}", creator, headers(creator, 45, 1L)));
        assertStatus(409, call("POST", path + "/cancel",
                "{\"reason\":\"No\"}", other, headers(other, 46, 1L)));

        JsonNode approved = JSON.readTree(assertStatus(200, call("POST", path + "/decision",
                "{\"decision\":\"APPROVED\"}", admin, headers(admin, 47, 1L))).body());
        assertEquals(2, approved.path("items").size());
        assertStatus(409, call("POST", path + "/cancel",
                "{\"reason\":\"Too late\"}", creator, headers(creator, 48, 2L)));
        assertStatus(409, call("POST", path + "/claim", null, other, headers(other, 49, 1L)));
        JsonNode claimed = JSON.readTree(assertStatus(200, call("POST", path + "/claim",
                null, other, headers(other, 50, 2L))).body());
        assertEquals("other", claimed.path("currentProcessor").path("processor")
                .path("username").asText());
        assertStatus(409, call("POST", path + "/claim", null, creator,
                headers(creator, 51, 3L)));
        JsonNode first = claimed.path("items").get(0);
        JsonNode second = claimed.path("items").get(1);
        String firstPath = path + "/items/" + first.path("id").asText() + "/accept";
        String firstCode = first.path("assetSnapshot").path("assetCode").asText();
        String valid = "{\"assetVersion\":1,\"lockVersion\":0,\"observedAssetCode\":\""
                + firstCode + "\"}";
        assertStatus(409, call("POST", firstPath, valid, creator, headers(creator, 52, 3L)));
        assertStatus(409, call("POST", firstPath,
                valid.replace("\"lockVersion\":0", "\"lockVersion\":99"),
                other, headers(other, 53, 3L)));
        assertStatus(409, call("POST", firstPath,
                valid.replace("\"assetVersion\":1", "\"assetVersion\":0"),
                other, headers(other, 54, 3L)));
        JsonNode partly = JSON.readTree(assertStatus(200, call("POST", firstPath,
                valid, other, headers(other, 55, 3L))).body());
        assertEquals("WAREHOUSE_PROCESSING", partly.path("receipt").path("status").asText());
        assertEquals("ACCEPTED", partly.path("item").path("executionStatus").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE released_at IS NULL"));
        assertStatus(409, call("POST", firstPath, valid, other, headers(other, 56, 4L)));
        String secondCode = second.path("assetSnapshot").path("assetCode").asText();
        String secondValid = valid.replace(firstCode, secondCode);
        JsonNode completed = JSON.readTree(assertStatus(200, call("POST", path + "/items/"
                + second.path("id").asText() + "/accept", secondValid,
                other, headers(other, 57, 4L))).body());
        assertEquals("COMPLETED", completed.path("receipt").path("status").asText());
        assertEquals(0, count("SELECT COUNT(*) FROM fams_todo WHERE closed_at IS NULL"));
    }

    private void exerciseDraftValidation(String validBody, JsonNode draftAsset,
            JsonNode completedAsset, Session creator, Session other, Session admin) throws Exception {
        assertStatus(400, call("POST", "/api/v1/inbound-receipts",
                "{\"businessDate\":\"2026-09-24\",\"items\":[]}",
                creator, headers(creator, 60, null)));
        assertStatus(400, call("POST", "/api/v1/inbound-receipts",
                validBody.replace("2026-09-24", "bad-date"),
                creator, headers(creator, 61, null)));
        assertStatus(409, call("POST", "/api/v1/inbound-receipts",
                validBody.replace("\"asset\":{\"id\":\""
                        + draftAsset.path("id").asText() + "\"",
                        "\"asset\":{\"id\":\"99999\""),
                creator, headers(creator, 62, null)));
        assertStatus(409, call("POST", "/api/v1/inbound-receipts", validBody,
                other, headers(other, 63, null)));
        assertStatus(409, call("POST", "/api/v1/inbound-receipts",
                validBody.replace("\"asset\":{\"id\":\""
                        + draftAsset.path("id").asText() + "\"",
                        "\"asset\":{\"id\":\""
                                + completedAsset.path("id").asText() + "\""),
                creator, headers(creator, 64, null)));

        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO fams_warehouse (id,code,code_normalized,name,status,version,"
                + "created_at,updated_at) VALUES (2,'WH2','WH2','Other','ACTIVE',0,?,?)",
                now, now);
        jdbc.update("INSERT INTO fams_location (id,code,code_normalized,name,location_type,"
                + "warehouse_id,status,version,created_at,updated_at) "
                + "VALUES (2,'BIN2','BIN2','Other bin','BIN',2,'ACTIVE',0,?,?)", now, now);
        String crossWarehouse = validBody.replace("\"targetLocation\":{\"id\":\"1\"",
                "\"targetLocation\":{\"id\":\"2\"");
        assertStatus(409, call("POST", "/api/v1/inbound-receipts", crossWarehouse,
                creator, headers(creator, 65, null)));
        jdbc.update("UPDATE fams_location SET status='DISABLED' WHERE id=1");
        assertStatus(409, call("POST", "/api/v1/inbound-receipts", validBody,
                creator, headers(creator, 66, null)));
        jdbc.update("UPDATE fams_location SET status='ACTIVE' WHERE id=1");

        JsonNode draft = JSON.readTree(assertStatus(201, call("POST", "/api/v1/inbound-receipts",
                validBody, creator, headers(creator, 67, null))).body());
        String path = "/api/v1/inbound-receipts/" + draft.path("id").asText();
        assertStatus(404, call("GET", path, null, admin, Map.of()));
        assertStatus(400, call("PATCH", path, "{}", creator, headers(creator, 68, 0L)));
        JsonNode replaced = JSON.readTree(assertStatus(200, call("PATCH", path,
                "{\"items\":[{\"asset\":{\"id\":\""
                        + draftAsset.path("id").asText() + "\",\"version\":0},"
                        + "\"targetWarehouse\":{\"id\":\"1\",\"version\":0},"
                        + "\"targetLocation\":{\"id\":\"1\",\"version\":0}}]}",
                creator, headers(creator, 69, 0L))).body());
        assertEquals(1, replaced.path("version").asInt());
        jdbc.update("UPDATE fams_organization SET status='DISABLED' WHERE id=1");
        assertStatus(409, call("POST", path + "/submit", null, creator,
                headers(creator, 70, 1L)));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE released_at IS NULL"));
        jdbc.update("UPDATE fams_organization SET status='ACTIVE' WHERE id=1");
        assertStatus(200, call("POST", path + "/cancel",
                "{\"reason\":\"Validation checked\"}", creator, headers(creator, 71, 1L)));
    }

    private void exerciseConcurrentDecision(String assetBody, Session creator,
            Session admin) throws Exception {
        jdbc.update("UPDATE fams_account_role SET role_code='ASSET_ADMIN' WHERE account_id=2");
        jdbc.update("UPDATE fams_system_account SET authorization_version=authorization_version+1 "
                + "WHERE id=2");
        Session secondAdmin = login("other");
        JsonNode asset = JSON.readTree(assertStatus(201, call("POST", "/api/v1/assets",
                assetBody.replace("Desk", "Decision desk"), creator, headers(creator, 110, null))).body());
        String body = """
                {"businessDate":"2026-09-24","items":[{"asset":{"id":"%s","version":0},
                 "targetWarehouse":{"id":"1","version":0},
                 "targetLocation":{"id":"1","version":0}}]}
                """.formatted(asset.path("id").asText());
        JsonNode receipt = JSON.readTree(assertStatus(201, call("POST", "/api/v1/inbound-receipts",
                body, creator, headers(creator, 111, null))).body());
        String receiptId = receipt.path("id").asText();
        String path = "/api/v1/inbound-receipts/" + receiptId;
        assertStatus(200, call("POST", path + "/submit", null, creator, headers(creator, 112, 0L)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<HttpResponse<String>> approval = CompletableFuture.supplyAsync(
                () -> competingDecision(ready, start, path, admin, 113, "{\"decision\":\"APPROVED\"}"));
        CompletableFuture<HttpResponse<String>> rejection = CompletableFuture.supplyAsync(
                () -> competingDecision(ready, start, path, secondAdmin, 114,
                        "{\"decision\":\"REJECTED\",\"reason\":\"Not required\"}"));
        ready.await();
        start.countDown();
        HttpResponse<String> approved = approval.get();
        HttpResponse<String> rejected = rejection.get();
        assertTrue((approved.statusCode() == 200 && rejected.statusCode() == 409)
                || (approved.statusCode() == 409 && rejected.statusCode() == 200));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_inbound_decision WHERE receipt_id=" + receiptId));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_audit_event WHERE resource_id=" + receiptId
                + " AND action='INBOUND_ADMIN_DECIDED'"));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_idempotency_record "
                + "WHERE operation_id='decideInboundReceipt' AND resource_id=" + receiptId));
        JsonNode current = JSON.readTree(assertStatus(200, call("GET", path, null, admin, Map.of())).body());
        assertEquals(approved.statusCode() == 200 ? "WAITING_WAREHOUSE_CLAIM" : "REJECTED",
                current.path("status").asText());
        assertEquals(approved.statusCode() == 200 ? 1 : 0,
                count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id="
                        + asset.path("id").asText() + " AND released_at IS NULL"));
    }

    private HttpResponse<String> competingDecision(CountDownLatch ready, CountDownLatch start, String path,
            Session session, int serial, String body) {
        ready.countDown();
        try {
            start.await();
            return call("POST", path + "/decision", body, session, headers(session, serial, 1L));
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private void exerciseConcurrentReplay(String assetBody, Session creator) throws Exception {
        JsonNode asset = JSON.readTree(assertStatus(201, call("POST", "/api/v1/assets",
                assetBody.replace("Desk", "Replay desk"), creator, headers(creator, 100, null))).body());
        String body = """
                {"businessDate":"2026-09-24","items":[{"asset":{"id":"%s","version":0},
                 "targetWarehouse":{"id":"1","version":0},
                 "targetLocation":{"id":"1","version":0}}]}
                """.formatted(asset.path("id").asText());
        int before = count("SELECT COUNT(*) FROM fams_inbound_receipt");
        List<HttpResponse<String>> created = race("POST", "/api/v1/inbound-receipts",
                body, creator, 101, null);
        JsonNode first = JSON.readTree(assertStatus(201, created.get(0)).body());
        assertEquals(first, JSON.readTree(assertStatus(201, created.get(1)).body()));
        assertEquals(before + 1, count("SELECT COUNT(*) FROM fams_inbound_receipt"));
        String path = "/api/v1/inbound-receipts/" + first.path("id").asText();
        List<HttpResponse<String>> submitted = race("POST", path + "/submit", null, creator, 102, 0L);
        assertEquals(JSON.readTree(assertStatus(200, submitted.get(0)).body()),
                JSON.readTree(assertStatus(200, submitted.get(1)).body()));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id="
                + asset.path("id").asText() + " AND released_at IS NULL"));
        assertStatus(409, call("POST", "/api/v1/inbound-receipts",
                body.replace("2026-09-24", "2026-09-25"), creator, headers(creator, 101, null)));
    }

    private List<HttpResponse<String>> race(String method, String path, String body,
            Session session, int serial, Long version) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        java.util.function.Supplier<HttpResponse<String>> request = () -> {
            ready.countDown();
            try {
                start.await();
                return call(method, path, body, session, headers(session, serial, version));
            } catch (Exception error) { throw new IllegalStateException(error); }
        };
        CompletableFuture<HttpResponse<String>> first = CompletableFuture.supplyAsync(request);
        CompletableFuture<HttpResponse<String>> second = CompletableFuture.supplyAsync(request);
        ready.await();
        start.countDown();
        return List.of(first.get(), second.get());
    }

    private void exerciseConcurrentClaim(String assetBody, Session creator,
            Session other, Session admin) throws Exception {
        JsonNode asset = JSON.readTree(assertStatus(201, call("POST", "/api/v1/assets",
                assetBody.replace("Desk", "Scanner"), creator, headers(creator, 80, null))).body());
        String body = """
                {"businessDate":"2026-09-24","items":[{"asset":{"id":"%s","version":0},
                 "targetWarehouse":{"id":"1","version":0},
                 "targetLocation":{"id":"1","version":0}}]}
                """.formatted(asset.path("id").asText());
        JsonNode receipt = JSON.readTree(assertStatus(201, call("POST",
                "/api/v1/inbound-receipts", body, creator,
                headers(creator, 81, null))).body());
        String path = "/api/v1/inbound-receipts/" + receipt.path("id").asText();
        assertStatus(200, call("POST", path + "/submit", null, creator,
                headers(creator, 82, 0L)));
        assertStatus(200, call("POST", path + "/decision",
                "{\"decision\":\"APPROVED\"}", admin, headers(admin, 83, 1L)));
        CompletableFuture<HttpResponse<String>> first = CompletableFuture.supplyAsync(
                () -> concurrentClaim(path, creator, 84));
        CompletableFuture<HttpResponse<String>> second = CompletableFuture.supplyAsync(
                () -> concurrentClaim(path, other, 85));
        HttpResponse<String> firstResponse = first.get();
        HttpResponse<String> secondResponse = second.get();
        int firstStatus = firstResponse.statusCode();
        int secondStatus = secondResponse.statusCode();
        assertTrue((firstStatus == 200 && secondStatus == 409)
                || (firstStatus == 409 && secondStatus == 200),
                "concurrent claim statuses: " + firstStatus + ", " + secondStatus
                        + "; bodies: " + firstResponse.body() + ", " + secondResponse.body());
        assertEquals(1, count("SELECT COUNT(*) FROM fams_inbound_processing_ownership "
                + "WHERE receipt_id=" + receipt.path("id").asText() + " AND archived_at IS NULL"));
    }

    private HttpResponse<String> concurrentClaim(String path, Session session, int serial) {
        try { return call("POST", path + "/claim", null, session,
                headers(session, serial, 2L)); }
        catch (Exception error) { throw new IllegalStateException(error); }
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
        for (int id = 1; id <= 4; id++) {
            String name = id == 1 ? "creator" : id == 2 ? "other" : id == 3 ? "admin" : "employee";
            jdbc.update("INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,"
                    + "organization_id,status,version,created_at,updated_at) "
                    + "VALUES (?,?,?,?,1,'ACTIVE',0,?,?)", id, "E" + id, "E" + id, name, now, now);
            jdbc.update("INSERT INTO fams_system_account (id,employee_id,username,username_normalized,"
                    + "password_hash,status,initial_password_required,failed_attempts,auth_epoch,"
                    + "authorization_version,version,created_at,updated_at) "
                    + "VALUES (?,?,?,?,?,'ENABLED',FALSE,0,0,0,0,?,?)", id, id, name,
                    name.toUpperCase(), new BCryptPasswordEncoder(4).encode(PASSWORD), now, now);
            jdbc.update("INSERT INTO fams_account_role (account_id,role_code,granted_at) "
                    + "VALUES (?,?,?)", id, id == 3 ? "ASSET_ADMIN"
                            : id == 4 ? "EMPLOYEE" : "WAREHOUSE_OPERATOR", now);
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
