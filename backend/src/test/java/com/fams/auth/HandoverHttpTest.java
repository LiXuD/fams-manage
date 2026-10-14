package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
            "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra"
        })
@EnabledIfSystemProperty(named = "d6.12.handover.runtime", matches = "true")
class HandoverHttpTest {
    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final AtomicLong FIXTURE = new AtomicLong(924000);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private static java.nio.file.Path proxyRoot;

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcTemplate jdbc;
    @Autowired MiniSessionService miniSessions;
    private long base;
    private Long extraTargetEmployee;
    private Session employee;
    private Session warehouse;
    private Session other;
    private Session admin;
    private Session department;

    private record ProxyScanPause(java.util.concurrent.CountDownLatch entered,
            java.util.concurrent.CountDownLatch release) {}
    private static final java.util.concurrent.atomic.AtomicReference<ProxyScanPause> PROXY_SCAN_PAUSE =
            new java.util.concurrent.atomic.AtomicReference<>();

    @org.springframework.boot.test.context.TestConfiguration
    static class MiniConfiguration {
        @org.springframework.context.annotation.Bean
        MiniWechatIdentityPort requisitionWechatFixture() {
            return code -> new MiniWechatIdentityPort.Identity("requisition-test-app", code);
        }
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="fams.test.proxy-evidence")
        com.fams.attachment.MalwareScanner proxyEvidenceScannerFixture() {
            return content -> {
                var pause = PROXY_SCAN_PAUSE.get();
                if (pause != null) {
                    pause.entered().countDown();
                    try {
                        if (!pause.release().await(20, TimeUnit.SECONDS)) { return com.fams.attachment.MalwareScanner.Result.UNAVAILABLE; }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return com.fams.attachment.MalwareScanner.Result.UNAVAILABLE;
                    }
                }
                return com.fams.attachment.MalwareScanner.Result.CLEAN;
            };
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        registry.add("spring.datasource.url", settings::url);
        registry.add("spring.datasource.username", settings::username);
        registry.add("spring.datasource.password", settings::password);
        if (Boolean.getBoolean("d6.15.proxy.runtime")) {
            try { proxyRoot=java.nio.file.Files.createTempDirectory("fams-d615-proxy-owned-"); }
            catch(java.io.IOException error) { throw new IllegalStateException(error); }
            registry.add("fams.attachments.quarantine-root", () -> proxyRoot.toString());
            registry.add("fams.test.proxy-evidence", () -> "true");
        }
    }

    @BeforeEach
    void seedOwnedFixturesAndAuthenticate() throws Exception {
        base = FIXTURE.addAndGet(100);
        Timestamp now = Timestamp.from(Instant.now());
        for (long id : new long[] {base, base + 1}) {
            jdbc.update(
                    "INSERT INTO fams_organization"
                            + " (id,code,code_normalized,name,status,created_at,updated_at) VALUES"
                            + " (?,?,?,?,'ACTIVE',?,?)",
                    id,
                    "RQ" + id,
                    "RQ" + id,
                    "Organization " + id,
                    now,
                    now);
        }
        jdbc.update(
                "INSERT INTO fams_asset_category"
                        + " (id,code,code_normalized,name,status,created_at,updated_at) VALUES"
                        + " (?,?,?,'Computer','ACTIVE',?,?)",
                base,
                "RQ" + base,
                "RQ" + base,
                now,
                now);
        jdbc.update(
                "INSERT INTO fams_warehouse"
                        + " (id,code,code_normalized,name,status,created_at,updated_at) VALUES"
                        + " (?,?,?,'Warehouse','ACTIVE',?,?)",
                base,
                "RQ" + base,
                "RQ" + base,
                now,
                now);
        jdbc.update(
                "INSERT INTO fams_location"
                    + " (id,code,code_normalized,name,location_type,warehouse_id,status,created_at,updated_at)"
                    + " VALUES (?,?,?,'Office','ROOM',?,'ACTIVE',?,?)",
                base,
                "RQ" + base,
                "RQ" + base,
                base,
                now,
                now);
        String[] roles = {
            "EMPLOYEE",
            "WAREHOUSE_OPERATOR",
            "WAREHOUSE_OPERATOR",
            "ASSET_ADMIN",
            "DEPARTMENT_ASSET_MANAGER",
            "EMPLOYEE",
            "SYSTEM_ADMIN",
            "TECH_ASSISTANT"
        };
        for (int i = 0; i < roles.length; i++) {
            long id = base + 10 + i;
            jdbc.update(
                    "INSERT INTO fams_employee"
                        + " (id,employee_no,employee_no_normalized,name,organization_id,status,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,'ACTIVE',?,?)",
                    id,
                    "RQ" + id,
                    "RQ" + id,
                    "Person " + i,
                    i == 5 ? base + 1 : base,
                    now,
                    now);
            jdbc.update(
                    "INSERT INTO fams_system_account"
                        + " (id,employee_id,username,username_normalized,password_hash,status,initial_password_required,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,'ENABLED',FALSE,?,?)",
                    id,
                    id,
                    "rq" + id,
                    "RQ" + id,
                    new BCryptPasswordEncoder(4).encode(PASSWORD),
                    now,
                    now);
            jdbc.update(
                    "INSERT INTO fams_account_role (account_id,role_code,granted_at) VALUES"
                            + " (?,?,?)",
                    id,
                    roles[i],
                    now);
        }
        jdbc.update(
                "INSERT INTO fams_business_parameter_version"
                    + " (id,version,status,effective_at,actor_account_id,actor_username,snapshot_json,created_at)"
                    + " VALUES"
                    + " (?,?,'ACTIVE',?,?,'fixture','{\"timezone\":\"Asia/Shanghai\",\"handoverAcceptanceDeadlineNaturalDays\":3}',?)",
                base,
                base,
                now,
                base + 13,
                now);
        jdbc.update(
                "UPDATE fams_business_parameter_state SET current_version=?,current_version_id=?"
                        + " WHERE singleton_id=1",
                base,
                base);
        for (int i = 0; i < 4; i++) {
            jdbc.update(
                    "INSERT INTO fams_asset"
                        + " (id,asset_code,name,category_id,lifecycle_status,exception_status,card_stage,custody_type,"
                        + "storage_warehouse_id,current_location_id,created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by)"
                        + " VALUES (?,?,'Test"
                        + " Computer',?,'IN_STOCK','NORMAL','ACTIVE','STORAGE',?,?,'2026-10-07',?,?,'fixture',?,?,?,?)",
                    base + 50 + i,
                    "RQ-ASSET-" + (base + i),
                    base,
                    base,
                    base,
                    base,
                    base + 11,
                    now,
                    now,
                    base + 11,
                    base + 11);
        }
        employee = login(base + 10);
        warehouse = login(base + 11);
        other = login(base + 12);
        admin = login(base + 13);
        department = login(base + 14);
    }

    @AfterEach
    void removeOnlyOwnedExactFixtureRange() {
        jdbc.update("DELETE t FROM fams_todo t JOIN fams_item_target_change c ON t.business_id=c.id WHERE t.business_type='ITEM_TARGET_CHANGE' AND c.creator_account_id BETWEEN ? AND ?", base+10, base+17);
        jdbc.update("DELETE FROM fams_item_target_change WHERE creator_account_id BETWEEN ? AND ?", base+10, base+17);
        if (base == 0) {
            return;
        }
        var proxyObjects=jdbc.queryForList("SELECT object_id FROM fams_attachment WHERE business_type='PROXY_CONFIRMATION' AND uploader_account_id BETWEEN ? AND ?",base+10,base+17);
        for(var object:proxyObjects) {
            String id=object.get("object_id").toString();
            jdbc.update("DELETE FROM fams_attachment_revision WHERE object_id=? OR previous_object_id=?",id,id);
            jdbc.update("DELETE FROM fams_attachment_upload_key WHERE object_id=?",id);
            jdbc.update("DELETE FROM fams_attachment WHERE object_id=?",id);
            jdbc.update("DELETE FROM fams_attachment_object_guard WHERE object_id=?",id);
            if(proxyRoot!=null) { try { java.nio.file.Files.deleteIfExists(proxyRoot.resolve(id)); }
                catch(java.io.IOException error) { throw new IllegalStateException(error); } }
        }
        jdbc.update("DELETE s FROM fams_attachment_scope s JOIN fams_proxy_confirmation p ON p.id=s.business_id WHERE s.business_type='PROXY_CONFIRMATION' AND p.creator_account_id BETWEEN ? AND ?",base+10,base+17);
        jdbc.update("DELETE FROM fams_business_result_notice WHERE recipient_employee_id BETWEEN ? AND ?",base+10,base+17);
        jdbc.update("DELETE FROM fams_proxy_confirmation WHERE creator_account_id BETWEEN ? AND ? ORDER BY id DESC",base+10,base+17);
        jdbc.update(
                "DELETE g FROM fams_transfer_receipt_grant g JOIN fams_transfer_item i ON"
                    + " i.id=g.item_id JOIN fams_transfer t ON t.id=i.request_id WHERE"
                    + " t.creator_account_id BETWEEN ? AND ?",
                base + 10,
                base + 17);
        jdbc.update("DELETE e FROM fams_transfer_event e JOIN fams_transfer t ON t.id=e.request_id"
                + " WHERE t.creator_account_id BETWEEN ? AND ?",base+10,base+17);
        jdbc.update(
                "DELETE l FROM fams_asset_business_lock l JOIN fams_transfer t ON"
                    + " t.id=l.business_id WHERE l.business_type='TRANSFER' AND"
                    + " t.creator_account_id BETWEEN ? AND ?",
                base + 10,
                base + 17);
        jdbc.update(
                "DELETE d FROM fams_todo d JOIN fams_transfer t ON t.id=d.business_id WHERE"
                    + " d.business_type='TRANSFER' AND t.creator_account_id BETWEEN ? AND ?",
                base + 10,
                base + 17);
        jdbc.update("DELETE i FROM fams_transfer_item i JOIN fams_transfer t ON t.id=i.request_id"
                + " WHERE t.creator_account_id BETWEEN ? AND ?",base+10,base+17);
        jdbc.update(
                "DELETE FROM fams_transfer WHERE creator_account_id BETWEEN ? AND ?",
                base + 10,
                base + 17);

        if (base == 0) {
            return;
        }
        jdbc.update("DELETE t FROM fams_todo t JOIN fams_return_followup f ON t.business_id=f.id"
                + " WHERE t.business_type IN ('RETURN_REPAIR','RETURN_LOST_REPORT') AND f.asset_id BETWEEN ? AND ?", base + 50, base + 53);
        jdbc.update("DELETE l FROM fams_asset_business_lock l JOIN fams_return_followup f ON l.business_id=f.id"
                + " WHERE l.business_type IN ('RETURN_REPAIR','RETURN_LOST_REPORT') AND f.asset_id BETWEEN ? AND ?", base + 50, base + 53);
        jdbc.update("DELETE FROM fams_return_followup WHERE asset_id BETWEEN ? AND ?", base + 50, base + 53);
        jdbc.update("DELETE t FROM fams_todo t JOIN fams_asset_return r ON t.business_id=r.id"
                + " WHERE t.business_type='ASSET_RETURN' AND r.asset_id BETWEEN ? AND ?", base + 50, base + 53);
        jdbc.update("DELETE l FROM fams_asset_business_lock l JOIN fams_asset_return r ON l.business_id=r.id"
                + " WHERE l.business_type='ASSET_RETURN' AND r.asset_id BETWEEN ? AND ?", base + 50, base + 53);
        jdbc.update("DELETE FROM fams_asset_return WHERE asset_id BETWEEN ? AND ?", base + 50, base + 53);
        var ids =
                jdbc.queryForList(
                        "SELECT id FROM fams_requisition WHERE creator_account_id BETWEEN ? AND ?",
                        Long.class,
                        base + 10,
                        base + 17);
        for (long id : ids) {
            jdbc.update(
                    "DELETE FROM fams_requisition_followup WHERE source_request_id=? OR"
                            + " latest_request_id=?",
                    id,
                    id);
        }
        for (long id : ids) {
            jdbc.update(
                    "UPDATE fams_requisition SET"
                        + " source_request_id=NULL,source_demand_id=NULL,source_reason_snapshot_json=NULL"
                        + " WHERE id=?",
                    id);
        }
        for (long id : ids) {
            jdbc.update("DELETE t FROM fams_todo t JOIN fams_loan_extension e ON t.business_id=e.id"
                    + " LEFT JOIN fams_loan l ON l.id=e.loan_id WHERE t.business_type='LOAN_EXTENSION'"
                    + " AND (e.request_id=? OR l.source_request_id=?)", id, id);
            jdbc.update("DELETE e FROM fams_loan_extension e LEFT JOIN fams_loan l ON l.id=e.loan_id"
                    + " WHERE e.request_id=? OR l.source_request_id=?", id, id);
            jdbc.update("DELETE t FROM fams_todo t JOIN fams_loan l ON l.id=t.business_id WHERE t.business_type='LOAN'"
                    + " AND l.source_request_id=?", id);
            jdbc.update("DELETE r FROM fams_loan_reminder r JOIN fams_loan l ON l.id=r.loan_id WHERE l.source_request_id=?", id);
            jdbc.update("DELETE FROM fams_loan WHERE source_request_id=?", id);
            jdbc.update(
                    "DELETE g FROM fams_requisition_receipt_grant g JOIN"
                            + " fams_requisition_allocation a ON a.id=g.allocation_id WHERE"
                            + " a.request_id=?",
                    id);
            jdbc.update(
                    "DELETE e FROM fams_requisition_handover_event e JOIN"
                            + " fams_requisition_allocation a ON a.id=e.allocation_id WHERE"
                            + " a.request_id=?",
                    id);
            jdbc.update(
                    "DELETE h FROM fams_requisition_handover h JOIN fams_requisition_allocation a"
                            + " ON a.id=h.allocation_id WHERE a.request_id=?",
                    id);
            jdbc.update(
                    "DELETE t FROM fams_todo t JOIN fams_requisition_allocation a ON"
                        + " t.business_id=a.id WHERE t.business_type='REQUISITION_ALLOCATION' AND"
                        + " a.request_id=?",
                    id);
            jdbc.update(
                    "UPDATE fams_requisition_allocation SET predecessor_allocation_id=NULL WHERE"
                            + " request_id=?",
                    id);
            for (String table :
                    List.of(
                            "fams_requisition_allocation",
                            "fams_requisition_unfulfillable",
                            "fams_requisition_change_proposal",
                            "fams_requisition_decision",
                            "fams_requisition_ownership",
                            "fams_requisition_demand")) {
                jdbc.update("DELETE FROM " + table + " WHERE request_id=?", id);
            }
            jdbc.update(
                    "DELETE FROM fams_asset_business_lock WHERE business_type='REQUISITION' AND"
                            + " business_id=?",
                    id);
            jdbc.update(
                    "DELETE FROM fams_todo WHERE business_type='REQUISITION' AND business_id=?",
                    id);
            jdbc.update("DELETE FROM fams_requisition WHERE id=?", id);
        }
        jdbc.update(
                "DELETE FROM fams_asset_timeline_event WHERE asset_id BETWEEN ? AND ?",
                base + 50,
                base + 53);
        jdbc.update(
                "DELETE FROM fams_asset_tag WHERE asset_id BETWEEN ? AND ?", base + 50, base + 53);
        jdbc.update("DELETE FROM fams_asset WHERE id BETWEEN ? AND ?", base + 50, base + 53);
        jdbc.update(
                "DELETE FROM fams_mini_session WHERE account_id BETWEEN ? AND ?",
                base + 10,
                base + 17);
        jdbc.update(
                "DELETE FROM fams_mini_binding WHERE account_id BETWEEN ? AND ?",
                base + 10,
                base + 17);
        jdbc.update(
                "DELETE FROM fams_idempotency_record WHERE actor_account_id BETWEEN ? AND ?",
                base + 10,
                base + 17);
        jdbc.update(
                "DELETE FROM fams_audit_event WHERE actor_account_id BETWEEN ? AND ?",
                base + 10,
                base + 17);
        jdbc.update(
                "DELETE FROM fams_security_event WHERE account_id BETWEEN ? AND ?",
                base + 10,
                base + 17);
        jdbc.update(
                "UPDATE fams_business_parameter_state SET current_version_id=NULL,current_version=0"
                        + " WHERE current_version_id=?",
                base);
        jdbc.update("DELETE FROM fams_business_parameter_version WHERE id=?", base);
        jdbc.update(
                "DELETE FROM fams_account_role WHERE account_id BETWEEN ? AND ?",
                base + 10,
                base + 17);
        jdbc.update(
                "DELETE FROM fams_system_account WHERE id BETWEEN ? AND ?", base + 10, base + 17);
        jdbc.update("DELETE FROM fams_employee WHERE id BETWEEN ? AND ?", base + 10, base + 17);
        if (extraTargetEmployee != null) { jdbc.update("DELETE FROM fams_employee WHERE id=?", extraTargetEmployee); }
        jdbc.update("DELETE FROM fams_location WHERE id IN (?,?)", base, base+1);
        jdbc.update("DELETE FROM fams_warehouse WHERE id IN (?,?)", base, base+1);
        jdbc.update("DELETE FROM fams_asset_category WHERE id=?", base);
        jdbc.update("DELETE FROM fams_organization WHERE id IN (?,?)", base, base + 1);
    }

    private JsonNode allocated(int quantity) throws Exception {
        return allocatedDraft(quantity, draft(quantity, base + 10, base));
    }

    private JsonNode allocatedDraft(int quantity, String input) throws Exception {
        var value = submitApprove(create(employee, input));
        value = action(warehouse, value, "/claim", null);
        String line = value.path("demands").get(0).path("id").asText();
        for (int i = 0; i < quantity; i++) {
            value = allocate(value, warehouse, line, base + 50 + i, null);
        }
        return value;
    }

