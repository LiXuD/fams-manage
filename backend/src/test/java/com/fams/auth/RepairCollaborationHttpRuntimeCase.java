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
@EnabledIfSystemProperty(named = "d6.19.repair.collaboration.runtime", matches = "true")
class RepairCollaborationHttpRuntimeCase {
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
        db.update(
                "DELETE FROM fams_todo WHERE (business_type='REPAIR_TECH' AND business_id IN"
                    + " (SELECT id FROM fams_repair_invitation WHERE repair_id IN (SELECT id FROM"
                    + " fams_repair_request WHERE creator_account_id BETWEEN 951001 AND 951010)))"
                    + " OR (business_type='REPAIR_OPINION' AND business_id IN (SELECT id FROM"
                    + " fams_repair_opinion WHERE repair_id IN (SELECT id FROM fams_repair_request"
                    + " WHERE creator_account_id BETWEEN 951001 AND 951010)))");
        db.update(
                "DELETE FROM fams_repair_collaboration_attachment WHERE invitation_id IN (SELECT id"
                    + " FROM fams_repair_invitation WHERE repair_id IN (SELECT id FROM"
                    + " fams_repair_request WHERE creator_account_id BETWEEN 951001 AND 951010))");
        db.update(
                "DELETE FROM fams_repair_opinion WHERE repair_id IN (SELECT id FROM"
                    + " fams_repair_request WHERE creator_account_id BETWEEN 951001 AND 951010)");
        db.update(
                "DELETE FROM fams_attachment WHERE business_type='REPAIR_TECH' AND"
                        + " uploader_account_id BETWEEN 951001 AND 951010");
        db.update(
                "DELETE FROM fams_attachment_scope WHERE business_type='REPAIR_TECH' AND"
                    + " business_id IN (SELECT id FROM fams_repair_invitation WHERE repair_id IN"
                    + " (SELECT id FROM fams_repair_request WHERE creator_account_id BETWEEN 951001"
                    + " AND 951010))");
        db.update(
                "DELETE FROM fams_repair_invitation WHERE repair_id IN (SELECT id FROM"
                    + " fams_repair_request WHERE creator_account_id BETWEEN 951001 AND 951010)");
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
    void onlyInvitedTechnicianSeesMinimalFaultAndNeverLegacyStaffScopes() throws Exception {
        String repair = submit(employee, 951020).path("id").asText();
        var tech = login(9);
        var invitation = invite(repair, 0);
        String id = invitation.path("invitationId").asText();
        var detail = ok(api(tech, "GET", "/repair-collaborations/" + id, null, 0, null));
        assertEquals(repair, detail.path("repairId").asText());
        assertEquals("REP-ASSET-951020", detail.path("asset").path("assetCode").asText());
        assertFalse(detail.has("source"));
        assertFalse(detail.has("creatorAccountId"));
        assertFalse(detail.toString().contains("responsibleEmployeeId"));
        assertFalse(detail.toString().contains("expectedReturnDate"));
        assertFalse(detail.toString().contains("processorAccountId"));
        for (String path :
                List.of(
                        "/repairs",
                        "/repairs/" + repair,
                        "/repairs/eligible",
                        "/inventory-tasks",
                        "/loans",
                        "/transfers")) {
            assertEquals(403, api(tech, "GET", path, null, 0, null).statusCode(), path);
        }
        db.update(
                "INSERT INTO fams_account_role(account_id,role_code,granted_at) VALUES"
                        + " (951007,'TECH_ASSISTANT',NOW(3))");
        var uninvited = login(7);
        assertEquals(
                403,
                api(uninvited, "GET", "/repair-collaborations/" + id, null, 0, null).statusCode());
    }

    @Test
    void malformedAttachmentListsAreRejectedWithoutWritingAnOpinion() throws Exception {
        String repair = submit(employee, 951020).path("id").asText(),
                id = invite(repair, 0).path("invitationId").asText();
        var tech = login(9);
        for (boolean nullList : new boolean[] {true, false}) {
            var body =
                    (tools.jackson.databind.node.ObjectNode) JSON.readTree(opinion("NEEDS_WORK"));
            if (nullList) {
                body.putNull("attachmentIds");
            } else {
                body.putArray("attachmentIds").addNull();
            }
            assertEquals(
                    400,
                    api(
                                    tech,
                                    "POST",
                                    "/repair-collaborations/" + id + "/opinions",
                                    body.toString(),
                                    0,
                                    key())
                            .statusCode());
        }
        assertEquals(0, count("fams_repair_opinion", "invitation_id=" + id));
        assertEquals(1, detail(admin, repair).path("version").asLong());
    }

