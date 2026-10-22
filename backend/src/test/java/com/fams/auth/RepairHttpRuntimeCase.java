package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Actual HTTP/security, transactional MySQL and stored image bytes; scanner is an explicit fixture.
 */
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
@EnabledIfSystemProperty(named = "d6.19.repair.runtime", matches = "true")
class RepairHttpRuntimeCase {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PASSWORD = "owned-repair-19-password";
    static java.nio.file.Path photoRoot;
    final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcTemplate db;
    @Autowired MiniSessionService miniSessions;
    Map<String, Object> parameters;
    Session admin, employee, warehouse, otherWarehouse, other, system;

    record Session(String cookie, String csrf) {}

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        var s = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        r.add("spring.datasource.url", s::url);
        r.add("spring.datasource.username", s::username);
        r.add("spring.datasource.password", s::password);
        if (photoRoot == null) {
            try {
                photoRoot = java.nio.file.Files.createTempDirectory("fams-repair-photos-");
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
        r.add("fams.attachments.quarantine-root", () -> photoRoot.toString());
        r.add("fams.test.proxy-evidence", () -> "true");
    }

    @BeforeEach
    void seed() throws Exception {
        parameters =
                db.queryForMap(
                        "SELECT current_version,current_version_id FROM"
                                + " fams_business_parameter_state WHERE singleton_id=1");
        for (long id : new long[] {951000, 951099}) {
            db.update(
                    "INSERT INTO"
                        + " fams_organization(id,code,code_normalized,name,status,created_at,updated_at)"
                        + " VALUES (?,?,?,'Repair organization','ACTIVE',NOW(3),NOW(3))",
                    id,
                    "R" + id,
                    "R" + id);
        }
        for (String table : List.of("fams_asset_category", "fams_warehouse")) {
            db.update(
                    "INSERT INTO "
                            + table
                            + "(id,code,code_normalized,name,status,created_at,updated_at) VALUES"
                            + " (951000,'REPAIROWN','REPAIROWN','Repair"
                            + " fixture','ACTIVE',NOW(3),NOW(3))");
        }
        db.update(
                "INSERT INTO"
                    + " fams_location(id,code,code_normalized,name,location_type,warehouse_id,status,created_at,updated_at)"
                    + " VALUES (951000,'REPAIRBIN','REPAIRBIN','Original"
                    + " bin','BIN',951000,'ACTIVE',NOW(3),NOW(3)),(951001,'REPAIRROOM','REPAIRROOM','Original"
                    + " room','ROOM',NULL,'ACTIVE',NOW(3),NOW(3))");
        String[] roles = {
            "ASSET_ADMIN",
            "EMPLOYEE",
            "WAREHOUSE_OPERATOR",
            "WAREHOUSE_OPERATOR",
            "DEPARTMENT_ASSET_MANAGER",
            "EMPLOYEE",
            "EMPLOYEE",
            "SYSTEM_ADMIN",
            "TECH_ASSISTANT",
            "ASSET_ADMIN"
        };
        for (int i = 1; i <= roles.length; i++) {
            long id = 951000 + i;
            db.update(
                    "INSERT INTO"
                        + " fams_employee(id,employee_no,employee_no_normalized,name,organization_id,status,created_at,updated_at)"
                        + " VALUES (?,?,?,'Repair person',?,'ACTIVE',NOW(3),NOW(3))",
                    id,
                    "REP" + i,
                    "REP" + i,
                    i == 7 ? 951099 : 951000);
            db.update(
                    "INSERT INTO"
                        + " fams_system_account(id,employee_id,username,username_normalized,password_hash,status,initial_password_required,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,'ENABLED',FALSE,NOW(3),NOW(3))",
                    id,
                    id,
                    "rep" + i,
                    "REP" + i,
                    new BCryptPasswordEncoder(4).encode(PASSWORD));
            db.update(
                    "INSERT INTO fams_account_role(account_id,role_code,granted_at) VALUES"
                            + " (?,?,NOW(3))",
                    id,
                    roles[i - 1]);
        }
        db.update(
                "INSERT INTO"
                    + " fams_business_parameter_version(id,version,status,effective_at,actor_account_id,actor_username,snapshot_json,created_at)"
                    + " VALUES"
                    + " (951000,951000,'ACTIVE',NOW(3),951001,'rep1','{\"timezone\":\"Asia/Shanghai\"}',NOW(3))");
        db.update(
                "UPDATE fams_business_parameter_state SET"
                        + " current_version=951000,current_version_id=951000 WHERE singleton_id=1");
        for (long id : new long[] {951020, 951021, 951022, 951023}) {
            boolean stock = id == 951023, shared = id == 951022;
            db.update(
                    "INSERT INTO"
                        + " fams_asset(id,asset_code,name,category_id,lifecycle_status,exception_status,card_stage,custody_type,storage_warehouse_id,current_location_id,using_organization_id,responsible_employee_id,department_responsible_employee_id,created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by)"
                        + " VALUES (?,?,'Actual repair"
                        + " asset',951000,?,'NORMAL','ACTIVE',?,?,?,?,?,?,CURRENT_DATE,951000,951001,'rep1',NOW(3),NOW(3),951001,951001)",
                    id,
                    "REP-ASSET-" + id,
                    stock ? "IN_STOCK" : "IN_USE",
                    stock ? "STORAGE" : shared ? "SHARED" : "PERSONAL",
                    stock ? 951000 : null,
                    stock ? 951000 : 951001,
                    stock ? null : 951000,
                    stock || shared ? null : id == 951021 ? 951007 : 951002,
                    shared ? 951005 : null);
        }
        admin = login(1);
        employee = login(2);
        warehouse = login(3);
        otherWarehouse = login(4);
        other = login(7);
        system = login(8);
    }

    @AfterEach
    void cleanup() throws Exception {
        db.execute("DROP TRIGGER IF EXISTS owned_repair_audit_failure");
        db.update(
                "DELETE FROM fams_todo WHERE business_type='REPAIR' AND business_id IN (SELECT id"
                        + " FROM fams_repair_request WHERE creator_account_id BETWEEN 951001 AND"
                        + " 951010)");
        db.update("DELETE FROM fams_asset_business_lock WHERE asset_id BETWEEN 951020 AND 951023");
        for (String table :
                List.of(
                        "fams_repair_photo",
                        "fams_repair_receipt",
                        "fams_repair_event",
                        "fams_repair_decision")) {
            db.update(
                    "DELETE FROM "
                            + table
                            + " WHERE repair_id IN (SELECT id FROM fams_repair_request WHERE"
                            + " creator_account_id BETWEEN 951001 AND 951010)");
        }
        db.update(
                "DELETE FROM fams_attachment WHERE business_type='REPAIR' AND uploader_account_id"
                        + " BETWEEN 951001 AND 951010");
        db.update(
                "DELETE FROM fams_attachment_scope WHERE business_type='REPAIR' AND business_id IN"
                    + " (SELECT id FROM fams_repair_request WHERE creator_account_id BETWEEN 951001"
                    + " AND 951010)");
        db.update(
                "DELETE FROM fams_repair_request WHERE creator_account_id BETWEEN 951001 AND"
                        + " 951010");
        db.update("DELETE FROM fams_asset_timeline_event WHERE asset_id BETWEEN 951020 AND 951023");
        db.update("DELETE FROM fams_loan WHERE asset_id BETWEEN 951020 AND 951023");
        db.update(
                "DELETE FROM fams_asset_import_batch WHERE"
                        + " id='00000000-0000-4000-8000-000000009519'");
        db.update("DELETE FROM fams_asset WHERE id BETWEEN 951020 AND 951023");
        db.update(
                "UPDATE fams_business_parameter_state SET current_version=?,current_version_id=?"
                        + " WHERE singleton_id=1",
                parameters.get("current_version"),
                parameters.get("current_version_id"));
        db.update("DELETE FROM fams_business_parameter_version WHERE id=951000");
        for (String table : List.of("fams_mini_session", "fams_mini_binding")) {
            db.update("DELETE FROM " + table + " WHERE account_id BETWEEN 951001 AND 951010");
        }
        db.update("DELETE FROM fams_audit_event WHERE actor_account_id BETWEEN 951001 AND 951010");
        db.update("DELETE FROM fams_security_event WHERE account_id BETWEEN 951001 AND 951010");
        db.update("DELETE FROM fams_account_role WHERE account_id BETWEEN 951001 AND 951010");
        db.update("DELETE FROM fams_system_account WHERE id BETWEEN 951001 AND 951010");
        db.update("DELETE FROM fams_employee WHERE id BETWEEN 951001 AND 951010");
        db.update("DELETE FROM fams_location WHERE id BETWEEN 951000 AND 951001");
        for (String table : List.of("fams_warehouse", "fams_asset_category")) {
            db.update("DELETE FROM " + table + " WHERE id=951000");
        }
        db.update("DELETE FROM fams_organization WHERE id IN (951000,951099)");
        try (var files = java.nio.file.Files.list(photoRoot)) {
            for (var file : files.toList()) {
                java.nio.file.Files.deleteIfExists(file);
            }
        }
    }

    @Test
    void actualInPlaceRepairPreservesCustodyAndOriginalFacts() throws Exception {
        var original =
                db.queryForMap(
                        "SELECT"
                            + " custody_type,using_organization_id,responsible_employee_id,current_location_id,last_inventory_business_date,created_business_date"
                            + " FROM fams_asset WHERE id=951020");
        var row = submit(employee, 951020);
        String id = row.path("id").asText();
        assertTrue(row.path("id").isString());
        assertEquals("IN_USE", lifecycle(951020));
        assertEquals(
                1, count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
        assertEquals(
                403, post(employee, "/" + id + "/decision", decision(), 0, key()).statusCode());
        row = ok(post(admin, "/" + id + "/decision", decision(), 0, key()));
        assertEquals("WAITING_WAREHOUSE_CLAIM", row.path("status").asText());
        assertEquals("IN_USE", lifecycle(951020));
        row = ok(post(warehouse, "/" + id + "/claim", null, 1, key()));
        assertEquals("951003", row.path("processorAccountId").asText());
        row = detail(warehouse, id);
        String accept = physical(row);
        assertEquals(
                409,
                post(
                                otherWarehouse,
                                "/" + id + "/accept-in-place",
                                accept,
                                row.path("version").asLong(),
                                key())
                        .statusCode());
        row =
                ok(
                        post(
                                warehouse,
                                "/" + id + "/accept-in-place",
                                accept,
                                row.path("version").asLong(),
                                key()));
        assertEquals("UNDER_REPAIR", lifecycle(951020));
        assertEquals(
                1, count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
        row = detail(warehouse, id);
        String complete = physical(row), request = key();
        var completed =
                ok(
                        post(
                                warehouse,
                                "/" + id + "/complete-in-place",
                                complete,
                                row.path("version").asLong(),
                                request));
        assertEquals("COMPLETED", completed.path("status").asText());
        assertEquals(
                completed,
                ok(
                        post(
                                warehouse,
                                "/" + id + "/complete-in-place",
                                complete,
                                row.path("version").asLong(),
                                request)));
        assertEquals("IN_USE", lifecycle(951020));
        assertEquals(
                0, count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
        assertEquals(
                0,
                count(
                        "fams_todo",
                        "business_type='REPAIR' AND business_id="
                                + id
                                + " AND todo_status='OPEN'"));
        assertEquals(
                original,
                db.queryForMap(
                        "SELECT"
                            + " custody_type,using_organization_id,responsible_employee_id,current_location_id,last_inventory_business_date,created_business_date"
                            + " FROM fams_asset WHERE id=951020"));
        var persisted = detail(admin, id);
        assertFalse(persisted.path("actions").path("completeInPlace").asBoolean());
        assertTrue(persisted.path("history").get(0).path("detail_json").isObject());
        assertEquals(6, persisted.path("historyTotal").asInt());
        assertEquals(
                1,
                count(
                        "fams_repair_event",
                        "repair_id=" + id + " AND action='REPAIR_CONFLICT_REJECTED'"));
    }

    @Test
    void reportingScopeBoundsAndUniqueAssetLockAreReal() throws Exception {
        assertEquals(403, post(employee, "", submission(951021), 0, key()).statusCode());
        assertEquals(403, post(system, "", submission(951020), 0, key()).statusCode());
        var shared = submit(login(6), 951022);
        assertEquals("951022", shared.path("assetId").asText());
        assertEquals(403, post(other, "", submission(951022), 0, key()).statusCode());
        assertEquals(400, get(admin, "?pageSize=101").statusCode());
        assertEquals(400, get(admin, "/eligible?page=1000001").statusCode());
        String request = key(), body = submission(951020);
        var first = ok(post(employee, "", body, 0, request));
        assertEquals(first, ok(post(employee, "", body, 0, request)));
        assertEquals(
                409,
                post(employee, "", body.replace("Actual fault", "Different fault"), 0, request)
                        .statusCode());
        assertEquals(409, post(employee, "", body, 0, key()).statusCode());
        assertEquals(1, count("fams_repair_request", "asset_id=951020"));
    }

    @Test
    void rejectionAndCancellationReleaseOnlyOwnLock() throws Exception {
        var row = submit(employee, 951020);
        String id = row.path("id").asText();
        assertEquals(403, get(warehouse, "/" + id).statusCode());
        var rejected =
                ok(
                        post(
                                admin,
                                "/" + id + "/decision",
                                "{\"result\":\"REJECT\",\"reason\":\"Actual invalid fault"
                                        + " report\"}",
                                0,
                                key()));
        assertEquals("REJECTED", rejected.path("status").asText());
        assertEquals("IN_USE", lifecycle(951020));
        row = submit(employee, 951020);
        id = row.path("id").asText();
        ok(
                post(
                        employee,
                        "/" + id + "/cancel",
                        "{\"reason\":\"Actual report withdrawn\"}",
                        0,
                        key()));
        assertEquals(
                0, count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
        assertEquals(
                2, count("fams_repair_event", "action IN ('REPAIR_DECIDED','REPAIR_CANCELLED')"));
    }

    @Test
    void currentAdminRoleIsRequiredBeforePermanentOriginalReply() throws Exception {
        var row = submit(employee, 951020);
        String id = row.path("id").asText(), request = key();
        ok(post(admin, "/" + id + "/decision", decision(), 0, request));
        db.update(
                "DELETE FROM fams_account_role WHERE account_id=951001 AND"
                        + " role_code='ASSET_ADMIN'");
        db.update(
                "UPDATE fams_system_account SET authorization_version=authorization_version+1 WHERE"
                        + " id=951001");
        assertTrue(
                List.of(401, 403)
                        .contains(
                                post(admin, "/" + id + "/decision", decision(), 0, request)
                                        .statusCode()));
        assertEquals(1, count("fams_repair_decision", "repair_id=" + id));
    }

    @Test
    void actualWarehouseClaimRaceHasOneOwnerAndAuditedLoser() throws Exception {
        String id = submit(employee, 951020).path("id").asText();
        ok(post(admin, "/" + id + "/decision", decision(), 0, key()));
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new java.util.concurrent.CountDownLatch(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            var a =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return post(warehouse, "/" + id + "/claim", null, 1, key());
                            });
            var b =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return post(otherWarehouse, "/" + id + "/claim", null, 1, key());
                            });
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            var statuses = new ArrayList<>(List.of(a.get().statusCode(), b.get().statusCode()));
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
        }
        assertEquals(
                1, count("fams_repair_event", "repair_id=" + id + " AND action='REPAIR_CLAIMED'"));
        assertEquals(
                1,
                count(
                        "fams_repair_event",
                        "repair_id=" + id + " AND action='REPAIR_CONFLICT_REJECTED'"));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT ownership_version FROM fams_repair_request WHERE id=?",
                        Integer.class,
                        id));
    }

    @Test
    void centralAuditFailureRollsBackAssetRepairAndReceiptTogether() throws Exception {
        String id = submit(employee, 951020).path("id").asText();
        ok(post(admin, "/" + id + "/decision", decision(), 0, key()));
        ok(post(warehouse, "/" + id + "/claim", null, 1, key()));
        var row = detail(warehouse, id);
        String request = key(), body = physical(row);
        db.execute(
                "CREATE TRIGGER owned_repair_audit_failure BEFORE INSERT ON fams_audit_event FOR"
                        + " EACH ROW BEGIN IF NEW.action='REPAIR_ACCEPTED_IN_PLACE' THEN SIGNAL"
                        + " SQLSTATE '45000' SET MESSAGE_TEXT='owned repair audit failure'; END IF;"
                        + " END");
        try {
            assertEquals(
                    500,
                    post(
                                    warehouse,
                                    "/" + id + "/accept-in-place",
                                    body,
                                    row.path("version").asLong(),
                                    request)
                            .statusCode());
            assertEquals("IN_USE", lifecycle(951020));
            assertEquals(0, count("fams_repair_receipt", "request_id='" + request + "'"));
            assertEquals(0, count("fams_asset_timeline_event", "asset_id=951020"));
        } finally {
            db.execute("DROP TRIGGER owned_repair_audit_failure");
        }
        ok(
                post(
                        warehouse,
                        "/" + id + "/accept-in-place",
                        body,
                        row.path("version").asLong(),
                        request));
        assertEquals("UNDER_REPAIR", lifecycle(951020));
    }

    @Test
    void inactiveReturnTargetCannotSilentlyReleaseRepair() throws Exception {
        String id = submit(employee, 951020).path("id").asText();
        ok(post(admin, "/" + id + "/decision", decision(), 0, key()));
        ok(post(warehouse, "/" + id + "/claim", null, 1, key()));
        var row = detail(warehouse, id);
        ok(
                post(
                        warehouse,
                        "/" + id + "/accept-in-place",
                        physical(row),
                        row.path("version").asLong(),
                        key()));
        row = detail(warehouse, id);
        db.update("UPDATE fams_location SET status='DISABLED' WHERE id=951001");
        var failed =
                post(
                        warehouse,
                        "/" + id + "/complete-in-place",
                        physical(row),
                        row.path("version").asLong(),
                        key());
        assertEquals(409, failed.statusCode());
        assertEquals(
                "REPAIR_RETURN_TARGET_INACTIVE",
                JSON.readTree(failed.body()).path("code").asText());
        assertEquals("UNDER_REPAIR", lifecycle(951020));
        assertEquals(
                1, count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
    }

    @Test
    void realMultipartPhotoFreezesApprovalEvidenceAndRetainsExactBytes() throws Exception {
        String id = submit(employee, 951020).path("id").asText();
        byte[] bytes =
                Base64.getDecoder()
                        .decode(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+afo0AAAAASUVORK5CYII=");
        String request = key();
        var upload = ok(upload(employee, id, 0, request, bytes));
        assertEquals(upload, ok(upload(employee, id, 0, request, bytes)));
        String object = upload.path("objectId").asText();
        var row = detail(employee, id);
        assertEquals("image/png", row.path("photos").get(0).path("contentType").asText());
        var grant =
                ok(
                        post(
                                employee,
                                "/" + id + "/photos/" + object + "/download-grant",
                                null,
                                0,
                                key()));
        var content =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + port
                                                        + grant.path("url").asText()))
                                .header("Cookie", employee.cookie())
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, content.statusCode());
        assertArrayEquals(bytes, content.body());
        assertTrue(
                content.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        ok(post(admin, "/" + id + "/decision", decision(), row.path("version").asLong(), key()));
        assertEquals(upload, ok(upload(employee, id, 0, request, bytes)));
        assertEquals(
                409,
                upload(employee, id, row.path("version").asLong() + 1, key(), bytes).statusCode());
        assertEquals(1, count("fams_repair_photo", "repair_id=" + id));
        assertTrue(
                db.queryForObject(
                                "SELECT approval_snapshot_json FROM fams_repair_request WHERE id=?",
                                String.class,
                                id)
                        .contains(object));
        assertEquals(403, get(other, "/" + id).statusCode());
    }

    @Test
    void opaqueMiniSessionReadsSameActualRepairWithCurrentBinding() throws Exception {
        String id = submit(employee, 951020).path("id").asText();
        var identity =
                new MiniWechatIdentityPort.Identity("owned-repair-app", "actual-repair-employee");
        db.update(
                "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES"
                        + " (951002,?,NOW(3))",
                miniSessions.identityHash(identity));
        var session = miniSessions.start(identity, UUID.randomUUID());
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:" + port + "/api/v1/mini/repairs/" + id))
                        .header("X-FAMS-Mini-Session", session.sessionToken())
                        .GET()
                        .build();
        assertEquals(
                id,
                ok(http.send(request, HttpResponse.BodyHandlers.ofString())).path("id").asText());
        db.update(
                "UPDATE fams_mini_binding SET revoked_at=NOW(3),version=version+1"
                        + " WHERE account_id=951002");
        assertTrue(
                List.of(401, 403)
                        .contains(
                                http.send(request, HttpResponse.BodyHandlers.ofString())
                                        .statusCode()));
    }

    @Test
    void activeBorrowingRepairPreservesEntireOriginalLoanAndDates() throws Exception {
        borrowing(false);
        var before = db.queryForList("SELECT * FROM fams_loan WHERE asset_id=951020");
        String id = submit(employee, 951020).path("id").asText();
        ok(post(admin, "/" + id + "/decision", decision(), 0, key()));
        ok(post(warehouse, "/" + id + "/claim", null, 1, key()));
        var row = detail(warehouse, id);
        ok(
                post(
                        warehouse,
                        "/" + id + "/accept-in-place",
                        physical(row),
                        row.path("version").asLong(),
                        key()));
        row = detail(warehouse, id);
        ok(
                post(
                        warehouse,
                        "/" + id + "/complete-in-place",
                        physical(row),
                        row.path("version").asLong(),
                        key()));
        assertEquals("BORROWED", lifecycle(951020));
        assertEquals(before, db.queryForList("SELECT * FROM fams_loan WHERE asset_id=951020"));
    }

    @Test
    void expiredBorrowingRequiresActualInboundAndNeverSilentlyRenewsOrCloses() throws Exception {
        borrowing(true);
        var before = db.queryForList("SELECT * FROM fams_loan WHERE asset_id=951020");
        String id = submit(employee, 951020).path("id").asText();
        ok(post(admin, "/" + id + "/decision", decision(), 0, key()));
        ok(post(warehouse, "/" + id + "/claim", null, 1, key()));
        var row = detail(warehouse, id);
        var rejected =
                post(
                        warehouse,
                        "/" + id + "/accept-in-place",
                        physical(row),
                        row.path("version").asLong(),
                        key());
        assertEquals(409, rejected.statusCode());
        assertEquals(
                "REPAIR_BORROWING_EXPIRED_INBOUND_REQUIRED",
                JSON.readTree(rejected.body()).path("code").asText());
        assertEquals(before, db.queryForList("SELECT * FROM fams_loan WHERE asset_id=951020"));
        assertEquals("BORROWED", lifecycle(951020));
        assertEquals(
                1, count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
    }

    @Test
    void twoActualAdministratorsRaceToOneImmutableApproval() throws Exception {
        String id = submit(employee, 951020).path("id").asText();
        var second = login(10);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new java.util.concurrent.CountDownLatch(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            var a =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return post(admin, "/" + id + "/decision", decision(), 0, key());
                            });
            var b =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return post(second, "/" + id + "/decision", decision(), 0, key());
                            });
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            var statuses = new ArrayList<>(List.of(a.get().statusCode(), b.get().statusCode()));
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
        }
        assertEquals(1, count("fams_repair_decision", "repair_id=" + id));
        assertEquals(
                1,
                count(
                        "fams_repair_event",
                        "repair_id=" + id + " AND action='REPAIR_CONFLICT_REJECTED'"));
    }

    private void borrowing(boolean expired) {
        db.update(
                "INSERT INTO"
                    + " fams_asset_import_batch(id,actor_account_id,upload_key,source_sha256,source_bytes,source_xlsx,created_at,updated_at)"
                    + " VALUES"
                    + " ('00000000-0000-4000-8000-000000009519',951001,'owned-initial-loan',SHA2('owned"
                    + " initial bytes',256),1,X'01',NOW(3),NOW(3))");
        db.update("UPDATE fams_asset SET lifecycle_status='BORROWED' WHERE id=951020");
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        var borrowed = today.minusDays(30);
        var due = expired ? today.minusDays(1) : today.plusDays(10);
        db.update(
                "INSERT INTO"
                    + " fams_loan(asset_id,borrower_employee_id,using_organization_id,source_type,initial_batch_id,initial_sheet_row,borrowed_date,original_return_date,expected_return_date,parameter_snapshot_json,created_at,updated_at)"
                    + " VALUES"
                    + " (951020,951002,951000,'INITIAL_IMPORT','00000000-0000-4000-8000-000000009519',2,?,?,?,'{}',NOW(3),NOW(3))",
                borrowed,
                due,
                due);
    }

    @Test
    void actualNativeHandlersRetryCommittedSubmissionAndCompletePhysicalRepair() throws Exception {
        var grants = new LinkedHashMap<String, Object>();
        for (var entry :
                Map.of("employee", 951002L, "admin", 951001L, "warehouse", 951003L).entrySet()) {
            var identity =
                    new MiniWechatIdentityPort.Identity("owned-repair-native", entry.getKey());
            db.update(
                    "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES (?,"
                        + " ?,NOW(3))",
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
                        .resolve("apps/miniprogram/test/runtime-repair-http.mjs");
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
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        var output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("\"nativeActualRepairClosed\":true"), output);
        assertEquals("IN_USE", lifecycle(951020));
        assertEquals(
                1, count("fams_repair_request", "asset_id=951020 AND repair_status='COMPLETED'"));
        assertEquals(
                0, count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
        assertEquals(
                5,
                count(
                        "fams_repair_event",
                        "repair_id IN (SELECT id FROM fams_repair_request WHERE asset_id=951020)"));
    }

    @Test
    void invalidApprovalPhysicalEvidenceAndStaleVersionsCannotAdvanceRepair() throws Exception {
        String id = submit(employee, 951020).path("id").asText();
        assertEquals(
                400,
                post(
                                admin,
                                "/" + id + "/decision",
                                decision().replace("INTERNAL_NO_COST", "FACTORY"),
                                0,
                                key())
                        .statusCode());
        assertEquals(409, post(admin, "/" + id + "/decision", decision(), 9, key()).statusCode());
        ok(post(admin, "/" + id + "/decision", decision(), 0, key()));
        assertEquals(
                409,
                post(
                                employee,
                                "/" + id + "/cancel",
                                "{\"reason\":\"Cannot cancel approved scope\"}",
                                1,
                                key())
                        .statusCode());
        ok(post(warehouse, "/" + id + "/claim", null, 1, key()));
        var row = detail(warehouse, id);
        String body = physical(row);
        assertEquals(
                409, post(warehouse, "/" + id + "/complete-in-place", body, 2, key()).statusCode());
        assertEquals(
                409,
                post(
                                warehouse,
                                "/" + id + "/accept-in-place",
                                body.replace(
                                        "\"neverLeftCustodian\":true",
                                        "\"neverLeftCustodian\":false"),
                                2,
                                key())
                        .statusCode());
        var wrong = (tools.jackson.databind.node.ObjectNode) JSON.readTree(body);
        wrong.put("ownershipVersion", 999);
        assertEquals(
                409,
                post(
                                warehouse,
                                "/" + id + "/accept-in-place",
                                JSON.writeValueAsString(wrong),
                                2,
                                key())
                        .statusCode());
        wrong = (tools.jackson.databind.node.ObjectNode) JSON.readTree(body);
        wrong.put("lockVersion", 999);
        assertEquals(
                409,
                post(
                                warehouse,
                                "/" + id + "/accept-in-place",
                                JSON.writeValueAsString(wrong),
                                2,
                                key())
                        .statusCode());
        wrong = (tools.jackson.databind.node.ObjectNode) JSON.readTree(body);
        wrong.put("observedCode", "WRONG-ACTUAL-CODE");
        assertEquals(
                409,
                post(
                                warehouse,
                                "/" + id + "/accept-in-place",
                                JSON.writeValueAsString(wrong),
                                2,
                                key())
                        .statusCode());
        assertEquals("IN_USE", lifecycle(951020));
        assertEquals(2, detail(warehouse, id).path("version").asInt());
    }

    private JsonNode submit(Session actor, long asset) throws Exception {
        return ok(post(actor, "", submission(asset), 0, key()));
    }

    private String submission(long asset) {
        return JSON.writeValueAsString(
                Map.of(
                        "assetId",
                        Long.toString(asset),
                        "assetVersion",
                        0,
                        "fault",
                        "Actual fault prevents startup",
                        "impact",
                        "HIGH",
                        "occurredAt",
                        Instant.now()
                                .minusSeconds(60)
                                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                                .toString()));
    }

    private String decision() {
        return "{\"result\":\"APPROVE\",\"allowedMethod\":\"INTERNAL_NO_COST\",\"reason\":\"Actual"
                + " fault checked for no cost internal repair\"}";
    }

    private JsonNode detail(Session actor, String id) throws Exception {
        return ok(get(actor, "/" + id));
    }

    private String physical(JsonNode row) {
        return JSON.writeValueAsString(
                Map.of(
                        "assetVersion",
                        row.path("assetVersion").asLong(),
                        "ownershipVersion",
                        row.path("ownershipVersion").asLong(),
                        "lockVersion",
                        row.path("lockVersion").asLong(),
                        "observedCode",
                        row.path("source").path("assetCode").asText(),
                        "actualLocationId",
                        row.path("source").path("locationId").asText(),
                        "neverLeftCustodian",
                        true,
                        "evidence",
                        "Actual physical check and repair evidence recorded at original location"));
    }

    private String lifecycle(long asset) {
        return db.queryForObject(
                "SELECT lifecycle_status FROM fams_asset WHERE id=?", String.class, asset);
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
                                                                PASSWORD))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        var body = ok(response);
        return new Session(
                response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                body.path("csrfToken").asText());
    }

    private HttpResponse<String> get(Session s, String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + port + "/api/v1/repairs" + path))
                        .header("Cookie", s.cookie())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Session s, String path, String body, long version, String key)
            throws Exception {
        return http.send(
                HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + port + "/api/v1/repairs" + path))
                        .header("Cookie", s.cookie())
                        .header("X-CSRF-Token", s.csrf())
                        .header("Idempotency-Key", key)
                        .header("If-Match-Version", Long.toString(version))
                        .header("Content-Type", "application/json")
                        .POST(
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(
            Session s, String id, long version, String key, byte[] bytes) throws Exception {
        String boundary = "repair-" + key();
        byte[] prefix =
                ("--"
                                + boundary
                                + "\r\n"
                                + "Content-Disposition: form-data; name=\"file\";"
                                + " filename=\"actual.png\"\r\n"
                                + "Content-Type: image/png\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var data = new java.io.ByteArrayOutputStream();
        data.write(prefix);
        data.write(bytes);
        data.write(
                ("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return http.send(
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/api/v1/repairs/"
                                                + id
                                                + "/photos"))
                        .header("Cookie", s.cookie())
                        .header("X-CSRF-Token", s.csrf())
                        .header("Idempotency-Key", key)
                        .header("If-Match-Version", Long.toString(version))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(data.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
