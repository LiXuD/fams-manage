package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Actual HTTP, owner ports and transactional MySQL; no simulated business writes. */
@SpringBootTest(
        classes = {
            HandoverHttpTest.TestApplication.class,
            HandoverHttpTest.MiniConfiguration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=fams-product",
            "spring.flyway.locations=classpath:db/migration/d6_04",
            "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
            "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra",
            "fams.inventory.start-poll-ms=3600000"
        })
@EnabledIfSystemProperty(named = "d6.19.repair.reassignment.runtime", matches = "true")
class RepairReassignmentHttpRuntimeCase {
    static final ObjectMapper JSON = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();
    final RepairHttpRuntimeCase fixture = new RepairHttpRuntimeCase();
    @Autowired JdbcTemplate db;
    @Autowired MiniSessionService miniSessions;

    @Value("${local.server.port}")
    int port;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        RepairHttpRuntimeCase.database(registry);
    }

    @BeforeEach
    void seed() throws Exception {
        fixture.db = db;
        fixture.miniSessions = miniSessions;
        fixture.port = port;
        fixture.seed();
    }

    @AfterEach
    void cleanup() throws Exception {
        db.execute("DROP TRIGGER IF EXISTS owned_repair_reassignment_audit_failure");
        db.execute("DROP TRIGGER IF EXISTS owned_repair_return_audit_failure");
        db.execute("DROP TRIGGER IF EXISTS owned_repair_target_audit_failure");
        db.update(
                "DELETE FROM fams_repair_approved_return_target WHERE repair_id IN (SELECT id FROM"
                        + " fams_repair_request WHERE asset_id BETWEEN 951020 AND 951023)");
        db.update(
                "DELETE FROM fams_todo WHERE business_type='ITEM_TARGET_CHANGE' AND business_id IN"
                    + " (SELECT id FROM fams_item_target_change WHERE business_type='REPAIR' AND"
                    + " business_id IN (SELECT id FROM fams_repair_request WHERE asset_id BETWEEN"
                    + " 951020 AND 951023))");
        db.update(
                "DELETE FROM fams_item_target_change WHERE business_type='REPAIR' AND business_id"
                    + " IN (SELECT id FROM fams_repair_request WHERE asset_id BETWEEN 951020 AND"
                    + " 951023)");
        db.update(
                "DELETE FROM fams_idempotency_record WHERE actor_account_id BETWEEN 951001 AND"
                        + " 951010 AND operation_id LIKE 'targetChange:%'");
        db.update(
                "DELETE FROM fams_todo WHERE business_type='LOAN_EXTENSION' AND business_id IN"
                        + " (SELECT id FROM fams_loan_extension WHERE loan_id IN (SELECT id FROM"
                        + " fams_loan WHERE asset_id BETWEEN 951020 AND 951023))");
        db.update(
                "DELETE FROM fams_loan_extension WHERE loan_id IN (SELECT id FROM fams_loan WHERE"
                        + " asset_id BETWEEN 951020 AND 951023)");
        db.update(
                "DELETE FROM fams_idempotency_record WHERE actor_account_id BETWEEN 951001 AND"
                        + " 951010 AND operation_id IN ('loan:propose','loan:decision')");
        db.update(
                "DELETE FROM fams_repair_return_recollection WHERE handover_id IN (SELECT id FROM"
                        + " fams_repair_return_handover WHERE repair_id IN (SELECT id FROM"
                        + " fams_repair_request WHERE asset_id BETWEEN 951020 AND 951023))");
        for (String table :
                List.of(
                        "fams_repair_return_handover",
                        "fams_loan_repair_return",
                        "fams_repair_inbound",
                        "fams_repair_physical_handling")) {
            db.update(
                    "DELETE FROM "
                            + table
                            + " WHERE repair_id IN (SELECT id FROM fams_repair_request WHERE"
                            + " asset_id BETWEEN 951020 AND 951023)");
        }
        fixture.cleanup();
    }

    @Test
    void controlledTransferPreservesFactsLockAndRevokesOldOwnerBeforeReplay() throws Exception {
        var row = processing();
        String id = row.path("id").asText(), originalKey = key(), originalBody = away(row);
        long oldVersion = row.path("version").asLong();
        ok(post(fixture.warehouse, id + "/accept-away", oldVersion, originalKey, originalBody));
        row = detail(id);
        var physical =
                db.queryForList(
                        "SELECT * FROM fams_repair_physical_handling WHERE repair_id=?", id);
        var assetBefore = asset();
        var locks = db.queryForList("SELECT * FROM fams_asset_business_lock WHERE asset_id=951020");
        var context = context(id);
        String body = transfer(context), request = key();
        var result =
                ok(
                        post(
                                fixture.admin,
                                id + "/reassignment",
                                row.path("version").asLong(),
                                request,
                                body));
        assertEquals("951004", result.path("processorAccountId").asText());
        assertEquals(
                row.path("ownershipVersion").asLong() + 1,
                result.path("ownershipVersion").asLong());
        assertEquals(assetBefore, asset());
        assertEquals(
                physical,
                db.queryForList(
                        "SELECT * FROM fams_repair_physical_handling WHERE repair_id=?", id));
        assertEquals(
                locks,
                db.queryForList("SELECT * FROM fams_asset_business_lock WHERE asset_id=951020"));
        assertEquals(row.path("source"), result.path("source"));
        assertEquals(
                result,
                ok(
                        post(
                                fixture.admin,
                                id + "/reassignment",
                                row.path("version").asLong(),
                                request,
                                body)));
        assertEquals(
                409,
                post(fixture.warehouse, id + "/accept-away", oldVersion, originalKey, originalBody)
                        .statusCode());
        assertEquals(
                0,
                count(
                        "fams_todo",
                        "business_type='REPAIR' AND business_id="
                                + id
                                + " AND pool_type='WAREHOUSE_OPERATOR' AND"
                                + " assignee_account_id=951003 AND closed_at IS NULL"));
        assertEquals(
                1,
                count(
                        "fams_repair_event",
                        "repair_id=" + id + " AND action='REPAIR_CONTROLLED_REASSIGNMENT'"));
        assertTrue(detail(id).path("actions").path("acceptAway").asBoolean() == false);
        assertEquals(
                "COMPLETED",
                ok(post(
                                fixture.otherWarehouse,
                                id + "/close-inbound",
                                result.path("version").asLong(),
                                key(),
                                inbound(result)))
                        .path("status")
                        .asText());
    }

    @Test
    void preAcceptanceTransferHasNoFabricatedPhysicalHandling() throws Exception {
        var row = processing();
        var context = context(row.path("id").asText());
        assertTrue(context.path("snapshot").path("acceptedAt").isNull());
        assertEquals(0, context.path("snapshot").path("physicalHandling").size());
        var result =
                ok(
                        post(
                                fixture.admin,
                                row.path("id").asText() + "/reassignment",
                                row.path("version").asLong(),
                                key(),
                                transfer(context)));
        assertEquals(0, result.path("physicalHandling").size());
        assertNull(
                db.queryForObject(
                        "SELECT accepted_at FROM fams_repair_request WHERE id=?",
                        java.sql.Timestamp.class,
                        row.path("id").asLong()));
        assertEquals("IN_USE", asset().get("lifecycle_status"));
    }

    @Test
    void outstandingDeliveryAndReceiverTodoSurviveAndNewOwnerMustActuallyRecollect()
            throws Exception {
        var row = delivered(accepted());
        String id = row.path("id").asText();
        var handovers =
                db.queryForList("SELECT * FROM fams_repair_return_handover WHERE repair_id=?", id);
        var receiverTodos =
                db.queryForList(
                        "SELECT * FROM fams_todo WHERE business_type='REPAIR' AND business_id=? AND"
                                + " pool_type='RECEIVER'",
                        id);
        var result =
                ok(
                        post(
                                fixture.admin,
                                id + "/reassignment",
                                row.path("version").asLong(),
                                key(),
                                transfer(context(id))));
        assertEquals(
                handovers,
                db.queryForList("SELECT * FROM fams_repair_return_handover WHERE repair_id=?", id));
        assertEquals(
                receiverTodos,
                db.queryForList(
                        "SELECT * FROM fams_todo WHERE business_type='REPAIR' AND business_id=? AND"
                                + " pool_type='RECEIVER'",
                        id));
        assertEquals(
                409,
                post(
                                fixture.otherWarehouse,
                                id + "/close-inbound",
                                result.path("version").asLong(),
                                key(),
                                inbound(result))
                        .statusCode());
        ok(
                post(
                        fixture.otherWarehouse,
                        id + "/return-recollection",
                        result.path("version").asLong(),
                        key(),
                        recollection(result)));
        var latest = detail(id);
        assertEquals(
                "COMPLETED",
                ok(post(
                                fixture.otherWarehouse,
                                id + "/close-inbound",
                                latest.path("version").asLong(),
                                key(),
                                inbound(latest)))
                        .path("status")
                        .asText());
    }

    @Test
    void changedSnapshotAndInvalidTargetOrMissingCheckNeverTransfer() throws Exception {
        var row = processing();
        String id = row.path("id").asText();
        var context = context(id);
        var body = (ObjectNode) JSON.readTree(transfer(context));
        body.put("unsynchronizedResultsChecked", false);
        assertEquals(
                400,
                post(fixture.admin, id + "/reassignment", 2, key(), body.toString()).statusCode());
        body = (ObjectNode) JSON.readTree(transfer(context));
        body.put("processorAccountId", "951002");
        assertEquals(
                409,
                post(fixture.admin, id + "/reassignment", 2, key(), body.toString()).statusCode());
        body = (ObjectNode) JSON.readTree(transfer(context));
        ((ObjectNode) body.path("snapshot")).put("ownershipVersion", 999);
        assertEquals(
                409,
                post(fixture.admin, id + "/reassignment", 2, key(), body.toString()).statusCode());
        assertEquals(403, get(fixture.system, id + "/reassignment").statusCode());
        assertEquals(
                403,
                post(fixture.warehouse, id + "/reassignment", 2, key(), transfer(context))
                        .statusCode());
        assertEquals("951003", detail(id).path("processorAccountId").asText());
    }

    @Test
    void lateAuditFailureRollsBackOwnerTodosEventAndReceipt() throws Exception {
        var row = accepted();
        String id = row.path("id").asText();
        var body = transfer(context(id));
        var before =
                db.queryForList(
                        "SELECT * FROM fams_todo WHERE business_type='REPAIR' AND business_id=?",
                        id);
        db.execute(
                "CREATE TRIGGER owned_repair_reassignment_audit_failure BEFORE INSERT ON"
                    + " fams_audit_event FOR EACH ROW BEGIN IF"
                    + " NEW.action='REPAIR_CONTROLLED_REASSIGNMENT' THEN SIGNAL SQLSTATE '45000'"
                    + " SET MESSAGE_TEXT='owned late failure'; END IF; END");
        assertEquals(
                500,
                post(fixture.admin, id + "/reassignment", row.path("version").asLong(), key(), body)
                        .statusCode());
        assertEquals("951003", detail(id).path("processorAccountId").asText());
        assertEquals(
                before,
                db.queryForList(
                        "SELECT * FROM fams_todo WHERE business_type='REPAIR' AND business_id=?",
                        id));
        assertEquals(
                0,
                count(
                        "fams_repair_event",
                        "repair_id=" + id + " AND action='REPAIR_CONTROLLED_REASSIGNMENT'"));
        assertEquals(
                0,
                count(
                        "fams_repair_receipt",
                        "repair_id=" + id + " AND operation LIKE 'REASSIGN:%'"));
    }

    @Test
    void withdrawnTargetRoleAndCurrentAdministratorRoleRejectBeforeWriteOrReplay()
            throws Exception {
        var row = processing();
        String id = row.path("id").asText(), body = transfer(context(id)), request = key();
        db.update(
                "DELETE FROM fams_account_role WHERE account_id=951004 AND"
                        + " role_code='WAREHOUSE_OPERATOR'");
        assertEquals(409, post(fixture.admin, id + "/reassignment", 2, request, body).statusCode());
        db.update(
                "INSERT INTO fams_account_role(account_id,role_code,granted_at) VALUES"
                        + " (951004,'WAREHOUSE_OPERATOR',NOW(3))");
        ok(post(fixture.admin, id + "/reassignment", 2, request, body));
        db.update(
                "DELETE FROM fams_account_role WHERE account_id=951001 AND"
                        + " role_code='ASSET_ADMIN'");
        assertEquals(403, post(fixture.admin, id + "/reassignment", 2, request, body).statusCode());
    }

    @Test
    void twoAdministratorsCompeteForOneOwnershipVersion() throws Exception {
        var row = accepted();
        String id = row.path("id").asText(), body = transfer(context(id));
        long version = row.path("version").asLong();
        var second = login(10);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new java.util.concurrent.CountDownLatch(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            var a =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return post(
                                        fixture.admin, id + "/reassignment", version, key(), body);
                            });
            var b =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return post(second, id + "/reassignment", version, key(), body);
                            });
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            var codes = new ArrayList<>(List.of(a.get().statusCode(), b.get().statusCode()));
            Collections.sort(codes);
            assertEquals(List.of(200, 409), codes);
        }
        assertEquals(
                1,
                count(
                        "fams_repair_event",
                        "repair_id=" + id + " AND action='REPAIR_CONTROLLED_REASSIGNMENT'"));
        assertEquals(
                row.path("ownershipVersion").asLong() + 1,
                detail(id).path("ownershipVersion").asLong());
    }

    @Test
    void receiverReceiptAndReassignmentCannotBothCommitAgainstSameVersion() throws Exception {
        var row = delivered(accepted());
        String id = row.path("id").asText();
        String body = transfer(context(id)), actualReceipt = receipt(row);
        long version = row.path("version").asLong();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var a =
                    executor.submit(
                            () -> {
                                start.await();
                                return post(
                                        fixture.admin, id + "/reassignment", version, key(), body);
                            });
            var b =
                    executor.submit(
                            () -> {
                                start.await();
                                return post(
                                        fixture.employee,
                                        id + "/return-receipt",
                                        version,
                                        key(),
                                        actualReceipt);
                            });
            start.countDown();
            var codes = new ArrayList<>(List.of(a.get().statusCode(), b.get().statusCode()));
            Collections.sort(codes);
            assertEquals(List.of(200, 409), codes);
        }
        var latest = detail(id);
        if ("WAREHOUSE_PROCESSING".equals(latest.path("status").asText())) {
            assertEquals("951004", latest.path("processorAccountId").asText());
            ok(
                    post(
                            fixture.employee,
                            id + "/return-receipt",
                            latest.path("version").asLong(),
                            key(),
                            receipt(latest)));
        }
        assertEquals("COMPLETED", detail(id).path("status").asText());
        assertEquals(
                1,
                count(
                        "fams_repair_return_handover",
                        "repair_id=" + id + " AND handover_status='RECEIVED'"));
        assertEquals(
                0, count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
    }

    @Test
    void physicalProgressAfterInspectionRejectsStaleTransferWithoutChangingOwner()
            throws Exception {
        var row = processing();
        String id = row.path("id").asText(), body = transfer(context(id));
        ok(post(fixture.warehouse, id + "/accept-away", 2, key(), away(row)));
        assertEquals(409, post(fixture.admin, id + "/reassignment", 2, key(), body).statusCode());
        assertEquals("951003", detail(id).path("processorAccountId").asText());
        assertEquals(1, count("fams_repair_physical_handling", "repair_id=" + id));
    }

    @Test
    void inactivePreviousOwnerCanBeReplacedAndReplayRemainsPermanent() throws Exception {
        var row = accepted();
        String id = row.path("id").asText(), body = transfer(context(id)), request = key();
        db.update("UPDATE fams_system_account SET status='DISABLED' WHERE id=951003");
        var result =
                ok(
                        post(
                                fixture.admin,
                                id + "/reassignment",
                                row.path("version").asLong(),
                                request,
                                body));
        db.update(
                "UPDATE fams_repair_receipt SET created_at=DATE_SUB(created_at,INTERVAL 90 DAY)"
                        + " WHERE repair_id=?",
                id);
        assertEquals(
                result,
                ok(
                        post(
                                fixture.admin,
                                id + "/reassignment",
                                row.path("version").asLong(),
                                request,
                                body)));
        assertEquals("951004", result.path("processorAccountId").asText());
        assertEquals(
                1,
                count(
                        "fams_repair_event",
                        "repair_id=" + id + " AND action='REPAIR_CONTROLLED_REASSIGNMENT'"));
    }

    @Test
    void actualNativeHandlersReassignAndRetryCommittedReplyBeforeNewOwnerInbound()
            throws Exception {
        var grants = new LinkedHashMap<String, Object>();
        for (var entry :
                Map.of(
                                "employee",
                                951002L,
                                "admin",
                                951001L,
                                "warehouse",
                                951003L,
                                "newWarehouse",
                                951004L)
                        .entrySet()) {
            var identity =
                    new MiniWechatIdentityPort.Identity(
                            "owned-reassignment-native", entry.getKey());
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
                        .resolve("apps/miniprogram/test/runtime-repair-reassignment-http.mjs");
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
        var process = builder.start();
        assertTrue(process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS));
        var output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("\"nativeActualReassignmentClosed\":true"), output);
        assertEquals("IN_STOCK", asset().get("lifecycle_status"));
        assertEquals(1, count("fams_repair_event", "action='REPAIR_CONTROLLED_REASSIGNMENT'"));
        assertEquals(
                0, count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
    }

    @Test
    void inPlaceAcceptanceIsPreservedAndNewOwnerCanActuallyFinish() throws Exception {
        var row = processing();
        String id = row.path("id").asText();
        ok(
                post(
                        fixture.warehouse,
                        id + "/accept-in-place",
                        row.path("version").asLong(),
                        key(),
                        inPlace(row)));
        row = detail(id);
        var result =
                ok(
                        post(
                                fixture.admin,
                                id + "/reassignment",
                                row.path("version").asLong(),
                                key(),
                                transfer(context(id))));
        assertEquals(row.path("acceptedAt"), result.path("acceptedAt"));
        assertEquals(0, result.path("physicalHandling").size());
        assertEquals(
                "COMPLETED",
                ok(post(
                                fixture.otherWarehouse,
                                id + "/complete-in-place",
                                result.path("version").asLong(),
                                key(),
                                inPlace(result)))
                        .path("status")
                        .asText());
    }

    @Test
    void refusedDeliveryStaysRefusedUntilNewOwnerActuallyRecollects() throws Exception {
        var row = delivered(accepted());
        String id = row.path("id").asText();
        ok(
                post(
                        fixture.employee,
                        id + "/return-refusal",
                        row.path("version").asLong(),
                        key(),
                        receipt(row)));
        row = detail(id);
        var before =
                db.queryForList("SELECT * FROM fams_repair_return_handover WHERE repair_id=?", id);
        var result =
                ok(
                        post(
                                fixture.admin,
                                id + "/reassignment",
                                row.path("version").asLong(),
                                key(),
                                transfer(context(id))));
        assertEquals(
                before,
                db.queryForList("SELECT * FROM fams_repair_return_handover WHERE repair_id=?", id));
        assertEquals(
                "REFUSED", result.path("returnHandovers").get(0).path("handover_status").asText());
        ok(
                post(
                        fixture.otherWarehouse,
                        id + "/return-recollection",
                        result.path("version").asLong(),
                        key(),
                        recollection(result)));
        assertEquals(
                "RECOLLECTED",
                detail(id).path("returnHandovers").get(0).path("handover_status").asText());
    }

    @Test
    void expiredLoanIsNotRenewedByReassignmentAndClosesOnlyOnActualInbound() throws Exception {
        borrowing(true);
        var row = accepted();
        String id = row.path("id").asText();
        var loansBefore = db.queryForList("SELECT * FROM fams_loan WHERE asset_id=951020");
        var result =
                ok(
                        post(
                                fixture.admin,
                                id + "/reassignment",
                                row.path("version").asLong(),
                                key(),
                                transfer(context(id))));
        assertEquals(loansBefore, db.queryForList("SELECT * FROM fams_loan WHERE asset_id=951020"));
        assertEquals(
                "COMPLETED",
                ok(post(
                                fixture.otherWarehouse,
                                id + "/close-inbound",
                                result.path("version").asLong(),
                                key(),
                                inbound(result)))
                        .path("status")
                        .asText());
        assertEquals(
                "RETURNED",
                db.queryForObject(
                        "SELECT relation_status FROM fams_loan WHERE asset_id=951020",
                        String.class));
        assertEquals(
                loansBefore.getFirst().get("original_return_date"),
                db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020")
                        .get("original_return_date"));
    }

    @Test
    void semanticSnapshotFieldOrderDoesNotChangePermanentOriginalReplay() throws Exception {
        var row = accepted();
        String id = row.path("id").asText(), request = key();
        var body = (ObjectNode) JSON.readTree(transfer(context(id)));
        var result =
                ok(
                        post(
                                fixture.admin,
                                id + "/reassignment",
                                row.path("version").asLong(),
                                request,
                                body.toString()));
        body.set("snapshot", reversedFields(body.path("snapshot")));
        assertEquals(
                result,
                ok(
                        post(
                                fixture.admin,
                                id + "/reassignment",
                                row.path("version").asLong(),
                                request,
                                body.toString())));
        body.put("reason", "A genuinely different reassignment reason");
        assertEquals(
                409,
                post(
                                fixture.admin,
                                id + "/reassignment",
                                row.path("version").asLong(),
                                request,
                                body.toString())
                        .statusCode());
        assertEquals(
                1,
                count(
                        "fams_repair_event",
                        "repair_id=" + id + " AND action='REPAIR_CONTROLLED_REASSIGNMENT'"));
    }

    private JsonNode reversedFields(JsonNode value) {
        if (value.isObject()) {
            var result = JSON.createObjectNode();
            var entries = new ArrayList<>(value.properties());
            Collections.reverse(entries);
            entries.forEach(entry -> result.set(entry.getKey(), reversedFields(entry.getValue())));
            return result;
        }
        if (value.isArray()) {
            var result = JSON.createArrayNode();
            value.forEach(item -> result.add(reversedFields(item)));
            return result;
        }
        return value;
    }

    private JsonNode context(String id) throws Exception {
        return ok(get(fixture.admin, id + "/reassignment"));
    }

    private String transfer(JsonNode context) {
        return JSON.writeValueAsString(
                Map.of(
                        "processorAccountId",
                        "951004",
                        "snapshot",
                        context.path("snapshot"),
                        "unsynchronizedResultsChecked",
                        true,
                        "reason",
                        "Original warehouse operator unavailable",
                        "evidence",
                        "Actually checked physical custody, pending receipt and all unsynchronized"
                                + " results with handover proof"));
    }

    private JsonNode accepted() throws Exception {
        var row = processing();
        String id = row.path("id").asText();
        ok(
                post(
                        fixture.warehouse,
                        id + "/accept-away",
                        row.path("version").asLong(),
                        key(),
                        away(row)));
        return detail(id);
    }

    private JsonNode delivered(JsonNode row) throws Exception {
        String id = row.path("id").asText();
        ok(
                post(
                        fixture.warehouse,
                        id + "/return-delivery",
                        row.path("version").asLong(),
                        key(),
                        delivery(row)));
        return detail(id);
    }

    private String delivery(JsonNode row) throws Exception {
        var body = base(row);
        var target = ok(get(fixture.warehouse, row.path("id").asText() + "/return-target"));
        for (String field : List.of("employeeVersion", "organizationVersion", "locationVersion")) {
            body.put(field, target.path(field).asLong());
        }
        return body.toString();
    }

    private String receipt(JsonNode row) {
        var body = base(row);
        body.put("handoverId", row.path("returnHandovers").get(0).path("id").asText());
        body.put("physicalChecked", true);
        return body.toString();
    }

    private String recollection(JsonNode row) {
        var body = base(row);
        body.put("handoverId", row.path("returnHandovers").get(0).path("id").asText());
        body.put("actualLocationId", "951000");
        body.put("locationVersion", 0);
        return body.toString();
    }

    private Session login(int index) throws Exception {
        var response =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:" + port + "/api/v1/auth/login"))
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                JSON.writeValueAsString(
                                                        Map.of(
                                                                "username",
                                                                "rep" + index,
                                                                "password",
                                                                RepairHttpRuntimeCase.PASSWORD))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        var result = ok(response);
        return new Session(
                response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                result.path("csrfToken").asText());
    }

    private HttpResponse<String> loanPost(Session actor, String path, long version, String body)
            throws Exception {
        return http.send(
                HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + port + "/api/v1/loans" + path))
                        .header("Cookie", actor.cookie())
                        .header("X-CSRF-Token", actor.csrf())
                        .header("Idempotency-Key", key())
                        .header("If-Match-Version", Long.toString(version))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode processing() throws Exception {
        String body =
                JSON.writeValueAsString(
                        Map.of(
                                "assetId",
                                "951020",
                                "assetVersion",
                                0,
                                "fault",
                                "Actual physical fault requires repair",
                                "impact",
                                "HIGH",
                                "occurredAt",
                                Instant.now()
                                        .minusSeconds(60)
                                        .truncatedTo(ChronoUnit.MILLIS)
                                        .toString()));
        String id = ok(post(fixture.employee, "", 0, key(), body)).path("id").asText();
        ok(
                post(
                        fixture.admin,
                        id + "/decision",
                        0,
                        key(),
                        "{\"result\":\"APPROVE\",\"allowedMethod\":\"INTERNAL_NO_COST\",\"reason\":\"Approve"
                            + " actual internal repair without cost\"}"));
        ok(post(fixture.warehouse, id + "/claim", 1, key(), null));
        return detail(id);
    }

    private ObjectNode base(JsonNode row) {
        var body = JSON.createObjectNode();
        for (String field : List.of("assetVersion", "ownershipVersion", "lockVersion")) {
            body.put(field, row.path(field).asLong());
        }
        if (row.path("loanVersion").isNumber()) {
            body.put("loanVersion", row.path("loanVersion").asLong());
        } else {
            body.putNull("loanVersion");
        }
        body.put("observedCode", row.path("source").path("assetCode").asText());
        body.put("occurredAt", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        body.put(
                "evidence",
                "Actual warehouse physical check and evidence of restoration and custody recorded");
        return body;
    }

    private String away(JsonNode row) {
        var b = base(row);
        b.put("actualLocationId", "951000");
        b.put("locationVersion", 0);
        return b.toString();
    }

    private String inbound(JsonNode row) {
        var b = base(row);
        b.put("warehouseId", "951000");
        b.put("warehouseVersion", 0);
        b.put("locationId", "951000");
        b.put("locationVersion", 0);
        return b.toString();
    }

    private String inPlace(JsonNode row) {
        var b = base(row);
        b.put("actualLocationId", row.path("source").path("locationId").asText());
        b.put("neverLeftCustodian", true);
        return b.toString();
    }

    private JsonNode detail(String id) throws Exception {
        return ok(get(fixture.warehouse, id));
    }

    private Map<String, Object> asset() {
        return db.queryForMap("SELECT * FROM fams_asset WHERE id=951020");
    }

    private int count(String table, String where) {
        return Objects.requireNonNull(
                db.queryForObject(
                        "SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class));
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private JsonNode ok(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> get(Session actor, String path) throws Exception {
        return api(actor, "GET", path, 0, null, null);
    }

    private HttpResponse<String> post(
            Session actor, String path, long version, String key, String body) throws Exception {
        return api(actor, "POST", path, version, key, body);
    }

    private HttpResponse<String> api(
            Session actor, String method, String path, long version, String key, String body)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/api/v1/repairs"
                                                + (path.isEmpty() ? "" : "/" + path)))
                        .header("Cookie", actor.cookie());
        if (!method.equals("GET")) {
            builder.header("X-CSRF-Token", actor.csrf())
                    .header("Idempotency-Key", key)
                    .header("If-Match-Version", Long.toString(version));
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return http.send(
                builder.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void borrowing(boolean expired) {
        db.update(
                "INSERT INTO"
                    + " fams_asset_import_batch(id,actor_account_id,upload_key,source_sha256,source_bytes,source_xlsx,created_at,updated_at)"
                    + " VALUES"
                    + " ('00000000-0000-4000-8000-000000009519',951001,'owned-physical-loan',SHA2('owned"
                    + " initial bytes',256),1,X'01',NOW(3),NOW(3))");
        db.update("UPDATE fams_asset SET lifecycle_status='BORROWED' WHERE id=951020");
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        db.update(
                "INSERT INTO"
                    + " fams_loan(asset_id,borrower_employee_id,using_organization_id,source_type,initial_batch_id,initial_sheet_row,borrowed_date,original_return_date,expected_return_date,parameter_snapshot_json,created_at,updated_at)"
                    + " VALUES"
                    + " (951020,951002,951000,'INITIAL_IMPORT','00000000-0000-4000-8000-000000009519',2,?,?,?,JSON_OBJECT('timezone','Asia/Shanghai'),NOW(3),NOW(3))",
                today.minusDays(30),
                today.minusDays(1),
                expired ? today.minusDays(1) : today.plusDays(30));
    }
}