    @Test
    void opinionAppendsWithoutClosingRepairAndApprovalFreezesOnlyExistingOpinions()
            throws Exception {
        String repair = submit(employee, 951020).path("id").asText(),
                id = invite(repair, 0).path("invitationId").asText();
        var tech = login(9);
        String request = key(), body = opinion("RESTORED");
        var first =
                ok(
                        api(
                                tech,
                                "POST",
                                "/repair-collaborations/" + id + "/opinions",
                                body,
                                0,
                                request));
        assertEquals(
                first,
                ok(
                        api(
                                tech,
                                "POST",
                                "/repair-collaborations/" + id + "/opinions",
                                body,
                                0,
                                request)));
        assertEquals(
                409,
                api(
                                tech,
                                "POST",
                                "/repair-collaborations/" + id + "/opinions",
                                opinion("NEEDS_WORK"),
                                0,
                                request)
                        .statusCode());
        assertEquals("IN_USE", lifecycle(951020));
        assertEquals("PENDING_ADMIN_DECISION", detail(admin, repair).path("status").asText());
        assertEquals(
                1, count("fams_asset_business_lock", "asset_id=951020 AND released_at IS NULL"));
        ok(post(admin, "/" + repair + "/decision", decision(), 2, key()));
        String frozen =
                db.queryForObject(
                        "SELECT approval_snapshot_json FROM fams_repair_request WHERE id=?",
                        String.class,
                        repair);
        assertEquals(1, JSON.readTree(frozen).path("technicalOpinions").size());
        ok(
                api(
                        tech,
                        "POST",
                        "/repair-collaborations/" + id + "/opinions",
                        opinion("FACTORY_SUGGESTED"),
                        1,
                        key()));
        assertEquals(
                frozen,
                db.queryForObject(
                        "SELECT approval_snapshot_json FROM fams_repair_request WHERE id=?",
                        String.class,
                        repair));
        assertEquals("WAITING_WAREHOUSE_CLAIM", detail(admin, repair).path("status").asText());
    }

    @Test
    void postApprovalInvitationBelongsOnlyToCurrentWarehouseOwner() throws Exception {
        String repair = submit(employee, 951020).path("id").asText();
        ok(post(admin, "/" + repair + "/decision", decision(), 0, key()));
        assertEquals(
                403,
                api(
                                admin,
                                "POST",
                                "/repairs/" + repair + "/collaborations",
                                invitationBody(),
                                1,
                                key())
                        .statusCode());
        assertEquals(
                403,
                api(
                                warehouse,
                                "POST",
                                "/repairs/" + repair + "/collaborations",
                                invitationBody(),
                                1,
                                key())
                        .statusCode());
        ok(post(warehouse, "/" + repair + "/claim", null, 1, key()));
        assertEquals(
                403,
                api(
                                otherWarehouse,
                                "POST",
                                "/repairs/" + repair + "/collaborations",
                                invitationBody(),
                                2,
                                key())
                        .statusCode());
        ok(
                api(
                        warehouse,
                        "POST",
                        "/repairs/" + repair + "/collaborations",
                        invitationBody(),
                        2,
                        key()));
        assertEquals(
                1,
                count(
                        "fams_repair_invitation",
                        "repair_id=" + repair + " AND invitation_status='ACTIVE'"));
    }

