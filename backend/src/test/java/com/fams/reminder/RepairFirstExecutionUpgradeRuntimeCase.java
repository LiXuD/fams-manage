package com.fams.reminder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfSystemProperty(named = "d6.24.repair.first.execution.upgrade.runtime", matches = "true")
class RepairFirstExecutionUpgradeRuntimeCase {
    @Test
    void populatedV82RetainsExistingRepairAndAcceptsBothWriters() {
        String url = System.getenv("D6_24C_TEST_DATASOURCE_URL");
        assertTrue(url != null && url.matches(
                "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/(fams-d6-24c-db-[0-9a-f]{16}|fams_duty)\\?.*"));
        var source = new DriverManagerDataSource(url,
                System.getenv("D6_24C_TEST_DATASOURCE_USERNAME"), System.getenv("D6_24C_TEST_DATASOURCE_PASSWORD"));
        var migration = Flyway.configure().dataSource(source).locations("classpath:db/migration/d6_04");
        migration.target("82").load().migrate();
        var db = new JdbcTemplate(source);
        String legacyInsert = """
                INSERT INTO fams_repair_request (id,asset_id,creator_account_id,asset_version,source_snapshot_json,
                    timezone_id,fault,impact,occurred_at,created_at,updated_at)
                VALUES (?,982083,982083,0,'{"preserve":true}','UTC','fixture','LOW',NOW(3),NOW(3),NOW(3))
                """;
        try {
            seed(db);
            db.update(legacyInsert, 982083);
            var prior = db.queryForMap("SELECT * FROM fams_repair_request WHERE id=982083");
            var v83 = migration.target("83").load();
            assertEquals(1, v83.migrate().migrationsExecuted);
            assertEquals(0, v83.migrate().migrationsExecuted);
            v83.validate();

            var upgraded = new HashMap<>(db.queryForMap("SELECT * FROM fams_repair_request WHERE id=982083"));
            assertEquals(prior.size() + 1, upgraded.size());
            assertNull(upgraded.remove("first_execution_parameter_snapshot_json"));
            assertEquals(prior, upgraded);
            assertEquals(1, db.update(legacyInsert, 982084));
            assertEquals(1, db.update("""
                    INSERT INTO fams_repair_request (id,asset_id,creator_account_id,asset_version,source_snapshot_json,
                        timezone_id,fault,impact,occurred_at,created_at,updated_at,first_execution_parameter_snapshot_json)
                    VALUES (982085,982083,982083,0,'{}','UTC','fixture','LOW',NOW(3),NOW(3),NOW(3),
                        JSON_OBJECT('timezone','UTC'))
                    """));
            assertNull(db.queryForObject("""
                    SELECT first_execution_parameter_snapshot_json FROM fams_repair_request WHERE id=982084
                    """, String.class));
            assertEquals("UTC", db.queryForObject("""
                    SELECT first_execution_parameter_snapshot_json->>'$.timezone'
                    FROM fams_repair_request WHERE id=982085
                    """, String.class));
        } finally {
            db.update("DELETE FROM fams_repair_request WHERE id BETWEEN 982083 AND 982085");
            db.update("DELETE FROM fams_asset WHERE id=982083");
            db.update("DELETE FROM fams_business_parameter_version WHERE id=982083");
            db.update("DELETE FROM fams_system_account WHERE id=982083");
            db.update("DELETE FROM fams_employee WHERE id=982083");
            db.update("DELETE FROM fams_organization WHERE id=982083");
        }
    }

    private static void seed(JdbcTemplate db) {
        db.update("""
                INSERT INTO fams_organization (id,code,code_normalized,name,status,created_at,updated_at)
                VALUES (982083,'CALUP','CALUP','Fixture','ACTIVE',NOW(3),NOW(3))
                """);
        db.update("""
                INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,organization_id,
                    status,created_at,updated_at)
                VALUES (982083,'CALUP','CALUP','Fixture',982083,'ACTIVE',NOW(3),NOW(3))
                """);
        db.update("""
                INSERT INTO fams_system_account (id,employee_id,username,username_normalized,password_hash,status,
                    initial_password_required,created_at,updated_at)
                VALUES (982083,982083,'calendarfixture','CALENDARFIXTURE','fixture','ENABLED',FALSE,NOW(3),NOW(3))
                """);
        db.update("""
                INSERT INTO fams_business_parameter_version (id,version,status,effective_at,actor_account_id,
                    actor_username,snapshot_json,created_at)
                VALUES (982083,982083,'ACTIVE',NOW(3),982083,'calendarfixture','{}',NOW(3))
                """);
        db.update("""
                INSERT INTO fams_asset (id,asset_code,name,lifecycle_status,exception_status,card_stage,created_business_date,
                    parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by)
                VALUES (982083,'CALUP','Fixture','WAITING_INBOUND','NORMAL','DRAFT',UTC_DATE(),
                    982083,982083,'calendarfixture',NOW(3),NOW(3),982083,982083)
                """);
    }
}
