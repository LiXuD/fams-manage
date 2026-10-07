package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Timestamp;
import java.time.Instant;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfSystemProperty(named = "d6.10.upgrade.runtime", matches = "true")
class MiniSessionUpgradeRuntimeCase {

    @Test
    void realV16DatabaseUpgradesToV18WithoutChangingExistingOrganization() {
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        var source = new DriverManagerDataSource(settings.url(), settings.username(), settings.password());
        var jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration/d6_04")
                .target("16").load().migrate();
        Timestamp now = Timestamp.from(Instant.parse("2026-10-05T02:00:00Z"));
        jdbc.update("""
                INSERT INTO fams_organization (id, code, code_normalized, name, status, version, created_at, updated_at)
                VALUES (910002, 'MINIUPGRADE', 'MINIUPGRADE', 'Preserved upgrade organization', 'ACTIVE', 9, ?, ?)
                """, now, now);
        assertEquals(1, Flyway.configure().dataSource(source).locations("classpath:db/migration/d6_04")
                .target("17").load().migrate().migrationsExecuted);
        Flyway current = Flyway.configure().dataSource(source).locations("classpath:db/migration/d6_04")
                .target("18").load();
        assertEquals(1, current.migrate().migrationsExecuted);
        assertEquals(0, current.migrate().migrationsExecuted);
        current.validate();
        assertEquals("Preserved upgrade organization", jdbc.queryForObject(
                "SELECT name FROM fams_organization WHERE id = 910002", String.class));
        assertEquals(9L, jdbc.queryForObject("SELECT version FROM fams_organization WHERE id = 910002", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM fams_mini_binding", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM fams_mini_session", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM fams_mini_binding_challenge", Integer.class));
        jdbc.update("DELETE FROM fams_organization WHERE id = 910002");
    }
}
