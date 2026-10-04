package com.fams.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "d6.09.upgrade.runtime", matches = "true")
class AttachmentUpgradeRuntimeCase {
    @Test
    void existingInboundSchemaUpgradesWithoutLosingMasterData() throws Exception {
        String url = System.getenv("D6_09_TEST_DATASOURCE_URL");
        String user = System.getenv("D6_09_TEST_DATASOURCE_USERNAME");
        String password = System.getenv("D6_09_TEST_DATASOURCE_PASSWORD");
        Flyway.configure().dataSource(url, user, password)
                .locations("classpath:db/migration/d6_04").target("7").load().migrate();
        try (var connection = DriverManager.getConnection(url, user, password);
                var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO fams_warehouse "
                    + "(code,code_normalized,name,status,version,created_at,updated_at) "
                    + "VALUES ('UPGRADE','UPGRADE','Existing warehouse','ACTIVE',3,NOW(),NOW())");
        }
        var flyway = Flyway.configure().dataSource(url, user, password)
                .locations("classpath:db/migration/d6_04").target("12").load();
        assertEquals(5, flyway.migrate().migrationsExecuted);
        assertEquals(0, flyway.migrate().migrationsExecuted);
        flyway.validate();
        try (var connection = DriverManager.getConnection(url, user, password);
                var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery(
                    "SELECT name,version FROM fams_warehouse WHERE code='UPGRADE'")) {
                rows.next();
                assertEquals("Existing warehouse", rows.getString(1));
                assertEquals(3, rows.getLong(2));
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE() AND table_name IN "
                    + "('fams_attachment_scope','fams_attachment','fams_attachment_upload_key',"
                    + "'fams_attachment_deletion_job','fams_attachment_cleanup_audit',"
                    + "'fams_attachment_object_guard','fams_attachment_revision')")) {
                rows.next();
                assertEquals(7, rows.getInt(1));
            }
        }
    }
}
