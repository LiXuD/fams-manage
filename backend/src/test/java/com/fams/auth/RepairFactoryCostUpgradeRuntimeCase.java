package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Forward DDL/populated fixture verification, separate from real expense business acceptance. */
@EnabledIfSystemProperty(named = "d6.20.repair.factory.cost.upgrade.runtime", matches = "true")
class RepairFactoryCostUpgradeRuntimeCase {
    @AfterEach
    void removeOnlyOwnedUpgradeFactsBeforeSuccessorBusinessSuites() {
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        assertTrue(
                settings.url()
                        .matches(
                                "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/fams-d6-04-db-[0-9a-f]{16}\\?.*"));
        var source =
                new DriverManagerDataSource(
                        settings.url(), settings.username(), settings.password());
        var db = new JdbcTemplate(source);
        var transaction =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                source));
        transaction.executeWithoutResult(
                status -> {
                    for (String table :
                            List.of("fams_repair_factory_quote", "fams_repair_factory_cost")) {
                        if (db.queryForObject(
                                        "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                            + " table_schema=DATABASE() AND table_name=?",
                                        Integer.class,
                                        table)
                                > 0) {
                            db.update(
                                    "DELETE f FROM "
                                            + table
                                            + " f JOIN fams_repair_factory_round r ON"
                                            + " r.id=f.round_id WHERE r.repair_id=954520");
                        }
                    }
                    for (String table :
                            List.of(
                                    "fams_repair_factory_evidence",
                                    "fams_repair_factory_event",
                                    "fams_repair_factory_round",
                                    "fams_repair_factory_scope")) {
                        db.update("DELETE FROM " + table + " WHERE repair_id=954520");
                    }
                    db.update(
                            "DELETE FROM fams_todo WHERE business_type='REPAIR' AND"
                                + " business_id=954520");
                    db.update("DELETE FROM fams_asset_business_lock WHERE asset_id=954520");
                    for (String table :
                            List.of(
                                    "fams_repair_physical_handling",
                                    "fams_repair_decision",
                                    "fams_repair_event",
                                    "fams_repair_receipt")) {
                        db.update("DELETE FROM " + table + " WHERE repair_id=954520");
                    }
                    db.update("DELETE FROM fams_repair_request WHERE id=954520");
                    db.update("DELETE FROM fams_asset WHERE id=954520");
                    db.update("DELETE FROM fams_business_parameter_version WHERE id=954520");
                    db.update("DELETE FROM fams_location WHERE id=954520");
                    db.update("DELETE FROM fams_warehouse WHERE id=954520");
                    db.update("DELETE FROM fams_asset_category WHERE id=954520");
                    db.update("DELETE FROM fams_account_role WHERE account_id=954520");
                    db.update("DELETE FROM fams_system_account WHERE id=954520");
                    db.update("DELETE FROM fams_employee WHERE id=954520");
                    db.update("DELETE FROM fams_organization WHERE id=954520");
                });
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_business_parameter_version WHERE id=954520",
                        Long.class));
    }

    @Test
    void populatedV52FactsSurviveForwardExpenseExpansionAndRepeat() {
        new RepairFactoryCostBaselineRuntimeCase()
                .observePopulatedVersionFiftyTwoBeforeAnyExpenseMigration();
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        var source =
                new DriverManagerDataSource(
                        settings.url(), settings.username(), settings.password());
        var db = new JdbcTemplate(source);
        var projections = new LinkedHashMap<String, List<String>>();
        for (String table :
                db.queryForList(
                        "SELECT TABLE_NAME FROM information_schema.tables WHERE"
                                + " table_schema=DATABASE() AND table_name<>'flyway_schema_history'"
                                + " ORDER BY TABLE_NAME",
                        String.class)) {
            assertTrue(table.matches("[a-zA-Z0-9_]+"));
            projections.put(
                    table,
                    db.queryForList(
                            "SELECT COLUMN_NAME FROM information_schema.columns WHERE"
                                    + " table_schema=DATABASE() AND table_name=? ORDER BY"
                                    + " ORDINAL_POSITION",
                            String.class,
                            table));
        }
        var before = snapshot(db, projections);
        var migration =
                Flyway.configure()
                        .dataSource(source)
                        .locations("classpath:db/migration/d6_04")
                        .target("53")
                        .load();
        assertEquals(1, migration.migrate().migrationsExecuted);
        assertEquals(0, migration.migrate().migrationsExecuted);
        assertEquals(before, snapshot(db, projections));
        assertEquals(
                "NO_COST",
                db.queryForObject(
                        "SELECT cost_policy FROM fams_repair_factory_scope WHERE id=954520",
                        String.class));
        assertEquals(
                new BigDecimal("0.00"),
                db.queryForObject(
                        "SELECT cost_limit FROM fams_repair_factory_scope WHERE id=954520",
                        BigDecimal.class));
        assertEquals(
                new BigDecimal("0.00"),
                db.queryForObject(
                        "SELECT actual_cost FROM fams_repair_factory_round WHERE repair_id=954520"
                                + " AND round_number=1",
                        BigDecimal.class));
        assertEquals(
                0L,
                db.queryForObject("SELECT COUNT(*) FROM fams_repair_factory_quote", Long.class));
        assertEquals(
                0L, db.queryForObject("SELECT COUNT(*) FROM fams_repair_factory_cost", Long.class));
        constraints(db);
        System.out.println(
                "{\"ownedCostUpgrade\":true,\"version\":53,\"migrations\":1,\"repeat\":0,\"legacyAllColumnsUnchanged\":true,\"legacyZeroRetainedWithoutBackfill\":true,\"fixtureOnlyNotActualBusiness\":true}");
    }

    private Map<String, String> snapshot(JdbcTemplate db, Map<String, List<String>> projections) {
        var result = new LinkedHashMap<String, String>();
        var json = new ObjectMapper();
        String quote = Character.toString((char) 96);
        projections.forEach(
                (table, columns) -> {
                    for (String column : columns) {
                        assertTrue(column.matches("[a-zA-Z0-9_]+"));
                    }
                    String fields =
                            columns.stream()
                                    .map(c -> quote + c + quote)
                                    .collect(java.util.stream.Collectors.joining(","));
                    result.put(
                            table,
                            json.writeValueAsString(
                                    db.queryForList(
                                            "SELECT "
                                                    + fields
                                                    + " FROM "
                                                    + quote
                                                    + table
                                                    + quote
                                                    + " ORDER BY "
                                                    + fields)));
                });
        return result;
    }

    private void constraints(JdbcTemplate db) {
        db.update(
                "INSERT INTO"
                    + " fams_repair_factory_scope(repair_id,scope_status,allowed_method,cost_policy,cost_limit,request_snapshot_json,request_reason,requested_by,requested_at,decision_snapshot_json,decision_reason,decided_by,decided_at)"
                    + " VALUES"
                    + " (954520,'APPROVED','FACTORY_COST_ALLOWED','LIMITED',100,'{}','DDL-only"
                    + " expense scope',954520,NOW(3),'{}','DDL-only approval',954520,NOW(3))");
        long scope =
                java.util.Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT id FROM fams_repair_factory_scope WHERE repair_id=954520"
                                        + " AND allowed_method='FACTORY_COST_ALLOWED'",
                                Long.class));
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "UPDATE fams_repair_factory_scope SET cost_limit=0 WHERE id=?",
                                scope));
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "UPDATE fams_repair_factory_scope SET cost_policy='UNLIMITED' WHERE"
                                        + " id=?",
                                scope));
        db.update(
                "INSERT INTO"
                    + " fams_repair_factory_round(repair_id,asset_id,scope_id,round_number,provider_name,service_contact,service_phone,expected_return_date,fault,original_identity_json,created_by,created_at)"
                    + " VALUES (954520,954520,?,3,'DDL-only provider','DDL-only"
                    + " contact','123','2026-02-01','DDL-only fault','{}',954520,'2026-01-03')",
                scope);
        long round =
                java.util.Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT id FROM fams_repair_factory_round WHERE repair_id=954520"
                                        + " AND round_number=3",
                                Long.class));
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "UPDATE fams_repair_factory_round SET actual_cost=-1 WHERE id=?",
                                round));
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "UPDATE fams_repair_factory_round SET dispatch_scope_id=? WHERE"
                                        + " id=?",
                                scope,
                                round));
        db.update(
                "UPDATE fams_repair_factory_round SET"
                    + " round_status='RECEIVED',dispatched_at='2026-01-04',dispatched_by=954520,dispatch_evidence='DDL-only"
                    + " dispatch',dispatch_scope_id=?,received_at='2026-01-05',received_by=954520,receive_location_id=954520,receive_location_version=0,condition_result='RESTORED',treatment_result='Actual"
                    + " physical fact separate from unresolved cost' WHERE id=?",
                scope,
                round);
        assertNull(
                db.queryForObject(
                        "SELECT actual_cost FROM fams_repair_factory_round WHERE id=?",
                        BigDecimal.class,
                        round));
        db.update(
                "INSERT INTO"
                    + " fams_repair_factory_cost(round_id,amount,evidence_ids_json,snapshot_json,recorded_by,recorded_at)"
                    + " VALUES"
                    + " (?,12.34,'[\"00000000-0000-4000-8000-000000000001\"]','{\"ddlFixtureOnly\":true}',954520,NOW(3))",
                round);
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        db.update(
                                "INSERT INTO"
                                    + " fams_repair_factory_cost(round_id,amount,evidence_ids_json,snapshot_json,recorded_by,recorded_at)"
                                    + " VALUES"
                                    + " (?,12.34,'[\"00000000-0000-4000-8000-000000000001\"]','{}',954520,NOW(3))",
                                round));
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "INSERT INTO"
                                    + " fams_repair_factory_quote(round_id,amount,evidence_ids_json,snapshot_json,created_by,created_at)"
                                    + " VALUES (?,5,'[]','{}',954520,NOW(3))",
                                round));
    }
}
