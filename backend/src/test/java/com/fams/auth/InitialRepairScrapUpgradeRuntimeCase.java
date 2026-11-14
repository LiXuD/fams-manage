package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfSystemProperty(named = "d6.23.initial.repair.scrap.upgrade.runtime", matches = "true")
class InitialRepairScrapUpgradeRuntimeCase {
    @Test
    void addsLinkedRepairWithoutRewritingExistingInitialScrap() {
        var source = RepairFactoryPartsBaselineRuntimeCase.source();
        var db = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration/d6_04")
                .target("74").load().migrate();
        new RepairFactoryPartsBaselineRuntimeCase().seedAtCurrentSchema(db);
        long asset = 954520L;
        String batch = UUID.randomUUID().toString();
        try {
            db.update("""
                    INSERT INTO fams_asset_import_batch(id,actor_account_id,upload_key,source_sha256,
                        source_bytes,source_xlsx,created_at,updated_at)
                    VALUES (?,?,'v75-upgrade-key',REPEAT('a',64),1,X'01',NOW(3),NOW(3))
                    """, batch, asset);
            insertRequest(db, asset, batch, 2, "STANDALONE", null);
            var original = db.queryForMap("SELECT * FROM fams_scrap_request WHERE initial_batch_id=?", batch);
            assertEquals(3819, assertThrows(UncategorizedSQLException.class,
                    () -> insertRequest(db, asset, batch, 3, "REPAIR", asset)).getSQLException().getErrorCode());

            var migration = Flyway.configure().dataSource(source)
                    .locations("classpath:db/migration/d6_04").target("75").load();
            assertEquals(1, migration.migrate().migrationsExecuted);
            assertEquals(0, migration.migrate().migrationsExecuted);
            assertEquals(original, db.queryForMap("SELECT * FROM fams_scrap_request WHERE initial_batch_id=?", batch));
            insertRequest(db, asset, batch, 3, "REPAIR", asset);
            assertEquals(3819, assertThrows(UncategorizedSQLException.class,
                    () -> insertRequest(db, asset, batch, 4, "LOSS", asset)).getSQLException().getErrorCode());
            assertEquals(asset, db.queryForObject("""
                    SELECT source_business_id FROM fams_scrap_request
                    WHERE initial_batch_id=? AND initial_sheet_row=3
                    """, Long.class, batch));
            var linked = db.queryForMap("SELECT * FROM fams_scrap_request WHERE initial_batch_id=? AND initial_sheet_row=3", batch);
            var lossMigration = Flyway.configure().dataSource(source)
                    .locations("classpath:db/migration/d6_04").target("76").load();
            assertEquals(1, lossMigration.migrate().migrationsExecuted);
            assertEquals(0, lossMigration.migrate().migrationsExecuted);
            assertEquals(original, db.queryForMap(
                    "SELECT * FROM fams_scrap_request WHERE initial_batch_id=? AND initial_sheet_row=2", batch));
            assertEquals(linked, db.queryForMap(
                    "SELECT * FROM fams_scrap_request WHERE initial_batch_id=? AND initial_sheet_row=3", batch));
            insertRequest(db, asset, batch, 4, "LOSS", asset);
            assertEquals(3819, assertThrows(UncategorizedSQLException.class,
                    () -> insertRequest(db, asset, batch, 1, "LOSS", asset)).getSQLException().getErrorCode());
        } finally {
            db.update("DELETE FROM fams_scrap_request WHERE initial_batch_id=?", batch);
            db.update("DELETE FROM fams_asset_import_batch WHERE id=?", batch);
            new LossUpgradeRuntimeCase().removeOnlyOwnedFixtureBeforeSuccessorBusinessSuites();
        }
    }

    private void insertRequest(JdbcTemplate db, long asset, String batch, int row, String source, Long sourceId) {
        db.update("""
                INSERT INTO fams_scrap_request(asset_id,creator_account_id,source_type,source_business_id,
                    source_snapshot_json,reason,observed_condition,proposed_disposition,request_status,
                    asset_version,initial_batch_id,initial_sheet_row,created_at,updated_at)
                VALUES (?,?,?,?,'{}','旧件无法修复','实物可核实','待处置保管','CANCELLED',0,?,?,NOW(3),NOW(3))
                """, asset, asset, source, sourceId, batch, row);
    }
}
