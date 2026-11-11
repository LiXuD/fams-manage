package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfSystemProperty(named = "d6.22.scrap.correction.upgrade.runtime", matches = "true")
class ScrapCorrectionUpgradeRuntimeCase {
    @Test
    void v61AddsCorrectionsWithoutRewritingScrapFactsAndGuardsOneActivePerRequest() {
        var source = RepairFactoryPartsBaselineRuntimeCase.source();
        var db = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration/d6_04")
                .target("60").load().migrate();
        new RepairFactoryPartsBaselineRuntimeCase().seedAtCurrentSchema(db);
        long asset = 954520L;
        long actor = 954520L;
        try {
            var frozen = insertRequest(db, asset, actor, "RESTORE_REQUIRED");
            var migration = Flyway.configure().dataSource(source)
                    .locations("classpath:db/migration/d6_04").target("61").load();
            assertEquals(1, migration.migrate().migrationsExecuted);
            assertEquals(0, migration.migrate().migrationsExecuted);
            assertEquals("61", migration.info().current().getVersion().toString());
            assertEquals(frozen, db.queryForMap("SELECT * FROM fams_scrap_request WHERE asset_id=? AND creator_account_id=?",
                    asset, actor));
            assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_scrap_correction", Long.class));
            insertRequest(db, asset, actor, "CORRECTED");
            var invalidStatus = assertThrows(UncategorizedSQLException.class,
                    () -> insertRequest(db, asset, actor, "CORRECTING"));
            assertEquals(3819, invalidStatus.getSQLException().getErrorCode());
            var request = db.queryForObject("SELECT id FROM fams_scrap_request"
                    + " WHERE request_status='RESTORE_REQUIRED' AND creator_account_id=? ORDER BY id LIMIT 1",
                    Long.class, actor);
            insertCorrection(db, request, asset, actor, "PENDING_ADMIN_DECISION");
            assertThrows(DuplicateKeyException.class,
                    () -> insertCorrection(db, request, asset, actor, "PROCESSING"));
            insertCorrection(db, request, asset, actor, "COMPLETED");
            assertEquals(2L, db.queryForObject("SELECT COUNT(*) FROM fams_scrap_correction WHERE request_id=?",
                    Long.class, request));
            assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM fams_scrap_correction"
                    + " WHERE request_id=? AND active_guard=1", Long.class, request));
        } finally {
            if (db.queryForObject("SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema=DATABASE() AND table_name='fams_scrap_correction'", Long.class) == 1L) {
                db.update("DELETE FROM fams_scrap_correction WHERE request_id IN"
                        + " (SELECT id FROM fams_scrap_request WHERE asset_id=? AND creator_account_id=?)", asset, actor);
                db.update("DELETE FROM fams_scrap_request WHERE asset_id=? AND creator_account_id=?", asset, actor);
            }
            new LossUpgradeRuntimeCase().removeOnlyOwnedFixtureBeforeSuccessorBusinessSuites();
        }
    }

    private java.util.Map<String, Object> insertRequest(JdbcTemplate db, long asset, long actor, String status) {
        db.update("INSERT INTO fams_scrap_request"
                + " (asset_id,creator_account_id,source_type,source_snapshot_json,reason,observed_condition,"
                + " proposed_disposition,request_status,asset_version,created_at,updated_at)"
                + " VALUES (?,?,?,'{}','现有申请冻结待纠正','保留原事实','待审核',?,0,NOW(3),NOW(3))",
                asset, actor, "STANDALONE", status);
        return db.queryForMap("SELECT * FROM fams_scrap_request WHERE asset_id=? AND creator_account_id=?"
                + " AND request_status=? ORDER BY id DESC LIMIT 1", asset, actor, status);
    }

    private void insertCorrection(JdbcTemplate db, long request, long asset, long actor, String status) {
        db.update("INSERT INTO fams_scrap_correction"
                + " (request_id,asset_id,creator_account_id,reason,asset_version,lock_version,"
                + " target_location_id,target_location_version,correction_status,request_snapshot_json,"
                + " created_at,updated_at) VALUES (?,?,?,?,0,0,1,0,?,'{}',NOW(3),NOW(3))",
                request, asset, actor, "按有效仓位受控纠正", status);
    }
}