    @Test
    void revocationAndRepairTerminalRejectEvenPermanentOriginalOpinion() throws Exception {
        String repair = submit(employee, 951020).path("id").asText(),
                id = invite(repair, 0).path("invitationId").asText();
        var tech = login(9);
        String request = key(), body = opinion("NEEDS_WORK");
        ok(api(tech, "POST", "/repair-collaborations/" + id + "/opinions", body, 0, request));
        ok(
                api(
                        admin,
                        "POST",
                        "/repair-collaborations/" + id + "/revoke",
                        "{\"reason\":\"Invitation no longer required\"}",
                        2,
                        key()));
        assertEquals(
                403, api(tech, "GET", "/repair-collaborations/" + id, null, 0, null).statusCode());
        assertEquals(
                403,
                api(tech, "POST", "/repair-collaborations/" + id + "/opinions", body, 0, request)
                        .statusCode());
        String next = invite(repair, 3).path("invitationId").asText();
        ok(
                post(
                        employee,
                        "/" + repair + "/cancel",
                        "{\"reason\":\"Original report cancelled before decision\"}",
                        4,
                        key()));
        assertEquals(
                403,
                api(tech, "GET", "/repair-collaborations/" + next, null, 0, null).statusCode());
        assertEquals(
                1,
                count("fams_repair_invitation", "id=" + next + " AND invitation_status='CLOSED'"));
        assertEquals(
                0,
                count(
                        "fams_todo",
                        "business_type='REPAIR_TECH' AND business_id="
                                + next
                                + " AND todo_status='OPEN'"));
    }

    @Test
    void currentTechnologyRoleIsRequiredBeforeOriginalOpinionReply() throws Exception {
        String repair = submit(employee, 951020).path("id").asText(),
                id = invite(repair, 0).path("invitationId").asText();
        var tech = login(9);
        String request = key(), body = opinion("NEEDS_WORK");
        ok(api(tech, "POST", "/repair-collaborations/" + id + "/opinions", body, 0, request));
        db.update(
                "DELETE FROM fams_account_role WHERE account_id=951009 AND"
                        + " role_code='TECH_ASSISTANT'");
        db.update(
                "UPDATE fams_system_account SET authorization_version=authorization_version+1 WHERE"
                        + " id=951009");
        assertTrue(
                List.of(401, 403)
                        .contains(
                                api(
                                                tech,
                                                "POST",
                                                "/repair-collaborations/" + id + "/opinions",
                                                body,
                                                0,
                                                request)
                                        .statusCode()));
        assertEquals(1, count("fams_repair_opinion", "invitation_id=" + id));
    }

