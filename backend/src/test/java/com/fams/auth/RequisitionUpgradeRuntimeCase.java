package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfSystemProperty(named = "d6.11.upgrade.runtime", matches = "true")
class RequisitionUpgradeRuntimeCase {

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static final String NOW = "'2026-10-07 01:00:00'";

    @BeforeAll
    static void upgradeAndSeedOnlyTheOwnedDatabase() {
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        var source = new DriverManagerDataSource(settings.url(), settings.username(), settings.password());
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        Flyway.configure().dataSource(source).locations("classpath:db/migration/d6_04")
                .target("18").load().migrate();
        jdbc.update("""
                INSERT INTO fams_organization (id, code, code_normalized, name, status, version, created_at, updated_at)
                VALUES (911001, 'REQUPGRADE', 'REQUPGRADE', 'Preserved requisition organization', 'ACTIVE', 9,
                        '2026-10-07 01:00:00', '2026-10-07 01:00:00')
                """);
        Flyway current = Flyway.configure().dataSource(source).locations("classpath:db/migration/d6_04")
                .target("19").load();
        assertEquals(1, current.migrate().migrationsExecuted);
        assertEquals(0, current.migrate().migrationsExecuted);
        current.validate();
        assertEquals(9L, jdbc.queryForObject("SELECT version FROM fams_organization WHERE id=911001", Long.class));
        assertEquals("Preserved requisition organization", jdbc.queryForObject(
                "SELECT name FROM fams_organization WHERE id=911001", String.class));
        seed();
    }

    @AfterAll
    static void cleanOnlyThisTestsExactFixtureIdentities() {
        if (jdbc == null) {
            return;
        }
        jdbc.update("DELETE FROM fams_requisition_ownership WHERE request_id IN (911101,911102)");
        jdbc.update("DELETE FROM fams_requisition_demand WHERE id IN (911111,911112)");
        jdbc.update("DELETE FROM fams_requisition WHERE id IN (911101,911102)");
        jdbc.update("DELETE FROM fams_asset WHERE id=911010");
        jdbc.update("DELETE FROM fams_audit_event WHERE id=911020");
        jdbc.update("DELETE FROM fams_business_parameter_version WHERE id=911004");
        jdbc.update("DELETE FROM fams_system_account WHERE id=911003");
        jdbc.update("DELETE FROM fams_employee WHERE id=911002");
        jdbc.update("DELETE FROM fams_location WHERE id=911006");
        jdbc.update("DELETE FROM fams_warehouse WHERE id=911007");
        jdbc.update("DELETE FROM fams_asset_category WHERE id=911005");
        jdbc.update("DELETE FROM fams_organization WHERE id=911001");
    }

