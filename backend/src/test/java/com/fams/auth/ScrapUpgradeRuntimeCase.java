package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfSystemProperty(named = "d6.22.scrap.upgrade.runtime", matches = "true")
class ScrapUpgradeRuntimeCase {
    @Test
    void v59AddsScrapFactsWithoutRewritingAnExistingAssetOrItsLock() {
        var source = RepairFactoryPartsBaselineRuntimeCase.source();
        var db = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration/d6_04")
                .target("58").load().migrate();
        new RepairFactoryPartsBaselineRuntimeCase().seedAtCurrentSchema(db);
        long asset = 954520L;
        long actor = 954520L;
        try {
            var assetBefore = db.queryForMap("SELECT * FROM fams_asset WHERE id=?", asset);
            var lockBefore = db.queryForMap(
                    "SELECT * FROM fams_asset_business_lock WHERE asset_id=?", asset);
            var migration = Flyway.configure().dataSource(source)
                    .locations("classpath:db/migration/d6_04").target("59").load();
            assertEquals(1, migration.migrate().migrationsExecuted);
            assertEquals(0, migration.migrate().migrationsExecuted);
            assertEquals("59", migration.info().current().getVersion().toString());
            assertEquals(assetBefore, db.queryForMap("SELECT * FROM fams_asset WHERE id=?", asset));
            assertEquals(lockBefore, db.queryForMap(
                    "SELECT * FROM fams_asset_business_lock WHERE asset_id=?", asset));
            assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_scrap_request", Long.class));
            assertEquals(0L, db.queryForObject(
                    "SELECT COUNT(*) FROM fams_scrap_disposition", Long.class));

            var invalidSource = assertThrows(UncategorizedSQLException.class,
                    () -> insert(db, asset, actor, "FACTORY_REPLACEMENT", 91L));
            assertEquals(3819, invalidSource.getSQLException().getErrorCode());
            insert(db, asset, actor, "REPAIR", 91L);
            assertThrows(DataIntegrityViolationException.class,
                    () -> insert(db, asset, actor, "REPAIR", 91L));
            db.update("UPDATE fams_scrap_request SET request_status='REJECTED' WHERE asset_id=?", asset);
            insert(db, asset, actor, "REPAIR", 91L);
            assertEquals(2L, db.queryForObject(
                    "SELECT COUNT(*) FROM fams_scrap_request WHERE asset_id=?", Long.class, asset));
        } finally {
            if (db.queryForObject("SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema=DATABASE() AND table_name='fams_scrap_request'", Long.class) == 1L) {
                db.update("DELETE FROM fams_scrap_request WHERE asset_id=? AND creator_account_id=?", asset, actor);
            }
            new LossUpgradeRuntimeCase().removeOnlyOwnedFixtureBeforeSuccessorBusinessSuites();
        }
    }

    private void insert(JdbcTemplate db, long asset, long actor, String source, long sourceId) {
        db.update("INSERT INTO fams_scrap_request"
                + " (asset_id,creator_account_id,source_type,source_business_id,source_snapshot_json,"
                + " reason,observed_condition,proposed_disposition,asset_version,created_at,updated_at)"
                + " VALUES (?,?,?,?,'{}','现有维修无法恢复','保留原维修事实','待审核',0,NOW(3),NOW(3))",
                asset, actor, source, sourceId);
    }
}