    private String borrowDraft(String dueDate) {
        var body = JSON.readTree(draft(1, base + 10, base));
        var object = (tools.jackson.databind.node.ObjectNode) body;
        object.put("requestType", "BORROW");
        object.put("expectedReturnDate", dueDate);
        return JSON.writeValueAsString(object);
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.fams.loan.LoanReminderService loanReminders;

    @Test
    void overdueRemindersAreIdempotentContinueDuringRepairAndSwitchToLostLiabilityWithoutChangingCustody() throws Exception {
        receivedBorrow();
        long loan = jdbc.queryForObject("SELECT id FROM fams_loan WHERE asset_id=?", Long.class, base + 50);
        jdbc.update("UPDATE fams_loan SET borrowed_date='1999-01-01',original_return_date='2000-01-01',expected_return_date='2000-01-01' WHERE id=?", loan);
        loanReminders.refresh(loan);
        loanReminders.refresh(loan);
        assertEquals(1, count("SELECT COUNT(*) FROM fams_loan_reminder WHERE loan_id=? AND reminder_type='OVERDUE'", loan));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_todo WHERE business_type='LOAN' AND business_id=? AND todo_type='OVERDUE' AND closed_at IS NULL", loan));
        assertEquals(1, jdbc.queryForObject("SELECT version FROM fams_asset WHERE id=?", Long.class, base + 50));
        jdbc.update("UPDATE fams_asset SET lifecycle_status='UNDER_REPAIR' WHERE id=?", base + 50);
        loanReminders.refresh(loan);
        assertEquals(1, count("SELECT COUNT(*) FROM fams_todo WHERE business_type='LOAN' AND business_id=? AND todo_type='OVERDUE' AND closed_at IS NULL", loan));
        jdbc.update("UPDATE fams_asset SET exception_status='LOST' WHERE id=?", base + 50);
        loanReminders.refresh(loan);
        assertEquals(0, count("SELECT COUNT(*) FROM fams_todo WHERE business_type='LOAN' AND business_id=? AND todo_type='OVERDUE' AND closed_at IS NULL", loan));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_todo WHERE business_type='LOAN' AND business_id=? AND todo_type='LOST_LIABILITY' AND closed_at IS NULL", loan));
        var detail = loanCall(employee, "GET", "/" + loan, null, null, null);
        assertTrue(JSON.readTree(detail.body()).path("lostLiability").asBoolean());
        assertTrue(!JSON.readTree(detail.body()).path("overdue").asBoolean());
        jdbc.update("UPDATE fams_asset SET exception_status='NORMAL' WHERE id=?", base + 50);
        loanReminders.refresh(loan);
        assertEquals(1, count("SELECT COUNT(*) FROM fams_todo WHERE business_type='LOAN' AND business_id=? AND todo_type='OVERDUE' AND closed_at IS NULL", loan));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_loan_reminder WHERE loan_id=? AND reminder_type='LOST_LIABILITY'", loan));
    }

    @Test
    void normalBorrowReturnRequiresOneAdministratorAndUniquePhysicalInspectorAndPreservesQuantity() throws Exception {
        var original = receivedBorrow();
        var value = approvedReturn(employee);
        String wrong = returnInspection("NORMAL", "wrong");
        assertEquals(409, returnCall(warehouse, "/" + value.path("id").asText() + "/inspect", wrong, key(), 2L).statusCode());
        String body = returnInspection("NORMAL", "RQ-ASSET-" + base), identity = key();
        String suffix = "/" + value.path("id").asText() + "/inspect";
        assertEquals(403, returnCall(other, suffix, body, key(), 2L).statusCode());
        var response = returnCall(warehouse, suffix, body, identity, 2L);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(response.body(), returnCall(warehouse, suffix, body, identity, 2L).body());
        var asset = jdbc.queryForMap("SELECT * FROM fams_asset WHERE id=?", base + 50);
        assertEquals("IN_STOCK", asset.get("lifecycle_status"));
        assertEquals("STORAGE", asset.get("custody_type"));
        assertEquals(null, asset.get("responsible_employee_id"));
        assertEquals(null, asset.get("using_organization_id"));
        assertEquals("RETURNED", jdbc.queryForObject("SELECT relation_status FROM fams_loan WHERE asset_id=?", String.class, base + 50));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
        assertEquals(1, get(employee, original.path("id").asText()).path("demands").get(0).path("completedQuantity").asInt());
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_timeline_event WHERE asset_id=? AND event_type='RETURN_ACCEPTED'", base + 50));
    }

    @Test
    void businessChoicesAreScopedAndOmittedDecisionCannotRejectPendingRequests() throws Exception {
        var value = allocatedDraft(1, borrowDraft("2098-12-31"));
        var choices = loanCall(employee, "GET", "/borrow-requests", null, null, null);
        assertEquals(200, choices.statusCode(), choices.body());
        assertTrue(choices.body().contains(value.path("id").asText()));
        assertEquals(0, JSON.readTree(loanCall(login(base + 15), "GET", "/borrow-requests", null, null, null).body())
                .path("total").asInt());
        var proposed = loanCall(employee, "POST", "/borrow-requests/" + value.path("id").asText() + "/extensions",
                "{\"expectedReturnDate\":\"2099-12-31\",\"reason\":\"延期交接\"}", key(), value.path("version").asLong());
        assertEquals(200, proposed.statusCode(), proposed.body());
        var extension = JSON.readTree(proposed.body());
        assertEquals(400, loanCall(admin, "POST", "/extensions/" + extension.path("id").asText() + "/decision",
                "{\"reason\":\"missing explicit decision\"}", key(), 0L).statusCode());
        assertEquals("PENDING", jdbc.queryForObject("SELECT extension_status FROM fams_loan_extension WHERE id=?",
                String.class, extension.path("id").asLong()));
        var decision = loanCall(admin, "POST", "/extensions/" + extension.path("id").asText() + "/decision",
                "{\"approved\":true,\"reason\":\"同意延期\"}", key(), 0L);
        assertEquals(200, decision.statusCode(), decision.body());
        value = get(employee, value.path("id").asText());
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        assertEquals(200, call(employee, "POST", path(value, 0, "receive"), receiptBody(value, 0, "RQ-ASSET-" + base),
                key(), value.path("version").asLong()).statusCode());
        var eligible = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/asset-returns/eligible-assets")).header("Cookie", employee.cookie()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, eligible.statusCode(), eligible.body());
        assertEquals(1, JSON.readTree(eligible.body()).path("total").asInt());
        assertEquals(Long.toString(base + 50), JSON.readTree(eligible.body()).path("items").get(0).path("id").asText());
        var submitted = returnCall(employee, "", "{\"assetId\":\"" + (base + 50)
                + "\",\"assetVersion\":1,\"reason\":\"归还\"}", key(), null);
        assertEquals(200, submitted.statusCode(), submitted.body());
        var returned = JSON.readTree(submitted.body());
        assertEquals(400, returnCall(admin, "/" + returned.path("id").asText() + "/decision",
                "{\"reason\":\"missing explicit decision\"}", key(), 0L).statusCode());
        assertEquals("PENDING_APPROVAL", jdbc.queryForObject("SELECT return_status FROM fams_asset_return WHERE id=?",
                String.class, returned.path("id").asLong()));
    }

    @Test
    void damagedBorrowReturnClosesLoanAndAtomicallyTransfersUniqueLockToRepair() throws Exception {
        receivedBorrow();
        var value = approvedReturn(employee);
        var response = returnCall(warehouse, "/" + value.path("id").asText() + "/inspect",
                returnInspection("DAMAGED", "RQ-ASSET-" + base), key(), 2L);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("UNDER_REPAIR", jdbc.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=?", String.class, base + 50));
        assertEquals("RETURNED", jdbc.queryForObject("SELECT relation_status FROM fams_loan WHERE asset_id=?", String.class, base + 50));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_return_followup WHERE asset_id=? AND business_type='REPAIR'", base + 50));
        assertEquals("RETURN_REPAIR", jdbc.queryForObject("SELECT business_type FROM fams_asset_business_lock WHERE asset_id=?"
                + " AND released_at IS NULL", String.class, base + 50));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_timeline_event WHERE asset_id=? AND event_type='RETURN_ACCEPTED'", base + 50));
    }

    @Test
    void missingBorrowReturnRetainsResponsibilityAndTransfersToLostReportWithoutFakeReceipt() throws Exception {
        receivedBorrow();
        var value = approvedReturn(employee);
        var response = returnCall(warehouse, "/" + value.path("id").asText() + "/inspect",
                returnInspection("MISSING", null), key(), 2L);
        assertEquals(200, response.statusCode(), response.body());
        var asset = jdbc.queryForMap("SELECT * FROM fams_asset WHERE id=?", base + 50);
        assertEquals("BORROWED", asset.get("lifecycle_status"));
        assertEquals("SEARCHING", asset.get("exception_status"));
        assertEquals(base + 10, ((Number) asset.get("responsible_employee_id")).longValue());
        assertEquals("OPEN", jdbc.queryForObject("SELECT relation_status FROM fams_loan WHERE asset_id=?", String.class, base + 50));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_return_followup WHERE asset_id=? AND business_type='LOST_REPORT'", base + 50));
        assertEquals("RETURN_LOST_REPORT", jdbc.queryForObject("SELECT business_type FROM fams_asset_business_lock WHERE asset_id=?"
                + " AND released_at IS NULL", String.class, base + 50));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_timeline_event WHERE asset_id=? AND event_type='RETURN_ACCEPTED'", base + 50));
    }

    @Test
    void disabledBorrowerCanBeReturnedByCurrentDepartmentManagerWithOwnIdentity() throws Exception {
        receivedBorrow();
        jdbc.update("UPDATE fams_employee SET status='DISABLED' WHERE id=?", base + 10);
        var value = approvedReturn(department);
        assertEquals(Long.toString(base + 14), value.path("proposedBy").asText());
        assertEquals(Long.toString(base + 10), value.path("responsibleEmployeeId").asText());
        var response = returnCall(warehouse, "/" + value.path("id").asText() + "/inspect",
                returnInspection("NORMAL", "RQ-ASSET-" + base), key(), 2L);
        assertEquals(200, response.statusCode(), response.body());
    }

    private JsonNode receivedBorrow() throws Exception {
        var value = allocatedDraft(1, borrowDraft("2099-12-31"));
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        var response = call(employee, "POST", path(value, 0, "receive"), receiptBody(value, 0, "RQ-ASSET-" + base),
                key(), value.path("version").asLong());
        assertEquals(200, response.statusCode(), response.body());
        return value;
    }

    @Test
    void nativeBorrowReceiptAndM05HandlersPersistOneExtensionAndReturnAfterLostResponses() throws Exception {
        var value = allocatedDraft(1, borrowDraft("2099-12-31"));
        warehouseWrite(value, 0, "deliver", delivery(value, 0));
        String code = "loan-native-" + base;
        jdbc.update("INSERT INTO fams_mini_binding (account_id,identity_hash,created_at) VALUES (?,?,?)",
                base + 10, miniSessions.identityHash(new MiniWechatIdentityPort.Identity("requisition-test-app", code)),
                Timestamp.from(Instant.now()));
        var script = java.nio.file.Path.of(System.getProperty("user.dir")).getParent()
                .resolve("apps/miniprogram/test/runtime-loan-http.mjs");
        String node = System.getenv("FAMS_TEST_NODE_EXECUTABLE");
        var builder = new ProcessBuilder("rtk", "proxy", node == null ? "node" : node, script.toString());
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_WECHAT_CODE", code);
        builder.redirectErrorStream(true);
        var process = builder.start();
        assertTrue(process.waitFor(45, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertEquals("BORROWED", jdbc.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=?", String.class, base + 50));
        long loan = jdbc.queryForObject("SELECT id FROM fams_loan WHERE asset_id=? AND relation_status='OPEN'", Long.class, base + 50);
        assertEquals(1, count("SELECT COUNT(*) FROM fams_loan_extension WHERE loan_id=? AND extension_status='PENDING'", loan));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_return WHERE asset_id=? AND return_status='PENDING_APPROVAL'", base + 50));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND business_type='ASSET_RETURN' AND released_at IS NULL", base + 50));
    }

    @Test
    void loanScopeAndRejectedExtensionKeepDatesAndCustodyUnchanged() throws Exception {
        receivedBorrow();
        long loan = jdbc.queryForObject("SELECT id FROM fams_loan WHERE asset_id=?", Long.class, base + 50);
        for (var actor : List.of(employee, department, admin, warehouse)) {
            var response = loanCall(actor, "GET", "", null, null, null);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(1, JSON.readTree(response.body()).path("total").asInt());
        }
        var stranger = login(base + 15);
        assertEquals(0, JSON.readTree(loanCall(stranger, "GET", "", null, null, null).body()).path("total").asInt());
        String proposal = "{\"expectedReturnDate\":\"2100-12-31\",\"reason\":\"拒绝不得改日期\"}";
        assertEquals(403, loanCall(department, "POST", "/" + loan + "/extensions", proposal, key(), 0L).statusCode());
        assertEquals(409, loanCall(employee, "POST", "/" + loan + "/extensions", proposal, key(), 9L).statusCode());
        assertEquals(400, loanCall(employee, "POST", "/" + loan + "/extensions",
                proposal.replace("2100-12-31", "2099-12-31"), key(), 0L).statusCode());
        String identity = key();
        var created = loanCall(employee, "POST", "/" + loan + "/extensions", proposal, identity, 0L);
        assertEquals(200, created.statusCode(), created.body());
        assertEquals(created.body(), loanCall(employee, "POST", "/" + loan + "/extensions", proposal, identity, 0L).body());
        assertEquals(409, loanCall(employee, "POST", "/" + loan + "/extensions",
                proposal.replace("拒绝不得改日期", "changed"), identity, 0L).statusCode());
        long extension = JSON.readTree(created.body()).path("id").asLong();
        assertEquals(1, JSON.readTree(loanCall(employee, "GET", "/extensions", null, null, null).body()).path("total").asInt());
        assertEquals(1, JSON.readTree(loanCall(admin, "GET", "/extensions", null, null, null).body()).path("total").asInt());
        assertEquals(0, JSON.readTree(loanCall(stranger, "GET", "/extensions", null, null, null).body()).path("total").asInt());
        String rejection = "{\"approved\":false,\"reason\":\"继续按原日期归还\"}";
        String suffix = "/extensions/" + extension + "/decision";
        assertEquals(200, loanCall(admin, "POST", suffix, rejection, key(), 0L).statusCode());
        assertEquals(409, loanCall(admin, "POST", suffix, rejection, key(), 1L).statusCode());
        assertEquals("2099-12-31", jdbc.queryForObject("SELECT expected_return_date FROM fams_loan WHERE id=?",
                java.sql.Date.class, loan).toString());
        assertEquals("BORROWED", jdbc.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=?", String.class, base + 50));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_todo WHERE business_type='LOAN_EXTENSION' AND business_id=? AND closed_at IS NULL", extension));
    }

    @Test
    void closedLostAndChangedLoanTargetsRejectExtensionWithoutCreatingNewFacts() throws Exception {
        receivedBorrow();
        long loan = jdbc.queryForObject("SELECT id FROM fams_loan WHERE asset_id=?", Long.class, base + 50);
        String body = "{\"expectedReturnDate\":\"2100-12-31\",\"reason\":\"边界核验\"}";
        assertEquals(404, loanCall(employee, "GET", "/999999999", null, null, null).statusCode());
        jdbc.update("UPDATE fams_loan SET relation_status='RETURNED',closed_at=NOW(3) WHERE id=?", loan);
        assertEquals(409, loanCall(employee, "POST", "/" + loan + "/extensions", body, key(), 0L).statusCode());
        jdbc.update("UPDATE fams_loan SET relation_status='OPEN',closed_at=NULL WHERE id=?", loan);
        jdbc.update("UPDATE fams_asset SET exception_status='LOST' WHERE id=?", base + 50);
        assertEquals(409, loanCall(employee, "POST", "/" + loan + "/extensions", body, key(), 0L).statusCode());
        jdbc.update("UPDATE fams_asset SET exception_status='NORMAL' WHERE id=?", base + 50);
        var response = loanCall(employee, "POST", "/" + loan + "/extensions", body, key(), 0L);
        assertEquals(200, response.statusCode(), response.body());
        long extension = JSON.readTree(response.body()).path("id").asLong();
        jdbc.update("UPDATE fams_loan SET expected_return_date='2100-01-01' WHERE id=?", loan);
        String suffix = "/extensions/" + extension + "/decision";
        String approve = "{\"approved\":true,\"reason\":\"核验\"}";
        assertEquals(409, loanCall(admin, "POST", suffix, approve, key(), 0L).statusCode());
        assertEquals("PENDING", jdbc.queryForObject("SELECT extension_status FROM fams_loan_extension WHERE id=?", String.class, extension));
        jdbc.update("UPDATE fams_loan SET borrowed_date='1999-01-01',original_return_date='2000-01-01',"
                + "expected_return_date='2000-01-01' WHERE id=?", loan);
        jdbc.update("UPDATE fams_loan_extension SET original_due_date='2000-01-01',requested_due_date='2001-01-01' WHERE id=?", extension);
        assertEquals(409, loanCall(admin, "POST", suffix, approve, key(), 0L).statusCode());
        assertEquals(0, count("SELECT COUNT(*) FROM fams_audit_event WHERE resource_type='LOAN_EXTENSION' AND resource_id=? AND action='LOAN_EXTENSION_APPROVED'", extension));
    }

    @Test
    void rejectedReturnReleasesLockAndReplayNeverChangesBorrowerResponsibility() throws Exception {
        receivedBorrow();
        String body = "{\"assetId\":\"" + (base + 50) + "\",\"assetVersion\":1,\"reason\":\"退还复核\"}";
        assertEquals(403, returnCall(login(base + 15), "", body, key(), null).statusCode());
        assertEquals(409, returnCall(employee, "", body.replace("\"assetVersion\":1", "\"assetVersion\":9"), key(), null).statusCode());
        String identity = key();
        var response = returnCall(employee, "", body, identity, null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(response.body(), returnCall(employee, "", body, identity, null).body());
        assertEquals(409, returnCall(employee, "", body.replace("退还复核", "changed"), identity, null).statusCode());
        long id = JSON.readTree(response.body()).path("id").asLong();
        String suffix = "/" + id;
        assertEquals(403, returnCall(employee, suffix + "/claim", null, key(), 0L).statusCode());
        assertEquals(409, returnCall(warehouse, suffix + "/claim", null, key(), 0L).statusCode());
        assertEquals(403, returnCall(warehouse, suffix + "/inspect", returnInspection("NORMAL", "RQ-ASSET-" + base), key(), 0L).statusCode());
        String reject = "{\"approved\":false,\"reason\":\"继续使用\"}", decisionKey = key();
        var rejected = returnCall(admin, suffix + "/decision", reject, decisionKey, 0L);
        assertEquals(200, rejected.statusCode(), rejected.body());
        assertEquals(rejected.body(), returnCall(admin, suffix + "/decision", reject, decisionKey, 0L).body());
        assertEquals(409, returnCall(admin, suffix + "/decision", reject, key(), 1L).statusCode());
        assertEquals("OPEN", jdbc.queryForObject("SELECT relation_status FROM fams_loan WHERE asset_id=?", String.class, base + 50));
        assertEquals(base + 10, jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 50));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
    }

    @Test
    void physicalReturnRejectsMissingTargetVersionsAndChangedWarehouseWithoutPartialReceipt() throws Exception {
        receivedBorrow();
        var value = approvedReturn(employee);
        String suffix = "/" + value.path("id").asText() + "/inspect";
        String body = returnInspection("NORMAL", "RQ-ASSET-" + base);
        for (String field : List.of("warehouseId", "locationId", "warehouseVersion", "locationVersion", "observedCode")) {
            var invalid = JSON.readTree(body).deepCopy();
            ((tools.jackson.databind.node.ObjectNode) invalid).remove(field);
            var response = returnCall(warehouse, suffix, JSON.writeValueAsString(invalid), key(), 2L);
            assertEquals(400, response.statusCode(), field + ": " + response.body());
        }
        assertEquals(409, returnCall(warehouse, suffix, body.replace("\"lockVersion\":0", "\"lockVersion\":1"), key(), 2L).statusCode());
        assertEquals(409, returnCall(warehouse, suffix, body.replace("\"assetVersion\":1", "\"assetVersion\":2"), key(), 2L).statusCode());
        jdbc.update("UPDATE fams_warehouse SET status='DISABLED' WHERE id=?", base);
        assertEquals(409, returnCall(warehouse, suffix, body, key(), 2L).statusCode());
        jdbc.update("UPDATE fams_warehouse SET status='ACTIVE' WHERE id=?", base);
        jdbc.update("UPDATE fams_location SET status='DISABLED' WHERE id=?", base);
        assertEquals(409, returnCall(warehouse, suffix, body, key(), 2L).statusCode());
        jdbc.update("UPDATE fams_location SET status='ACTIVE',location_type='ROOM' WHERE id=?", base);
        assertEquals(409, returnCall(warehouse, suffix, body, key(), 2L).statusCode());
        jdbc.update("UPDATE fams_location SET location_type='BIN',version=1 WHERE id=?", base);
        assertEquals(409, returnCall(warehouse, suffix, body, key(), 2L).statusCode());
        assertEquals("BORROWED", jdbc.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=?", String.class, base + 50));
        assertEquals("WAREHOUSE_PROCESSING", jdbc.queryForObject("SELECT return_status FROM fams_asset_return WHERE id=?", String.class, value.path("id").asLong()));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_timeline_event WHERE asset_id=? AND event_type='RETURN_ACCEPTED'", base + 50));
    }

    @Test
    void returnReassignmentPreservesCustodyAndLockUntilSuccessorInspects() throws Exception {
        receivedBorrow();
        var value = approvedReturn(employee);
        String id = value.path("id").asText(), suffix = "/" + id + "/reassignment";
        var snapshot = returnReassignmentSnapshot(admin, id);
        String body = returnReassignmentBody(snapshot, base + 12), identity = key();
        assertEquals(403, returnCall(warehouse, suffix, body, key(), 2L).statusCode());
        assertEquals(409, returnCall(admin, suffix, returnReassignmentBody(snapshot, base + 10), key(), 2L).statusCode());
        assertEquals(400, returnCall(admin, suffix, body.replace("true", "false"), key(), 2L).statusCode());
        var response = returnCall(admin, suffix, body, identity, 2L);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(response.body(), returnCall(admin, suffix, body, identity, 2L).body());
        assertEquals(409, returnCall(admin, suffix, body.replace("值班交接", "changed"), identity, 2L).statusCode());
        assertEquals(1, count("SELECT ownership_version FROM fams_asset_return WHERE id=?", id));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_audit_event WHERE action='RETURN_CONTROLLED_REASSIGNMENT' AND resource_id=?", id));
        assertEquals("BORROWED", jdbc.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=?", String.class, base + 50));
        assertEquals(base + 10, jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 50));
        assertEquals("OPEN", jdbc.queryForObject("SELECT relation_status FROM fams_loan WHERE asset_id=?", String.class, base + 50));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
        assertEquals(403, returnCall(warehouse, "/" + id + "/inspect", returnInspection("NORMAL", "RQ-ASSET-" + base), key(), 3L).statusCode());
        response = returnCall(other, "/" + id + "/inspect", returnInspection("NORMAL", "RQ-ASSET-" + base), key(), 3L);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("RETURNED", jdbc.queryForObject("SELECT relation_status FROM fams_loan WHERE asset_id=?", String.class, base + 50));
        assertEquals("IN_STOCK", jdbc.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=?", String.class, base + 50));
    }

    @Test
    void returnReassignmentRejectsDriftAndRollsBackThenTransfersDisabledOwner() throws Exception {
        receivedBorrow();
        var value = approvedReturn(employee);
        String id = value.path("id").asText(), suffix = "/" + id + "/reassignment";
        String body = returnReassignmentBody(returnReassignmentSnapshot(admin, id), base + 12);
        jdbc.update("UPDATE fams_asset_business_lock SET lock_version=1 WHERE business_type='ASSET_RETURN' AND business_id=?", id);
        assertEquals(409, returnCall(admin, suffix, body, key(), 2L).statusCode());
        jdbc.update("UPDATE fams_asset_business_lock SET lock_version=0 WHERE business_type='ASSET_RETURN' AND business_id=?", id);
        String trigger = "return_reassign_fault_" + base, identity = key();
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON fams_audit_event FOR EACH ROW BEGIN IF NEW.action='RETURN_CONTROLLED_REASSIGNMENT' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned rollback fixture'; END IF; END");
        try {
            assertTrue(returnCall(admin, suffix, body, identity, 2L).statusCode() >= 400);
            assertEquals(0, count("SELECT ownership_version FROM fams_asset_return WHERE id=?", id));
            assertEquals(base + 11, jdbc.queryForObject("SELECT processor_account_id FROM fams_asset_return WHERE id=?", Long.class, id));
            assertEquals(2, count("SELECT version FROM fams_asset_return WHERE id=?", id));
            assertEquals(0, count("SELECT COUNT(*) FROM fams_idempotency_record WHERE operation_id='reassignAssetReturn' AND idempotency_key=?", identity));
        } finally { jdbc.execute("DROP TRIGGER " + trigger); }
        jdbc.update("UPDATE fams_system_account SET status='DISABLED' WHERE id=?", base + 11);
        assertEquals(200, returnCall(admin, suffix, body, identity, 2L).statusCode());
    }

    @Test
    void returnReassignmentCurrentSnapshotAndTargetRulesAndCompetitionAreAtomic() throws Exception {
        receivedBorrow();
        var value = approvedReturn(employee);
        String id = value.path("id").asText(), suffix = "/" + id + "/reassignment";
        var snapshot = returnReassignmentSnapshot(admin, id);
        String body = returnReassignmentBody(snapshot, base + 12);
        assertEquals(409, returnCall(admin, suffix, returnReassignmentBody(snapshot, base + 11), key(), 2L).statusCode());
        assertEquals(409, returnCall(admin, suffix, returnReassignmentBody(snapshot, 999999999), key(), 2L).statusCode());
        jdbc.update("UPDATE fams_system_account SET initial_password_required=TRUE WHERE id=?", base + 12);
        assertEquals(409, returnCall(admin, suffix, body, key(), 2L).statusCode());
        jdbc.update("UPDATE fams_system_account SET initial_password_required=FALSE WHERE id=?", base + 12);
        jdbc.update("UPDATE fams_employee SET status='DISABLED' WHERE id=?", base + 12);
        assertEquals(409, returnCall(admin, suffix, body, key(), 2L).statusCode());
        jdbc.update("UPDATE fams_employee SET status='ACTIVE' WHERE id=?", base + 12);
        var wrong = JSON.readTree(body);
        ((tools.jackson.databind.node.ObjectNode) wrong.path("items").get(0)).put("assetVersion", 99);
        assertEquals(409, returnCall(admin, suffix, wrong.toString(), key(), 2L).statusCode());
        ((tools.jackson.databind.node.ObjectNode) wrong.path("items").get(0)).put("id", "999999999");
        assertEquals(409, returnCall(admin, suffix, wrong.toString(), key(), 2L).statusCode());
        assertEquals(409, returnCall(admin, suffix, body, key(), 99L).statusCode());
        var first = CompletableFuture.supplyAsync(() -> uncheckedReturnReassignment(suffix, body));
        var second = CompletableFuture.supplyAsync(() -> uncheckedReturnReassignment(suffix, body));
        assertEquals(List.of(200, 409), List.of(first.get(20, TimeUnit.SECONDS).statusCode(), second.get(20, TimeUnit.SECONDS).statusCode()).stream().sorted().toList());
        assertEquals(1, count("SELECT ownership_version FROM fams_asset_return WHERE id=?", id));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_audit_event WHERE action='RETURN_CONTROLLED_REASSIGNMENT' AND resource_id=?", id));
    }

    private HttpResponse<String> uncheckedReturnReassignment(String suffix, String body) {
        try { return returnCall(admin, suffix, body, key(), 2L); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private JsonNode returnReassignmentSnapshot(Session actor, String id) throws Exception {
        var reply = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/asset-returns/" + id + "/reassignment"))
                .header("Cookie", actor.cookie()).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, reply.statusCode(), reply.body());
        return JSON.readTree(reply.body());
    }

    private String returnReassignmentBody(JsonNode snapshot, long target) {
        var item = snapshot.path("items").get(0);
        return JSON.writeValueAsString(java.util.Map.of("processorAccountId", Long.toString(target),
                "ownershipVersion", snapshot.path("ownershipVersion").asLong(), "reason", "值班交接", "evidence", "核对实物及未同步结果",
                "unsynchronizedResultsChecked", true, "items", List.of(java.util.Map.of("id", item.path("id").asText(),
                "version", item.path("version").asLong(), "status", item.path("status").asText(),
                "assetVersion", item.path("assetVersion").asLong(), "lockVersion", item.path("lockVersion").asLong()))));
    }

    private JsonNode approvedReturn(Session proposer) throws Exception {
        String body = "{\"assetId\":\"" + (base + 50) + "\",\"assetVersion\":1,\"reason\":\"退还与代办原因\"}";
        var response = returnCall(proposer, "", body, key(), null);
        assertEquals(200, response.statusCode(), response.body());
        var value = JSON.readTree(response.body());
        String suffix = "/" + value.path("id").asText();
        assertEquals(403, returnCall(proposer, suffix + "/decision", "{\"approved\":true,\"reason\":\"同意\"}", key(), 0L).statusCode());
        response = returnCall(admin, suffix + "/decision", "{\"approved\":true,\"reason\":\"同意\"}", key(), 0L);
        assertEquals(200, response.statusCode(), response.body());
        response = returnCall(warehouse, suffix + "/claim", null, key(), 1L);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(409, returnCall(other, suffix + "/claim", null, key(), 1L).statusCode());
        return JSON.readTree(response.body());
    }

    private String returnInspection(String result, String observed) {
        jdbc.update("UPDATE fams_location SET location_type='BIN' WHERE id=?", base);
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("result", result);
        body.put("reason", "实物验收说明");
        body.put("assetVersion", 1);
        body.put("lockVersion", 0);
        if (!"MISSING".equals(result)) {
            body.put("observedCode", observed);
            body.put("warehouseId", Long.toString(base));
            body.put("warehouseVersion", 0);
            body.put("locationId", Long.toString(base));
            body.put("locationVersion", 0);
        }
        return JSON.writeValueAsString(body);
    }

    private HttpResponse<String> returnCall(Session session, String suffix, String body, String identity, Long version) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/asset-returns" + suffix))
                .header("Cookie", session.cookie()).header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", identity);
        if (version != null) { builder.header("If-Match-Version", version.toString()); }
        if (body != null) { builder.header("Content-Type", "application/json"); }
        builder.POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void loanExtensionNeedsAdministratorDecisionRetainsOverdueHistoryAndDoesNotChangeAsset()
            throws Exception {
        var value = allocatedDraft(1, borrowDraft("2099-12-31"));
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        assertEquals(200, call(employee, "POST", path(value, 0, "receive"),
                receiptBody(value, 0, "RQ-ASSET-" + base), key(), value.path("version").asLong()).statusCode());
        long loan = jdbc.queryForObject("SELECT id FROM fams_loan WHERE asset_id=?", Long.class, base + 50);
        jdbc.update("UPDATE fams_loan SET borrowed_date='1999-01-01',original_return_date='2000-01-01',"
                + "expected_return_date='2000-01-01' WHERE id=?", loan);
        var detail = loanCall(employee, "GET", "/" + loan, null, null, null);
        assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(JSON.readTree(detail.body()).path("overdue").asBoolean());
        assertEquals(200, loanCall(department, "GET", "/" + loan, null, null, null).statusCode());
        assertEquals(404, loanCall(login(base + 15), "GET", "/" + loan, null, null, null).statusCode());
        var proposal = loanCall(employee, "POST", "/" + loan + "/extensions",
                "{\"expectedReturnDate\":\"2099-12-31\",\"reason\":\"继续使用\"}", key(), 0L);
        assertEquals(200, proposal.statusCode(), proposal.body());
        var extension = JSON.readTree(proposal.body());
        assertEquals("PENDING", extension.path("extensionStatus").asText());
        assertTrue(jdbc.queryForObject("SELECT expected_return_date FROM fams_loan WHERE id=?",
                java.sql.Date.class, loan).toString().equals("2000-01-01"));
        assertEquals(403, loanCall(employee, "POST", "/extensions/" + extension.path("id").asText() + "/decision",
                "{\"approved\":true,\"reason\":\"同意\"}", key(), 0L).statusCode());
        String decisionKey = key(), suffix = "/extensions/" + extension.path("id").asText() + "/decision";
        String body = "{\"approved\":true,\"reason\":\"管理员同意延期\"}";
        var approved = loanCall(admin, "POST", suffix, body, decisionKey, 0L);
        assertEquals(200, approved.statusCode(), approved.body());
        assertEquals(approved.body(), loanCall(admin, "POST", suffix, body, decisionKey, 0L).body());
        var result = JSON.readTree(approved.body());
        assertEquals("2000-01-02", result.path("overdueFrom").asText());
        assertTrue(!result.path("overdueThrough").asText().isEmpty());
        assertEquals("BORROWED", jdbc.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=?",
                String.class, base + 50));
        assertEquals(1, get(employee, value.path("id").asText()).path("demands").get(0).path("completedQuantity").asInt());
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
    }

    private HttpResponse<String> loanCall(Session session, String method, String suffix, String body,
            String identity, Long version) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/loans" + suffix))
                .header("Cookie", session.cookie());
        if (identity != null) {
            builder.header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", identity);
        }
        if (version != null) { builder.header("If-Match-Version", version.toString()); }
        if (body != null) { builder.header("Content-Type", "application/json"); }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void borrowingReceiptCreatesOneOpenRelationshipAndReplayPreservesCompletedQuantity()
            throws Exception {
        var value = allocatedDraft(1, borrowDraft("2099-12-31"));
        assertEquals("BORROW", value.path("requestType").asText());
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        assertEquals("IN_STOCK", jdbc.queryForObject(
                "SELECT lifecycle_status FROM fams_asset WHERE id=?", String.class, base + 50));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_loan WHERE asset_id=?", base + 50));
        String body = receiptBody(value, 0, "RQ-ASSET-" + base), identity = key();
        String endpoint = path(value, 0, "receive");
        var received = call(employee, "POST", endpoint, body, identity, value.path("version").asLong());
        assertEquals(200, received.statusCode(), received.body());
        var repeated = call(employee, "POST", endpoint, body, identity, value.path("version").asLong());
        assertEquals(received.body(), repeated.body());
        assertEquals("BORROWED", jdbc.queryForObject(
                "SELECT lifecycle_status FROM fams_asset WHERE id=?", String.class, base + 50));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_loan WHERE asset_id=? AND relation_status='OPEN'"
                + " AND borrower_employee_id=? AND expected_return_date='2099-12-31'", base + 50, base + 10));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=?"
                + " AND released_at IS NULL", base + 50));
        assertEquals(1, get(employee, value.path("id").asText()).path("demands").get(0)
                .path("completedQuantity").asInt());
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_timeline_event WHERE asset_id=?"
                + " AND event_type='BORROW_RECEIVED'", base + 50));
    }

    @Test
    void expiredBorrowingDateCannotCreateSubmitApproveOrReceiveAndRetainsPhysicalLock()
            throws Exception {
        assertEquals(400, call(employee, "POST", "", borrowDraft("2000-01-01"), key(), null).statusCode());
        var draft = create(employee, borrowDraft("2099-12-31"));
        jdbc.update("UPDATE fams_requisition SET expected_return_date='2000-01-01' WHERE id=?",
                draft.path("id").asText());
        assertEquals(400, call(employee, "POST", "/" + draft.path("id").asText() + "/submit",
                null, key(), draft.path("version").asLong()).statusCode());
        jdbc.update("UPDATE fams_requisition SET expected_return_date='2099-12-31' WHERE id=?",
                draft.path("id").asText());
        var submitted = action(employee, draft, "/submit", null);
        jdbc.update("UPDATE fams_requisition SET expected_return_date='2000-01-01' WHERE id=?",
                draft.path("id").asText());
        assertEquals(400, call(admin, "POST", "/" + draft.path("id").asText() + "/decision",
                "{\"result\":\"APPROVED\"}", key(), submitted.path("version").asLong()).statusCode());
        var value = allocatedDraft(1, borrowDraft("2099-12-31"));
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        String body = receiptBody(value, 0, "RQ-ASSET-" + base);
        jdbc.update("UPDATE fams_requisition SET expected_return_date='2000-01-01' WHERE id=?",
                value.path("id").asText());
        var rejected = call(employee, "POST", path(value, 0, "receive"), body, key(), value.path("version").asLong());
        assertEquals(409, rejected.statusCode(), rejected.body());
        assertEquals("LOAN_RETURN_DATE_EXPIRED", JSON.readTree(rejected.body()).path("code").asText());
        assertEquals(0, count("SELECT COUNT(*) FROM fams_loan WHERE asset_id=?", base + 50));
        assertEquals("IN_STOCK", jdbc.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=?",
                String.class, base + 50));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=?"
                + " AND released_at IS NULL", base + 50));
    }

    @Test
    void lateBorrowingReceiptFailureRollsBackOpenRelationshipTogetherWithFormalCustody()
            throws Exception {
        var value = allocatedDraft(1, borrowDraft("2099-12-31"));
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        String body = receiptBody(value, 0, "RQ-ASSET-" + base), identity = key();
        String trigger = "d613_borrow_fault_" + base;
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON fams_requisition_handover_event"
                + " FOR EACH ROW BEGIN IF NEW.actor_account_id=" + (base + 10)
                + " AND NEW.event_type='RECEIVED' THEN SIGNAL SQLSTATE '45000'"
                + " SET MESSAGE_TEXT='owned borrowing fault'; END IF; END");
        try {
            var failed = call(employee, "POST", path(value, 0, "receive"), body, identity, value.path("version").asLong());
            assertEquals(500, failed.statusCode(), failed.body());
            assertEquals(0, count("SELECT COUNT(*) FROM fams_loan WHERE asset_id=?", base + 50));
            assertEquals("IN_STOCK", jdbc.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=?",
                    String.class, base + 50));
            assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=?"
                    + " AND released_at IS NULL", base + 50));
            assertEquals(0, count("SELECT COUNT(*) FROM fams_idempotency_record WHERE idempotency_key=?", identity));
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger);
        }
        assertEquals(200, call(employee, "POST", path(value, 0, "receive"), body, identity,
                value.path("version").asLong()).statusCode());
        assertEquals(1, count("SELECT COUNT(*) FROM fams_loan WHERE asset_id=?", base + 50));
    }

    private String path(JsonNode value, int index, String action) {
        return "/"
                + value.path("id").asText()
                + "/allocations/"
                + value.path("allocations").get(index).path("id").asText()
                + "/"
                + action;
    }

    private String delivery(JsonNode value, int index) {
        var a = value.path("allocations").get(index);
        return "{\"allocationVersion\":"
                + a.path("version").asLong()
                + ",\"lockVersion\":"
                + a.path("lockVersion").asLong()
                + ",\"assetVersion\":0,\"observedAssetCode\":\"RQ-ASSET-"
                + (base + index)
                + "\",\"destination\":\"Room 1\",\"evidence\":\"现场交付核对编码\"}";
    }

    private String warehouseBody(JsonNode value, int index, boolean returned) {
        var a = value.path("allocations").get(index);
        return "{\"allocationVersion\":"
                + a.path("version").asLong()
                + ",\"lockVersion\":"
                + a.path("lockVersion").asLong()
                + ",\"assetVersion\":0,\"reason\":\"不再需要\",\"observedAssetCode\":\"RQ-ASSET-"
                + (base + index)
                + "\",\"inspectionEvidence\":\"实物、编码及附件均正常，已回原仓位\",\"physicalReturned\":"
                + returned
                + "}";
    }

    private JsonNode warehouseWrite(JsonNode value, int index, String action, String body)
            throws Exception {
        var reply =
                call(
                        warehouse,
                        "POST",
                        path(value, index, action),
                        body,
                        key(),
                        value.path("version").asLong());
        assertEquals(200, reply.statusCode(), reply.body());
        return JSON.readTree(reply.body());
    }

    private String receiptBody(JsonNode value, int index, String code) throws Exception {
        var grant = call(employee, "POST", path(value, index, "grant"), null, key(), null);
        assertEquals(200, grant.statusCode(), grant.body());
        return "{\"grant\":\""
                + JSON.readTree(grant.body()).path("grant").asText()
                + "\",\"observedAssetCode\":\""
                + code
                + "\",\"physicalChecked\":true}";
    }

    @Test
    void deliveryReceiptAndLostResponseReplayAtomicallyUpdateResponsibility() throws Exception {
        var value = allocated(1);
        String id = value.path("id").asText();
        assertEquals(
                409,
                call(employee, "POST", path(value, 0, "grant"), null, key(), null).statusCode());
        assertEquals(
                0,
                JSON.readTree(call(employee, "GET", "/receipts/pending", null, null, null).body())
                        .path("total")
                        .asInt());
        String key = key(), body = delivery(value, 0), endpoint = path(value, 0, "deliver");
        long before = value.path("version").asLong();
        var first = call(warehouse, "POST", endpoint, body, key, before);
        assertEquals(200, first.statusCode(), first.body());
        assertEquals(first.body(), call(warehouse, "POST", endpoint, body, key, before).body());
        value = JSON.readTree(first.body());
        Instant.parse(value.path("handovers").get(0).path("deliveredAt").asText());
        Instant.parse(value.path("handovers").get(0).path("receiptDeadlineAt").asText());
        assertEquals(
                "IN_STOCK",
                jdbc.queryForObject(
                        "SELECT lifecycle_status FROM fams_asset WHERE id=?",
                        String.class,
                        base + 50));
        var pending =
                JSON.readTree(call(employee, "GET", "/receipts/pending", null, null, null).body());
        assertEquals(1, pending.path("total").asInt());
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_requisition_handover WHERE allocation_id=?",
                        value.path("allocations").get(0).path("id").asText()));
        var assetReply =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + port
                                                        + "/api/v1/assets/"
                                                        + (base + 50)))
                                .header("Cookie", warehouse.cookie())
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, assetReply.statusCode(), assetReply.body());
        assertEquals(
                "Room 1",
                JSON.readTree(assetReply.body())
                        .path("physicalDestination")
                        .path("destination")
                        .asText());
        var deliveryTime =
                jdbc.queryForObject(
                        "SELECT delivered_at FROM fams_requisition_handover WHERE allocation_id=?",
                        Timestamp.class,
                        value.path("allocations").get(0).path("id").asText());
        var deadline =
                jdbc.queryForObject(
                        "SELECT receipt_deadline_at FROM fams_requisition_handover WHERE"
                                + " allocation_id=?",
                        Timestamp.class,
                        value.path("allocations").get(0).path("id").asText());
        assertEquals(
                259200,
                java.time.Duration.between(deliveryTime.toInstant(), deadline.toInstant())
                        .getSeconds());
        assertEquals(
                404, call(admin, "POST", path(value, 0, "grant"), null, key(), null).statusCode());
        String receive = receiptBody(value, 0, "RQ-ASSET-" + base);
        key = key();
        endpoint = path(value, 0, "receive");
        before = value.path("version").asLong();
        var done = call(employee, "POST", endpoint, receive, key, before);
        assertEquals(200, done.statusCode(), done.body());
        assertEquals(done.body(), call(employee, "POST", endpoint, receive, key, before).body());
        assertEquals(409, call(employee, "POST", endpoint, receive, key(), before).statusCode());
        assertEquals(
                "IN_USE",
                jdbc.queryForObject(
                        "SELECT lifecycle_status FROM fams_asset WHERE id=?",
                        String.class,
                        base + 50));
        assertEquals(
                base + 10,
                jdbc.queryForObject(
                        "SELECT responsible_employee_id FROM fams_asset WHERE id=?",
                        Long.class,
                        base + 50));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND"
                                + " released_at IS NULL",
                        base + 50));
        value = get(employee, id);
        assertEquals("COMPLETED", value.path("requestStatus").asText());
        assertEquals(1, value.path("demands").get(0).path("completedQuantity").asInt());
        assertEquals(2, value.path("handoverEvents").size());
        jdbc.update("UPDATE fams_system_account SET auth_epoch=auth_epoch+1 WHERE id=?", base + 10);
        assertEquals(401, call(employee, "POST", endpoint, receive, key, before).statusCode());
    }

    @Test
    void unhandedCancelAndRetrievedCancelPreserveQuantitiesAndPhysicalLocks() throws Exception {
        var value = allocated(3);
        String id = value.path("id").asText();
        String cancelBody = warehouseBody(value, 0, false),
                cancelPath = path(value, 0, "cancel"),
                cancelKey = key();
        long version = value.path("version").asLong();
        var cancelled = call(warehouse, "POST", cancelPath, cancelBody, cancelKey, version);
        assertEquals(200, cancelled.statusCode(), cancelled.body());
        value = JSON.readTree(cancelled.body());
        value = warehouseWrite(value, 1, "deliver", delivery(value, 1));
        assertEquals(
                409,
                call(
                                warehouse,
                                "POST",
                                path(value, 1, "cancel"),
                                warehouseBody(value, 1, false),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        String receive =
                receiptBody(value, 1, "wrong-observed-code").replace("}", ",\"reason\":\"实物不符\"}");
        var refused =
                call(
                        employee,
                        "POST",
                        path(value, 1, "refuse"),
                        receive,
                        key(),
                        value.path("version").asLong());
        assertEquals(200, refused.statusCode(), refused.body());
        value = get(employee, id);
        assertEquals(2, value.path("demands").get(0).path("pendingHandoverQuantity").asInt());
        assertEquals(
                2,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE business_id=? AND"
                                + " released_at IS NULL",
                        id));
        value = warehouseWrite(value, 1, "recovery", warehouseBody(value, 1, false));
        assertEquals(
                409,
                call(employee, "POST", path(value, 1, "grant"), null, key(), null).statusCode());
        assertEquals(
                400,
                call(
                                warehouse,
                                "POST",
                                path(value, 1, "recover"),
                                warehouseBody(value, 1, false),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(
                2,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE business_id=? AND"
                                + " released_at IS NULL",
                        id));
        value = warehouseWrite(value, 1, "recover", warehouseBody(value, 1, true));
        String lastBody = warehouseBody(value, 2, false),
                lastPath = path(value, 2, "cancel"),
                lastKey = key();
        version = value.path("version").asLong();
        var last = call(warehouse, "POST", lastPath, lastBody, lastKey, version);
        assertEquals(200, last.statusCode(), last.body());
        assertEquals(
                last.body(), call(warehouse, "POST", lastPath, lastBody, lastKey, version).body());
        value = get(employee, id);
        assertEquals("CANCELLED", value.path("requestStatus").asText());
        assertEquals(3, value.path("demands").get(0).path("cancelledQuantity").asInt());
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE business_id=? AND"
                                + " released_at IS NULL",
                        id));
        assertEquals(
                3,
                count(
                        "SELECT COUNT(*) FROM fams_asset WHERE id BETWEEN ? AND ? AND"
                                + " lifecycle_status='IN_STOCK'",
                        base + 50,
                        base + 52));
        assertEquals(6, value.path("handoverEvents").size());
    }

    @Test
    void grantExpiryWrongAssetVersionsAndCurrentTargetAreRejectedWithoutWrites() throws Exception {
        var value = allocated(2);
        String id = value.path("id").asText();
        assertEquals(
                409,
                call(
                                other,
                                "POST",
                                path(value, 0, "deliver"),
                                delivery(value, 0),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(
                403,
                call(
                                employee,
                                "POST",
                                path(value, 0, "deliver"),
                                delivery(value, 0),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(
                409,
                call(
                                warehouse,
                                "POST",
                                path(value, 0, "deliver"),
                                delivery(value, 0)
                                        .replace("\"lockVersion\":0", "\"lockVersion\":9"),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(
                409,
                call(
                                warehouse,
                                "POST",
                                path(value, 0, "deliver"),
                                delivery(value, 0).replace("RQ-ASSET-" + base, "wrong"),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        String receive = receiptBody(value, 0, "wrong");
        assertEquals(
                409,
                call(
                                employee,
                                "POST",
                                path(value, 0, "receive"),
                                receive,
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        receive = receiptBody(value, 0, "RQ-ASSET-" + base);
        assertEquals(
                409,
                call(
                                employee,
                                "POST",
                                path(value, 1, "receive"),
                                receive,
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        jdbc.update(
                "UPDATE fams_requisition_receipt_grant SET issued_at=DATE_SUB(NOW(3),INTERVAL 10"
                        + " MINUTE), expires_at=DATE_SUB(NOW(3),INTERVAL 5 MINUTE) WHERE"
                        + " allocation_id=?",
                value.path("allocations").get(0).path("id").asText());
        assertEquals(
                409,
                call(
                                employee,
                                "POST",
                                path(value, 0, "receive"),
                                receive,
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        receive = receiptBody(value, 0, "RQ-ASSET-" + base);
        jdbc.update("UPDATE fams_location SET version=version+1 WHERE id=?", base);
        assertEquals(
                409,
                call(
                                employee,
                                "POST",
                                path(value, 0, "receive"),
                                receive,
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_requisition_handover_event WHERE"
                                + " event_type='RECEIVED' AND actor_account_id=?",
                        base + 10));
        assertEquals(0, get(employee, id).path("demands").get(0).path("completedQuantity").asInt());
        assertEquals(
                2,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE business_id=? AND"
                                + " released_at IS NULL",
                        id));
    }

    @Test
    void receivingAndRecoveryRaceAllowsOnlyOneOutcomeAndSharedTargetIsExact() throws Exception {
        jdbc.update("INSERT INTO fams_account_role (account_id,role_code,granted_at) VALUES (?,'DEPARTMENT_ASSET_MANAGER',NOW(3))", base + 10);
        employee = login(base + 10);
        var draft = create(department, draft(1, base + 10, base).replace("PERSONAL", "SHARED"));
        var submitted = action(department, draft, "/submit", null);
        var value = action(admin, submitted, "/decision", "{\"result\":\"APPROVED\"}");
        value = action(warehouse, value, "/claim", null);
        value =
                allocate(
                        value,
                        warehouse,
                        value.path("demands").get(0).path("id").asText(),
                        base + 50,
                        null);
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        assertEquals(
                404,
                call(department, "POST", path(value, 0, "grant"), null, key(), null).statusCode());
        String receive = receiptBody(value, 0, "RQ-ASSET-" + base),
                endpoint = path(value, 0, "receive"),
                recovery = path(value, 0, "recovery");
        long version = value.path("version").asLong();
        String body = warehouseBody(value, 0, false);
        var a =
                CompletableFuture.supplyAsync(
                        () -> unchecked(employee, endpoint, receive, version));
        var b = CompletableFuture.supplyAsync(() -> unchecked(warehouse, recovery, body, version));
        var first = a.get(20, TimeUnit.SECONDS);
        var second = b.get(20, TimeUnit.SECONDS);
        assertEquals(
                List.of(200, 409),
                java.util.stream.Stream.of(first.statusCode(), second.statusCode())
                        .sorted()
                        .toList());
        String status =
                jdbc.queryForObject(
                        "SELECT allocation_status FROM fams_requisition_allocation WHERE"
                                + " request_id=?",
                        String.class,
                        value.path("id").asText());
        if ("RECEIVED".equals(status)) {
            assertEquals(
                    "SHARED",
                    jdbc.queryForObject(
                            "SELECT custody_type FROM fams_asset WHERE id=?",
                            String.class,
                            base + 50));
            assertEquals(
                    base + 10,
                    jdbc.queryForObject(
                            "SELECT department_responsible_employee_id FROM fams_asset WHERE id=?",
                            Long.class,
                            base + 50));
            assertEquals(
                    0,
                    count(
                            "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND"
                                    + " released_at IS NULL",
                            base + 50));
        } else {
            assertEquals("RECOVERY_PENDING_CANCEL", status);
            assertEquals(
                    "IN_STOCK",
                    jdbc.queryForObject(
                            "SELECT lifecycle_status FROM fams_asset WHERE id=?",
                            String.class,
                            base + 50));
            assertEquals(
                    1,
                    count(
                            "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND"
                                    + " released_at IS NULL",
                            base + 50));
        }
    }

    @Test
    void nativeReceiptHandlersReachActualHttpAndPersistOneReceiptAfterLostResponse()
            throws Exception {
        var value = allocated(1);
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        String code = "receipt-native-" + base;
        jdbc.update(
                "INSERT INTO fams_mini_binding (account_id,identity_hash,created_at) VALUES"
                        + " (?,?,?)",
                base + 10,
                miniSessions.identityHash(
                        new MiniWechatIdentityPort.Identity("requisition-test-app", code)),
                Timestamp.from(Instant.now()));
        var script =
                java.nio.file.Path.of(System.getProperty("user.dir"))
                        .getParent()
                        .resolve("apps/miniprogram/test/runtime-receipt-http.mjs");
        String node = System.getenv("FAMS_TEST_NODE_EXECUTABLE");
        var builder =
                new ProcessBuilder("rtk", "proxy", node == null ? "node" : node, script.toString());
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_WECHAT_CODE", code);
        builder.redirectErrorStream(true);
        var process = builder.start();
        assertTrue(process.waitFor(45, TimeUnit.SECONDS));
        String output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertEquals(
                "IN_USE",
                jdbc.queryForObject(
                        "SELECT lifecycle_status FROM fams_asset WHERE id=?",
                        String.class,
                        base + 50));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_requisition_handover_event WHERE"
                                + " event_type='RECEIVED' AND actor_account_id=?",
                        base + 10));
    }

    @Test
    void staleApprovalTargetsKeysAndPhysicalStateCannotCreateHandoverFacts() throws Exception {
        var value = allocated(2);
        String endpoint = path(value, 0, "deliver"), body = delivery(value, 0);
        long version = value.path("version").asLong();
        assertEquals(
                404,
                call(employee, "GET", path(value, 0, "receipt"), null, null, null).statusCode());
        assertEquals(409, call(warehouse, "POST", endpoint, body, key(), version - 1).statusCode());
        assertEquals(
                409,
                call(
                                warehouse,
                                "POST",
                                endpoint,
                                body.replace("\"allocationVersion\":0", "\"allocationVersion\":9"),
                                key(),
                                version)
                        .statusCode());
        assertEquals(
                409,
                call(
                                warehouse,
                                "POST",
                                endpoint,
                                body.replace("\"assetVersion\":0", "\"assetVersion\":9"),
                                key(),
                                version)
                        .statusCode());
        jdbc.update(
                "UPDATE fams_requisition SET request_status='PENDING_ADMIN_DECISION' WHERE id=?",
                value.path("id").asText());
        assertEquals(409, call(warehouse, "POST", endpoint, body, key(), version).statusCode());
        jdbc.update(
                "UPDATE fams_requisition SET request_status='WAITING_HANDOVER' WHERE id=?",
                value.path("id").asText());
        jdbc.update("UPDATE fams_employee SET organization_id=? WHERE id=?", base + 1, base + 10);
        assertEquals(409, call(warehouse, "POST", endpoint, body, key(), version).statusCode());
        jdbc.update("UPDATE fams_employee SET organization_id=? WHERE id=?", base, base + 10);
        jdbc.update("UPDATE fams_asset_category SET status='DISABLED' WHERE id=?", base);
        assertEquals(409, call(warehouse, "POST", endpoint, body, key(), version).statusCode());
        jdbc.update("UPDATE fams_asset_category SET status='ACTIVE' WHERE id=?", base);
        jdbc.update(
                "UPDATE fams_asset SET exception_status='SEARCHING' WHERE id=?", base + 50);
        assertEquals(409, call(warehouse, "POST", endpoint, body, key(), version).statusCode());
        jdbc.update("UPDATE fams_asset SET exception_status='NORMAL' WHERE id=?", base + 50);
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_requisition_handover WHERE allocation_id=?",
                        value.path("allocations").get(0).path("id").asText()));
        String identity = key();
        var delivered = call(warehouse, "POST", endpoint, body, identity, version);
        assertEquals(200, delivered.statusCode(), delivered.body());
        assertEquals(
                409,
                call(
                                warehouse,
                                "POST",
                                endpoint,
                                body.replace("Room 1", "Room 2"),
                                identity,
                                version)
                        .statusCode());
        value = JSON.readTree(delivered.body());
        String receive = receiptBody(value, 0, "RQ-ASSET-" + base);
        assertEquals(
                400,
                call(
                                employee,
                                "POST",
                                path(value, 0, "receive"),
                                receive.replace("true", "false"),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(
                400,
                call(
                                employee,
                                "POST",
                                path(value, 0, "refuse"),
                                receive,
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(
                404,
                call(
                                warehouse,
                                "POST",
                                path(value, 0, "receive"),
                                receive,
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(
                409,
                call(
                                employee,
                                "POST",
                                path(value, 0, "receive"),
                                receive.replace(
                                        JSON.readTree(receive).path("grant").asText(),
                                        "z".repeat(43)),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        jdbc.update(
                "UPDATE fams_requisition_allocation SET version=version+1 WHERE id=?",
                value.path("allocations").get(0).path("id").asText());
        assertEquals(
                409,
                call(
                                employee,
                                "POST",
                                path(value, 0, "receive"),
                                receive,
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_requisition_handover_event WHERE"
                            + " event_type='RECEIVED' AND actor_account_id=?",
                        base + 10));
    }

    @Test
    void receiptFailureRollsBackCustodyQuantitiesLockAndGrantBeforeRetry() throws Exception {
        var value = allocated(1);
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        String body = receiptBody(value, 0, "RQ-ASSET-" + base),
                endpoint = path(value, 0, "receive"),
                identity = key();
        String trigger = "d612_receipt_fault_" + base;
        jdbc.execute(
                "CREATE TRIGGER "
                        + trigger
                        + " BEFORE INSERT ON fams_requisition_handover_event FOR EACH ROW BEGIN IF"
                        + " NEW.actor_account_id="
                        + (base + 10)
                        + " AND NEW.event_type='RECEIVED' THEN SIGNAL SQLSTATE '45000' SET"
                        + " MESSAGE_TEXT='owned receipt fault'; END IF; END");
        try {
            var failed =
                    call(
                            employee,
                            "POST",
                            endpoint,
                            body,
                            identity,
                            value.path("version").asLong());
            assertEquals(500, failed.statusCode(), failed.body());
            assertEquals(
                    "IN_STOCK",
                    jdbc.queryForObject(
                            "SELECT lifecycle_status FROM fams_asset WHERE id=?",
                            String.class,
                            base + 50));
            assertEquals(
                    1,
                    count(
                            "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND"
                                + " released_at IS NULL",
                            base + 50));
            assertEquals(
                    0,
                    get(employee, value.path("id").asText())
                            .path("demands")
                            .get(0)
                            .path("completedQuantity")
                            .asInt());
            assertEquals(
                    1,
                    count(
                            "SELECT COUNT(*) FROM fams_requisition_receipt_grant WHERE"
                                + " allocation_id=? AND consumed_at IS NULL",
                            value.path("allocations").get(0).path("id").asText()));
            assertEquals(
                    0,
                    count(
                            "SELECT COUNT(*) FROM fams_idempotency_record WHERE idempotency_key=?",
                            identity));
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger);
        }
        var result =
                call(employee, "POST", endpoint, body, identity, value.path("version").asLong());
        assertEquals(200, result.statusCode(), result.body());
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_requisition_handover_event WHERE"
                            + " event_type='RECEIVED' AND actor_account_id=?",
                        base + 10));
    }

    @Test
    void mixedReceivedAndCancelledUnitsFinishPartiallyWithoutChangingCompletedResponsibility()
            throws Exception {
        var value = allocated(2);
        String id = value.path("id").asText();
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        var received =
                call(
                        employee,
                        "POST",
                        path(value, 0, "receive"),
                        receiptBody(value, 0, "RQ-ASSET-" + base),
                        key(),
                        value.path("version").asLong());
        assertEquals(200, received.statusCode(), received.body());
        value = get(employee, id);
        assertEquals(
                409,
                call(
                                warehouse,
                                "POST",
                                path(value, 0, "cancel"),
                                warehouseBody(value, 0, false),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        assertEquals(
                403,
                call(
                                employee,
                                "POST",
                                path(value, 1, "cancel"),
                                warehouseBody(value, 1, false),
                                key(),
                                value.path("version").asLong())
                        .statusCode());
        value = warehouseWrite(value, 1, "cancel", warehouseBody(value, 1, false));
        assertEquals("PARTIALLY_COMPLETED", value.path("requestStatus").asText());
        assertEquals(1, value.path("demands").get(0).path("completedQuantity").asInt());
        assertEquals(1, value.path("demands").get(0).path("cancelledQuantity").asInt());
        assertEquals(0, value.path("demands").get(0).path("pendingHandoverQuantity").asInt());
        assertEquals(
                "IN_USE",
                jdbc.queryForObject(
                        "SELECT lifecycle_status FROM fams_asset WHERE id=?",
                        String.class,
                        base + 50));
        assertEquals(
                "IN_STOCK",
                jdbc.queryForObject(
                        "SELECT lifecycle_status FROM fams_asset WHERE id=?",
                        String.class,
                        base + 51));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE business_id=? AND"
                            + " released_at IS NULL",
                        id));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_requisition_ownership WHERE request_id=? AND"
                            + " archived_at IS NULL",
                        id));
    }

    private JsonNode create(Session session, String body) throws Exception {
        var reply = call(session, "POST", "", body, key(), null);
        assertEquals(201, reply.statusCode(), reply.body());
        return JSON.readTree(reply.body());
    }

    private JsonNode revise(Session session, JsonNode value, String body) throws Exception {
        var reply =
                call(
                        session,
                        "PATCH",
                        "/" + value.path("id").asText(),
                        body,
                        key(),
                        value.path("version").asLong());
        assertEquals(200, reply.statusCode(), reply.body());
        return JSON.readTree(reply.body());
    }

    private JsonNode submitApprove(JsonNode value) throws Exception {
        return action(
                admin,
                action(employee, value, "/submit", null),
                "/decision",
                "{\"result\":\"APPROVED\"}");
    }

    private JsonNode action(Session session, JsonNode value, String action, String body)
            throws Exception {
        var reply =
                call(
                        session,
                        "POST",
                        "/" + value.path("id").asText() + action,
                        body,
                        key(),
                        value.path("version").asLong());
        assertEquals(200, reply.statusCode(), reply.body());
        return JSON.readTree(reply.body());
    }

    private JsonNode get(Session session, String id) throws Exception {
        var reply = call(session, "GET", "/" + id, null, null, null);
        assertEquals(200, reply.statusCode(), reply.body());
        return JSON.readTree(reply.body());
    }

    private JsonNode allocate(
            JsonNode value, Session owner, String line, long asset, String predecessor)
            throws Exception {
        return action(
                owner, value, "/demands/" + line + "/allocations", allocation(asset, predecessor));
    }

    private String allocation(long asset, String predecessor) {
        return "{\"asset\":{\"id\":\""
                + asset
                + "\",\"version\":0}"
                + (predecessor == null
                        ? ""
                        : ",\"predecessorAllocationId\":\""
                                + predecessor
                                + "\",\"replacementReason\":\"型号调整\"")
                + "}";
    }

    private String draft(int quantity, long target, long org) {
        return """
               {"demands":[{"category":{"id":"%d","version":0},"specification":"16GB",
                "purpose":"Office work","expectedDate":"2026-10-10","custodyType":"PERSONAL",
                "organization":{"id":"%d","version":0},"employee":{"id":"%d","version":0},
                "location":{"id":"%d","version":0},"quantity":%d}]}
               """
                .formatted(base, org, target, base, quantity);
    }

    private Session login(long id) throws Exception {
        var reply =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:" + port + "/api/v1/auth/login"))
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"username\":\"rq"
                                                        + id
                                                        + "\",\"password\":\""
                                                        + PASSWORD
                                                        + "\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, reply.statusCode(), reply.body());
        return new Session(
                reply.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                JSON.readTree(reply.body()).path("csrfToken").asText());
    }

    private HttpResponse<String> call(
            Session session, String method, String suffix, String body, String key, Long version)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/requisitions" + suffix));
        var headers = new HashMap<String, String>();
        headers.put("Cookie", session.cookie());
        if (key != null) {
            headers.put("Idempotency-Key", key);
            headers.put("X-CSRF-Token", session.csrf());
        }
        if (version != null) {
            headers.put("If-Match-Version", version.toString());
        }
        if (body != null) {
            headers.put("Content-Type", "application/json");
        }
        headers.forEach(builder::header);
        builder.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> unchecked(
            Session session, String suffix, String body, long version) {
        try {
            return call(session, "POST", suffix, body, key(), version);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private void personalTransferFixture() {
        jdbc.update(
                "UPDATE fams_asset SET"
                    + " lifecycle_status='IN_USE',custody_type='PERSONAL',storage_warehouse_id=NULL,owning_organization_id=?,using_organization_id=?,responsible_employee_id=?"
                    + " WHERE id=?",
                base,
                base,
                base + 10,
                base + 50);
    }

    private String transferDraft(
            boolean stock,
            boolean shared,
            long targetEmployee,
            long targetOrganization,
            long targetLocation,
            long targetWarehouse) {
        String target =
                JSON.writeValueAsString(
                        java.util.Map.of(
                                "custodyType",
                                stock ? "STORAGE" : shared ? "SHARED" : "PERSONAL",
                                "locationId",
                                Long.toString(targetLocation),
                                "referenceVersions",
                                stock
                                        ? java.util.Map.of("location", 0, "warehouse", 0)
                                        : java.util.Map.of(
                                                "location",
                                                0,
                                                "usingOrganization",
                                                0,
                                                "employee",
                                                0)));
        var object = (tools.jackson.databind.node.ObjectNode) JSON.readTree(target);
        if (!stock) {
            object.put("usingOrganizationId", Long.toString(targetOrganization));
            object.put("employeeId", Long.toString(targetEmployee));
        } else {
            object.put("warehouseId", Long.toString(targetWarehouse));
        }
        object.put("owningOrganizationId", Long.toString(base));
        ((tools.jackson.databind.node.ObjectNode) object.path("referenceVersions"))
                .put("owningOrganization", 0);
        return "{\"reason\":\"正式责任交接\",\"items\":[{\"assetId\":\""
                + (base + 50)
                + "\",\"assetVersion\":0,\"target\":"
                + object
                + "}]}";
    }

    private HttpResponse<String> transferCall(
            Session actor, String method, String suffix, String body, String identity, Long version)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:" + port + "/api/v1/transfers" + suffix))
                        .header("Cookie", actor.cookie());
        if (identity != null) {
            builder.header("X-CSRF-Token", actor.csrf()).header("Idempotency-Key", identity);
        }
        if (version != null) {
            builder.header("If-Match-Version", version.toString());
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

    private JsonNode transferAction(Session actor, JsonNode row, String suffix, String body)
            throws Exception {
        var response =
                transferCall(
                        actor,
                        "POST",
                        "/" + row.path("id").asText() + suffix,
                        body,
                        key(),
                        row.path("version").asLong());
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private JsonNode claimedTransfer(String draft, Session creator) throws Exception {
        var response = transferCall(creator, "POST", "", draft, key(), null);
        assertEquals(200, response.statusCode(), response.body());
        var row = transferAction(creator, JSON.readTree(response.body()), "/submit", null);
        row = transferAction(admin, row, "/decision", "{\"approved\":true,\"reason\":\"同意\"}");
        return transferAction(warehouse, row, "/claim", null);
    }

    private String transferPhysical(JsonNode row, String signer, boolean returned) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("itemVersion", row.path("items").get(0).path("version").asLong());
        body.put("observedCode", "RQ-ASSET-" + base);
        body.put("destination", "目标现场");
        body.put("evidence", "现场已核对清单和实物");
        body.put("physicalReturned", returned);
        if (signer != null) {
            body.put("actualReceiver", signer);
            body.put("receivedAt", Instant.now().toString());
        }
        return JSON.writeValueAsString(body);
    }

    private String transferItemPath(JsonNode row, String action) {
        return "/items/" + row.path("items").get(0).path("id").asText() + "/" + action;
    }

    private String transferReceipt(Session receiver, JsonNode row, String code) throws Exception {
        String item = row.path("items").get(0).path("id").asText();
        var grant =
                transferCall(receiver, "POST", "/receipts/" + item + "/grant", null, key(), null);
        assertEquals(200, grant.statusCode(), grant.body());
        return "{\"grant\":\""
                + JSON.readTree(grant.body()).path("grant").asText()
                + "\",\"physicalChecked\":true,\"observedCode\":\""
                + code
                + "\",\"reason\":\"现场核对\"}";
    }

    private String reassignmentBody(JsonNode snapshot, long target) {
        var body = JSON.createObjectNode();
        body.put("processorAccountId", Long.toString(target));
        body.put("ownershipVersion", snapshot.path("ownershipVersion").asLong());
        body.put("reason", "原库管长期缺席");
        body.put("evidence", "逐项核对实物清单及双方交接记录");
        body.put("unsynchronizedResultsChecked", true);
        var items = body.putArray("items");
        for (var item : snapshot.path("items")) {
            var check = items.addObject();
            check.put("id", item.path("id").asText());
            check.put("version", item.path("version").asLong());
            check.put("status", item.path("itemStatus").asText());
            check.put("lockVersion", item.path("lockVersion").asLong());
        }
        return body.toString();
    }

    private JsonNode reassignmentSnapshot(JsonNode row) throws Exception {
        var response = transferCall(admin, "GET", "/" + row.path("id").asText()
                + "/reassignment", null, null, null);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    @Test
    void transferReassignmentPreservesCustodyLocksAndApprovedTargetAndReplaysOnce() throws Exception {
        personalTransferFixture();
        var row = claimedTransfer(transferDraft(false, false, base + 15, base + 1, base, 0), employee);
        var snapshot = reassignmentSnapshot(row);
        String body = reassignmentBody(snapshot, base + 12), identity = key();
        String path = "/" + row.path("id").asText() + "/reassignment";
        long version = row.path("version").asLong();
        var response = transferCall(admin, "POST", path, body, identity, version);
        assertEquals(200, response.statusCode(), response.body());
        var changed = JSON.readTree(response.body());
        assertEquals(Long.toString(base + 12), changed.path("processorAccountId").asText());
        assertEquals(1, changed.path("ownershipVersion").asLong());
        assertEquals(row.path("approvedVersion"), changed.path("approvedVersion"));
        assertEquals(row.path("items"), changed.path("items"));
        assertEquals(base + 10, jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 50));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_todo WHERE business_type='TRANSFER' AND business_id=? AND closed_at IS NULL AND assignee_account_id=?", row.path("id").asLong(), base + 12));
        assertEquals(response.body(), transferCall(admin, "POST", path, body, identity, version).body());
        assertEquals(409, transferCall(admin, "POST", path, body.replace("长期缺席", "已离职"), identity, version).statusCode());
        assertEquals(1, count("SELECT COUNT(*) FROM fams_transfer_event WHERE request_id=? AND event_type='CONTROLLED_REASSIGNMENT'", row.path("id").asLong()));
        assertEquals(409, transferCall(warehouse, "POST", "/" + changed.path("id").asText() + transferItemPath(changed, "deliver"), transferPhysical(changed, null, false), key(), changed.path("version").asLong()).statusCode());
        changed = transferAction(other, changed, transferItemPath(changed, "deliver"), transferPhysical(changed, null, false));
        assertEquals("DELIVERED", changed.path("items").get(0).path("itemStatus").asText());
    }

    @Test
    void transferReassignmentRejectsPermissionsInvalidTargetsAndStalePhysicalSnapshot() throws Exception {
        personalTransferFixture();
        var row = claimedTransfer(transferDraft(false, false, base + 15, base + 1, base, 0), employee);
        var snapshot = reassignmentSnapshot(row);
        String body = reassignmentBody(snapshot, base + 12);
        String path = "/" + row.path("id").asText() + "/reassignment";
        long version = row.path("version").asLong();
        assertEquals(403, transferCall(warehouse, "POST", path, body, key(), version).statusCode());
        assertEquals(403, transferCall(login(base + 16), "POST", path, body, key(), version).statusCode());
        assertEquals(409, transferCall(admin, "POST", path, reassignmentBody(snapshot, base + 10), key(), version).statusCode());
        assertEquals(409, transferCall(admin, "POST", path, reassignmentBody(snapshot, base + 11), key(), version).statusCode());
        assertEquals(400, transferCall(admin, "POST", path, body.replace("Checked\":true", "Checked\":false"), key(), version).statusCode());
        assertEquals(409, transferCall(admin, "POST", path, body, key(), version - 1).statusCode());
        jdbc.update("UPDATE fams_asset_business_lock SET lock_version=lock_version+1 WHERE asset_id=? AND released_at IS NULL", base + 50);
        assertEquals(409, transferCall(admin, "POST", path, body, key(), version).statusCode());
        jdbc.update("UPDATE fams_system_account SET status='DISABLED' WHERE id=?", base + 12);
        assertEquals(409, transferCall(admin, "POST", path, body, key(), version).statusCode());
        assertEquals(0, count("SELECT COUNT(*) FROM fams_transfer_event WHERE request_id=? AND event_type='CONTROLLED_REASSIGNMENT'", row.path("id").asLong()));
        assertEquals(base + 11, jdbc.queryForObject("SELECT processor_account_id FROM fams_transfer WHERE id=?", Long.class, row.path("id").asLong()));
    }

    @Test
    void transferReassignmentAfterDeliveryRetainsReceiptAndSurvivesOldProcessorDisable() throws Exception {
        personalTransferFixture();
        var row = claimedTransfer(transferDraft(false, false, base + 15, base + 1, base, 0), employee);
        row = transferAction(warehouse, row, transferItemPath(row, "deliver"), transferPhysical(row, null, false));
        jdbc.update("UPDATE fams_system_account SET status='DISABLED',auth_epoch=auth_epoch+1 WHERE id=?", base + 11);
        var snapshot = reassignmentSnapshot(row);
        var changed = transferAction(admin, row, "/reassignment", reassignmentBody(snapshot, base + 12));
        assertEquals(row.path("items"), changed.path("items"));
        String receiptBody = transferReceipt(login(base + 15), changed, "RQ-ASSET-" + base);
        var response = transferCall(login(base + 15), "POST", "/receipts/" + changed.path("items").get(0).path("id").asText() + "/receive", receiptBody, key(), changed.path("version").asLong());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(base + 15, jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 50));
        assertEquals(409, transferCall(admin, "GET", "/" + row.path("id").asText() + "/reassignment", null, null, null).statusCode());
    }

    @Test
    void transferReassignmentAuditFailureRollsBackOwnerVersionTodoAndReplay() throws Exception {
        personalTransferFixture();
        var row = claimedTransfer(transferDraft(false, false, base + 15, base + 1, base, 0), employee);
        var snapshot = reassignmentSnapshot(row);
        String trigger = "d615_fail_" + base;
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON fams_audit_event FOR EACH ROW BEGIN IF NEW.action='CONTROLLED_REASSIGNMENT' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned test failure'; END IF; END");
        String identity = key(), path = "/" + row.path("id").asText() + "/reassignment";
        String body = reassignmentBody(snapshot, base + 12);
        try {
            assertTrue(transferCall(admin, "POST", path, body, identity, row.path("version").asLong()).statusCode() >= 400);
            assertEquals(base + 11, jdbc.queryForObject("SELECT processor_account_id FROM fams_transfer WHERE id=?", Long.class, row.path("id").asLong()));
            assertEquals(0, count("SELECT ownership_version FROM fams_transfer WHERE id=?", row.path("id").asLong()));
            assertEquals(0, count("SELECT COUNT(*) FROM fams_transfer_event WHERE request_id=? AND event_type='CONTROLLED_REASSIGNMENT'", row.path("id").asLong()));
            assertEquals(0, count("SELECT COUNT(*) FROM fams_idempotency_record WHERE actor_account_id=? AND idempotency_key=?", base + 13, identity));
        } finally { jdbc.execute("DROP TRIGGER " + trigger); }
        assertEquals(200, transferCall(admin, "POST", path, body, identity, row.path("version").asLong()).statusCode());
    }

    @Test
    void transferActualTargetReceiptUpdatesAllFieldsOnceWithoutEarlyCustodyOrOtherWarehouseWrites()
            throws Exception {
        personalTransferFixture();
        var row =
                claimedTransfer(
                        transferDraft(false, false, base + 15, base + 1, base, 0), employee);
        assertEquals(
                409,
                transferCall(
                                other,
                                "POST",
                                "/" + row.path("id").asText() + transferItemPath(row, "deliver"),
                                transferPhysical(row, null, false),
                                key(),
                                row.path("version").asLong())
                        .statusCode());
        row =
                transferAction(
                        warehouse,
                        row,
                        transferItemPath(row, "deliver"),
                        transferPhysical(row, null, false));
        assertEquals(
                base + 10,
                jdbc.queryForObject(
                        "SELECT responsible_employee_id FROM fams_asset WHERE id=?",
                        Long.class,
                        base + 50));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND"
                                + " released_at IS NULL",
                        base + 50));
        var target = login(base + 15);
        assertEquals(
                0,
                JSON.readTree(
                                transferCall(employee, "GET", "/receipts/pending", null, null, null)
                                        .body())
                        .path("total")
                        .asInt());
        assertEquals(
                1,
                JSON.readTree(
                                transferCall(target, "GET", "/receipts/pending", null, null, null)
                                        .body())
                        .path("total")
                        .asInt());
        String item = row.path("items").get(0).path("id").asText();
        assertEquals(
                404,
                transferCall(admin, "POST", "/receipts/" + item + "/grant", null, key(), null)
                        .statusCode());
        assertEquals(
                404,
                transferCall(target, "GET", "/" + row.path("id").asText(), null, null, null)
                        .statusCode());
        String receipt = transferReceipt(target, row, "RQ-ASSET-" + base),
                identity = key(),
                path = "/receipts/" + item + "/receive";
        var response =
                transferCall(target, "POST", path, receipt, identity, row.path("version").asLong());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(
                response.body(),
                transferCall(target, "POST", path, receipt, identity, row.path("version").asLong())
                        .body());
        assertEquals(
                409,
                transferCall(
                                target,
                                "POST",
                                path,
                                receipt.replace("现场核对", "changed"),
                                identity,
                                row.path("version").asLong())
                        .statusCode());
        assertEquals(
                base + 15,
                jdbc.queryForObject(
                        "SELECT responsible_employee_id FROM fams_asset WHERE id=?",
                        Long.class,
                        base + 50));
        assertEquals(
                base + 1,
                jdbc.queryForObject(
                        "SELECT using_organization_id FROM fams_asset WHERE id=?",
                        Long.class,
                        base + 50));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_asset_timeline_event WHERE asset_id=? AND"
                                + " event_type='TRANSFER_COMPLETED'",
                        base + 50));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND"
                                + " released_at IS NULL",
                        base + 50));
        var finalRow =
                JSON.readTree(
                        transferCall(
                                        employee,
                                        "GET",
                                        "/" + row.path("id").asText(),
                                        null,
                                        null,
                                        null)
                                .body());
        assertEquals("COMPLETED", finalRow.path("requestStatus").asText());
        assertEquals(1, finalRow.path("completedQuantity").asInt());
        jdbc.update("UPDATE fams_system_account SET auth_epoch=auth_epoch+1 WHERE id=?", base + 15);
        assertEquals(
                401,
                transferCall(target, "POST", path, receipt, identity, row.path("version").asLong())
                        .statusCode());
    }

    @Test
    void transferSharedTargetRequiresCurrentDepartmentRoleAndCannotBeReceivedByDelegate()
            throws Exception {
        personalTransferFixture();
        String draft = transferDraft(false, true, base + 14, base, base, 0);
        var row = claimedTransfer(draft, department);
        row =
                transferAction(
                        warehouse,
                        row,
                        transferItemPath(row, "deliver"),
                        transferPhysical(row, null, false));
        String item = row.path("items").get(0).path("id").asText();
        assertEquals(
                404,
                transferCall(employee, "POST", "/receipts/" + item + "/grant", null, key(), null)
                        .statusCode());
        String body = transferReceipt(department, row, "RQ-ASSET-" + base);
        var response =
                transferCall(
                        department,
                        "POST",
                        "/receipts/" + item + "/receive",
                        body,
                        key(),
                        row.path("version").asLong());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(
                "SHARED",
                jdbc.queryForObject(
                        "SELECT custody_type FROM fams_asset WHERE id=?", String.class, base + 50));
        assertEquals(
                null,
                jdbc.queryForObject(
                        "SELECT responsible_employee_id FROM fams_asset WHERE id=?",
                        Long.class,
                        base + 50));
        assertEquals(
                base + 14,
                jdbc.queryForObject(
                        "SELECT department_responsible_employee_id FROM fams_asset WHERE id=?",
                        Long.class,
                        base + 50));
    }

    @Test
    void transferCrossWarehouseNeedsRealDestinationReceiptAndOnlyOneProcessor() throws Exception {
        jdbc.update("UPDATE fams_location SET location_type='BIN' WHERE id=?", base);
        jdbc.update(
                "INSERT INTO fams_warehouse"
                    + " (id,code,code_normalized,name,status,created_at,updated_at) VALUES (?,?"
                    + " ,?,'Target','ACTIVE',NOW(3),NOW(3))",
                base + 1,
                "WH" + base,
                "WH" + base);
        jdbc.update(
                "INSERT INTO fams_location"
                    + " (id,code,code_normalized,name,location_type,warehouse_id,status,created_at,updated_at)"
                    + " VALUES (?,?,?,'Target bin','BIN',?,'ACTIVE',NOW(3),NOW(3))",
                base + 1,
                "BIN" + base,
                "BIN" + base,
                base + 1);
        var row = claimedTransfer(transferDraft(true, false, 0, 0, base + 1, base + 1), warehouse);
        assertEquals(
                400,
                transferCall(
                                warehouse,
                                "POST",
                                "/" + row.path("id").asText() + transferItemPath(row, "verify"),
                                transferPhysical(row, null, false),
                                key(),
                                row.path("version").asLong())
                        .statusCode());
        assertEquals(
                base,
                jdbc.queryForObject(
                        "SELECT storage_warehouse_id FROM fams_asset WHERE id=?",
                        Long.class,
                        base + 50));
        assertEquals(
                409,
                transferCall(
                                other,
                                "POST",
                                "/" + row.path("id").asText() + transferItemPath(row, "verify"),
                                transferPhysical(row, "现场签收人", false),
                                key(),
                                row.path("version").asLong())
                        .statusCode());
        row =
                transferAction(
                        warehouse,
                        row,
                        transferItemPath(row, "verify"),
                        transferPhysical(row, "现场签收人", false));
        assertEquals("COMPLETED", row.path("requestStatus").asText());
        assertEquals(
                base + 1,
                jdbc.queryForObject(
                        "SELECT storage_warehouse_id FROM fams_asset WHERE id=?",
                        Long.class,
                        base + 50));
        assertEquals(
                "IN_STOCK",
                jdbc.queryForObject(
                        "SELECT lifecycle_status FROM fams_asset WHERE id=?",
                        String.class,
                        base + 50));
        assertTrue(row.path("events").toString().contains("现场签收人"));
    }

    @Test
    void transferLocationOnlyVerificationAndUnhandedCancellationPreservePartialQuantity()
            throws Exception {
        personalTransferFixture();
        jdbc.update(
                "INSERT INTO fams_location"
                    + " (id,code,code_normalized,name,location_type,status,created_at,updated_at)"
                    + " VALUES (?,?,?,'Other room','ROOM','ACTIVE',NOW(3),NOW(3))",
                base + 1,
                "ROOM" + base,
                "ROOM" + base);
        String draft = transferDraft(false, false, base + 10, base, base + 1, 0);
        var body = (tools.jackson.databind.node.ObjectNode) JSON.readTree(draft);
        var items = (tools.jackson.databind.node.ArrayNode) body.path("items");
        var second = items.get(0).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) second).put("assetId", Long.toString(base + 51));
        items.add(second);
        jdbc.update(
                "UPDATE fams_asset SET"
                    + " lifecycle_status='IN_USE',custody_type='PERSONAL',storage_warehouse_id=NULL,owning_organization_id=?,using_organization_id=?,responsible_employee_id=?"
                    + " WHERE id=?",
                base,
                base,
                base + 10,
                base + 51);
        var row = claimedTransfer(body.toString(), employee);
        assertEquals(
                409,
                transferCall(
                                warehouse,
                                "POST",
                                "/" + row.path("id").asText() + transferItemPath(row, "deliver"),
                                transferPhysical(row, null, false),
                                key(),
                                row.path("version").asLong())
                        .statusCode());
        row =
                transferAction(
                        warehouse,
                        row,
                        transferItemPath(row, "verify"),
                        transferPhysical(row, null, false));
        String secondPath = "/items/" + row.path("items").get(1).path("id").asText() + "/cancel";
        var cancellation =
                (tools.jackson.databind.node.ObjectNode)
                        JSON.readTree(transferPhysical(row, null, false));
        cancellation.put("itemVersion", row.path("items").get(1).path("version").asLong());
        cancellation.put("observedCode", "RQ-ASSET-" + (base + 1));
        row = transferAction(warehouse, row, secondPath, cancellation.toString());
        assertEquals("PARTIALLY_COMPLETED", row.path("requestStatus").asText());
        assertEquals(1, row.path("completedQuantity").asInt());
        assertEquals(1, row.path("cancelledQuantity").asInt());
        assertEquals(
                base + 1,
                jdbc.queryForObject(
                        "SELECT current_location_id FROM fams_asset WHERE id=?",
                        Long.class,
                        base + 50));
        assertEquals(
                base,
                jdbc.queryForObject(
                        "SELECT current_location_id FROM fams_asset WHERE id=?",
                        Long.class,
                        base + 51));
    }

    @Test
    void transferRefusalKeepsSourceLockAndCancellationNeedsActualRecovery() throws Exception {
        personalTransferFixture();
        var target = login(base + 15);
        var row =
                claimedTransfer(
                        transferDraft(false, false, base + 15, base + 1, base, 0), employee);
        row =
                transferAction(
                        warehouse,
                        row,
                        transferItemPath(row, "deliver"),
                        transferPhysical(row, null, false));
        String item = row.path("items").get(0).path("id").asText();
        var response =
                transferCall(
                        target,
                        "POST",
                        "/receipts/" + item + "/refuse",
                        transferReceipt(target, row, "wrong"),
                        key(),
                        row.path("version").asLong());
        assertEquals(200, response.statusCode(), response.body());
        row =
                JSON.readTree(
                        transferCall(
                                        employee,
                                        "GET",
                                        "/" + row.path("id").asText(),
                                        null,
                                        null,
                                        null)
                                .body());
        assertEquals("REFUSED", row.path("items").get(0).path("itemStatus").asText());
        assertEquals(
                409,
                transferCall(
                                employee,
                                "POST",
                                "/" + row.path("id").asText() + "/cancel",
                                null,
                                key(),
                                row.path("version").asLong())
                        .statusCode());
        row =
                transferAction(
                        warehouse,
                        row,
                        transferItemPath(row, "recovery"),
                        transferPhysical(row, null, false));
        assertEquals(
                400,
                transferCall(
                                warehouse,
                                "POST",
                                "/" + row.path("id").asText() + transferItemPath(row, "recover"),
                                transferPhysical(row, null, false),
                                key(),
                                row.path("version").asLong())
                        .statusCode());
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND"
                            + " released_at IS NULL",
                        base + 50));
        row =
                transferAction(
                        warehouse,
                        row,
                        transferItemPath(row, "recover"),
                        transferPhysical(row, null, true));
        assertEquals("CANCELLED", row.path("requestStatus").asText());
        assertEquals(
                base + 10,
                jdbc.queryForObject(
                        "SELECT responsible_employee_id FROM fams_asset WHERE id=?",
                        Long.class,
                        base + 50));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND"
                            + " released_at IS NULL",
                        base + 50));
    }

    @Test
    void transferDuplicateNoChangeScopeAndReferenceFailuresCannotCreateFacts() throws Exception {
        personalTransferFixture();
        String draft = transferDraft(false, false, base + 15, base + 1, base, 0);
        assertEquals(
                403, transferCall(login(base + 15), "POST", "", draft, key(), null).statusCode());
        assertEquals(
                409,
                transferCall(
                                employee,
                                "POST",
                                "",
                                draft.replace("\"assetVersion\":0", "\"assetVersion\":99"),
                                key(),
                                null)
                        .statusCode());
        assertEquals(
                400,
                transferCall(
                                employee,
                                "POST",
                                "",
                                transferDraft(false, false, base + 10, base, base, 0),
                                key(),
                                null)
                        .statusCode());
        var object = (tools.jackson.databind.node.ObjectNode) JSON.readTree(draft);
        var items = (tools.jackson.databind.node.ArrayNode) object.path("items");
        items.add(items.get(0).deepCopy());
        assertEquals(
                400,
                transferCall(employee, "POST", "", object.toString(), key(), null).statusCode());
        assertEquals(
                409,
                transferCall(
                                employee,
                                "POST",
                                "",
                                transferDraft(false, true, base + 15, base + 1, base, 0),
                                key(),
                                null)
                        .statusCode());
        assertEquals(
                409,
                transferCall(
                                employee,
                                "POST",
                                "",
                                transferDraft(false, false, base + 15, base, base, 0),
                                key(),
                                null)
                        .statusCode());
        jdbc.update(
                "INSERT INTO fams_location"
                    + " (id,code,code_normalized,name,location_type,warehouse_id,status,created_at,updated_at)"
                    + " VALUES (?,?,?,'Storage bin','BIN',?,'ACTIVE',NOW(3),NOW(3))",
                base + 1,
                "TRANSFERBIN" + base,
                "TRANSFERBIN" + base,
                base);
        assertEquals(
                409,
                transferCall(
                                employee,
                                "POST",
                                "",
                                transferDraft(false, false, base + 15, base + 1, base + 1, 0),
                                key(),
                                null)
                        .statusCode());
        jdbc.update("UPDATE fams_employee SET status='DISABLED' WHERE id=?", base + 15);
        assertEquals(409, transferCall(employee, "POST", "", draft, key(), null).statusCode());
        assertEquals(
                0,
                count("SELECT COUNT(*) FROM fams_transfer WHERE creator_account_id=?", base + 10));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND"
                                + " released_at IS NULL",
                        base + 50));
    }
    @Test
    void transferApprovalRejectionAndDraftCancelNeverRewriteCustodyAndReuseIsBoundToPayload()
            throws Exception {
        personalTransferFixture();
        String draft = transferDraft(false, false, base + 15, base + 1, base, 0), identity = key();
        var response = transferCall(employee, "POST", "", draft, identity, null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(
                response.body(), transferCall(employee, "POST", "", draft, identity, null).body());
        assertEquals(
                409,
                transferCall(
                                employee,
                                "POST",
                                "",
                                draft.replace("正式责任交接", "changed"),
                                identity,
                                null)
                        .statusCode());
        var row = transferAction(employee, JSON.readTree(response.body()), "/cancel", null);
        assertEquals("CANCELLED", row.path("requestStatus").asText());
        response = transferCall(employee, "POST", "", draft, key(), null);
        row = transferAction(employee, JSON.readTree(response.body()), "/submit", null);
        assertEquals(
                403,
                transferCall(
                                employee,
                                "POST",
                                "/" + row.path("id").asText() + "/decision",
                                "{\"approved\":true,\"reason\":\"no\"}",
                                key(),
                                row.path("version").asLong())
                        .statusCode());
        row = transferAction(admin, row, "/decision", "{\"approved\":false,\"reason\":\"保持原责任\"}");
        assertEquals("REJECTED", row.path("requestStatus").asText());
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND"
                            + " released_at IS NULL",
                        base + 50));
        assertEquals(
                base + 10,
                jdbc.queryForObject(
                        "SELECT responsible_employee_id FROM fams_asset WHERE id=?",
                        Long.class,
                        base + 50));
    }

    @Test
    void transferReceiptExpiryWrongCodeAndLateFailureRollbackBeforeSameKeyRetry() throws Exception {
        personalTransferFixture();
        var target = login(base + 15);
        var row =
                claimedTransfer(
                        transferDraft(false, false, base + 15, base + 1, base, 0), employee);
        row =
                transferAction(
                        warehouse,
                        row,
                        transferItemPath(row, "deliver"),
                        transferPhysical(row, null, false));
        String item = row.path("items").get(0).path("id").asText(),
                path = "/receipts/" + item + "/receive";
        String body = transferReceipt(target, row, "wrong");
        assertEquals(
                409,
                transferCall(target, "POST", path, body, key(), row.path("version").asLong())
                        .statusCode());
        jdbc.update(
                "UPDATE fams_transfer_receipt_grant SET issued_at=DATE_SUB(NOW(3),INTERVAL 10"
                    + " MINUTE),expires_at=DATE_SUB(NOW(3),INTERVAL 5 MINUTE) WHERE item_id=?",
                item);
        body = body.replace("wrong", "RQ-ASSET-" + base);
        assertEquals(
                409,
                transferCall(target, "POST", path, body, key(), row.path("version").asLong())
                        .statusCode());
        body = transferReceipt(target, row, "RQ-ASSET-" + base);
        String identity = key(), trigger = "d614_transfer_fault_" + base;
        jdbc.execute(
                "CREATE TRIGGER "
                        + trigger
                        + " BEFORE INSERT ON fams_transfer_event FOR EACH ROW BEGIN IF"
                        + " NEW.event_type='TRANSFER_RECEIVE' AND NEW.actor_account_id="
                        + (base + 15)
                        + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned transfer fault';"
                        + " END IF; END");
        try {
            assertEquals(
                    500,
                    transferCall(target, "POST", path, body, identity, row.path("version").asLong())
                            .statusCode());
            assertEquals(
                    base + 10,
                    jdbc.queryForObject(
                            "SELECT responsible_employee_id FROM fams_asset WHERE id=?",
                            Long.class,
                            base + 50));
            assertEquals(
                    "DELIVERED",
                    jdbc.queryForObject(
                            "SELECT item_status FROM fams_transfer_item WHERE id=?",
                            String.class,
                            item));
            assertEquals(
                    1,
                    count(
                            "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND"
                                + " released_at IS NULL",
                            base + 50));
            assertEquals(
                    0,
                    count(
                            "SELECT COUNT(*) FROM fams_idempotency_record WHERE idempotency_key=?",
                            identity));
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger);
        }
        var response =
                transferCall(target, "POST", path, body, identity, row.path("version").asLong());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_asset_timeline_event WHERE asset_id=? AND"
                            + " event_type='TRANSFER_COMPLETED'",
                        base + 50));
    }

    @Test
    void transferPersonnelAndTargetCatalogSeparateSelectionRightsFromSensitiveDataAndStopIdentity()
            throws Exception {
        personalTransferFixture();
        assertEquals(
                403,
                transferCall(
                                employee,
                                "GET",
                                "/personnel-handover?employeeId=" + (base + 10),
                                null,
                                null,
                                null)
                        .statusCode());
        var response =
                transferCall(
                        admin,
                        "GET",
                        "/personnel-handover?employeeId=" + (base + 10),
                        null,
                        null,
                        null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1, JSON.readTree(response.body()).path("assets").size());
        response =
                transferCall(
                        employee,
                        "GET",
                        "/catalog/employee?organizationId=" + (base + 1),
                        null,
                        null,
                        null);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(!response.body().contains("phone"));
        assertEquals(
                400,
                transferCall(employee, "GET", "/catalog/employee", null, null, null).statusCode());
        assertEquals(
                404,
                transferCall(employee, "GET", "/catalog/secret", null, null, null).statusCode());
        assertEquals(
                0,
                JSON.readTree(
                                transferCall(
                                                login(base + 15),
                                                "GET",
                                                "/eligible-assets",
                                                null,
                                                null,
                                                null)
                                        .body())
                        .path("total")
                        .asInt());
        jdbc.update("UPDATE fams_employee SET status='DISABLED' WHERE id=?", base + 10);
        assertEquals(401, transferCall(employee, "GET", "", null, null, null).statusCode());
        response =
                transferCall(
                        admin,
                        "GET",
                        "/personnel-handover?employeeId=" + (base + 10),
                        null,
                        null,
                        null);
        assertEquals(
                "DISABLED",
                JSON.readTree(response.body()).path("employee").path("status").asText());
        assertEquals(
                base + 10,
                jdbc.queryForObject(
                        "SELECT responsible_employee_id FROM fams_asset WHERE id=?",
                        Long.class,
                        base + 50));
        var row = claimedTransfer(transferDraft(false, false, base + 15, base + 1, base, 0), admin);
        assertEquals("PROCESSING", row.path("requestStatus").asText());
    }

    @Test
    void transferConcurrentClaimAndReceiptHaveOnlyOneWinner() throws Exception {
        personalTransferFixture();
        var response =
                transferCall(
                        employee,
                        "POST",
                        "",
                        transferDraft(false, false, base + 15, base + 1, base, 0),
                        key(),
                        null);
        var row = transferAction(employee, JSON.readTree(response.body()), "/submit", null);
        row = transferAction(admin, row, "/decision", "{\"approved\":true,\"reason\":\"批准整单\"}");
        String requestId = row.path("id").asText();
        long claimVersion = row.path("version").asLong();
        var first =
                CompletableFuture.supplyAsync(
                        () ->
                                uncheckedTransfer(
                                        warehouse,
                                        "/" + requestId + "/claim",
                                        null,
                                        key(),
                                        claimVersion));
        var second =
                CompletableFuture.supplyAsync(
                        () ->
                                uncheckedTransfer(
                                        other,
                                        "/" + requestId + "/claim",
                                        null,
                                        key(),
                                        claimVersion));
        var statuses =
                List.of(
                        first.get(15, TimeUnit.SECONDS).statusCode(),
                        second.get(15, TimeUnit.SECONDS).statusCode());
        assertEquals(1, statuses.stream().filter(v -> v == 200).count());
        assertEquals(1, statuses.stream().filter(v -> v == 409).count());
        row = JSON.readTree(transferCall(admin, "GET", "/" + requestId, null, null, null).body());
        Session owner =
                row.path("processorAccountId").asText().equals(Long.toString(base + 11))
                        ? warehouse
                        : other;
        row =
                transferAction(
                        owner,
                        row,
                        transferItemPath(row, "deliver"),
                        transferPhysical(row, null, false));
        var target = login(base + 15);
        String body = transferReceipt(target, row, "RQ-ASSET-" + base);
        String path = "/receipts/" + row.path("items").get(0).path("id").asText() + "/receive";
        long receiptVersion = row.path("version").asLong();
        first =
                CompletableFuture.supplyAsync(
                        () -> uncheckedTransfer(target, path, body, key(), receiptVersion));
        second =
                CompletableFuture.supplyAsync(
                        () -> uncheckedTransfer(target, path, body, key(), receiptVersion));
        statuses =
                List.of(
                        first.get(15, TimeUnit.SECONDS).statusCode(),
                        second.get(15, TimeUnit.SECONDS).statusCode());
        assertEquals(1, statuses.stream().filter(v -> v == 200).count());
        assertEquals(1, statuses.stream().filter(v -> v == 409).count());
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_asset_timeline_event WHERE asset_id=? AND"
                                + " event_type='TRANSFER_COMPLETED'",
                        base + 50));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND"
                                + " released_at IS NULL",
                        base + 50));
    }

    @Test
    void transferTargetDisableOrReferenceDriftPreservesResponsibilityAndLock() throws Exception {
        personalTransferFixture();
        var target = login(base + 15);
        var row =
                claimedTransfer(
                        transferDraft(false, false, base + 15, base + 1, base, 0), employee);
        row =
                transferAction(
                        warehouse,
                        row,
                        transferItemPath(row, "deliver"),
                        transferPhysical(row, null, false));
        String body = transferReceipt(target, row, "RQ-ASSET-" + base);
        String path = "/receipts/" + row.path("items").get(0).path("id").asText() + "/receive";
        jdbc.update("UPDATE fams_employee SET status='DISABLED' WHERE id=?", base + 15);
        assertEquals(
                401,
                transferCall(target, "POST", path, body, key(), row.path("version").asLong())
                        .statusCode());
        jdbc.update(
                "UPDATE fams_employee SET status='ACTIVE',version=version+1 WHERE id=?", base + 15);
        target = login(base + 15);
        assertEquals(
                409,
                transferCall(target, "POST", path, body, key(), row.path("version").asLong())
                        .statusCode());
        assertEquals(
                base + 10,
                jdbc.queryForObject(
                        "SELECT responsible_employee_id FROM fams_asset WHERE id=?",
                        Long.class,
                        base + 50));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND"
                                + " released_at IS NULL",
                        base + 50));
    }

    private HttpResponse<String> uncheckedTransfer(
            Session session, String path, String body, String key, long version) {
        try {
            return transferCall(session, "POST", path, body, key, version);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    @Test
    void transferNativeReceiptHandlersReachHttpAndPersistOnceAfterLostReply() throws Exception {
        personalTransferFixture();
        var row =
                claimedTransfer(
                        transferDraft(false, false, base + 15, base + 1, base, 0), employee);
        row =
                transferAction(
                        warehouse,
                        row,
                        transferItemPath(row, "deliver"),
                        transferPhysical(row, null, false));
        String code = "transfer-native-" + base;
        jdbc.update(
                "INSERT INTO fams_mini_binding (account_id,identity_hash,created_at) VALUES"
                        + " (?,?,?)",
                base + 15,
                miniSessions.identityHash(
                        new MiniWechatIdentityPort.Identity("requisition-test-app", code)),
                Timestamp.from(Instant.now()));
        var script =
                java.nio.file.Path.of(System.getProperty("user.dir"))
                        .getParent()
                        .resolve("apps/miniprogram/test/runtime-transfer-receipt-http.mjs");
        String node = System.getenv("FAMS_TEST_NODE_EXECUTABLE");
        var builder =
                new ProcessBuilder("rtk", "proxy", node == null ? "node" : node, script.toString());
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_WECHAT_CODE", code);
        builder.redirectErrorStream(true);
        var process = builder.start();
        assertTrue(process.waitFor(45, TimeUnit.SECONDS));
        String output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertEquals(
                base + 15,
                jdbc.queryForObject(
                        "SELECT responsible_employee_id FROM fams_asset WHERE id=?",
                        Long.class,
                        base + 50));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_transfer_event WHERE request_id=? AND"
                                + " event_type='TRANSFER_RECEIVE'",
                        row.path("id").asLong()));
    }

    @Test
    void transferNativeReassignmentHandlersReachHttpAndPreservePhysicalFacts() throws Exception {
        personalTransferFixture();
        var row = claimedTransfer(
                transferDraft(false, false, base + 15, base + 1, base, 0), employee);
        row = transferAction(warehouse, row, transferItemPath(row, "deliver"),
                transferPhysical(row, null, false));
        String code = "transfer-reassign-native-" + base;
        jdbc.update("INSERT INTO fams_mini_binding (account_id,identity_hash,created_at) VALUES (?,?,?)",
                base + 13, miniSessions.identityHash(
                        new MiniWechatIdentityPort.Identity("requisition-test-app", code)),
                Timestamp.from(Instant.now()));
        var script = java.nio.file.Path.of(System.getProperty("user.dir")).getParent()
                .resolve("apps/miniprogram/test/runtime-transfer-reassignment-http.mjs");
        String node = System.getenv("FAMS_TEST_NODE_EXECUTABLE");
        var builder = new ProcessBuilder("rtk", "proxy", node == null ? "node" : node, script.toString());
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_WECHAT_CODE", code);
        builder.environment().put("FAMS_TEST_TRANSFER_ID", row.path("id").asText());
        builder.environment().put("FAMS_TEST_SUCCESSOR_ID", Long.toString(base + 12));
        builder.redirectErrorStream(true);
        var process = builder.start();
        assertTrue(process.waitFor(45, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertEquals(base + 12, jdbc.queryForObject(
                "SELECT processor_account_id FROM fams_transfer WHERE id=?", Long.class, row.path("id").asLong()));
        assertEquals(base + 10, jdbc.queryForObject(
                "SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 50));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_transfer_event WHERE request_id=?"
                + " AND event_type='CONTROLLED_REASSIGNMENT'", row.path("id").asLong()));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=?"
                + " AND released_at IS NULL", base + 50));
        var changed = JSON.readTree(transferCall(admin, "GET", "/" + row.path("id").asText(),
                null, null, null).body());
        var rejected = transferCall(warehouse, "POST", "/" + changed.path("id").asText()
                + transferItemPath(changed, "deliver"), transferPhysical(changed, null, false),
                key(), changed.path("version").asLong());
        assertEquals(409, rejected.statusCode(), rejected.body());
        assertTrue(rejected.body().contains("TRANSFER_NOT_PROCESSOR"), rejected.body());
    }

    @Test
    @EnabledIfSystemProperty(named = "d6.15.proxy.runtime", matches = "true")
    void proxyConfirmationNativeRequisitionHandlersUploadApproveExecuteAndNotifyThroughRealHttp() throws Exception {
        var row = allocated(1);
        row = warehouseWrite(row, 0, "deliver", delivery(row, 0));
        verifyNativeProxyHandlers(row, false);
    }

    @Test
    @EnabledIfSystemProperty(named = "d6.15.proxy.runtime", matches = "true")
    void proxyConfirmationNativeTransferHandlersUploadApproveExecuteAndNotifyThroughRealHttp() throws Exception {
        personalTransferFixture();
        var row = claimedTransfer(transferDraft(false, false, base + 15, base + 1, base, 0), employee);
        row = transferAction(warehouse, row, transferItemPath(row, "deliver"), transferPhysical(row, null, false));
        verifyNativeProxyHandlers(row, true);
    }

    @Test
    @EnabledIfSystemProperty(named = "d6.15.proxy.runtime", matches = "true")
    void proxyConfirmationMiniUploadRevalidatesLogoutAfterScanningBeforeRegistration() throws Exception {
        var row = allocated(1);
        row = warehouseWrite(row, 0, "deliver", delivery(row, 0));
        var created = proxyCall(warehouse, "POST", "", proxyDraft(proxyContext(row)), key(), null);
        assertEquals(200, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        String token = proxyMiniLogin(base + 11, "paused-upload");
        var pause = new ProxyScanPause(new java.util.concurrent.CountDownLatch(1), new java.util.concurrent.CountDownLatch(1));
        PROXY_SCAN_PAUSE.set(pause);
        java.util.concurrent.CompletableFuture<HttpResponse<String>> upload;
        try {
            upload = http.sendAsync(proxyMiniUploadRequest(token, id, key()), HttpResponse.BodyHandlers.ofString());
            assertTrue(pause.entered().await(10, TimeUnit.SECONDS), "Upload did not enter the owned scanner pause");
            var logout = proxyMiniCall(token, "POST", "/auth/logout", null);
            assertEquals(204, logout.statusCode(), logout.body());
        } finally {
            PROXY_SCAN_PAUSE.set(null);
            pause.release().countDown();
        }
        var rejected = upload.get(20, TimeUnit.SECONDS);
        assertEquals(401, rejected.statusCode(), rejected.body());
        assertEquals(0, count("SELECT COUNT(*) FROM fams_attachment WHERE business_type='PROXY_CONFIRMATION' AND business_id=?", id));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM fams_proxy_confirmation WHERE id=?", Long.class, id));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
    }

    @Test
    @EnabledIfSystemProperty(named = "d6.15.proxy.runtime", matches = "true")
    void proxyConfirmationMiniDownloadGrantCannotSurviveLogoutOrCrossAccountIdentity() throws Exception {
        var row = allocated(1);
        row = warehouseWrite(row, 0, "deliver", delivery(row, 0));
        var created = proxyCall(warehouse, "POST", "", proxyDraft(proxyContext(row)), key(), null);
        assertEquals(200, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        var proof = proxyUpload(id, 0);
        assertEquals(201, proof.statusCode(), proof.body());
        String object = JSON.readTree(proof.body()).path("objectId").asText();
        String token = proxyMiniLogin(base + 11, "grant-owner");
        var grant = proxyMiniCall(token, "POST", "/attachments/" + object + "/download-grant", null);
        assertEquals(200, grant.statusCode(), grant.body());
        String url = JSON.readTree(grant.body()).path("url").asText();
        assertTrue(url.startsWith("/api/v1/mini/attachments/"));
        var content = proxyMiniCall(token, "GET", url.substring("/api/v1/mini".length()), null);
        assertEquals(200, content.statusCode(), content.body());
        assertTrue(content.body().startsWith("%PDF-1.7"));
        String otherToken = proxyMiniLogin(base + 12, "grant-other");
        var cross = proxyMiniCall(otherToken, "GET", url.substring("/api/v1/mini".length()), null);
        assertEquals(403, cross.statusCode(), cross.body());
        assertEquals(204, proxyMiniCall(token, "POST", "/auth/logout", null).statusCode());
        assertEquals(401, proxyMiniCall(token, "GET", url.substring("/api/v1/mini".length()), null).statusCode());
        assertEquals(1, count("SELECT COUNT(*) FROM fams_attachment WHERE business_type='PROXY_CONFIRMATION' AND business_id=?", id));
    }

    @Test
    @EnabledIfSystemProperty(named = "d6.15.proxy.runtime", matches = "true")
    void proxyConfirmationResultNoticeKeepsLargeIdentifiersExactAcrossReadAndReview() throws Exception {
        var row = allocated(1);
        row = warehouseWrite(row, 0, "deliver", delivery(row, 0));
        var created = proxyCall(warehouse, "POST", "", proxyDraft(proxyContext(row)), key(), null);
        assertEquals(200, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        assertEquals(201, proxyUpload(id, 0).statusCode());
        assertEquals(200, proxyCall(warehouse, "POST", "/" + id + "/submit", null, key(), 1L).statusCode());
        assertEquals(200, proxyCall(admin, "POST", "/" + id + "/decision", proxyDecision(true), key(), 2L).statusCode());
        assertEquals(200, proxyCall(warehouse, "POST", "/" + id + "/execute", null, key(), 3L).statusCode());
        String large = "9007199254740993";
        assertEquals(1, jdbc.update("UPDATE fams_business_result_notice SET id=? WHERE execution_id=? AND recipient_employee_id=?", large, id, base + 10));
        var notice = JSON.readTree(noticeCall(employee, "GET", "").body()).path("items").get(0);
        assertTrue(notice.path("id").isTextual());
        assertEquals(large, notice.path("id").asText());
        assertTrue(notice.path("businessId").isTextual());
        var read = noticeCall(employee, "POST", "/" + large + "/read");
        assertEquals(200, read.statusCode(), read.body());
        assertEquals(large, JSON.readTree(read.body()).path("id").asText());
        assertEquals(200, noticeObjection(employee, large, "Please check the exact result identifier", key()).statusCode());
        var review = JSON.readTree(noticeCall(admin, "GET", "/review-requests").body()).path("items").get(0);
        assertEquals(large, review.path("noticeId").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM fams_audit_event WHERE resource_type='BUSINESS_RESULT_NOTICE' AND resource_id=? AND action='RESULT_NOTICE_OBJECTION'", large));
    }

    @Test
    @EnabledIfSystemProperty(named = "d6.15.proxy.runtime", matches = "true")
    void proxyConfirmationRequisitionRaceWithActualReceiptCommitsOnlyOneOutcome() throws Exception {
        var row = allocated(1);
        row = warehouseWrite(row, 0, "deliver", delivery(row, 0));
        verifyProxyReceiptRace(row, false);
    }

    @Test
    @EnabledIfSystemProperty(named = "d6.15.proxy.runtime", matches = "true")
    void proxyConfirmationTransferRaceWithActualReceiptCommitsOnlyOneOutcome() throws Exception {
        personalTransferFixture();
        var row = claimedTransfer(transferDraft(false, false, base + 15, base + 1, base, 0), employee);
        row = transferAction(warehouse, row, transferItemPath(row, "deliver"), transferPhysical(row, null, false));
        verifyProxyReceiptRace(row, true);
    }

    private void verifyProxyReceiptRace(JsonNode row, boolean transfer) throws Exception {
        var context = transfer ? JSON.readTree(transferProxyContext(warehouse, row).body()) : proxyContext(row);
        var created = proxyCall(warehouse, "POST", "", proxyDraft(context), key(), null);
        assertEquals(200, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        assertEquals(201, proxyUpload(id, 0).statusCode());
        assertEquals(200, proxyCall(warehouse, "POST", "/" + id + "/submit", null, key(), 1L).statusCode());
        assertEquals(200, proxyCall(admin, "POST", "/" + id + "/decision", proxyDecision(true), key(), 2L).statusCode());
        var receiver = transfer ? login(base + 15) : employee;
        String receipt = transfer ? transferReceipt(receiver, row, "RQ-ASSET-" + base) : receiptBody(row, 0, "RQ-ASSET-" + base);
        String normalPath = transfer ? "/receipts/" + context.path("itemId").asText() + "/receive" : path(row, 0, "receive");
        var start = new java.util.concurrent.CountDownLatch(1);
        var proxy = CompletableFuture.supplyAsync(() -> proxyRaceResult(start, () ->
                proxyCall(warehouse, "POST", "/" + id + "/execute", null, key(), 3L).statusCode()));
        var normal = CompletableFuture.supplyAsync(() -> proxyRaceResult(start, () ->
                (transfer ? transferCall(receiver, "POST", normalPath, receipt, key(), row.path("version").asLong())
                        : call(receiver, "POST", normalPath, receipt, key(), row.path("version").asLong())).statusCode()));
        start.countDown();
        int proxyStatus = proxy.get(30, TimeUnit.SECONDS), normalStatus = normal.get(30, TimeUnit.SECONDS);
        assertEquals(java.util.List.of(200, 409), java.util.stream.Stream.of(proxyStatus, normalStatus).sorted().toList());
        assertEquals(base + (transfer ? 15 : 10), jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 50));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
        assertEquals(proxyStatus == 200 ? 1 : 0, count("SELECT COUNT(*) FROM fams_business_result_notice WHERE execution_id=?", id));
        String sql = transfer ? "SELECT COUNT(*) FROM fams_transfer_event WHERE request_id=? AND event_type IN ('TRANSFER_RECEIVE','TRANSFER_PROXY_RECEIVED')"
                : "SELECT COUNT(*) FROM fams_requisition_handover_event WHERE allocation_id=? AND event_type IN ('RECEIVED','PROXY_RECEIVED')";
        assertEquals(1, count(sql, transfer ? row.path("id").asText() : context.path("itemId").asText()));
        assertEquals(proxyStatus == 200 ? "EXECUTED" : "APPROVED",
                jdbc.queryForObject("SELECT confirmation_status FROM fams_proxy_confirmation WHERE id=?", String.class, id));
    }

    private int proxyRaceResult(java.util.concurrent.CountDownLatch start, java.util.concurrent.Callable<Integer> action) {
        try {
            start.await();
            return action.call();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private String proxyMiniLogin(long account, String suffix) throws Exception {
        String code = "proxy-mini-" + base + "-" + suffix;
        jdbc.update("INSERT INTO fams_mini_binding (account_id,identity_hash,created_at) VALUES (?,?,?)", account,
                miniSessions.identityHash(new MiniWechatIdentityPort.Identity("requisition-test-app", code)), Timestamp.from(Instant.now()));
        var response = proxyMiniCall(null, "POST", "/auth/login", JSON.writeValueAsString(java.util.Map.of("code", code)));
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body()).path("sessionToken").asText();
    }

    private HttpResponse<String> proxyMiniCall(String token, String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/mini" + path));
        if (token != null) { request.header("X-FAMS-Mini-Session", token); }
        if ("POST".equals(method)) {
            request.header("Content-Type", "application/json").POST(body == null
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        } else { request.GET(); }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest proxyMiniUploadRequest(String token, String id, String identity) {
        String boundary = "owned-mini-upload-boundary";
        String data = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"signed.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n%PDF-1.7 signed asset list\r\n--" + boundary + "--\r\n";
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/mini/proxy-confirmations/" + id + "/attachments"))
                .header("X-FAMS-Mini-Session", token).header("Idempotency-Key", identity).header("If-Match-Version", "0")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofString(data)).build();
    }

    private void verifyNativeProxyHandlers(JsonNode row, boolean transfer) throws Exception {
        String business = transfer ? "TRANSFER" : "REQUISITION", prefix = "proxy-native-" + base;
        long recipient = base + (transfer ? 15 : 10);
        for (var entry : java.util.Map.of("warehouse", base + 11, "admin", base + 13, "recipient", recipient).entrySet()) {
            String code = prefix + "-" + entry.getKey();
            jdbc.update("INSERT INTO fams_mini_binding (account_id,identity_hash,created_at) VALUES (?,?,?)",
                    entry.getValue(), miniSessions.identityHash(
                            new MiniWechatIdentityPort.Identity("requisition-test-app", code)), Timestamp.from(Instant.now()));
        }
        var script = java.nio.file.Path.of(System.getProperty("user.dir")).getParent()
                .resolve("apps/miniprogram/test/runtime-proxy-confirmation-http.mjs");
        String node = System.getenv("FAMS_TEST_NODE_EXECUTABLE");
        var builder = new ProcessBuilder("rtk", "proxy", node == null ? "node" : node, script.toString());
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_PROXY_BUSINESS", business);
        builder.environment().put("FAMS_TEST_PROXY_REQUEST_ID", row.path("id").asText());
        String item = row.path(transfer ? "items" : "allocations").get(0).path("id").asText();
        builder.environment().put("FAMS_TEST_PROXY_ITEM_ID", item);
        for (String role : new String[] {"warehouse", "admin", "recipient"}) {
            builder.environment().put("FAMS_TEST_PROXY_" + role.toUpperCase(java.util.Locale.ROOT) + "_CODE", prefix + "-" + role);
        }
        builder.redirectErrorStream(true);
        var process = builder.start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertEquals(recipient, jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 50));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
        Long proposal = jdbc.queryForObject("SELECT id FROM fams_proxy_confirmation WHERE business_type=? AND business_id=? AND item_id=?",
                Long.class, business, row.path("id").asText(), item);
        assertEquals("EXECUTED", jdbc.queryForObject("SELECT confirmation_status FROM fams_proxy_confirmation WHERE id=?", String.class, proposal));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_business_result_notice WHERE execution_id=? AND recipient_employee_id=? AND read_at IS NOT NULL", proposal, recipient));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_attachment WHERE business_type='PROXY_CONFIRMATION' AND business_id=?", proposal));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_todo WHERE business_type=? AND business_id=? AND todo_type='PROXY_RECEIPT_REVIEW' AND closed_at IS NULL", business, row.path("id").asText()));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_audit_event WHERE action='RESULT_NOTICE_OBJECTION' AND actor_account_id=?", recipient));
        String eventSql = transfer ? "SELECT COUNT(*) FROM fams_transfer_event WHERE request_id=? AND event_type='TRANSFER_PROXY_RECEIVED'"
                : "SELECT COUNT(*) FROM fams_requisition_handover_event WHERE allocation_id=? AND event_type='PROXY_RECEIVED'";
        assertEquals(1, count(eventSql, transfer ? row.path("id").asText() : item));
    }

    @org.junit.jupiter.api.AfterAll
    static void removeOnlyOwnedProxyStorage() throws Exception {
        if (proxyRoot != null) {
            try (var files = java.nio.file.Files.walk(proxyRoot)) {
                for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    java.nio.file.Files.delete(file);
                }
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "d6.15.proxy.runtime", matches = "true")
    void proxyConfirmationEvidenceApprovalExecutionAndReplayAreAtomic() throws Exception {
        var value = allocated(1);
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        var snapshot = proxyContext(value);
        String input = proxyDraft(snapshot);
        assertEquals(403, proxyCall(employee, "POST", "", input, key(), null).statusCode());
        var created = proxyCall(warehouse, "POST", "", input, key(), null);
        assertEquals(200, created.statusCode(), created.body());
        var draft = JSON.readTree(created.body());
        String id = draft.path("id").asText();
        assertEquals(
                409,
                proxyCall(warehouse, "POST", "/" + id + "/submit", null, key(), 0L).statusCode());
        var uploaded = proxyUpload(id, 0);
        assertEquals(201, uploaded.statusCode(), uploaded.body());
        var submitted = proxyCall(warehouse, "POST", "/" + id + "/submit", null, key(), 1L);
        assertEquals(200, submitted.statusCode(), submitted.body());
        assertEquals(409, proxyUpload(id, 2).statusCode());
        assertEquals(
                403,
                proxyCall(warehouse, "POST", "/" + id + "/decision", proxyDecision(true), key(), 2L)
                        .statusCode());
        var approved =
                proxyCall(admin, "POST", "/" + id + "/decision", proxyDecision(true), key(), 2L);
        assertEquals(200, approved.statusCode(), approved.body());
        assertEquals(
                "IN_STOCK",
                jdbc.queryForObject(
                        "SELECT lifecycle_status FROM fams_asset WHERE id=?",
                        String.class,
                        base + 50));
        assertEquals(
                0,
                count("SELECT COUNT(*) FROM fams_business_result_notice WHERE execution_id=?", id));
        assertEquals(
                409, proxyCall(other, "POST", "/" + id + "/execute", null, key(), 3L).statusCode());
        String identity = key();
        var executed = proxyCall(warehouse, "POST", "/" + id + "/execute", null, identity, 3L);
        assertEquals(200, executed.statusCode(), executed.body());
        assertEquals(
                executed.body(),
                proxyCall(warehouse, "POST", "/" + id + "/execute", null, identity, 3L).body());
        assertEquals(
                "IN_USE",
                jdbc.queryForObject(
                        "SELECT lifecycle_status FROM fams_asset WHERE id=?",
                        String.class,
                        base + 50));
        assertEquals(
                base + 10,
                jdbc.queryForObject(
                        "SELECT responsible_employee_id FROM fams_asset WHERE id=?",
                        Long.class,
                        base + 50));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_business_result_notice WHERE execution_id=? AND"
                            + " recipient_employee_id=?",
                        id,
                        base + 10));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_requisition_handover_event WHERE allocation_id=?"
                            + " AND event_type='PROXY_RECEIVED'",
                        snapshot.path("itemId").asText()));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND"
                            + " released_at IS NULL",
                        base + 50));
        assertEquals(1, JSON.readTree(proxyCall(admin, "GET", "/" + id + "/attachments",
                null, null, null).body()).path("items").size());
        var inbox = JSON.readTree(noticeCall(employee, "GET", "").body());
        assertEquals(1, inbox.path("total").asInt());
        assertEquals(1, inbox.path("unread").asInt());
        String noticeId = inbox.path("items").get(0).path("id").asText();
        assertEquals(0, JSON.readTree(noticeCall(other, "GET", "").body()).path("total").asInt());
        assertEquals(404, noticeCall(other, "POST", "/" + noticeId + "/read").statusCode());
        assertEquals(200, noticeCall(employee, "POST", "/" + noticeId + "/read").statusCode());
        assertEquals(200, noticeCall(employee, "POST", "/" + noticeId + "/read").statusCode());
        assertEquals(0, JSON.readTree(noticeCall(employee, "GET", "").body()).path("unread").asInt());
        String objectionKey = key();
        String objectionTrigger = "owned_proxy_objection_" + base;
        jdbc.execute("CREATE TRIGGER " + objectionTrigger + " BEFORE INSERT ON fams_audit_event FOR EACH ROW BEGIN "
                + "IF NEW.resource_type='BUSINESS_RESULT_NOTICE' AND NEW.resource_id=" + noticeId
                + " AND NEW.action='RESULT_NOTICE_OBJECTION' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned objection failure'; END IF; END");
        try {
            assertEquals(500, noticeObjection(employee, noticeId, "Please check the signed delivery record", objectionKey).statusCode());
            assertEquals(0, count("SELECT COUNT(*) FROM fams_todo WHERE business_type='REQUISITION'"
                    + " AND business_id=? AND todo_type='PROXY_RECEIPT_REVIEW'", snapshot.path("businessId").asText()));
            assertEquals(0, count("SELECT COUNT(*) FROM fams_idempotency_record WHERE actor_account_id=?"
                    + " AND operation_id='requestBusinessResultReview' AND idempotency_key=?", base + 10, objectionKey));
        } finally {
            jdbc.execute("DROP TRIGGER " + objectionTrigger);
        }
        assertEquals(404, noticeObjection(other, noticeId, "Not the recipient", key()).statusCode());
        var objection = noticeObjection(employee, noticeId, "Please check the signed delivery record", objectionKey);
        assertEquals(200, objection.statusCode(), objection.body());
        assertEquals(JSON.readTree(objection.body()), JSON.readTree(noticeObjection(employee, noticeId,
                "Please check the signed delivery record", objectionKey).body()));
        assertEquals(409, noticeObjection(employee, noticeId, "Changed report under same key", objectionKey).statusCode());
        assertEquals(1, count("SELECT COUNT(*) FROM fams_todo WHERE business_type='REQUISITION'"
                + " AND business_id=? AND todo_type='PROXY_RECEIPT_REVIEW' AND closed_at IS NULL", snapshot.path("businessId").asText()));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_audit_event WHERE resource_type='BUSINESS_RESULT_NOTICE'"
                + " AND resource_id=? AND action='RESULT_NOTICE_OBJECTION'", noticeId));
        assertTrue(JSON.readTree(noticeCall(employee, "GET", "").body()).path("items").get(0).path("reviewRequested").asBoolean());
        assertEquals(403, noticeCall(employee, "GET", "/review-requests").statusCode());
        var reviewQueue = noticeCall(admin, "GET", "/review-requests");
        assertEquals(200, reviewQueue.statusCode(), reviewQueue.body());
        var review = JSON.readTree(reviewQueue.body()).path("items").get(0);
        assertEquals(noticeId, review.path("noticeId").asText());
        assertEquals("Please check the signed delivery record", review.path("reason").asText());
        assertEquals(id, review.path("executionId").asText());
        assertEquals("IN_USE", jdbc.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=?",
                String.class, base + 50));
    }

    @Test
    @EnabledIfSystemProperty(named = "d6.15.proxy.runtime", matches = "true")
    void proxyConfirmationLateNoticeFailureRollsBackBorrowingThenRetriesAndRejectsEvidenceDrift()
            throws Exception {
        var value = allocatedDraft(1, borrowDraft("2099-12-31"));
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        var snapshot = proxyContext(value);
        var created = proxyCall(warehouse, "POST", "", proxyDraft(snapshot), key(), null);
        assertEquals(200, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        var uploaded = proxyUpload(id, 0);
        assertEquals(201, uploaded.statusCode(), uploaded.body());
        String object = JSON.readTree(uploaded.body()).path("objectId").asText();
        assertEquals(
                200,
                proxyCall(warehouse, "POST", "/" + id + "/submit", null, key(), 1L).statusCode());
        jdbc.update(
                "UPDATE fams_attachment SET content_sha256=REPEAT('0',64) WHERE object_id=?",
                object);
        var rejected =
                proxyCall(admin, "POST", "/" + id + "/decision", proxyDecision(true), key(), 2L);
        assertEquals(409, rejected.statusCode(), rejected.body());
        String digest =
                java.util.HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                java.nio.file.Files.readAllBytes(
                                                        proxyRoot.resolve(object))));
        jdbc.update(
                "UPDATE fams_attachment SET content_sha256=? WHERE object_id=?", digest, object);
        var approved =
                proxyCall(admin, "POST", "/" + id + "/decision", proxyDecision(true), key(), 2L);
        assertEquals(200, approved.statusCode(), approved.body());
        String trigger = "d615_proxy_notice_" + base;
        jdbc.execute(
                "CREATE TRIGGER "
                        + trigger
                        + " BEFORE INSERT ON fams_business_result_notice FOR EACH ROW BEGIN IF"
                        + " NEW.execution_id="
                        + id
                        + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned notice failure';"
                        + " END IF; END");
        String identity = key();
        try {
            assertEquals(
                    500,
                    proxyCall(warehouse, "POST", "/" + id + "/execute", null, identity, 3L)
                            .statusCode());
            assertEquals(
                    "IN_STOCK",
                    jdbc.queryForObject(
                            "SELECT lifecycle_status FROM fams_asset WHERE id=?",
                            String.class,
                            base + 50));
            assertEquals(0, count("SELECT COUNT(*) FROM fams_loan WHERE asset_id=?", base + 50));
            assertEquals(
                    1,
                    count(
                            "SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND"
                                + " released_at IS NULL",
                            base + 50));
            assertEquals(
                    "APPROVED",
                    jdbc.queryForObject(
                            "SELECT confirmation_status FROM fams_proxy_confirmation WHERE id=?",
                            String.class,
                            id));
            assertEquals(
                    0,
                    count(
                            "SELECT COUNT(*) FROM fams_requisition_handover_event WHERE"
                                + " allocation_id=? AND event_type='PROXY_RECEIVED'",
                            snapshot.path("itemId").asText()));
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger);
        }
        var result = proxyCall(warehouse, "POST", "/" + id + "/execute", null, identity, 3L);
        assertEquals(200, result.statusCode(), result.body());
        assertEquals(
                "BORROWED",
                jdbc.queryForObject(
                        "SELECT lifecycle_status FROM fams_asset WHERE id=?",
                        String.class,
                        base + 50));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fams_loan WHERE asset_id=? AND"
                            + " relation_status='OPEN'",
                        base + 50));
    }

    @Test
    @EnabledIfSystemProperty(named = "d6.15.proxy.runtime", matches = "true")
    void proxyConfirmationTransferCommitsOriginalTargetOnlyAfterExecutionAndRollsBackLateFailure() throws Exception {
        personalTransferFixture();
        var row = claimedTransfer(transferDraft(false, false, base + 15, base + 1, base, 0), employee);
        row = transferAction(warehouse, row, transferItemPath(row, "deliver"), transferPhysical(row, null, false));
        var context = transferProxyContext(warehouse, row);
        assertEquals(200, context.statusCode(), context.body());
        var snapshot = JSON.readTree(context.body());
        assertEquals("TRANSFER", snapshot.path("businessType").asText());
        var created = proxyCall(warehouse, "POST", "", proxyDraft(snapshot), key(), null);
        assertEquals(200, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        assertEquals(201, proxyUpload(id, 0).statusCode());
        var submitted = proxyCall(warehouse, "POST", "/" + id + "/submit", null, key(), 1L);
        assertEquals(200, submitted.statusCode(), submitted.body());
        var approved = proxyCall(admin, "POST", "/" + id + "/decision", proxyDecision(true), key(), 2L);
        assertEquals(200, approved.statusCode(), approved.body());
        assertEquals(base + 10, jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 50));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_business_result_notice WHERE execution_id=?", id));
        jdbc.update("UPDATE fams_employee SET status='DISABLED' WHERE id=?", base + 15);
        assertEquals(409, proxyCall(warehouse, "POST", "/" + id + "/execute", null, key(), 3L).statusCode());
        jdbc.update("UPDATE fams_employee SET status='ACTIVE' WHERE id=?", base + 15);
        jdbc.update("UPDATE fams_asset_business_lock SET lock_version=lock_version+1 WHERE asset_id=? AND released_at IS NULL", base + 50);
        var stale = proxyCall(warehouse, "POST", "/" + id + "/execute", null, key(), 3L);
        assertEquals(409, stale.statusCode(), stale.body());
        assertEquals("PROXY_RECEIPT_SCOPE_CHANGED", JSON.readTree(stale.body()).path("code").asText());
        jdbc.update("UPDATE fams_asset_business_lock SET lock_version=lock_version-1 WHERE asset_id=? AND released_at IS NULL", base + 50);
        String trigger = "d615_transfer_notice_" + base, identity = key();
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON fams_business_result_notice FOR EACH ROW BEGIN IF NEW.execution_id=" + id
                + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned transfer notice failure'; END IF; END");
        try {
            assertEquals(500, proxyCall(warehouse, "POST", "/" + id + "/execute", null, identity, 3L).statusCode());
            assertEquals(base + 10, jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 50));
            assertEquals("DELIVERED", jdbc.queryForObject("SELECT item_status FROM fams_transfer_item WHERE id=?", String.class, snapshot.path("itemId").asText()));
            assertEquals("APPROVED", jdbc.queryForObject("SELECT confirmation_status FROM fams_proxy_confirmation WHERE id=?", String.class, id));
            assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
            assertEquals(0, count("SELECT COUNT(*) FROM fams_transfer_event WHERE request_id=? AND event_type='TRANSFER_PROXY_RECEIVED'", snapshot.path("businessId").asText()));
        } finally { jdbc.execute("DROP TRIGGER " + trigger); }
        var executed = proxyCall(warehouse, "POST", "/" + id + "/execute", null, identity, 3L);
        assertEquals(200, executed.statusCode(), executed.body());
        assertEquals(JSON.readTree(executed.body()), JSON.readTree(proxyCall(warehouse, "POST", "/" + id + "/execute", null, identity, 3L).body()));
        assertEquals(base + 15, jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 50));
        assertEquals(base + 1, jdbc.queryForObject("SELECT using_organization_id FROM fams_asset WHERE id=?", Long.class, base + 50));
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT request_status FROM fams_transfer WHERE id=?", String.class, snapshot.path("businessId").asText()));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_transfer_event WHERE request_id=? AND event_type='TRANSFER_PROXY_RECEIVED'", snapshot.path("businessId").asText()));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_business_result_notice WHERE execution_id=? AND business_type='TRANSFER' AND recipient_employee_id=?", id, base + 15));
        var target = login(base + 15);
        var notice = JSON.readTree(noticeCall(target, "GET", "").body()).path("items").get(0);
        assertEquals("TRANSFER", notice.path("businessType").asText());
        assertEquals(200, noticeObjection(target, notice.path("id").asText(), "Please verify transfer proof", key()).statusCode());
        var review = JSON.readTree(noticeCall(admin, "GET", "/review-requests").body()).path("items").get(0);
        assertEquals("TRANSFER", review.path("businessType").asText());
        assertEquals(id, review.path("executionId").asText());
        assertEquals(0, JSON.readTree(noticeCall(employee, "GET", "").body()).path("total").asInt());
    }

    @Test
    @EnabledIfSystemProperty(named = "d6.15.proxy.runtime", matches = "true")
    void proxyConfirmationTransferReassignmentRevokesReplayAndInvalidatesOldApprovalSnapshot() throws Exception {
        personalTransferFixture();
        var row = claimedTransfer(transferDraft(false, false, base + 15, base + 1, base, 0), employee);
        assertEquals(409, transferProxyContext(warehouse, row).statusCode());
        row = transferAction(warehouse, row, transferItemPath(row, "deliver"), transferPhysical(row, null, false));
        assertEquals(409, transferProxyContext(other, row).statusCode());
        var snapshot = JSON.readTree(transferProxyContext(warehouse, row).body());
        String body = proxyDraft(snapshot), createKey = key(), submitKey = key();
        var created = proxyCall(warehouse, "POST", "", body, createKey, null);
        assertEquals(200, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        assertEquals(201, proxyUpload(id, 0).statusCode());
        assertEquals(200, proxyCall(warehouse, "POST", "/" + id + "/submit", null, submitKey, 1L).statusCode());
        assertEquals(200, proxyCall(admin, "POST", "/" + id + "/decision", proxyDecision(true), key(), 2L).statusCode());
        var check = reassignmentSnapshot(row);
        row = transferAction(admin, row, "/reassignment", reassignmentBody(check, base + 12));
        for (var response : java.util.List.of(proxyCall(warehouse, "POST", "", body, createKey, null),
                proxyCall(warehouse, "POST", "/" + id + "/submit", null, submitKey, 1L),
                proxyCall(warehouse, "POST", "/" + id + "/execute", null, key(), 3L))) {
            assertEquals(409, response.statusCode(), response.body());
            assertEquals("TRANSFER_NOT_PROCESSOR", JSON.readTree(response.body()).path("code").asText());
        }
        assertEquals(200, proxyCall(other, "GET", "/" + id, null, null, null).statusCode());
        var stale = proxyCall(other, "POST", "/" + id + "/execute", null, key(), 3L);
        assertEquals(409, stale.statusCode(), stale.body());
        assertEquals("PROXY_RECEIPT_SCOPE_CHANGED", JSON.readTree(stale.body()).path("code").asText());
        assertEquals(200, transferProxyContext(other, row).statusCode());
        assertEquals(base + 10, jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 50));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_business_result_notice WHERE execution_id=?", id));
    }

    @Test
    @EnabledIfSystemProperty(named = "d6.15.proxy.runtime", matches = "true")
    void proxyConfirmationTransferCannotOverrideRecipientRefusalOrRecovery() throws Exception {
        personalTransferFixture();
        var row = claimedTransfer(transferDraft(false, false, base + 15, base + 1, base, 0), employee);
        row = transferAction(warehouse, row, transferItemPath(row, "deliver"), transferPhysical(row, null, false));
        var snapshot = JSON.readTree(transferProxyContext(warehouse, row).body());
        var created = proxyCall(warehouse, "POST", "", proxyDraft(snapshot), key(), null);
        assertEquals(200, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        assertEquals(201, proxyUpload(id, 0).statusCode());
        assertEquals(200, proxyCall(warehouse, "POST", "/" + id + "/submit", null, key(), 1L).statusCode());
        assertEquals(200, proxyCall(admin, "POST", "/" + id + "/decision", proxyDecision(true), key(), 2L).statusCode());
        var target = login(base + 15);
        var refused = transferCall(target, "POST", "/receipts/" + snapshot.path("itemId").asText() + "/refuse",
                transferReceipt(target, row, "wrong"), key(), row.path("version").asLong());
        assertEquals(200, refused.statusCode(), refused.body());
        row = JSON.readTree(transferCall(employee, "GET", "/" + row.path("id").asText(), null, null, null).body());
        assertEquals("REFUSED", row.path("items").get(0).path("itemStatus").asText());
        assertEquals(409, transferProxyContext(warehouse, row).statusCode());
        assertEquals(409, proxyCall(warehouse, "POST", "/" + id + "/execute", null, key(), 3L).statusCode());
        row = transferAction(warehouse, row, transferItemPath(row, "recovery"), transferPhysical(row, null, false));
        assertEquals("RECOVERY_PENDING", row.path("items").get(0).path("itemStatus").asText());
        assertEquals(409, transferProxyContext(warehouse, row).statusCode());
        assertEquals(409, proxyCall(warehouse, "POST", "/" + id + "/execute", null, key(), 3L).statusCode());
        assertEquals(base + 10, jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 50));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_business_result_notice WHERE execution_id=?", id));
    }

    private HttpResponse<String> transferProxyContext(Session session, JsonNode row) throws Exception {
        return proxyCall(session, "GET", "/context?businessType=TRANSFER&businessId=" + row.path("id").asText()
                + "&itemId=" + row.path("items").get(0).path("id").asText(), null, null, null);
    }

    @Test
    void approvedItemTargetChangeKeepsOriginalDemandAndLockUntilNewRecipientReceives() throws Exception {
        var value = allocated(2);
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        long allocation = value.path("allocations").get(0).path("id").asLong();
        String oldReceipt = receiptBody(value, 0, "RQ-ASSET-" + base);
        var originalDemand = jdbc.queryForMap("SELECT * FROM fams_requisition_demand WHERE request_id=?", value.path("id").asLong());
        var originalHandover = jdbc.queryForMap("SELECT * FROM fams_requisition_handover WHERE allocation_id=?", allocation);
        var snapshotReply = targetCall(warehouse, "GET", "/context?businessId=" + value.path("id").asText() + "&itemId=" + allocation, null, null, null);
        assertEquals(200, snapshotReply.statusCode(), snapshotReply.body());
        var snapshot = JSON.readTree(snapshotReply.body());
        String body = targetProposal(snapshot, base+14);
        String createKey = key();
        var created = targetCall(warehouse, "POST", "", body, createKey, snapshot.path("version").asLong());
        assertEquals(200, created.statusCode(), created.body());
        assertEquals(created.body(), targetCall(warehouse, "POST", "", body, createKey, snapshot.path("version").asLong()).body());
        assertEquals(403, targetCall(employee, "GET", "", null, null, null).statusCode());
        String id = JSON.readTree(created.body()).path("id").asText();
        String decision = "{\"result\":\"APPROVED\",\"reason\":\"Verified new target and retained physical delivery\"}";
        assertEquals(403, targetCall(warehouse, "POST", "/"+id+"/decision", decision, key(), 0L).statusCode());
        String decisionKey = key();
        var approved = targetCall(admin, "POST", "/"+id+"/decision", decision, decisionKey, 0L);
        assertEquals(200, approved.statusCode(), approved.body());
        assertEquals(approved.body(), targetCall(admin, "POST", "/"+id+"/decision", decision, decisionKey, 0L).body());
        assertEquals(originalDemand, jdbc.queryForMap("SELECT * FROM fams_requisition_demand WHERE request_id=?", value.path("id").asLong()));
        assertEquals(originalHandover, jdbc.queryForMap("SELECT * FROM fams_requisition_handover WHERE allocation_id=?", allocation));
        assertEquals("IN_STOCK", jdbc.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=?", String.class, base+50));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base+50));
        assertEquals(404, call(employee, "POST", path(value,0,"receive"), oldReceipt, key(), value.path("version").asLong()).statusCode());
        assertEquals(404, call(employee, "POST", path(value,0,"grant"), null, key(), null).statusCode());
        var pending = JSON.readTree(call(department,"GET","/receipts/pending",null,null,null).body());
        assertEquals(Long.toString(allocation), pending.path("items").get(0).path("id").asText());
        var grant = call(department,"POST",path(value,0,"grant"),null,key(),null);
        assertEquals(200,grant.statusCode(),grant.body());
        var header = JSON.readTree(call(warehouse,"GET","/"+value.path("id").asText(),null,null,null).body());
        String receipt = JSON.writeValueAsString(java.util.Map.of("grant",JSON.readTree(grant.body()).path("grant").asText(),"observedAssetCode","RQ-ASSET-"+base,"physicalChecked",true));
        var received = call(department,"POST",path(value,0,"receive"),receipt,key(),header.path("version").asLong());
        assertEquals(200,received.statusCode(),received.body());
        assertEquals(base+14,jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?",Long.class,base+50));
        assertEquals(0,count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL",base+50));
        assertEquals(409,targetCall(warehouse,"GET","/context?businessId="+value.path("id").asText()+"&itemId="+allocation,null,null,null).statusCode());
    }

    @Test
    void approvedItemTargetChangeValidatesNewReferencesAndStaleSnapshotWithoutChangingCustody() throws Exception {
        var value = allocated(1);
        value = warehouseWrite(value,0,"deliver",delivery(value,0));
        long allocation = value.path("allocations").get(0).path("id").asLong();
        String endpoint = "/context?businessId="+value.path("id").asText()+"&itemId="+allocation;
        // A disabled original recipient can be corrected; only the proposed references must be active.
        jdbc.update("UPDATE fams_employee SET status='DISABLED',version=version+1 WHERE id=?",base+10);
        var snapshot = JSON.readTree(targetCall(warehouse,"GET",endpoint,null,null,null).body());
        String body = targetProposal(snapshot,base+14);
        var created = targetCall(warehouse,"POST","",body,key(),snapshot.path("version").asLong());
        assertEquals(200,created.statusCode(),created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        jdbc.update("UPDATE fams_employee SET version=version+1 WHERE id=?",base+14);
        String decision = "{\"result\":\"APPROVED\",\"reason\":\"Target checked\"}";
        assertEquals(409,targetCall(admin,"POST","/"+id+"/decision",decision,key(),0L).statusCode());
        assertEquals("PENDING",jdbc.queryForObject("SELECT change_status FROM fams_item_target_change WHERE id=?",String.class,id));
        assertEquals(0,count("SELECT COUNT(*) FROM fams_requisition_allocation WHERE id=? AND approved_target_json IS NOT NULL",allocation));
        String reject = "{\"result\":\"REJECTED\",\"reason\":\"References changed, reapply\"}";
        assertEquals(200,targetCall(admin,"POST","/"+id+"/decision",reject,key(),0L).statusCode());
        assertEquals("IN_STOCK",jdbc.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=?",String.class,base+50));
    }

    @Test
    void approvedItemTargetChangeRollbackAndNegativeGuardsPreservePhysicalFacts() throws Exception {
        var value = allocated(1);
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        long allocation = value.path("allocations").get(0).path("id").asLong();
        String endpoint = "/context?businessId=" + value.path("id").asText() + "&itemId=" + allocation;
        var reply = targetCall(warehouse, "GET", endpoint, null, null, null);
        var snapshot = JSON.readTree(reply.body());
        long version = snapshot.path("version").asLong();
        String body = targetProposal(snapshot, base + 14);
        assertEquals(409, targetCall(other, "GET", endpoint, null, null, null).statusCode());
        assertEquals(403, targetCall(employee, "POST", "", body, key(), version).statusCode());
        assertEquals(409, targetCall(warehouse, "POST", "", body, key(), version + 1).statusCode());
        var bad = (tools.jackson.databind.node.ObjectNode) JSON.readTree(body);
        bad.put("physicalChecked", false);
        assertEquals(400, targetCall(warehouse, "POST", "", bad.toString(), key(), version).statusCode());
        bad.put("physicalChecked", true).put("observedAssetCode", "wrong");
        assertEquals(409, targetCall(warehouse, "POST", "", bad.toString(), key(), version).statusCode());
        bad.put("observedAssetCode", snapshot.path("assetCode").asText());
        bad.set("target", snapshot.path("target"));
        assertEquals(400, targetCall(warehouse, "POST", "", bad.toString(), key(), version).statusCode());
        String createKey = key();
        var created = targetCall(warehouse, "POST", "", body, createKey, version);
        assertEquals(200, created.statusCode(), created.body());
        assertEquals(409, targetCall(warehouse, "POST", "", body, key(), version).statusCode());
        bad = (tools.jackson.databind.node.ObjectNode) JSON.readTree(body);
        bad.put("reason", "Changed payload with original identity");
        assertEquals(409, targetCall(warehouse, "POST", "", bad.toString(), createKey, version).statusCode());
        String id = JSON.readTree(created.body()).path("id").asText();
        String decision = "{\"result\":\"APPROVED\",\"reason\":\"Physical destination and target verified\"}";
        assertEquals(409, targetCall(admin, "POST", "/" + id + "/decision", decision, key(), 1L).statusCode());
        assertEquals(404, targetCall(admin, "POST", "/999999999/decision", decision, key(), 0L).statusCode());
        var before = jdbc.queryForMap("SELECT * FROM fams_requisition_allocation WHERE id=?", allocation);
        String trigger = "d615_target_fault_" + base;
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON fams_requisition_handover_event"
                + " FOR EACH ROW BEGIN IF NEW.event_type='TARGET_CHANGED' THEN SIGNAL SQLSTATE '45000'"
                + " SET MESSAGE_TEXT='owned late target change fault'; END IF; END");
        String identity = key();
        try {
            assertEquals(500, targetCall(admin, "POST", "/" + id + "/decision", decision, identity, 0L).statusCode());
            assertEquals(before, jdbc.queryForMap("SELECT * FROM fams_requisition_allocation WHERE id=?", allocation));
            assertEquals("PENDING", jdbc.queryForObject("SELECT change_status FROM fams_item_target_change WHERE id=?", String.class, id));
            assertEquals(0, count("SELECT COUNT(*) FROM fams_idempotency_record WHERE idempotency_key=?", identity));
            assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
        } finally { jdbc.execute("DROP TRIGGER " + trigger); }
        assertEquals(200, targetCall(admin, "POST", "/" + id + "/decision", decision, identity, 0L).statusCode());
        assertEquals(1, count("SELECT COUNT(*) FROM fams_requisition_handover_event WHERE allocation_id=? AND event_type='TARGET_CHANGED'", allocation));
        assertEquals("IN_STOCK", jdbc.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=?", String.class, base + 50));
    }

    @Test
    void approvedItemTargetChangeBorrowOpensOnlyNewBorrowerAndReassignmentRevokesProposalReplay() throws Exception {
        var value = allocatedDraft(1, borrowDraft("2099-12-31"));
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        String endpoint = "/context?businessId=" + value.path("id").asText() + "&itemId=" + value.path("allocations").get(0).path("id").asText();
        var snapshot = JSON.readTree(targetCall(warehouse, "GET", endpoint, null, null, null).body());
        String body = targetProposal(snapshot, base + 14);
        String createKey = key();
        var created = targetCall(warehouse, "POST", "", body, createKey, snapshot.path("version").asLong());
        assertEquals(200, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        String decision = "{\"result\":\"APPROVED\",\"reason\":\"New borrower and unchanged due date checked\"}";
        // B2 separately tests actual reassignment; inject its persisted owner/version effect here.
        jdbc.update("UPDATE fams_requisition_ownership SET processor_account_id=?,ownership_version=ownership_version+1 WHERE request_id=? AND archived_at IS NULL", base + 12, value.path("id").asLong());
        assertEquals(409, targetCall(warehouse, "POST", "", body, createKey, snapshot.path("version").asLong()).statusCode());
        assertEquals(409, targetCall(admin, "POST", "/" + id + "/decision", decision, key(), 0L).statusCode());
        String reject = "{\"result\":\"REJECTED\",\"reason\":\"Processor changed; new owner must verify\"}";
        assertEquals(200, targetCall(admin, "POST", "/" + id + "/decision", reject, key(), 0L).statusCode());
        snapshot = JSON.readTree(targetCall(other, "GET", endpoint, null, null, null).body());
        created = targetCall(other, "POST", "", targetProposal(snapshot, base + 14), key(), snapshot.path("version").asLong());
        assertEquals(200, created.statusCode(), created.body());
        id = JSON.readTree(created.body()).path("id").asText();
        assertEquals(200, targetCall(admin, "POST", "/" + id + "/decision", decision, key(), 0L).statusCode());
        assertEquals(0, count("SELECT COUNT(*) FROM fams_loan WHERE asset_id=?", base + 50));
        var grant = call(department, "POST", path(value, 0, "grant"), null, key(), null);
        assertEquals(200, grant.statusCode(), grant.body());
        var current = JSON.readTree(call(other, "GET", "/" + value.path("id").asText(), null, null, null).body());
        String receipt = JSON.writeValueAsString(java.util.Map.of("grant", JSON.readTree(grant.body()).path("grant").asText(), "observedAssetCode", "RQ-ASSET-" + base, "physicalChecked", true));
        assertEquals(200, call(department, "POST", path(value, 0, "receive"), receipt, key(), current.path("version").asLong()).statusCode());
        assertEquals(base + 14, jdbc.queryForObject("SELECT borrower_employee_id FROM fams_loan WHERE asset_id=?", Long.class, base + 50));
        assertEquals("2099-12-31", jdbc.queryForObject("SELECT expected_return_date FROM fams_loan WHERE asset_id=?", java.sql.Date.class, base + 50).toString());
    }

    @Test
    void approvedItemTargetChangeAssetDriftReturnsConflictAndNeverCreatesProposal() throws Exception {
        var value = allocated(1);
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        String endpoint = "/context?businessId=" + value.path("id").asText() + "&itemId=" + value.path("allocations").get(0).path("id").asText();
        var snapshot = JSON.readTree(targetCall(warehouse, "GET", endpoint, null, null, null).body());
        jdbc.update("UPDATE fams_asset SET version=version+1 WHERE id=?", base + 50);
        var context = targetCall(warehouse, "GET", endpoint, null, null, null);
        assertEquals(409, context.statusCode(), context.body());
        assertEquals("ASSET_HANDOVER_CHANGED", JSON.readTree(context.body()).path("code").asText());
        var proposal = targetCall(warehouse, "POST", "", targetProposal(snapshot, base + 14), key(), snapshot.path("version").asLong());
        assertEquals(409, proposal.statusCode(), proposal.body());
        assertEquals(0, count("SELECT COUNT(*) FROM fams_item_target_change WHERE business_id=?", value.path("id").asLong()));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
    }

    @Test
    void approvedItemTargetChangeSharedRequiresCurrentDepartmentManagerEvenAfterApproval() throws Exception {
        var value = allocated(1);
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        String endpoint = "/context?businessId=" + value.path("id").asText() + "&itemId=" + value.path("allocations").get(0).path("id").asText();
        var snapshot = JSON.readTree(targetCall(warehouse, "GET", endpoint, null, null, null).body());
        var bad = (tools.jackson.databind.node.ObjectNode) JSON.readTree(targetProposal(snapshot, base + 10));
        ((tools.jackson.databind.node.ObjectNode) bad.get("target")).put("custodyType", "SHARED");
        var refused = targetCall(warehouse, "POST", "", bad.toString(), key(), snapshot.path("version").asLong());
        assertEquals(409, refused.statusCode(), refused.body());
        assertEquals("HANDOVER_SHARED_RECEIVER_INVALID", JSON.readTree(refused.body()).path("code").asText());
        ((tools.jackson.databind.node.ObjectNode) bad.get("target")).put("employeeId", Long.toString(base + 14));
        var created = targetCall(warehouse, "POST", "", bad.toString(), key(), snapshot.path("version").asLong());
        assertEquals(200, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        String decision = "{\"result\":\"APPROVED\",\"reason\":\"Valid department manager verified\"}";
        assertEquals(200, targetCall(admin, "POST", "/" + id + "/decision", decision, key(), 0L).statusCode());
        // Model persisted role revocation after approval; proxy cannot bypass the new target's current role.
        jdbc.update("DELETE FROM fams_account_role WHERE account_id=? AND role_code='DEPARTMENT_ASSET_MANAGER'", base + 14);
        var grant = call(department, "POST", path(value, 0, "grant"), null, key(), null);
        assertEquals(403, grant.statusCode(), grant.body());
        var proxy = proxyCall(warehouse, "GET", "/context?businessId=" + value.path("id").asText()
                + "&itemId=" + value.path("allocations").get(0).path("id").asText(), null, null, null);
        assertEquals(409, proxy.statusCode(), proxy.body());
        assertEquals("HANDOVER_SHARED_RECEIVER_INVALID", JSON.readTree(proxy.body()).path("code").asText());
        assertEquals("IN_STOCK", jdbc.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=?", String.class, base + 50));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
    }

    @Test
    void approvedItemTargetChangeAuditUsesActingWarehouseRoleForMultiRoleAccount() throws Exception {
        var value = allocated(1);
        jdbc.update("INSERT INTO fams_account_role (account_id,role_code,granted_at) VALUES (?,'ASSET_ADMIN',NOW(3))", base + 11);
        warehouse = login(base + 11);
        String endpoint = "/context?businessId=" + value.path("id").asText() + "&itemId=" + value.path("allocations").get(0).path("id").asText();
        var snapshot = JSON.readTree(targetCall(warehouse, "GET", endpoint, null, null, null).body());
        var created = targetCall(warehouse, "POST", "", targetProposal(snapshot, base + 14), key(), snapshot.path("version").asLong());
        assertEquals(200, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        assertEquals("WAREHOUSE_OPERATOR", jdbc.queryForObject("SELECT actor_role FROM fams_audit_event WHERE resource_type='ITEM_TARGET_CHANGE' AND resource_id=? AND action='TARGET_CHANGE_PROPOSED'", String.class, id));
        assertEquals("WAITING_DELIVERY", jdbc.queryForObject("SELECT allocation_status FROM fams_requisition_allocation WHERE id=?", String.class, value.path("allocations").get(0).path("id").asText()));
        String decision = "{\"result\":\"APPROVED\",\"reason\":\"Before delivery target checked\"}";
        assertEquals(200, targetCall(admin, "POST", "/" + id + "/decision", decision, key(), 0L).statusCode());
        assertEquals("ASSET_ADMIN", jdbc.queryForObject("SELECT actor_role FROM fams_audit_event WHERE resource_type='ITEM_TARGET_CHANGE' AND resource_id=? AND action='TARGET_CHANGE_APPROVED'", String.class, id));
        assertEquals("IN_STOCK", jdbc.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=?", String.class, base + 50));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
        value = JSON.readTree(call(warehouse, "GET", "/" + value.path("id").asText(), null, null, null).body());
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        assertEquals(Long.toString(base + 14), jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(target_snapshot_json,'$.employee.id')) FROM fams_requisition_handover WHERE allocation_id=?", String.class, value.path("allocations").get(0).path("id").asText()));
        assertEquals("Person 4", jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(target_snapshot_json,'$.employee.name')) FROM fams_requisition_handover WHERE allocation_id=?", String.class, value.path("allocations").get(0).path("id").asText()));
        assertEquals(base + 14, jdbc.queryForObject("SELECT temporary_employee_id FROM fams_requisition_handover WHERE allocation_id=?", Long.class, value.path("allocations").get(0).path("id").asText()));
        assertEquals(base + 10, jdbc.queryForObject("SELECT target_employee_id FROM fams_requisition_demand WHERE request_id=?", Long.class, value.path("id").asText()));
    }

    @Test
    void approvedItemTargetChangeLegacyOverrideFreezesItsApprovedTargetWhenDelivered() throws Exception {
        var value = allocated(1);
        String endpoint = "/context?businessId=" + value.path("id").asText() + "&itemId=" + value.path("allocations").get(0).path("id").asText();
        var snapshot = JSON.readTree(targetCall(warehouse, "GET", endpoint, null, null, null).body());
        var created = targetCall(warehouse, "POST", "", targetProposal(snapshot, base + 14), key(), snapshot.path("version").asLong());
        assertEquals(200, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        String decision = "{\"result\":\"APPROVED\",\"reason\":\"Before delivery approved target\"}";
        assertEquals(200, targetCall(admin, "POST", "/" + id + "/decision", decision, key(), 0L).statusCode());
        long allocation = value.path("allocations").get(0).path("id").asLong();
        jdbc.update("UPDATE fams_requisition_allocation SET approved_target_json=JSON_REMOVE(approved_target_json,'$.referenceSnapshot') WHERE id=?", allocation);
        value = JSON.readTree(call(warehouse, "GET", "/" + value.path("id").asText(), null, null, null).body());
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        assertEquals(Long.toString(base + 14), jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(target_snapshot_json,'$.employee.id')) FROM fams_requisition_handover WHERE allocation_id=?", String.class, allocation));
        assertEquals(0L, jdbc.queryForObject("SELECT CAST(JSON_EXTRACT(target_snapshot_json,'$.employee.version') AS UNSIGNED) FROM fams_requisition_handover WHERE allocation_id=?", Long.class, allocation));
        assertEquals(404, call(employee, "POST", path(value, 0, "grant"), null, key(), null).statusCode());
        assertEquals(200, call(department, "POST", path(value, 0, "grant"), null, key(), null).statusCode());
    }

    @Test
    void approvedTransferTargetChangeRetainsPhysicalFactsAndRequiresOnlyNewRecipient() throws Exception {
        personalTransferFixture();
        var row = claimedTransfer(transferDraft(false, false, base + 15, base + 1, base, 0), employee);
        row = transferAction(warehouse, row, transferItemPath(row, "deliver"), transferPhysical(row, null, false));
        String item = row.path("items").get(0).path("id").asText();
        var originalRecipient = login(base + 15);
        String originalReceipt = transferReceipt(originalRecipient, row, "RQ-ASSET-" + base);
        var beforeAsset = jdbc.queryForMap("SELECT * FROM fams_asset WHERE id=?", base + 50);
        var physical = jdbc.queryForMap("SELECT destination,delivery_evidence,delivered_at,receipt_deadline_at,source_snapshot_json,parameter_snapshot_json FROM fams_transfer_item WHERE id=?", item);
        var snapshot = transferTargetContext(warehouse, row, 0);
        String body = genericTargetProposal(snapshot, transferNewTarget(base + 12, base));
        String identity = key();
        var created = genericTargetCall(warehouse, "TRANSFER", "POST", "", body, identity, snapshot.path("version").asLong());
        assertEquals(200, created.statusCode(), created.body());
        var change = JSON.readTree(created.body());
        assertEquals(change, JSON.readTree(genericTargetCall(warehouse, "TRANSFER", "POST", "", body, identity, snapshot.path("version").asLong()).body()));
        String decision = "{\"result\":\"APPROVED\",\"reason\":\"Physical asset, destination and new target verified\"}";
        assertEquals(404, targetCall(admin, "POST", "/" + change.path("id").asText() + "/decision", decision, key(), 0L).statusCode());
        assertEquals(403, genericTargetCall(warehouse, "TRANSFER", "POST", "/" + change.path("id").asText() + "/decision", decision, key(), 0L).statusCode());
        String approvalKey = key();
        var approved = genericTargetCall(admin, "TRANSFER", "POST", "/" + change.path("id").asText() + "/decision", decision, approvalKey, 0L);
        assertEquals(200, approved.statusCode(), approved.body());
        assertEquals(JSON.readTree(approved.body()), JSON.readTree(genericTargetCall(admin, "TRANSFER", "POST", "/" + change.path("id").asText() + "/decision", decision, approvalKey, 0L).body()));
        assertEquals(beforeAsset, jdbc.queryForMap("SELECT * FROM fams_asset WHERE id=?", base + 50));
        assertEquals(physical, jdbc.queryForMap("SELECT destination,delivery_evidence,delivered_at,receipt_deadline_at,source_snapshot_json,parameter_snapshot_json FROM fams_transfer_item WHERE id=?", item));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
        assertEquals(404, transferCall(originalRecipient, "POST", "/receipts/" + item + "/grant", null, key(), null).statusCode());
        assertEquals(404, transferCall(originalRecipient, "POST", "/receipts/" + item + "/receive", originalReceipt, key(), row.path("version").asLong()).statusCode());
        assertEquals(0, JSON.readTree(transferCall(originalRecipient, "GET", "/receipts/pending", null, null, null).body()).path("total").asInt());
        assertEquals(item, JSON.readTree(transferCall(other, "GET", "/receipts/pending", null, null, null).body()).path("items").get(0).path("id").asText());
        row = JSON.readTree(transferCall(warehouse, "GET", "/" + row.path("id").asText(), null, null, null).body());
        String receive = transferReceipt(other, row, "RQ-ASSET-" + base);
        var received = transferCall(other, "POST", "/receipts/" + item + "/receive", receive, key(), row.path("version").asLong());
        assertEquals(200, received.statusCode(), received.body());
        assertEquals(base + 12, jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 50));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_transfer_event WHERE item_id=? AND event_type='TRANSFER_TARGET_CHANGED'", item));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_transfer_event WHERE item_id=? AND event_type='TRANSFER_RECEIVE'", item));
        assertEquals(Long.toString(base + 15), jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(source_snapshot_json,'$.target.employeeId')) FROM fams_item_target_change WHERE id=?", String.class, change.path("id").asText()));
    }

    @Test
    void approvedTransferTargetChangeReferenceDriftAndLateFailureRollbackThenReplay() throws Exception {
        personalTransferFixture();
        var row = claimedTransfer(transferDraft(false, false, base + 15, base + 1, base, 0), employee);
        row = transferAction(warehouse, row, transferItemPath(row, "deliver"), transferPhysical(row, null, false));
        String item = row.path("items").get(0).path("id").asText();
        var originalRecipient = login(base + 15);
        transferReceipt(originalRecipient, row, "RQ-ASSET-" + base);
        var snapshot = transferTargetContext(warehouse, row, 0);
        jdbc.update("UPDATE fams_asset SET version=version+1 WHERE id=?", base + 50);
        var drift = genericTargetCall(warehouse, "TRANSFER", "GET", "/context?businessId=" + row.path("id").asText() + "&itemId=" + item, null, null, null);
        assertEquals(409, drift.statusCode(), drift.body());
        assertEquals("TRANSFER_SOURCE_CHANGED", JSON.readTree(drift.body()).path("code").asText());
        jdbc.update("UPDATE fams_asset SET version=version-1 WHERE id=?", base + 50);
        var created = genericTargetCall(warehouse, "TRANSFER", "POST", "", genericTargetProposal(snapshot, transferNewTarget(base + 12, base)), key(), snapshot.path("version").asLong());
        assertEquals(200, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        String decision = "{\"result\":\"APPROVED\",\"reason\":\"Current new target verified\"}";
        jdbc.update("UPDATE fams_employee SET version=version+1 WHERE id=?", base + 12);
        assertEquals(409, genericTargetCall(admin, "TRANSFER", "POST", "/" + id + "/decision", decision, key(), 0L).statusCode());
        jdbc.update("UPDATE fams_employee SET version=version-1 WHERE id=?", base + 12);
        var before = jdbc.queryForMap("SELECT * FROM fams_transfer_item WHERE id=?", item);
        String trigger = "d615_target_transfer_fault_" + base;
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON fams_transfer_event FOR EACH ROW BEGIN IF NEW.event_type='TRANSFER_TARGET_CHANGED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned target change fault'; END IF; END");
        String identity = key();
        try {
            assertEquals(500, genericTargetCall(admin, "TRANSFER", "POST", "/" + id + "/decision", decision, identity, 0L).statusCode());
            assertEquals(before, jdbc.queryForMap("SELECT * FROM fams_transfer_item WHERE id=?", item));
            assertEquals("PENDING", jdbc.queryForObject("SELECT change_status FROM fams_item_target_change WHERE id=?", String.class, id));
            assertEquals(1, count("SELECT COUNT(*) FROM fams_transfer_receipt_grant WHERE item_id=? AND consumed_at IS NULL", item));
            assertEquals(0, count("SELECT COUNT(*) FROM fams_idempotency_record WHERE idempotency_key=?", identity));
        } finally { jdbc.execute("DROP TRIGGER " + trigger); }
        assertEquals(200, genericTargetCall(admin, "TRANSFER", "POST", "/" + id + "/decision", decision, identity, 0L).statusCode());
        assertEquals(1, count("SELECT COUNT(*) FROM fams_transfer_event WHERE item_id=? AND event_type='TRANSFER_TARGET_CHANGED'", item));
        assertEquals(base + 10, jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 50));
    }

    @Test
    void approvedTransferTargetChangeReassignmentRevokesReplayAndCompletedStaleProposalCanBeRejected() throws Exception {
        personalTransferFixture();
        var row = claimedTransfer(transferDraft(false, false, base + 15, base + 1, base, 0), employee);
        var snapshot = transferTargetContext(warehouse, row, 0);
        String body = genericTargetProposal(snapshot, transferNewTarget(base + 12, base)), identity = key();
        var created = genericTargetCall(warehouse, "TRANSFER", "POST", "", body, identity, snapshot.path("version").asLong());
        assertEquals(200, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        var ownership = reassignmentSnapshot(row);
        var reassigned = transferCall(admin, "POST", "/" + row.path("id").asText() + "/reassignment", reassignmentBody(ownership, base + 12), key(), row.path("version").asLong());
        assertEquals(200, reassigned.statusCode(), reassigned.body());
        row = JSON.readTree(reassigned.body());
        assertEquals(409, genericTargetCall(warehouse, "TRANSFER", "POST", "", body, identity, snapshot.path("version").asLong()).statusCode());
        String approve = "{\"result\":\"APPROVED\",\"reason\":\"Checked\"}", reject = "{\"result\":\"REJECTED\",\"reason\":\"Scope changed; reapply\"}";
        assertEquals(409, genericTargetCall(admin, "TRANSFER", "POST", "/" + id + "/decision", approve, key(), 0L).statusCode());
        assertEquals(200, genericTargetCall(admin, "TRANSFER", "POST", "/" + id + "/decision", reject, key(), 0L).statusCode());
        snapshot = transferTargetContext(other, row, 0);
        created = genericTargetCall(other, "TRANSFER", "POST", "", genericTargetProposal(snapshot, transferNewTarget(base + 13, base)), key(), snapshot.path("version").asLong());
        assertEquals(200, created.statusCode(), created.body());
        id = JSON.readTree(created.body()).path("id").asText();
        row = transferAction(other, row, transferItemPath(row, "deliver"), transferPhysical(row, null, false));
        var originalRecipient = login(base + 15);
        String receive = transferReceipt(originalRecipient, row, "RQ-ASSET-" + base);
        assertEquals(200, transferCall(originalRecipient, "POST", "/receipts/" + row.path("items").get(0).path("id").asText() + "/receive", receive, key(), row.path("version").asLong()).statusCode());
        assertEquals(409, genericTargetCall(admin, "TRANSFER", "POST", "/" + id + "/decision", approve, key(), 0L).statusCode());
        assertEquals(200, genericTargetCall(admin, "TRANSFER", "POST", "/" + id + "/decision", reject, key(), 0L).statusCode());
        assertEquals(base + 15, jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 50));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
    }

    @Test
    void approvedTransferTargetChangeRefusalRequiresNewReceiptAndRecoveryCannotBeOverridden() throws Exception {
        personalTransferFixture();
        var row = claimedTransfer(transferDraft(false, false, base + 15, base + 1, base, 0), employee);
        row = transferAction(warehouse, row, transferItemPath(row, "deliver"), transferPhysical(row, null, false));
        String item = row.path("items").get(0).path("id").asText();
        var originalRecipient = login(base + 15);
        String refusal = transferReceipt(originalRecipient, row, "RQ-ASSET-" + base);
        assertEquals(200, transferCall(originalRecipient, "POST", "/receipts/" + item + "/refuse", refusal, key(), row.path("version").asLong()).statusCode());
        row = JSON.readTree(transferCall(warehouse, "GET", "/" + row.path("id").asText(), null, null, null).body());
        var physical = jdbc.queryForMap("SELECT destination,delivered_at,receipt_deadline_at FROM fams_transfer_item WHERE id=?", item);
        var snapshot = transferTargetContext(warehouse, row, 0);
        var created = genericTargetCall(warehouse, "TRANSFER", "POST", "", genericTargetProposal(snapshot, transferNewTarget(base + 12, base)), key(), snapshot.path("version").asLong());
        assertEquals(200, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        String approve = "{\"result\":\"APPROVED\",\"reason\":\"Correct physical asset and new recipient verified\"}";
        assertEquals(200, genericTargetCall(admin, "TRANSFER", "POST", "/" + id + "/decision", approve, key(), 0L).statusCode());
        assertEquals("DELIVERED", jdbc.queryForObject("SELECT item_status FROM fams_transfer_item WHERE id=?", String.class, item));
        assertEquals(physical, jdbc.queryForMap("SELECT destination,delivered_at,receipt_deadline_at FROM fams_transfer_item WHERE id=?", item));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_transfer_event WHERE item_id=? AND event_type='TRANSFER_REFUSE'", item));
        row = JSON.readTree(transferCall(warehouse, "GET", "/" + row.path("id").asText(), null, null, null).body());
        snapshot = transferTargetContext(warehouse, row, 0);
        created = genericTargetCall(warehouse, "TRANSFER", "POST", "", genericTargetProposal(snapshot, transferNewTarget(base + 13, base)), key(), snapshot.path("version").asLong());
        assertEquals(200, created.statusCode(), created.body());
        id = JSON.readTree(created.body()).path("id").asText();
        row = transferAction(warehouse, row, transferItemPath(row, "recovery"), transferPhysical(row, null, false));
        assertEquals(409, genericTargetCall(warehouse, "TRANSFER", "GET", "/context?businessId=" + row.path("id").asText() + "&itemId=" + item, null, null, null).statusCode());
        assertEquals(409, genericTargetCall(admin, "TRANSFER", "POST", "/" + id + "/decision", approve, key(), 0L).statusCode());
        assertEquals(200, genericTargetCall(admin, "TRANSFER", "POST", "/" + id + "/decision", "{\"result\":\"REJECTED\",\"reason\":\"Physical recovery in progress\"}", key(), 0L).statusCode());
        assertEquals(base + 10, jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 50));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
    }

    @Test
    void approvedTransferTargetChangeLeavesCompletedSiblingImmutable() throws Exception {
        personalTransferFixture();
        jdbc.update("UPDATE fams_asset SET lifecycle_status='IN_USE',custody_type='PERSONAL',storage_warehouse_id=NULL,owning_organization_id=?,using_organization_id=?,responsible_employee_id=? WHERE id=?", base, base, base + 10, base + 51);
        var draft = (tools.jackson.databind.node.ObjectNode) JSON.readTree(transferDraft(false, false, base + 15, base + 1, base, 0));
        var items = (tools.jackson.databind.node.ArrayNode) draft.get("items");
        var second = items.get(0).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) second).put("assetId", Long.toString(base + 51));
        items.add(second);
        var row = claimedTransfer(draft.toString(), employee);
        row = transferAction(warehouse, row, transferItemPath(row, "deliver"), transferPhysical(row, null, false));
        var originalRecipient = login(base + 15);
        String receive = transferReceipt(originalRecipient, row, "RQ-ASSET-" + base);
        assertEquals(200, transferCall(originalRecipient, "POST", "/receipts/" + row.path("items").get(0).path("id").asText() + "/receive", receive, key(), row.path("version").asLong()).statusCode());
        row = JSON.readTree(transferCall(warehouse, "GET", "/" + row.path("id").asText(), null, null, null).body());
        String firstItem = row.path("items").get(0).path("id").asText();
        var completed = jdbc.queryForMap("SELECT * FROM fams_transfer_item WHERE id=?", firstItem);
        var completedAsset = jdbc.queryForMap("SELECT * FROM fams_asset WHERE id=?", base + 50);
        assertEquals(409, genericTargetCall(warehouse, "TRANSFER", "GET", "/context?businessId=" + row.path("id").asText() + "&itemId=" + firstItem, null, null, null).statusCode());
        var snapshot = transferTargetContext(warehouse, row, 1);
        var created = genericTargetCall(warehouse, "TRANSFER", "POST", "", genericTargetProposal(snapshot, transferNewTarget(base + 12, base)), key(), snapshot.path("version").asLong());
        assertEquals(200, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        assertEquals(200, genericTargetCall(admin, "TRANSFER", "POST", "/" + id + "/decision", "{\"result\":\"APPROVED\",\"reason\":\"Only unfinished sibling changed\"}", key(), 0L).statusCode());
        assertEquals(completed, jdbc.queryForMap("SELECT * FROM fams_transfer_item WHERE id=?", firstItem));
        assertEquals(completedAsset, jdbc.queryForMap("SELECT * FROM fams_asset WHERE id=?", base + 50));
        assertEquals("READY", jdbc.queryForObject("SELECT item_status FROM fams_transfer_item WHERE id=?", String.class, row.path("items").get(1).path("id").asText()));
        assertEquals(base + 10, jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 51));
    }

    @Test
    void approvedTransferTargetChangeStockApprovalCannotReplaceDestinationReceipt() throws Exception {
        jdbc.update("UPDATE fams_location SET location_type='BIN' WHERE id=?", base);
        jdbc.update("INSERT INTO fams_warehouse (id,code,code_normalized,name,status,created_at,updated_at) VALUES (?,?,?,'Target warehouse','ACTIVE',NOW(3),NOW(3))", base + 1, "TCWH" + base, "TCWH" + base);
        jdbc.update("INSERT INTO fams_location (id,code,code_normalized,name,location_type,warehouse_id,status,created_at,updated_at) VALUES (?,?,?,'Target bin','BIN',?,'ACTIVE',NOW(3),NOW(3))", base + 1, "TCBIN" + base, "TCBIN" + base, base + 1);
        var row = claimedTransfer(transferDraft(true, false, 0, 0, base + 1, base + 1), warehouse);
        var snapshot = transferTargetContext(warehouse, row, 0);
        var target = (tools.jackson.databind.node.ObjectNode) JSON.readTree(transferDraft(true, false, 0, 0, base + 1, base + 1)).path("items").get(0).path("target");
        target.put("owningOrganizationId", Long.toString(base + 1));
        var created = genericTargetCall(warehouse, "TRANSFER", "POST", "", genericTargetProposal(snapshot, target), key(), snapshot.path("version").asLong());
        assertEquals(200, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        assertEquals(200, genericTargetCall(admin, "TRANSFER", "POST", "/" + id + "/decision", "{\"result\":\"APPROVED\",\"reason\":\"Storage target reapproved\"}", key(), 0L).statusCode());
        assertEquals(base, jdbc.queryForObject("SELECT storage_warehouse_id FROM fams_asset WHERE id=?", Long.class, base + 50));
        row = JSON.readTree(transferCall(warehouse, "GET", "/" + row.path("id").asText(), null, null, null).body());
        assertEquals(400, transferCall(warehouse, "POST", "/" + row.path("id").asText() + transferItemPath(row, "verify"), transferPhysical(row, null, false), key(), row.path("version").asLong()).statusCode());
        row = transferAction(warehouse, row, transferItemPath(row, "verify"), transferPhysical(row, "Destination inspector", false));
        assertEquals(base + 1, jdbc.queryForObject("SELECT storage_warehouse_id FROM fams_asset WHERE id=?", Long.class, base + 50));
        assertEquals(base + 1, jdbc.queryForObject("SELECT owning_organization_id FROM fams_asset WHERE id=?", Long.class, base + 50));
        assertEquals("COMPLETED", row.path("requestStatus").asText());
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
    }

    @Test
    void approvedTransferTargetChangePreservesLargeRecipientIdentifierExactly() throws Exception {
        long large = 9007199254740993L + base;
        jdbc.update("INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,organization_id,status,created_at,updated_at) VALUES (?,?,?,'Large target',?,'ACTIVE',NOW(3),NOW(3))", large, "LARGETC" + base, "LARGETC" + base, base);
        extraTargetEmployee = large;
        jdbc.update("UPDATE fams_system_account SET employee_id=? WHERE id=?", large, base + 12);
        other = login(base + 12);
        personalTransferFixture();
        var row = claimedTransfer(transferDraft(false, false, base + 15, base + 1, base, 0), employee);
        row = transferAction(warehouse, row, transferItemPath(row, "deliver"), transferPhysical(row, null, false));
        var snapshot = transferTargetContext(warehouse, row, 0);
        var created = genericTargetCall(warehouse, "TRANSFER", "POST", "", genericTargetProposal(snapshot, transferNewTarget(large, base)), key(), snapshot.path("version").asLong());
        assertEquals(200, created.statusCode(), created.body());
        assertEquals(Long.toString(large), JSON.readTree(created.body()).path("proposed_target_json").path("employeeId").asText());
        String id = JSON.readTree(created.body()).path("id").asText();
        assertEquals(200, genericTargetCall(admin, "TRANSFER", "POST", "/" + id + "/decision", "{\"result\":\"APPROVED\",\"reason\":\"Exact recipient identity verified\"}", key(), 0L).statusCode());
        row = JSON.readTree(transferCall(warehouse, "GET", "/" + row.path("id").asText(), null, null, null).body());
        assertEquals(Long.toString(large), row.path("items").get(0).path("targetSnapshot").path("employeeId").asText());
        String receive = transferReceipt(other, row, "RQ-ASSET-" + base);
        assertEquals(200, transferCall(other, "POST", "/receipts/" + row.path("items").get(0).path("id").asText() + "/receive", receive, key(), row.path("version").asLong()).statusCode());
        assertEquals(large, jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 50));
    }

    @Test
    void approvedTransferTargetChangeFacadeKeepsLegacyFingerprintAndDomainNamespacesSeparate() throws Exception {
        var value = allocated(1);
        var reply = targetCall(warehouse, "GET", "/context?businessId=" + value.path("id").asText() + "&itemId=" + value.path("allocations").get(0).path("id").asText(), null, null, null);
        var snapshot = JSON.readTree(reply.body());
        String body = targetProposal(snapshot, base + 14), identity = key();
        var created = targetCall(warehouse, "POST", "", body, identity, snapshot.path("version").asLong());
        assertEquals(200, created.statusCode(), created.body());
        Object legacy = JSON.readValue(body, Class.forName("com.fams.requisition.ItemTargetChangeInput$Proposal"));
        String legacyHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(java.util.Map.of("version", snapshot.path("version").asLong(), "input", legacy)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(legacyHash, jdbc.queryForObject("SELECT payload_sha256 FROM fams_idempotency_record WHERE actor_account_id=? AND operation_id='targetChange:propose' AND idempotency_key=?", String.class, base + 11, identity));
        assertEquals(JSON.readTree(created.body()), JSON.readTree(genericTargetCall(warehouse, "REQUISITION", "POST", "", body, identity, snapshot.path("version").asLong()).body()));
        jdbc.update("UPDATE fams_asset SET lifecycle_status='IN_USE',custody_type='PERSONAL',storage_warehouse_id=NULL,owning_organization_id=?,using_organization_id=?,responsible_employee_id=? WHERE id=?", base, base, base + 10, base + 51);
        var draft = (tools.jackson.databind.node.ObjectNode) JSON.readTree(transferDraft(false, false, base + 15, base + 1, base, 0));
        ((tools.jackson.databind.node.ObjectNode) draft.path("items").get(0)).put("assetId", Long.toString(base + 51));
        var row = claimedTransfer(draft.toString(), employee);
        snapshot = transferTargetContext(warehouse, row, 0);
        var transfer = genericTargetCall(warehouse, "TRANSFER", "POST", "", genericTargetProposal(snapshot, transferNewTarget(base + 12, base)), identity, snapshot.path("version").asLong());
        assertEquals(200, transfer.statusCode(), transfer.body());
        assertEquals("TRANSFER", JSON.readTree(transfer.body()).path("business_type").asText());
        assertTrue(!JSON.readTree(created.body()).path("id").asText().equals(JSON.readTree(transfer.body()).path("id").asText()));
        assertEquals(2, count("SELECT COUNT(*) FROM fams_idempotency_record WHERE actor_account_id=? AND idempotency_key=?", base + 11, identity));
    }

    private JsonNode transferNewTarget(long employee, long organization) {
        return JSON.readTree(transferDraft(false, false, employee, organization, base, 0)).path("items").get(0).path("target");
    }
    private JsonNode transferTargetContext(Session actor, JsonNode row, int index) throws Exception {
        var response = genericTargetCall(actor, "TRANSFER", "GET", "/context?businessId=" + row.path("id").asText()
                + "&itemId=" + row.path("items").get(index).path("id").asText(), null, null, null);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }
    private String genericTargetProposal(JsonNode snapshot, JsonNode target) {
        return JSON.writeValueAsString(java.util.Map.of("businessId", snapshot.path("businessId").asText(), "itemId", snapshot.path("itemId").asText(),
                "snapshot", snapshot, "target", target, "reason", "Approved scope correction", "observedAssetCode", snapshot.path("assetCode").asText(),
                "physicalVerification", "Correct physical asset and unchanged delivery destination verified", "physicalChecked", true));
    }
    private HttpResponse<String> genericTargetCall(Session actor, String type, String method, String suffix, String body, String identity, Long version) throws Exception {
        String query = suffix.contains("?") ? "&" : "?";
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/item-target-changes" + suffix + query + "businessType=" + type)).header("Cookie", actor.cookie());
        if (identity != null) { builder.header("X-CSRF-Token", actor.csrf()).header("Idempotency-Key", identity); }
        if (version != null) { builder.header("If-Match-Version", version.toString()); }
        if (body != null) { builder.header("Content-Type", "application/json"); }
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @EnabledIfSystemProperty(named = "d6.15.proxy.runtime", matches = "true")
    void approvedItemTargetChangeNativeHandlersReachRealHttpAndActualReceipt() throws Exception {
        var row = allocated(1);
        row = warehouseWrite(row, 0, "deliver", delivery(row, 0));
        verifyNativeTargetHandlers(row, false);
    }

    @Test
    @EnabledIfSystemProperty(named = "d6.15.proxy.runtime", matches = "true")
    void approvedTransferTargetChangeNativeHandlersReachRealHttpAndActualReceipt() throws Exception {
        personalTransferFixture();
        var row = claimedTransfer(transferDraft(false, false, base + 15, base + 1, base, base), employee);
        row = transferAction(warehouse, row, transferItemPath(row, "deliver"), transferPhysical(row, null, false));
        verifyNativeTargetHandlers(row, true);
    }

    private void verifyNativeTargetHandlers(JsonNode row, boolean transfer) throws Exception {
        String type = transfer ? "TRANSFER" : "REQUISITION", prefix = "target-native-" + base;
        for (var entry : java.util.Map.of("warehouse", base + 11, "admin", base + 13,
                "recipient", base + 12, "original", base + (transfer ? 15 : 10)).entrySet()) {
            jdbc.update("INSERT INTO fams_mini_binding (account_id,identity_hash,created_at) VALUES (?,?,?)",
                    entry.getValue(), miniSessions.identityHash(new MiniWechatIdentityPort.Identity(
                            "requisition-test-app", prefix + "-" + entry.getKey())), Timestamp.from(Instant.now()));
        }
        var script = java.nio.file.Path.of(System.getProperty("user.dir")).getParent()
                .resolve("apps/miniprogram/test/runtime-target-change-http.mjs");
        String node = System.getenv("FAMS_TEST_NODE_EXECUTABLE");
        var builder = new ProcessBuilder("rtk", "proxy", node == null ? "node" : node, script.toString());
        builder.environment().put("FAMS_TEST_API_PORT", Integer.toString(port));
        builder.environment().put("FAMS_TEST_TARGET_BUSINESS", type);
        builder.environment().put("FAMS_TEST_TARGET_REQUEST", row.path("id").asText());
        builder.environment().put("FAMS_TEST_TARGET_ITEM", row.path(transfer ? "items" : "allocations").get(0).path("id").asText());
        builder.environment().put("FAMS_TEST_TARGET_EMPLOYEE", Long.toString(base + 12));
        builder.environment().put("FAMS_TEST_TARGET_ORGANIZATION", Long.toString(base));
        builder.environment().put("FAMS_TEST_TARGET_CODE_PREFIX", prefix);
        builder.redirectErrorStream(true);
        var process = builder.start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertEquals(base + 12, jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 50));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_item_target_change WHERE business_type=? AND business_id=? AND change_status='APPROVED'", type, row.path("id").asText()));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_audit_event WHERE resource_type='ITEM_TARGET_CHANGE' AND action='TARGET_CHANGE_APPROVED' AND actor_account_id=?", base + 13));
        String event = transfer ? "SELECT COUNT(*) FROM fams_transfer_event WHERE request_id=? AND event_type='TRANSFER_RECEIVE'"
                : "SELECT COUNT(*) FROM fams_requisition_handover_event WHERE allocation_id=? AND event_type='RECEIVED'";
        assertEquals(1, count(event, transfer ? row.path("id").asText() : row.path("allocations").get(0).path("id").asText()));
    }

    @Test
    void approvedItemTargetChangeJointPreservesCompletedSiblingAndUnallocatedRemainder() throws Exception {
        var row = allocatedDraft(2, draft(3, base + 10, base));
        row = warehouseWrite(row, 0, "deliver", delivery(row, 0));
        var received = call(employee, "POST", path(row, 0, "receive"), receiptBody(row, 0, "RQ-ASSET-" + base), key(), row.path("version").asLong());
        assertEquals(200, received.statusCode(), received.body());
        row = get(warehouse, row.path("id").asText());
        var completedAsset = jdbc.queryForMap("SELECT * FROM fams_asset WHERE id=?", base + 50);
        var completedAllocation = jdbc.queryForMap("SELECT * FROM fams_requisition_allocation WHERE id=?", row.path("allocations").get(0).path("id").asLong());
        var originalDemand = jdbc.queryForMap("SELECT * FROM fams_requisition_demand WHERE request_id=?", row.path("id").asLong());
        long item = row.path("allocations").get(1).path("id").asLong();
        var contextReply = targetCall(warehouse, "GET", "/context?businessId=" + row.path("id").asText() + "&itemId=" + item, null, null, null);
        assertEquals(200, contextReply.statusCode(), contextReply.body());
        var snapshot = JSON.readTree(contextReply.body());
        var proposed = targetCall(warehouse, "POST", "", targetProposal(snapshot, base + 14), key(), snapshot.path("version").asLong());
        assertEquals(200, proposed.statusCode(), proposed.body());
        String proposal = JSON.readTree(proposed.body()).path("id").asText();
        assertEquals(200, targetCall(admin, "POST", "/" + proposal + "/decision", "{\"result\":\"APPROVED\",\"reason\":\"Only unfinished allocation\"}", key(), 0L).statusCode());
        assertEquals(originalDemand, jdbc.queryForMap("SELECT * FROM fams_requisition_demand WHERE request_id=?", row.path("id").asLong()));
        assertEquals(completedAllocation, jdbc.queryForMap("SELECT * FROM fams_requisition_allocation WHERE id=?", row.path("allocations").get(0).path("id").asLong()));
        assertEquals(completedAsset, jdbc.queryForMap("SELECT * FROM fams_asset WHERE id=?", base + 50));
        row = get(warehouse, row.path("id").asText());
        row = warehouseWrite(row, 1, "deliver", delivery(row, 1));
        var grant = call(department, "POST", path(row, 1, "grant"), null, key(), null);
        assertEquals(200, grant.statusCode(), grant.body());
        String body = JSON.writeValueAsString(java.util.Map.of("grant", JSON.readTree(grant.body()).path("grant").asText(),
                "observedAssetCode", "RQ-ASSET-" + (base + 1), "physicalChecked", true));
        assertEquals(200, call(department, "POST", path(row, 1, "receive"), body, key(), row.path("version").asLong()).statusCode());
        row = get(warehouse, row.path("id").asText());
        assertEquals(1, row.path("demands").get(0).path("pendingAllocationQuantity").asInt());
        assertEquals(2, row.path("demands").get(0).path("completedQuantity").asInt());
        row = allocate(row, warehouse, row.path("demands").get(0).path("id").asText(), base + 52, null);
        row = warehouseWrite(row, 2, "deliver", delivery(row, 2));
        assertEquals(200, call(employee, "POST", path(row, 2, "receive"), receiptBody(row, 2, "RQ-ASSET-" + (base + 2)), key(), row.path("version").asLong()).statusCode());
        var done = get(warehouse, row.path("id").asText());
        assertEquals("COMPLETED", done.path("requestStatus").asText());
        assertEquals(3, done.path("demands").get(0).path("completedQuantity").asInt());
        assertEquals(base + 10, jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 52));
        assertEquals(base + 14, jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 51));
        assertEquals(completedAsset, jdbc.queryForMap("SELECT * FROM fams_asset WHERE id=?", base + 50));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE business_type='REQUISITION' AND business_id=? AND released_at IS NULL", row.path("id").asLong()));
    }

    @Test
    void approvedItemTargetChangeJointUnallocatedScopeUsesExistingSingleAdminDecision() throws Exception {
        var row = submitApprove(create(employee, draft(2, base + 10, base)));
        row = action(warehouse, row, "/claim", null);
        var before = jdbc.queryForMap("SELECT * FROM fams_requisition_decision WHERE request_id=?", row.path("id").asLong());
        row = action(warehouse, row, "/changes", draft(2, base + 14, base));
        assertEquals("PENDING_ADMIN_DECISION", row.path("requestStatus").asText());
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE business_type='REQUISITION' AND business_id=?", row.path("id").asLong()));
        row = action(admin, row, "/decision", "{\"result\":\"APPROVED\",\"comment\":\"New unallocated target\"}");
        assertEquals(before, jdbc.queryForMap("SELECT * FROM fams_requisition_decision WHERE id=?", before.get("id")));
        row = allocate(row, warehouse, row.path("demands").get(0).path("id").asText(), base + 50, null);
        row = warehouseWrite(row, 0, "deliver", delivery(row, 0));
        assertEquals(404, call(employee, "POST", path(row, 0, "grant"), null, key(), null).statusCode());
        assertEquals(200, call(department, "POST", path(row, 0, "grant"), null, key(), null).statusCode());
        assertEquals(2, row.path("demands").get(0).path("requestedQuantity").asInt());
        assertEquals(1, row.path("demands").get(0).path("pendingAllocationQuantity").asInt());
        assertEquals(1, row.path("demands").get(0).path("pendingHandoverQuantity").asInt());
    }

    @Test
    void approvedItemTargetChangeJointRaceWithActualReceiptHasOneWinner() throws Exception {
        var row = allocated(1);
        row = warehouseWrite(row, 0, "deliver", delivery(row, 0));
        verifyTargetDecisionRace(row, false);
    }

    @Test
    void approvedTransferTargetChangeJointRaceWithActualReceiptHasOneWinner() throws Exception {
        personalTransferFixture();
        var row = claimedTransfer(transferDraft(false, false, base + 15, base + 1, base, 0), employee);
        row = transferAction(warehouse, row, transferItemPath(row, "deliver"), transferPhysical(row, null, false));
        verifyTargetDecisionRace(row, true);
    }

    private void verifyTargetDecisionRace(JsonNode row, boolean transfer) throws Exception {
        String type = transfer ? "TRANSFER" : "REQUISITION";
        long item = row.path(transfer ? "items" : "allocations").get(0).path("id").asLong();
        var contextReply = genericTargetCall(warehouse, type, "GET", "/context?businessId=" + row.path("id").asText() + "&itemId=" + item, null, null, null);
        assertEquals(200, contextReply.statusCode(), contextReply.body());
        var snapshot = JSON.readTree(contextReply.body());
        String proposalBody = transfer ? genericTargetProposal(snapshot, transferNewTarget(base + 14, base)) : targetProposal(snapshot, base + 14);
        var created = genericTargetCall(warehouse, type, "POST", "", proposalBody, key(), snapshot.path("version").asLong());
        assertEquals(200, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        var receiver = transfer ? login(base + 15) : employee;
        String receipt = transfer ? transferReceipt(receiver, row, "RQ-ASSET-" + base) : receiptBody(row, 0, "RQ-ASSET-" + base);
        var start = new java.util.concurrent.CountDownLatch(1);
        var decision = CompletableFuture.supplyAsync(() -> proxyRaceResult(start, () -> genericTargetCall(admin, type, "POST", "/" + id + "/decision",
                "{\"result\":\"APPROVED\",\"reason\":\"Concurrent actual receipt\"}", key(), 0L).statusCode()));
        var receiptReply = new java.util.concurrent.atomic.AtomicReference<String>();
        var normal = CompletableFuture.supplyAsync(() -> proxyRaceResult(start, () -> {
            var reply = transfer
                    ? transferCall(receiver, "POST", "/receipts/" + item + "/receive", receipt, key(), row.path("version").asLong())
                    : call(receiver, "POST", path(row, 0, "receive"), receipt, key(), row.path("version").asLong());
            receiptReply.set(reply.body());
            return reply.statusCode();
        }));
        start.countDown();
        int decided = decision.get(30, TimeUnit.SECONDS), received = normal.get(30, TimeUnit.SECONDS);
        assertTrue(decided == 200 && (received == 404 || received == 409) || decided == 409 && received == 200,
                "decision=" + decided + " receive=" + received + " body=" + receiptReply.get());
        if (received == 409) {
            assertEquals(transfer ? "TRANSFER_WRITE_CONFLICT" : "REQUISITION_WRITE_CONFLICT",
                    JSON.readTree(receiptReply.get()).path("code").asText(), receiptReply.get());
        }
        System.out.println("Target/receipt race " + type + " decision=" + decided + " receipt=" + received
                + " rejection=" + JSON.readTree(receiptReply.get()).path("code").asText());
        assertEquals(received == 200 ? Long.valueOf(base + (transfer ? 15 : 10)) : transfer ? Long.valueOf(base + 10) : null,
                jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 50));
        assertEquals(received == 200 ? 0 : 1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
        assertEquals(decided == 200 ? "APPROVED" : "PENDING", jdbc.queryForObject("SELECT change_status FROM fams_item_target_change WHERE id=?", String.class, id));
        String sql = transfer ? "SELECT COUNT(*) FROM fams_transfer_event WHERE request_id=? AND event_type='TRANSFER_RECEIVE'"
                : "SELECT COUNT(*) FROM fams_requisition_handover_event WHERE allocation_id=? AND event_type='RECEIVED'";
        assertEquals(received == 200 ? 1 : 0, count(sql, transfer ? row.path("id").asText() : Long.toString(item)));
    }

    @Test
    @EnabledIfSystemProperty(named = "d6.15.proxy.runtime", matches = "true")
    void approvedItemTargetChangeJointInvalidatesApprovedProxyWithoutRetargetingEvidence() throws Exception {
        var row = allocated(1);
        row = warehouseWrite(row, 0, "deliver", delivery(row, 0));
        verifyTargetInvalidatesProxy(row, false);
    }

    @Test
    @EnabledIfSystemProperty(named = "d6.15.proxy.runtime", matches = "true")
    void approvedTransferTargetChangeJointInvalidatesApprovedProxyWithoutRetargetingEvidence() throws Exception {
        personalTransferFixture();
        var row = claimedTransfer(transferDraft(false, false, base + 15, base + 1, base, 0), employee);
        row = transferAction(warehouse, row, transferItemPath(row, "deliver"), transferPhysical(row, null, false));
        verifyTargetInvalidatesProxy(row, true);
    }

    private void verifyTargetInvalidatesProxy(JsonNode row, boolean transfer) throws Exception {
        String type = transfer ? "TRANSFER" : "REQUISITION";
        var proxySnapshot = transfer ? JSON.readTree(transferProxyContext(warehouse, row).body()) : proxyContext(row);
        var proxy = proxyCall(warehouse, "POST", "", proxyDraft(proxySnapshot), key(), null);
        assertEquals(200, proxy.statusCode(), proxy.body());
        String proxyId = JSON.readTree(proxy.body()).path("id").asText();
        assertEquals(201, proxyUpload(proxyId, 0).statusCode());
        assertEquals(200, proxyCall(warehouse, "POST", "/" + proxyId + "/submit", null, key(), 1L).statusCode());
        assertEquals(200, proxyCall(admin, "POST", "/" + proxyId + "/decision", proxyDecision(true), key(), 2L).statusCode());
        var frozenProxy = jdbc.queryForMap("SELECT * FROM fams_proxy_confirmation WHERE id=?", proxyId);
        long item = row.path(transfer ? "items" : "allocations").get(0).path("id").asLong();
        var context = genericTargetCall(warehouse, type, "GET", "/context?businessId=" + row.path("id").asText() + "&itemId=" + item, null, null, null);
        assertEquals(200, context.statusCode(), context.body());
        var snapshot = JSON.readTree(context.body());
        String body = transfer ? genericTargetProposal(snapshot, transferNewTarget(base + 14, base)) : targetProposal(snapshot, base + 14);
        var proposed = genericTargetCall(warehouse, type, "POST", "", body, key(), snapshot.path("version").asLong());
        assertEquals(200, proposed.statusCode(), proposed.body());
        String proposalId = JSON.readTree(proposed.body()).path("id").asText();
        assertEquals(200, genericTargetCall(admin, type, "POST", "/" + proposalId + "/decision", "{\"result\":\"APPROVED\",\"reason\":\"New target supersedes old permission\"}", key(), 0L).statusCode());
        assertEquals(409, proxyCall(warehouse, "POST", "/" + proxyId + "/execute", null, key(), 3L).statusCode());
        assertEquals(frozenProxy, jdbc.queryForMap("SELECT * FROM fams_proxy_confirmation WHERE id=?", proxyId));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_business_result_notice WHERE execution_id=?", proxyId));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
        assertEquals(transfer ? Long.valueOf(base + 10) : null, jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 50));
        var refreshed = transfer ? JSON.readTree(transferProxyContext(warehouse, row).body()) : proxyContext(row);
        var newDraft = (tools.jackson.databind.node.ObjectNode) JSON.readTree(proxyDraft(refreshed));
        newDraft.put("previousConfirmationId", proxyId);
        var replacement = proxyCall(warehouse, "POST", "", JSON.writeValueAsString(newDraft), key(), null);
        assertEquals(200, replacement.statusCode(), replacement.body());
        String replacementId = JSON.readTree(replacement.body()).path("id").asText();
        assertEquals(201, proxyUpload(replacementId, 0).statusCode());
        assertEquals(200, proxyCall(warehouse, "POST", "/" + replacementId + "/submit", null, key(), 1L).statusCode());
        assertEquals(200, proxyCall(admin, "POST", "/" + replacementId + "/decision", proxyDecision(true), key(), 2L).statusCode());
        assertEquals(200, proxyCall(warehouse, "POST", "/" + replacementId + "/execute", null, key(), 3L).statusCode());
        assertEquals(frozenProxy, jdbc.queryForMap("SELECT * FROM fams_proxy_confirmation WHERE id=?", proxyId));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_business_result_notice WHERE execution_id=?", proxyId));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_business_result_notice WHERE execution_id=? AND recipient_employee_id=?", replacementId, base + 14));
        assertEquals(base + 14, jdbc.queryForObject("SELECT responsible_employee_id FROM fams_asset WHERE id=?", Long.class, base + 50));
        assertEquals(0, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=? AND released_at IS NULL", base + 50));
    }

    private String targetProposal(JsonNode snapshot, long employeeId) {
        return JSON.writeValueAsString(java.util.Map.of("businessId",snapshot.path("businessId").asText(),
                "itemId",snapshot.path("itemId").asText(),"snapshot",snapshot,"target",java.util.Map.of(
                "custodyType","PERSONAL","organizationId",Long.toString(base),"organizationVersion",0,
                "employeeId",Long.toString(employeeId),"employeeVersion",0,"locationId",Long.toString(base),"locationVersion",0),
                "reason","Original recipient changed; approved correction required","observedAssetCode",snapshot.path("assetCode").asText(),
                "physicalVerification","Correct asset verified at original delivery destination; no return to stock","physicalChecked",true));
    }

    private HttpResponse<String> targetCall(Session session, String method, String suffix, String body, String key, Long version) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/requisition-target-changes"+suffix)).header("Cookie",session.cookie());
        if (key != null) { builder.header("Idempotency-Key",key).header("X-CSRF-Token",session.csrf()); }
        if (version != null) { builder.header("If-Match-Version",version.toString()); }
        if (body != null) { builder.header("Content-Type","application/json"); }
        builder.method(method,body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode proxyContext(JsonNode value) throws Exception {
        var response =
                proxyCall(
                        warehouse,
                        "GET",
                        "/context?businessId="
                                + value.path("id").asText()
                                + "&itemId="
                                + value.path("allocations").get(0).path("id").asText(),
                        null,
                        null,
                        null);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private String proxyDraft(JsonNode snapshot) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("businessId", snapshot.path("businessId").asText());
        body.put("itemId", snapshot.path("itemId").asText());
        body.put("snapshot", snapshot);
        body.put("observedAssetCode", snapshot.path("assetCode").asText());
        body.put("actualSigner", "Original responsible recipient");
        body.put("actualSignerEmployeeId", snapshot.path("targetEmployeeId").asText());
        body.put("actualReceivedAt", Instant.now().minusSeconds(30).toString());
        body.put("cannotConfirmReason", "Recipient cannot operate the device");
        body.put("contactRecord", "Contacted recipient and checked signed asset list");
        body.put("delegatedReceipt", false);
        body.put("evidenceKind", "SIGNED_RECEIPT");
        body.put("collectionMethod", "CODE_CONFIRMED");
        body.put("targetOrAuthorizedProxyReceived", true);
        return JSON.writeValueAsString(body);
    }

    @Test
    @EnabledIfSystemProperty(named = "d6.15.proxy.runtime", matches = "true")
    void proxyConfirmationReassignmentRevokesOriginalWarehouseReplayPermission() throws Exception {
        var value = allocated(2);
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        var snapshot = proxyContext(value);
        String draftBody = proxyDraft(snapshot);
        String createKey = key();
        var created = proxyCall(warehouse, "POST", "", draftBody, createKey, null);
        assertEquals(200, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        assertEquals(201, proxyUpload(id, 0).statusCode());
        String submitKey = key();
        assertEquals(200, proxyCall(warehouse, "POST", "/" + id + "/submit", null, submitKey, 1L).statusCode());
        assertEquals(200, proxyCall(admin, "POST", "/" + id + "/decision", proxyDecision(true), key(), 2L).statusCode());
        String executeKey = key();
        assertEquals(200, proxyCall(warehouse, "POST", "/" + id + "/execute", null, executeKey, 3L).statusCode());
        String request = snapshot.path("businessId").asText();
        var inspected = call(admin, "GET", "/" + request + "/reassignment", null, null, null);
        assertEquals(200, inspected.statusCode(), inspected.body());
        var transfer = JSON.readTree(inspected.body());
        var body = JSON.createObjectNode();
        body.put("processorAccountId", Long.toString(base + 12));
        body.put("ownershipVersion", transfer.path("ownershipVersion").asLong());
        body.put("reason", "Original warehouse unavailable");
        body.put("evidence", "Checked completed receipt and remaining physical item");
        body.put("unsynchronizedResultsChecked", true);
        body.set("items", transfer.path("items"));
        var reassigned = call(admin, "POST", "/" + request + "/reassignment", body.toString(), key(), transfer.path("version").asLong());
        assertEquals(200, reassigned.statusCode(), reassigned.body());
        for (var response : java.util.List.of(
                proxyCall(warehouse, "POST", "", draftBody, createKey, null),
                proxyCall(warehouse, "POST", "/" + id + "/submit", null, submitKey, 1L),
                proxyCall(warehouse, "POST", "/" + id + "/execute", null, executeKey, 3L))) {
            assertEquals(409, response.statusCode(), response.body());
            assertEquals("PROXY_RECEIPT_NOT_PROCESSOR", JSON.readTree(response.body()).path("code").asText());
        }
        assertEquals(200, proxyCall(other, "GET", "/" + id, null, null, null).statusCode());
        assertEquals(1, count("SELECT COUNT(*) FROM fams_business_result_notice WHERE execution_id=?", id));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_asset_business_lock WHERE business_id=? AND business_type='REQUISITION' AND released_at IS NULL", request));
    }

    @Test
    @EnabledIfSystemProperty(named = "d6.15.proxy.runtime", matches = "true")
    void proxyConfirmationValidatesManualDelegationProofAndRejectsWithoutChangingCustody() throws Exception {
        var value = allocated(1);
        value = warehouseWrite(value, 0, "deliver", delivery(value, 0));
        var snapshot = proxyContext(value);
        assertProxyDraftRejected(snapshot, b -> b.put("targetOrAuthorizedProxyReceived", false), 400, "PROXY_RECEIPT_EVIDENCE_REQUIRED");
        assertProxyDraftRejected(snapshot, b -> b.put("actualReceivedAt", java.time.Instant.now().plusSeconds(60).toString()), 400, "PROXY_RECEIPT_EVIDENCE_REQUIRED");
        assertProxyDraftRejected(snapshot, b -> b.put("evidenceKind", "UNKNOWN"), 400, "PROXY_RECEIPT_EVIDENCE_REQUIRED");
        assertProxyDraftRejected(snapshot, b -> b.put("collectionMethod", "UNKNOWN"), 400, "PROXY_RECEIPT_EVIDENCE_REQUIRED");
        assertProxyDraftRejected(snapshot, b -> b.put("collectionMethod", "MANUAL_RECORD").put("manualReason", ""), 400, "PROXY_RECEIPT_EVIDENCE_REQUIRED");
        assertProxyDraftRejected(snapshot, b -> b.put("delegatedReceipt", true), 400, "PROXY_RECEIPT_DELEGATION_REQUIRED");
        assertProxyDraftRejected(snapshot, b -> b.put("delegatedReceipt", true).put("evidenceKind", "AUTHORIZED_PROXY").put("delegationRelationship", ""), 400, "PROXY_RECEIPT_DELEGATION_REQUIRED");
        assertProxyDraftRejected(snapshot, b -> b.put("delegatedReceipt", true).put("evidenceKind", "AUTHORIZED_PROXY").put("delegationRelationship", "Family").put("delegationBasis", ""), 400, "PROXY_RECEIPT_DELEGATION_REQUIRED");
        assertProxyDraftRejected(snapshot, b -> b.putNull("actualSignerEmployeeId"), 400, "PROXY_RECEIPT_SIGNER_MISMATCH");
        assertProxyDraftRejected(snapshot, b -> b.put("actualSignerEmployeeId", base + 12), 400, "PROXY_RECEIPT_SIGNER_MISMATCH");
        assertProxyDraftRejected(snapshot, b -> b.put("observedAssetCode", "wrong"), 409, "PROXY_RECEIPT_ASSET_MISMATCH");
        assertProxyDraftRejected(snapshot, b -> b.put("previousConfirmationId", Long.MAX_VALUE), 404, "REQUISITION_UNAVAILABLE");
        var body = (tools.jackson.databind.node.ObjectNode) JSON.readTree(proxyDraft(snapshot));
        body.put("delegatedReceipt", true).put("actualSigner", "Authorized family signer")
                .put("evidenceKind", "AUTHORIZED_PROXY").put("delegationRelationship", "Family")
                .put("delegationBasis", "Signed authorization linked to receipt")
                .put("collectionMethod", "MANUAL_RECORD").put("manualReason", "Code label unreadable to scanner; physically matched");
        String identity = key();
        var created = proxyCall(warehouse, "POST", "", body.toString(), identity, null);
        assertEquals(200, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        assertEquals(JSON.readTree(created.body()), JSON.readTree(proxyCall(warehouse, "POST", "", body.toString(), identity, null).body()));
        body.put("contactRecord", "Changed payload");
        assertEquals(409, proxyCall(warehouse, "POST", "", body.toString(), identity, null).statusCode());
        assertEquals(404, proxyCall(admin, "GET", "/" + id, null, null, null).statusCode());
        assertEquals(404, proxyCall(other, "GET", "/" + id, null, null, null).statusCode());
        assertEquals(403, proxyCall(employee, "GET", "", null, null, null).statusCode());
        assertEquals(1, JSON.readTree(proxyCall(warehouse, "GET", "", null, null, null).body()).path("total").asInt());
        assertEquals(0, JSON.readTree(proxyCall(admin, "GET", "", null, null, null).body()).path("total").asInt());
        var uploaded = proxyUpload(id, 0);
        assertEquals(201, uploaded.statusCode(), uploaded.body());
        String object = JSON.readTree(uploaded.body()).path("objectId").asText();
        jdbc.update("UPDATE fams_attachment SET scan_status='UNAVAILABLE' WHERE object_id=?", object);
        assertEquals(409, proxyCall(warehouse, "POST", "/" + id + "/submit", null, key(), 1L).statusCode());
        jdbc.update("UPDATE fams_attachment SET scan_status='CLEAN',size_bytes=size_bytes+1 WHERE object_id=?", object);
        assertEquals(409, proxyCall(warehouse, "POST", "/" + id + "/submit", null, key(), 1L).statusCode());
        jdbc.update("UPDATE fams_attachment SET size_bytes=size_bytes-1 WHERE object_id=?", object);
        var proofPath = proxyRoot.resolve(object);
        byte[] proof = java.nio.file.Files.readAllBytes(proofPath);
        java.nio.file.Files.delete(proofPath);
        assertEquals(409, proxyCall(warehouse, "POST", "/" + id + "/submit", null, key(), 1L).statusCode());
        java.nio.file.Files.write(proofPath, proof);
        String submitKey = key();
        var submitted = proxyCall(warehouse, "POST", "/" + id + "/submit", null, submitKey, 1L);
        assertEquals(200, submitted.statusCode(), submitted.body());
        assertEquals(JSON.readTree(submitted.body()), JSON.readTree(proxyCall(warehouse, "POST", "/" + id + "/submit", null, submitKey, 1L).body()));
        for (String checks : java.util.List.of(proxyDecision(true).replace("physicalEvidenceChecked\":true", "physicalEvidenceChecked\":false"), proxyDecision(true).replace("noDisputeChecked\":true", "noDisputeChecked\":false"))) {
            assertEquals(400, proxyCall(admin, "POST", "/" + id + "/decision", checks, key(), 2L).statusCode());
        }
        String decisionKey = key();
        var rejected = proxyCall(admin, "POST", "/" + id + "/decision", proxyDecision(false), decisionKey, 2L);
        assertEquals(200, rejected.statusCode(), rejected.body());
        assertEquals(JSON.readTree(rejected.body()), JSON.readTree(proxyCall(admin, "POST", "/" + id + "/decision", proxyDecision(false), decisionKey, 2L).body()));
        assertEquals(409, proxyCall(warehouse, "POST", "/" + id + "/execute", null, key(), 3L).statusCode());
        body = (tools.jackson.databind.node.ObjectNode) JSON.readTree(proxyDraft(snapshot));
        body.put("previousConfirmationId", id);
        assertEquals(200, proxyCall(warehouse, "POST", "", body.toString(), key(), null).statusCode());
        assertEquals(0, count("SELECT COUNT(*) FROM fams_business_result_notice WHERE business_id=?", snapshot.path("businessId").asText()));
        assertEquals("IN_STOCK", jdbc.queryForObject("SELECT lifecycle_status FROM fams_asset WHERE id=?", String.class, base + 50));
    }

    private void assertProxyDraftRejected(JsonNode snapshot,
            java.util.function.Consumer<tools.jackson.databind.node.ObjectNode> edit, int status, String code) throws Exception {
        var body = (tools.jackson.databind.node.ObjectNode) JSON.readTree(proxyDraft(snapshot));
        edit.accept(body);
        var response = proxyCall(warehouse, "POST", "", body.toString(), key(), null);
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JSON.readTree(response.body()).path("code").asText());
    }

    private String proxyDecision(boolean approved) {
        return "{\"approved\":"
                + approved
                + ",\"reason\":\"Signed physical delivery and target"
                + " checked\",\"physicalEvidenceChecked\":true,\"noDisputeChecked\":true}";
    }

    private HttpResponse<String> noticeObjection(Session session, String id, String reason, String identity)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/business-result-notices/" + id + "/objection"))
                .header("Cookie", session.cookie()).header("X-CSRF-Token", session.csrf())
                .header("Idempotency-Key", identity).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(java.util.Map.of("reason", reason))))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> noticeCall(Session session, String method, String path)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/business-result-notices" + path)).header("Cookie", session.cookie());
        if ("POST".equals(method)) {
            builder.header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", key()).POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.GET();
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> proxyCall(
            Session session, String method, String path, String body, String identity, Long version)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/api/v1/proxy-confirmations"
                                                + path))
                        .header("Cookie", session.cookie());
        if ("POST".equals(method)) {
            builder.header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", identity);
            if (version != null) {
                builder.header("If-Match-Version", version.toString());
            }
            builder.header("Content-Type", "application/json")
                    .POST(
                            body == null
                                    ? HttpRequest.BodyPublishers.noBody()
                                    : HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.GET();
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> proxyUpload(String id, long version) throws Exception {
        String boundary = "fams-proxy-evidence-boundary";
        String data =
                "--"
                        + boundary
                        + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\";"
                        + " filename=\"signed-list.pdf\"\r\n"
                        + "Content-Type: application/pdf\r\n\r\n"
                        + "%PDF-1.7 signed asset list\r\n"
                        + "--"
                        + boundary
                        + "--\r\n";
        return http.send(
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/api/v1/proxy-confirmations/"
                                                + id
                                                + "/attachments"))
                        .header("Cookie", warehouse.cookie())
                        .header("X-CSRF-Token", warehouse.csrf())
                        .header("Idempotency-Key", key())
                        .header("If-Match-Version", Long.toString(version))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofString(data))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private record Session(String cookie, String csrf) {}

    @SpringBootApplication(scanBasePackages = "com.fams")
    static class TestApplication {}
}
