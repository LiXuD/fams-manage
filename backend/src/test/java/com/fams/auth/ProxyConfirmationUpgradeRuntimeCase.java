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
            seedVersionTwentyNineAllocation(db);
            var historicalAllocation = historicalAllocationRows(db);
            var oldColumns = db.queryForList("SELECT column_name,column_type,is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='fams_requisition_allocation' ORDER BY ordinal_position");
            var targetUpgrade = Flyway.configure().dataSource(source).locations("classpath:db/migration/d6_04").target("30").load();
            assertEquals(1, targetUpgrade.migrate().migrationsExecuted);
            assertEquals(0, targetUpgrade.migrate().migrationsExecuted);
            targetUpgrade.validate();
            assertEquals(before, db.queryForMap("SELECT * FROM fams_asset_return WHERE id=923029"));
            var newColumns = db.queryForList("SELECT column_name,column_type,is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='fams_requisition_allocation' ORDER BY ordinal_position");
            assertEquals(oldColumns, newColumns.subList(0, oldColumns.size()));
            assertEquals(historicalAllocation, historicalAllocationRows(db));
            assertEquals(null, db.queryForObject("SELECT approved_target_json FROM fams_requisition_allocation WHERE id=923029", String.class));
            assertEquals(null, db.queryForObject("SELECT receipt_target_employee_id FROM fams_requisition_allocation WHERE id=923029", Long.class));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM fams_item_target_change", Integer.class));
            assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='fams_requisition_allocation' AND column_name IN ('approved_target_json','receipt_target_employee_id') AND is_nullable='YES'", Integer.class));
        } finally {
            db.update("DELETE FROM fams_requisition_receipt_grant WHERE allocation_id=923029");
            db.update("DELETE FROM fams_requisition_handover_event WHERE id=923029");
            db.update("DELETE FROM fams_requisition_handover WHERE allocation_id=923029");
            for (String table : new String[] {"fams_requisition_allocation", "fams_requisition_decision", "fams_requisition_ownership", "fams_requisition_demand", "fams_requisition", "fams_asset_business_lock", "fams_asset_return"}) {
                db.update("DELETE FROM " + table + " WHERE id=923029");
            }
            db.update("DELETE FROM fams_asset WHERE id IN (923029,923030)");
            db.update("DELETE FROM fams_audit_event WHERE id IN (923029,923030,923031)");
            db.update("DELETE FROM fams_business_parameter_version WHERE id=923029");
            db.update("DELETE FROM fams_account_role WHERE account_id=923029");
            for (String table : new String[] {"fams_system_account", "fams_employee"}) { db.update("DELETE FROM " + table + " WHERE id=923029"); }
            db.update("DELETE FROM fams_location WHERE id IN (923029,923030)");
            for (String table : new String[] {"fams_warehouse", "fams_asset_category", "fams_organization"}) { db.update("DELETE FROM " + table + " WHERE id=923029"); }
        }
    }
    private void seedVersionTwentyNineAllocation(JdbcTemplate db) {
        db.update("INSERT INTO fams_asset_category (id,code,code_normalized,name,status,created_at,updated_at) VALUES (923029,'ALLOCUP','ALLOCUP','Upgrade category','ACTIVE',NOW(3),NOW(3))");
        db.update("INSERT INTO fams_warehouse (id,code,code_normalized,name,status,created_at,updated_at) VALUES (923029,'ALLOCUP','ALLOCUP','Upgrade warehouse','ACTIVE',NOW(3),NOW(3))");
        db.update("INSERT INTO fams_location (id,code,code_normalized,name,location_type,warehouse_id,status,created_at,updated_at) VALUES (923029,'ALLOCROOM','ALLOCROOM','Historical target room','ROOM',NULL,'ACTIVE',NOW(3),NOW(3)),(923030,'ALLOCBIN','ALLOCBIN','Historical stock bin','BIN',923029,'ACTIVE',NOW(3),NOW(3))");
        db.update("INSERT INTO fams_account_role (account_id,role_code,granted_at) VALUES (923029,'WAREHOUSE_OPERATOR',NOW(3)),(923029,'ASSET_ADMIN',NOW(3))");
        db.update("INSERT INTO fams_asset (id,asset_code,name,category_id,lifecycle_status,exception_status,card_stage,custody_type,storage_warehouse_id,current_location_id,created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by) VALUES (923030,'ALLOCUP-ASSET','Historical allocated asset',923029,'IN_STOCK','NORMAL','ACTIVE','STORAGE',923029,923030,'2025-01-01',923029,923029,'PROXYUP',NOW(3),NOW(3),923029,923029)");
        db.update("INSERT INTO fams_requisition (id,request_no,creator_account_id,creator_username_snapshot,request_status,request_version,parameter_version_id,parameter_snapshot_json,submitted_at,version,created_at,updated_at) VALUES (923029,'ALLOCUP-REQUEST',923029,'PROXYUP','WAITING_HANDOVER',2,923029,'{\"timezone\":\"Asia/Shanghai\",\"handoverAcceptanceDeadlineNaturalDays\":3}','2025-01-01',8,'2025-01-01','2025-01-01')");
        String target = "{\"employee\":{\"id\":\"923029\",\"name\":\"Upgrade fixture\",\"version\":0},\"organization\":{\"id\":\"923029\",\"name\":\"Upgrade fixture\",\"version\":0},\"location\":{\"id\":\"923029\",\"name\":\"Historical target room\",\"version\":0}}";
        db.update("INSERT INTO fams_requisition_demand (id,request_id,line_no,category_id,category_version_snapshot,category_name_snapshot,specification,purpose,expected_date,custody_type,target_organization_id,organization_version_snapshot,target_employee_id,employee_version_snapshot,target_location_id,location_version_snapshot,target_snapshot_json,requested_quantity,pending_allocation_quantity,pending_handover_quantity,version) VALUES (923029,923029,1,923029,0,'Upgrade category','Historical specification','Historical purpose','2025-01-01','PERSONAL',923029,0,923029,0,923029,0,?,1,0,1,4)", target);
        db.update("INSERT INTO fams_requisition_ownership (id,request_id,processor_account_id,ownership_version,acquired_at) VALUES (923029,923029,923029,3,'2025-01-01')");
        db.update("INSERT INTO fams_audit_event (id,request_id,actor_account_id,actor_role,action,resource_type,resource_id,before_version,after_version,occurred_at,detail_json) VALUES (923029,'00000000-0000-0000-0000-000000923029',923029,'ASSET_ADMIN','REQUISITION_APPROVED','REQUISITION',923029,1,2,'2025-01-01','{}'),(923030,'00000000-0000-0000-0000-000000923030',923029,'WAREHOUSE_OPERATOR','REQUISITION_ALLOCATED','REQUISITION',923029,6,7,'2025-01-01','{}'),(923031,'00000000-0000-0000-0000-000000923031',923029,'WAREHOUSE_OPERATOR','HANDOVER_DELIVERED','REQUISITION',923029,7,8,'2025-01-01','{}')");
        db.update("INSERT INTO fams_requisition_decision (id,request_id,request_version,decision_result,approved_scope_json,decided_by_account_id,decided_at,audit_event_id) VALUES (923029,923029,2,'APPROVED',?,923029,'2025-01-01',923029)", target);
        db.update("INSERT INTO fams_asset_business_lock (id,asset_id,business_type,business_id,business_item_id,lock_version,acquired_at) VALUES (923029,923030,'REQUISITION',923029,923029,3,'2025-01-01')");
        db.update("INSERT INTO fams_requisition_allocation (id,request_id,demand_id,ownership_id,asset_id,asset_version_snapshot,asset_snapshot_json,business_lock_id,allocation_status,version,allocated_at,delivered_at,audit_event_id) VALUES (923029,923029,923029,923029,923030,0,'{\"assetId\":\"923030\",\"assetCode\":\"ALLOCUP-ASSET\",\"name\":\"Historical allocated asset\",\"assetVersion\":0}',923029,'DELIVERED_WAITING_RECEIPT',1,'2025-01-01','2025-01-01',923030)");
        db.update("INSERT INTO fams_requisition_handover (allocation_id,delivered_by_account_id,delivered_at,receipt_deadline_at,destination_text,temporary_employee_id,delivery_evidence,target_snapshot_json,formal_custody_snapshot_json) VALUES (923029,923029,'2025-01-01','2025-01-04','Historical delivery room',923029,'Historical dispatch evidence',?,'{\"lifecycleStatus\":\"IN_STOCK\",\"custodyType\":\"STORAGE\",\"storageWarehouseId\":\"923029\",\"currentLocationId\":\"923030\"}')", target);
        db.update("INSERT INTO fams_requisition_handover_event (id,allocation_id,event_type,actor_account_id,occurred_at,observed_asset_code,evidence_text,allocation_version_before,allocation_version_after,audit_event_id) VALUES (923029,923029,'DELIVERED',923029,'2025-01-01','ALLOCUP-ASSET','Historical dispatch evidence',0,1,923031)");
        db.update("INSERT INTO fams_requisition_receipt_grant (token_hash,allocation_id,receiver_account_id,receiver_employee_id,auth_epoch,authorization_version,request_version,allocation_version,issued_at,expires_at) VALUES (?,923029,923029,923029,0,0,2,1,'2025-01-01','2025-01-04')", "a".repeat(64));
    }

    private java.util.Map<String, Object> historicalAllocationRows(JdbcTemplate db) {
        var result = new java.util.LinkedHashMap<String, Object>();
        for (String table : new String[] {"fams_requisition", "fams_requisition_demand", "fams_requisition_ownership", "fams_requisition_decision", "fams_asset_business_lock", "fams_requisition_handover_event"}) {
            result.put(table, db.queryForMap("SELECT * FROM " + table + " WHERE id=923029"));
        }
        var allocation = new java.util.LinkedHashMap<>(db.queryForMap("SELECT * FROM fams_requisition_allocation WHERE id=923029"));
        allocation.remove("approved_target_json");
        allocation.remove("receipt_target_employee_id");
        result.put("allocation", allocation);
        result.put("asset", db.queryForMap("SELECT * FROM fams_asset WHERE id=923030"));
        result.put("handover", db.queryForMap("SELECT * FROM fams_requisition_handover WHERE allocation_id=923029"));
        result.put("grant", db.queryForMap("SELECT * FROM fams_requisition_receipt_grant WHERE allocation_id=923029"));
        return result;
    }

}
