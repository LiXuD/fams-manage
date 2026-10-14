package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfSystemProperty(named = "d6.16.inventory.upgrade.runtime", matches = "true")
class InventoryUpgradeRuntimeCase {
    @Test
    void populatedVersionThirtyUpgradeIsAdditiveRepeatableAndExcludesUnpublishedAssets() {
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        var source =
                new DriverManagerDataSource(
                        settings.url(), settings.username(), settings.password());
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration/d6_04")
                .target("30")
                .load()
                .migrate();
        var db = new JdbcTemplate(source);
        db.update(
                "INSERT INTO"
                    + " fams_organization(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (933500,'INVUP','INVUP','Inventory"
                    + " upgrade','ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_employee(id,employee_no,employee_no_normalized,name,organization_id,status,created_at,updated_at)"
                    + " VALUES (933500,'INVUP','INVUP','Inventory"
                    + " upgrade',933500,'ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_system_account(id,employee_id,username,username_normalized,password_hash,status,created_at,updated_at)"
                    + " VALUES"
                    + " (933500,933500,'INVUP','INVUP','not-a-login-fixture','ENABLED',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_business_parameter_version(id,version,status,effective_at,actor_account_id,actor_username,snapshot_json,created_at)"
                    + " VALUES (933500,933500,'ACTIVE',NOW(3),933500,'INVUP','{}',NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_warehouse(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (933500,'INVUP','INVUP','Upgrade"
                    + " warehouse','ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_location(id,code,code_normalized,name,location_type,warehouse_id,status,created_at,updated_at)"
                    + " VALUES (933500,'INVUP','INVUP','Upgrade"
                    + " bin','BIN',933500,'ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_asset(id,asset_code,name,lifecycle_status,exception_status,card_stage,custody_type,storage_warehouse_id,current_location_id,created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by)"
                    + " VALUES (933500,'INVUP-STOCK','Historical"
                    + " stock','IN_STOCK','NORMAL','ACTIVE','STORAGE',933500,933500,'2025-01-01',933500,933500,'INVUP','2025-01-01','2025-01-01',933500,933500)");
        db.update(
                "INSERT INTO"
                    + " fams_asset(id,asset_code,name,lifecycle_status,exception_status,card_stage,created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by)"
                    + " VALUES (933501,'INVUP-DRAFT','Historical"
                    + " unpublished','WAITING_INBOUND','NORMAL','DRAFT','2025-01-01',933500,933500,'INVUP','2025-01-01','2025-01-01',933500,933500)");
        var before =
                db.queryForList("SELECT * FROM fams_asset WHERE id IN (933500,933501) ORDER BY id");
        var columns =
                db.queryForList(
                        "SELECT table_name,column_name,column_type,is_nullable,column_default,extra"
                            + " FROM information_schema.columns WHERE table_schema=DATABASE() ORDER"
                            + " BY table_name,ordinal_position");
        try {
            var next =
                    Flyway.configure()
                            .dataSource(source)
                            .locations("classpath:db/migration/d6_04")
                            .target("31")
                            .load();
            assertEquals(1, next.migrate().migrationsExecuted);
            assertEquals(0, next.migrate().migrationsExecuted);
            next.validate();
            assertEquals(
                    before,
                    db.queryForList(
                            "SELECT * FROM fams_asset WHERE id IN (933500,933501) ORDER BY id"));
            var names = columns.stream().map(row -> row.get("TABLE_NAME")).distinct().toList();
            var after =
                    db
                            .queryForList(
                                    "SELECT"
                                        + " table_name,column_name,column_type,is_nullable,column_default,extra"
                                        + " FROM information_schema.columns WHERE"
                                        + " table_schema=DATABASE() ORDER BY"
                                        + " table_name,ordinal_position")
                            .stream()
                            .filter(row -> names.contains(row.get("TABLE_NAME")))
                            .toList();
            assertEquals(columns, after);
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_asset_inventory_source_v1 WHERE asset_id IN"
                                + " (933500,933501)",
                            Integer.class));
            assertEquals(
                    "933500",
                    db.queryForObject(
                            "SELECT JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.assetId')) FROM"
                                + " fams_asset_inventory_source_v1 WHERE asset_id=933500",
                            String.class));
            for (String table :
                    new String[] {
                        "fams_inventory_task",
                        "fams_inventory_item",
                        "fams_inventory_assignment",
                        "fams_inventory_participant",
                        "fams_inventory_task_event"
                    }) {
                assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
            }
        } finally {
            db.update("DELETE FROM fams_asset WHERE id IN (933500,933501)");
            db.update("DELETE FROM fams_business_parameter_version WHERE id=933500");
            db.update("DELETE FROM fams_system_account WHERE id=933500");
            db.update("DELETE FROM fams_employee WHERE id=933500");
            db.update("DELETE FROM fams_location WHERE id=933500");
            db.update("DELETE FROM fams_warehouse WHERE id=933500");
            db.update("DELETE FROM fams_organization WHERE id=933500");
        }
    }
}
