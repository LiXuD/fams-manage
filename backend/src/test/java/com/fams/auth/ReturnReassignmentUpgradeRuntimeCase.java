package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfSystemProperty(named = "d6.15.return.upgrade.runtime", matches = "true")
class ReturnReassignmentUpgradeRuntimeCase {
    @Test
    void versionTwentySevenUpgradePreservesReturnFactsAndRepeatDoesNothing() {
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        var source = new DriverManagerDataSource(settings.url(), settings.username(), settings.password());
        Flyway.configure().dataSource(source).locations("classpath:db/migration/d6_04").target("27").load().migrate();
        var db = new JdbcTemplate(source);
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='fams_asset_return' AND column_name='ownership_version'", Integer.class));
        db.update("INSERT INTO fams_organization (id,code,code_normalized,name,status,created_at,updated_at) VALUES (923028,'RETURNUP','RETURNUP','Upgrade fixture','ACTIVE',NOW(3),NOW(3))");
        db.update("INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,organization_id,status,created_at,updated_at) VALUES (923028,'RETURNUP','RETURNUP','Upgrade fixture',923028,'ACTIVE',NOW(3),NOW(3))");
        db.update("INSERT INTO fams_system_account (id,employee_id,username,username_normalized,password_hash,status,created_at,updated_at) VALUES (923028,923028,'RETURNUP','RETURNUP','not-a-login-fixture','ENABLED',NOW(3),NOW(3))");
        db.update("INSERT INTO fams_business_parameter_version (id,version,status,effective_at,actor_account_id,actor_username,snapshot_json,created_at) VALUES (923028,923028,'ACTIVE',NOW(3),923028,'RETURNUP','{}',NOW(3))");
        db.update("INSERT INTO fams_asset (id,asset_code,name,lifecycle_status,exception_status,card_stage,created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by) VALUES (923028,'RETURNUP','Historical asset','WAITING_INBOUND','NORMAL','DRAFT',CURRENT_DATE,923028,923028,'RETURNUP',NOW(3),NOW(3),923028,923028)");
        db.update("INSERT INTO fams_asset_return (id,asset_id,asset_version,responsible_employee_id,using_organization_id,proposed_by,reason,processor_account_id,return_status,parameter_snapshot_json,created_at,version) VALUES (923028,923028,0,923028,923028,923028,'preserved historical return',923028,'WAREHOUSE_PROCESSING','{}',NOW(3),7)");
        var before = db.queryForMap("SELECT * FROM fams_asset_return WHERE id=923028");
        try {
            var next = Flyway.configure().dataSource(source).locations("classpath:db/migration/d6_04").target("28").load();
            assertEquals(1, next.migrate().migrationsExecuted);
            assertEquals(0, next.migrate().migrationsExecuted);
            next.validate();
            var after = db.queryForMap("SELECT * FROM fams_asset_return WHERE id=923028");
            assertEquals(0L, ((Number) after.remove("ownership_version")).longValue());
            assertEquals(before, after);
            var rejection = assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> db.update("UPDATE fams_asset_return SET ownership_version=-1 WHERE id=923028"));
            assertEquals(3819, ((java.sql.SQLException) rejection.getRootCause()).getErrorCode());
            assertEquals(28, db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class));
        } finally {
            for (String table : new String[] {"fams_asset_return", "fams_asset", "fams_business_parameter_version", "fams_system_account", "fams_employee", "fams_organization"}) {
                db.update("DELETE FROM " + table + " WHERE id=923028");
            }
        }
    }
}
