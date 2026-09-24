package com.fams.masterdata;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "d6.05.upgrade.runtime", matches = "true")
class FlywayUpgradeRuntimeCase {

    @Test
    void existingV3HistoryAndDataUpgradeToV6() throws Exception {
        String url = System.getenv("D6_05_TEST_DATASOURCE_URL");
        String username = System.getenv("D6_05_TEST_DATASOURCE_USERNAME");
        String password = System.getenv("D6_05_TEST_DATASOURCE_PASSWORD");
        Flyway.configure().dataSource(url, username, password)
                .locations("classpath:db/migration/d6_04").target("3").load().migrate();

        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO fams_warehouse "
                    + "(code, code_normalized, name, status, version, created_at, updated_at) "
                    + "VALUES ('EXISTING', 'EXISTING', 'Existing warehouse', 'ACTIVE', 0, "
                    + "'2026-09-23 01:00:00', '2026-09-23 01:00:00')");
            assertEquals(3, count(statement, "SELECT COUNT(*) FROM flyway_schema_history "
                    + "WHERE success = 1"));
        }

        Flyway.configure().dataSource(url, username, password)
                .locations("classpath:db/migration/d6_04").load().migrate();

        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            assertEquals(6, count(statement, "SELECT COUNT(*) FROM flyway_schema_history "
                    + "WHERE success = 1"));
            assertEquals(1, count(statement, "SELECT COUNT(*) FROM fams_warehouse "
                    + "WHERE code = 'EXISTING'"));
            assertEquals(3, count(statement, "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND table_name IN "
                    + "('fams_asset', 'fams_inbound_receipt', 'fams_inbound_receipt_item')"));
            assertEquals(7, count(statement, "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND table_name IN "
                    + "('fams_asset_business_lock', 'fams_asset_tag', 'fams_asset_tag_history', "
                    + "'fams_asset_timeline_event', 'fams_asset_saved_filter', "
                    + "'fams_asset_tag_print_job', 'fams_asset_tag_print_item')"));
        }
    }

    private int count(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