    @Test
    void auditFailureRollsBackOpinionVersionTodoAndPermanentReceipt() throws Exception {
        String repair = submit(employee, 951020).path("id").asText(),
                id = invite(repair, 0).path("invitationId").asText();
        var tech = login(9);
        String request = key(), body = opinion("NEEDS_WORK");
        db.execute(
                "CREATE TRIGGER owned_repair_audit_failure BEFORE INSERT ON fams_audit_event FOR"
                    + " EACH ROW BEGIN IF NEW.action='REPAIR_TECH_OPINION_ADDED' THEN SIGNAL"
                    + " SQLSTATE '45000' SET MESSAGE_TEXT='owned opinion audit failure'; END IF;"
                    + " END");
        try {
            assertEquals(
                    500,
                    api(
                                    tech,
                                    "POST",
                                    "/repair-collaborations/" + id + "/opinions",
                                    body,
                                    0,
                                    request)
                            .statusCode());
            assertEquals(0, count("fams_repair_opinion", "invitation_id=" + id));
            assertEquals(0, count("fams_repair_receipt", "request_id='" + request + "'"));
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT version FROM fams_repair_invitation WHERE id=?",
                            Integer.class,
                            id));
            assertEquals(
                    1,
                    count(
                            "fams_todo",
                            "business_type='REPAIR_TECH' AND business_id="
                                    + id
                                    + " AND todo_status='OPEN'"));
        } finally {
            db.execute("DROP TRIGGER owned_repair_audit_failure");
        }
        ok(api(tech, "POST", "/repair-collaborations/" + id + "/opinions", body, 0, request));
    }

    @Test
    void actualConcurrentAdministratorsCreateOneInvitationAndAuditedLoser() throws Exception {
        String repair = submit(employee, 951020).path("id").asText();
        var second = login(10);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new java.util.concurrent.CountDownLatch(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            var a =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return api(
                                        admin,
                                        "POST",
                                        "/repairs/" + repair + "/collaborations",
                                        invitationBody(),
                                        0,
                                        key());
                            });
            var b =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return api(
                                        second,
                                        "POST",
                                        "/repairs/" + repair + "/collaborations",
                                        invitationBody(),
                                        0,
                                        key());
                            });
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            var statuses = new ArrayList<>(List.of(a.get().statusCode(), b.get().statusCode()));
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
        }
        assertEquals(1, count("fams_repair_invitation", "repair_id=" + repair));
        assertEquals(
                1,
                count(
                        "fams_repair_event",
                        "repair_id=" + repair + " AND action='REPAIR_CONFLICT_REJECTED'"));
    }

    @Test
    void invitedFaultImageAndOwnOpinionAttachmentRequireCurrentAuthorityAndExactScope()
            throws Exception {
        byte[] bytes =
                Base64.getDecoder()
                        .decode(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+afo0AAAAASUVORK5CYII=");
        String repair = submit(employee, 951020).path("id").asText();
        var fault = ok(upload(employee, repair, 0, key(), bytes));
        String id = invite(repair, 1).path("invitationId").asText();
        var tech = login(9);
        var grant =
                ok(
                        api(
                                tech,
                                "POST",
                                "/repair-collaborations/"
                                        + id
                                        + "/fault-photos/"
                                        + fault.path("objectId").asText()
                                        + "/download-grant",
                                null,
                                0,
                                key()));
        assertArrayEquals(bytes, readPrivate(tech, grant).body());
        String request = key();
        var attachment =
                ok(technicalUpload(tech, id, 0, request, bytes, "actual.png", "image/png"));
        assertEquals(
                attachment,
                ok(technicalUpload(tech, id, 0, request, bytes, "actual.png", "image/png")));
        String object = attachment.path("objectId").asText();
        assertEquals(
                404,
                api(
                                admin,
                                "POST",
                                "/repair-collaborations/"
                                        + id
                                        + "/staff-attachments/"
                                        + object
                                        + "/download-grant",
                                null,
                                0,
                                key())
                        .statusCode());
        var body = (tools.jackson.databind.node.ObjectNode) JSON.readTree(opinion("NEEDS_WORK"));
        body.putArray("attachmentIds").add(object);
        ok(
                api(
                        tech,
                        "POST",
                        "/repair-collaborations/" + id + "/opinions",
                        JSON.writeValueAsString(body),
                        1,
                        key()));
        var staffGrant =
                ok(
                        api(
                                admin,
                                "POST",
                                "/repair-collaborations/"
                                        + id
                                        + "/staff-attachments/"
                                        + object
                                        + "/download-grant",
                                null,
                                0,
                                key()));
        assertArrayEquals(bytes, readPrivate(admin, staffGrant).body());
        assertEquals(1, count("fams_repair_collaboration_attachment", "invitation_id=" + id));
        var row = detail(admin, repair);
        ok(
                api(
                        admin,
                        "POST",
                        "/repair-collaborations/" + id + "/revoke",
                        "{\"reason\":\"Revoked actual evidence permission\"}",
                        row.path("version").asLong(),
                        key()));
        assertEquals(403, readPrivate(tech, grant).statusCode());
        assertEquals(
                403,
                technicalUpload(tech, id, 0, request, bytes, "actual.png", "image/png")
                        .statusCode());
    }

    @Test
    void otherInvitedTechnicianReadsOnlyOfficialSameRepairOpinionEvidence() throws Exception {
        String repair = submit(employee, 951020).path("id").asText(),
                id = invite(repair, 0).path("invitationId").asText();
        var tech = login(9);
        db.update(
                "INSERT INTO fams_account_role(account_id,role_code,granted_at) VALUES"
                        + " (951007,'TECH_ASSISTANT',NOW(3))");
        var secondTech = login(7);
        String second =
                ok(api(
                                admin,
                                "POST",
                                "/repairs/" + repair + "/collaborations",
                                invitationBody().replace("951009", "951007"),
                                1,
                                key()))
                        .path("invitationId")
                        .asText();
        byte[] pdf =
                "%PDF-1.7\nOwned opinion fixture\n%%EOF"
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        String object =
                ok(technicalUpload(tech, id, 0, key(), pdf, "opinion.pdf", "application/pdf"))
                        .path("objectId")
                        .asText();
        String grantPath =
                "/repair-collaborations/"
                        + second
                        + "/opinion-attachments/"
                        + object
                        + "/download-grant";
        assertEquals(404, api(secondTech, "POST", grantPath, null, 0, key()).statusCode());
        assertEquals(
                404,
                api(
                                secondTech,
                                "POST",
                                "/repair-collaborations/"
                                        + second
                                        + "/attachments/"
                                        + object
                                        + "/download-grant",
                                null,
                                0,
                                key())
                        .statusCode());
        var body = (tools.jackson.databind.node.ObjectNode) JSON.readTree(opinion("NEEDS_WORK"));
        body.putArray("attachmentIds").add(object);
        ok(
                api(
                        tech,
                        "POST",
                        "/repair-collaborations/" + id + "/opinions",
                        JSON.writeValueAsString(body),
                        1,
                        key()));
        var grant = ok(api(secondTech, "POST", grantPath, null, 0, key()));
        var content = readPrivate(secondTech, grant);
        assertEquals(200, content.statusCode());
        assertArrayEquals(pdf, content.body());
        assertTrue(
                content.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        String foreignRepair = submit(employee, 951022).path("id").asText(),
                foreign = invite(foreignRepair, 0).path("invitationId").asText();
        assertEquals(
                404,
                api(
                                tech,
                                "POST",
                                "/repair-collaborations/"
                                        + foreign
                                        + "/opinion-attachments/"
                                        + object
                                        + "/download-grant",
                                null,
                                0,
                                key())
                        .statusCode());
    }

    @Test
    void invalidImageMetadataLeavesNoRegisteredOpinionAttachment() throws Exception {
        String repair = submit(employee, 951020).path("id").asText(),
                id = invite(repair, 0).path("invitationId").asText();
        var tech = login(9);
        byte[] pdf =
                "%PDF-1.7\nOwned wrong image".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals(
                400,
                technicalUpload(tech, id, 0, key(), pdf, "wrong.png", "image/png").statusCode());
        assertEquals(0, count("fams_repair_collaboration_attachment", "invitation_id=" + id));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT version FROM fams_repair_invitation WHERE id=?",
                        Integer.class,
                        id));
        try (var files = java.nio.file.Files.list(photoRoot)) {
            assertEquals(0, files.count());
        }
    }

    private HttpResponse<byte[]> readPrivate(Session actor, JsonNode grant) throws Exception {
        return http.send(
                HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + port + grant.path("url").asText()))
                        .header("Cookie", actor.cookie())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> technicalUpload(
            Session actor,
            String invitation,
            long version,
            String request,
            byte[] bytes,
            String filename,
            String mime)
            throws Exception {
        String boundary = "owned-tech-" + key();
        var data = new java.io.ByteArrayOutputStream();
        data.write(
                ("--"
                                + boundary
                                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                                + filename
                                + "\"\r\nContent-Type: "
                                + mime
                                + "\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        data.write(bytes);
        data.write(
                ("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return http.send(
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/api/v1/repair-collaborations/"
                                                + invitation
                                                + "/attachments"))
                        .header("Cookie", actor.cookie())
                        .header("X-CSRF-Token", actor.csrf())
                        .header("Idempotency-Key", request)
                        .header("If-Match-Version", Long.toString(version))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(data.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String invitationBody() {
        return "{\"technicianAccountId\":\"951009\",\"reason\":\"Please assess actual fault with"
                + " minimum necessary evidence\"}";
    }

    private JsonNode invite(String repair, long version) throws Exception {
        return ok(
                api(
                        admin,
                        "POST",
                        "/repairs/" + repair + "/collaborations",
                        invitationBody(),
                        version,
                        key()));
    }

    private String opinion(String result) {
        return JSON.writeValueAsString(
                Map.of(
                        "judgement",
                        "Actual noninvasive fault assessment",
                        "method",
                        "Check actual power connection without opening the device",
                        "result",
                        result,
                        "occurredAt",
                        Instant.now()
                                .minusSeconds(60)
                                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                                .toString(),
                        "attachmentIds",
                        List.of()));
    }

    private HttpResponse<String> api(
            Session actor, String method, String path, String body, long version, String key)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                        .header("Cookie", actor.cookie());
        if (!"GET".equals(method)) {
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
