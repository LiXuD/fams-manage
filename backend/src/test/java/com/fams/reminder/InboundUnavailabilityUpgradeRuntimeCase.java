package com.fams.reminder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfSystemProperty(named = "d6.24.inbound.unavailability.upgrade.runtime", matches = "true")
class InboundUnavailabilityUpgradeRuntimeCase {
    @Test
    void expandsPopulatedV77WithoutChangingOwnership() {
        String url = System.getenv("D6_24C_TEST_DATASOURCE_URL");
        assertTrue(url != null && url.matches(
                "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/(fams-d6-24c-db-[0-9a-f]{16}|fams_duty)\\?.*"));
        var source = new DriverManagerDataSource(url,
                System.getenv("D6_24C_TEST_DATASOURCE_USERNAME"), System.getenv("D6_24C_TEST_DATASOURCE_PASSWORD"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration/d6_04").target("77").load().migrate();
        var db = new JdbcTemplate(source);
        try {
            seed(db);
            var prior = db.queryForMap("SELECT * FROM fams_inbound_processing_ownership WHERE receipt_id=982001");
            var v78 = Flyway.configure().dataSource(source).locations("classpath:db/migration/d6_04").target("78").load();
            assertEquals(1, v78.migrate().migrationsExecuted);
            assertEquals(0, v78.migrate().migrationsExecuted);
            var current = new HashMap<>(db.queryForMap(
                    "SELECT * FROM fams_inbound_processing_ownership WHERE receipt_id=982001"));
            assertEquals(prior.size() + 3, current.size());
            for (var column : new String[]{"unavailable_at", "unavailable_reason", "unavailable_ownership_version"}) {
                assertNull(current.remove(column));
            }
            assertEquals(prior, current);
            assertEquals(3819, assertThrows(UncategorizedSQLException.class, () -> db.update(
                    "UPDATE fams_inbound_processing_ownership SET unavailable_reason='Partial' WHERE receipt_id=982001"))
                    .getSQLException().getErrorCode());
            assertEquals(1, db.update("""
                    UPDATE fams_inbound_processing_ownership SET unavailable_at=UTC_TIMESTAMP(3),
                        unavailable_reason='Complete', unavailable_ownership_version=ownership_version
                    WHERE receipt_id=982001
                    """));
            assertEquals(1, db.update("UPDATE fams_inbound_processing_ownership SET ownership_version=ownership_version+1"
                    + " WHERE receipt_id=982001"));
        } finally {
            db.update("DELETE FROM fams_inbound_processing_ownership WHERE receipt_id=982001");
            db.update("DELETE FROM fams_inbound_receipt WHERE id=982001");
            db.update("DELETE FROM fams_business_parameter_version WHERE id=982001");
            db.update("DELETE FROM fams_system_account WHERE id=982001");
            db.update("DELETE FROM fams_employee WHERE id=982001");
            db.update("DELETE FROM fams_organization WHERE id=982001");
        }
    }

    private static void seed(JdbcTemplate db) {
        db.update("""
                INSERT INTO fams_organization (id,code,code_normalized,name,status,version,created_at,updated_at)
                VALUES (982001,'AVAIL','AVAIL','Fixture','ACTIVE',0,NOW(3),NOW(3))
                """);
        db.update("""
                INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,organization_id,
                    status,version,created_at,updated_at)
                VALUES (982001,'AVAIL','AVAIL','Fixture',982001,'ACTIVE',0,NOW(3),NOW(3))
                """);
        db.update("""
                INSERT INTO fams_system_account (id,employee_id,username,username_normalized,password_hash,status,
                    initial_password_required,created_at,updated_at)
                VALUES (982001,982001,'availabilityfixture','AVAILABILITYFIXTURE','fixture','ENABLED',FALSE,NOW(3),NOW(3))
                """);
        db.update("""
                INSERT INTO fams_business_parameter_version (id,version,status,effective_at,actor_account_id,
                    actor_username,snapshot_json,created_at)
                VALUES (982001,982001,'ACTIVE',NOW(3),982001,'availabilityfixture','{}',NOW(3))
                """);
        db.update("""
                INSERT INTO fams_inbound_receipt (id,receipt_no,creator_account_id,creator_username_snapshot,
                    receipt_status,business_date,parameter_version_id,timezone_snapshot,admin_decision_deadline_workdays,
                    warehouse_claim_deadline_workdays,warehouse_first_execution_deadline_workdays,submitted_at,
                    version,created_at,updated_at,created_by,updated_by)
                VALUES (982001,'AVAIL-FIXTURE',982001,'availabilityfixture','WAREHOUSE_PROCESSING',UTC_DATE(),
                    982001,'UTC',2,1,2,NOW(3),3,NOW(3),NOW(3),982001,982001)
                """);
        db.update("""
                INSERT INTO fams_inbound_processing_ownership
                    (receipt_id,processor_account_id,processor_username_snapshot,ownership_version,acquired_at)
                VALUES (982001,982001,'availabilityfixture',1,NOW(3))
                """);
    }
}
