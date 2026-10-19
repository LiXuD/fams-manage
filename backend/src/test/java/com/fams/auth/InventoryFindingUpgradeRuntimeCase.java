package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A populated V36 candidate, including its original finding receipt, survives the additive link
 * table.
 */
@EnabledIfSystemProperty(named = "d6.18.inventory.finding.upgrade.runtime", matches = "true")
class InventoryFindingUpgradeRuntimeCase {
    @Test
    void versionThirtySixFactsAndColumnMetadataSurviveFindingLinksAndRepeat() {
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        var source =
                new DriverManagerDataSource(
                        settings.url(), settings.username(), settings.password());
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration/d6_04")
                .target("36")
                .load()
                .migrate();
        var db = new JdbcTemplate(source);
        db.update(
                "INSERT INTO"
                    + " fams_organization(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (944700,'FINDUP','FINDUP','Finding"
                    + " upgrade','ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_employee(id,employee_no,employee_no_normalized,name,organization_id,status,created_at,updated_at)"
                    + " VALUES (944700,'FINDUP','FINDUP','Finding"
                    + " upgrade',944700,'ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_system_account(id,employee_id,username,username_normalized,password_hash,status,created_at,updated_at)"
                    + " VALUES"
                    + " (944700,944700,'FINDUP','FINDUP','not-login','ENABLED',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_inventory_task(id,name,task_status,range_json,starts_at,due_at,creator_account_id,created_at,updated_at,version,captured_result_count)"
                    + " VALUES (944700,'Original finding"
                    + " task','IN_PROGRESS','{}','2026-01-01','2026-02-01',944700,'2026-01-01','2026-01-02',4,1)");
        db.update(
                "INSERT INTO"
                    + " fams_inventory_observation(id,task_id,client_event_id,actor_account_id,device_id,assignment_version,recheck_generation,source,"
                    + "raw_observed_at,first_persisted_at,client_expires_at,received_at,facts_json,payload_sha256,fact_sha256,outcome,receipt_json)"
                    + " VALUES"
                    + " (944700,944700,'00000000-0000-4000-8000-000000000037',944700,'00000000-0000-4000-8000-000000000038',0,0,'MANUAL','2026-01-01','2026-01-01','2026-01-04','2026-01-02',JSON_OBJECT('actual',JSON_OBJECT('description','Original"
                    + " physical"
                    + " finding'),'photoIds',JSON_ARRAY('00000000-0000-4000-8000-000000000039')),SHA2('original"
                    + " payload',256),SHA2('original facts',256),'FINDING_REVIEW',"
                    + "JSON_OBJECT('outcome','FINDING_REVIEW','counted',FALSE))");
        db.update(
                "INSERT INTO"
                    + " fams_inventory_review_subject(observation_id,dimension,task_id,recheck_generation,subject_status,version)"
                    + " VALUES (944700,'FINDING',944700,0,'OPEN',0)");
        db.update(
                "INSERT INTO"
                    + " fams_inventory_review_receipt(task_id,actor_account_id,operation,request_id,payload_sha256,response_json,created_at)"
                    + " VALUES"
                    + " (944700,944700,'PREPARE_DIFFERENCES','00000000-0000-4000-8000-000000000040',SHA2('original"
                    + " request',256),JSON_OBJECT('version',4),'2026-01-02')");
        var tables =
                java.util.List.of(
                        "fams_inventory_task",
                        "fams_inventory_observation",
                        "fams_inventory_review_subject",
                        "fams_inventory_review_receipt");
        var before = new java.util.LinkedHashMap<String, Object>();
        for (String table : tables) {
            before.put(
                    table,
                    db.queryForList(
                            "SELECT * FROM "
                                    + table
                                    + " WHERE "
                                    + (table.equals("fams_inventory_task") ? "id" : "task_id")
                                    + "=944700"));
        }
        var columns =
                db.queryForList(
                        "SELECT table_name,column_name,column_type,is_nullable,column_default,extra"
                            + " FROM information_schema.columns WHERE table_schema=DATABASE() ORDER"
                            + " BY table_name,ordinal_position");
        var oldNames = columns.stream().map(row -> row.get("TABLE_NAME")).distinct().toList();
        try {
            var next =
                    Flyway.configure()
                            .dataSource(source)
                            .locations("classpath:db/migration/d6_04")
                            .target("37")
                            .load();
            assertEquals(1, next.migrate().migrationsExecuted);
            assertEquals(0, next.migrate().migrationsExecuted);
            next.validate();
            assertEquals(
                    columns,
                    db
                            .queryForList(
                                    "SELECT"
                                        + " table_name,column_name,column_type,is_nullable,column_default,extra"
                                        + " FROM information_schema.columns WHERE"
                                        + " table_schema=DATABASE() ORDER BY"
                                        + " table_name,ordinal_position")
                            .stream()
                            .filter(row -> oldNames.contains(row.get("TABLE_NAME")))
                            .toList());
            for (String table : tables) {
                assertEquals(
                        before.get(table),
                        db.queryForList(
                                "SELECT * FROM "
                                        + table
                                        + " WHERE "
                                        + (table.equals("fams_inventory_task") ? "id" : "task_id")
                                        + "=944700"));
            }
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_inventory_finding_link", Integer.class));
        } finally {
            db.update("DELETE FROM fams_inventory_review_receipt WHERE task_id=944700");
            db.update("DELETE FROM fams_inventory_review_subject WHERE task_id=944700");
            db.update("DELETE FROM fams_inventory_observation WHERE task_id=944700");
            db.update("DELETE FROM fams_inventory_task WHERE id=944700");
            db.update("DELETE FROM fams_system_account WHERE id=944700");
            db.update("DELETE FROM fams_employee WHERE id=944700");
            db.update("DELETE FROM fams_organization WHERE id=944700");
        }
    }
}
