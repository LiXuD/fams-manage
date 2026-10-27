package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fams.attachment.MalwareScanner;
import com.fams.auth.RepairHttpRuntimeCase.Session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** Actual HTTP/MySQL/owner ports; only external identity and scanner outcomes are fixtures. */
@SpringBootTest(
        classes = {
            HandoverHttpTest.TestApplication.class,
            RepairFactoryHttpRuntimeCase.ExternalFixtures.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=fams-product",
            "spring.flyway.locations=classpath:db/migration/d6_04",
            "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
            "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra",
            "fams.inventory.start-poll-ms=3600000"
        })
@EnabledIfSystemProperty(named = "d6.20.repair.factory.runtime", matches = "true")
class RepairFactoryHttpRuntimeCase {
    static final ObjectMapper JSON = new ObjectMapper();
    static final byte[] PNG =
            Base64.getDecoder()
                    .decode(
                            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+afo0AAAAASUVORK5CYII=");
    static final AtomicReference<MalwareScanner.Result> SCAN =
            new AtomicReference<>(MalwareScanner.Result.CLEAN);
    final HttpClient http = HttpClient.newHttpClient();
    final RepairHttpRuntimeCase fixture = new RepairHttpRuntimeCase();
    @Autowired JdbcTemplate db;
    @Autowired MiniSessionService miniSessions;

    @Value("${local.server.port}")
    int port;

    @Test
    void actualNativeFactoryPagesRoundTripOwnedOriginalsAndCloseOnlyAfterInbound()
            throws Exception {
        runNativeScenario(false);
    }

    @Test
    void actualNativeInitialSingleFactoryApprovalRetriesAndNeedsNoSecondDecision()
            throws Exception {
        runNativeScenario(true);
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_factory_scope WHERE"
                                + " scope_status='APPROVED'",
                        Long.class));
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_factory_event WHERE"
                                + " action='FACTORY_SCOPE_REQUESTED'",
                        Long.class));
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_decision WHERE"
                                + " allowed_method='INTERNAL_OR_FACTORY'",
                        Long.class));
    }

    @Test
    void actualNativeExpensePagesKeepOriginalCostReplayAndCumulativeAuthority() throws Exception {
        runNativeScenario(true, true);
        assertEquals(
                2L, db.queryForObject("SELECT COUNT(*) FROM fams_repair_factory_cost", Long.class));
        assertEquals(
                2L,
                db.queryForObject("SELECT COUNT(*) FROM fams_repair_factory_quote", Long.class));
        assertEquals(
                new java.math.BigDecimal("105.01"),
                db.queryForObject(
                        "SELECT SUM(amount) FROM fams_repair_factory_cost",
                        java.math.BigDecimal.class));
        assertEquals(
                2L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_factory_scope WHERE"
                                + " scope_status='APPROVED'",
                        Long.class));
    }

    private void runNativeScenario(boolean initial) throws Exception {
        runNativeScenario(initial, false);
    }

    private void runNativeScenario(boolean initial, boolean priced) throws Exception {
        runNativeScenario(initial, priced, false);
    }

    @Test
    void actualNativePartsPagesRetainOriginalCardAndReplayCommittedIndependentFacts()
            throws Exception {
        var original =
                db.queryForMap(
                        "SELECT id,asset_code,serial_number,created_business_date,"
                                + "last_inventory_business_date FROM fams_asset WHERE id=951020");
        runNativeScenario(false, false, true);
        assertEquals(
                original,
                db.queryForMap(
                        "SELECT id,asset_code,serial_number,created_business_date,"
                                + "last_inventory_business_date FROM fams_asset WHERE id=951020"));
        assertEquals(
                2L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_factory_parts_change", Long.class));
        assertEquals(
                2L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset_timeline_event WHERE asset_id=951020"
                                + " AND event_type='REPAIR_FACTORY_PARTS_OBSERVED'",
                        Long.class));
        assertFalse(
                db.queryForList(
                                "SELECT summary_json FROM fams_asset_timeline_event WHERE"
                                    + " asset_id=951020")
                        .toString()
                        .contains("PRIVATE-NATIVE-PART"));
    }

    private void runNativeScenario(boolean initial, boolean priced, boolean parts)
            throws Exception {
        var grants = new LinkedHashMap<String, Object>();
        for (var entry :
                Map.of("employee", 951002L, "admin", 951001L, "warehouse", 951003L).entrySet()) {
            var identity =
                    new MiniWechatIdentityPort.Identity("owned-factory-native", entry.getKey());
            db.update(
                    "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES"
                            + " (?,?,NOW(3))",
                    entry.getValue(),
                    miniSessions.identityHash(identity));
            var grant = miniSessions.start(identity, UUID.randomUUID());
            grants.put(
                    entry.getKey(),
                    Map.of(
                            "sessionToken",
                            grant.sessionToken(),
                            "accountId",
                            entry.getValue().toString(),
                            "bindingId",
                            grant.bindingId()));
        }
        var script =
                java.nio.file.Path.of(System.getProperty("user.dir"))
                        .getParent()
                        .resolve("apps/miniprogram/test/runtime-repair-factory-http.mjs");
        var builder =
                new ProcessBuilder(
                                "rtk",
                                "proxy",
                                System.getProperty(
                                        "fams.test.node",
                                        System.getenv()
                                                .getOrDefault("FAMS_TEST_NODE_EXECUTABLE", "node")),
                                script.toString())
                        .redirectErrorStream(true);
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_REPAIR_GRANTS", JSON.writeValueAsString(grants));
        builder.environment().put("FAMS_TEST_FACTORY_INITIAL", initial ? "1" : "0");
        builder.environment().put("FAMS_TEST_FACTORY_PRICED", priced ? "1" : "0");
        builder.environment().put("FAMS_TEST_FACTORY_PARTS", parts ? "1" : "0");
        var process = builder.start();
        try {
            assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
            var output =
                    new String(
                            process.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("\"nativeFactoryClosed\":true"), output);
            if (parts) {
                assertTrue(output.contains("\"nativePartsObserved\":true"), output);
                assertTrue(output.contains("\"committedPartsOriginalReplay\":true"), output);
                assertTrue(output.contains("\"partsImmutable\":true"), output);
            }
            System.out.println(output.strip());
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
        assertEquals("IN_STOCK", lifecycle());
        assertEquals(
                3L,
                db.queryForObject("SELECT COUNT(*) FROM fams_repair_factory_round", Long.class));
        assertEquals(
                (priced ? 8L : 4L) + (parts ? 1L : 0L),
                db.queryForObject("SELECT COUNT(*) FROM fams_repair_factory_evidence", Long.class));
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=951020 AND"
                                + " released_at IS NULL",
                        Long.class));
    }

    @Test
    void initialFactoryScopeAllowsActualInternalCompletionWithoutInventingDispatch()
            throws Exception {
        String id = submittedForInitialApproval();
        var decision = initialDecision();
        ok(post(fixture.admin, "/" + id + "/decision", 0, key(), decision));
        assertEquals("IN_USE", lifecycle());
        assertEquals(
                "APPROVED",
                privateDetail(fixture.admin, id)
                        .path("approvedScope")
                        .path("scopeStatus")
                        .asText());
        ok(post(fixture.warehouse, "/" + id + "/claim", 1, key(), null));
        for (String action : List.of("accept-in-place", "complete-in-place")) {
            var current = root(fixture.warehouse, id);
            var physical = guards(current);
            physical.put("observedCode", "REP-ASSET-951020");
            physical.put("actualLocationId", "951001");
            physical.put("neverLeftCustodian", true);
            physical.put(
                    "evidence",
                    "Actual original device never left its custodian and was checked at the"
                            + " unchanged location");
            ok(
                    post(
                            fixture.warehouse,
                            "/" + id + "/" + action,
                            current.path("version").asLong(),
                            key(),
                            physical));
        }
        assertEquals("COMPLETED", root(fixture.employee, id).path("status").asText());
        assertEquals("IN_USE", lifecycle());
        assertEquals(
                0L,
                db.queryForObject("SELECT COUNT(*) FROM fams_repair_factory_round", Long.class));
    }

    @Test
    void initialFactoryPrivateAuditFailureRollsBackOriginalApprovalAndRetryIsSafe()
            throws Exception {
        String id = submittedForInitialApproval(), nonce = key();
        var decision = initialDecision();
        db.execute(
                "CREATE TRIGGER owned_factory_audit_failure BEFORE INSERT ON"
                        + " fams_repair_factory_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET"
                        + " MESSAGE_TEXT='owned_initial_factory_failure'");
        assertEquals(
                500, post(fixture.admin, "/" + id + "/decision", 0, nonce, decision).statusCode());
        assertEquals("PENDING_ADMIN_DECISION", root(fixture.admin, id).path("status").asText());
        assertEquals(0, root(fixture.admin, id).path("version").asInt());
        assertEquals(
                0L, db.queryForObject("SELECT COUNT(*) FROM fams_repair_decision", Long.class));
        assertEquals(
                0L,
                db.queryForObject("SELECT COUNT(*) FROM fams_repair_factory_scope", Long.class));
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_receipt WHERE operation=?",
                        Long.class,
                        "DECIDE:" + id));
        assertEquals("IN_USE", lifecycle());
        db.execute("DROP TRIGGER owned_factory_audit_failure");
        var first = ok(post(fixture.admin, "/" + id + "/decision", 0, nonce, decision));
        var again = ok(post(fixture.admin, "/" + id + "/decision", 0, nonce, decision));
        assertEquals(first, again);
        assertEquals(
                1L,
                db.queryForObject("SELECT COUNT(*) FROM fams_repair_factory_scope", Long.class));
    }

    @Test
    void concurrentInitialAdministratorsCreateOneOriginalDecisionAndOneFactoryScope()
            throws Exception {
        String id = submittedForInitialApproval();
        var decision = initialDecision();
        Session another = login(10);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first =
                    pool.submit(
                            () -> post(fixture.admin, "/" + id + "/decision", 0, key(), decision));
            var second =
                    pool.submit(() -> post(another, "/" + id + "/decision", 0, key(), decision));
            assertEquals(
                    List.of(200, 409),
                    List.of(first.get().statusCode(), second.get().statusCode()).stream()
                            .sorted()
                            .toList());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(
                1L, db.queryForObject("SELECT COUNT(*) FROM fams_repair_decision", Long.class));
        assertEquals(
                1L,
                db.queryForObject("SELECT COUNT(*) FROM fams_repair_factory_scope", Long.class));
        assertEquals("WAITING_WAREHOUSE_CLAIM", root(fixture.admin, id).path("status").asText());
        assertEquals("IN_USE", lifecycle());
    }

    @Test
    void missingInitialHandlingChoiceIsAClientRejectionWithoutFactoryFacts() throws Exception {
        String id = submittedForInitialApproval();
        var input = initialDecision();
        input.putNull("allowedMethod");
        assertEquals(
                400, post(fixture.admin, "/" + id + "/decision", 0, key(), input).statusCode());
        assertEquals("PENDING_ADMIN_DECISION", root(fixture.admin, id).path("status").asText());
        assertEquals(
                0L,
                db.queryForObject("SELECT COUNT(*) FROM fams_repair_factory_scope", Long.class));
    }

    private String submittedForInitialApproval() throws Exception {
        var input = JSON.createObjectNode();
        input.put("assetId", "951020");
        input.put("assetVersion", 0);
        input.put("fault", "Actual original fault checked for a single initial factory approval");
        input.put("impact", "HIGH");
        input.put("occurredAt", now());
        return ok(post(fixture.employee, "", 0, key(), input)).path("id").asText();
    }

    private ObjectNode initialDecision() {
        var input = JSON.createObjectNode();
        input.put("result", "APPROVE");
        input.put("allowedMethod", "INTERNAL_OR_FACTORY");
        input.put(
                "reason",
                "PRIVATE-INITIAL-SCOPE checked original device and authorized no-cost factory"
                        + " handling");
        return input;
    }

    @TestConfiguration
    static class ExternalFixtures {
        @Bean
        MiniWechatIdentityPort factoryWechatFixture() {
            return code -> new MiniWechatIdentityPort.Identity("factory-runtime-fixture", code);
        }

        @Bean
        @Primary
        MalwareScanner factoryScannerFixture() {
            return input -> SCAN.get();
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        RepairHttpRuntimeCase.database(registry);
    }

    @BeforeEach
    void seed() throws Exception {
        SCAN.set(MalwareScanner.Result.CLEAN);
        fixture.db = db;
        fixture.miniSessions = miniSessions;
        fixture.port = port;
        fixture.seed();
        db.update("UPDATE fams_business_parameter_version SET snapshot_json=? WHERE id=951000",
                "{\"timezone\":\"Asia/Shanghai\",\"workWeek\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\"],"
                        + "\"holidays\":[],\"adjustedWorkdays\":[],\"adminDecisionDeadlineWorkdays\":2,"
                        + "\"warehouseClaimDeadlineWorkdays\":1,\"warehouseFirstExecutionDeadlineWorkdays\":2}");
        db.update(
                "UPDATE fams_asset SET serial_number='ORIGINAL-FACTORY-951020',"
                        + "serial_number_normalized='ORIGINAL-FACTORY-951020' WHERE id=951020");
    }

    @AfterEach
    void cleanup() throws Exception {
        SCAN.set(MalwareScanner.Result.CLEAN);
        db.execute("DROP TRIGGER IF EXISTS owned_factory_audit_failure");
        cleanupReplacementFacts();
        String owned =
                " IN (SELECT id FROM fams_repair_request WHERE asset_id BETWEEN 951020 AND 951023)";
        db.update("DELETE FROM fams_repair_factory_parts_change WHERE repair_id" + owned);
        db.update("DELETE FROM fams_repair_factory_event WHERE repair_id" + owned);
        db.update("DELETE FROM fams_repair_factory_evidence WHERE repair_id" + owned);
        for (String table : List.of("fams_attachment", "fams_attachment_scope")) {
            db.update(
                    "DELETE FROM "
                            + table
                            + " WHERE business_type='REPAIR_FACTORY' AND business_id"
                            + " IN (SELECT id FROM fams_repair_factory_round WHERE repair_id"
                            + owned
                            + ")");
        }
        for (String table : List.of("fams_repair_factory_cost", "fams_repair_factory_quote")) {
            db.update(
                    "DELETE FROM "
                            + table
                            + " WHERE round_id IN (SELECT id FROM fams_repair_factory_round WHERE"
                            + " repair_id"
                            + owned
                            + ")");
        }
        db.update("DELETE FROM fams_repair_factory_round WHERE repair_id" + owned);
        db.update("DELETE FROM fams_repair_factory_scope WHERE repair_id" + owned);
        for (String table :
                List.of(
                        "fams_loan_repair_return",
                        "fams_repair_inbound",
                        "fams_repair_physical_handling")) {
            db.update("DELETE FROM " + table + " WHERE repair_id" + owned);
        }
        fixture.cleanup();
    }

    private void cleanupReplacementFacts() {
        var origins = db.queryForList("SELECT asset_id FROM fams_asset_factory_replacement_origin"
                + " WHERE original_asset_id BETWEEN 951020 AND 951023");
        db.update("DELETE FROM fams_repair_factory_replacement WHERE original_asset_id BETWEEN 951020 AND 951023");
        for (var origin : origins) {
            long asset = ((Number) origin.get("asset_id")).longValue();
            for (var row : db.queryForList("SELECT receipt_id FROM fams_inbound_factory_replacement_origin"
                    + " WHERE asset_id=?", asset)) {
                long receipt = ((Number) row.get("receipt_id")).longValue();
                db.update("DELETE FROM fams_todo WHERE business_type='INBOUND' AND business_id=?", receipt);
                db.update("DELETE FROM fams_inbound_acceptance WHERE asset_id=?", asset);
                db.update("DELETE FROM fams_inbound_approval_scope_item WHERE decision_id IN"
                        + " (SELECT id FROM fams_inbound_decision WHERE receipt_id=?)", receipt);
                for (String table : List.of("fams_inbound_decision", "fams_inbound_processing_ownership",
                        "fams_inbound_receipt_item", "fams_inbound_factory_replacement_origin")) {
                    db.update("DELETE FROM " + table + " WHERE receipt_id=?", receipt);
                }
                db.update("DELETE FROM fams_inbound_receipt WHERE id=?", receipt);
            }
            db.update("DELETE FROM fams_asset_factory_replacement_origin WHERE asset_id=?", asset);
            for (String table : List.of("fams_asset_tag_history", "fams_asset_tag",
                    "fams_asset_timeline_event", "fams_asset_business_lock")) {
                db.update("DELETE FROM " + table + " WHERE asset_id=?", asset);
            }
            db.update("DELETE FROM fams_asset WHERE id=?", asset);
        }
        db.update("DELETE FROM fams_idempotency_record WHERE actor_account_id BETWEEN 951001 AND 951010");
        db.update("DELETE FROM fams_numbering_rule WHERE code_normalized IN ('OWNEDFACTORYASSET','OWNEDFACTORYINBOUND')");
    }

    @Test
    void replacementCreatesIndependentCardAndRealInboundWithoutClosingOriginalResponsibility() throws Exception {
        String id = approved(), round = create(id, fixture.warehouse);
        dispatch(id, round, fixture.warehouse);
        String object = upload(id, round, fixture.warehouse, "REPLACEMENT");
        var input = replacementInput(id, round, object);
        String references = path(id, "/rounds/" + round + "/replacement-references/category?page=1&pageSize=20");
        assertEquals(200, get(fixture.warehouse, references).statusCode());
        assertEquals(404, get(fixture.admin, references).statusCode());
        assertEquals(403, get(fixture.employee, references).statusCode());
        assertEquals(403, get(fixture.otherWarehouse, references).statusCode());
        long version = privateDetail(fixture.warehouse, id).path("version").asLong();
        String operation = path(id, "/rounds/" + round + "/replacements"), requestKey = key();
        var old = JSON.valueToTree(db.queryForMap("SELECT * FROM fams_asset WHERE id=951020"));
        var result = ok(post(fixture.warehouse, operation, version, requestKey, input));
        String asset = result.path("replacementAssetId").asText();
        String receipt = result.path("inboundReceiptId").asText();
        assertFalse("951020".equals(asset));
        var card = ownerOk(ownerCall(fixture.warehouse, "GET", "/assets/" + asset, null, 0));
        assertEquals("DRAFT", card.path("cardStage").asText());
        assertEquals("FACTORY_REPLACEMENT", card.path("source").asText());
        assertEquals(input.path("newAsset").path("serialNumber"), card.path("serialNumber"));
        assertFalse("REP-ASSET-951020".equals(card.path("assetCode").asText()));
        assertEquals(result, ok(post(fixture.warehouse, operation, version, requestKey, input)));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_repair_factory_replacement"));
        assertEquals(403, post(fixture.employee, operation, version, key(), input).statusCode());
        assertEquals(403, get(fixture.employee, operation).statusCode());
        assertEquals(403, post(fixture.otherWarehouse, operation, version, key(), input).statusCode());
        var hidden = ok(get(fixture.admin, operation)).path("items").get(0);
        assertTrue(hidden.path("replacementAssetId").isNull());
        assertTrue(hidden.path("inbound").isNull());
        assertEquals(404, ownerCall(fixture.admin, "GET", "/assets/" + asset, null, 0).statusCode());
        assertEquals(404, ownerCall(fixture.admin, "GET", "/assets/" + asset + "/factory-replacements", null, 0).statusCode());
        assertEquals(0, ownerOk(ownerCall(fixture.admin, "GET", "/assets/951020/factory-replacements", null, 0)).path("items").size());
        var correction = JSON.createObjectNode().put("source", "ordinary purchase");
        assertEquals(409, ownerCall(fixture.warehouse, "PATCH", "/assets/" + asset, correction, 0).statusCode());
        correction = JSON.createObjectNode().put("serialNumber", "OTHER-SERIAL");
        assertEquals(409, ownerCall(fixture.warehouse, "PATCH", "/assets/" + asset, correction, 0).statusCode());
        var draft = ownerOk(ownerCall(fixture.warehouse, "GET", "/inbound-receipts/" + receipt, null, 0));
        assertEquals(result.path("sourceId"), draft.path("factoryReplacement").path("sourceId"));
        String inbound = "/inbound-receipts/" + receipt;
        var submitted = ownerOk(ownerCall(fixture.warehouse, "POST", inbound + "/submit", null, 0));
        assertEquals("PENDING_ADMIN_DECISION", submitted.path("status").asText());
        var approved = ownerOk(ownerCall(fixture.admin, "POST", inbound + "/decision",
                JSON.createObjectNode().put("decision", "APPROVED"), 1));
        assertEquals("WAITING_WAREHOUSE_CLAIM", approved.path("status").asText());
        var claimed = ownerOk(ownerCall(fixture.warehouse, "POST", inbound + "/claim", null, 2));
        String item = claimed.path("items").get(0).path("id").asText();
        var acceptance = JSON.createObjectNode().put("assetVersion", 1).put("lockVersion", 0)
                .put("observedAssetCode", card.path("assetCode").asText());
        var completed = ownerOk(ownerCall(fixture.warehouse, "POST", inbound + "/items/" + item + "/accept", acceptance, 3));
        assertEquals("COMPLETED", completed.path("receipt").path("status").asText());
        assertEquals("IN_STOCK", completed.path("asset").path("lifecycle").asText());
        assertEquals(old, JSON.valueToTree(db.queryForMap("SELECT * FROM fams_asset WHERE id=951020")));
        assertEquals("FACTORY_REPAIR", lifecycle());
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=951020 AND released_at IS NULL"));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_repair_factory_round WHERE received_at IS NOT NULL"));
        for (String side : List.of("951020", asset)) {
            var trace = ownerOk(ownerCall(fixture.warehouse, "GET", "/assets/" + side + "/factory-replacements", null, 0));
            assertEquals(result.path("sourceId"), trace.path("items").get(0).path("sourceId"));
            assertFalse(trace.toString().contains("Private named manufacturer"));
        }
        assertEquals("COMPLETED", ok(get(fixture.admin, operation)).path("items").get(0).path("inbound").path("status").asText());
    }

    @Test
    void replacementRejectsInvalidIdentityEvidenceAndVersionsAndRollsBackLateAudit() throws Exception {
        String id = approved(), round = create(id, fixture.warehouse);
        dispatch(id, round, fixture.warehouse);
        String object = upload(id, round, fixture.warehouse, "REPLACEMENT");
        var input = replacementInput(id, round, object);
        long version = privateDetail(fixture.warehouse, id).path("version").asLong();
        String operation = path(id, "/rounds/" + round + "/replacements");
        var invalid = input.deepCopy();
        invalid.put("replacementPhysicalIdentityChecked", false);
        assertEquals(400, post(fixture.warehouse, operation, version, key(), invalid).statusCode());
        invalid = input.deepCopy();
        invalid.withObject("/newAsset").put("serialNumber", "original-factory-951020");
        assertEquals(409, post(fixture.warehouse, operation, version, key(), invalid).statusCode());
        invalid = input.deepCopy();
        invalid.remove("lockVersion");
        assertEquals(400, post(fixture.warehouse, operation, version, key(), invalid).statusCode());
        invalid = input.deepCopy();
        invalid.put("assetVersion", 999);
        assertEquals(409, post(fixture.warehouse, operation, version, key(), invalid).statusCode());
        invalid = input.deepCopy();
        invalid.putArray("evidenceIds").add(UUID.randomUUID().toString());
        assertEquals(409, post(fixture.warehouse, operation, version, key(), invalid).statusCode());
        var before = count("SELECT COUNT(*) FROM fams_asset");
        db.execute("CREATE TRIGGER owned_factory_audit_failure BEFORE INSERT ON fams_audit_event FOR EACH ROW"
                + " BEGIN IF NEW.action='FACTORY_REPLACEMENT_RECORDED' THEN SIGNAL SQLSTATE '45000'"
                + " SET MESSAGE_TEXT='Owned replacement late audit failure'; END IF; END");
        assertTrue(post(fixture.warehouse, operation, version, key(), input).statusCode() >= 500);
        assertEquals(before, count("SELECT COUNT(*) FROM fams_asset"));
        for (String table : List.of("fams_asset_factory_replacement_origin", "fams_inbound_factory_replacement_origin",
                "fams_repair_factory_replacement", "fams_inbound_receipt")) {
            assertEquals(0, count("SELECT COUNT(*) FROM " + table));
        }
        db.execute("DROP TRIGGER owned_factory_audit_failure");
        ok(post(fixture.warehouse, operation, version, key(), input));
    }

    @Test
    void concurrentReplacementHasOneCardAndCancelledDraftReapplicationKeepsOrigin() throws Exception {
        String id = approved(), round = create(id, fixture.warehouse);
        dispatch(id, round, fixture.warehouse);
        String object = upload(id, round, fixture.warehouse, "REPLACEMENT");
        var input = replacementInput(id, round, object);
        long version = privateDetail(fixture.warehouse, id).path("version").asLong();
        String operation = path(id, "/rounds/" + round + "/replacements");
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> post(fixture.warehouse, operation, version, key(), input));
            var second = threads.submit(() -> post(fixture.warehouse, operation, version, key(), input));
            var replies = List.of(first.get(), second.get());
            assertEquals(List.of(200, 409), replies.stream().map(HttpResponse::statusCode).sorted().toList());
            var created = JSON.readTree(replies.stream().filter(r -> r.statusCode() == 200).findFirst().orElseThrow().body());
            String asset = created.path("replacementAssetId").asText(), receipt = created.path("inboundReceiptId").asText();
            ownerOk(ownerCall(fixture.warehouse, "POST", "/inbound-receipts/" + receipt + "/cancel",
                    JSON.createObjectNode().put("reason", "Corrected draft reapplication"), 0));
            var next = JSON.createObjectNode().put("businessDate", LocalDate.now().toString());
            var item = next.putArray("items").addObject();
            item.putObject("asset").put("id", asset).put("version", 0);
            item.putObject("targetWarehouse").put("id", "951000").put("version", 0);
            item.putObject("targetLocation").put("id", "951000").put("version", 0);
            var renewed = ownerOk(ownerCall(fixture.warehouse, "POST", "/inbound-receipts", next, 0));
            assertEquals(created.path("sourceId"), renewed.path("factoryReplacement").path("sourceId"));
            assertEquals(1, count("SELECT COUNT(*) FROM fams_repair_factory_replacement"));
            assertEquals(2, count("SELECT COUNT(*) FROM fams_inbound_factory_replacement_origin"));
            assertEquals(renewed.path("id"), ok(get(fixture.warehouse, operation)).path("items").get(0).path("inbound").path("id"));
        }
    }

    private ObjectNode replacementInput(String id, String round, String object) throws Exception {
        replacementNumbering();
        var input = guards(privateDetail(fixture.warehouse, id));
        input.put("roundVersion", privateRound(fixture.warehouse, id, round).path("version").asLong());
        input.put("originalDisposition", "FACTORY_RETAINED");
        input.put("replacementPhysicalIdentityChecked", true);
        input.put("observedAt", now());
        input.putArray("evidenceIds").add(object);
        input.put("reason", "Private manufacturer exchange proof, original responsibility still outstanding");
        var asset = input.putObject("newAsset");
        asset.put("name", "Independent replacement unit").put("serialNumber", "NEW-" + UUID.randomUUID())
                .put("model", "Replacement model").put("unit", "台");
        asset.putObject("category").put("id", "951000").put("version", 0);
        asset.putObject("organization").put("id", "951000").put("version", 0);
        input.put("businessDate", LocalDate.now().toString());
        input.put("warehouseId", "951000").put("warehouseVersion", 0)
                .put("locationId", "951000").put("locationVersion", 0);
        return input;
    }

    private void replacementNumbering() {
        for (String target : List.of("ASSET", "INBOUND_RECEIPT")) {
            String code = "ASSET".equals(target) ? "OWNEDFACTORYASSET" : "OWNEDFACTORYINBOUND";
            db.update("INSERT INTO fams_numbering_rule(id,code,code_normalized,target,mode,fixed_prefix,"
                            + "date_part,sequence_length,status,version,created_at,updated_at)"
                            + " SELECT ?,?,?,?,'ENTERPRISE',?,'NONE',8,'ACTIVE',0,NOW(3),NOW(3)"
                            + " WHERE NOT EXISTS(SELECT id FROM fams_numbering_rule WHERE target=? AND status='ACTIVE')",
                    "ASSET".equals(target) ? 951080 : 951081, code, code, target,
                    "ASSET".equals(target) ? "RXA" : "RXI", target);
        }
    }

    @Test
    void actualNativeReplacementPageKeepsOriginalReplayAndRequiresIndependentInbound() throws Exception {
        String id = approved(), round = create(id, fixture.warehouse);
        dispatch(id, round, fixture.warehouse);
        replacementNumbering();
        var old = JSON.valueToTree(db.queryForMap("SELECT * FROM fams_asset WHERE id=951020"));
        var grants = new LinkedHashMap<String, Object>();
        for (var entry : Map.of("employee", 951002L, "admin", 951001L, "warehouse", 951003L).entrySet()) {
            var identity = new MiniWechatIdentityPort.Identity("owned-replacement-native", entry.getKey());
            db.update("INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES (?,?,NOW(3))",
                    entry.getValue(), miniSessions.identityHash(identity));
            var grant = miniSessions.start(identity, UUID.randomUUID());
            grants.put(entry.getKey(), Map.of("sessionToken", grant.sessionToken(),
                    "accountId", entry.getValue().toString(), "bindingId", grant.bindingId()));
        }
        var script = java.nio.file.Path.of(System.getProperty("user.dir")).getParent()
                .resolve("apps/miniprogram/test/runtime-repair-replacement-http.mjs");
        var builder = new ProcessBuilder("rtk", "proxy", System.getProperty("fams.test.node",
                System.getenv().getOrDefault("FAMS_TEST_NODE_EXECUTABLE", "node")), script.toString())
                .redirectErrorStream(true);
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_REPAIR_GRANTS", JSON.writeValueAsString(grants));
        builder.environment().put("FAMS_TEST_REPLACEMENT_REPAIR", id);
        builder.environment().put("FAMS_TEST_REPLACEMENT_ROUND", round);
        var process = builder.start();
        JsonNode result;
        try {
            assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("\"nativeReplacementDraft\":true"), output);
            assertTrue(output.contains("\"replacementOriginalReplay\":true"), output);
            result = JSON.readTree(output.lines().filter(line -> line.startsWith("{"))
                    .reduce((first, second) -> second).orElseThrow());
            System.out.println(output.strip());
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
        String asset = result.path("replacementAssetId").asText();
        var card = ownerOk(ownerCall(fixture.warehouse, "GET", "/assets/" + asset, null, 0));
        assertEquals("DRAFT", card.path("cardStage").asText());
        String inbound = "/inbound-receipts/" + result.path("inboundReceiptId").asText();
        ownerOk(ownerCall(fixture.warehouse, "POST", inbound + "/submit", null, 0));
        ownerOk(ownerCall(fixture.admin, "POST", inbound + "/decision", JSON.createObjectNode().put("decision", "APPROVED"), 1));
        var claimed = ownerOk(ownerCall(fixture.warehouse, "POST", inbound + "/claim", null, 2));
        String item = claimed.path("items").get(0).path("id").asText();
        var acceptance = JSON.createObjectNode().put("assetVersion", 1).put("lockVersion", 0)
                .put("observedAssetCode", card.path("assetCode").asText());
        var completed = ownerOk(ownerCall(fixture.warehouse, "POST", inbound + "/items/" + item + "/accept", acceptance, 3));
        assertEquals("COMPLETED", completed.path("receipt").path("status").asText());
        assertEquals("IN_STOCK", completed.path("asset").path("lifecycle").asText());
        assertEquals(old, JSON.valueToTree(db.queryForMap("SELECT * FROM fams_asset WHERE id=951020")));
        assertEquals("FACTORY_REPAIR", lifecycle());
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=951020 AND released_at IS NULL"));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_repair_factory_round WHERE received_at IS NOT NULL"));
    }

    private HttpResponse<String> ownerCall(Session actor, String method, String path, ObjectNode body, long version) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .header("Cookie", actor.cookie()).header("X-CSRF-Token", actor.csrf())
                .header("Idempotency-Key", key()).header("If-Match-Version", Long.toString(version))
                .header("Content-Type", "application/json").method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ownerOk(HttpResponse<String> response) {
        assertTrue(response.statusCode() == 200 || response.statusCode() == 201, response.body());
        return JSON.readTree(response.body());
    }

    private int count(String sql) {
        return Objects.requireNonNull(db.queryForObject(sql, Integer.class));
    }

    @Test
    void twoActualPaidRoundsUsePrivateBudgetAndTruthfulOverrunUntilExplicitExpansion()
            throws Exception {
        String id = begin();
        expenseScope(id, "LIMITED", "100.00");
        approve(id);
        String first = create(id, fixture.warehouse);
        money(id, first, "quotes", "40.10", "QUOTE");
        dispatch(id, first, fixture.warehouse);
        money(id, first, "cost", "39.99", "COST_RECEIPT");
        paidReceipt(id, first, "39.99", "STILL_FAULTY");
        String second = create(id, fixture.warehouse);
        money(id, second, "quotes", "60.01", "QUOTE");
        dispatch(id, second, fixture.warehouse);
        money(id, second, "cost", "65.02", "COST_RECEIPT");
        paidReceipt(id, second, "65.02", "RESTORED");
        var detail = privateDetail(fixture.warehouse, id);
        assertEquals("105.01", detail.path("costSummary").path("confirmedTotal").asText());
        assertTrue(detail.path("costSummary").path("overrun").asBoolean());
        assertTrue(detail.path("summary").path("followupOpen").asBoolean());
        assertEquals(
                2,
                db.queryForObject("SELECT COUNT(*) FROM fams_repair_factory_cost", Integer.class));
        assertEquals("UNDER_REPAIR", lifecycle());
        var row = root(fixture.warehouse, id);
        assertEquals(
                409,
                post(
                                fixture.warehouse,
                                "/" + id + "/close-inbound",
                                row.path("version").asLong(),
                                key(),
                                inbound(row, now()))
                        .statusCode());
        assertFalse(root(fixture.employee, id).toString().contains("105.01"));
        assertFalse(root(fixture.employee, id).toString().contains("costPolicy"));
        expenseScope(id, "LIMITED", "105.01");
        approve(id);
        assertFalse(
                privateDetail(fixture.warehouse, id)
                        .path("costSummary")
                        .path("followupOpen")
                        .asBoolean());
        row = root(fixture.warehouse, id);
        ok(
                post(
                        fixture.warehouse,
                        "/" + id + "/close-inbound",
                        row.path("version").asLong(),
                        key(),
                        inbound(row, now())));
        assertEquals("COMPLETED", root(fixture.employee, id).path("status").asText());
        assertEquals(0, activeLocks());
    }

    @Test
    void actualReceiptCanRetainUnassessedCostAndLateOriginalDoesNotInventZero() throws Exception {
        String id = approved(), round = create(id, fixture.warehouse);
        dispatch(id, round, fixture.warehouse);
        upload(id, round, fixture.warehouse, "RECEIPT");
        var row = privateDetail(fixture.warehouse, id);
        var receipt = movement(id, round, fixture.warehouse, true, "RESTORED");
        receipt.remove("actualCost");
        receipt.put("costUnassessed", true);
        ok(
                post(
                        fixture.warehouse,
                        path(id, "/rounds/" + round + "/receive-original"),
                        row.path("version").asLong(),
                        key(),
                        receipt));
        assertTrue(privateRound(fixture.warehouse, id, round).path("actualCost").isNull());
        assertEquals(
                1,
                privateDetail(fixture.warehouse, id)
                        .path("costSummary")
                        .path("unassessedCount")
                        .asInt());
        assertEquals("UNDER_REPAIR", lifecycle());
        row = root(fixture.warehouse, id);
        assertEquals(
                409,
                post(
                                fixture.warehouse,
                                "/" + id + "/close-inbound",
                                row.path("version").asLong(),
                                key(),
                                inbound(row, now()))
                        .statusCode());
        money(id, round, "cost", "0.00", null);
        assertEquals(
                "0.00", privateRound(fixture.warehouse, id, round).path("actualCost").asText());
        assertEquals(
                0,
                privateDetail(fixture.warehouse, id)
                        .path("costSummary")
                        .path("unassessedCount")
                        .asInt());
        row = root(fixture.warehouse, id);
        ok(
                post(
                        fixture.warehouse,
                        "/" + id + "/close-inbound",
                        row.path("version").asLong(),
                        key(),
                        inbound(row, now())));
    }

    @Test
    void initialPrivateExpenseApprovalIsAtomicAndCannotBeSmuggledIntoInternalDecision()
            throws Exception {
        String id = submittedForInitialApproval();
        var body = initialDecision();
        body.put("factoryCostPolicy", "LIMITED");
        body.put("factoryCostLimit", "99.95");
        body.put("allowedMethod", "INTERNAL_NO_COST");
        assertEquals(400, post(fixture.admin, "/" + id + "/decision", 0, key(), body).statusCode());
        body.put("allowedMethod", "INTERNAL_OR_FACTORY");
        ok(post(fixture.admin, "/" + id + "/decision", 0, key(), body));
        var detail = privateDetail(fixture.admin, id);
        assertEquals("LIMITED", detail.path("approvedScope").path("costPolicy").asText());
        assertEquals("99.95", detail.path("approvedScope").path("costLimit").asText());
        assertFalse(root(fixture.employee, id).toString().contains("99.95"));
        assertEquals(
                1, db.queryForObject("SELECT COUNT(*) FROM fams_repair_decision", Integer.class));
    }

    @Test
    void pendingExpenseApprovalNeverPreventsActualReceiptAndChangedPhysicalBasisNeedsNewRequest()
            throws Exception {
        String id = approved(), round = create(id, fixture.warehouse);
        dispatch(id, round, fixture.warehouse);
        money(id, round, "cost", "25.00", "COST_RECEIPT");
        expenseScope(id, "LIMITED", "25.00");
        paidReceipt(id, round, "25.00", "RESTORED");
        var detail = privateDetail(fixture.admin, id);
        assertEquals(
                409,
                post(
                                fixture.admin,
                                path(id, "/scope-decision"),
                                detail.path("version").asLong(),
                                key(),
                                scopeDecision(detail, "APPROVE"))
                        .statusCode());
        ok(
                post(
                        fixture.admin,
                        path(id, "/scope-withdrawal"),
                        detail.path("version").asLong(),
                        key(),
                        scopeDecision(detail, "WITHDRAW")));
        var physical = privateRound(fixture.warehouse, id, round);
        expenseScope(id, "LIMITED", "25.00");
        approve(id);
        var after = privateRound(fixture.warehouse, id, round);
        assertEquals(physical.path("receivedAt"), after.path("receivedAt"));
        assertEquals(physical.path("scopeId"), after.path("scopeId"));
        assertEquals(physical.path("dispatchScopeId"), after.path("dispatchScopeId"));
        assertFalse(
                privateDetail(fixture.warehouse, id)
                        .path("costSummary")
                        .path("followupOpen")
                        .asBoolean());
    }

    @Test
    void expenseHoldRetainsResponsibilityAcrossControlledOwnerTransfer() throws Exception {
        String id = approved(), round = create(id, fixture.warehouse);
        dispatch(id, round, fixture.warehouse);
        upload(id, round, fixture.warehouse, "RECEIPT");
        var row = privateDetail(fixture.warehouse, id);
        var receipt = movement(id, round, fixture.warehouse, true, "RESTORED");
        receipt.remove("actualCost");
        receipt.put("costUnassessed", true);
        ok(
                post(
                        fixture.warehouse,
                        path(id, "/rounds/" + round + "/receive-original"),
                        row.path("version").asLong(),
                        key(),
                        receipt));
        var inspection = ok(get(fixture.admin, "/" + id + "/reassignment"));
        var transfer = JSON.createObjectNode();
        transfer.put("processorAccountId", "951004");
        transfer.set("snapshot", inspection.path("snapshot"));
        transfer.put("unsynchronizedResultsChecked", true);
        transfer.put(
                "reason", "Controlled actual transfer retains unresolved expense responsibility");
        transfer.put(
                "evidence",
                "Both warehouses checked the actually received original and unresolved expense"
                        + " follow-up");
        ok(
                post(
                        fixture.admin,
                        "/" + id + "/reassignment",
                        root(fixture.admin, id).path("version").asLong(),
                        key(),
                        transfer));
        assertEquals(403, get(fixture.warehouse, path(id, "")).statusCode());
        row = privateDetail(fixture.otherWarehouse, id);
        assertTrue(row.path("costSummary").path("followupOpen").asBoolean());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_todo WHERE business_type='REPAIR' AND"
                                + " business_id=? AND todo_type='FACTORY_COST_FOLLOWUP' AND"
                                + " todo_status='OPEN' AND assignee_account_id=951004",
                        Integer.class,
                        id));
        var body = guards(row);
        body.put(
                "roundVersion",
                privateRound(fixture.otherWarehouse, id, round).path("version").asLong());
        body.put("amount", "0.00");
        body.put("reason", "Final verified warranty no-cost fact checked by new owner");
        body.put("finalAmountChecked", true);
        body.set("evidenceIds", JSON.createArrayNode());
        ok(
                post(
                        fixture.otherWarehouse,
                        path(id, "/rounds/" + round + "/cost"),
                        row.path("version").asLong(),
                        key(),
                        body));
        assertFalse(
                privateDetail(fixture.otherWarehouse, id)
                        .path("costSummary")
                        .path("followupOpen")
                        .asBoolean());
    }

    @Test
    void concurrentFinalCostsHaveOneFactAndOneVersionWinner() throws Exception {
        String id = approved(), round = create(id, fixture.warehouse);
        var row = privateDetail(fixture.warehouse, id);
        var body = guards(row);
        body.put(
                "roundVersion",
                privateRound(fixture.warehouse, id, round).path("version").asLong());
        body.put("amount", "0.00");
        body.put("reason", "Both final facts checked against the same pending round");
        body.put("finalAmountChecked", true);
        body.set("evidenceIds", JSON.createArrayNode());
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first =
                    pool.submit(
                            () ->
                                    post(
                                                    fixture.warehouse,
                                                    path(id, "/rounds/" + round + "/cost"),
                                                    row.path("version").asLong(),
                                                    key(),
                                                    body)
                                            .statusCode());
            var second =
                    pool.submit(
                            () ->
                                    post(
                                                    fixture.warehouse,
                                                    path(id, "/rounds/" + round + "/cost"),
                                                    row.path("version").asLong(),
                                                    key(),
                                                    body)
                                            .statusCode());
            assertEquals(
                    List.of(200, 409),
                    java.util.stream.Stream.of(first.get(), second.get()).sorted().toList());
        }
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_factory_cost WHERE round_id=?",
                        Integer.class,
                        round));
    }

    @Test
    void quoteAndCostLateAuditFailuresRollBackAllFactsVersionsAndOriginals() throws Exception {
        String id = approved(), round = create(id, fixture.warehouse);
        for (String operation : List.of("quotes", "cost")) {
            String object =
                    upload(
                            id,
                            round,
                            fixture.warehouse,
                            operation.equals("quotes") ? "QUOTE" : "COST_RECEIPT");
            var row = privateDetail(fixture.warehouse, id);
            var body = guards(row);
            body.put(
                    "roundVersion",
                    privateRound(fixture.warehouse, id, round).path("version").asLong());
            body.put("amount", "1.00");
            body.put("reason", "Exact original final amount is a truthful unapproved overrun");
            body.put("finalAmountChecked", true);
            body.set("evidenceIds", JSON.createArrayNode().add(object));
            String nonce = key(),
                    action =
                            operation.equals("quotes")
                                    ? "FACTORY_QUOTE_RECORDED"
                                    : "FACTORY_COST_RECORDED";
            db.execute(
                    "CREATE TRIGGER owned_factory_audit_failure BEFORE INSERT ON fams_audit_event"
                            + " FOR EACH ROW BEGIN IF NEW.action='"
                            + action
                            + "' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned cost late"
                            + " audit failure'; END IF; END");
            assertEquals(
                    500,
                    post(
                                    fixture.warehouse,
                                    path(id, "/rounds/" + round + "/" + operation),
                                    row.path("version").asLong(),
                                    nonce,
                                    body)
                            .statusCode());
            assertEquals(row, privateDetail(fixture.warehouse, id));
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_repair_receipt WHERE request_id=?",
                            Integer.class,
                            nonce));
            db.execute("DROP TRIGGER owned_factory_audit_failure");
            ok(
                    post(
                            fixture.warehouse,
                            path(id, "/rounds/" + round + "/" + operation),
                            row.path("version").asLong(),
                            nonce,
                            body));
        }
    }

    @Test
    void dispatchMustUseCurrentApprovedBudgetAfterWaitingOnRepairHeader() throws Exception {
        String id = begin();
        expenseScope(id, "LIMITED", "200.00");
        approve(id);
        String round = create(id, fixture.warehouse);
        money(id, round, "quotes", "150.00", "QUOTE");
        upload(id, round, fixture.warehouse, "FACTORY_ACK");
        expenseScope(id, "LIMITED", "100.00");
        var detail = privateDetail(fixture.warehouse, id);
        var body = movement(id, round, fixture.warehouse, false, "RESTORED");
        long predictedVersion = detail.path("version").asLong() + 1;
        try (var blocker = Objects.requireNonNull(db.getDataSource()).getConnection();
                var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var query =
                    blocker.prepareStatement(
                            "SELECT id FROM fams_repair_request WHERE id=? FOR UPDATE")) {
                query.setString(1, id);
                try (var held = query.executeQuery()) {
                    assertTrue(held.next());
                }
            }
            var request =
                    pool.submit(
                            () ->
                                    post(
                                            fixture.warehouse,
                                            path(id, "/rounds/" + round + "/dispatch"),
                                            predictedVersion,
                                            key(),
                                            body));
            boolean waiting = false;
            for (int attempt = 0; attempt < 200; attempt++) {
                int count =
                        Objects.requireNonNull(
                                db.queryForObject(
                                        "SELECT COUNT(*) FROM information_schema.processlist WHERE"
                                            + " ID<>CONNECTION_ID() AND DB=DATABASE() AND INFO LIKE"
                                            + " '%fams_repair_request%FOR UPDATE%'",
                                        Integer.class));
                if (count > 0) {
                    waiting = true;
                    break;
                }
                Thread.sleep(25);
            }
            assertTrue(
                    waiting,
                    "Owned HTTP request must actually wait on the repair header before fixture"
                            + " approval commits");
            // Controlled approval fixture, not an assertion that this is the real administrator
            // endpoint.
            try (var update =
                    blocker.prepareStatement(
                            "UPDATE fams_repair_factory_scope SET"
                                + " scope_status='APPROVED',decision_snapshot_json='{\"ownedConcurrencyFixtureOnly\":true}',decision_reason='Owned"
                                + " current approval fixture',decided_by=(SELECT decided_by FROM"
                                + " (SELECT decided_by FROM fams_repair_factory_scope WHERE"
                                + " repair_id=? AND scope_status=\'APPROVED\' ORDER BY id DESC"
                                + " LIMIT 1) old_scope),decided_at=NOW(3),version=version+1 WHERE"
                                + " id=?")) {
                update.setString(1, id);
                update.setString(2, detail.path("pendingScope").path("id").asText());
                assertEquals(1, update.executeUpdate());
            }
            try (var update =
                    blocker.prepareStatement(
                            "UPDATE fams_repair_request SET version=version+1 WHERE id=?")) {
                update.setString(1, id);
                assertEquals(1, update.executeUpdate());
            }
            blocker.commit();
            var rejected = request.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(409, rejected.statusCode());
            assertEquals(
                    "REPAIR_FACTORY_COST_SCOPE_REQUIRED",
                    JSON.readTree(rejected.body()).path("code").asText());
        }
        assertEquals("UNDER_REPAIR", lifecycle());
        assertEquals(
                "PENDING_DISPATCH",
                privateRound(fixture.warehouse, id, round).path("roundStatus").asText());
    }

    @Test
    void finiteDispatchRequiresQuotedOriginalWithinCurrentRepairWideCeiling() throws Exception {
        String id = begin();
        expenseScope(id, "LIMITED", "50.00");
        approve(id);
        String round = create(id, fixture.warehouse);
        upload(id, round, fixture.warehouse, "FACTORY_ACK");
        for (boolean excessive : List.of(false, true)) {
            if (excessive) {
                money(id, round, "quotes", "50.01", "QUOTE");
            }
            var detail = privateDetail(fixture.warehouse, id);
            assertEquals(
                    409,
                    post(
                                    fixture.warehouse,
                                    path(id, "/rounds/" + round + "/dispatch"),
                                    detail.path("version").asLong(),
                                    key(),
                                    movement(id, round, fixture.warehouse, false, "RESTORED"))
                            .statusCode());
            assertEquals("UNDER_REPAIR", lifecycle());
        }
        money(id, round, "quotes", "50.00", "QUOTE");
        dispatch(id, round, fixture.warehouse);
        assertEquals(
                "0.00",
                privateDetail(fixture.warehouse, id)
                        .path("costSummary")
                        .path("confirmedTotal")
                        .asText());
        assertEquals(2, privateRound(fixture.warehouse, id, round).path("quotes").size());
    }

    @Test
    void newFinancialFactsRequireEveryExplicitVersionAndFinalAmountConfirmation() throws Exception {
        String id = approved(), round = create(id, fixture.warehouse);
        var detail = privateDetail(fixture.warehouse, id);
        var input = guards(detail);
        input.put(
                "roundVersion",
                privateRound(fixture.warehouse, id, round).path("version").asLong());
        input.put("amount", "0.00");
        input.put("reason", "Explicit final original amount and every live version checked");
        input.put("finalAmountChecked", true);
        input.set("evidenceIds", JSON.createArrayNode());
        for (String missing :
                List.of(
                        "assetVersion",
                        "ownershipVersion",
                        "lockVersion",
                        "roundVersion",
                        "finalAmountChecked")) {
            var body = input.deepCopy();
            body.remove(missing);
            assertEquals(
                    400,
                    post(
                                    fixture.warehouse,
                                    path(id, "/rounds/" + round + "/cost"),
                                    detail.path("version").asLong(),
                                    key(),
                                    body)
                            .statusCode());
        }
        for (String bad : List.of("01.00", "1.001", "1e2", "-1.00")) {
            var body = input.deepCopy();
            body.put("amount", bad);
            assertEquals(
                    400,
                    post(
                                    fixture.warehouse,
                                    path(id, "/rounds/" + round + "/cost"),
                                    detail.path("version").asLong(),
                                    key(),
                                    body)
                            .statusCode());
        }
        var numericAmount = input.deepCopy();
        numericAmount.put("amount", 1);
        assertEquals(
                400,
                post(
                                fixture.warehouse,
                                path(id, "/rounds/" + round + "/cost"),
                                detail.path("version").asLong(),
                                key(),
                                numericAmount)
                        .statusCode());
        assertTrue(privateRound(fixture.warehouse, id, round).path("actualCost").isNull());
        assertEquals(
                0,
                db.queryForObject("SELECT COUNT(*) FROM fams_repair_factory_cost", Integer.class));
    }

    @Test
    void changedQuoteInvalidatesFrozenScopeBasisAndDispatchRetainsItsActualAuthorization()
            throws Exception {
        String id = approved(), round = create(id, fixture.warehouse);
        String creationScope = privateRound(fixture.warehouse, id, round).path("scopeId").asText();
        expenseScope(id, "LIMITED", "100.00");
        money(id, round, "quotes", "10.00", "QUOTE");
        var detail = privateDetail(fixture.admin, id);
        assertEquals(
                409,
                post(
                                fixture.admin,
                                path(id, "/scope-decision"),
                                detail.path("version").asLong(),
                                key(),
                                scopeDecision(detail, "APPROVE"))
                        .statusCode());
        ok(
                post(
                        fixture.admin,
                        path(id, "/scope-withdrawal"),
                        detail.path("version").asLong(),
                        key(),
                        scopeDecision(detail, "WITHDRAW")));
        expenseScope(id, "LIMITED", "100.00");
        approve(id);
        String dispatchScope =
                privateDetail(fixture.warehouse, id).path("approvedScope").path("id").asText();
        dispatch(id, round, fixture.warehouse);
        var physical = privateRound(fixture.warehouse, id, round);
        assertEquals(creationScope, physical.path("scopeId").asText());
        assertEquals(dispatchScope, physical.path("dispatchScopeId").asText());
        assertFalse(creationScope.equals(dispatchScope));
    }

    private void expenseScope(String id, String policy, String limit) throws Exception {
        var detail = privateDetail(fixture.warehouse, id);
        var body = guards(detail);
        body.put("reason", "Private repair-wide expense authority must cover all actual rounds");
        body.put("costPolicy", policy);
        if (limit != null) {
            body.put("costLimit", limit);
        }
        ok(
                post(
                        fixture.warehouse,
                        path(id, "/scopes"),
                        detail.path("version").asLong(),
                        key(),
                        body));
    }

    private void money(String id, String round, String operation, String amount, String purpose)
            throws Exception {
        String original = purpose == null ? null : upload(id, round, fixture.warehouse, purpose);
        var detail = privateDetail(fixture.warehouse, id);
        var body = guards(detail);
        body.put(
                "roundVersion",
                privateRound(fixture.warehouse, id, round).path("version").asLong());
        body.put("amount", amount);
        body.put("reason", "Private original and exact amount checked for this round");
        body.put("finalAmountChecked", true);
        var ids = JSON.createArrayNode();
        if (original != null) {
            ids.add(original);
        }
        body.set("evidenceIds", ids);
        String nonce = key();
        long version = detail.path("version").asLong();
        String url = path(id, "/rounds/" + round + "/" + operation);
        var first = ok(post(fixture.warehouse, url, version, nonce, body));
        assertEquals(first, ok(post(fixture.warehouse, url, version, nonce, body)));
        assertEquals(403, post(fixture.employee, url, version, nonce, body).statusCode());
        if ("cost".equals(operation)) {
            var current = privateDetail(fixture.warehouse, id);
            body.put(
                    "roundVersion",
                    privateRound(fixture.warehouse, id, round).path("version").asLong());
            assertEquals(
                    409,
                    post(fixture.warehouse, url, current.path("version").asLong(), key(), body)
                            .statusCode());
        }
    }

    private void paidReceipt(String id, String round, String amount, String condition)
            throws Exception {
        upload(id, round, fixture.warehouse, "RECEIPT");
        var row = privateDetail(fixture.warehouse, id);
        var body = movement(id, round, fixture.warehouse, true, condition);
        body.put("actualCost", amount);
        body.put(
                "treatmentResult",
                "Original physical identity and actual receipt checked against the selected"
                        + " evidence and recorded final cost");
        ok(
                post(
                        fixture.warehouse,
                        path(id, "/rounds/" + round + "/receive-original"),
                        row.path("version").asLong(),
                        key(),
                        body));
    }

    @Test
    void realCancelledRoundsAndTheirImmutableHistoryPageIndependently() throws Exception {
        String id = approved();
        for (int i = 0; i < 21; i++) {
            String round = create(id, fixture.warehouse);
            JsonNode before = privateDetail(fixture.warehouse, id);
            var input = guards(before);
            input.put("roundVersion", before.path("activeRound").path("version").asLong());
            input.put(
                    "reason",
                    "Supplier unavailable before any physical dispatch; keep original round");
            ok(
                    post(
                            fixture.warehouse,
                            path(id, "/rounds/" + round + "/cancel"),
                            before.path("version").asLong(),
                            key(),
                            input));
        }
        JsonNode first = ok(get(fixture.warehouse, path(id, "/rounds?page=1&pageSize=20")));
        JsonNode second = ok(get(fixture.warehouse, path(id, "/rounds?page=2&pageSize=20")));
        assertEquals(21, first.path("total").asInt());
        assertEquals(20, first.path("items").size());
        assertEquals(1, second.path("items").size());
        assertEquals(21, first.path("items").get(0).path("roundNumber").asInt());
        assertEquals(1, second.path("items").get(0).path("roundNumber").asInt());
        assertEquals("CANCELLED", second.path("items").get(0).path("roundStatus").asText());
        var seen = new java.util.HashSet<String>();
        for (int page = 1; page <= 3; page++) {
            JsonNode history =
                    ok(get(fixture.warehouse, path(id, "/history?page=" + page + "&pageSize=20")));
            assertEquals(44, history.path("total").asInt());
            assertEquals(page == 3 ? 4 : 20, history.path("items").size());
            for (JsonNode event : history.path("items")) {
                assertTrue(seen.add(event.path("id").asText()));
            }
        }
        assertEquals(44, seen.size());
        assertEquals(
                0,
                privateDetail(fixture.warehouse, id)
                        .path("summary")
                        .path("actualDispatchCount")
                        .asInt());
        assertEquals(1, activeLocks());
        assertEquals("UNDER_REPAIR", lifecycle());
    }

    @Test
    void twoActualRoundsRetainOneLockAndOriginalsUntilSeparateRealRepairClosure() throws Exception {
        var original = originalFacts();
        String id = approved();
        JsonNode approval = privateDetail(fixture.warehouse, id).path("approvedScope");
        String cancelled = create(id, fixture.warehouse);
        JsonNode before = privateDetail(fixture.warehouse, id);
        var cancel = guards(before);
        cancel.put("roundVersion", before.path("activeRound").path("version").asLong());
        cancel.put("reason", "No physical dispatch occurred; new supplier needed");
        ok(
                post(
                        fixture.warehouse,
                        path(id, "/rounds/" + cancelled + "/cancel"),
                        before.path("version").asLong(),
                        key(),
                        cancel));
        assertEquals(
                0,
                privateDetail(fixture.warehouse, id)
                        .path("summary")
                        .path("actualDispatchCount")
                        .asInt());
        String first = create(id, fixture.warehouse);
        dispatch(id, first, fixture.warehouse);
        assertEquals("FACTORY_REPAIR", lifecycle());
        JsonNode root = root(fixture.warehouse, id);
        assertFalse(root.path("actions").path("closeInbound").asBoolean());
        assertEquals(
                409,
                post(
                                fixture.warehouse,
                                "/" + id + "/close-inbound",
                                root.path("version").asLong(),
                                key(),
                                inbound(root, now()))
                        .statusCode());
        receive(id, first, fixture.warehouse, "STILL_FAULTY");
        JsonNode frozenFirst = privateRound(fixture.warehouse, id, first);
        assertEquals("UNDER_REPAIR", lifecycle());
        assertEquals(1, activeLocks());
        assertEquals("WAREHOUSE_PROCESSING", root(fixture.employee, id).path("status").asText());
        String second = create(id, fixture.warehouse);
        dispatch(id, second, fixture.warehouse);
        receive(id, second, fixture.warehouse, "RESTORED");
        assertEquals(frozenFirst, privateRound(fixture.warehouse, id, first));
        assertEquals(approval, privateDetail(fixture.warehouse, id).path("approvedScope"));
        assertEquals(
                2,
                privateDetail(fixture.warehouse, id)
                        .path("summary")
                        .path("actualDispatchCount")
                        .asInt());
        assertEquals(original, originalFacts());
        assertEquals(1, activeLocks());
        root = root(fixture.warehouse, id);
        assertEquals(
                400,
                post(
                                fixture.warehouse,
                                "/" + id + "/close-inbound",
                                root.path("version").asLong(),
                                key(),
                                inbound(root, Instant.now().minusSeconds(60).toString()))
                        .statusCode());
        ok(
                post(
                        fixture.warehouse,
                        "/" + id + "/close-inbound",
                        root.path("version").asLong(),
                        key(),
                        inbound(root, now())));
        assertEquals("COMPLETED", root(fixture.employee, id).path("status").asText());
        assertEquals("IN_STOCK", lifecycle());
        assertEquals(0, activeLocks());
        assertEquals(
                0,
                db.queryForObject(
                                "SELECT SUM(actual_cost) FROM fams_repair_factory_round WHERE"
                                        + " repair_id=?",
                                java.math.BigDecimal.class,
                                id)
                        .signum());
    }

    @Test
    void privateOriginalsAndCurrentWarehouseSurviveControlledInTransitReassignment()
            throws Exception {
        String id = approved(), round = create(id, fixture.warehouse);
        String object = upload(id, round, fixture.warehouse, "FACTORY_ACK");
        String token =
                ok(get(
                                fixture.warehouse,
                                path(id, "/rounds/" + round + "/evidence/" + object + "/grant")))
                        .path("token")
                        .asText();
        assertArrayEquals(PNG, content(fixture.warehouse, id, round, object, token).body());
        for (Session denied :
                List.of(fixture.employee, fixture.otherWarehouse, fixture.system, login(9))) {
            assertEquals(403, get(denied, path(id, "")).statusCode());
            assertEquals(
                    403,
                    get(denied, path(id, "/rounds/" + round + "/evidence/" + object + "/grant"))
                            .statusCode());
        }
        var publicRoot = root(fixture.employee, id);
        assertTrue(publicRoot.has("factorySummary"));
        assertFalse(publicRoot.toString().contains("Private factory contact"));
        assertFalse(publicRoot.toString().contains("13900001234"));
        assertFalse(publicRoot.toString().contains("providerName"));
        assertFalse(publicRoot.toString().contains("actualCost"));
        dispatch(id, round, fixture.warehouse);
        var inspection = ok(get(fixture.admin, "/" + id + "/reassignment"));
        assertEquals(
                "DISPATCHED",
                inspection
                        .path("snapshot")
                        .path("factorySummary")
                        .path("round")
                        .path("roundStatus")
                        .asText());
        var input = JSON.createObjectNode();
        input.put("processorAccountId", "951004");
        input.set("snapshot", inspection.path("snapshot"));
        input.put("unsynchronizedResultsChecked", true);
        input.put("reason", "Current repair handler is being replaced");
        input.put(
                "evidence",
                "Actual warehouse handover checked; original device is still at named"
                        + " manufacturer");
        ok(
                post(
                        fixture.admin,
                        "/" + id + "/reassignment",
                        root(fixture.admin, id).path("version").asLong(),
                        key(),
                        input));
        assertEquals(403, content(fixture.warehouse, id, round, object, token).statusCode());
        assertEquals(403, get(fixture.warehouse, path(id, "")).statusCode());
        assertEquals(1, activeLocks());
        receive(id, round, fixture.otherWarehouse, "RESTORED");
        assertEquals("UNDER_REPAIR", lifecycle());
        assertEquals("951004", root(fixture.admin, id).path("processorAccountId").asText());
    }

    @Test
    void scopeRejectionWithdrawalAndChangedOwnershipNeverRewriteInitialApproval() throws Exception {
        String id = begin();
        String original =
                db.queryForObject(
                        "SELECT approval_snapshot_json FROM fams_repair_request WHERE id=?",
                        String.class,
                        id);
        requestScope(id, fixture.warehouse);
        JsonNode detail = privateDetail(fixture.admin, id);
        var decision = scopeDecision(detail, "REJECT");
        ok(
                post(
                        fixture.admin,
                        path(id, "/scope-decision"),
                        detail.path("version").asLong(),
                        key(),
                        decision));
        requestScope(id, fixture.warehouse);
        detail = privateDetail(fixture.warehouse, id);
        ok(
                post(
                        fixture.warehouse,
                        path(id, "/scope-withdrawal"),
                        detail.path("version").asLong(),
                        key(),
                        scopeDecision(detail, "WITHDRAW")));
        requestScope(id, fixture.warehouse);
        var inspection = ok(get(fixture.admin, "/" + id + "/reassignment"));
        var reassign = JSON.createObjectNode();
        reassign.put("processorAccountId", "951004");
        reassign.set("snapshot", inspection.path("snapshot"));
        reassign.put("unsynchronizedResultsChecked", true);
        reassign.put("reason", "Transfer pending factory followup");
        reassign.put(
                "evidence",
                "Actual repair file and pending scope handed over to new warehouse owner");
        ok(
                post(
                        fixture.admin,
                        "/" + id + "/reassignment",
                        root(fixture.admin, id).path("version").asLong(),
                        key(),
                        reassign));
        detail = privateDetail(fixture.admin, id);
        assertEquals(
                409,
                post(
                                fixture.admin,
                                path(id, "/scope-decision"),
                                detail.path("version").asLong(),
                                key(),
                                scopeDecision(detail, "APPROVE"))
                        .statusCode());
        detail = privateDetail(fixture.otherWarehouse, id);
        ok(
                post(
                        fixture.otherWarehouse,
                        path(id, "/scope-withdrawal"),
                        detail.path("version").asLong(),
                        key(),
                        scopeDecision(detail, "WITHDRAW")));
        requestScope(id, fixture.otherWarehouse);
        approve(id);
        assertEquals(
                original,
                db.queryForObject(
                        "SELECT approval_snapshot_json FROM fams_repair_request WHERE id=?",
                        String.class,
                        id));
        assertEquals(
                4,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_factory_scope WHERE repair_id=?",
                        Integer.class,
                        id));
    }

    @Test
    void concurrentScopeAdministratorsAndRoundCreationHaveOneWinnerAndAuditedLoser()
            throws Exception {
        String id = begin();
        requestScope(id, fixture.warehouse);
        JsonNode detail = privateDetail(fixture.admin, id);
        var decision = scopeDecision(detail, "APPROVE");
        Session otherAdmin = login(10);
        long version = detail.path("version").asLong();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first =
                    pool.submit(
                            () ->
                                    post(
                                            fixture.admin,
                                            path(id, "/scope-decision"),
                                            version,
                                            key(),
                                            decision));
            var second =
                    pool.submit(
                            () ->
                                    post(
                                            otherAdmin,
                                            path(id, "/scope-decision"),
                                            version,
                                            key(),
                                            decision));
            var results = List.of(first.get(), second.get());
            assertEquals(
                    List.of(200, 409),
                    results.stream().map(HttpResponse::statusCode).sorted().toList());
            assertTrue(
                    JSON.readTree(
                                    results.stream()
                                            .filter(x -> x.statusCode() == 409)
                                            .findFirst()
                                            .orElseThrow()
                                            .body())
                            .path("fieldErrors")
                            .has("processedBy"));
            JsonNode ready = privateDetail(fixture.warehouse, id);
            ObjectNode input = roundCreation(ready);
            long rootVersion = ready.path("version").asLong();
            var one =
                    pool.submit(
                            () ->
                                    post(
                                            fixture.warehouse,
                                            path(id, "/rounds"),
                                            rootVersion,
                                            key(),
                                            input));
            var two =
                    pool.submit(
                            () ->
                                    post(
                                            fixture.warehouse,
                                            path(id, "/rounds"),
                                            rootVersion,
                                            key(),
                                            input));
            assertEquals(
                    List.of(200, 409),
                    List.of(one.get().statusCode(), two.get().statusCode()).stream()
                            .sorted()
                            .toList());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_factory_round WHERE repair_id=?",
                        Integer.class,
                        id));
        assertEquals(1, activeLocks());
    }

    @Test
    void auditFailureRollsBackDispatchAndReceiptAcrossAssetRoundTodoAndOriginalReply()
            throws Exception {
        String id = approved(), round = create(id, fixture.warehouse);
        upload(id, round, fixture.warehouse, "FACTORY_ACK");
        for (boolean receiving : List.of(false, true)) {
            if (receiving) {
                upload(id, round, fixture.warehouse, "RECEIPT");
            }
            JsonNode detail = privateDetail(fixture.warehouse, id);
            ObjectNode body = movement(id, round, fixture.warehouse, receiving, "RESTORED");
            String route =
                    path(id, "/rounds/" + round + (receiving ? "/receive-original" : "/dispatch"));
            String originalKey = key();
            var before = db.queryForMap("SELECT * FROM fams_asset WHERE id=951020");
            String action = receiving ? "FACTORY_ROUND_RECEIVED" : "FACTORY_ROUND_DISPATCHED";
            db.execute(
                    "CREATE TRIGGER owned_factory_audit_failure BEFORE INSERT ON fams_audit_event"
                            + " FOR EACH ROW BEGIN IF NEW.action='"
                            + action
                            + "' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned factory late"
                            + " audit failure'; END IF; END");
            assertEquals(
                    500,
                    post(
                                    fixture.warehouse,
                                    route,
                                    detail.path("version").asLong(),
                                    originalKey,
                                    body)
                            .statusCode());
            assertEquals(before, db.queryForMap("SELECT * FROM fams_asset WHERE id=951020"));
            assertEquals(detail, privateDetail(fixture.warehouse, id));
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_repair_receipt WHERE request_id=?",
                            Integer.class,
                            originalKey));
            db.execute("DROP TRIGGER owned_factory_audit_failure");
            var saved =
                    ok(
                            post(
                                    fixture.warehouse,
                                    route,
                                    detail.path("version").asLong(),
                                    originalKey,
                                    body));
            assertEquals(
                    saved,
                    ok(
                            post(
                                    fixture.warehouse,
                                    route,
                                    detail.path("version").asLong(),
                                    originalKey,
                                    body)));
            assertEquals(1, activeLocks());
        }
    }

    @Test
    void scannerFailureAndDuplicateUploadDoNotCreateFactsOrDuplicateStoredOriginals()
            throws Exception {
        String id = approved(), round = create(id, fixture.warehouse);
        JsonNode detail = privateDetail(fixture.warehouse, id);
        String originalKey = key();
        SCAN.set(MalwareScanner.Result.UNAVAILABLE);
        assertEquals(
                503,
                uploadResponse(id, round, fixture.warehouse, "FACTORY_ACK", detail, originalKey)
                        .statusCode());
        assertEquals(detail, privateDetail(fixture.warehouse, id));
        SCAN.set(MalwareScanner.Result.INFECTED);
        assertEquals(
                422,
                uploadResponse(id, round, fixture.warehouse, "FACTORY_ACK", detail, originalKey)
                        .statusCode());
        assertEquals(detail, privateDetail(fixture.warehouse, id));
        SCAN.set(MalwareScanner.Result.CLEAN);
        var reply =
                ok(
                        uploadResponse(
                                id, round, fixture.warehouse, "FACTORY_ACK", detail, originalKey));
        assertEquals(
                reply,
                ok(
                        uploadResponse(
                                id, round, fixture.warehouse, "FACTORY_ACK", detail, originalKey)));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_factory_evidence WHERE round_id=?",
                        Integer.class,
                        round));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_attachment WHERE business_type='REPAIR_FACTORY'"
                                + " AND business_id=?",
                        Integer.class,
                        round));
        dispatch(id, round, fixture.warehouse);
        receive(id, round, fixture.warehouse, "RESTORED");
        assertEquals(
                reply,
                ok(
                        uploadResponse(
                                id, round, fixture.warehouse, "FACTORY_ACK", detail, originalKey)));
    }

    @Test
    void originalIdentityActualTimeScopeFeeAndLocationChecksPrecedePhysicalWrites()
            throws Exception {
        String id = approved(), round = create(id, fixture.warehouse);
        upload(id, round, fixture.warehouse, "FACTORY_ACK");
        JsonNode detail = privateDetail(fixture.warehouse, id);
        ObjectNode body = movement(id, round, fixture.warehouse, false, "RESTORED");
        for (Map.Entry<String, Object> invalid :
                Map.<String, Object>of(
                                "observedSerial",
                                "NEW-REPLACEMENT-SERIAL",
                                "assetVersion",
                                999L,
                                "ownershipVersion",
                                999L,
                                "lockVersion",
                                999L,
                                "roundVersion",
                                999L)
                        .entrySet()) {
            var wrong = body.deepCopy();
            wrong.set(invalid.getKey(), JSON.valueToTree(invalid.getValue()));
            assertEquals(
                    409,
                    post(
                                    fixture.warehouse,
                                    path(id, "/rounds/" + round + "/dispatch"),
                                    detail.path("version").asLong(),
                                    key(),
                                    wrong)
                            .statusCode());
        }
        var future = body.deepCopy();
        future.put(
                "occurredAt",
                Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MILLIS).toString());
        assertEquals(
                400,
                post(
                                fixture.warehouse,
                                path(id, "/rounds/" + round + "/dispatch"),
                                detail.path("version").asLong(),
                                key(),
                                future)
                        .statusCode());
        assertEquals("UNDER_REPAIR", lifecycle());
        dispatch(id, round, fixture.warehouse);
        upload(id, round, fixture.warehouse, "RECEIPT");
        detail = privateDetail(fixture.warehouse, id);
        body = movement(id, round, fixture.warehouse, true, "RESTORED");
        var fee = body.deepCopy();
        fee.put("actualCost", "1.00");
        assertEquals(
                409,
                post(
                                fixture.warehouse,
                                path(id, "/rounds/" + round + "/receive-original"),
                                detail.path("version").asLong(),
                                key(),
                                fee)
                        .statusCode());
        var unchecked = body.deepCopy();
        unchecked.put("originalPhysicalIdentityChecked", false);
        assertEquals(
                400,
                post(
                                fixture.warehouse,
                                path(id, "/rounds/" + round + "/receive-original"),
                                detail.path("version").asLong(),
                                key(),
                                unchecked)
                        .statusCode());
        var location = body.deepCopy();
        location.put("locationVersion", 999);
        assertEquals(
                409,
                post(
                                fixture.warehouse,
                                path(id, "/rounds/" + round + "/receive-original"),
                                detail.path("version").asLong(),
                                key(),
                                location)
                        .statusCode());
        location.put("actualLocationId", "951000");
        location.put("locationVersion", 0);
        db.update("UPDATE fams_warehouse SET status='DISABLED' WHERE id=951000");
        assertEquals(
                409,
                post(
                                fixture.warehouse,
                                path(id, "/rounds/" + round + "/receive-original"),
                                detail.path("version").asLong(),
                                key(),
                                location)
                        .statusCode());
        assertEquals("FACTORY_REPAIR", lifecycle());
        assertEquals(1, activeLocks());
    }

    @Test
    void permanentReplyStillRequiresCurrentRoleAndUnchangedOriginalBody() throws Exception {
        String id = approved();
        JsonNode detail = privateDetail(fixture.warehouse, id);
        ObjectNode body = roundCreation(detail);
        String originalKey = key();
        long version = detail.path("version").asLong();
        var original = ok(post(fixture.warehouse, path(id, "/rounds"), version, originalKey, body));
        assertEquals(
                original,
                ok(post(fixture.warehouse, path(id, "/rounds"), version, originalKey, body)));
        var changed = body.deepCopy();
        changed.put("fault", "Different original fault must not reuse the key");
        assertEquals(
                409,
                post(fixture.warehouse, path(id, "/rounds"), version, originalKey, changed)
                        .statusCode());
        db.update(
                "DELETE FROM fams_account_role WHERE account_id=951003 AND"
                        + " role_code='WAREHOUSE_OPERATOR'");
        assertEquals(
                403,
                post(fixture.warehouse, path(id, "/rounds"), version, originalKey, body)
                        .statusCode());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_factory_round WHERE repair_id=?",
                        Integer.class,
                        id));
    }

    @Test
    void originalWithoutRecordedSerialCanReallyReturnWithoutInventingASerial() throws Exception {
        db.update(
                "UPDATE fams_asset SET serial_number=NULL,serial_number_normalized=NULL WHERE"
                        + " id=951020");
        String id = approved(), round = create(id, fixture.warehouse);
        assertTrue(
                privateRound(fixture.warehouse, id, round)
                        .path("originalIdentity")
                        .path("serialNumber")
                        .isNull());
        for (boolean receiving : List.of(false, true)) {
            upload(id, round, fixture.warehouse, receiving ? "RECEIPT" : "FACTORY_ACK");
            var input = movement(id, round, fixture.warehouse, receiving, "RESTORED");
            input.putNull("observedSerial");
            ok(
                    post(
                            fixture.warehouse,
                            path(
                                    id,
                                    "/rounds/"
                                            + round
                                            + (receiving ? "/receive-original" : "/dispatch")),
                            privateDetail(fixture.warehouse, id).path("version").asLong(),
                            key(),
                            input));
        }
        assertEquals("UNDER_REPAIR", lifecycle());
        assertEquals(1, activeLocks());
        assertTrue(
                privateRound(fixture.warehouse, id, round)
                        .path("originalIdentity")
                        .path("serialNumber")
                        .isNull());
        assertEquals(
                1,
                privateDetail(fixture.warehouse, id)
                        .path("summary")
                        .path("actualDispatchCount")
                        .asInt());
    }

    @Test
    void pendingDispatchCanBeControlledTransferredAndCancelledOnlyByTheNewOwner() throws Exception {
        String id = approved(), round = create(id, fixture.warehouse);
        JsonNode original = privateRound(fixture.warehouse, id, round);
        JsonNode inspection = ok(get(fixture.admin, "/" + id + "/reassignment"));
        assertEquals(
                "PENDING_DISPATCH",
                inspection
                        .path("snapshot")
                        .path("factorySummary")
                        .path("round")
                        .path("roundStatus")
                        .asText());
        var transfer = JSON.createObjectNode();
        transfer.put("processorAccountId", "951004");
        transfer.set("snapshot", inspection.path("snapshot"));
        transfer.put("unsynchronizedResultsChecked", true);
        transfer.put("reason", "Actual warehouse handover before physical factory dispatch");
        transfer.put(
                "evidence",
                "The new responsible warehouse checked the original pending round and unchanged"
                        + " device");
        ok(
                post(
                        fixture.admin,
                        "/" + id + "/reassignment",
                        root(fixture.admin, id).path("version").asLong(),
                        key(),
                        transfer));
        assertEquals(403, get(fixture.warehouse, path(id, "")).statusCode());
        var current = privateDetail(fixture.otherWarehouse, id);
        var input = guards(current);
        input.put("roundVersion", current.path("activeRound").path("version").asLong());
        input.put("reason", "Factory dispatch no longer needed; no device was ever sent");
        assertEquals(
                403,
                post(
                                fixture.warehouse,
                                path(id, "/rounds/" + round + "/cancel"),
                                current.path("version").asLong(),
                                key(),
                                input)
                        .statusCode());
        ok(
                post(
                        fixture.otherWarehouse,
                        path(id, "/rounds/" + round + "/cancel"),
                        current.path("version").asLong(),
                        key(),
                        input));
        JsonNode cancelled = privateRound(fixture.otherWarehouse, id, round);
        assertEquals(original.path("originalIdentity"), cancelled.path("originalIdentity"));
        assertEquals(original.path("providerName"), cancelled.path("providerName"));
        assertEquals("CANCELLED", cancelled.path("roundStatus").asText());
        assertEquals(
                0,
                privateDetail(fixture.otherWarehouse, id)
                        .path("summary")
                        .path("actualDispatchCount")
                        .asInt());
        assertEquals("UNDER_REPAIR", lifecycle());
        assertEquals(1, activeLocks());
    }

    @Test
    void factoryScopeCannotBypassActualAcceptanceOrDuplicateAnExistingScope() throws Exception {
        var submission = JSON.createObjectNode();
        submission.put("assetId", "951020");
        submission.put("assetVersion", 0);
        submission.put("fault", "Actual fault reported before any physical acceptance");
        submission.put("impact", "HIGH");
        submission.put(
                "occurredAt",
                Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS).toString());
        String id = ok(post(fixture.employee, "", 0, key(), submission)).path("id").asText();
        var decision = JSON.createObjectNode();
        decision.put("result", "APPROVE");
        decision.put("allowedMethod", "INTERNAL_NO_COST");
        decision.put("reason", "Initial internal no-cost repair scope only");
        ok(post(fixture.admin, "/" + id + "/decision", 0, key(), decision));
        ok(post(fixture.warehouse, "/" + id + "/claim", 1, key(), null));
        JsonNode row = privateDetail(fixture.warehouse, id);
        var request = guards(row);
        request.put("reason", "Request manufacturer repair before physical acceptance");
        assertEquals(
                409,
                post(
                                fixture.warehouse,
                                path(id, "/scopes"),
                                row.path("version").asLong(),
                                key(),
                                request)
                        .statusCode());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_factory_scope WHERE repair_id=?",
                        Integer.class,
                        id));
        var away = guards(row);
        away.put("observedCode", "REP-ASSET-951020");
        away.put("actualLocationId", "951001");
        away.put("locationVersion", 0);
        away.put("occurredAt", now());
        away.put("evidence", "Original device actually accepted by the current repair warehouse");
        ok(
                post(
                        fixture.warehouse,
                        "/" + id + "/accept-away",
                        row.path("version").asLong(),
                        key(),
                        away));
        requestScope(id, fixture.warehouse);
        row = privateDetail(fixture.warehouse, id);
        request = guards(row);
        request.put("reason", "A second pending scope must not overwrite the first");
        assertEquals(
                409,
                post(
                                fixture.warehouse,
                                path(id, "/scopes"),
                                row.path("version").asLong(),
                                key(),
                                request)
                        .statusCode());
        JsonNode root = root(fixture.warehouse, id);
        assertEquals(
                409,
                post(
                                fixture.warehouse,
                                "/" + id + "/close-inbound",
                                root.path("version").asLong(),
                                key(),
                                inbound(root, now()))
                        .statusCode());
        approve(id);
        row = privateDetail(fixture.warehouse, id);
        request = guards(row);
        request.put("reason", "A second approved scope must not replace the approved original");
        assertEquals(
                409,
                post(
                                fixture.warehouse,
                                path(id, "/scopes"),
                                row.path("version").asLong(),
                                key(),
                                request)
                        .statusCode());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_factory_scope WHERE repair_id=?",
                        Integer.class,
                        id));
        assertEquals(1, activeLocks());
    }

    @Test
    void expectedReturnCalendarDateRemainsUnshiftedInAllFiveResponseProjections() throws Exception {
        String id = approved();
        JsonNode before = privateDetail(fixture.warehouse, id);
        var input = roundCreation(before);
        String expected = input.path("expectedReturnDate").asText();
        JsonNode created =
                ok(
                        post(
                                fixture.warehouse,
                                path(id, "/rounds"),
                                before.path("version").asLong(),
                                key(),
                                input));
        String round = created.path("round").path("id").asText();
        assertEquals(expected, created.path("round").path("expectedReturnDate").asText());
        assertEquals(
                expected,
                privateDetail(fixture.warehouse, id)
                        .path("activeRound")
                        .path("expectedReturnDate")
                        .asText());
        assertEquals(
                expected,
                privateRound(fixture.warehouse, id, round).path("expectedReturnDate").asText());
        assertEquals(
                expected,
                ok(get(fixture.warehouse, path(id, "/rounds?page=1&pageSize=20")))
                        .path("items")
                        .get(0)
                        .path("expectedReturnDate")
                        .asText());
        assertEquals(
                expected,
                root(fixture.employee, id)
                        .path("factorySummary")
                        .path("round")
                        .path("expectedReturnDate")
                        .asText());
    }

    @Test
    void nativeMultipartHintsPreserveChosenOriginalAndCannotOverrideRealBytes() throws Exception {
        String id = approved();
        String round = create(id, fixture.warehouse);
        JsonNode detail = privateDetail(fixture.warehouse, id);
        var hints = Map.of("originalFilename", "厂家接收原件.png", "declaredContentType", "image/png");
        for (var invalid :
                List.of(
                        Map.of("originalFilename", "incomplete.png"),
                        Map.of(
                                "originalFilename",
                                "../escape.png",
                                "declaredContentType",
                                "image/png"))) {
            assertEquals(
                    422,
                    nativeMetadataUpload(
                                    id, round, detail, key(), invalid, "application/octet-stream")
                            .statusCode());
        }
        var wrongBytes =
                nativeMetadataUpload(
                        id,
                        round,
                        detail,
                        key(),
                        Map.of(
                                "originalFilename",
                                "not-the-bytes.jpg",
                                "declaredContentType",
                                "image/jpeg"),
                        "application/octet-stream");
        assertEquals(400, wrongBytes.statusCode());
        assertEquals(
                "REPAIR_PHOTO_OR_INPUT_INVALID",
                JSON.readTree(wrongBytes.body()).path("code").asText());
        assertEquals(
                422,
                nativeMetadataUpload(id, round, detail, key(), hints, "image/jpeg").statusCode());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_factory_evidence", Integer.class));
        assertEquals(detail.path("version"), privateDetail(fixture.warehouse, id).path("version"));
        String key = key();
        JsonNode reply =
                ok(nativeMetadataUpload(id, round, detail, key, hints, "application/octet-stream"));
        assertEquals(
                reply,
                ok(
                        nativeMetadataUpload(
                                id, round, detail, key, hints, "application/octet-stream")));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_factory_evidence", Integer.class));
        var file = privateRound(fixture.warehouse, id, round).path("files").get(0);
        assertEquals("厂家接收原件.png", file.path("filename").asText());
        assertEquals("image/png", file.path("contentType").asText());
        assertEquals(PNG.length, file.path("sizeBytes").asInt());
        String object = reply.path("objectId").asText();
        var grant =
                ok(
                        get(
                                fixture.warehouse,
                                path(id, "/rounds/" + round + "/evidence/" + object + "/grant")));
        assertArrayEquals(
                PNG,
                content(fixture.warehouse, id, round, object, grant.path("token").asText()).body());
        assertEquals(
                409,
                nativeMetadataUpload(
                                id,
                                round,
                                detail,
                                key,
                                Map.of(
                                        "originalFilename",
                                        "different.png",
                                        "declaredContentType",
                                        "image/png"),
                                "application/octet-stream")
                        .statusCode());
    }

    private HttpResponse<String> nativeMetadataUpload(
            String id,
            String round,
            JsonNode detail,
            String key,
            Map<String, String> hints,
            String partMime)
            throws Exception {
        String boundary = "native-factory-" + UUID.randomUUID();
        var bytes = new java.io.ByteArrayOutputStream();
        var fields = new LinkedHashMap<>(hints);
        fields.put("purpose", "FACTORY_ACK");
        fields.put("roundVersion", detail.path("activeRound").path("version").asText());
        for (String field : List.of("assetVersion", "ownershipVersion", "lockVersion")) {
            fields.put(field, detail.path(field).asText());
        }
        for (var entry : fields.entrySet()) {
            bytes.write(
                    ("--"
                                    + boundary
                                    + "\r\nContent-Disposition: form-data; name=\""
                                    + entry.getKey()
                                    + "\"\r\n\r\n"
                                    + entry.getValue()
                                    + "\r\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        bytes.write(
                ("--"
                                + boundary
                                + "\r\n"
                                + "Content-Disposition: form-data; name=\"file\";"
                                + " filename=\"wx-opaque-tmp\"\r\n"
                                + "Content-Type: "
                                + partMime
                                + "\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        bytes.write(PNG);
        bytes.write(
                ("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return http.send(
                HttpRequest.newBuilder(uri(path(id, "/rounds/" + round + "/evidence")))
                        .header("Cookie", fixture.warehouse.cookie())
                        .header("X-CSRF-Token", fixture.warehouse.csrf())
                        .header("Idempotency-Key", key)
                        .header("If-Match-Version", detail.path("version").asText())
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void partsObservationRetainsOriginalCardAndAppendsPrivateBeforeAfterWithPermanentReplay()
            throws Exception {
        String id = approved(), round = create(id, fixture.warehouse);
        dispatch(id, round, fixture.warehouse);
        receive(id, round, fixture.warehouse, "RESTORED");
        uploadPartsOriginal(id, round);
        var asset = new LinkedHashMap<>(db.queryForMap("SELECT * FROM fams_asset WHERE id=951020"));
        var frozenRound =
                new LinkedHashMap<>(
                        db.queryForMap(
                                "SELECT * FROM fams_repair_factory_round WHERE id=?", round));
        var lock =
                db.queryForMap(
                        "SELECT * FROM fams_asset_business_lock WHERE asset_id=951020 AND"
                                + " released_at IS NULL");
        var body = partsBody(id, round, "PRIVATE-PART-OLD", "PRIVATE-PART-NEW");
        long version = privateDetail(fixture.warehouse, id).path("version").asLong();
        String nonce = key(), route = path(id, "/rounds/" + round + "/parts");
        var saved = ok(post(fixture.warehouse, route, version, nonce, body));
        assertEquals(saved, ok(post(fixture.warehouse, route, version, nonce, body)));
        var currentAsset =
                new LinkedHashMap<>(db.queryForMap("SELECT * FROM fams_asset WHERE id=951020"));
        for (String field : List.of("version", "updated_by", "updated_at")) {
            asset.remove(field);
            currentAsset.remove(field);
        }
        assertEquals(asset, currentAsset);
        assertEquals(
                lock,
                db.queryForMap(
                        "SELECT * FROM fams_asset_business_lock WHERE asset_id=951020 AND"
                                + " released_at IS NULL"));
        var currentRound =
                new LinkedHashMap<>(
                        db.queryForMap(
                                "SELECT * FROM fams_repair_factory_round WHERE id=?", round));
        assertEquals(
                ((Number) frozenRound.remove("version")).longValue() + 1,
                ((Number) currentRound.remove("version")).longValue());
        assertEquals(frozenRound, currentRound);
        var page = ok(get(fixture.admin, route));
        assertEquals(1, page.path("total").asInt());
        assertEquals("PRIVATE-PART-OLD", page.path("items").get(0).path("beforeInfo").asText());
        assertEquals("PRIVATE-PART-NEW", page.path("items").get(0).path("afterInfo").asText());
        assertFalse(page.path("canRecord").asBoolean());
        assertTrue(ok(get(fixture.warehouse, route)).path("canRecord").asBoolean());
        assertEquals(403, get(fixture.employee, route).statusCode());
        assertEquals(403, get(fixture.otherWarehouse, route).statusCode());
        assertFalse(root(fixture.employee, id).toString().contains("PRIVATE-PART"));
        assertFalse(
                db.queryForList(
                                "SELECT summary_json FROM fams_asset_timeline_event WHERE"
                                        + " asset_id=951020")
                        .toString()
                        .contains("PRIVATE-PART"));
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset_timeline_event WHERE asset_id=951020 AND"
                                + " event_type='REPAIR_FACTORY_PARTS_OBSERVED'",
                        Long.class));
        assertEquals("UNDER_REPAIR", lifecycle());
        assertEquals(1, activeLocks());
        var root = root(fixture.warehouse, id);
        ok(
                post(
                        fixture.warehouse,
                        "/" + id + "/close-inbound",
                        root.path("version").asLong(),
                        key(),
                        inbound(root, now())));
        assertEquals("COMPLETED", root(fixture.employee, id).path("status").asText());
        assertEquals(0, activeLocks());
        assertEquals(1, ok(get(fixture.admin, route)).path("total").asInt());
    }

    @Test
    void partsObservationLateAuditFailureRollsBackOwnerEventFactAndOriginalReceipt()
            throws Exception {
        String id = approved(), round = create(id, fixture.warehouse);
        dispatch(id, round, fixture.warehouse);
        receive(id, round, fixture.warehouse, "RESTORED");
        uploadPartsOriginal(id, round);
        var body = partsBody(id, round, "OLD-PRIVATE-CPU", "NEW-PRIVATE-CPU");
        var before = partsMutationFacts(id, round);
        long version = privateDetail(fixture.warehouse, id).path("version").asLong();
        String nonce = key(), route = path(id, "/rounds/" + round + "/parts");
        db.execute(
                "CREATE TRIGGER owned_factory_audit_failure BEFORE INSERT ON fams_audit_event FOR"
                    + " EACH ROW BEGIN IF NEW.action='FACTORY_PARTS_OBSERVED' THEN SIGNAL SQLSTATE"
                    + " '45000' SET MESSAGE_TEXT='owned parts late audit failure'; END IF; END");
        assertEquals(500, post(fixture.warehouse, route, version, nonce, body).statusCode());
        assertEquals(before, partsMutationFacts(id, round));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_receipt WHERE request_id=?",
                        Integer.class,
                        nonce));
        db.execute("DROP TRIGGER owned_factory_audit_failure");
        var saved = ok(post(fixture.warehouse, route, version, nonce, body));
        assertEquals(saved, ok(post(fixture.warehouse, route, version, nonce, body)));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_factory_parts_change", Integer.class));
    }

    @Test
    void partsObservationRequiresLatestPhysicalCustodyExactOriginalAndCurrentTechnicalBasis()
            throws Exception {
        String id = approved(), round = create(id, fixture.warehouse);
        var pending = privateDetail(fixture.warehouse, id);
        assertEquals(
                409,
                uploadResponse(id, round, fixture.warehouse, "PARTS_CHANGE", pending, key())
                        .statusCode());
        dispatch(id, round, fixture.warehouse);
        assertFalse(
                ok(get(fixture.warehouse, path(id, "/rounds/" + round + "/parts")))
                        .path("canRecord")
                        .asBoolean());
        receive(id, round, fixture.warehouse, "RESTORED");
        uploadPartsOriginal(id, round);
        String route = path(id, "/rounds/" + round + "/parts");
        var body = partsBody(id, round, "OLD-PRIVATE-DISK", "NEW-PRIVATE-DISK");
        long version = privateDetail(fixture.warehouse, id).path("version").asLong();
        for (String field :
                List.of(
                        "roundVersion",
                        "assetVersion",
                        "ownershipVersion",
                        "lockVersion",
                        "originalPhysicalIdentityChecked")) {
            var missing = body.deepCopy();
            missing.remove(field);
            assertEquals(
                    400,
                    post(fixture.warehouse, route, version, key(), missing).statusCode(),
                    field);
        }
        var wrong = body.deepCopy();
        wrong.put("observedSerial", "DIFFERENT-WHOLE-DEVICE");
        assertEquals(409, post(fixture.warehouse, route, version, key(), wrong).statusCode());
        var same = body.deepCopy();
        same.put("afterInfo", body.path("beforeInfo").asText());
        assertEquals(400, post(fixture.warehouse, route, version, key(), same).statusCode());
        var future = body.deepCopy();
        future.put("observedAt", Instant.now().plusSeconds(60).toString());
        assertEquals(400, post(fixture.warehouse, route, version, key(), future).statusCode());
        assertEquals(403, post(fixture.admin, route, version, key(), body).statusCode());
        assertEquals(403, post(fixture.otherWarehouse, route, version, key(), body).statusCode());
        ok(post(fixture.warehouse, route, version, key(), body));
        var stale = partsBody(id, round, "OLD-PRIVATE-DISK", "THIRD-PRIVATE-DISK");
        assertEquals(409, post(fixture.warehouse, route, version, key(), stale).statusCode());
        var originalObservation =
                db.queryForMap(
                        "SELECT * FROM fams_repair_factory_parts_change WHERE repair_id=? ORDER BY"
                                + " id LIMIT 1",
                        id);
        var continued =
                partsBody(
                        id,
                        round,
                        "Freshly observed original 8GB module",
                        "Observed replacement 8GB module");
        ok(
                post(
                        fixture.warehouse,
                        route,
                        privateDetail(fixture.warehouse, id).path("version").asLong(),
                        key(),
                        continued));
        assertEquals(
                originalObservation,
                db.queryForMap(
                        "SELECT * FROM fams_repair_factory_parts_change WHERE repair_id=? ORDER BY"
                                + " id LIMIT 1",
                        id));
        create(id, fixture.warehouse);
        assertFalse(ok(get(fixture.warehouse, route)).path("canRecord").asBoolean());
        var active = partsBody(id, round, "THIRD-PRIVATE-DISK", "FOURTH-PRIVATE-DISK");
        assertEquals(
                409,
                post(
                                fixture.warehouse,
                                route,
                                privateDetail(fixture.warehouse, id).path("version").asLong(),
                                key(),
                                active)
                        .statusCode());
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_factory_parts_change", Integer.class));
    }

    @Test
    void partsObservationConcurrentDistinctKeysHaveOneWinnerAndRoleRevocationBlocksReplay()
            throws Exception {
        String id = approved(), round = create(id, fixture.warehouse);
        dispatch(id, round, fixture.warehouse);
        receive(id, round, fixture.warehouse, "RESTORED");
        uploadPartsOriginal(id, round);
        var body = partsBody(id, round, "OLD-PRIVATE-MEMORY", "NEW-PRIVATE-MEMORY");
        long version = privateDetail(fixture.warehouse, id).path("version").asLong();
        String route = path(id, "/rounds/" + round + "/parts"), firstKey = key(), secondKey = key();
        var pool = Executors.newFixedThreadPool(2);
        int first;
        try {
            var one = pool.submit(() -> post(fixture.warehouse, route, version, firstKey, body));
            var two = pool.submit(() -> post(fixture.warehouse, route, version, secondKey, body));
            first = one.get().statusCode();
            assertEquals(
                    List.of(200, 409),
                    List.of(first, two.get().statusCode()).stream().sorted().toList());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_factory_parts_change", Integer.class));
        db.update(
                "DELETE FROM fams_account_role WHERE account_id=951003 AND"
                        + " role_code='WAREHOUSE_OPERATOR'");
        assertEquals(
                403,
                post(fixture.warehouse, route, version, first == 200 ? firstKey : secondKey, body)
                        .statusCode());
    }

    private void uploadPartsOriginal(String id, String round) throws Exception {
        var detail = (ObjectNode) privateDetail(fixture.warehouse, id);
        detail.set("activeRound", privateRound(fixture.warehouse, id, round));
        ok(uploadResponse(id, round, fixture.warehouse, "PARTS_CHANGE", detail, key()));
    }

    private ObjectNode partsBody(String id, String round, String before, String after)
            throws Exception {
        var body = guards(privateDetail(fixture.warehouse, id));
        var original = privateRound(fixture.warehouse, id, round);
        body.put("roundVersion", original.path("version").asLong());
        body.put("partName", "Memory component");
        body.put("beforeInfo", before);
        body.put("afterInfo", after);
        body.put("observedCode", "REP-ASSET-951020");
        body.put("observedSerial", "ORIGINAL-FACTORY-951020");
        body.put("observedAt", now());
        body.put("originalPhysicalIdentityChecked", true);
        body.put(
                "reason",
                "Observed the returned original and checked private before/after component"
                        + " originals");
        var originals = JSON.createArrayNode();
        for (var item : original.path("evidence")) {
            if ("PARTS_CHANGE".equals(item.path("purpose").asText())) {
                originals.add(item.path("object_id").asText());
            }
        }
        body.set("evidenceIds", originals);
        return body;
    }

    private Map<String, Object> partsMutationFacts(String id, String round) {
        var facts = new LinkedHashMap<String, Object>();
        facts.put("asset", db.queryForList("SELECT * FROM fams_asset WHERE id=951020"));
        facts.put(
                "round",
                db.queryForList("SELECT * FROM fams_repair_factory_round WHERE id=?", round));
        facts.put(
                "assetEvents",
                db.queryForList(
                        "SELECT * FROM fams_asset_timeline_event WHERE asset_id=951020 ORDER BY"
                                + " id"));
        for (String table :
                List.of(
                        "fams_repair_request",
                        "fams_repair_factory_parts_change",
                        "fams_repair_factory_event",
                        "fams_repair_event",
                        "fams_repair_receipt")) {
            facts.put(
                    table,
                    db.queryForList(
                            "SELECT * FROM "
                                    + table
                                    + " WHERE "
                                    + ("fams_repair_request".equals(table) ? "id" : "repair_id")
                                    + "=? ORDER BY "
                                    + ("fams_repair_receipt".equals(table) ? "request_id" : "id"),
                            id));
        }
        return facts;
    }

    private String begin() throws Exception {
        var submission = JSON.createObjectNode();
        submission.put("assetId", "951020");
        submission.put("assetVersion", 0);
        submission.put("fault", "Actual fault requires workshop diagnosis");
        submission.put("impact", "HIGH");
        submission.put(
                "occurredAt",
                Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS).toString());
        String id = ok(post(fixture.employee, "", 0, key(), submission)).path("id").asText();
        var decision = JSON.createObjectNode();
        decision.put("result", "APPROVE");
        decision.put("allowedMethod", "INTERNAL_NO_COST");
        decision.put("reason", "Original internal no-cost repair scope checked");
        ok(post(fixture.admin, "/" + id + "/decision", 0, key(), decision));
        ok(post(fixture.warehouse, "/" + id + "/claim", 1, key(), null));
        var row = root(fixture.warehouse, id);
        var away = guards(row);
        away.put("observedCode", "REP-ASSET-951020");
        away.put("actualLocationId", "951001");
        away.put("locationVersion", 0);
        away.put("occurredAt", now());
        away.put(
                "evidence",
                "Actual original device physically handed to this repair warehouse handler");
        ok(
                post(
                        fixture.warehouse,
                        "/" + id + "/accept-away",
                        row.path("version").asLong(),
                        key(),
                        away));
        return id;
    }

    private String approved() throws Exception {
        String id = begin();
        requestScope(id, fixture.warehouse);
        approve(id);
        return id;
    }

    private void requestScope(String id, Session warehouse) throws Exception {
        var row = privateDetail(warehouse, id);
        var input = guards(row);
        input.put("reason", "Actual warranty repair needs manufacturer service with no charges");
        ok(post(warehouse, path(id, "/scopes"), row.path("version").asLong(), key(), input));
    }

    private void approve(String id) throws Exception {
        var detail = privateDetail(fixture.admin, id);
        ok(
                post(
                        fixture.admin,
                        path(id, "/scope-decision"),
                        detail.path("version").asLong(),
                        key(),
                        scopeDecision(detail, "APPROVE")));
    }

    private ObjectNode scopeDecision(JsonNode detail, String result) {
        var input = JSON.createObjectNode();
        input.put("scopeId", detail.path("pendingScope").path("id").asText());
        input.put("scopeVersion", detail.path("pendingScope").path("version").asLong());
        input.put("result", result);
        input.put("reason", "Current factory scope and actual no-charge warranty facts checked");
        return input;
    }

    private String create(String id, Session warehouse) throws Exception {
        var detail = privateDetail(warehouse, id);
        return ok(post(
                        warehouse,
                        path(id, "/rounds"),
                        detail.path("version").asLong(),
                        key(),
                        roundCreation(detail)))
                .path("round")
                .path("id")
                .asText();
    }

    private ObjectNode roundCreation(JsonNode detail) {
        var input = guards(detail);
        input.put("scopeId", detail.path("approvedScope").path("id").asText());
        input.put("scopeVersion", detail.path("approvedScope").path("version").asLong());
        input.put("providerName", "Private named manufacturer");
        input.put("serviceContact", "Private factory contact");
        input.put("servicePhone", "13900001234");
        input.put(
                "expectedReturnDate",
                LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(7).toString());
        input.put("fault", "Original hardware fault verified for this separate factory round");
        return input;
    }

    private void dispatch(String id, String round, Session warehouse) throws Exception {
        JsonNode detail = privateRound(warehouse, id, round);
        boolean hasAcknowledgement = false;
        for (var item : detail.path("evidence")) {
            if ("FACTORY_ACK".equals(item.path("purpose").asText())) {
                hasAcknowledgement = true;
            }
        }
        if (!hasAcknowledgement) {
            upload(id, round, warehouse, "FACTORY_ACK");
        }
        var row = privateDetail(warehouse, id);
        ok(
                post(
                        warehouse,
                        path(id, "/rounds/" + round + "/dispatch"),
                        row.path("version").asLong(),
                        key(),
                        movement(id, round, warehouse, false, "RESTORED")));
    }

    private void receive(String id, String round, Session warehouse, String condition)
            throws Exception {
        upload(id, round, warehouse, "RECEIPT");
        var row = privateDetail(warehouse, id);
        ok(
                post(
                        warehouse,
                        path(id, "/rounds/" + round + "/receive-original"),
                        row.path("version").asLong(),
                        key(),
                        movement(id, round, warehouse, true, condition)));
    }

    private ObjectNode movement(
            String id, String round, Session warehouse, boolean receiving, String condition)
            throws Exception {
        var detail = privateDetail(warehouse, id);
        var original = privateRound(warehouse, id, round);
        var input = guards(detail);
        input.put("roundVersion", original.path("version").asLong());
        input.put("observedCode", "REP-ASSET-951020");
        input.put("observedSerial", "ORIGINAL-FACTORY-951020");
        input.put("occurredAt", now());
        var ids = JSON.createArrayNode();
        for (var item : original.path("evidence")) {
            if ((receiving ? "RECEIPT" : "FACTORY_ACK").equals(item.path("purpose").asText())) {
                ids.add(item.path("object_id").asText());
            }
        }
        input.set("evidenceIds", ids);
        if (receiving) {
            input.put("originalPhysicalIdentityChecked", true);
            input.put("actualLocationId", "951001");
            input.put("locationVersion", 0);
            input.put("conditionResult", condition);
            input.put("actualCost", "0.00");
            input.put(
                    "treatmentResult",
                    "Actual original inspected and true manufacturer result recorded, without"
                            + " charges");
        } else {
            input.put(
                    "evidence",
                    "Original device actually handed to named manufacturer with attached acceptance"
                            + " proof");
        }
        return input;
    }

    private ObjectNode guards(JsonNode detail) {
        var input = JSON.createObjectNode();
        for (String field : List.of("assetVersion", "ownershipVersion", "lockVersion")) {
            input.put(field, detail.path(field).asLong());
        }
        return input;
    }

    private ObjectNode inbound(JsonNode root, String time) {
        var input = guards(root);
        input.put("observedCode", "REP-ASSET-951020");
        input.put("warehouseId", "951000");
        input.put("warehouseVersion", 0);
        input.put("locationId", "951000");
        input.put("locationVersion", 0);
        input.put("occurredAt", time);
        input.put(
                "evidence",
                "Actual repair completed and original device verified at destination storage bin");
        return input;
    }

    private String upload(String id, String round, Session warehouse, String purpose)
            throws Exception {
        return ok(uploadResponse(
                        id, round, warehouse, purpose, privateDetail(warehouse, id), key()))
                .path("objectId")
                .asText();
    }

    private HttpResponse<String> uploadResponse(
            String id, String round, Session warehouse, String purpose, JsonNode detail, String key)
            throws Exception {
        String boundary = "factory-" + UUID.randomUUID();
        var bytes = new java.io.ByteArrayOutputStream();
        var fields = new LinkedHashMap<String, String>();
        fields.put("purpose", purpose);
        fields.put("roundVersion", detail.path("activeRound").path("version").asText());
        for (String field : List.of("assetVersion", "ownershipVersion", "lockVersion")) {
            fields.put(field, detail.path(field).asText());
        }
        for (var entry : fields.entrySet()) {
            bytes.write(
                    ("--"
                                    + boundary
                                    + "\r\nContent-Disposition: form-data; name=\""
                                    + entry.getKey()
                                    + "\"\r\n\r\n"
                                    + entry.getValue()
                                    + "\r\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        bytes.write(
                ("--"
                                + boundary
                                + "\r\n"
                                + "Content-Disposition: form-data; name=\"file\";"
                                + " filename=\"factory-proof.png\"\r\n"
                                + "Content-Type: image/png\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        bytes.write(PNG);
        bytes.write(
                ("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return http.send(
                HttpRequest.newBuilder(uri(path(id, "/rounds/" + round + "/evidence")))
                        .header("Cookie", warehouse.cookie())
                        .header("X-CSRF-Token", warehouse.csrf())
                        .header("Idempotency-Key", key)
                        .header("If-Match-Version", detail.path("version").asText())
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> content(
            Session who, String id, String round, String object, String token) throws Exception {
        return http.send(
                HttpRequest.newBuilder(
                                uri(
                                        path(
                                                id,
                                                "/rounds/"
                                                        + round
                                                        + "/evidence/"
                                                        + object
                                                        + "/content?token="
                                                        + java.net.URLEncoder.encode(
                                                                token,
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8))))
                        .header("Cookie", who.cookie())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private Map<String, Object> originalFacts() {
        return db.queryForMap(
                "SELECT"
                    + " custody_type,using_organization_id,responsible_employee_id,current_location_id,created_business_date,last_inventory_business_date"
                    + " FROM fams_asset WHERE id=951020");
    }

    private int activeLocks() {
        return Objects.requireNonNull(
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=951020 AND"
                                + " released_at IS NULL",
                        Integer.class));
    }

    private String lifecycle() {
        return db.queryForObject(
                "SELECT lifecycle_status FROM fams_asset WHERE id=951020", String.class);
    }

    private JsonNode privateDetail(Session who, String id) throws Exception {
        return ok(get(who, path(id, "")));
    }

    private JsonNode privateRound(Session who, String id, String round) throws Exception {
        return ok(get(who, path(id, "/rounds/" + round)));
    }

    private JsonNode root(Session who, String id) throws Exception {
        return ok(get(who, "/" + id));
    }

    private String path(String id, String tail) {
        return "/" + id + "/factory" + tail;
    }

    private String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + "/api/v1/repairs" + path);
    }

    private JsonNode ok(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> get(Session who, String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(path)).header("Cookie", who.cookie()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(
            Session who, String path, long version, String key, ObjectNode body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Cookie", who.cookie())
                        .header("X-CSRF-Token", who.csrf())
                        .header("If-Match-Version", Long.toString(version))
                        .header("Idempotency-Key", key)
                        .header("Content-Type", "application/json")
                        .POST(
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Session login(int index) throws Exception {
        var body = JSON.createObjectNode();
        body.put("username", "rep" + index);
        body.put("password", RepairHttpRuntimeCase.PASSWORD);
        var response =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:" + port + "/api/v1/auth/login"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        var data = ok(response);
        return new Session(
                response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                data.path("csrfToken").asText());
    }
}
