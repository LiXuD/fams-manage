package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Populated legacy repair adoption and DDL constraints; actual business writes have a separate HTTP
 * suite.
 */
@EnabledIfSystemProperty(named = "d6.20.repair.factory.upgrade.runtime", matches = "true")
class RepairFactoryUpgradeRuntimeCase {
    @Test
    void populatedVersionFiftyOneFactsAndShapesSurviveAdditiveFactoryHistory() {
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(System.getenv());
        var source =
                new DriverManagerDataSource(
                        settings.url(), settings.username(), settings.password());
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration/d6_04")
                .target("51")
                .load()
                .migrate();
        var db = new JdbcTemplate(source);
        db.update(
                "INSERT INTO"
                    + " fams_organization(id,code,code_normalized,name,status,created_at,updated_at)"
                    + " VALUES (954520,'FACTORYUP52','FACTORYUP52','Owned legacy factory"
                    + " upgrade','ACTIVE',NOW(3),NOW(3))");
        try {
            seed(db);
            var facts = facts(db);
            var shapes = shapes(db);
            var next =
                    Flyway.configure()
                            .dataSource(source)
                            .locations("classpath:db/migration/d6_04")
                            .target("52")
                            .load();
            assertEquals(1, next.migrate().migrationsExecuted);
            assertEquals(0, next.migrate().migrationsExecuted);
            assertEquals(shapes, shapes(db));
            assertEquals(facts, facts(db));
            for (String table :
                    List.of(
                            "fams_repair_factory_scope",
                            "fams_repair_factory_round",
                            "fams_repair_factory_event",
                            "fams_repair_factory_evidence")) {
                assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
            }
            constraints(db);
            assertEquals(facts, facts(db));
        } finally {
            for (String table :
                    List.of(
                            "fams_repair_factory_evidence",
                            "fams_repair_factory_event",
                            "fams_repair_factory_round",
                            "fams_repair_factory_scope")) {
                var exists =
                        db.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                        + " table_schema=DATABASE() AND table_name=?",
                                Integer.class,
                                table);
                if (exists != null && exists > 0) {
                    db.update("DELETE FROM " + table + " WHERE repair_id=954520");
                }
            }
            db.update("DELETE FROM fams_todo WHERE business_type='REPAIR' AND business_id=954520");
            db.update("DELETE FROM fams_asset_business_lock WHERE asset_id=954520");
            for (String table :
                    List.of(
                            "fams_repair_physical_handling",
                            "fams_repair_decision",
                            "fams_repair_event",
                            "fams_repair_receipt")) {
                db.update("DELETE FROM " + table + " WHERE repair_id=954520");
            }
            db.update("DELETE FROM fams_repair_request WHERE id=954520");
            db.update("DELETE FROM fams_asset WHERE id=954520");
            db.update("DELETE FROM fams_business_parameter_version WHERE id=954520");
            db.update("DELETE FROM fams_location WHERE id=954520");
            db.update("DELETE FROM fams_warehouse WHERE id=954520");
            db.update("DELETE FROM fams_asset_category WHERE id=954520");
            db.update("DELETE FROM fams_account_role WHERE account_id=954520");
            db.update("DELETE FROM fams_system_account WHERE id=954520");
            db.update("DELETE FROM fams_employee WHERE id=954520");
            db.update("DELETE FROM fams_organization WHERE id=954520");
        }
    }

    private void seed(JdbcTemplate db) {
        db.update(
                "INSERT INTO"
                    + " fams_employee(id,employee_no,employee_no_normalized,name,organization_id,status,created_at,updated_at)"
                    + " VALUES (954520,'FACTORYUP52','FACTORYUP52','Owned legacy"
                    + " person',954520,'ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_system_account(id,employee_id,username,username_normalized,password_hash,status,initial_password_required,created_at,updated_at)"
                    + " VALUES"
                    + " (954520,954520,'factoryup52','FACTORYUP52','not-login','ENABLED',FALSE,NOW(3),NOW(3))");
        db.update(
                "INSERT INTO fams_account_role(account_id,role_code,granted_at) VALUES"
                        + " (954520,'ASSET_ADMIN',NOW(3)),(954520,'WAREHOUSE_OPERATOR',NOW(3))");
        for (String table : List.of("fams_asset_category", "fams_warehouse")) {
            db.update(
                    "INSERT INTO "
                            + table
                            + "(id,code,code_normalized,name,status,created_at,updated_at) VALUES"
                            + " (954520,'FACTORYUP52','FACTORYUP52','Owned legacy"
                            + " reference','ACTIVE',NOW(3),NOW(3))");
        }
        db.update(
                "INSERT INTO"
                    + " fams_location(id,code,code_normalized,name,location_type,warehouse_id,status,created_at,updated_at)"
                    + " VALUES (954520,'FACTORYUP52','FACTORYUP52','Owned legacy"
                    + " bin','BIN',954520,'ACTIVE',NOW(3),NOW(3))");
        db.update(
                "INSERT INTO"
                    + " fams_business_parameter_version(id,version,status,effective_at,actor_account_id,actor_username,snapshot_json,created_at)"
                    + " VALUES"
                    + " (954520,954520,'ACTIVE','2026-01-01',954520,'factoryup52','{\"timezone\":\"Asia/Shanghai\"}','2026-01-01')");
        db.update(
                "INSERT INTO"
                    + " fams_asset(id,asset_code,name,category_id,lifecycle_status,exception_status,card_stage,custody_type,"
                    + "storage_warehouse_id,current_location_id,created_business_date,last_inventory_business_date,parameter_version_id,"
                    + "creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by,version)"
                    + " VALUES (954520,'FACTORY-UP-52','Original legacy physical"
                    + " asset',954520,'UNDER_REPAIR','NORMAL','ACTIVE','STORAGE',"
                    + "954520,954520,'2026-01-01','2026-01-01',954520,954520,'factoryup52','2026-01-01','2026-01-02',954520,954520,1)");
        String original =
                "{\"id\":\"954520\",\"assetCode\":\"FACTORY-UP-52\",\"lifecycle\":\"IN_STOCK\",\"custodyType\":\"STORAGE\",\"warehouseId\":\"954520\",\"locationId\":\"954520\",\"version\":0}";
        String approved =
                "{\"allowedMethod\":\"INTERNAL_NO_COST\",\"reason\":\"Original immutable"
                        + " approval\"}";
        db.update(
                "INSERT INTO"
                    + " fams_repair_request(id,asset_id,creator_account_id,asset_version,source_snapshot_json,timezone_id,"
                    + "fault,impact,occurred_at,repair_status,allowed_method,approval_snapshot_json,processor_account_id,ownership_version,claimed_at,accepted_at,version,created_at,updated_at)"
                    + " VALUES (954520,954520,954520,1,?,'Asia/Shanghai','Legacy observed"
                    + " fault','HIGH','2026-01-01','WAREHOUSE_PROCESSING',"
                    + "'INTERNAL_NO_COST',?,954520,1,'2026-01-01','2026-01-02',3,'2026-01-01','2026-01-02')",
                original,
                approved);
        db.update(
                "INSERT INTO"
                    + " fams_repair_decision(repair_id,before_version,after_version,result,allowed_method,snapshot_json,reason,actor_account_id,recorded_at)"
                    + " VALUES (954520,0,1,'APPROVE','INTERNAL_NO_COST',?,'Legacy original"
                    + " decision',954520,'2026-01-01')",
                approved);
        db.update(
                "INSERT INTO"
                    + " fams_repair_physical_handling(repair_id,handler_employee_id,actual_location_id,location_version,before_asset_version,occurred_at,evidence,actor_account_id,recorded_at)"
                    + " VALUES (954520,954520,954520,0,0,'2026-01-02','Original legacy physical"
                    + " acceptance proof',954520,'2026-01-02')");
        db.update(
                "INSERT INTO"
                    + " fams_repair_event(repair_id,action,before_version,after_version,actor_account_id,actor_username,actor_role,detail_json,occurred_at)"
                    + " VALUES"
                    + " (954520,'REPAIR_ACCEPTED_AWAY',2,3,954520,'factoryup52','WAREHOUSE_OPERATOR','{\"original\":true}','2026-01-02')");
        db.update(
                "INSERT INTO"
                    + " fams_asset_business_lock(asset_id,business_type,business_id,business_item_id,acquired_at)"
                    + " VALUES (954520,'REPAIR',954520,954520,'2026-01-01')");
        db.update(
                "INSERT INTO"
                    + " fams_todo(pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,opened_at)"
                    + " VALUES"
                    + " ('WAREHOUSE_OPERATOR',954520,'REPAIR',954520,'PROCESS','OPEN','2026-01-02')");
    }

    private Map<String, Object> facts(JdbcTemplate db) {
        var result = new LinkedHashMap<String, Object>();
        for (String table : List.of("fams_asset", "fams_repair_request")) {
            result.put(table, db.queryForList("SELECT * FROM " + table + " WHERE id=954520"));
        }
        for (String table :
                List.of(
                        "fams_repair_decision",
                        "fams_repair_physical_handling",
                        "fams_repair_event")) {
            String order = "fams_repair_physical_handling".equals(table) ? "repair_id" : "id";
            result.put(
                    table,
                    db.queryForList(
                            "SELECT * FROM "
                                    + table
                                    + " WHERE repair_id=954520 ORDER BY "
                                    + order));
        }
        result.put(
                "lock",
                db.queryForList("SELECT * FROM fams_asset_business_lock WHERE asset_id=954520"));
        result.put(
                "todo",
                db.queryForList(
                        "SELECT * FROM fams_todo WHERE business_type='REPAIR' AND"
                                + " business_id=954520"));
        return result;
    }

    private List<Map<String, Object>> shapes(JdbcTemplate db) {
        return db.queryForList(
                "SELECT"
                    + " TABLE_NAME,COLUMN_NAME,ORDINAL_POSITION,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA"
                    + " FROM information_schema.columns WHERE table_schema=DATABASE() AND"
                    + " table_name NOT IN"
                    + " ('fams_repair_factory_scope','fams_repair_factory_round','fams_repair_factory_event','fams_repair_factory_evidence')"
                    + " ORDER BY TABLE_NAME,ORDINAL_POSITION");
    }

    private void constraints(JdbcTemplate db) {
        db.update(
                "INSERT INTO"
                    + " fams_repair_factory_scope(id,repair_id,scope_status,request_snapshot_json,request_reason,requested_by,requested_at,decision_snapshot_json,decision_reason,decided_by,decided_at,version)"
                    + " VALUES (954520,954520,'APPROVED','{}','DDL-only constraint"
                    + " fixture',954520,'2026-01-03','{}','DDL-only approval"
                    + " fixture',954520,'2026-01-03',1)");
        String insert =
                "INSERT INTO"
                    + " fams_repair_factory_round(repair_id,asset_id,scope_id,round_number,provider_name,service_contact,service_phone,expected_return_date,fault,original_identity_json,created_by,created_at)"
                    + " VALUES (954520,954520,954520,?,'DDL provider','DDL"
                    + " contact','123','2026-02-01','DDL-only fault','{}',954520,'2026-01-03')";
        db.update(insert, 1);
        assertThrows(DataIntegrityViolationException.class, () -> db.update(insert, 2));
        String receive =
                "UPDATE fams_repair_factory_round SET"
                    + " round_status='RECEIVED',dispatched_at='2026-01-04',dispatched_by=954520,dispatch_evidence='DDL-only"
                    + " dispatch"
                    + " proof',received_at='2026-01-05',received_by=954520,receive_location_id=954520,receive_location_version=0,condition_result='RESTORED',treatment_result='DDL-only"
                    + " result',actual_cost=? WHERE repair_id=954520 AND round_number=1";
        checkRejected(() -> db.update(receive, (Object) null));
        checkRejected(() -> db.update(receive, 1));
        assertEquals(
                "PENDING_DISPATCH",
                db.queryForObject(
                        "SELECT round_status FROM fams_repair_factory_round WHERE repair_id=954520"
                                + " AND round_number=1",
                        String.class));
        db.update(
                "UPDATE fams_repair_factory_round SET round_status='CANCELLED',cancel_reason='No"
                        + " physical dispatch happened' WHERE repair_id=954520 AND round_number=1");
        db.update(insert, 2);
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_repair_factory_round WHERE repair_id=954520",
                        Integer.class));
    }

    private void checkRejected(Runnable operation) {
        var error = assertThrows(DataAccessException.class, operation::run);
        var sql = assertInstanceOf(java.sql.SQLException.class, error.getMostSpecificCause());
        assertEquals(3819, sql.getErrorCode());
        assertTrue(sql.getMessage().contains("ck_factory_round_receipt"));
    }
}
