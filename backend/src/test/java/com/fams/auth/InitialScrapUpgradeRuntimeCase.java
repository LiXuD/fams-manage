package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfSystemProperty(named = "d6.23.initial.scrap.upgrade.runtime", matches = "true")
class InitialScrapUpgradeRuntimeCase {
    @Test
    void v64AddsInitialProvenanceWithoutRewritingScrapDocumentsAndBindsOneOpeningRow() {
        var source = RepairFactoryPartsBaselineRuntimeCase.source();
        var db = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration/d6_04")
                .target("63").load().migrate();
        new RepairFactoryPartsBaselineRuntimeCase().seedAtCurrentSchema(db);
        long asset = 954520L;
        long actor = 954520L;
        String batch = UUID.randomUUID().toString();
        try {
            db.update("INSERT INTO fams_asset_import_batch(id,actor_account_id,upload_key,source_sha256,"
                    + "source_bytes,source_xlsx,created_at,updated_at) VALUES (?,?,'upgrade-key',"
                    + "'0000000000000000000000000000000000000000000000000000000000000000',1,X'01',NOW(3),NOW(3))",
                    batch, actor);
            db.update("INSERT INTO fams_scrap_request"
                    + " (asset_id,creator_account_id,source_type,source_snapshot_json,reason,observed_condition,"
                    + " proposed_disposition,request_status,asset_version,created_at,updated_at) VALUES"
                    + " (?,?,?,'{}','已执行报废等待处置','保留原事实','待处置保管','EXECUTED',0,NOW(3),NOW(3))",
                    asset, actor, "STANDALONE");
            // The pre-V64 row cannot be read with SELECT *: the migration itself adds the two provenance columns.
            var columns = "id,asset_id,creator_account_id,processor_account_id,source_type,source_business_id,"
                    + "source_item_id,source_snapshot_json,reason,observed_condition,proposed_disposition,"
                    + "request_status,decision_snapshot_json,execution_snapshot_json,asset_version,"
                    + "ownership_version,version,created_at,updated_at";
            var frozen = db.queryForMap("SELECT " + columns + " FROM fams_scrap_request WHERE asset_id=?"
                    + " AND request_status='EXECUTED'", asset);
            var migration = Flyway.configure().dataSource(source)
                    .locations("classpath:db/migration/d6_04").target("64").load();
            assertEquals(1, migration.migrate().migrationsExecuted);
            assertEquals(0, migration.migrate().migrationsExecuted);
            assertEquals("64", migration.info().current().getVersion().toString());
            assertEquals(frozen, db.queryForMap("SELECT " + columns + " FROM fams_scrap_request WHERE asset_id=?"
                    + " AND request_status='EXECUTED'", asset));
            assertEquals(null, db.queryForObject("SELECT initial_batch_id FROM fams_scrap_request"
                    + " WHERE id=?", String.class, frozen.get("id")));
            assertEquals(null, db.queryForObject("SELECT initial_sheet_row FROM fams_scrap_request"
                    + " WHERE id=?", Long.class, frozen.get("id")));
            insertRequest(db, asset, actor, "CANCELLED", batch, 2);
            insertRequest(db, asset, actor, "CANCELLED", batch, 3);
            int unprovenanced = db.queryForObject("SELECT COUNT(*) FROM fams_scrap_request"
                    + " WHERE initial_batch_id IS NULL", Integer.class);
            assertThrows(DuplicateKeyException.class,
                    () -> insertRequest(db, asset, actor, "CANCELLED", batch, 2));
            assertEquals(3819, assertThrows(UncategorizedSQLException.class,
                    () -> insertRequest(db, asset, actor, "CANCELLED", batch, null))
                    .getSQLException().getErrorCode());
            assertEquals(3819, assertThrows(UncategorizedSQLException.class,
                    () -> insertRequest(db, asset, actor, "CANCELLED", null, 4))
                    .getSQLException().getErrorCode());
            var unknownBatch = assertThrows(DataIntegrityViolationException.class,
                    () -> insertRequest(db, asset, actor, "CANCELLED", UUID.randomUUID().toString(), 5));
            assertEquals(1452, ((java.sql.SQLException) unknownBatch.getRootCause()).getErrorCode());
            assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM fams_scrap_request"
                    + " WHERE initial_batch_id=?", Integer.class, batch));
            assertEquals(unprovenanced, db.queryForObject("SELECT COUNT(*) FROM fams_scrap_request"
                    + " WHERE initial_batch_id IS NULL", Integer.class));
        } finally {
            db.update("DELETE FROM fams_scrap_request WHERE creator_account_id=?", actor);
            db.update("DELETE FROM fams_asset_import_batch WHERE id=?", batch);
            new LossUpgradeRuntimeCase().removeOnlyOwnedFixtureBeforeSuccessorBusinessSuites();
        }
    }

    private void insertRequest(JdbcTemplate db, long asset, long actor, String status,
            String batch, Integer row) {
        db.update("INSERT INTO fams_scrap_request"
                + " (asset_id,creator_account_id,source_type,source_snapshot_json,reason,observed_condition,"
                + " proposed_disposition,request_status,asset_version,initial_batch_id,initial_sheet_row,"
                + " created_at,updated_at) VALUES (?,?,?,'{}','已执行报废等待处置','保留原事实','待处置保管',?,0,?,?,"
                + "NOW(3),NOW(3))", asset, actor, "STANDALONE", status, batch, row);
    }
}
