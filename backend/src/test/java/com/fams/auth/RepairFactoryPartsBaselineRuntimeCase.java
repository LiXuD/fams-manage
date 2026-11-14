package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Retained owned V53 fixture for backup/restore; not real manufacturer or money evidence. */
@EnabledIfSystemProperty(named = "d6.20.repair.factory.parts.baseline", matches = "true")
class RepairFactoryPartsBaselineRuntimeCase {
    static DriverManagerDataSource source() {
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        assertTrue(
                settings.url()
                        .matches(
                                "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/fams-d6-04-db-[0-9a-f]{16}\\?.*"));
        return new DriverManagerDataSource(
                settings.url(), settings.username(), settings.password());
    }

    @Test
    void observePopulatedVersionFiftyThreeBeforePartsMigration() {
        var source = source();
        var migration =
                Flyway.configure()
                        .dataSource(source)
                        .locations("classpath:db/migration/d6_04")
                        .target("53")
                        .load();
        migration.migrate();
        assertEquals("53", migration.info().current().getVersion().toString());
        var db = new JdbcTemplate(source);
        seedAtCurrentSchema(db);
    }

    void seedAtCurrentSchema(JdbcTemplate db) {
        var existing = db.queryForList("SELECT code FROM fams_organization WHERE id=954520");
        if (!existing.isEmpty()) {
            assertEquals("FACTORYPARTS54", existing.getFirst().get("code"));
            assertEquals(
                    2,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_repair_factory_round WHERE repair_id=954520",
                            Integer.class));
            return;
        }
        db.update(
                "INSERT INTO"
                    + " fams_organization(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (954520,'FACTORYPARTS54','FACTORYPARTS54','Owned V53 parts"
                    + " baseline','ACTIVE',NOW(3),NOW(3))");
        new RepairFactoryUpgradeRuntimeCase().seed(db);
        db.update(
                "INSERT INTO fams_repair_factory_scope(id,repair_id,scope_status,allowed_method,"
                    + "cost_policy,cost_limit,request_snapshot_json,request_reason,requested_by,requested_at,"
                    + "decision_snapshot_json,decision_reason,decided_by,decided_at,version) VALUES"
                    + " (954520,954520,'APPROVED','FACTORY_COST_ALLOWED','LIMITED',100.00,'{}','Owned"
                    + " V53 scope',954520,'2026-01-03','{}','Owned V53 explicit"
                    + " budget',954520,'2026-01-03',1)");
        for (int number = 1; number <= 2; number++) {
            db.update(
                    "INSERT INTO"
                        + " fams_repair_factory_round(repair_id,asset_id,scope_id,round_number,"
                        + "provider_name,service_contact,service_phone,expected_return_date,fault,"
                        + "original_identity_json,created_by,created_at) VALUES"
                        + " (954520,954520,954520,?,'Owned V53 provider','Owned V53"
                        + " contact','123','2026-02-01','Original V53"
                        + " fault','{}',954520,'2026-01-03')",
                    number);
            if (number == 1) {
                db.update(
                        "UPDATE fams_repair_factory_round SET"
                            + " round_status='RECEIVED',dispatched_at='2026-01-04',dispatched_by=954520,dispatch_evidence='Original"
                            + " dispatch',"
                            + "received_at='2026-01-05',received_by=954520,receive_location_id=954520,receive_location_version=0,condition_result='RESTORED',treatment_result='Actual"
                            + " V53 receipt',actual_cost=48.49 WHERE repair_id=954520 AND"
                            + " round_number=1");
            } else {
                db.update(
                        "UPDATE fams_repair_factory_round SET"
                            + " round_status='CANCELLED',cancel_reason='Never physically"
                            + " dispatched' WHERE repair_id=954520 AND round_number=2");
            }
        }
        long round =
                db.queryForObject(
                        "SELECT id FROM fams_repair_factory_round WHERE repair_id=954520 AND"
                            + " round_number=1",
                        Long.class);
        // Deliberate schema fixtures, not scanned originals or production financial assertions.
        String objects = "[\"00000000-0000-0000-0000-000000000054\"]";
        db.update(
                "INSERT INTO"
                    + " fams_repair_factory_quote(round_id,amount,evidence_ids_json,snapshot_json,created_by,created_at)"
                    + " VALUES (?,50.50,?,'{}',954520,'2026-01-03')",
                round,
                objects);
        db.update(
                "INSERT INTO"
                    + " fams_repair_factory_cost(round_id,amount,evidence_ids_json,snapshot_json,recorded_by,recorded_at)"
                    + " VALUES (?,48.49,?,'{}',954520,'2026-01-05')",
                round,
                objects);
        System.out.println(
                "{\"ownedPartsBaseline\":true,\"version\":53,\"fixtureOnlyNotActualBusiness\":true}");
    }
}
