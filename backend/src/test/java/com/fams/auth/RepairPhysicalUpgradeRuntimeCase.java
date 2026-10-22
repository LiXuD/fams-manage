package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** A populated V44 database retains repair opinions and frozen inventory facts across V45. */
@EnabledIfSystemProperty(named = "d6.19.repair.physical.upgrade.runtime", matches = "true")
class RepairPhysicalUpgradeRuntimeCase {
    @Test
    void versionFortyFourTechnicalFactsSurvivePhysicalRepairAndRepeat() {
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        var source =
                new DriverManagerDataSource(
                        settings.url(), settings.username(), settings.password());
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration/d6_04")
                .target("44")
                .load()
                .migrate();
        var db = new JdbcTemplate(source);
        db.update(
                "INSERT INTO"
                    + " fams_organization(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (950800,'READYUP','READYUP','Finding"
                    + " upgrade','ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_employee(id,employee_no,employee_no_normalized,name,organization_id,status,created_at,updated_at)"
                    + " VALUES (950800,'READYUP','READYUP','Finding"
                    + " upgrade',950800,'ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_system_account(id,employee_id,username,username_normalized,password_hash,status,created_at,updated_at)"
                    + " VALUES"
                    + " (950800,950800,'READYUP','READYUP','not-login','ENABLED',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_inventory_task(id,name,task_status,range_json,starts_at,due_at,creator_account_id,created_at,updated_at,version,captured_result_count)"
                    + " VALUES (950800,'Original finding"
                    + " task','IN_PROGRESS','{}','2026-01-01','2026-02-01',950800,'2026-01-01','2026-01-02',4,1)");
        db.update(
                "INSERT INTO"
                    + " fams_inventory_observation(id,task_id,client_event_id,actor_account_id,device_id,assignment_version,recheck_generation,source,"
                    + "raw_observed_at,first_persisted_at,client_expires_at,received_at,facts_json,payload_sha256,fact_sha256,outcome,receipt_json)"
                    + " VALUES"
                    + " (950800,950800,'00000000-0000-4000-8000-000000000037',950800,'00000000-0000-4000-8000-000000000038',0,0,'MANUAL','2026-01-01','2026-01-01','2026-01-04','2026-01-02',JSON_OBJECT('actual',JSON_OBJECT('description','Original"
                    + " physical"
                    + " finding'),'photoIds',JSON_ARRAY('00000000-0000-4000-8000-000000000039')),SHA2('original"
                    + " payload',256),SHA2('original facts',256),'FINDING_REVIEW',"
                    + "JSON_OBJECT('outcome','FINDING_REVIEW','counted',FALSE))");
        db.update(
                "INSERT INTO"
                    + " fams_inventory_review_subject(observation_id,dimension,task_id,recheck_generation,subject_status,version)"
                    + " VALUES (950800,'FINDING',950800,0,'OPEN',0)");
        db.update(
                "INSERT INTO"
                    + " fams_inventory_review_receipt(task_id,actor_account_id,operation,request_id,payload_sha256,response_json,created_at)"
                    + " VALUES"
                    + " (950800,950800,'PREPARE_DIFFERENCES','00000000-0000-4000-8000-000000000040',SHA2('original"
                    + " request',256),JSON_OBJECT('version',4),'2026-01-02')");
        db.update(
                "INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES"
                        + " (950800,SHA2('readiness-upgrade-binding',256),'2026-01-01')");
        long binding =
                java.util.Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT id FROM fams_mini_binding WHERE account_id=950800 AND"
                                        + " revoked_at IS NULL",
                                Long.class));
        db.update(
                "INSERT INTO"
                    + " fams_inventory_sync_declaration(task_id,actor_account_id,binding_id,binding_version,device_id,assignment_version,observation_watermark,queue_digest,unresolved_count,declared_at)"
                    + " VALUES"
                    + " (950800,950800,?,0,'00000000-0000-4000-8000-000000000041',0,0,SHA2('original"
                    + " queue',256),0,'2026-01-02')",
                binding);
        db.update(
                "INSERT INTO"
                    + " fams_inventory_sync_receipt(task_id,request_id,actor_account_id,binding_id,payload_sha256,response_json,created_at)"
                    + " VALUES"
                    + " (950800,'00000000-0000-4000-8000-000000000042',950800,?,SHA2('original"
                    + " declaration',256),JSON_OBJECT('taskVersion',4),'2026-01-02')",
                binding);
        db.update(
                "INSERT INTO"
                    + " fams_asset_category(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (950800,'SEALUP','SEALUP','Seal upgrade','ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_warehouse(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (950800,'SEALUP','SEALUP','Seal upgrade','ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_location(id,code,code_normalized,name,location_type,warehouse_id,status,created_at,updated_at)"
                    + " VALUES (950800,'SEALUP','SEALUP','Seal"
                    + " upgrade','BIN',950800,'ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_business_parameter_version(id,version,status,effective_at,actor_account_id,actor_username,snapshot_json,created_at)"
                    + " VALUES"
                    + " (950800,950800,'ACTIVE',NOW(3),950800,'READYUP',JSON_OBJECT('timezone','Asia/Shanghai'),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_asset(id,asset_code,name,category_id,lifecycle_status,exception_status,card_stage,custody_type,storage_warehouse_id,current_location_id,created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by)"
                    + " VALUES (950810,'SEAL-UP','Seal"
                    + " upgrade',950800,'IN_STOCK','NORMAL','ACTIVE','STORAGE',950800,950800,CURRENT_DATE,950800,950800,'READYUP',NOW(3),NOW(3),950800,950800)");
        db.update(
                "INSERT INTO fams_inventory_item(id,task_id,asset_id,asset_version,snapshot_json)"
                        + " SELECT 950810,950800,asset_id,asset_version,snapshot_json FROM"
                        + " fams_asset_inventory_source_v1 WHERE asset_id=950810");
        db.update(
                "INSERT INTO"
                    + " fams_inventory_item_review(item_id,recheck_generation,task_id,observation_id,review_status,verified_observed_at,reviewed_asset_version,reason,evidence,reviewer_account_id,reviewer_username,reviewed_at,version)"
                    + " VALUES (950810,0,950800,NULL,'NOT_OBSERVED',NULL,0,'Original"
                    + " reason','Original durable unobserved"
                    + " evidence',950800,'READYUP','2026-01-02',1)");
        db.update(
                "INSERT INTO"
                    + " fams_inventory_unobserved_reason(task_id,item_id,recheck_generation,reason_kind,reason,evidence,reviewed_asset_version,actor_account_id,actor_username,recorded_at)"
                    + " VALUES (950800,950810,0,'UNABLE_TO_OBSERVE','Original reason','Original"
                    + " durable unobserved evidence',0,950800,'READYUP','2026-01-02')");
        db.update(
                "INSERT INTO"
                    + " fams_inventory_sealed_report(task_id,task_version,timezone_id,expected_count,accepted_count,not_observed_count,captured_count,actor_account_id,actor_username,seal_reason,sync_digest,report_sha256,snapshot_json,created_at)"
                    + " VALUES (950800,4,'Asia/Shanghai',1,0,1,1,950800,'READYUP','Original"
                    + " immutable report',SHA2('original sync',256),SHA2('original"
                    + " report',256),'{\"originalFrozen\":true}', '2026-01-02')");
        db.update(
                "INSERT INTO"
                    + " fams_inventory_sealed_item(task_id,item_id,asset_id,recheck_generation,snapshot_json,snapshot_sha256)"
                    + " VALUES (950800,950810,950810,0,'{\"originalItem\":true}',SHA2('original"
                    + " item',256))");
        db.update(
                "INSERT INTO"
                    + " fams_inventory_sealed_evidence(task_id,observation_id,snapshot_json,snapshot_sha256)"
                    + " VALUES (950800,950800,'{\"originalEvidence\":true}',SHA2('original"
                    + " evidence',256))");
        db.update(
                "INSERT INTO"
                    + " fams_inventory_sealed_sync(task_id,account_id,snapshot_json,snapshot_sha256)"
                    + " VALUES (950800,950800,'{\"originalRoster\":true}',SHA2('original"
                    + " roster',256))");
        db.update(
                "INSERT INTO"
                    + " fams_inventory_late_case(observation_id,task_id,comparison_kind,needs_review,snapshot_json,snapshot_sha256,created_at)"
                    + " VALUES"
                    + " (950800,950800,'FINDING',TRUE,'{\"originalLate\":true}',SHA2('original"
                    + " late',256),'2026-01-02')");
        db.update(
                "INSERT INTO"
                    + " fams_repair_request(id,asset_id,creator_account_id,asset_version,source_snapshot_json,timezone_id,fault,impact,occurred_at,repair_status,allowed_method,approval_snapshot_json,version,created_at,updated_at,completed_at)"
                    + " VALUES"
                    + " (950800,950810,950800,0,'{\"assetCode\":\"SEAL-UP\",\"lifecycle\":\"IN_STOCK\"}','Asia/Shanghai','Original"
                    + " repair"
                    + " fault','MEDIUM','2026-01-01','COMPLETED','INTERNAL_NO_COST','{\"originalApproval\":true}',4,'2026-01-01','2026-01-02','2026-01-02')");
        db.update(
                "INSERT INTO"
                    + " fams_repair_decision(repair_id,before_version,after_version,result,allowed_method,snapshot_json,reason,actor_account_id,recorded_at)"
                    + " VALUES"
                    + " (950800,0,1,'APPROVE','INTERNAL_NO_COST','{\"originalApproval\":true}','Original"
                    + " approved repair',950800,'2026-01-01')");
        db.update(
                "INSERT INTO"
                    + " fams_repair_event(repair_id,action,before_version,after_version,actor_account_id,actor_username,actor_role,detail_json,occurred_at)"
                    + " VALUES"
                    + " (950800,'REPAIR_COMPLETED_IN_PLACE',3,4,950800,'READYUP','WAREHOUSE_OPERATOR','{\"originalCompletion\":true}','2026-01-02')");
        db.update(
                "INSERT INTO"
                    + " fams_repair_receipt(actor_account_id,operation,request_id,repair_id,payload_sha256,response_json,created_at)"
                    + " VALUES"
                    + " (950800,'COMPLETE:950800','00000000-0000-4000-8000-000000000044',950800,SHA2('original"
                    + " repair receipt',256),'{\"originalReceipt\":true}','2026-01-02')");
        db.update(
                "INSERT INTO"
                    + " fams_repair_invitation(id,repair_id,technician_account_id,inviter_account_id,invitation_status,reason,version,invited_at,ended_at,end_reason)"
                    + " VALUES (950800,950800,950800,950800,'CLOSED','Original scoped"
                    + " invitation',1,'2026-01-01','2026-01-02','REPAIR_TERMINAL')");
        db.update(
                "INSERT INTO"
                    + " fams_repair_opinion(id,invitation_id,repair_id,before_version,judgement,method,opinion_result,occurred_at,attachment_ids_json,actor_account_id,actor_username,recorded_at)"
                    + " VALUES (950800,950800,950800,0,'Original technical judgement','Original"
                    + " actual"
                    + " method','RESTORED','2026-01-01','[]',950800,'READYUP','2026-01-01')");
        var repairBefore = new java.util.LinkedHashMap<String, Object>();
        for (String table :
                java.util.List.of(
                        "fams_repair_request",
                        "fams_repair_invitation",
                        "fams_repair_opinion",
                        "fams_repair_decision",
                        "fams_repair_event",
                        "fams_repair_receipt")) {
            repairBefore.put(
                    table,
                    db.queryForList(
                            "SELECT * FROM "
                                    + table
                                    + " WHERE "
                                    + (table.equals("fams_repair_request") ? "id" : "repair_id")
                                    + "=950800"));
        }
        var tables =
                java.util.List.of(
                        "fams_inventory_sealed_report",
                        "fams_inventory_sealed_item",
                        "fams_inventory_sealed_evidence",
                        "fams_inventory_sealed_sync",
                        "fams_inventory_task",
                        "fams_inventory_item_review",
                        "fams_inventory_unobserved_reason",
                        "fams_inventory_observation",
                        "fams_inventory_review_subject",
                        "fams_inventory_review_receipt",
                        "fams_inventory_sync_declaration",
                        "fams_inventory_sync_receipt",
                        "fams_inventory_late_case");
        var before = new java.util.LinkedHashMap<String, Object>();
        for (String table : tables) {
            before.put(
                    table,
                    db.queryForList(
                            "SELECT * FROM "
                                    + table
                                    + " WHERE "
                                    + (table.equals("fams_inventory_task") ? "id" : "task_id")
                                    + "=950800"));
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
                            .target("45")
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
                                        + "=950800"));
            }
            assertEquals(
                    1,
                    db.queryForObject("SELECT COUNT(*) FROM fams_repair_request", Integer.class));
            for (var entry : repairBefore.entrySet()) {
                assertEquals(
                        entry.getValue(),
                        db.queryForList(
                                "SELECT * FROM "
                                        + entry.getKey()
                                        + " WHERE "
                                        + (entry.getKey().equals("fams_repair_request")
                                                ? "id"
                                                : "repair_id")
                                        + "=950800"));
            }
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_repair_physical_handling", Integer.class));
        } finally {
            db.update("DELETE FROM fams_repair_opinion WHERE repair_id=950800");
            db.update("DELETE FROM fams_repair_invitation WHERE repair_id=950800");
            db.update("DELETE FROM fams_repair_receipt WHERE repair_id=950800");
            db.update("DELETE FROM fams_repair_event WHERE repair_id=950800");
            db.update("DELETE FROM fams_repair_decision WHERE repair_id=950800");
            db.update("DELETE FROM fams_repair_request WHERE id=950800");
            db.update("DELETE FROM fams_inventory_late_case WHERE task_id=950800");
            db.update("DELETE FROM fams_inventory_sealed_item WHERE task_id=950800");
            db.update("DELETE FROM fams_inventory_sealed_evidence WHERE task_id=950800");
            db.update("DELETE FROM fams_inventory_sealed_sync WHERE task_id=950800");
            db.update("DELETE FROM fams_inventory_sealed_report WHERE task_id=950800");
            db.update("DELETE FROM fams_inventory_sync_receipt WHERE task_id=950800");
            db.update("DELETE FROM fams_inventory_sync_declaration WHERE task_id=950800");
            db.update("DELETE FROM fams_mini_binding WHERE account_id=950800");
            db.update("DELETE FROM fams_inventory_review_receipt WHERE task_id=950800");
            db.update("DELETE FROM fams_inventory_review_subject WHERE task_id=950800");
            db.update("DELETE FROM fams_inventory_observation WHERE task_id=950800");
            db.update("DELETE FROM fams_inventory_unobserved_reason WHERE task_id=950800");
            db.update("DELETE FROM fams_inventory_item_review WHERE task_id=950800");
            db.update("DELETE FROM fams_inventory_item WHERE task_id=950800");
            db.update("DELETE FROM fams_asset WHERE id=950810");
            db.update("DELETE FROM fams_business_parameter_version WHERE id=950800");
            db.update("DELETE FROM fams_location WHERE id=950800");
            db.update("DELETE FROM fams_warehouse WHERE id=950800");
            db.update("DELETE FROM fams_asset_category WHERE id=950800");
            db.update("DELETE FROM fams_inventory_task WHERE id=950800");
            db.update("DELETE FROM fams_system_account WHERE id=950800");
            db.update("DELETE FROM fams_employee WHERE id=950800");
            db.update("DELETE FROM fams_organization WHERE id=950800");
        }
    }
}
