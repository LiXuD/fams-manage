package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.LinkedHashMap;
import java.util.List;

/** Owned V52 DDL/data fixture for successor backup and upgrade; not actual business acceptance. */
@EnabledIfSystemProperty(named = "d6.20.repair.factory.cost.baseline", matches = "true")
class RepairFactoryCostBaselineRuntimeCase {
    @Test
    void observePopulatedVersionFiftyTwoBeforeAnyExpenseMigration() {
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        assertTrue(
                settings.url()
                        .matches(
                                "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/fams-d6-04-db-[0-9a-f]{16}\\?.*"),
                "This retained fixture must run only in an owned disposable runtime database");
        var source =
                new DriverManagerDataSource(
                        settings.url(), settings.username(), settings.password());
        var flyway =
                Flyway.configure()
                        .dataSource(source)
                        .locations("classpath:db/migration/d6_04")
                        .target("52")
                        .load();
        flyway.migrate();
        assertEquals("52", flyway.info().current().getVersion().toString());
        var db = new JdbcTemplate(source);
        db.update(
                "INSERT INTO"
                    + " fams_organization(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (954520,'FACTORYUP52','FACTORYUP52','Owned V52 expense"
                    + " baseline','ACTIVE',NOW(3),NOW(3))");
        new RepairFactoryUpgradeRuntimeCase().seed(db);
        db.update(
                "INSERT INTO"
                    + " fams_repair_factory_scope(id,repair_id,scope_status,request_snapshot_json,request_reason,requested_by,requested_at,decision_snapshot_json,decision_reason,decided_by,decided_at,version)"
                    + " VALUES (954520,954520,'APPROVED','{}','Retained V52 DDL"
                    + " fixture',954520,'2026-01-03','{}','Original no-cost"
                    + " authorization',954520,'2026-01-03',1)");
        String insert =
                "INSERT INTO"
                    + " fams_repair_factory_round(repair_id,asset_id,scope_id,round_number,provider_name,service_contact,service_phone,expected_return_date,fault,original_identity_json,created_by,created_at)"
                    + " VALUES (954520,954520,954520,?,'Owned original provider','Owned original"
                    + " contact','123','2026-02-01','Legacy original"
                    + " fault','{\"assetId\":\"954520\",\"assetCode\":\"FACTORY-UP-52\",\"serialNumber\":null}',954520,'2026-01-03')";
        db.update(insert, 1);
        db.update(
                "UPDATE fams_repair_factory_round SET"
                    + " round_status='RECEIVED',dispatched_at='2026-01-04',dispatched_by=954520,dispatch_evidence='Retained"
                    + " original"
                    + " dispatch',received_at='2026-01-05',received_by=954520,receive_location_id=954520,receive_location_version=0,condition_result='STILL_FAULTY',treatment_result='Retained"
                    + " original receipt with verified no charge',actual_cost=0 WHERE"
                    + " repair_id=954520 AND round_number=1");
        db.update(insert, 2);
        db.update(
                "UPDATE fams_repair_factory_round SET round_status='CANCELLED',cancel_reason='No"
                        + " physical dispatch occurred' WHERE repair_id=954520 AND round_number=2");
        var witness = new LinkedHashMap<String, Object>();
        witness.put("ownedCostBaseline", true);
        witness.put("version", 52);
        witness.put("fixtureOnlyNotActualBusiness", true);
        witness.put(
                "tables",
                db.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                + " table_schema=DATABASE()",
                        Long.class));
        witness.put(
                "factoryColumns",
                db.queryForList(
                        "SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA"
                            + " FROM information_schema.columns WHERE table_schema=DATABASE() AND"
                            + " table_name LIKE 'fams_repair_factory_%' ORDER BY"
                            + " TABLE_NAME,ORDINAL_POSITION"));
        witness.put(
                "constraints",
                db.queryForList(
                        "SELECT tc.TABLE_NAME,tc.CONSTRAINT_NAME,cc.CHECK_CLAUSE FROM"
                                + " information_schema.table_constraints tc JOIN"
                                + " information_schema.check_constraints cc ON"
                                + " cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND"
                                + " cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME WHERE"
                                + " tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.TABLE_NAME LIKE"
                                + " 'fams_repair_factory_%' ORDER BY"
                                + " tc.TABLE_NAME,tc.CONSTRAINT_NAME"));
        witness.put(
                "roundFacts",
                db.queryForList(
                        "SELECT round_number,round_status,CAST(expected_return_date AS CHAR) AS"
                            + " expected_return_date,dispatched_at,received_at,actual_cost,version"
                            + " FROM fams_repair_factory_round WHERE repair_id=954520 ORDER BY"
                            + " round_number"));
        for (String table :
                List.of(
                        "fams_repair_decision",
                        "fams_repair_event",
                        "fams_asset_business_lock",
                        "fams_todo")) {
            witness.put(table, db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        }
        System.out.println(new tools.jackson.databind.ObjectMapper().writeValueAsString(witness));
        // Deliberately retained only until the owning runner dumps/restores and removes its
        // container.
    }
}
