package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfSystemProperty(named = "d6.12.upgrade.runtime", matches = "true")
class HandoverUpgradeRuntimeCase {
    @Test
    void versionTwentyUpgradeRepeatAndValidationPreserveExistingRows() {
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        var source =
                new DriverManagerDataSource(
                        settings.url(), settings.username(), settings.password());
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration/d6_04")
                .target("20")
                .load()
                .migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update(
                "INSERT INTO fams_organization"
                    + " (id,code,code_normalized,name,status,version,created_at,updated_at) VALUES"
                    + " (923001,'HANDOVERUPGRADE','HANDOVERUPGRADE','Preserved"
                    + " V20','ACTIVE',9,NOW(3),NOW(3))");
        try {
            var current =
                    Flyway.configure()
                            .dataSource(source)
                            .locations("classpath:db/migration/d6_04")
                            .target("21")
                            .load();
            assertEquals(1, current.migrate().migrationsExecuted);
            assertEquals(0, current.migrate().migrationsExecuted);
            current.validate();
            assertEquals(
                    9,
                    jdbc.queryForObject(
                            "SELECT version FROM fams_organization WHERE id=923001",
                            Integer.class));
            assertEquals(
                    9,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE"
                                + " constraint_schema=DATABASE() AND table_name IN"
                                + " ('fams_requisition_handover',"
                                + "'fams_requisition_handover_event','fams_requisition_receipt_grant')",
                            Integer.class));
            assertEquals(
                    21,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",
                            Integer.class));
        } finally {
            jdbc.update("DELETE FROM fams_organization WHERE id=923001");
        }
    }
}
