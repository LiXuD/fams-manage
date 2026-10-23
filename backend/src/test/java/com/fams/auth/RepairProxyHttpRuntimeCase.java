package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Actual repair/proxy/asset/notice HTTP transactions; malware scanner is an explicit fixture. */
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
@EnabledIfSystemProperty(named = "d6.19.repair.proxy.runtime", matches = "true")
class RepairProxyHttpRuntimeCase {
    static final ObjectMapper JSON = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();
    final RepairReturnHttpRuntimeCase physical = new RepairReturnHttpRuntimeCase();
    final RepairHttpRuntimeCase fixture = physical.fixture;
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
        physical.db = db;
        physical.miniSessions = miniSessions;
        physical.port = port;
        physical.seed();
    }

    @AfterEach
    void cleanup() throws Exception {
        db.execute("DROP TRIGGER IF EXISTS owned_repair_proxy_failure");
        db.update(
                "DELETE FROM fams_repair_proxy_return_evidence WHERE repair_id IN (SELECT id FROM"
                        + " fams_repair_request WHERE asset_id BETWEEN 951020 AND 951023)");
        db.update(
                "DELETE FROM fams_business_result_notice WHERE business_type='REPAIR' AND"
                    + " business_id IN (SELECT id FROM fams_repair_request WHERE asset_id BETWEEN"
                    + " 951020 AND 951023)");
        for (String table :
                List.of(
                        "fams_attachment_cleanup_audit",
                        "fams_attachment_deletion_job",
                        "fams_attachment_upload_key")) {
            db.update(
                    "DELETE FROM "
                            + table
                            + " WHERE object_id IN (SELECT object_id FROM fams_attachment WHERE"
                            + " business_type='PROXY_CONFIRMATION' AND business_id IN (SELECT id"
                            + " FROM fams_proxy_confirmation WHERE creator_account_id BETWEEN"
                            + " 951001 AND 951010))");
        }
        db.update(
                "DELETE FROM fams_attachment WHERE business_type='PROXY_CONFIRMATION' AND"
                        + " business_id IN (SELECT id FROM fams_proxy_confirmation WHERE"
                        + " creator_account_id BETWEEN 951001 AND 951010)");
        db.update(
                "DELETE FROM fams_attachment_scope WHERE business_type='PROXY_CONFIRMATION' AND"
                        + " business_id IN (SELECT id FROM fams_proxy_confirmation WHERE"
                        + " creator_account_id BETWEEN 951001 AND 951010)");
        db.update(
                "UPDATE fams_proxy_confirmation SET previous_confirmation_id=NULL WHERE"
                        + " creator_account_id BETWEEN 951001 AND 951010");
        db.update(
                "DELETE FROM fams_proxy_confirmation WHERE creator_account_id BETWEEN 951001 AND"
                        + " 951010");
        db.update(
                "DELETE FROM fams_idempotency_record WHERE actor_account_id BETWEEN 951001 AND"
                        + " 951010");
        physical.cleanup();
    }

    @Test
    void approvalKeepsRepairLockedUntilCurrentWarehouseExecutesAndRecipientCanObject()
            throws Exception {
        var row = delivered(accepted());
        String repair = row.path("id").asText();
        var before = asset();
        var original =
                db.queryForMap(
                        "SELECT * FROM fams_repair_return_handover WHERE repair_id=?", repair);
        assertTrue(row.path("actions").path("proxyReturn").asBoolean());
        String id = approved(row);
        assertEquals("UNDER_REPAIR", asset().get("lifecycle_status"));
        assertEquals(
                1, count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
        assertEquals(0, count("fams_business_result_notice", "business_type='REPAIR'"));
        assertEquals(
                403,
                call(
                                fixture.admin,
                                "POST",
                                "/proxy-confirmations/" + id + "/execute",
                                3,
                                key(),
                                null)
                        .statusCode());
        String replay = key();
        var result = execute(id, replay);
        assertEquals("EXECUTED", result.path("confirmationStatus").asText());
        assertEquals(result, execute(id, replay));
        assertEquals("COMPLETED", detail(repair).path("status").asText());
        assertEquals("IN_USE", asset().get("lifecycle_status"));
        for (String field :
                List.of(
                        "responsible_employee_id",
                        "using_organization_id",
                        "current_location_id",
                        "last_inventory_business_date",
                        "created_business_date")) {
            assertEquals(before.get(field), asset().get(field));
        }
        var after =
                db.queryForMap(
                        "SELECT * FROM fams_repair_return_handover WHERE repair_id=?", repair);
        for (String field :
                List.of(
                        "id",
                        "target_snapshot_json",
                        "delivery_evidence",
                        "delivered_at",
                        "delivered_by",
                        "recorded_at")) {
            assertEquals(original.get(field), after.get(field));
        }
        assertEquals("RECEIVED", after.get("handover_status"));
        var proof =
                db.queryForMap(
                        "SELECT * FROM fams_repair_proxy_return_evidence WHERE confirmation_id=?",
                        id);
        assertEquals("Actual original recipient", proof.get("actual_signer"));
        assertEquals(951002L, ((Number) proof.get("actual_signer_employee_id")).longValue());
        assertEquals(951001L, ((Number) proof.get("approved_by")).longValue());
        assertEquals(951003L, ((Number) proof.get("executed_by")).longValue());
        assertEquals(
                0, count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
        assertEquals(
                1,
                count(
                        "fams_business_result_notice",
                        "business_type='REPAIR' AND recipient_employee_id=951002"));
        var inbox = ok(call(fixture.employee, "GET", "/business-result-notices", 0, null, null));
        String notice = inbox.path("items").get(0).path("id").asText();
        assertEquals(
                0,
                ok(call(fixture.other, "GET", "/business-result-notices", 0, null, null))
                        .path("total")
                        .asInt());
        assertEquals(
                404,
                call(
                                fixture.other,
                                "POST",
                                "/business-result-notices/" + notice + "/objection",
                                0,
                                key(),
                                "{\"reason\":\"Not the recipient\"}")
                        .statusCode());
        String originalKey = key(),
                body = "{\"reason\":\"Please check actual signed receipt evidence\"}";
        var objection =
                ok(
                        call(
                                fixture.employee,
                                "POST",
                                "/business-result-notices/" + notice + "/objection",
                                0,
                                originalKey,
                                body));
        assertEquals(
                objection,
                ok(
                        call(
                                fixture.employee,
                                "POST",
                                "/business-result-notices/" + notice + "/objection",
                                0,
                                originalKey,
                                body)));
        var review =
                ok(call(
                                fixture.admin,
                                "GET",
                                "/business-result-notices/review-requests",
                                0,
                                null,
                                null))
                        .path("items")
                        .get(0);
        assertEquals("REPAIR", review.path("businessType").asText());
        assertEquals(id, review.path("executionId").asText());
        assertEquals("IN_USE", asset().get("lifecycle_status"));
    }

    @Test
    void disabledLoginDoesNotInvalidateAnActiveEmployeeActualReceipt() throws Exception {
        var row = delivered(accepted());
        db.update(
                "UPDATE fams_system_account SET status='DISABLED',auth_epoch=auth_epoch+1 WHERE"
                        + " id=951002");
        String id = approved(row);
        execute(id, key());
        assertEquals("IN_USE", asset().get("lifecycle_status"));
        assertEquals(1, count("fams_business_result_notice", "recipient_employee_id=951002"));
    }

    @Test
    void inactiveEmployeeCannotUseApprovedEvidenceAndDoesNotReleaseLock() throws Exception {
        var row = delivered(accepted());
        String id = approved(row);
        db.update("UPDATE fams_employee SET status='DISABLED',version=version+1 WHERE id=951002");
        assertEquals(
                409,
                call(
                                fixture.warehouse,
                                "POST",
                                "/proxy-confirmations/" + id + "/execute",
                                3,
                                key(),
                                null)
                        .statusCode());
        assertUnexecuted(id);
    }

    @Test
    void lateAuditAndNoticeFailuresRollbackEveryDomainThenExactOriginalRetrySucceeds()
            throws Exception {
        var row = delivered(accepted());
        String id = approved(row), request = key();
        for (String trigger :
                List.of(
                        "BEFORE INSERT ON fams_audit_event FOR EACH ROW BEGIN IF"
                            + " NEW.action='REPAIR_PROXY_RETURN_RECEIVED' THEN SIGNAL SQLSTATE"
                            + " '45000' SET MESSAGE_TEXT='owned repair audit failure'; END IF; END",
                        "BEFORE INSERT ON fams_business_result_notice FOR EACH ROW SIGNAL SQLSTATE"
                                + " '45000' SET MESSAGE_TEXT='owned repair notice failure'")) {
            db.execute("CREATE TRIGGER owned_repair_proxy_failure " + trigger);
            try {
                assertEquals(
                        500,
                        call(
                                        fixture.warehouse,
                                        "POST",
                                        "/proxy-confirmations/" + id + "/execute",
                                        3,
                                        request,
                                        null)
                                .statusCode());
                assertUnexecuted(id);
            } finally {
                db.execute("DROP TRIGGER owned_repair_proxy_failure");
            }
        }
        execute(id, request);
        assertEquals(1, count("fams_repair_proxy_return_evidence", "confirmation_id=" + id));
    }

    @Test
    void refusalOrActualRecollectionInvalidatesPreviouslyApprovedPermission() throws Exception {
        var row = delivered(accepted());
        String id = approved(row), repair = row.path("id").asText();
        ok(
                post(
                        fixture.employee,
                        repair + "/return-refusal",
                        row.path("version").asLong(),
                        key(),
                        receipt(row)));
        assertEquals(
                409,
                call(
                                fixture.warehouse,
                                "POST",
                                "/proxy-confirmations/" + id + "/execute",
                                3,
                                key(),
                                null)
                        .statusCode());
        assertUnexecuted(id);
        row = detail(repair);
        ok(
                post(
                        fixture.warehouse,
                        repair + "/return-recollection",
                        row.path("version").asLong(),
                        key(),
                        recollection(row)));
        assertEquals(
                409,
                call(
                                fixture.warehouse,
                                "POST",
                                "/proxy-confirmations/" + id + "/execute",
                                3,
                                key(),
                                null)
                        .statusCode());
        assertEquals(0, count("fams_repair_proxy_return_evidence", "confirmation_id=" + id));
    }

    @Test
    void currentWarehouseRoleIsRequiredEvenForOriginalExecutedReplay() throws Exception {
        var row = delivered(accepted());
        String id = approved(row), request = key();
        execute(id, request);
        db.update(
                "DELETE FROM fams_account_role WHERE account_id=951003 AND"
                        + " role_code='WAREHOUSE_OPERATOR'");
        assertEquals(
                403,
                call(
                                fixture.warehouse,
                                "POST",
                                "/proxy-confirmations/" + id + "/execute",
                                3,
                                request,
                                null)
                        .statusCode());
        assertEquals(1, count("fams_repair_proxy_return_evidence", "confirmation_id=" + id));
    }

    @Test
    void sharedDepartmentRoleWithdrawalRejectsEvidenceWithoutChangingCustody() throws Exception {
        db.update(
                "UPDATE fams_asset SET"
                    + " custody_type='SHARED',responsible_employee_id=NULL,department_responsible_employee_id=951005"
                    + " WHERE id=951020");
        var row = delivered(accepted());
        String id = approved(row);
        db.update(
                "DELETE FROM fams_account_role WHERE account_id=951005 AND"
                        + " role_code='DEPARTMENT_ASSET_MANAGER'");
        assertEquals(
                409,
                call(
                                fixture.warehouse,
                                "POST",
                                "/proxy-confirmations/" + id + "/execute",
                                3,
                                key(),
                                null)
                        .statusCode());
        assertUnexecuted(id);
    }

    @Test
    void borrowingSnapshotKeepsLargeIdentityExactAndSameLoanRemainsOpenAfterExecution()
            throws Exception {
        borrowing(false);
        db.update("UPDATE fams_loan SET id=9007199254740993 WHERE asset_id=951020");
        var original = db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020");
        var row = delivered(accepted());
        var context = context(row);
        assertTrue(context.path("loan").path("id").isString());
        assertEquals("9007199254740993", context.path("loan").path("id").asText());
        String id = approved(row);
        execute(id, key());
        assertEquals(original, db.queryForMap("SELECT * FROM fams_loan WHERE asset_id=951020"));
        assertEquals("BORROWED", asset().get("lifecycle_status"));
    }

    @Test
    void nativeRepairHandlersUseOpaqueSessionsAndRetryOriginalEvidenceThroughActualHttp()
            throws Exception {
        var row = delivered(accepted());
        String repair = row.path("id").asText();
        String prefix = "repair-proxy-native-" + key();
        for (var entry :
                Map.of("warehouse", 951003L, "admin", 951001L, "recipient", 951002L).entrySet()) {
            db.update(
                    "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES"
                            + " (?,?,NOW(3))",
                    entry.getValue(),
                    miniSessions.identityHash(
                            new MiniWechatIdentityPort.Identity(
                                    "requisition-test-app", prefix + "-" + entry.getKey())));
        }
        var script =
                java.nio.file.Path.of(System.getProperty("user.dir"))
                        .getParent()
                        .resolve("apps/miniprogram/test/runtime-repair-proxy-http.mjs");
        String node = System.getenv("FAMS_TEST_NODE_EXECUTABLE");
        var builder =
                new ProcessBuilder("rtk", "proxy", node == null ? "node" : node, script.toString());
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_PROXY_BUSINESS", "REPAIR");
        builder.environment().put("FAMS_TEST_PROXY_REQUEST_ID", repair);
        builder.environment().put("FAMS_TEST_PROXY_ITEM_ID", "951020");
        for (String role : List.of("warehouse", "admin", "recipient")) {
            builder.environment()
                    .put(
                            "FAMS_TEST_PROXY_" + role.toUpperCase(java.util.Locale.ROOT) + "_CODE",
                            prefix + "-" + role);
        }
        builder.redirectErrorStream(true);
        var process = builder.start();
        assertTrue(process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS));
        String output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertEquals("COMPLETED", detail(repair).path("status").asText());
        assertEquals(1, count("fams_repair_proxy_return_evidence", "repair_id=" + repair));
        assertEquals(
                1,
                count(
                        "fams_business_result_notice",
                        "business_type='REPAIR' AND business_id="
                                + repair
                                + " AND read_at IS NOT NULL"));
        assertEquals(
                1,
                count(
                        "fams_todo",
                        "business_type='REPAIR' AND business_id="
                                + repair
                                + " AND todo_type='PROXY_RECEIPT_REVIEW' AND closed_at IS NULL"));
    }

    @Test
    void actualRecipientRacingApprovedProxyCommitsOnlyOneReceipt() throws Exception {
        var row = delivered(accepted());
        String id = approved(row), repair = row.path("id").asText();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var actual =
                    pool.submit(
                            () -> {
                                start.await();
                                return post(
                                        fixture.employee,
                                        repair + "/return-receipt",
                                        row.path("version").asLong(),
                                        key(),
                                        receipt(row));
                            });
            var proxy =
                    pool.submit(
                            () -> {
                                start.await();
                                return call(
                                        fixture.warehouse,
                                        "POST",
                                        "/proxy-confirmations/" + id + "/execute",
                                        3,
                                        key(),
                                        null);
                            });
            start.countDown();
            var statuses =
                    new ArrayList<>(List.of(actual.get().statusCode(), proxy.get().statusCode()));
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
            assertEquals("COMPLETED", detail(repair).path("status").asText());
            assertEquals(
                    1,
                    count(
                            "fams_asset_timeline_event",
                            "asset_id=951020 AND event_type IN"
                                + " ('REPAIR_RETURN_RECEIVED','REPAIR_PROXY_RETURN_RECEIVED')"));
            assertEquals(
                    0,
                    count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void borrowingExpiryAndTargetVersionDriftInvalidateApprovalWithoutSilentRestoration()
            throws Exception {
        borrowing(false);
        var row = delivered(accepted());
        String id = approved(row);
        var expected =
                db.queryForObject(
                        "SELECT expected_return_date FROM fams_loan WHERE asset_id=951020",
                        java.time.LocalDate.class);
        // Fault injection preserves the original due-date constraint and uses the business date.
        db.update(
                "UPDATE fams_loan SET expected_return_date=original_return_date,version=version+1"
                        + " WHERE asset_id=951020");
        assertEquals(
                409,
                call(
                                fixture.warehouse,
                                "POST",
                                "/proxy-confirmations/" + id + "/execute",
                                3,
                                key(),
                                null)
                        .statusCode());
        assertUnexecuted(id);
        db.update(
                "UPDATE fams_loan SET expected_return_date=?,version=version-1 WHERE"
                        + " asset_id=951020",
                expected);
        db.update("UPDATE fams_location SET version=version+1 WHERE id=951001");
        assertEquals(
                409,
                call(
                                fixture.warehouse,
                                "POST",
                                "/proxy-confirmations/" + id + "/execute",
                                3,
                                key(),
                                null)
                        .statusCode());
        assertUnexecuted(id);
    }

    @Test
    void reassignmentRevokesOldWarehouseAndInvalidatesOldApprovalForItsSuccessor()
            throws Exception {
        var row = delivered(accepted());
        String id = approved(row), repair = row.path("id").asText();
        var context = ok(get(fixture.admin, repair + "/reassignment"));
        var transfer = JSON.createObjectNode();
        transfer.put("processorAccountId", "951004");
        transfer.set("snapshot", context.path("snapshot"));
        transfer.put("unsynchronizedResultsChecked", true);
        transfer.put("reason", "Original operator unavailable after actual dispatch");
        transfer.put(
                "evidence",
                "Checked actual dispatch, pending physical receipt and unsynchronized evidence");
        ok(
                post(
                        fixture.admin,
                        repair + "/reassignment",
                        row.path("version").asLong(),
                        key(),
                        transfer.toString()));
        assertEquals(
                403,
                call(
                                fixture.warehouse,
                                "POST",
                                "/proxy-confirmations/" + id + "/execute",
                                3,
                                key(),
                                null)
                        .statusCode());
        assertEquals(
                409,
                call(
                                fixture.otherWarehouse,
                                "POST",
                                "/proxy-confirmations/" + id + "/execute",
                                3,
                                key(),
                                null)
                        .statusCode());
        assertUnexecuted(id);
    }

    @Test
    void modifiedFrozenSignedBytesCannotExecuteAnOtherwiseCurrentApproval() throws Exception {
        var row = delivered(accepted());
        String id = approved(row);
        String object =
                db.queryForObject(
                        "SELECT object_id FROM fams_attachment WHERE"
                                + " business_type='PROXY_CONFIRMATION' AND business_id=?",
                        String.class,
                        id);
        // Replace the test-owned read-only blob to simulate storage tampering.
        java.nio.file.Files.delete(RepairHttpRuntimeCase.photoRoot.resolve(object));
        java.nio.file.Files.writeString(
                RepairHttpRuntimeCase.photoRoot.resolve(object),
                "%PDF-1.7 changed signed receipt bytes");
        assertEquals(
                409,
                call(
                                fixture.warehouse,
                                "POST",
                                "/proxy-confirmations/" + id + "/execute",
                                3,
                                key(),
                                null)
                        .statusCode());
        assertUnexecuted(id);
    }

    @Test
    void sharedActiveResponsibleManagerCanReceiveWithDisabledLoginAndRetainsSharedCustody()
            throws Exception {
        db.update(
                "UPDATE fams_asset SET"
                    + " custody_type='SHARED',responsible_employee_id=NULL,department_responsible_employee_id=951005"
                    + " WHERE id=951020");
        var row = delivered(accepted());
        db.update(
                "UPDATE fams_system_account SET status='DISABLED',auth_epoch=auth_epoch+1 WHERE"
                        + " id=951005");
        execute(approved(row), key());
        assertEquals("SHARED", asset().get("custody_type"));
        assertEquals(null, asset().get("responsible_employee_id"));
        assertEquals(
                951005L, ((Number) asset().get("department_responsible_employee_id")).longValue());
        assertEquals(1, count("fams_business_result_notice", "recipient_employee_id=951005"));
    }

    @Test
    void approvedChangedTargetReceivesTheResultInsteadOfTheDepartedOriginalEmployee()
            throws Exception {
        var row = accepted();
        String repair = row.path("id").asText();
        var original = row.path("source").deepCopy();
        db.update("UPDATE fams_employee SET status='DISABLED',version=version+1 WHERE id=951002");
        var proposed = proposal(row);
        ok(
                targetPost(
                        fixture.admin,
                        proposed.path("id").asText() + "/decision",
                        0,
                        key(),
                        "{\"result\":\"APPROVED\",\"reason\":\"Approve verified active replacement"
                                + " responsibility\"}"));
        row = delivered(detail(repair));
        db.update(
                "UPDATE fams_system_account SET status='DISABLED',auth_epoch=auth_epoch+1 WHERE"
                        + " id=951006");
        String id = approved(row);
        execute(id, key());
        assertEquals("IN_USE", asset().get("lifecycle_status"));
        assertEquals(951006L, ((Number) asset().get("responsible_employee_id")).longValue());
        assertEquals(original, detail(repair).path("source"));
        assertEquals(
                1,
                count(
                        "fams_business_result_notice",
                        "execution_id=" + id + " AND recipient_employee_id=951006"));
        assertEquals(
                0,
                count(
                        "fams_business_result_notice",
                        "execution_id=" + id + " AND recipient_employee_id=951002"));
        assertEquals(
                951006L,
                db.queryForObject(
                        "SELECT actual_signer_employee_id FROM fams_repair_proxy_return_evidence"
                                + " WHERE confirmation_id=?",
                        Long.class,
                        id));
    }

    @Test
    void legacyJvmReceiptOrdersReplayIdenticallyButChangedOrExpiredRequestsDoNot()
            throws Exception {
        var row = delivered(accepted());
        String id = approved(row), request = key();
        var result = execute(id, request);
        // Simulate records persisted under each observed legacy JVM map seed.
        for (String raw :
                List.of("{\"id\":" + id + ",\"version\":3}", "{\"version\":3,\"id\":" + id + "}")) {
            assertEquals(
                    1,
                    db.update(
                            "UPDATE fams_idempotency_record SET payload_sha256=SHA2(?,256) WHERE"
                                + " actor_account_id=951003 AND"
                                + " operation_id='executeProxyConfirmation' AND idempotency_key=?",
                            raw,
                            request));
            assertEquals(result, execute(id, request));
            assertEquals(
                    409,
                    call(
                                    fixture.warehouse,
                                    "POST",
                                    "/proxy-confirmations/" + id + "/execute",
                                    4,
                                    request,
                                    null)
                            .statusCode());
        }
        db.update(
                "UPDATE fams_idempotency_record SET expires_at=DATE_SUB(NOW(3),INTERVAL 1 SECOND)"
                    + " WHERE actor_account_id=951003 AND operation_id='executeProxyConfirmation'"
                    + " AND idempotency_key=?",
                request);
        assertEquals(
                409,
                call(
                                fixture.warehouse,
                                "POST",
                                "/proxy-confirmations/" + id + "/execute",
                                3,
                                request,
                                null)
                        .statusCode());
        assertEquals(1, count("fams_repair_proxy_return_evidence", "confirmation_id=" + id));
        assertEquals(1, count("fams_business_result_notice", "execution_id=" + id));
        assertEquals(
                1,
                count(
                        "fams_asset_timeline_event",
                        "asset_id=951020 AND event_type='REPAIR_PROXY_RETURN_RECEIVED'"));
    }

    private String contextPath(JsonNode row) {
        return "context?businessId=" + row.path("id").asText() + "&itemId=951020";
    }

    private ObjectNode target(long employeeVersion) {
        var target = JSON.createObjectNode();
        target.put("custodyType", "PERSONAL");
        target.put("organizationId", "951000");
        target.put("employeeId", "951006");
        target.put("locationId", "951001");
        target.put("organizationVersion", 0);
        target.put("employeeVersion", employeeVersion);
        target.put("locationVersion", 0);
        return target;
    }

    private JsonNode proposal(JsonNode row) throws Exception {
        return proposal(row, target(0));
    }

    private JsonNode proposal(JsonNode row, JsonNode target) throws Exception {
        var snapshot = ok(targetGet(fixture.warehouse, contextPath(row)));
        return ok(
                targetPost(
                        fixture.warehouse,
                        "",
                        row.path("version").asLong(),
                        key(),
                        proposalBody(row, snapshot, target)));
    }

    private String proposalBody(JsonNode row, JsonNode snapshot, JsonNode target) {
        var body = JSON.createObjectNode();
        body.put("businessId", row.path("id").asText());
        body.put("itemId", "951020");
        body.set("snapshot", snapshot);
        body.set("target", target);
        body.put(
                "reason",
                "Original target no longer suitable; request explicitly approved actual new"
                        + " responsibility");
        body.put("observedAssetCode", row.path("source").path("assetCode").asText());
        body.put(
                "physicalVerification",
                "Actually checked the asset and verified the intended physical receiving person and"
                        + " place");
        body.put("physicalChecked", true);
        return body.toString();
    }

    private HttpResponse<String> targetGet(Session actor, String path) throws Exception {
        return targetApi(actor, "GET", path, 0, null, null);
    }

    private HttpResponse<String> targetPost(
            Session actor, String path, long version, String key, String body) throws Exception {
        return targetApi(actor, "POST", path, version, key, body);
    }

    private HttpResponse<String> targetApi(
            Session actor, String method, String path, long version, String key, String body)
            throws Exception {
        String separator = path.contains("?") ? "&" : "?";
        var builder =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/api/v1/item-target-changes"
                                                + (path.isEmpty() || path.startsWith("?")
                                                        ? path
                                                        : "/" + path)
                                                + separator
                                                + "businessType=REPAIR"))
                        .header("Cookie", actor.cookie());
        if (!method.equals("GET")) {
            builder.header("X-CSRF-Token", actor.csrf())
                    .header("Idempotency-Key", key)
                    .header("If-Match-Version", Long.toString(version))
                    .header("Content-Type", "application/json");
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

    private void assertUnexecuted(String id) {
        assertEquals("UNDER_REPAIR", asset().get("lifecycle_status"));
        assertEquals(
                1, count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
        assertEquals(0, count("fams_repair_proxy_return_evidence", "confirmation_id=" + id));
        assertEquals(0, count("fams_business_result_notice", "execution_id=" + id));
        assertEquals(
                "APPROVED",
                db.queryForObject(
                        "SELECT confirmation_status FROM fams_proxy_confirmation WHERE id=?",
                        String.class,
                        id));
    }

    private JsonNode context(JsonNode row) throws Exception {
        return ok(
                call(
                        fixture.warehouse,
                        "GET",
                        "/proxy-confirmations/context?businessType=REPAIR&businessId="
                                + row.path("id").asText()
                                + "&itemId=951020",
                        0,
                        null,
                        null));
    }

    private String approved(JsonNode row) throws Exception {
        var snapshot = context(row);
        var body = JSON.createObjectNode();
        body.put("businessId", row.path("id").asText());
        body.put("itemId", "951020");
        body.set("snapshot", snapshot);
        body.put("observedAssetCode", snapshot.path("assetCode").asText());
        body.put("actualSigner", "Actual original recipient");
        body.put("actualSignerEmployeeId", snapshot.path("targetEmployeeId").asText());
        body.put("actualReceivedAt", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        body.put("cannotConfirmReason", "Recipient has no usable online login");
        body.put("contactRecord", "Directly contacted actual recipient and checked signed receipt");
        body.put("delegatedReceipt", false);
        body.put("evidenceKind", "SIGNED_RECEIPT");
        body.put("collectionMethod", "CODE_CONFIRMED");
        body.put("targetOrAuthorizedProxyReceived", true);
        String id =
                ok(call(
                                fixture.warehouse,
                                "POST",
                                "/proxy-confirmations",
                                0,
                                key(),
                                body.toString()))
                        .path("id")
                        .asText();
        String boundary = "owned-repair-proxy-proof";
        String data =
                "--"
                        + boundary
                        + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\";"
                        + " filename=\"signed-repair.pdf\"\r\n"
                        + "Content-Type: application/pdf\r\n\r\n"
                        + "%PDF-1.7 actual signed repair receipt\r\n"
                        + "--"
                        + boundary
                        + "--\r\n";
        var uploaded =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + port
                                                        + "/api/v1/proxy-confirmations/"
                                                        + id
                                                        + "/attachments"))
                                .header("Cookie", fixture.warehouse.cookie())
                                .header("X-CSRF-Token", fixture.warehouse.csrf())
                                .header("Idempotency-Key", key())
                                .header("If-Match-Version", "0")
                                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                                .POST(HttpRequest.BodyPublishers.ofString(data))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(201, uploaded.statusCode(), uploaded.body());
        ok(
                call(
                        fixture.warehouse,
                        "POST",
                        "/proxy-confirmations/" + id + "/submit",
                        1,
                        key(),
                        null));
        ok(
                call(
                        fixture.admin,
                        "POST",
                        "/proxy-confirmations/" + id + "/decision",
                        2,
                        key(),
                        "{\"approved\":true,\"reason\":\"Actual signed receipt and authorization"
                            + " checked\",\"physicalEvidenceChecked\":true,\"noDisputeChecked\":true}"));
        return id;
    }

    private JsonNode execute(String id, String request) throws Exception {
        return ok(
                call(
                        fixture.warehouse,
                        "POST",
                        "/proxy-confirmations/" + id + "/execute",
                        3,
                        request,
                        null));
    }

    private HttpResponse<String> call(
            Session actor, String method, String path, long version, String request, String body)
            throws Exception {
        var b =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                        .header("Cookie", actor.cookie());
        if (method.equals("POST")) {
            b.header("X-CSRF-Token", actor.csrf())
                    .header("Idempotency-Key", request)
                    .header("If-Match-Version", Long.toString(version));
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        return http.send(
                b.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
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
