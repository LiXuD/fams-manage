package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfSystemProperty(named = "d6.18.inventory.upgrade.runtime", matches = "true")
class InventoryReviewUpgradeRuntimeCase {
    @Test
    void populatedVersionThirtyTwoUpgradePreservesOriginalObservations() {
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        var source =
                new DriverManagerDataSource(
                        settings.url(), settings.username(), settings.password());
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration/d6_04")
                .target("32")
                .load()
                .migrate();
        var db = new JdbcTemplate(source);
        db.update(
                "INSERT INTO"
                    + " fams_organization(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (943500,'INVUP','INVUP','Inventory"
                    + " upgrade','ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_employee(id,employee_no,employee_no_normalized,name,organization_id,status,created_at,updated_at)"
                    + " VALUES (943500,'INVUP','INVUP','Inventory"
                    + " upgrade',943500,'ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_system_account(id,employee_id,username,username_normalized,password_hash,status,created_at,updated_at)"
                    + " VALUES"
                    + " (943500,943500,'INVUP','INVUP','not-a-login-fixture','ENABLED',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_business_parameter_version(id,version,status,effective_at,actor_account_id,actor_username,snapshot_json,created_at)"
                    + " VALUES (943500,943500,'ACTIVE',NOW(3),943500,'INVUP','{}',NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_warehouse(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (943500,'INVUP','INVUP','Upgrade"
                    + " warehouse','ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_location(id,code,code_normalized,name,location_type,warehouse_id,status,created_at,updated_at)"
                    + " VALUES (943500,'INVUP','INVUP','Upgrade"
                    + " bin','BIN',943500,'ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_asset(id,asset_code,name,lifecycle_status,exception_status,card_stage,custody_type,storage_warehouse_id,current_location_id,created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by)"
                    + " VALUES (943500,'INVUP-STOCK','Historical"
                    + " stock','IN_STOCK','NORMAL','ACTIVE','STORAGE',943500,943500,'2025-01-01',943500,943500,'INVUP','2025-01-01','2025-01-01',943500,943500)");
        db.update(
                "INSERT INTO"
                    + " fams_asset(id,asset_code,name,lifecycle_status,exception_status,card_stage,created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by)"
                    + " VALUES (943501,'INVUP-DRAFT','Historical"
                    + " unpublished','WAITING_INBOUND','NORMAL','DRAFT','2025-01-01',943500,943500,'INVUP','2025-01-01','2025-01-01',943500,943500)");
        db.update(
                "INSERT INTO"
                    + " fams_inventory_task(id,name,task_status,range_json,starts_at,due_at,expected_count,creator_account_id,publisher_account_id,parameter_version_id,timezone_id,created_at,updated_at,published_at)"
                    + " VALUES (943500,'Historical frozen"
                    + " task','READY',JSON_OBJECT('categoryId',NULL),'2026-01-01','2026-02-01',1,943500,943500,943500,'Asia/Shanghai','2026-01-01','2026-01-01','2026-01-01')");
        db.update(
                "INSERT INTO"
                    + " fams_inventory_item(id,task_id,asset_id,asset_version,snapshot_json,executor_account_id,assignment_version)"
                    + " SELECT 943500,943500,asset_id,asset_version,snapshot_json,943500,1 FROM"
                    + " fams_asset_inventory_source_v1 WHERE asset_id=943500");
        db.update(
                "INSERT INTO fams_inventory_participant(task_id,account_id,first_assigned_at)"
                    + " VALUES (943500,943500,'2026-01-01')");
        db.update("UPDATE fams_inventory_task SET task_status='IN_PROGRESS',started_at='2026-01-01',"
                + "assignment_version=1,version=4,captured_result_count=1 WHERE id=943500");
        db.update("UPDATE fams_inventory_item SET result_count=1 WHERE id=943500");
        db.update("INSERT INTO fams_inventory_clock_calibration(id,actor_account_id,device_id,client_time,server_time)"
                + " VALUES ('00000000-0000-4000-8000-000000000031',943500,'00000000-0000-4000-8000-000000000032','2026-01-01','2026-01-01')");
        db.update("INSERT INTO fams_inventory_observation(id,task_id,client_event_id,actor_account_id,device_id,item_id,"
                + "assignment_version,recheck_generation,snapshot_asset_version,source,asset_code,raw_observed_at,"
                + "first_persisted_at,client_expires_at,calibrated_observed_at,calibrated_persisted_at,calibration_id,"
                + "received_at,facts_json,payload_sha256,fact_sha256,outcome,receipt_json)"
                + " VALUES (943500,943500,'00000000-0000-4000-8000-000000000033',943500,"
                + "'00000000-0000-4000-8000-000000000032',943500,1,0,0,'SCAN','INVUP-STOCK',"
                + "'2026-01-01','2026-01-01','2026-01-04','2026-01-01','2026-01-01',"
                + "'00000000-0000-4000-8000-000000000031','2026-01-02',"
                + "JSON_OBJECT('itemId',943500,'source','SCAN','actual',JSON_OBJECT('locationId',943500,'custodyType','STORAGE','condition','GOOD')),"
                + "SHA2('historical payload fixture',256),SHA2('historical facts fixture',256),'ACCEPTED',"
                + "JSON_OBJECT('eventId','00000000-0000-4000-8000-000000000033','outcome','ACCEPTED','counted',true))");
        db.update("INSERT INTO fams_inventory_round_result(item_id,recheck_generation,observation_id,fact_sha256)"
                + " VALUES (943500,0,943500,SHA2('historical facts fixture',256))");
        var observations = db.queryForList("SELECT * FROM fams_inventory_observation WHERE task_id=943500");
        var originalRounds = db.queryForList("SELECT * FROM fams_inventory_round_result WHERE item_id=943500");
        var frozenTask = db.queryForList("SELECT * FROM fams_inventory_task WHERE id=943500");
        var frozenItems = db.queryForList("SELECT * FROM fams_inventory_item WHERE task_id=943500");
        var before =
                db.queryForList("SELECT * FROM fams_asset WHERE id IN (943500,943501) ORDER BY id");
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
                            .target("33")
                            .load();
            assertEquals(1, next.migrate().migrationsExecuted);
            assertEquals(0, next.migrate().migrationsExecuted);
            next.validate();
            assertEquals(
                    before,
                    db.queryForList(
                            "SELECT * FROM fams_asset WHERE id IN (943500,943501) ORDER BY id"));
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
                                    + " (943500,943501)",
                            Integer.class));
            assertEquals(
                    "943500",
                    db.queryForObject(
                            "SELECT JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.assetId')) FROM"
                                    + " fams_asset_inventory_source_v1 WHERE asset_id=943500",
                            String.class));
            assertEquals(
                    frozenTask,
                    db.queryForList("SELECT * FROM fams_inventory_task WHERE id=943500"));
            assertEquals(
                    frozenItems,
                    db.queryForList("SELECT * FROM fams_inventory_item WHERE task_id=943500"));
            for (String table :
                    new String[] {
                        "fams_inventory_item_review",
                        "fams_inventory_review_subject",
                        "fams_inventory_review_receipt"
                    }) {
                assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
            }
            assertEquals(observations, db.queryForList("SELECT * FROM fams_inventory_observation WHERE task_id=943500"));
            assertEquals(originalRounds, db.queryForList("SELECT * FROM fams_inventory_round_result WHERE item_id=943500"));
        } finally {
            db.update("DELETE FROM fams_inventory_round_result WHERE item_id=943500");
            db.update("DELETE FROM fams_inventory_observation WHERE task_id=943500");
            db.update("DELETE FROM fams_inventory_clock_calibration WHERE actor_account_id=943500");
            db.update("DELETE FROM fams_inventory_participant WHERE task_id=943500");
            db.update("DELETE FROM fams_inventory_item WHERE task_id=943500");
            db.update("DELETE FROM fams_inventory_task WHERE id=943500");
            db.update("DELETE FROM fams_asset WHERE id IN (943500,943501)");
            db.update("DELETE FROM fams_business_parameter_version WHERE id=943500");
            db.update("DELETE FROM fams_system_account WHERE id=943500");
            db.update("DELETE FROM fams_employee WHERE id=943500");
            db.update("DELETE FROM fams_location WHERE id=943500");
            db.update("DELETE FROM fams_warehouse WHERE id=943500");
            db.update("DELETE FROM fams_organization WHERE id=943500");
        }
    }
}
