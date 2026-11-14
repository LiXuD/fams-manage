package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

/** Populated owned V54 only; no claim of actual manufacturer proof or acceptance. */
@EnabledIfSystemProperty(named = "d6.20.repair.factory.replacement.baseline", matches = "true")
class RepairFactoryReplacementBaselineRuntimeCase {
    @Test
    void observePopulatedVersionFiftyFourBeforeReplacementMigration() {
        var source = RepairFactoryPartsBaselineRuntimeCase.source();
        var migration = Flyway.configure().dataSource(source)
                .locations("classpath:db/migration/d6_04").target("54").load();
        migration.migrate();
        assertEquals("54", migration.info().current().getVersion().toString());
        var db = new JdbcTemplate(source);
        new RepairFactoryPartsBaselineRuntimeCase().seedAtCurrentSchema(db);
        if (db.queryForObject("SELECT COUNT(*) FROM fams_repair_factory_parts_change WHERE repair_id=954520", Integer.class) == 0) {
            long round = db.queryForObject("SELECT id FROM fams_repair_factory_round WHERE repair_id=954520 AND round_number=1", Long.class);
            db.update("INSERT INTO fams_repair_factory_parts_change(repair_id,round_id,asset_id,"
                            + "part_name,before_info,after_info,observed_at,evidence_ids_json,"
                            + "snapshot_json,recorded_by,recorded_at) VALUES (954520,?,954520,"
                            + "'Owned old module','Old module identity','New module identity',"
                            + "'2026-01-05','[\"00000000-0000-0000-0000-000000000054\"]',"
                            + "'{\"fixtureOnly\":true}',954520,'2026-01-05')", round);
        }
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_repair_factory_parts_change WHERE repair_id=954520", Integer.class));
        System.out.println("{\"ownedReplacementBaseline\":true,\"version\":54,\"fixtureOnlyNotActualBusiness\":true}");
    }
}