    @Test
    void emptyOwnedDatabaseCreatesAllNineteenMigrationsAndSevenRequisitionTables() {
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        String freshUrl = settings.url().replace("fams-d6-04-db-", "fams-d6-07-db-");
        var source = new DriverManagerDataSource(freshUrl, settings.username(), settings.password());
        Flyway fresh = Flyway.configure().dataSource(source).locations("classpath:db/migration/d6_04")
                .target("19").load();
        assertEquals(19, fresh.migrate().migrationsExecuted);
        assertEquals(0, fresh.migrate().migrationsExecuted);
        fresh.validate();
        assertEquals(7, new JdbcTemplate(source).queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
                AND table_name IN ('fams_requisition','fams_requisition_demand','fams_requisition_decision',
                    'fams_requisition_ownership','fams_requisition_allocation',
                    'fams_requisition_unfulfillable','fams_requisition_followup')
                """, Integer.class));
    }

    @Test
    void quantitiesConserveAcrossPartialAllocationUnfulfillableAndCancellation() {
        rollback(() -> {
            jdbc.update("""
                    UPDATE fams_requisition_demand SET pending_allocation_quantity=0,
                    pending_handover_quantity=2, unfulfillable_quantity=1 WHERE id=911111
                    """);
            assertEquals(3, quantityTotal());
            assertCheckFailure("ck_req_demand_quantities", () -> jdbc.update(
                    "UPDATE fams_requisition_demand SET completed_quantity=1 WHERE id=911111"));
            jdbc.update("""
                    UPDATE fams_requisition_demand SET pending_handover_quantity=1,
                    completed_quantity=1 WHERE id=911111
                    """);
            assertEquals(3, quantityTotal());
            assertCheckFailure("ck_req_demand_quantities", () -> jdbc.update(
                    "UPDATE fams_requisition_demand SET requested_quantity=0 WHERE id=911111"));
            assertCheckFailure("ck_req_demand_quantities", () -> jdbc.update(
                    "UPDATE fams_requisition_demand SET cancelled_quantity=-1 WHERE id=911111"));
            jdbc.update("""
                    UPDATE fams_requisition_demand SET pending_handover_quantity=0,
                    completed_quantity=0, unfulfillable_quantity=2, cancelled_quantity=1 WHERE id=911111
                    """);
            assertEquals(3, quantityTotal());
        });
    }

    @Test
    void personalAndSharedTargetsAreMutuallyExclusiveAndDemandBelongsToItsRequest() {
        rollback(() -> {
            assertCheckFailure("ck_req_demand_target", () -> jdbc.update("""
                    UPDATE fams_requisition_demand SET target_department_employee_id=911002 WHERE id=911111
                    """));
            assertCheckFailure("ck_req_demand_target", () -> jdbc.update(
                    "UPDATE fams_requisition_demand SET target_employee_id=NULL WHERE id=911111"));
            jdbc.update("""
                    UPDATE fams_requisition_demand SET custody_type='SHARED', target_employee_id=NULL,
                    target_department_employee_id=911002 WHERE id=911111
                    """);
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                    UPDATE fams_requisition SET source_request_id=911102, source_demand_id=911111,
                    source_reason_snapshot_json='{}' WHERE id=911101
                    """));
        });
    }

    @Test
    void approvalHasOneDecisionPerVersionAndClaimHistoryRetainsOnlyOneCurrentOwner() {
        rollback(() -> {
            String approval = """
                    INSERT INTO fams_requisition_decision
                    (request_id,request_version,decision_result,approved_scope_json,decided_by_account_id,
                     decided_at,audit_event_id) VALUES (911101,0,'APPROVED','{"requestedQuantity":3}',911003,
                     '2026-10-07 01:00:00',911020)
                    """;
            jdbc.update(approval);
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(approval));
            owner(911121, 911101);
            assertThrows(DataIntegrityViolationException.class, () -> owner(911122, 911101));
            jdbc.update("UPDATE fams_requisition_ownership SET archived_at=" + NOW + " WHERE id=911121");
            owner(911122, 911101);
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM fams_requisition_ownership WHERE request_id=911101", Integer.class));
        });
    }

    @Test
    void cancelledAndReceivedAllocationHistoryDoesNotBlockLaterLegitimateStockReuse() {
        rollback(() -> {
            owner(911121, 911101);
            owner(911122, 911102);
            lock(911131);
            allocation(911141, 911111, 911121, 911131, null);
            lock(911132);
            assertThrows(DataIntegrityViolationException.class, () -> allocation(
                    911142, 911112, 911121, 911132, null));
            assertThrows(DataIntegrityViolationException.class, () -> allocation(
                    911142, 911111, 911122, 911132, null));
            assertThrows(DataIntegrityViolationException.class, () -> allocation(
                    911142, 911111, 911121, 911132, null));
            jdbc.update("UPDATE fams_requisition_allocation SET allocation_status='CANCELLED' WHERE id=911141");
            allocation(911142, 911111, 911121, 911132, 911141L);
            jdbc.update("UPDATE fams_requisition_allocation SET allocation_status='RECEIVED' WHERE id=911142");
            lock(911133);
            allocation(911143, 911111, 911121, 911133, null);
            assertEquals(3, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM fams_requisition_allocation WHERE demand_id=911111", Integer.class));
            assertEquals("IN_STOCK", jdbc.queryForObject(
                    "SELECT lifecycle_status FROM fams_asset WHERE id=911010", String.class));
        });
    }

    @Test
    void unfulfillableRequiresPositiveQuantityTypedReasonDescriptionAndPersistedQuerySummary() {
        rollback(() -> {
            String sql = """
                    INSERT INTO fams_requisition_unfulfillable
                    (request_id,demand_id,quantity,reason_code,reason_description,requirement_snapshot_json,
                     inventory_query_summary_json,recorded_by_account_id,recorded_at,audit_event_id)
                    VALUES (911101,911111,?,?,?,'{"specification":"memory 16GB"}',
                            '{"availableQuantity":0,"categoryId":911005}',911003,'2026-10-07 01:00:00',911020)
                    """;
            assertCheckFailure("ck_req_unfulfillable_other", () -> jdbc.update(sql, 1, "OTHER", null));
            assertCheckFailure("ck_req_unfulfillable_other", () -> jdbc.update(sql, 1, "OTHER", "  "));
            assertCheckFailure("ck_req_unfulfillable_quantity", () -> jdbc.update(sql, 0, "STOCK_SHORTAGE", null));
            assertCheckFailure("ck_req_unfulfillable_reason", () -> jdbc.update(sql, 1, "UNKNOWN", null));
            jdbc.update(sql, 1, "OTHER", "Unavailable specification");
            assertEquals("0", jdbc.queryForObject("""
                    SELECT JSON_UNQUOTE(JSON_EXTRACT(inventory_query_summary_json,'$.availableQuantity'))
                    FROM fams_requisition_unfulfillable WHERE request_id=911101
                    """, String.class));
        });
    }

    @Test
    void followupPointerCannotForkOrReferenceAnUnrelatedRegularRequest() {
        rollback(() -> {
            jdbc.update("""
                    UPDATE fams_requisition SET source_request_id=911101, source_demand_id=911111,
                    source_reason_snapshot_json='{"reasonCode":"STOCK_SHORTAGE"}' WHERE id=911102
                    """);
            String sql = """
                    INSERT INTO fams_requisition_followup
                    (source_request_id,source_demand_id,latest_request_id,updated_at)
                    VALUES (911101,911111,911102,'2026-10-07 01:00:00')
                    """;
            jdbc.update(sql);
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql));
            assertCheckFailure("ck_req_followup_distinct", () -> jdbc.update(
                    "UPDATE fams_requisition_followup SET latest_request_id=911101 WHERE source_demand_id=911111"));
            assertEquals(911102L, jdbc.queryForObject(
                    "SELECT latest_request_id FROM fams_requisition_followup WHERE source_demand_id=911111", Long.class));
        });
    }

    @Test
    void failedAllocationRollsBackQuantityAndAuditTogether() {
        int auditsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM fams_audit_event", Integer.class);
        assertThrows(DataIntegrityViolationException.class, () -> transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    UPDATE fams_requisition_demand SET pending_allocation_quantity=2,
                    pending_handover_quantity=1 WHERE id=911111
                    """);
            jdbc.update("""
                    INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,action,resource_type,
                    resource_id,occurred_at,detail_json) VALUES ('00000000-0000-0000-0000-000000911001',
                    911003,'WAREHOUSE_OPERATOR','ALLOCATE','REQUISITION',911101,'2026-10-07 01:00:00','{}')
                    """);
            owner(911121, 911101);
            owner(911122, 911101);
        }));
        assertEquals(3, jdbc.queryForObject(
                "SELECT pending_allocation_quantity FROM fams_requisition_demand WHERE id=911111", Integer.class));
        assertEquals(auditsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM fams_audit_event", Integer.class));
    }

    @Test
    void twoConcurrentClaimTransactionsPersistExactlyOneOwner() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> competingClaim(start, 911121));
            var second = executor.submit(() -> competingClaim(start, 911122));
            start.countDown();
            assertEquals(1, first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM fams_requisition_ownership WHERE request_id=911102", Integer.class));
        } finally {
            jdbc.update("DELETE FROM fams_requisition_ownership WHERE request_id=911102");
        }
    }

    private int competingClaim(CountDownLatch start, long id) throws InterruptedException {
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Claim test start barrier timed out");
        }
        try {
            transactions.executeWithoutResult(status -> owner(id, 911102));
            return 1;
        } catch (DataIntegrityViolationException rejected) {
            return 0;
        }
    }

    private void assertCheckFailure(String constraint, Runnable action) {
        DataAccessException rejected = assertThrows(DataAccessException.class, action::run);
        SQLException cause = (SQLException) rejected.getMostSpecificCause();
        assertEquals(3819, cause.getErrorCode());
        assertTrue(cause.getMessage().contains(constraint), cause.getMessage());
    }

    private int quantityTotal() {
        return jdbc.queryForObject("""
                SELECT pending_allocation_quantity+pending_handover_quantity+completed_quantity
                       +unfulfillable_quantity+cancelled_quantity FROM fams_requisition_demand WHERE id=911111
                """, Integer.class);
    }

    private void rollback(Runnable action) {
        transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            action.run();
        });
    }

    private void owner(long id, long requestId) {
        jdbc.update("""
                INSERT INTO fams_requisition_ownership (id,request_id,processor_account_id,acquired_at)
                VALUES (?,?,911003,'2026-10-07 01:00:00')
                """, id, requestId);
    }

    private void lock(long id) {
        jdbc.update("UPDATE fams_asset_business_lock SET released_at=" + NOW + " WHERE asset_id=911010");
        jdbc.update("""
                INSERT INTO fams_asset_business_lock
                (id,asset_id,business_type,business_id,business_item_id,acquired_at)
                VALUES (?,911010,'REQUISITION',911101,911111,'2026-10-07 01:00:00')
                """, id);
    }

    private void allocation(long id, long demand, long owner, long lock, Long predecessor) {
        jdbc.update("""
                INSERT INTO fams_requisition_allocation (id,request_id,demand_id,ownership_id,asset_id,
                asset_version_snapshot,asset_snapshot_json,business_lock_id,allocation_status,
                predecessor_allocation_id,allocated_at,audit_event_id)
                VALUES (?,911101,?,?,911010,0,'{}',?,'WAITING_DELIVERY',?,'2026-10-07 01:00:00',911020)
                """, id, demand, owner, lock, predecessor);
    }

    private static void seed() {
        jdbc.update("""
                INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,organization_id,status,
                created_at,updated_at) VALUES (911002,'REQ','REQ','Test recipient',911001,'ACTIVE',
                '2026-10-07 01:00:00','2026-10-07 01:00:00')
                """);
        jdbc.update("""
                INSERT INTO fams_system_account (id,employee_id,username,username_normalized,password_hash,
                status,created_at,updated_at) VALUES (911003,911002,'REQ','REQ','test-not-a-login-credential',
                'ENABLED','2026-10-07 01:00:00','2026-10-07 01:00:00')
                """);
        jdbc.update("""
                INSERT INTO fams_business_parameter_version
                (id,version,status,effective_at,actor_account_id,actor_username,snapshot_json,created_at)
                VALUES (911004,911004,'SUPERSEDED','2026-10-07 01:00:00',911003,'REQ','{}','2026-10-07 01:00:00')
                """);
        jdbc.update("""
                INSERT INTO fams_asset_category (id,code,code_normalized,name,status,created_at,updated_at)
                VALUES (911005,'REQ','REQ','Computer','ACTIVE','2026-10-07 01:00:00','2026-10-07 01:00:00')
                """);
        jdbc.update("""
                INSERT INTO fams_warehouse (id,code,code_normalized,name,status,created_at,updated_at)
                VALUES (911007,'REQ','REQ','Storage','ACTIVE','2026-10-07 01:00:00','2026-10-07 01:00:00')
                """);
        jdbc.update("""
                INSERT INTO fams_location (id,code,code_normalized,name,location_type,warehouse_id,status,
                created_at,updated_at) VALUES (911006,'REQ','REQ','Room','ROOM',911007,'ACTIVE',
                '2026-10-07 01:00:00','2026-10-07 01:00:00')
                """);
        jdbc.update("""
                INSERT INTO fams_asset (id,asset_code,name,category_id,lifecycle_status,exception_status,card_stage,
                custody_type,storage_warehouse_id,current_location_id,created_business_date,parameter_version_id,
                creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by)
                VALUES (911010,'REQ-ASSET','Test inventory',911005,'IN_STOCK','NORMAL','ACTIVE','STORAGE',911007,
                911006,'2026-10-07',911004,911003,'REQ','2026-10-07 01:00:00','2026-10-07 01:00:00',911003,911003)
                """);
        jdbc.update("""
                INSERT INTO fams_audit_event (id,request_id,actor_account_id,actor_role,action,resource_type,
                resource_id,occurred_at,detail_json) VALUES (911020,'00000000-0000-0000-0000-000000911020',
                911003,'WAREHOUSE_OPERATOR','FIXTURE','REQUISITION',911101,'2026-10-07 01:00:00','{}')
                """);
        for (long request : new long[] {911101, 911102}) {
            jdbc.update("""
                    INSERT INTO fams_requisition (id,request_no,creator_account_id,creator_username_snapshot,
                    request_status,parameter_version_id,parameter_snapshot_json,created_at,updated_at)
                    VALUES (?, ?,911003,'REQ','DRAFT',911004,'{}','2026-10-07 01:00:00','2026-10-07 01:00:00')
                    """, request, "REQ-" + request);
            jdbc.update("""
                    INSERT INTO fams_requisition_demand (id,request_id,line_no,category_id,category_version_snapshot,
                    category_name_snapshot,specification,purpose,expected_date,custody_type,target_organization_id,
                    organization_version_snapshot,target_employee_id,employee_version_snapshot,target_location_id,
                    location_version_snapshot,target_snapshot_json,requested_quantity,pending_allocation_quantity)
                    VALUES (?,?,1,911005,0,'Computer','Memory 16GB','Office','2026-10-08','PERSONAL',911001,9,
                    911002,0,911006,0,'{}',3,3)
                    """, request + 10, request);
        }
    }
}
