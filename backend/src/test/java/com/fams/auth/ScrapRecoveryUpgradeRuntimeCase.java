package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfSystemProperty(named = "d6.22.scrap.recovery.upgrade.runtime", matches = "true")
class ScrapRecoveryUpgradeRuntimeCase {
    @Test
    void v63AddsRecoveriesWithoutRewritingWrittenOffScrapAndGuardsOneActivePerRequest() {
        var source = RepairFactoryPartsBaselineRuntimeCase.source();
        var db = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration/d6_04")
                .target("62").load().migrate();
        new RepairFactoryPartsBaselineRuntimeCase().seedAtCurrentSchema(db);
        long asset = 954520L;
        long actor = 954520L;
        try {
            var frozen = insertRequest(db, asset, actor);
            db.update("INSERT INTO fams_scrap_disposition(request_id,processor_account_id,disposition_status,"
                    + "custody_snapshot_json,completion_snapshot_json,created_at,updated_at) VALUES"
                    + " (?,?,'LOSS_WRITTEN_OFF','{}','{\"writtenOffAt\":\"2026-11-09T00:00:00.000Z\"}',NOW(3),NOW(3))",
                    frozen.get("id"), actor);
            var writtenOff = db.queryForMap("SELECT * FROM fams_scrap_disposition WHERE request_id=?",
                    frozen.get("id"));
            var migration = Flyway.configure().dataSource(source)
                    .locations("classpath:db/migration/d6_04").target("63").load();
            assertEquals(1, migration.migrate().migrationsExecuted);
            assertEquals(0, migration.migrate().migrationsExecuted);
            assertEquals("63", migration.info().current().getVersion().toString());
            assertEquals(frozen, db.queryForMap("SELECT * FROM fams_scrap_request WHERE asset_id=? AND creator_account_id=?",
                    asset, actor));
            assertEquals(writtenOff, db.queryForMap("SELECT * FROM fams_scrap_disposition WHERE request_id=?",
                    frozen.get("id")));
            assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM fams_scrap_recovery", Long.class));
            long request = ((Number) frozen.get("id")).longValue();
            insertRecovery(db, request, asset, actor, "PENDING_ADMIN_DECISION");
            assertThrows(DuplicateKeyException.class,
                    () -> insertRecovery(db, request, asset, actor, "PROCESSING"));
            insertRecovery(db, request, asset, actor, "COMPLETED");
            assertEquals(2L, db.queryForObject("SELECT COUNT(*) FROM fams_scrap_recovery WHERE request_id=?",
                    Long.class, request));
            assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM fams_scrap_recovery"
                    + " WHERE request_id=? AND active_guard=1", Long.class, request));
            var invalidStatus = assertThrows(UncategorizedSQLException.class,
                    () -> insertRecovery(db, request, asset, actor, "RECOVERING"));
            assertEquals(3819, invalidStatus.getSQLException().getErrorCode());
            var invalidVersions = assertThrows(UncategorizedSQLException.class,
                    () -> db.update("INSERT INTO fams_scrap_recovery"
                            + " (request_id,asset_id,creator_account_id,reason,found_at,temporary_location_id,"
                            + "temporary_location_version,temporary_custodian_employee_id,asset_version,"
                            + "target_location_id,target_location_version,recovery_status,request_snapshot_json,"
                            + "created_at,updated_at) VALUES (?,?,?,'找回原卡',NOW(3),1,-1,2,0,1,0,"
                            + "'COMPLETED','{}',NOW(3),NOW(3))", request, asset, actor));
            assertEquals(3819, invalidVersions.getSQLException().getErrorCode());
            assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM fams_scrap_recovery WHERE request_id=?"
                    + " AND recovery_status='COMPLETED'", Long.class, request));
        } finally {
            if (db.queryForObject("SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema=DATABASE() AND table_name='fams_scrap_recovery'", Long.class) == 1L) {
                db.update("DELETE FROM fams_scrap_disposition WHERE request_id IN"
                        + " (SELECT id FROM fams_scrap_request WHERE asset_id=? AND creator_account_id=?)", asset, actor);
                db.update("DELETE FROM fams_scrap_recovery WHERE request_id IN"
                        + " (SELECT id FROM fams_scrap_request WHERE asset_id=? AND creator_account_id=?)", asset, actor);
                db.update("DELETE FROM fams_scrap_request WHERE asset_id=? AND creator_account_id=?", asset, actor);
            }
            new LossUpgradeRuntimeCase().removeOnlyOwnedFixtureBeforeSuccessorBusinessSuites();
        }
    }

    private java.util.Map<String, Object> insertRequest(JdbcTemplate db, long asset, long actor) {
        db.update("INSERT INTO fams_scrap_request"
                + " (asset_id,creator_account_id,source_type,source_business_id,source_snapshot_json,reason,"
                + " observed_condition,proposed_disposition,request_status,asset_version,created_at,updated_at)"
                + " VALUES (?,?,?,?,'{}','已确认遗失办理核销','保留原事实','遗失核销','EXECUTED',0,NOW(3),NOW(3))",
                asset, actor, "LOSS", 1);
        return db.queryForMap("SELECT * FROM fams_scrap_request WHERE asset_id=? AND creator_account_id=?",
                asset, actor);
    }

    private void insertRecovery(JdbcTemplate db, long request, long asset, long actor, String status) {
        db.update("INSERT INTO fams_scrap_recovery"
                + " (request_id,asset_id,creator_account_id,reason,found_at,temporary_location_id,"
                + "temporary_location_version,temporary_custodian_employee_id,asset_version,"
                + "target_location_id,target_location_version,recovery_status,request_snapshot_json,"
                + "created_at,updated_at) VALUES (?,?,?,'找回原卡',NOW(3),1,0,2,0,1,0,?,'{}',NOW(3),NOW(3))",
                request, asset, actor, status);
    }
}
