package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfSystemProperty(named = "d6.15.upgrade.runtime", matches = "true")
class ReassignmentUpgradeRuntimeCase {
    @Test
    void versionTwentySixUpgradeAndRepeatPreserveExistingRows() {
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        var source =
                new DriverManagerDataSource(
                        settings.url(), settings.username(), settings.password());
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration/d6_04")
                .target("26")
                .load()
                .migrate();
        var db = new JdbcTemplate(source);
        db.update(
                "INSERT INTO fams_organization"
                    + " (id,code,code_normalized,name,status,version,created_at,updated_at) VALUES"
                    + " (923015,'REASSIGNUPGRADE','REASSIGNUPGRADE','Preserved"
                    + " V26','ACTIVE',7,NOW(3),NOW(3))");
        try {
            var next =
                    Flyway.configure()
                            .dataSource(source)
                            .locations("classpath:db/migration/d6_04")
                            .target("27")
                            .load();
            assertEquals(1, next.migrate().migrationsExecuted);
            assertEquals(0, next.migrate().migrationsExecuted);
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='fams_transfer' AND column_name='ownership_version' AND column_default='0'", Integer.class));
            next.validate();
            assertEquals(
                    7,
                    db.queryForObject(
                            "SELECT version FROM fams_organization WHERE id=923015",
                            Integer.class));
            assertEquals(
                    4,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                + " table_schema=DATABASE() AND table_name IN"
                                + " ('fams_transfer','fams_transfer_item','fams_transfer_event','fams_transfer_receipt_grant')",
                            Integer.class));
            assertEquals(
                    27,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",
                            Integer.class));
        } finally {
            db.update("DELETE FROM fams_organization WHERE id=923015");
        }
    }
}
