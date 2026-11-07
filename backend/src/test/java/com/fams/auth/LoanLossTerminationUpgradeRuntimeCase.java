package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfSystemProperty(named = "d6.22.loss.termination.upgrade", matches = "true")
class LoanLossTerminationUpgradeRuntimeCase {
    @Test
    void v60ExpandsTerminalStatusWithoutRewritingPopulatedV59Loans() {
        var source = RepairFactoryPartsBaselineRuntimeCase.source();
        var db = new JdbcTemplate(source);
        var configuration = Flyway.configure().dataSource(source).locations("classpath:db/migration/d6_04");
        configuration.target("59").load().migrate();
        new RepairFactoryPartsBaselineRuntimeCase().seedAtCurrentSchema(db);
        String batch = "00000000-0000-4000-8000-000000095460";
        try {
            db.update("INSERT INTO fams_asset_import_batch"
                    + " (id,actor_account_id,upload_key,source_sha256,source_bytes,source_xlsx,created_at,updated_at)"
                    + " VALUES (?,954520,'owned-loan-v59',SHA2('owned-loan-v59',256),1,X'01',NOW(3),NOW(3))", batch);
            db.update("INSERT INTO fams_loan(asset_id,borrower_employee_id,using_organization_id,source_type,"
                    + "initial_batch_id,initial_sheet_row,borrowed_date,original_return_date,expected_return_date,"
                    + "parameter_snapshot_json,relation_status,closed_at,created_at,updated_at) VALUES"
                    + " (954520,954520,954520,'INITIAL_IMPORT',?,2,'2026-01-01','2026-02-01','2026-02-04','{}','OPEN',NULL,NOW(3),NOW(3)),"
                    + " (954520,954520,954520,'INITIAL_IMPORT',?,3,'2025-12-01','2025-12-10','2025-12-10','{}','RETURNED','2025-12-08',NOW(3),NOW(3)),"
                    + " (954520,954520,954520,'INITIAL_IMPORT',?,4,'2025-11-01','2025-11-10','2025-11-10','{}','VOID','2025-11-05',NOW(3),NOW(3))",
                    batch, batch, batch);
            String fixture = "SELECT * FROM fams_loan WHERE initial_batch_id=? ORDER BY initial_sheet_row";
            var before = db.queryForList(fixture, batch);
            String open = " WHERE initial_batch_id=? AND initial_sheet_row=2";
            String terminate = "UPDATE fams_loan SET relation_status='LOST_TERMINATED'";
            assertEquals(3819, assertThrows(UncategorizedSQLException.class,
                    () -> db.update(terminate + ",closed_at=NOW(3)" + open, batch)).getSQLException().getErrorCode());
            var migration = configuration.target("60").load();
            assertEquals(1, migration.migrate().migrationsExecuted);
            assertEquals(0, migration.migrate().migrationsExecuted);
            assertEquals(before, db.queryForList(fixture, batch));
            assertEquals(3819, assertThrows(UncategorizedSQLException.class,
                    () -> db.update(terminate + open, batch)).getSQLException().getErrorCode());
            assertEquals(1, db.update(terminate + ",closed_at=NOW(3)" + open, batch));
            assertNull(db.queryForObject("SELECT open_asset_id FROM fams_loan" + open, Long.class, batch));
        } finally {
            db.update("DELETE FROM fams_loan WHERE initial_batch_id=?", batch);
            db.update("DELETE FROM fams_asset_import_batch WHERE id=?", batch);
            new LossUpgradeRuntimeCase().removeOnlyOwnedFixtureBeforeSuccessorBusinessSuites();
        }
    }
}
