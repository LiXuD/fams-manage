package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfSystemProperty(named = "d6.15.proxy.upgrade.runtime", matches = "true")
class ProxyConfirmationUpgradeRuntimeCase {
    @Test
    void versionTwentyEightUpgradePreservesBusinessFactsAndRepeatDoesNothing() {
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        var source = new DriverManagerDataSource(settings.url(), settings.username(), settings.password());
        Flyway.configure().dataSource(source).locations("classpath:db/migration/d6_04").target("28").load().migrate();
        var db = new JdbcTemplate(source);
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='fams_proxy_confirmation'", Integer.class));
        db.update("INSERT INTO fams_organization (id,code,code_normalized,name,status,created_at,updated_at) VALUES (923029,'PROXYUP','PROXYUP','Upgrade fixture','ACTIVE',NOW(3),NOW(3))");
        db.update("INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,organization_id,status,created_at,updated_at) VALUES (923029,'PROXYUP','PROXYUP','Upgrade fixture',923029,'ACTIVE',NOW(3),NOW(3))");
        db.update("INSERT INTO fams_system_account (id,employee_id,username,username_normalized,password_hash,status,created_at,updated_at) VALUES (923029,923029,'PROXYUP','PROXYUP','not-a-login-fixture','ENABLED',NOW(3),NOW(3))");
        db.update("INSERT INTO fams_business_parameter_version (id,version,status,effective_at,actor_account_id,actor_username,snapshot_json,created_at) VALUES (923029,923029,'ACTIVE',NOW(3),923029,'PROXYUP','{}',NOW(3))");
        db.update("INSERT INTO fams_asset (id,asset_code,name,lifecycle_status,exception_status,card_stage,created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by) VALUES (923029,'PROXYUP','Historical asset','WAITING_INBOUND','NORMAL','DRAFT',CURRENT_DATE,923029,923029,'PROXYUP',NOW(3),NOW(3),923029,923029)");
        db.update("INSERT INTO fams_asset_return (id,asset_id,asset_version,responsible_employee_id,using_organization_id,proposed_by,reason,processor_account_id,return_status,parameter_snapshot_json,created_at,version) VALUES (923029,923029,0,923029,923029,923029,'preserved historical return',923029,'WAREHOUSE_PROCESSING','{}',NOW(3),7)");
        var before = db.queryForMap("SELECT * FROM fams_asset_return WHERE id=923029");
        try {
            var next = Flyway.configure().dataSource(source).locations("classpath:db/migration/d6_04").target("29").load();
            assertEquals(1, next.migrate().migrationsExecuted);
            assertEquals(0, next.migrate().migrationsExecuted);
            next.validate();
            var after = db.queryForMap("SELECT * FROM fams_asset_return WHERE id=923029");
            assertEquals(before, after);
            assertEquals(29, db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_proxy_confirmation", Integer.class));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_business_result_notice", Integer.class));
            String check = db.queryForObject("SELECT CHECK_CLAUSE FROM information_schema.check_constraints WHERE constraint_schema=DATABASE() AND constraint_name='ck_req_handover_event_type'", String.class);
            for (String event : new String[] {"DELIVERED", "RECEIVED", "REFUSED", "RECOVERY_REQUESTED", "RECOVERED", "CANCELLED", "PROXY_RECEIVED"}) {
                org.junit.jupiter.api.Assertions.assertTrue(check.contains(event));
            }
            // Read the actual V29 schema before the additive change; no populated target is rewritten.
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='fams_requisition_allocation' AND column_name='approved_target_json'", Integer.class));
            var oldColumns = db.queryForList("SELECT column_name,column_type,is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='fams_requisition_allocation' ORDER BY ordinal_position");
            var targetUpgrade = Flyway.configure().dataSource(source).locations("classpath:db/migration/d6_04").target("30").load();
            assertEquals(1, targetUpgrade.migrate().migrationsExecuted);
            assertEquals(0, targetUpgrade.migrate().migrationsExecuted);
            targetUpgrade.validate();
            assertEquals(before, db.queryForMap("SELECT * FROM fams_asset_return WHERE id=923029"));
            var newColumns = db.queryForList("SELECT column_name,column_type,is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='fams_requisition_allocation' ORDER BY ordinal_position");
            assertEquals(oldColumns, newColumns.subList(0, oldColumns.size()));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_item_target_change", Integer.class));
            assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='fams_requisition_allocation' AND column_name IN ('approved_target_json','receipt_target_employee_id') AND is_nullable='YES'", Integer.class));
        } finally {
            for (String table : new String[] {"fams_asset_return", "fams_asset", "fams_business_parameter_version", "fams_system_account", "fams_employee", "fams_organization"}) {
                db.update("DELETE FROM " + table + " WHERE id=923029");
            }
        }
    }
}
