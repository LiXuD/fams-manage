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

/** Forward-only expansion preserves every old row and tolerates an unchanged repeat. */
@EnabledIfSystemProperty(named = "d6.21.loss.upgrade.runtime", matches = "true")
class LossUpgradeRuntimeCase {
    @AfterEach
    void removeOnlyOwnedFixtureBeforeSuccessorBusinessSuites() {
        var db = new JdbcTemplate(RepairFactoryPartsBaselineRuntimeCase.source());
        db.update("DELETE FROM fams_repair_factory_parts_change WHERE repair_id=954520");
        new RepairFactoryCostUpgradeRuntimeCase()
                .removeOnlyOwnedUpgradeFactsBeforeSuccessorBusinessSuites();
    }

    @Test
    void populatedV55FactsSurviveLossExpansionAndRepeat() {
        var source = RepairFactoryPartsBaselineRuntimeCase.source();
        var db = new JdbcTemplate(source);
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration/d6_04")
                .target("55")
                .load()
                .migrate();
        new RepairFactoryPartsBaselineRuntimeCase().seedAtCurrentSchema(db);
        var before = facts(db);
        var migration =
                Flyway.configure()
                        .dataSource(source)
                        .locations("classpath:db/migration/d6_04")
                        .target("56")
                        .load();
        assertEquals(1, migration.migrate().migrationsExecuted);
        assertEquals(0, migration.migrate().migrationsExecuted);
        assertEquals("56", migration.info().current().getVersion().toString());
        var after = facts(db);
        for (String table : List.of("fams_loss_report", "fams_loss_event", "fams_loss_receipt")) {
            assertEquals(List.of(), after.remove(table));
        }
        assertEquals(before, after);
        System.out.println(
                "{\"ownedLossUpgrade\":true,\"version\":56,\"migrations\":1,\"repeat\":0,\"allOldColumnsUnchanged\":true,\"fixtureOnlyNotActualBusiness\":true}");
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
