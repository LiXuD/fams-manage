package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Previous schema facts and all old columns remain untouched by the parts expansion. */
@EnabledIfSystemProperty(named = "d6.20.repair.factory.parts.upgrade.runtime", matches = "true")
class RepairFactoryPartsUpgradeRuntimeCase {
    @AfterEach
    void removeOnlyOwnedFixtureForSuccessorBusinessSuites() {
        new RepairFactoryCostUpgradeRuntimeCase()
                .removeOnlyOwnedUpgradeFactsBeforeSuccessorBusinessSuites();
    }

    @Test
    void populatedV53FactsSurvivePartsExpansionAndRepeat() {
        new RepairFactoryPartsBaselineRuntimeCase()
                .observePopulatedVersionFiftyThreeBeforePartsMigration();
        var source = RepairFactoryPartsBaselineRuntimeCase.source();
        var db = new JdbcTemplate(source);
        var before = facts(db);
        var migration =
                Flyway.configure()
                        .dataSource(source)
                        .locations("classpath:db/migration/d6_04")
                        .target("54")
                        .load();
        assertEquals(1, migration.migrate().migrationsExecuted);
        assertEquals(0, migration.migrate().migrationsExecuted);
        assertEquals("54", migration.info().current().getVersion().toString());
        var after = facts(db);
        after.remove("fams_repair_factory_parts_change");
        assertEquals(before, after);
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_factory_parts_change", Integer.class));
        System.out.println(
                "{\"ownedPartsUpgrade\":true,\"version\":54,\"migrations\":1,\"repeat\":0,\"allOldColumnsUnchanged\":true,\"fixtureOnlyNotActualBusiness\":true}");
    }

    private Map<String, List<String>> facts(JdbcTemplate db) {
        var result = new LinkedHashMap<String, List<String>>();
        var json = new ObjectMapper();
        for (String table :
                db.queryForList(
                        "SELECT TABLE_NAME FROM information_schema.tables WHERE"
                            + " table_schema=DATABASE() AND table_name<>'flyway_schema_history'"
                            + " ORDER BY TABLE_NAME",
                        String.class)) {
            if (!table.matches("[a-zA-Z0-9_]+")) {
                throw new IllegalArgumentException("Unexpected owned table name");
            }
            result.put(
                    table,
                    db.queryForList("SELECT * FROM " + table).stream()
                            .map(json::writeValueAsString)
                            .sorted()
                            .toList());
        }
        return result;
    }
}
